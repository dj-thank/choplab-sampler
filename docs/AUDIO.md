# 音声契約

この文書の数値は再構築の受入目標です。測定結果は [ROADMAP](ROADMAP.md) が参照するCI/PRへ記録します。現行0.18.0のlinear interpolation、48 source-frame境界fade、0.9 knee/0.98 ceiling、PCM16 exportは既知の出発点であり、任意の曲で劣化やclickが測定済みという意味ではありません。

## 共通engineの契約

EngineCoreをAndroid、Windows、offline exportで共用します。内部は48kHz stereo float32、時間は音声frame、範囲は `[start, end)`。左右とfloat headroomを保ち、非有限値を拒否します。元bytesを残して不要な再圧縮/量子化を避け、16/24bit整数化の最後でだけTPDFディザを適用します。

初期案は32voice＋16fade枠。scratch/preview/metronomeも予算に含め、満杯時のsteal/release規則を試験します。Stop/Panicはqueue満杯でも消失させず、鳴り続けやstale voiceを防ぎます。1.5ms lookaheadと-1dBFS sample ceilingはlimiter候補であり、遅延・tailを全経路で扱います。

note repeatは押したframeを起点に、曲のtempo/swingと同じ有理数clockで4/8/16/32分・8/16分3連を刻む。保持PADごとに既存primary内の2枠を予約し、前の発音を96frameで解放しながら次を始める。指を離すとPADのrelease、停止・画面移動・program/tempo変更・出力喪失で保持を終える。読み上げ・Enter/Spaceの単一操作はengine側で1拍に制限し、UI timerを発音clockにしない。録音では同じclock・stereo/envelope/加工のpre-masterフレーズを不変float資産にし、quantizeはフレーズ先頭へ適用、録音1回=1Undoで曲へ置く。曲と書出しはその同じ資産を使い、元音とPAD設定を保持する。長尺入力は小窓で読み、出力PCMを共有128MiBから先に予約する。

位置scratchは開始、時刻付き絶対source位置、CUT、終了を受けます。touch/mouse/将来jogをadapterで位置へ変換し、更新周期、補間、負速、端、idle silence、解放fadeを一つの実装で定義します。鳴っているPADをつかむ操作は、その位置から開始し、つかんだ時に再生中だったPAD voiceだけを一度だけ復帰させます。通常の8倍を超える移動は8倍で追って遅れて着き、動き始めと停止は2 msでfadeし、止めたまま離しても鳴らしません。

原曲のSOURCEとHANDは同じ不変PCMを共有し、再生位置・音量・CUT・操作所有は独立します。HANDを動かしてもSOURCEは再生を続け、HAND終了はSOURCEのseek・pause・resumeを発行しません。SOURCEのseek/playもHANDを移動させません。HANDは移動中だけ発音し、終了・取消・画面移動・出力切断で一度だけ解放します。SOURCE停止・交換・全停止はHANDも解放し、残る96frameのscalar fadeは旧PCMを保持しません。HANDはprimary32枠の1枠を予約し、満杯時はtyped拒否、CUTと専用gainは別に扱います。配置32＋primary32＋fade16＋SOURCE1の最大81同時readerと128MiBの常駐予算を維持します。原曲監視とHANDは書出しへ混ぜず、PADの加工・配置は制作のmix経路を使います。

出力の喪失・ライフサイクル解放・明示再接続でengineを作り直すときは、最後に受付確認されたSOURCE・HAND・曲の監視音量を初期値として保持する。消音を含め、再接続の最初のblockから100%へ戻さない。拒否・取消された音量命令は保持値へ昇格せず、声・SOURCE・sequenceは停止したままにする。監視音量はruntimeだけの設定で、制作文書・Undo・通常書出しgraphへ保存しない。

## 自動チョップ

自動チョップは選択済みnative範囲への提案とし、等分1–128区間、またはアタック検出の最大区間数・最小音量（dBFS）・最小間隔を明示する。48kHz解析PCMの左右powerを使い、逆相で相殺しない。1ms hopのenergy上昇と直前32msの背景との差から候補を作る。音量しきい値は品質確率ではない。無音・持続音で内部の切れ目が見つからない場合、過密で上限を超える場合、設定に対して短すぎる場合は全候補を拒否する。最大127marker、start-inclusive/end-exclusiveを保ち、48kHzへの範囲変換はstartをceil、endをfloorとする。

