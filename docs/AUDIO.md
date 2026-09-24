# 音声契約

この文書の数値は再構築の受入目標です。測定結果は [ROADMAP](ROADMAP.md) が参照するCI/PRへ記録します。現行0.18.0のlinear interpolation、48 source-frame境界fade、0.9 knee/0.98 ceiling、PCM16 exportは既知の出発点であり、任意の曲で劣化やclickが測定済みという意味ではありません。

## 共通engineの契約

EngineCoreをAndroid、Windows、offline exportで共用します。内部は48kHz stereo float32、時間は音声frame、範囲は `[start, end)`。左右とfloat headroomを保ち、非有限値を拒否します。元bytesを残して不要な再圧縮/量子化を避け、16/24bit整数化の最後でだけTPDFディザを適用します。

初期案は32voice＋16fade枠。scratch/preview/metronomeも予算に含め、満杯時のsteal/release規則を試験します。Stop/Panicはqueue満杯でも消失させず、鳴り続けやstale voiceを防ぎます。1.5ms lookaheadと-1dBFS sample ceilingはlimiter候補であり、遅延・tailを全経路で扱います。

位置scratchは開始、時刻付き絶対source位置、CUT、終了を受けます。touch/mouse/将来jogをadapterで位置へ変換し、更新周期、補間、負速、端、idle silence、解放fadeと元transportの復帰を一つの実装で定義します。

## 固定するfixtureと試験

段階2A着手前にrevision、seed、rate、level、channel、初期状態、命令、測定窓を固定します。合成/許諾素材を使い、結果を見て都合のよい条件だけを選び直しません。

| 試験 | 受入と測定条件 |
|---|---|
| Renderの分割一致 | 同一runtime・PCM・命令frame・Program変更・seedでblock `{1,17,96,192,480,4096}` の出力bit一致。block途中のtrigger/stop/変更も含む。ART/JVM横断は別比較し、差がある場合は上限と理由をADRで承認 |
| Resample | 44.1↔48、96→48kHz。Nyquistに応じpass/transition/stop帯域を指定。初期pass20Hz–20kHzで±0.05dB、aliasを生むstop帯100dB以上を目標。sweep/multi-toneとgroup-delay補正。transition幅0は要求しない |
| Variable pitch | +12半音のcutoff追従を試す。0.1FS sweep/multi-tone、許可帯域の基本波等を基準にfold-back≤-70dBを目標。基本波が消える領域はdBFSも併記。tap数だけで合格にしない |
| Loop / click | 位相連続tone、DC差、短loop、stereo、pitch、retrigger/choke/steal/stopを別fixtureに。期待envelopeとの差でtone境界残差≤-80dBFS、過渡click残差peak≤-60dBFSを目標。crossfadeで周期を短縮しない |
| Master | 過大入力、重ね合わせ、release、全停止、NaN/Infを試す。sample peak -1dBFS初期目標。true peakを名乗るならinter-sample peakを別評価。exportはlookaheadを除去しtailをflush |
| Timing | 40–240BPMの整数/小数、swing、pattern/tempo切替を1時間分の有理数referenceと比較し累積ずれ0frame。late/overflow注入とStop保全。DAC clock誤差ゼロの主張にはしない |
| Allocation / CPU | warm-up後1万blockのrender thread増分割当をharness/UIから分離して測る。Pixel32voice＋fade/scratch/metronomeでp99<block期間25%を初期目標。最大、10分underrun、冷間/熱時、計測制約を記録 |
| Channels / export | 左右非対称/逆相/mono/headroom、16/24bit端値、ディザ分布/相関/seed、frame数/範囲/tail、保存→再読込。stemsは出力点を固定してpre-master和と比較 |
| Listening A/B | 同じ拍/長さ/処理の新旧を同一ラウドネス基準で合わせ、無処理比較も保持。数値合格と人間の音質評価を別記録 |

帯域制限の取込resampleと可変pitch補間は別の問題です。sincのtap/phase数は品質・CPUで採用を決めます。5分48kHz stereo floatは115.2MBなので、メモリ上限とprefetchなしで長尺を増やしません。renderにはfile/network I/O、allocation、blocking lock、logを入れません。

## 録音・遅延

入力event時刻、要求frame、適用frame、出力遅延、往復実測、手動補正を分けます。OS timestampだけで指先から耳までの遅延を断定しません。固定60ms補正をroute別測定へ置換し、時計領域、長時間drift、route/format変更と補正失効を扱います。

ボーカルの±5msは指定した有線/USB等のroute・rate・bufferで補正後の目標です。反復数・p95/最大・測定誤差を添え、Bluetoothや全端末への保証にしません。UNPROCESSED/VOICE_PERFORMANCEは対応確認とfallbackを実装。punch/compのcrossfadeは10msを初期案とし短区間でclampします。

## ミックス・声・分離

- WSOLAはstretch、YIN/PSOLAは単音voiceのpitch補正候補。子音・無声・低信頼・低音・急変・倍半分誤検出を評価し、不成立なら原音を保つ。非破壊A/B、キー/スケール、retune/vibratoを明示する。
- FXはgain/pan/mute/solo、EQ/filter/comp/delay/reverb send/masterを共有graphで処理。latency compensation、tail、loop折返し、solo/muteの意味を固定。post-fader/pre-master等stem出力点を表示し、非線形master後の和が一致するとは約束しない。
- 4パート分離はdrums/bass/other/vocalsの順とshapeを実modelで検証。44.1kHz・7.8秒単位のstreaming、4出力のtransaction確定、取消/容量不足/空きRAM/ORT設定を両OSで試す。fp16 weightsだけでruntime RAM半減とは言わない。
- AndroidはAudioTrack、Windowsはまず連続Java Sound、段階11でWASAPI event駆動の出力/入力/loopbackを試す。endpoint probe成功は実音やloopback成功の証拠ではない。

既存のchannel/PCM oracleは [ADR3](adr/ADR-0003-audio-parity-primitives.md) と [ADR4](adr/ADR-0004-pattern-master-parity-gate.md)、Windows調査は [WASAPI research](research/windows-wasapi-jna-2026-08-20.md) に残します。
