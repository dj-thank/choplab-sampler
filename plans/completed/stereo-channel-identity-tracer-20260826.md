# Preserve stereo channel identity end to end

## Purpose and user-visible outcome

左右の異なるステレオ素材をChopLabへ取り込んだとき、知らないうちに中央monoへ畳まず、元曲再生、PAD再生、project保存/再読込、Pattern/Song WAV書出しまで左右を保つ。mono素材と旧projectは従来どおり扱い、画面上のrange/playheadは常にaudio frameを指す。

## Current state

- exact base: `d6c22434f1bfd9fa5bc505717d0be4fa4a552a3d` / tree `85ffa6d8b44f06eb60fbd5e37aba2ddd761160eb`。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-stereo-tracer-20260826`。
- shared `PcmBuffer` は1–2ch interleavedを表せるが、MVP `PcmAudio` と各adapterはmono前提。
- Android/Windows decoderは2chを平均し、Android realtime/Windows Clip/offline renderer/archiveはmono固定。
- schema 6までのarchive audio行はchannelCountを持たず、embedded WAVはstrict mono。

## Constraints and invariants

- `PcmAudio.samples` をinterleaved PCM16、`frameCount`をper-channel frame数として明文化する。constructor defaultは1chで既存mono call siteを保つ。
- 対応保存形は1ch/2chのみ。3–8ch importは従来の平均mono、archiveは1/2ch以外を拒否する。
- 既存range、slice、trim、playhead、durationはframe単位を維持する。
- mono-only render/archiveのchannel countとdata lengthを保持する。
- schema 1–6はchannelCount=1と解釈し、schema 7だけがmanifest channelCountとgeneric PCM16 WAVを使用する。
- realtime callbackはpreallocated mutable stereo frameを使い、per-frame allocation/lock/I/Oを追加しない。
- channel-aware PCM byte上限、partial-frame、manifest/header mismatchはfail closed。
- device/provider/public/signing/secret/Human gateはscope外。

## Architecture and interfaces

`PcmAudio` をMVP channel truthの唯一の入口にし、`sampleAt(frame, channel)`、`playbackSampleAt(frame, outputChannel)`、`monoSampleAt(frame)`を提供する。DSP position/filter/rangeはframe単位で一度だけ進み、left/rightは同じpositionから別々に補間・filterする。

Android realtimeは再利用可能なprimitive `MutableStereoFrame`へrenderし、source/scratch/PAD mixをleft/right別にsoft-limitする。host/offlineは`RenderedPcm(samples, channelCount, frameCount)`を使い、Windows formatとWAV writerへchannelCountを渡す。視覚/解析は`monoSampleAt`へ明示投影する。

Archive schema 7ではaudio manifestへchannelCountを追加し、generic `Pcm16WavCodec`が1/2ch header、interleaved sample count、declared frame countを相互検証する。旧schemaのparser/codec pathはmono固定で残す。

## Milestones

### Milestone 1: Frame/channel domain and decoder RED/GREEN

- Scope: `PcmAudio` contract、Android PCM conversion helper、Windows streaming decoder、frame-aware resource limits。
- Tests: asymmetric stereo、mono、3ch downmix、partial frame、known/unknown oversize、duration、per-channel DC projection。
- Acceptance: decode resultの`channelCount/frameCount/samples`がfixtureと一致し、旧mono testsもgreen。

### Milestone 2: Schema 7 save/reopen and compatibility

- Scope: manifest channelCount、generic PCM16 WAV、resident byte accounting、legacy schema 1–6 read path。
- Tests: stereo round-trip、mono round-trip、legacy fixture、header/manifest mismatch、partial frame、duplicate-ID channel mismatch、budget boundary。
- Acceptance:左右非対称fixtureがexact round-tripし、旧fixtureはmonoのまま、negative controlsはfail closed。

### Milestone 3: Live/offline/Windows playback identity

- Scope: Android source/PAD/scratch voice、host PAD renderer、Pattern/Song renderer、Windows Clip/scratch format。
- Tests: stereo interpolation/filter/reverse、terminal sample、mono duplication、offline WAV header/data、Android/offline左右別PCM parity。
- Acceptance:同一fixture・controlsで左右各channelの最大PCM差が許容範囲内。mono-only WAVは1chを維持。

### Milestone 4: Analysis projections and repository closeout

- Scope: waveform、transient、zero crossing、all direct sample indexing、SSOT/read-back。
- Tests: stereo frame geometry、channel-average analysis、full configured test gate、package/SBOM/public-surface、diff/status。
- Acceptance: exact HEAD/tree、test counts、artifact hashes、dirty preservation、remaining external gatesを記録し、planをcompletedへ移す。

## Progress

- [x] 2026-08-26T23:33+09:00 — Wave 9 closeoutをexact baseとして専用worktreeを作成し、mono assumptionsとchannel-sensitive call sitesをinventory。
- [x] 2026-08-26T23:39+09:00 — Milestone 1 RED/GREEN。shared frame/channel contract、Android PCM conversion、Windows streaming decodeを左右非対称fixtureで固定。
- [x] 2026-08-26T23:49+09:00 — Milestone 2 RED/GREEN。schema 7 channel manifest、strict mono/stereo WAV、channel-aware memory budget、schema 1–6 mono migrationを固定。
- [x] 2026-08-27T00:03+09:00 — Milestone 3 RED/GREEN。Android realtime source/PAD/scratch、host PAD、Pattern/Song WAV、Windows Clip/scratchを左右別frameへ接続。
- [x] 2026-08-27T00:04+09:00 — Milestone 4 analysis substep。waveform、trim overview、PAD/timeline peaks、zero crossing、transientをframe-based mono projectionへ統一。
- [x] 2026-08-27T00:24+09:00 — Milestone 4 full gate / closeout。review修正後のexact product checkpointで197-task／537-test gate、package、release/SBOM/public-surface、bytecode、Standards/Spec read-backを完了。

## Discoveries

- `PcmAudio` constructorへdefault channelCountを足すだけなら大半のmono fixturesはsource-compatibleだが、direct `samples[index]` はframe semanticsを破るため明示修正が必要。
- Android `AudioTrack` 自体は既にstereo float output。現在はmix後にmonoを左右複製しているため、device API変更なしにchannel identityを通せる。
- mono-only Pattern export bytesはoutput channel countをassigned audioの最大channelCountから決めれば維持できる。
- Android decoderはoutput format確定前にbuilderを作るとproviderのformat変更でchannel shapeを誤るため、最初のPCM bufferまでbuilder生成を遅延し、その後のsample rate/channel shape変更を拒否する。
- 新規worktreeには`local.properties`が無いため、Android testはファイル保存ではなくprocess-local `ANDROID_HOME`で既存SDKを指定する。
- current writerからlegacy fixtureを導出するtestはschema headerだけでなくschema 7のaudio channel fieldも除去する必要がある。旧parser自体は7-field audio行をそのまま維持した。
- duplicate audio IDの同一性にはname/rate/samplesだけでなくchannelCountが必要。同じinterleaved bytesでもmonoとstereoではframe truthが異なる。
- Android realtimeは既存stereo `AudioTrack`を維持し、callback開始時に3個のmutable stereo frameだけを確保する。各sampleではpositionを一度だけ進め、L/R filterとmaster limiterを独立適用する。
- offline WAVのchannel countは実際のrender eventに含まれる最大channelCountから決めるため、mono-only projectの1ch header/data bytesを維持できる。
- Windows Clipへ渡すpure PCM stream seamを分離すると、音声deviceなしで2ch format、4-byte frame size、interleaving、frame lengthをread-backできる。
- interleaved sample indexをframeとして扱うと、zero crossingは2倍位置へずれ、波形は後半半分を見ず、transientの時間軸も崩れる。visual/analysisだけは左右平均を明示し、audible pathの左右identityとは分離した。
- Review時、PCM開始後の二度目のcodec format-changeがzero-size EOSで終わると後続buffer側の安定性checkへ到達しないedgeを発見した。format-change通知時点でもstored channel shape/sample rateを検証し、データ有無に依存せずfail closedにした。

## Decision log

- 2026-08-26T23:33+09:00 — verifier連続投資を止め、製品体験へ戻すWaveとしてstereo tracerを選択。
- 2026-08-26T23:33+09:00 — multichannel layout推測はせず、1/2chのみ保持し3–8chは既存互換の平均mono。
- 2026-08-26T23:33+09:00 — pan/stems/UI mixerは同時実装しない。channel identityが成立してから別waveで判断する。

## Validation log

- `:shared:desktopTest --tests com.choplab.sampler.model.PcmAudioChannelTest`
  - RED: `channelCount` / frame access API未実装でtest compile failure。
  - GREEN: interleaved stereo frameCount/duration/access、mono playback projection、partial/unsupported channel rejection PASS。
- `:shared:desktopTest :desktop:test :app:testDebugUnitTest`
  - shared/desktop GREEN。app test compileはtest helperのJDK `Buffer` return型だけで停止し、production compileはPASS。
- `:app:testDebugUnitTest --tests com.choplab.sampler.audio.Pcm16ArrayBuilderTest`
  - GREEN: frame-aware stereo capacity、PCM16 stereo preservation、3ch average-mono、partial frame/layout change rejection PASS。
- `:jvm-core:test --tests '*ProjectArchiveCodecTest.schemaSevenRoundTripPreservesAsymmetricStereoAndWavShape'`
  - RED: schema 6 mono codecが6 interleaved samplesを3 mono framesとしてread-backできずreject。
- `:shared:desktopTest :jvm-core:test`
  - GREEN: schema 7 stereo exact round-trip、WAV 2ch/block-align/data size、schema 1–6 mono migration、manifest/header mismatch、resident budget、duplicate-ID channel mismatchを含む全shared/JVM-core tests PASS。
- `:jvm-core:test --tests '*PadPcmRendererTest.asymmetricStereoRendersInterleavedWithoutChannelCollapse' --tests '*PatternRendererTest.stereoPatternMasterPreservesLeftAndRightWhileMonoMastersStayMono'`
  - RED: host PAD APIとPattern summaryがmono shapeしか持たずtest compile failure。
  - GREEN: interleaved host render、2ch WAV header/block-align/data、左右符号identity PASS。
- `:app:testDebugUnitTest --tests '*SamplerEngineVoiceTest.realtimeVoicePreservesStereoAndDuplicatesMonoWithoutAdvancingTwice' :desktop:test`
  - GREEN: realtime stereo/mono projection、single frame advance、Windows full suite PASS。
- `:app:testDebugUnitTest --tests '*PatternMasterParityTest.asymmetricStereoFullBarMatchesRealtimeVoiceAndMasterPerChannel'`
  - GREEN: asymmetric stereo full-bar Android/offline master parity、左右各sampleの最大delta 1以下。
- `:desktop:test --tests '*JavaSoundPcmInputTest'`
  - GREEN: Windows PCM handoffは2ch、frame size 4、frameLength 2、interleaved bytes exact。
- `:shared:desktopTest --tests '*ProductionCommandTest.stereoZeroCrossingUsesAudioFramesInsteadOfInterleavedSampleOffsets'`
  - RED: direct sample indexingがframe 100のcrossingをsample offset 200として返却。
- `:shared:desktopTest ... :app:testDebugUnitTest --tests stereo analysis fixtures`
  - GREEN: zero crossing frame 100、waveform後半bucketの±peak、stereo transientの4 onset frameを保持。
- `:app:testDebugUnitTest --tests Pcm16ArrayBuilderTest` + focused mono Pattern control
  - GREEN: PCM開始後のsample-rate／stored-channel driftをzero-size EOS前でもreject。mono-only exportは1ch、block align 2、frame×2-byte data lengthを維持。
- clean configured Gradle gate
  - Product checkpoint `66d3911f57dfb56baed682cf8c0ec9a0aed85164` / tree `e60216ab70ef540f48815524e0b645de16817007`。
  - `BUILD SUCCESSFUL in 4m 31s`; 197 tasks（192 executed / 5 up-to-date）。537 tests / 95 suites、failure/error/skip 0。debug/release Lintはfatal/error 0、warning 7。
  - Android 264、shared Android/Desktop 58/58、JVM-core 68、Desktop 89。
- release/package/policy read-back
  - unsigned release APK 24,192,116 bytes / `7F180B3A48452179B3277D8FA3633820E6C093B8A759CD43E3B4464D6259016A`。aapt2 manifest/security/alignment/unsigned policy PASS、`--require-signed` expected exit 1。
  - Windows ProductVersion `0.17.0`; app-image 405 files / 176,614,704 bytes / manifest `9F97268C312E570D5B051C5B5BB06F7A142EE42EE610BE2ADB3FE202A186CB2A`。
  - CycloneDX 1.6、650 components / 651 dependencies、1,581,101 bytes / `EA59D6FD8BA9B87C206F35396A2B21B98673DDE637CFC55B8FACB8AD984BCD39`。
  - configured validation 18 tasks、Python policy 59、reachable-history public surface 429、`git diff --check` PASS。
- release bytecode / review
  - output array 1と`MutableStereoFrame` 3個はouter loop前。steady sample loopの`new` 0、renderLoopのmonitor 0、`java.io`/`java.nio` reference 0。唯一の後段`new`はAudioTrack write failure exception。
  - Standards/Spec unresolved finding 0/0。

## Risks and rollback

最大のriskはsample countとframe countの混同による範囲/容量/drift、またはschema migrationの破壊。左右非対称fixture、legacy archive、partial-frame/oversize negative controlsで各境界を反証する。rollbackはこのisolated branchを採用しないことだけで、canonical dirty checkoutとWave 9成果物は不変。

## Remaining device validation

物理端末での左右出力、route/focus、Bluetooth/USB、音質、latency、TalkBack、実音聴取はこのLOCAL sliceでは証明しない。Pixel/ADB、provider/public、署名、Human GOは別の権限付きtaskで扱う。