解析はworkerでPCM leaseを保持し、4096frame以下の読出窓と検出scratchを共有128MiBへ予約する。取消後も実読出しが戻るまでその窓の予約とworker枠を返さない。生成・設定変更・試聴は文書と元bytesを変更せず、明示適用だけが切れ目を1Undoで置換する。PADは別の明示割当で区間を参照する。古revision、録音中、別の制作処理中は適用を拒否する。区間試聴は共通SOURCEのCHOP ownerを取得し、engineの範囲終端で停止、終了時に元の位置・ピッチ・音量へ戻して停止を保つ。HAND・他のSOURCEツールと所有を競合させず、制作音声や書出しへ混ぜない。検出の聴感評価と物理入力遅延は別受入とする。

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

## SOURCEのBPM・キー候補

解析はworkerで、再生pitchを変える前の選択範囲の先頭最大30秒を扱う。native範囲を48kHzへstart切上げ/end切下げで写し、左右を測定後に合成して逆相打消しを避ける。4秒未満では候補を出さない。BPMは50ms RMSの10ms間隔増分と自己相関から40–240の候補を求め、半分/倍の曖昧さを含め最大3件にする。キーは8192frameの窓を100ms間隔で測定し、音程のあるピークのchromaとmajor/minor profileの相関から最大3件にする。相関は確率ではない。雑音・単音・短い打音など根拠不足では棄権し、範囲の候補を曲全体の確定キーとしない。

SOURCEと同じPCM leaseを使い、作業配列と4096frame以下の読取窓の1MiBを共有128MiBから先に予約する。足りなければactive音源を保って拒否する。取消・遅着・終了で予約/leaseを返し、編集中のrevision変更や録音/別処理中は適用を拒否する。候補選択では編集せず、BPMの明示適用だけをexpectedRevision付き1編集にし、swing・原音・配置を保持する。合成tempo/chord、逆相、無音/雑音/単音、native範囲、予算不足、取消/stale、Undo/archive/exportをローカルで確かめる。実曲の検出品質と端末の処理時間/実音は別受入とする。

## 録音・遅延

入力event時刻、要求frame、適用frame、出力遅延、往復実測、手動補正を分けます。OS timestampだけで指先から耳までの遅延を断定しません。LiveChopの固定60msはroute別の出力推定または明示手動値へ置換します。往復実測と長時間driftは別の実route検証で確認します。

LiveChopは押下callbackの単調時刻、coherentなengine/SOURCE位置、出力待ちframeと未書込frame、limiter lookaheadを同時点で読み、native資産rateと試聴pitchに換算して切断する。部分書込み済みframeを未書込分へ重ねて数えない。`ESTIMATED`は出力の報告値による推定、`MANUAL`は明示した合計遅延0–1000msであり、物理往復実測と区別する。報告のない遅延は不明のまま保持し、推定モードの開始を拒否する。同じ出力session・clock・形式・bufferを確認できる一時的なreadout競合は型で区別し、手動補正を消さない。その読み取りは位置や遅延を持たず、新しい切断には使わない。出力の喪失・route/clock/形式/buffer変更は補正を失効させる。押下callback時刻はOSの物理タッチ時刻ではなく、UI配送遅延も実測対象に残る。

route識別はsession内の不透明tokenだけを使い、device IDや補正をprojectへ保存しない。新出力、engine clock、format/rate/channels、buffer/block、adapterが報告したclock世代変更で手動補正と実行中passを失効する。Androidはtimestamp喪失・復帰・逆行、JavaSoundは報告position逆行を通知し、WASAPIのendpoint無効化は既存出力終了と明示再openで新tokenになる。OSが報告しない物理route変化やdriftを検出済みとはしない。押下からreleaseまでのpass/revision/route不一致、取消、録音・準備中は切断を追加しない。適用したnative切断frameは範囲先頭以上へclampし、endはexclusive、1押下1Undoと元bytesの保存再開を保つ。要求/適用cut frameはengine commandの適用receiptとは別に扱う。

