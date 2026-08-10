# おとひろいを無スクロールのプロ向け固定コンソールへ刷新する

## Purpose and user-visible outcome

Androidスマートフォンで縦スクロールを一切使わず、主要な制作操作へ1〜2タップで到達できる固定コンソールへ刷新する。16 PAD、波形、transport、PAD編集、16-step、録音／取込／書き出しを `CHOP / PAD / SEQ / SOURCE` の4モードへ整理し、MPC系ワークステーションの即応性を独自の視覚言語で再構成する。

## Current state

- public `main` は `f30afb7`、作業開始時clean。
- `OtohiroiDeck.kt` はcream/orange/greenの正式UIを実装済みだが、全機能を縦に積み `verticalScroll` と折りたたみ詳細に依存する。
- Pixel 9aは現在接続されていない。前回Releaseのdevice evidenceは過去証拠であり、今回変更のDEVICE_PASSには使わない。
- 2026-08-10、変更前のoffline validationはportable JDK/Kotlin環境でPASS。

## Constraints and invariants

- `app/` のみを変更し、`reference/pro-v0.2/`は触らない。
- `minSdk=29`、現在のAudioTrack engine、mono MVP挙動を維持する。
- 画面本体で `verticalScroll` / `horizontalScroll` を使わない。各modeはavailable bounds内でweight配分する。
- portraitとlandscapeの両方を `BoxWithConstraints` で配置し、orientation lockに逃げない。
- 16 PAD、現在BANK、ALL STOP、現在mode、statusは明瞭に保つ。
- タップ対象は原則40dp以上、演奏PADは可能な限り48dp以上。色だけに依存せずtext/semanticsでも状態を示す。
- AKAI/MPCのロゴ、asset、固有trade dressは複製せず、workflow概念だけを独自UIで再構成する。

## Architecture and interfaces

- pure `DeckLayoutPolicy` がavailable width/heightをportrait/landscapeとcompact/regularへ分類し、chrome/control/gap metricsを返す。
- Composeのtop-levelは固定 `Column`: machine header、mode strip、mode body(weight)、status strip。
- `CHOP`: source action、waveform、source transport、bank、16 PAD。
- `PAD`: bank、16 PAD、PARAM/PLAY subpage。pitch/tone/gainとreverse/play mode/choke/clearを分離。
- `SEQ`: transport/REC、BPM/Swing、16 PAD、2×8 steps、clear/export。
- `SOURCE`: import/mic/system capture、range waveform、manual/auto chop、assign controls。
- portraitは上下、landscapeは左右分割し、同じcallbacks/stateを共有する。

## Milestones

### Milestone 1: Layout policy and shell
- pure policy testsをRed/Greenで追加。
- scroll依存とlegacy card stackを除去。
- fixed chromeとmode stateを実装。

### Milestone 2: Four production workspaces
- CHOP/PAD/SEQ/SOURCEをportrait/landscapeへ実装。
- tactile pressed/selected/playing/record states、semantics、hapticsを追加。
- 既存ViewModel機能を各modeへ再接続。

### Milestone 3: Validation and public preview
- pure tests、offline validation、Android unit/lint/assembleを実行。
- source scanでscroll API不使用とdead legacy UI除去を確認。
- device不在を明記し、CI APKをoutputsへ保存。review後にcommit/pushし、必要なら新preview Releaseを作る。

## Progress

- [x] 2026-08-10 — clean baseline、scroll原因、既存機能導線を確認。
- [x] 2026-08-10 — layout policy Red/Green（4 tests PASS）。
- [x] 2026-08-10 — fixed consoleと4 mode実装、scroll API除去。
- [x] 2026-08-10 — local Android test/lint/APK、review、main/tag CI、public Release完了。DEVICE_PASSのみ保留。

## Discoveries

- 現行top-levelは `verticalScroll(rememberScrollState())`、581行のdeckと500行のlegacy advanced screenを同居させている。
- 現在はphone/emulator captureがなく、今回UIの正式なscreenshot auditとDEVICE_PASSは不可能。コード・pure policy・CIまでをLOCAL/PUBLIC evidenceとして分離する。

## Decision log

- 2026-08-10 — scrollを単に無効化せず、機能を4 modeへ再配置する。全機能を1画面へ縮小するとtarget sizeと可読性が壊れるため。
- 2026-08-10 — orientationを固定せずportrait/landscapeを別compositionで支える。制作機器として置き方を選べる方が操作しやすいため。
- 2026-08-10 — `CHOP`を起動modeにする。読込→再生→PADで刻むが製品の最短価値だから。

## Validation log

- `scripts/validate_project.sh` — 2026-08-10 / pre-change — PASS。
- `:app:compileDebugKotlin` — 2026-08-10 / post-change — PASS。
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — 2026-08-10 / post-change — PASS。18 tests、lint 0 errors / 9 warnings。
- source scan — 2026-08-10 / post-change — `verticalScroll`、`horizontalScroll`、`rememberScrollState`なし。
- local APK — SHA-256 `CDB02CFFA5F693F2550F41260558D04E259F31AC917E998ED16CDE12D07E8ABD`、30,433,927 bytes（version 0.2.0、compact-landscape/accessibility hardening後）。
- main CI `31352372588` — PASS。tag CI `31352511018` — PASS。
- release CI `31352511062` — build/package/publish PASS。
- public APK — `ChopLab-v0.2.0-preview.1-debug.apk`、30,034,832 bytes、SHA-256 `B1CC4F6B014F507F3F928AF10CA0EB25E41EA58E50CCEC771CDE43A9A0F62C26`。

## Outcome

`e0896ad`を`main`へ公開し、`v0.2.0-preview.1` Releaseを作成した。固定4 mode、無スクロール、portrait/landscape、40dp主要操作、16 PAD、haptic、二段階clear、compact波形viewportを実装した。公開APKの取得とhash照合まで完了。phone不在のため今回版のvisual/device/audio E2Eは未検証であり、`DEVICE_PASS`または`HUMAN_GO`とはしない。

## Risks and rollback

- 画面密度によるclippingは実機なしでは完全確認できない。constraint-driven layout、pure metrics、CI compileで下限を守り、device項目は未確認のまま残す。
- legacy advanced UI削除で到達不能機能が出る可能性がある。ViewModel public intentsを一覧化し、4 modeのcallbackへ対応付ける。
- commit単位で通常の `git revert` が可能。既存Release tagは変更しない。

## Remaining device validation

- 360–430dp級portraitで全modeのclipping、touch target、IME/font scale。
- landscapeで左右panel比率、PAD連打、step入力。
- import/mic/system capture、live chop、PAD edit、sequence/exportのend-to-end。
- TalkBack reading order、haptic、audio latency、background/resume。
