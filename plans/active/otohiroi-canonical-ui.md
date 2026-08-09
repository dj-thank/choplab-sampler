# 「おとひろい」正式UIとライブチョップを実装する

## Purpose and user-visible outcome

ユーザー提示のHTMLを正式な画面仕様として、Androidアプリのトップ画面を生成物の配色・密度・日本語ラベル・操作順へ刷新する。ユーザーは曲を読み込み、再生しながら16 PADを叩いてその瞬間を刻み、停止後にPADを演奏し、PAD別トーンと16-stepビートを同じ縦長画面で操作できる。既存の範囲編集、4 BANK、マイク／端末音録音、Swing、Reverse、Gate、Choke、WAV exportは詳細機能として残す。

## Current state

- `app/` はCompose UIとAudioTrackベースのmono samplerを実装済み。
- 現行画面はMaterial 3の複数カード構成で、提示HTMLの「おとひろい」外観とは異なる。
- PAD割当、16-step、PAD別pitch/tone/gainは実装済みだが、曲を流しながらPADで現在位置を直接割り当てる導線とソース再生ヘッドは未実装。
- 2026-08-10、変更前の `scripts/validate_project.sh` はportable JDK/KotlinをPATHへ追加した環境でPASS。Gitは `main` / `2861ec5` から開始し、作業ツリーはclean。

## Constraints and invariants

- `minSdk=29`、mono MVP engine、既存project model foundationを維持する。
- source playback位置はaudio frameで扱い、start-inclusive/end-exclusiveを守る。
- AudioTrack render threadで新たなI/O、blocking lock、UI callbackを行わない。UIへはatomicなframe/playing状態だけを公開する。
- HTMLは外観とワークフローの仕様であり、WebViewや外部font通信は導入しない。
- 既存の4 BANKと詳細編集機能は削除しない。

## Architecture and interfaces

- `SamplerPlaybackEngine` にsource play/stopとatomicな現在frame/playingを追加する。
- `SamplerEngine` は通常PAD voiceとは別のsource voiceを1つ持ち、同じrender loopでmixする。
- `SamplerUiState` はsource playhead、source playing、master source pitchを保持する。
- pure command `assignLiveChopToPad` が同一bank・同一audioのPAD開始位置を時間順に並べ、各PADのendを次の開始位置または選択終端へ更新する。
- Composeは正式deckを常時表示し、従来の高度な範囲/PAD編集を折りたたみ領域に置く。

## Milestones

### Milestone 1: Live chop domain and engine
- pure command testsを先に追加する。
- source playback contract、engine command、ViewModel polling/intentを追加する。
- offline Kotlin testsでdomainを検証する。

### Milestone 2: Canonical Compose UI
- cream panel / dark surround / orange lamp / green waveformの独自themeを実装する。
- header、source buttons、waveform、transport、16 PAD、PAD tone、16 stepsを提示順に配置する。
- bankと詳細機能を残し、touch targetとcontent descriptionsを付ける。

### Milestone 3: Validation and publication
- offline validation、unit、lint、assembleを実行する。
- 接続済みPixel 9aへ生成APKを導入し、launchと主要タップ導線を確認する。
- docs/feature matrix/stateを更新し、review後にcommit/pushしてpublic CIを確認する。

## Progress

- [x] 2026-08-10 — 提示HTML 505行を全文確認し、正式UIと機能要件を抽出。
- [x] 2026-08-10 — clean baselineとoffline validation PASSを確認。
- [x] 2026-08-10 — live chop domain/engineを実装し、pure Kotlin 14 testsを通過。
- [x] 2026-08-10 — canonical Compose UIを実装。Android compile/device確認は次項で実施。
- [x] 2026-08-10 — public CI `31321170535`でunit/lint/APK PASS、Pixel 9aでvisualとlive-chop smoke PASS。
- [x] 2026-08-10 — `v0.1.1-preview.1`を公開し、public Release APKをPixel 9aへ再インストール。

## Discoveries

- 通常のPowerShell PATHにはbash/kotlincがないが、Git Bashとportable Kotlin/JDKは `C:\Program Files\Git\bin\bash.exe` および `F:\CodexData\ChopLab\tools` に存在する。
- local Android SDKにはPlatform 36 / Build Tools 36.0.0がなく、Android Gradle gateはpublic GitHub Actionsを主証拠にする必要がある。

## Decision log

- 2026-08-10 — HTMLをWebViewとして埋め込まずComposeで再現する。既存のAndroid録音・SAF・AudioTrack機能と安全に統合するため。
- 2026-08-10 — 商品名表示は正式仕様の「おとひろい」を主、ChopLabを小さな技術名として残す。
- 2026-08-10 — HTMLの16 PADを現在BANKの16 PADとして解釈し、既存4 BANKはcompact selectorで保持する。

## Validation log

- `scripts/validate_project.sh` — 2026-08-10 / Windows 11 + portable JDK 17 + Kotlin 2.3.21 — PASS before changes。
- pure Kotlin JUnit 6 classes / 14 tests — 2026-08-10 / portable Kotlin 2.3.21 + JUnit 4.13.2 — PASS after live-chop/UI source changes。
- `scripts/validate_project.sh` — 2026-08-10 / same environment — PASS after source changes。
- `git diff --check` — 2026-08-10 — PASS。
- GitHub Actions `31321170535` — 2026-08-10 / Ubuntu + Android 36 — unit, lint, assembleDebug, artifact upload PASS。
- CI APK `BDAE4725031940B8452331D61BA689905AE1C947E77C615BAF0586EB7BAD32F5` / 29,920,144 bytes — Pixel 9a install + launch PASS。
- Pixel 9a live-chop smoke — generated mono 48 kHz WAV import, source play, PAD 01 capture, stop, assigned marker observed; no immediate fatal exception。Test WAV removed。
- final main CI `31321683089` — unit/lint/assemble/artifact PASS。Release workflow `31321828427` — build/package/publish PASS。
- public APK `F4C1C47066771ABF4FD47AB1F72C06A442A30FA7B6EE13B1ADC6777C416EFB6A` / 29,920,144 bytes — Pixel 9a install、version 0.1.1、launch PASS。

## Risks and rollback

- source voice追加でPAD/sequence mixへ回帰する可能性がある。source voiceは独立フィールドに限定し、既存voice listとtransport処理を維持する。
- compact UIで小画面のtouch targetが不足する可能性がある。PADは正方形を維持し、16 stepsのみminimum 20dp相当を許容する。
- 単一commitで `git revert` 可能にし、既存public release tagは変更しない。

## Remaining device validation

- source import、再生、波形seek、再生中のPAD刻み、停止後のPAD retrigger。
- マイク／端末音権限と録音停止後の自動読込。
- 16-step playback、BPM、PAD tone、advanced controls、WAV export。
- 画面幅と縦スクロール、回転／background後のaudio lifecycle。
