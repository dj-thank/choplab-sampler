# 音声契約

この文書の数値は再構築の受入目標です。測定結果は [ROADMAP](ROADMAP.md) が参照するCI/PRへ記録します。現行0.18.0のlinear interpolation、48 source-frame境界fade、0.9 knee/0.98 ceiling、PCM16 exportは既知の出発点であり、任意の曲で劣化やclickが測定済みという意味ではありません。

## 共通engineの契約

EngineCoreをAndroid、Windows、offline exportで共用します。内部は48kHz stereo float32、時間は音声frame、範囲は `[start, end)`。左右とfloat headroomを保ち、非有限値を拒否します。元bytesを残して不要な再圧縮/量子化を避け、16/24bit整数化の最後でだけTPDFディザを適用します。

初期案は32voice＋16fade枠。scratch/preview/metronomeも予算に含め、満杯時のsteal/release規則を試験します。Stop/Panicはqueue満杯でも消失させず、鳴り続けやstale voiceを防ぎます。1.5ms lookaheadと-1dBFS sample ceilingはlimiter候補であり、遅延・tailを全経路で扱います。

位置scratchは開始、時刻付き絶対source位置、CUT、終了を受けます。touch/mouse/将来jogをadapterで位置へ変換し、更新周期、補間、負速、端、idle silence、解放fadeを一つの実装で定義します。鳴っているPADをつかむ操作は、その位置から開始し、つかんだ時に再生中だったPAD voiceだけを一度だけ復帰させます。通常の8倍を超える移動は8倍で追って遅れて着き、動き始めと停止は2 msでfadeし、止めたまま離しても鳴らしません。

原曲のSOURCEとHANDは同じ不変PCMを共有し、再生位置・音量・CUT・操作所有は独立します。HANDを動かしてもSOURCEは再生を続け、HAND終了はSOURCEのseek・pause・resumeを発行しません。SOURCEのseek/playもHANDを移動させません。HANDは移動中だけ発音し、終了・取消・画面移動・出力切断で一度だけ解放します。SOURCE停止・交換・全停止はHANDも解放し、残る96frameのscalar fadeは旧PCMを保持しません。HANDはprimary32枠の1枠を予約し、満杯時はtyped拒否、CUTと専用gainは別に扱います。配置32＋primary32＋fade16＋SOURCE1の最大81同時readerと128MiBの常駐予算を維持します。原曲監視とHANDは書出しへ混ぜず、PADの加工・配置は制作のmix経路を使います。

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

帯域制限の取込resampleと可変pitch補間は別の問題です。取込は小窓の帯域制限resamplerを使い、AndroidのMediaCodec出力も私有float cacheへ渡す。元の圧縮bytesを保存用の正本として保持し、cacheを元資産の代わりにしない。長尺はresident全展開ではなくworkerのprefetchとpaged PCMを使う。renderのpage missはI/Oを起こさず無音・typed通知にし、Reloadは同じrevision/Undoを保持して自動再生しない。READY/FAILEDを制作hostに表示する。

3連の編集間隔は960PPQで8分3連320tick・16分3連160tick。STEP・選択PADのquantize・曲配置の吸着/Fillは同じ音楽tickを使い、終端を含めない。global swingは既存の全tickの単調変換を保ち、3連でも描画・吸着・配置・再生に同じ変換を適用する。straight（50%）では1拍を等しく3/6分割し、swing変更を無視する別の時刻軸は作らない。

全hostが共有する128MiBはmanaged PCMの一つの予算であり、resident/paged cache、prepared/queued/active program、SOURCE、workerの防御copy・resample窓・ring・出力窓を先に予約する。cacheとengineに128MiBずつ別枠を許さない。非active LRUを解放しても入らないときはtyped拒否し、active音源を追い出さない。renderでのlease返却は原子操作だけとし、providerの破棄・解放再試行はworkerで行う。Stop/取消/遅着/終了でも所有を返す。これはJVM/RSS・OS codec・推論modelのactivationの総RAM上限ではない。新しいTTS/comp/音声driverもこの予算に接続した証拠を各sliceで得る。

native資産の30,000,000frame（48kHzで625秒）と資産/disk scratchの1GiB上限は別に維持する。400秒のproduction通しと44.1/48/96kHzの精度oracleは候補revisionの証拠であり、全上限・全端末の性能を保証しない。renderにはfile/network I/O、allocation、blocking lock、logを入れない。固定条件の性能結果と実端末の10分underrunは分けて[ROADMAP](ROADMAP.md)に示す。

## 録音・遅延

入力event時刻、要求frame、適用frame、出力遅延、往復実測、手動補正を分けます。OS timestampだけで指先から耳までの遅延を断定しません。固定60ms補正をroute別測定へ置換し、時計領域、長時間drift、route/format変更と補正失効を扱います。