ボーカルの±5msは指定した有線/USB等のroute・rate・bufferで補正後の目標です。反復数・p95/最大・測定誤差を添え、Bluetoothや全端末への保証にしません。UNPROCESSED/VOICE_PERFORMANCEは対応確認とfallbackを実装。punch/compのcrossfadeは10msを初期案とし短区間でclampします。

take/compは原曲の48kHz絶対frameで区間を保持し、歌詞行や通常clipの後編集で候補を失わない。compの同じ時刻の2takeを最大10msのconstant-sumでつなぎ、先頭・末尾・短区間ではfadeをclampする。workerは最大4096frameずつ読む。全takeをresident化せず、元音と原録音bytesを保持し、明示確定したrender資産だけを通常graphへ渡す。共有PCMへのcomp窓・入力route bufferの予約と、実routeの補正/聴感は別々に検証する。

clickは共有engineのmonitorだけに出し、通常のWAV書出しgraphへ混ぜません。マイクが室内のmonitor音を拾うかどうかは実routeで別に確認します。count-inは0/1/2小節、四分音符と小節頭を同じ音声clockで鳴らし、開始cueまで曲のsequence frameを進めません。Stop/Pause/Seek/tempo変更・program交換・出力喪失でcueを取り消し、export engineはmonitor専用命令を拒否します。click用slotも既存32voice予算内に予約します。

マイクを先にarmedにして、cueより前のsamplesは録音fileへ入れません。準備待ちの上限20秒と録音の最大5分を分けます。permission/command拒否・遅着・取消はscratchを破棄し、PADの開始前押下も制作へ記録しません。engine cueからhost出力時刻への変換はdriverの推定であり、入力遅延・往復補正や±5msの実測合格ではありません。

## ミックス・声・分離

