# 共通4工程UI

`ContinuousEditor` は `ContinuousEditorState` を描画し、typed `ContinuousEditorAction` を返します。ユーザーが選択した案2の改訂版を基準に、4工程、左のPAD面、右の濃い自由配置、共通原曲バー、曲全体バーを維持します。画面契約は [DESIGN](../docs/DESIGN.md)、進捗は [ROADMAP](../docs/ROADMAP.md) が正本です。

`ContinuousEditorPresenter` はcoreの `Studio` へ編集を渡し、結果を投影します。Project/Undo/jobの正本はStudioです。表示用の選択・幅・zoomだけをUI側で持ち、Projectを複製して別管理しません。5画面カード案は採用せず、履歴へ保全しました。

- 原曲のidentity、native frame、試聴音量は、PAD選択と曲全体の48kHz clockから分離します。
- 128 PADはA–H×16。大きい4×4 PADを保ち、BANKは必要に応じて横スクロールします。
- 自由配置は48kHz frame位置とsourceの半開区間で編集します。1回の移動・トリム・分割・複製は1回のStudio/Undo操作です。
- Readoutは波形/時刻の小さい領域で読み、停止中seekもrefreshKeyで反映します。実際の再生PADはengineのcoherent maskから取得します。
- 試聴音量は保存する音量と別です。未接続操作はcapabilityで無効にし、成功表示を作りません。
- 文言は122組の日英resource。合成fixtureを実素材や実音の証拠にしません。

KMP Android/JVM、CMP1.11.1、JDK21/bytecode17。`:ui:desktopTest` は4工程、PAD/clip入力、原曲identity、Undo、音量分離、disabled、compact/font拡大、frame readoutを検証します。`:ui:compileAndroidMain` はAndroid向けコンパイルです。

Windowsは `:desktop:runLinkedPreview` または `:desktop:packageWindowsLinkedPreview` で専用profileの候補へ接続できます。既定の本番入口は保持しています。Androidの新Activity/FilePorts接続、残る機能移行、実機の音声/TalkBackとHuman受入は別途残ります。
