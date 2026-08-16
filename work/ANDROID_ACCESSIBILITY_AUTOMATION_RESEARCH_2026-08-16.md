# ChopLab Android: アクセシビリティ／DEVICE自動検証の一次資料調査

調査日: 2026-08-16
対象: TalkBack 実サービス経路、focus traversal/custom actions/spoken feedback、AVD/Gradle Managed Devices/ATD、Compose/UIAutomator/Espresso/Robolectric の役割分担。以下は公式ドキュメントまたは公式ソースのみを根拠とする。

## 結論（ChopLab に適用できる現実的な構成）

1. **LOCAL**: Compose の Semantics（ラベル、Role、StateDescription、CustomActions、traversal/merge）を `ComposeTestRule` で決定的に検査する。Compose のテストは merged tree が既定で、必要時は `useUnmergedTree=true` を明示する。これは accessibility service が見る最終的な framework tree と同一ではないため、DEVICE の補完にはなるが代替ではない。[Compose Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)、[Compose Semantics testing](https://developer.android.com/develop/ui/compose/testing/semantics)
2. **DEVICE**: AndroidJUnitRunner 上の UI Automator（新 API 2.4 または legacy）で、アプリ外を含む Accessibility window/node を読み、focus 状態、可視テキスト/contentDescription、click/scroll/custom action の可否を検査する。`UiAutomation` は platform accessibility API を使う特殊な AccessibilityService と明記されている。[UI Automator modern](https://developer.android.com/training/testing/other-components/ui-automator)、[UI Automator legacy](https://developer.android.com/training/testing/other-components/ui-automator-legacy)、[UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)
3. **DEVICE（TalkBack相当のノード経路）**: 専用の debug/test `AccessibilityService` を test APK に用意し、`rootInActiveWindow`/`AccessibilityEvent`/`AccessibilityNodeInfo` を収集して accessibility focus、traversal、actions を記録する。`performAction()` は AccessibilityService からのみ実行可能なので、通常の Compose test やアプリプロセスから直接呼ぶ設計は不可。[Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)、[AccessibilityNodeInfo.performAction](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.html#performAction(int))
4. **TalkBackそのものの音声**: 公式 API は「TalkBack が実際に発話した文字列」をテスト断言するものではない。TalkBack は synthesized voice によるシステム accessibility service と説明されるが、音声出力は設定・言語・TTS エンジン・レートに依存するため、CI の機械的な spoken-string PASS にはしない。[Android accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/principles)、[Google TalkBack 公式ソース](https://github.com/google/talkback)
5. **仮想端末**: Gradle Managed Devices は API 27+ の仮想/リモート端末を作成・配置・破棄し、クリーンなスナップショット状態を得られる。ATD は instrumented test 用に軽量化されるが、SystemUI/Settings 等が除去・無効化され、hardware rendering 依存の screenshot は未対応。TalkBack の有効化や設定 UI を含む実サービス試験には通常の AVD（必要な Google 系 image）または実機を使い、ATD は node/semantics のスモークに限定する。[Gradle Managed Devices](https://developer.android.com/studio/test/managed-devices)、[ATD limitations](https://developer.android.com/studio/test/managed-devices#use-atds)

## 1. TalkBack 実サービス経路の API と制約

### 検査できるもの

- Android の accessibility service は `AccessibilityNodeInfo` の tree を取得し、現在の accessibility focus を `findFocus(FOCUS_ACCESSIBILITY)` で確認できる。対象 node へ `ACTION_ACCESSIBILITY_FOCUS` を要求でき、click/scroll 等は `performAction()` で実行する。[Accessibility service: focus/actions](https://developer.android.com/guide/topics/ui/accessibility/service)、[AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.html)
- Custom action は `AccessibilityNodeInfo.AccessibilityAction` として node に公開される。View は `addAction()`/`performAccessibilityAction()`、Compose は SemanticsActions を使う。したがって ChopLab では「録音開始」「停止」「波形操作」などが node の action list に現れ、実行後に状態/event が変わることを assertion できる。[AccessibilityAction API](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction)、[Compose Semantics actions](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- focus traversal は node の論理順序と accessibility focus event の系列を取得して検査できる。ただし Compose の merged semantics tree はテスト側の既定であり、accessibility service は unmerged tree に独自 merging を適用するため、両方の期待値を用意する。[Compose merged/unmerged tree](https://developer.android.com/develop/ui/compose/accessibility/semantics#merged-and-unmerged)

### できない／不安定なもの

- Android の `UiAutomation` は accessibility API による node introspection と raw input injection を提供するが、TalkBack の lifecycle hook や TTS 発話文字列の assertion API ではない。[UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)
- 公式の accessibility testing guidance も、Android Studio の ATF は「device 上の実行時にだけ起きる問題を検出できない」と注意し、speakable text や誤った focus は manual testing と UI hierarchy inspection の組合せを推奨している。[Test your app's accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing)
- TalkBack は設定（speech language/rate、verbosity、音量、TTS engine）に依存するため、録音した音声や `logcat` を CI の「発話内容」証拠にしない。発話の自然さ、重複、句読点、タイミングは人間が TalkBack を有効にした端末で確認する。TalkBack の公式説明は spoken feedback と gesture 操作を示すが、テスト用発話プロトコルは提供していない。[TalkBack source strings/description](https://github.com/google/talkback/blob/master/talkback/src/main/res/values/strings.xml)

## 2. 仮想 Android / AVD / GMD / ATD の範囲

### マルチタッチ

- Emulator は Control/Command を押して pinch/spread の二指入力をシミュレートできる。別プロセスからの決定的な注入は `UiAutomation` の raw input API または emulator console の利用範囲に限られ、物理端末の指の接触感・遅延は再現しない。[Run apps on Emulator](https://developer.android.com/studio/run/emulator#navigate)、[UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)
- Emulator を Android Studio の tool window 内で動かすと two-finger/multi-touch が動かない既知の制約がある。必要なら separate window で実行する。[Emulator troubleshooting: multi-touch](https://developer.android.com/studio/run/emulator-troubleshooting#multi-touch)

### accessibility / TalkBack

- 通常 AVD はほぼ実端末の能力を提供するが、公式 GMD/ATD の説明はアプリ instrumented test の効率化が目的。ATD は Settings/SystemUI を除去・無効化するため、TalkBack を Settings UI から有効化する試験、通知 shade、TTS/音声フォーカスのシステム相互作用には不適切。これは ATD の削除表からの直接的な適用判断であり、通常 AVD/実機で分離する。[ATD removed components](https://developer.android.com/studio/test/managed-devices#use-atds)
- GMD は端末 lifecycle・snapshot・sharding を管理するため、node tree と action の再現性には有効。ただし managed device の PASS は実機の TalkBack/音声 PASS を意味しない。[GMD overview](https://developer.android.com/studio/test/managed-devices)

### マイク／音声競合

- Emulator の microphone input は privacy/performance 上デフォルト無効。Extended Controls の「Virtual microphone uses host audio input」を有効化すると host microphone を受ける。headset plug、headset microphone、Voice Assist のイベントもシミュレートできる。[Extended controls: Microphone](https://developer.android.com/studio/run/emulator-extended-controls#microphone)
- Bluetooth headset 使用時は emulator が microphone を有効化して duplex mode に切り替えることで音質が低下する既知問題がある。`hw.audioInput=no` は回避策だが、音声入力試験を無効化するため、ChopLab の録音・TalkBack 音声競合の最終判定には実機／実 headset を使う。[Emulator troubleshooting: Bluetooth audio](https://developer.android.com/studio/run/emulator-troubleshooting#bluetooth-audio)

## 3. 推奨テスト層（Robolectric / Compose / UIAutomator / node / service）

| 層 | 目的 | 決定的に PASS にできる主張 | 境界 |
|---|---|---|---|
| Robolectric/local | ViewModel、状態遷移、権限・音声 engine の fake | 純粋な状態機械、エラー処理 | 実 framework accessibility tree、TalkBack、real audio device を証明しない。Robolectric は JVM 上で Android API の shadow を提供するため、DEVICE 証拠には昇格させない。[Robolectric公式](https://robolectric.org/) |
| Compose `ComposeTestRule` | Semantics properties/actions、merged/unmerged tree、enabled/focused/state | label/contentDescription/Role/StateDescription、custom action の存在、click 後状態 | 実 AccessibilityService の merging/focus traversal、TalkBack TTS ではない。[Compose testing](https://developer.android.com/develop/ui/compose/testing/semantics) |
| Espresso | View/hybrid 部分の同期された user-like操作 | View の text/contentDescription、visibility、state | cross-app/SystemUI には不向き。Espresso は View ベースで、UI Automator より同期機構が強い。[Espresso basics](https://developer.android.com/training/testing/espresso/basics) |
| UI Automator / UiAutomation | 外部プロセス、SystemUI、Accessibility window/node、focus/action smoke | resource/text/contentDescription、node focus、可視 action、rotation、スクリーンショット | TTS 発話、物理 multi-touch の忠実度、実機音声競合は証明しない。[UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)、[UiAutomation](https://developer.android.com/reference/android/app/UiAutomation) |
| debug AccessibilityService（DEVICE） | TalkBack に近い framework service 経路 | `AccessibilityEvent` 系列、root/node tree、accessibility focus、`performAction` 結果 | 本物 TalkBack の traversal policy、発話文・音声タイミングは置換できない。サービスの有効化自体は端末設定/ADB の別証拠が必要。[Create service](https://developer.android.com/guide/topics/ui/accessibility/service) |
| 本物 TalkBack + 実機 | 最終 human/device acceptance | focus traversal の体感、spoken feedback の意味・重複・順序、録音と TalkBack の音声競合 | 自動 CI の再現性が低い。人間の明示的チェックリストと画面/ログ証跡を残す |

推奨パイプラインは `LOCAL_PASS (Compose + Robolectric) -> DEVICE_PASS (通常 AVD/GMD + UIAutomator + debug service) -> HUMAN_GO (TalkBack 実機、マイク/BT/音声フォーカス)`。ATD の結果は `DEVICE_ATD_SMOKE` として別名にし、上位 PASS に読み替えない。

## 4. 公式 GitHub サンプル／実装の参照先

- Android Open Source Project の [android/platform-samples](https://github.com/android/platform-samples) は `Accessibility` topic を含む公式 API サンプル集合。サンプルは機能単位の簡略例で production-ready ではない旨も README に明記される。
- TalkBack の実装とリソースは [google/talkback](https://github.com/google/talkback)。TalkBack 自体の挙動・設定文字列を確認する一次ソースとして使うが、アプリの CI に内部実装を依存させない。
- Compose の公式 testing/accessibility コード例は Android Developers の [Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) と [Semantics testing](https://developer.android.com/develop/ui/compose/testing/semantics) に掲載。依存バージョンは ChopLab の version catalog に合わせ、ページ記載の最新版を盲目的に固定しない。
- AndroidX Test UI Automator の公式 API は [androidx.test.uiautomator documentation](https://developer.android.com/training/testing/other-components/ui-automator) および AndroidX の [test repository](https://github.com/android/android-test)。新 API は 2.4 系で under development と明記されるため、CI 採用時は pinned version と release note を記録する。

## 5. 人間しか判定できない境界（ChopLab の受入チェックリスト）

- TalkBack を有効化した実機で swipe-left/right を繰り返し、フォーカス順が利用者の期待する論理順か、不可視/装飾 node に迷い込まないかを確認する。
- 各 node の spoken feedback が「何のコントロールか・現在状態・操作方法」を過不足なく伝えるか、録音開始/停止や波形操作で重複・取りこぼし・不自然な読み上げがないかを確認する（音声文字列の自動 PASS は設定依存のため不可）。
- TalkBack と ChopLab の録音・再生・マイク権限を同時に使い、入力音声の欠落、ducking、feedback loop、Bluetooth headset 切替を実機で確認する。Emulator の host microphone は代替証拠に留める。
- 実機の異なる TalkBack verbosity、言語/TTS、font scale、orientation、画面サイズで、焦点枠・タッチターゲット・スクロールが破綻しないかを確認する。公式 guidance は手動 TalkBack 探索を推奨している。[Accessibility design guidance](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)
- 人間確認後も「PUBLIC/HUMAN_GO」と「LOCAL/DEVICE の自動 PASS」を混同しない。自動ログ、node dump、スクリーンショットは補助証跡であり、spoken feedback の意味理解を置換しない。

## 直接参照 URL 一覧

- https://developer.android.com/develop/ui/compose/accessibility/semantics
- https://developer.android.com/develop/ui/compose/testing/semantics
- https://developer.android.com/guide/topics/ui/accessibility/testing
- https://developer.android.com/guide/topics/ui/accessibility/service
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.html
- https://developer.android.com/reference/android/app/UiAutomation
- https://developer.android.com/training/testing/other-components/ui-automator
- https://developer.android.com/training/testing/espresso/basics
- https://developer.android.com/studio/test/managed-devices
- https://developer.android.com/studio/run/emulator
- https://developer.android.com/studio/run/emulator-extended-controls
- https://developer.android.com/studio/run/emulator-troubleshooting
- https://github.com/google/talkback
- https://github.com/android/platform-samples
- https://github.com/android/android-test
- https://robolectric.org/
