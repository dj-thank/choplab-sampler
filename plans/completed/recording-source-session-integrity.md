# 素材上書きと録音セッションの整合性を統一する

## Purpose and user-visible outcome

再生中の素材をA01など既存PADへ叩いたときは、その場で現在の素材へ明示的に上書きする。マイク／端末音声／ボーカル録音中はUIと実際の録音状態を一つの録音セッション契約から表示し、録音を汚すPAD・source・loop・scratchの誤再生を起こさない。録音STOPは全工程とLayer Studioから到達可能にする。

## Current state

- 実装基準は`app/`のAudioTrack MVP、versionName `0.12.0` / versionCode `19`。
- 変更前は`microphoneRecording`、`systemAudioRecording`、`vocalOverdubRecording`が別々の真偽値で、録音開始・停止・decode間に一貫した状態がなかった。
- Capture面はsource再生よりassigned PAD previewを優先し、A01などの上書きを拒否していた。
- 完成実装は`RecordingSession.kt`を状態の唯一の所有者とし、`SamplerViewModel`がengine／recorder／service境界を接続する。

## Constraints and invariants

- project archive schemaと既存ユーザーデータを変更しない。録音セッションはruntime-only。
- source録音と、Beatを鳴らし続けるVocal overdubを区別する。
- 録音セッションは同時に1つだけ。STOPPINGは後着の開始通知でRECへ戻さない。
- 既存の4工程、4 BANK × 32 PAD、非スクロール構成を維持する。
- 実装前にユーザー症状を表すpure Kotlin regression testsをREDで観測する。
- 物理端末の既存archiveへ触れる場合は事前・事後hashを照合する。Pixel 9aへの最終上書きでは4 autosaveのSHA-256をinstall前／後／起動後で照合する。

## Architecture and interfaces

- `model/RecordingSession.kt`: `RecordingKind`、`RecordingPhase`、開始／観測／停止／終了reducer、再生開始policyを所有。
- `SamplerUiState.recordingSession`: UI・routing・ViewModelが参照する唯一の録音状態。旧boolean名は読み取り専用derived propertyとして互換表示に限定。
- `SamplerViewModel`: 録音開始前に`engine.stopAllPlayback()`とruntime state停止を実行。Vocalだけrecorder開始成功後に選択loopを再始動。録音完了ファイルのdecode／保存が終わるまでSTOPPINGを維持。
- `PadPressRouting` / playback entry points: 録音中、およびsourceのSTARTING／STOPPING中のPAD開始を拒否。元曲、preview、Beat loop、transport、scratchは共通の主再生切替境界で競合voiceを停止。
- `GuidedWorkflow` / `OtohiroiDeck`: 種別・phase・STOP表示、競合入力と外部pickerの無効化をpure presentationから描画。
- Persistence: archive encoder/decoderは変更せず、history snapshotにも録音状態を保存しない。

## Milestones

### Milestone 1: 症状を再現して根因を固定

- assigned A01、録音中PAD、live-chop overwriteの3回帰をREDで観測。
- Capture routing priority、上書き拒否、録音boolean非連携を根因として確定。

### Milestone 2: 上書きと単一録音セッション

- assigned Capture PADを現在素材へ上書き。
- 対象PADの旧engine voiceを停止し、loop/scratch runtime参照を解除。
- 録音kind/phase reducer、全再生停止、競合録音拒否、再生開始guardを実装。

### Milestone 3: 常設STOPと非同期整合

- 全工程headerをkind別STOPへ切替。
- Layer Studio modal内STOP、STOPPING中の二重停止無効化、外部picker遮断を実装。
- 端末音声serviceの後着Recording通知がSTOPPINGを取り消さない回帰を追加。

### Milestone 4: 検証と成果物

- full unit/lint/assemble、pure smoke相当、wrapper/XML、no-scroll、emulator runtime、exact APK照合を完了。
- 文書、レビュー、APKを同期。

## Progress

- [x] 2026-08-13 — 3症状をpure regression testsでRED化し、仮説1〜3を確定。
- [x] 2026-08-13 — A01上書き、旧voice停止、loop/scratch参照解除を実装。
- [x] 2026-08-13 — 単一録音セッション、全再生停止、競合録音／再生開始guardを実装。
- [x] 2026-08-13 — header／Capture／Voice／Layer StudioのSTOP表示と外部picker遮断を統一。
- [x] 2026-08-13 — full gate、APK、emulator no-scroll／録音／assigned PAD block／modal STOPを確認。
- [x] 2026-08-13 — Standards／Spec二段階レビューと文書同期を完了。