- BEATのWSOLAはworkerで素材BPM÷曲BPMの長さへ変換する。両channelで共通の相関位置を選び、headroomをclipしない。最大2048frameの窓/半窓hop/256frame探索を使い、読取・出力は4096frame以下、DSP窓と返却窓/書込/検証bufferの180,224bytesを共有PCMへ先予約する。全体PCM/出力長に比例した配列を持たず、長尺もpaged leaseで処理する。RAM・asset/project上限・保存容量・共有disk scratchを満たせなければ全体をtyped拒否する。取消したworkerの実finallyまでslotを保ち、遅着・部分結果から文書を確定しない。
- WSOLAの元範囲はnative `[start,end)` を48kHzへstart ceil/end floorで内側に変換し、結果はround(inputFrames×素材milliBPM÷曲milliBPM) frameとする。40–240BPMの全倍率を扱い、非1:1で入力/出力128frame未満は拒否する。1:1の適用は元bytes・native範囲をそのまま使う。原音A/Bも元を変更せず、確定した必須float WAVを共有engineのPAD/clip再生とexportが同じように読む。曲BPM変更は自動再生成しない。
- WSOLAは1:1のbit一致、既存offline oracleとの一致、220Hz toneの音程、非対称/逆相/無音channelとheadroom、transient欠落、長尺・取消を固定fixtureで確認する。テンポ変更時のtransient形状や極端な倍率の聴感は別のHuman受入とし、合成tone合格で音楽全般の品質を保証しない。YIN/PSOLAは単音voiceのpitch補正候補。子音・無声・低信頼・低音・急変・倍半分誤検出を評価し、不成立なら原音を保つ。非破壊A/B、キー/スケール、retune/vibratoを明示する。
- FXはgain/pan/mute/solo、EQ/filter/comp/delay/reverb send/masterを共有graphで処理。latency compensation、tail、loop折返し、solo/muteの意味を固定。post-fader/pre-master等stem出力点を表示し、非線形master後の和が一致するとは約束しない。
- ミキサーは明示したBANK routeと通常trackに同じgain/pan/mute/solo・insert・sendを適用し、SOURCE/HAND/clickのmonitor経路を外に保つ。未routeのPAD試聴は既存のunrouted busを使う。insertは追加lookaheadなし、最終limiterの72frameだけを書出しで補償する。pattern折返しではFX履歴を保ち、seek/一時停止/先頭から再生/graph交換ではreset、全停止では有限fade後に履歴を消去する。master meterはmaster FX後・最終limiter前、track/return meterはstem出力点を示す。
- 制作用stem ZIPは48kHz stereo float32を既定とし、graphに含まれるtrackと共通delay/reverb returnを同じ準備済graphで逐次生成する。整数stemはheadroom超過で全体拒否。WAVは24bit既定/16bit選択、最終量子化のseed付きTPDF、最後のclipまでの長さとgraph余韻の有無を明示する。Desktopは選択先のatomic publishと空き容量preflight、Androidの私有ZIP stagingは共有1GiB disk scratchを公開完了まで保持し、失敗/取消は一時資産を解放する。
- 新しいSTEP・PAD配置・Fill・演奏録音・NOTE REPEATのclipは、そのPADのBANK routeへ配置する。未routeのBANKは中立の独立trackをclipと同じ編集で確立し、既存の無所属trackや他BANKを再利用しない。明示した配置先は維持する。PAD panは素材の左右へ先に適用し、BANK panは後段で適用する（両panの加算ではない）。通常配置のPAD gainはclip gainに残し、performedのgain/panは音に焼き、BANKのfader/FXは再生・書出し時に一度だけ通す。既存の制作clipを暗黙に移動・再レンダーしない。
- 4パート分離は固定した単一HT-Demucsのdrums/bass/other/vocalsを使う（[NOTICE](../NOTICE.md)）。44.1kHz・7.8秒/343,980frameと1/4重複の逐次OLA、実tensor `[1,4,2,343980]` とfinite値を検証する。4 WAVの全header/hash/合計quotaを先確認し、同じAssetStore lock下でpublish、失敗時は今回新規hashだけ戻す。元bytes・既存同hashを保持。文書は後段の明示SetArrangement/expectedRevisionで1Undoにし、準備だけでは変更しない。crashで未参照immutable資産が残り得るが、部分文書を確定しない。
- 4stemのORTはCPU1thread/NO_OPT/arena・memory pattern・prepacking無効。入出力/OLA/copyは共有128MiB PCMへ予約し、model activation/RSSは別のlive RAM preflightでtotal3.5GiB/available1.5GiBとlowMemory/unknown拒否を確認する。Macは即時freeだけでは再利用可能メモリを除外するため、source付きのfresh available estimateとpressureを別に確認し、閾値を下げない。fp16 weightsだけでRAM半減を主張しない。実workerの数値とHuman音質、Android/Windows実model受入を分ける。
- AndroidはAudioTrack、Windowsの既存hostは連続Java Sound。WASAPIはshared event駆動の専用STAに出力/マイク/通常global-mix loopbackを所有させ、Get/Releaseとcloseを同じworkerで行う。clientは48kHz stereo FLOAT32、OS共有変換を明示し、loopbackはWindows10 build15063以降のdefault render全mix（自分の出力を含む、OS保護に従う）。別endpoint/マイクへの自動fallback・自動retryを行わない。
- NEXTのWindows既定出力はWASAPI。マイクとシステム音は明示録音だけで開き、歌はstereo入力を平均してmono、システム音はstereoのまま保持する。入力の開始中・録音中や旧route未解放では切替を拒否する。メニューの明示選択・再接続は声と曲を止め、旧出力を解放してから新出力を開く。制作内容を変更せず自動再生しない。Java Sound選択時はWASAPI loopbackを代用しない。
- WASAPIのring/endpoint/scratchは共有PCMへInitialize前に予約し実buffer後に縮小する。非協力workerは実finallyまでslot/予算を保持し同mode再openをBUSYで拒否、代替routeの明示選択は解放確認後に限る。capture gap/timestamp/overflow/device lossをtyped中断とし、QPC100nsをSystem.nanoTimeや往復実測補正と同一視しない。hostのroute変更通知と校正失効は別の接続受入。endpoint probe・短いnative stream・長時間/実音/Humanを別証拠にする。

既存のchannel/PCM oracleは [ADR3](adr/ADR-0003-audio-parity-primitives.md) と [ADR4](adr/ADR-0004-pattern-master-parity-gate.md)、Windows調査は [WASAPI research](research/windows-wasapi-jna-2026-08-20.md) に残します。
