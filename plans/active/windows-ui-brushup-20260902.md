# Windows UI / 起動ブラッシュアップ — 2026-09-02

## 目的

ユーザー依頼「おとひろい pad の UI や機能を徹底的にブラッシュアップ。読み込み速度、細かいバグ、無駄な画面表示での操作や UI としてよくないところを改善」に対し、
Windows デスクトップ版を主対象として、実測・コード読解で確認できた摩擦だけを shared deck と Desktop adapter の小さな変更で直す。

- 対象 root: `work/choplab-ui-brushup-20260902`（PR #83 最新 head `13e41af` から分岐した `codex/choplab-ui-brushup-pr83-latest-20260902`。predecessor `a5f5a17` は旧branchに保全）
- 変更対象: `shared/.../ui/OtohiroiDeck.kt`, `WaveformEditor.kt`, `PadGrid.kt`, `desktop/.../DesktopApp.kt`, `DesktopSamplerController.kt`, `audio/DesktopWavDecoder.kt`, `desktop/build.gradle.kts`
- 目標 gate: `LOCAL_PASS`（shared/jvm-core/desktop tests、H13 long-press UI test、`packageWindows`）。物理音声・Human は別 gate。

## 観測した問題と対応

| 観測 | 影響 | 対応 |
| --- | --- | --- |
| `setMasterPitch` が曲全体を `PadPcmRenderer` で UI スレッド上に再レンダリング | 長い曲で KEY ± を押すたびに数秒フリーズ | I/O worker へ移し、旧キー再生を正確な frame で止めて新キー完成後だけ同 frame から再開。古い要求は epoch で破棄 |
| `DesktopWavDecoder.readPcm` がサンプル単位 `append` | 10 分 stereo で数千万回の境界チェック | mono/stereo 保持時は little-endian view で chunk 一括コピー（`appendAll`）。上限判定は従来と同一 |
| `JFileChooser` を取込のたびに生成 | Windows shell 走査で毎回 1〜3 秒 | プロセス内で 1 インスタンスを再利用し、open/save/export の前回フォルダを共有 |
| `ConfirmActionButton` の armed 状態が永続 | 誤タップ後「もう一度で削除」が残り続け、後で誤確定 | 4 秒で自動解除、disabled 時も解除 |
| デスクトップでホバー反応なし | どこが押せるか分からない | `MachineButton` / 工程タブ / PAD に hover 色・影 |
| 波形がホイールに無反応 | マウスでズーム不可（2 本指前提） | 縦ホイール = カーソル位置ズーム、横ホイール = パン（`resolveWaveformWheelGesture`） |
| console 最大幅 980dp | 最大化 1080p で左右が大きく空く | 1280dp へ拡張（横長では PAD grid は正方形維持、波形側へ配分） |
| ウィンドウ最小サイズなし | 縮めると行が潰れる | 760×600 px を下限に |
| status strip 8sp | 100% DPI で読めない | 9sp |
| jpackage に JVM オプションなし | 初回フレームまで hsperfdata mmap / heap 拡張 | `-XX:-UsePerfData -Xms160m -Dfile.encoding=UTF-8`（CI の JDK 17 でも有効な範囲のみ。AppCDS 自動生成は JDK 19+ のため見送り） |

## やらないこと

- Android の挙動変更（shared の変更は Compose 共通だが、hover は touch では発火しない）
- Realtime API / Spotify / WASAPI 経路の変更
- 公開 Release、tag、dirty canonical checkout への書き込み

## 検証

- `JAVA_HOME=JDK17 ./gradlew :shared:desktopTest :jvm-core:test :desktop:test` → shared 90 / jvm-core 88 / desktop 182、failure/error/skip 0
- `python -m unittest discover -s scripts/tests` → 67 OK（picker contract を含む）
- `:desktop:desktopLongPressUiTest :desktop:packageWindows` → receipt `outputs/windows-ui-brushup-20260902.md` を参照