## Discoveries

- assigned PADの旧音は割当データだけでなく、engine voiceと`loopingPadIndex`／`scratchingPadIndex`にも残り得たため、上書き時に両方の境界を処理する必要があった。
- 端末音声serviceはSTOP直後にRecording通知が到着し得る。STOPPINGを単調な終端方向として扱わないとUIがRECへ逆戻りする。
- sourceの停止要求はUI stateだけでは音を止めない。Beat loop等の主再生を始める前にengineへ全voice停止を発行しないと、元曲や直前previewと同じ音が二重になる。
- リセット後の古い端末音声service通知は、無視するだけでなく録音結果の破棄完了までSTOPPINGを維持しないと、空にした画面へ録音表示が再出現し得る。
- Compose Dialogは固定headerを覆うため、headerだけではSTOP常設契約を満たさない。modal内にも同じpresentation由来STOPが必要だった。
- Windows既定`bash.exe`は未設定WSLへ向き、Git Bashには単体`kotlinc`がなかった。Gradle cache内Kotlin 2.3.21 compilerでpure smokeを等価実行し、スクリプトのsource list自体も更新した。

## Decision log

- 2026-08-13 — 3booleanへの条件追加ではなく、kindとphaseを持つ単一sessionへ置換。排他性とSTOPPINGの可視性を型で保つため。
- 2026-08-13 — source録音は全再生禁止、Vocalだけrecorder開始後に1つのBeat loopを意図的に復元。録音汚染防止とoverdub用途を両立するため。
- 2026-08-13 — live chop overwriteはpitch/tone/gain/play mode/stepsを保持し、PCM範囲とlive runtime参照だけを置換。演奏設定を壊さず旧音を排除するため。
- 2026-08-13 — 元曲、preview、Beat loop、transport、scratchを排他的な主再生モードに統合。通常PADの重ね演奏は残しつつ、モード切替由来の偶発的な二重音だけを除くため。
- 2026-08-13 — archive schemaは変更しない。録音phaseは制作データではなく実行中operationだから。

## Validation log

- Focused regression: 4 suites / 49件を反復実行、最終GREEN。
- Full Gradle: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs`、BUILD SUCCESSFUL。179 tests / 38 suites、failures/errors/skips 0、Lint errors 0 / warnings 10。
- Pure smoke equivalent: cached Kotlin 2.3.21 compilerで`SmokeMainKt` PASS。Android XML 4件parse PASS。Wrapper SHA-256一致。
- `git diff --check` PASS。UI scroll-container source scan 0 matches。
- Pixel 9a: `5A121JEBF08094`、Android 17、1080×2424。exact final APKを`adb install -r`し、pullしたbase.apkのsize/SHA-256一致。4 autosaveはinstall前／後／起動後でSHA-256不変。`MainActivity`起動は確認したがkeyguardがロック中のためfinal-hash画面操作は未確認。
- Prior emulator smoke: Android 16 x86_64、1080×2424。near-final APKで各確認画面scrollable node 0、MIC STOP、assigned A01 block、Layer Studio modal STOPを観測。
- Connected runner: near-final emulatorで`:app:connectedDebugAndroidTest --offline` BUILD SUCCESSFUL、instrumentation casesは0。
- APK: `outputs/ChopLab-v0.12.0-recording-source-integrity-local-debug.apk`、31,643,454 bytes、SHA-256 `8AA11856647F6D830E574AF143460FA418AB7BD47A4CE21EBD5254632C1CA574`。

## Risks and rollback

- 物理マイク／Playback Captureの音質・latency・端末固有service orderingはemulatorでは保証しない。
- 問題時は本実装commitを通常の`git revert`で戻せる。archive schemaと既存PCM byteは変更していない。

## Remaining device validation

- Pixel 9aのkeyguard解除後、実音を使ったMIC source録音、Vocal loop整列、端末Playback Captureを聴取する。
- 録音中にassigned PADを連打して二重音が無いことを人間の耳とaudio traceで確認する。
- 今回保持を確認した既存4 archiveを、次回の破壊的な端末試験でも事前snapshotと照合する。
- TalkBack、物理multi-touch、長時間録音、バックグラウンド／画面回転を追加確認する。
