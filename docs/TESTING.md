# 検証の契約

変更の影響に合う最小の意味ある試験から始め、必要なCIを完了して差分を確認します。結果の索引は [ROADMAP](ROADMAP.md)、詳細はrevisionに束縛されたPR/CI artifactに置きます。同じ入力の無変更な高コスト試験を毎回やり直す必要はありません。

## 層と言えること

| 層 | 言えること | 言えないこと |
|---|---|---|
| LOCAL unit/host/DSP | reducer、範囲、保存、取消、fixtureに対する音声差/性能 | 実端末の音・権限・provider・主観品質 |
| Compose / ImageComposeScene / Xvfb | state、geometry、semantics、合成入力、48dp、選択した画面 | native dialog、physical touch、マイク、読み上げ |
| Android instrumentation / framework node / emulator | Android frameworkのnode/action/gesture、選択したemulator上の動作 | TalkBackが実際に何を読むか、物理音・route品質 |
| DEVICE | 指定bytes・機種・routeのinstall、lifecycle、音声/録音/割込み | 全機種保証、人間の品質評価 |
| PROVIDER | 実アカウントの指定操作と結果・拒否/取消 | 公開配布の適合、人間の受入 |
| PUBLIC | 公開先からのversion/signature/hash/内容readback | 実機品質、Human GO |
| HUMAN_GO | 人間による試聴、TalkBack、操作感、製品受入 | 全自動回帰の代替 |

`LOCAL_PASS → DEVICE_PASS → PROVIDER_PASS → PUBLIC_PASS → HUMAN_GO` は別の境界です。必要な層を省略・昇格せず、scopeと未確認を併記します。historical receipt、画像、health応答、子agentの文章は新しい上位passではありません。

## 0.18.0 moduleのコマンド

checkoutのJDK/SDKを設定してrepository rootから実行します。Windowsは `gradlew.bat`、他hostは `./gradlew` を使います。

```powershell
.\gradlew.bat :shared:desktopTest :shared:testAndroidHostTest :jvm-core:test :desktop:test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :desktop:packageWindows
```

関連するUI suiteと公開surface/配布検査も選びます。新module/Preview variantを追加したPRで実在するtask名をROADMAP/CIへ反映し、全体 `check`だけで全module実行とみなしません。文書だけならリンク、契約の整合、機密/個人path、必須CIを確認し、fresh buildと報告しません。

Macでは同じJVM/desktop suiteに加え、`:desktop:compileMacSystemAudioHelper`、`:desktop:desktopUiQualityTest`、`:desktop:desktopLongPressUiTest` を実行します。実ファイル・providerの取込は私有の隔離ライブラリで確認し、取得時間とdecode/再読込を分けて測ります。合成音源の比較ではフレーム・rate・左右・サンプル一致も確認します。

## 必須の振る舞い

- editing: 純reducer、plan/effect/commit/cancel、revision、Undo/Redo、busy/no-op、対象を固定した確認、stale job拒否。
- persistence: roundtrip、hash/channel/frame、未知schema/重複/path/ZIP bomb/上限、atomic publish、中断/容量不足、復旧3世代とUndo資産保全。
- audio: [AUDIO](AUDIO.md) の固定fixture、command frame、block一致、左右、quantize/tail、停止/steal/loopと負経路。
- UI: SOURCE→CHOP→PAD→step→再生→書出し→保存→再開→Undoの通し、文字倍率1.3/2.0、scroll/focus/keyboard。kit変更・loop・record/interrupt/route loss・共有/.choplib・アクセス不能資産を追加。
- online/AI: fake contractの後に明示された実accountで確認。取消/429/失効/遅い応答、metadataと音声の分離を試す。
- package: Windowsの実app-imageをWindowsで起動/停止、native処理を確認。Androidは対象variantのlint/assemble、signature/package/version/hashをreadback。

## 実機の所有

ADBは一つの明示serialだけを対象にし、全connected device taskを使いません。install前にowner/lease、対象APKと署名、残すデータを確認します。無断uninstallや端末初期化を行いません。合成fixtureと隔離profileを優先し、最終状態をreadbackして解放します。

既存の `config/choplab-review-avd.json` とemulator runnerは、対象AVD・source provenance・実test数・fatal/ANR・font/rotation復元を検査します。テストをcompileしただけでは実行済みになりません。emulator runnerがphysical serialを拒否する保護を保持してください。

性能試験はwarm-up、rate/buffer、機種、OS、冷間/熱時、反復数、p99/最大/underrunを添えます。サイズは同じbuild種別・圧縮形式で測り、初回DL・展開・利用者素材/cacheを分けます。human確認は最大5項目に絞り、未回答は未確認のまま残します。

研究資料は [Android audio/accessibility review](research/android-audio-accessibility-reference-review-2026-08-17.md) に保存しています。資料の日時とsource revisionを現在の検証結果へ読み替えません。
