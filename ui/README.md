# 共通UI（段階2A）

`com.choplab.ui.ChopLabApp` は `StudioUiState` の不変な表示投影を描画し、`UiAction` をhostへ返します。Project、Undo、jobs、port、保存や音声処理を所有しません。hostがcore/Studioの結果を新しい投影へ反映するまで、操作結果を成功表示しません。テスト素材は `desktopTest/MockStudioFixture` のみです。

```kotlin
ChopLabApp(
    state = projectedUiState,
    onAction = adapter::dispatch,
    playhead = { adapter.readPlaybackSnapshot() },
)
```

rootが `settings.gradle.kts` へ `:ui` を登録し、Android/desktop hostから依存します。coreへのadapterはhostの責任とし、このmoduleはまだcoreに依存しません。KMP Android/JVM、CMP 1.11.1、JDK21/bytecode17、Android resources有効です。Compose resourcesは `com.choplab.ui.resources` へ生成します。

## 接続の契約

- `StudioUiState` は保存しない表示投影。list/setは変更しないsnapshotを渡します。既定capabilitiesは空で、接続できた操作だけ有効化します。候補metadataに `UseSource` は表示しません。全5画面・settings・viewportはhostが選択を保持します。
- `SetChopRange` は元音全体に対する0–1端点、`NudgeRange` は±1frame。coreが `[start,end)` の範囲を確定します。波形peaksは描画専用です。
- PADはglobal ID 0–127、BANK A–H×16。短tapはrelease後に `SelectPad/PadTap` を送り、hostがplay modeに応じて試聴を扱います。`PadDown/PadUp` は実際の保持だけに使います。long pressの閾値を超えてvoiceを所有し、release/cancel/navigationで `PadUp` を1回送ります。scrollが取消になった短pressは再生しません。誤爆抑止のため発音開始をrelease/long press判定まで待つ構成であり、即時PAD演奏のlatencyを達成したとは扱いません。hostは同様にdevice/route/cancelで音声所有を解放します。
- `bars * 16` が総step。`viewportColumns`（16/32/64）と `viewportStart` は表示だけで、曲長を変更しません。48dpのstepと固定PAD名を横scrollします。
- `playhead` は小さい読み取りsnapshotを返します。`withFrameNanos` が再生中の波形/時刻部分だけを明示的に更新し、whole app/documentへ24msごとのcopyを流しません。停止中のseekなどはhostのobservable状態と該当投影更新に束縛します。
- gainは0–2、panは−1–1、tempo操作は20–300、barsは1–8。host/coreで受理・拒否を判定し、拒否はtyped noticeへ投影します。音量単位はratioをpercent表示します。
- 画面文言は133組のja/en resource。project名、素材名、歌詞等の利用者データはそのまま表示します。通常の文字色組は4.5:1以上、touch操作は48dp以上。拡大fontではtoolbar/navigationを横scrollして全操作へ到達できます。

## 検証

`:ui:desktopTest` はImageComposeSceneによる35枚（5画面×phone縦/横・tablet・desktop・font1.3/2.0）と、pointer/callback・disabled・metadata境界・step範囲・PAD所有解放・slider/歌詞・frame polling・contrastを検証します。`-PuiEvidenceDir=<path>` でPNG出力先を指定できます。拡大fontで末尾の書出しに到達した画像1枚を加え、計36枚です。PNGをsourceへcommitしません。

この検証の上限はJVM offscreen componentの `LOCAL_PASS` です。host制作通し、native dialog、Android実機TalkBack、実音/実マイク、provider、Human受入はここでは証明しません。音量/FX/録音/オンライン等の可用性はhost capabilitiesで決まり、見た目だけで実装済みとしません。