ボーカルの±5msは指定した有線/USB等のroute・rate・bufferで補正後の目標です。反復数・p95/最大・測定誤差を添え、Bluetoothや全端末への保証にしません。UNPROCESSED/VOICE_PERFORMANCEは対応確認とfallbackを実装。punch/compのcrossfadeは10msを初期案とし短区間でclampします。

take/compは原曲の48kHz絶対frameで区間を保持し、歌詞行や通常clipの後編集で候補を失わない。compの同じ時刻の2takeを最大10msのconstant-sumでつなぎ、先頭・末尾・短区間ではfadeをclampする。workerは最大4096frameずつ読む。全takeをresident化せず、元音と原録音bytesを保持し、明示確定したrender資産だけを通常graphへ渡す。共有PCMへのcomp窓・入力route bufferの予約と、実routeの補正/聴感は別々に検証する。

clickは共有engineのmonitorだけに出し、通常のWAV書出しgraphへ混ぜません。マイクが室内のmonitor音を拾うかどうかは実routeで別に確認します。count-inは0/1/2小節、四分音符と小節頭を同じ音声clockで鳴らし、開始cueまで曲のsequence frameを進めません。Stop/Pause/Seek/tempo変更・program交換・出力喪失でcueを取り消し、export engineはmonitor専用命令を拒否します。click用slotも既存32voice予算内に予約します。

マイクを先にarmedにして、cueより前のsamplesは録音fileへ入れません。準備待ちの上限20秒と録音の最大5分を分けます。permission/command拒否・遅着・取消はscratchを破棄し、PADの開始前押下も制作へ記録しません。engine cueからhost出力時刻への変換はdriverの推定であり、入力遅延・往復補正や±5msの実測合格ではありません。

## ミックス・声・分離

- WSOLAはstretch、YIN/PSOLAは単音voiceのpitch補正候補。子音・無声・低信頼・低音・急変・倍半分誤検出を評価し、不成立なら原音を保つ。非破壊A/B、キー/スケール、retune/vibratoを明示する。
- FXはgain/pan/mute/solo、EQ/filter/comp/delay/reverb send/masterを共有graphで処理。latency compensation、tail、loop折返し、solo/muteの意味を固定。post-fader/pre-master等stem出力点を表示し、非線形master後の和が一致するとは約束しない。
- 4パート分離は固定した単一HT-Demucsのdrums/bass/other/vocalsを使う（[NOTICE](../NOTICE.md)）。44.1kHz・7.8秒/343,980frameと1/4重複の逐次OLA、実tensor `[1,4,2,343980]` とfinite値を検証する。4 WAVの全header/hash/合計quotaを先確認し、同じAssetStore lock下でpublish、失敗時は今回新規hashだけ戻す。元bytes・既存同hashを保持。文書は後段の明示SetArrangement/expectedRevisionで1Undoにし、準備だけでは変更しない。crashで未参照immutable資産が残り得るが、部分文書を確定しない。
- 4stemのORTはCPU1thread/NO_OPT/arena・memory pattern・prepacking無効。入出力/OLA/copyは共有128MiB PCMへ予約し、model activation/RSSは別のlive RAM preflightでtotal3.5GiB/available1.5GiBとlowMemory/unknown拒否を確認する。Macは即時freeだけでは再利用可能メモリを除外するため、source付きのfresh available estimateとpressureを別に確認し、閾値を下げない。fp16 weightsだけでRAM半減を主張しない。実workerの数値とHuman音質、Android/Windows実model受入を分ける。
- AndroidはAudioTrack、Windowsの既存hostは連続Java Sound。WASAPIはshared event駆動の専用STAに出力/マイク/通常global-mix loopbackを所有させ、Get/Releaseとcloseを同じworkerで行う。clientは48kHz stereo FLOAT32、OS共有変換を明示し、loopbackはWindows10 build15063以降のdefault render全mix（自分の出力を含む、OS保護に従う）。別endpoint/マイクへの自動fallback・自動retryを行わない。
- WASAPIのring/endpoint/scratchは共有PCMへInitialize前に予約し実buffer後に縮小する。非協力workerは実finallyまでslot/予算を保持し同mode再openをBUSYで拒否、代替routeの明示選択は解放確認後に限る。capture gap/timestamp/overflow/device lossをtyped中断とし、QPC100nsをSystem.nanoTimeや往復実測補正と同一視しない。hostのroute変更通知と校正失効は別の接続受入。endpoint probe・短いnative stream・長時間/実音/Humanを別証拠にする。

既存のchannel/PCM oracleは [ADR3](adr/ADR-0003-audio-parity-primitives.md) と [ADR4](adr/ADR-0004-pattern-master-parity-gate.md)、Windows調査は [WASAPI research](research/windows-wasapi-jna-2026-08-20.md) に残します。
