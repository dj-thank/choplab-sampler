# ChopLabへの参加

[AGENTS](AGENTS.md) と [ROADMAP](docs/ROADMAP.md) を読み、対象の機能と現在の担当を確認してください。AndroidとWindowsの共通の振る舞いを小さな変更で改善します。

## 開発環境

0.18.0の基準は JDK 17、Android SDK Platform 37、Build Tools 36.0.0、同梱Gradle Wrapperです。実際に必要な版はcheckoutのGradle設定を使います。JDK21や依存更新は段階1Bの候補であり、この文書だけでは導入済みになりません。

WindowsではPowerShell 7を優先します。SDKは既存環境を利用し、`local.properties`、SDKパス、APIキーをコミットしません。

```powershell
.\scripts\doctor.ps1
.\gradlew.bat :desktop:run
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS/Linuxの開発チェックには同梱 `gradlew` と `scripts/doctor.sh` を使えます。Windows固有の音声・ダイアログ・パッケージ起動はWindowsで確認します。検証コマンドと層の違いは [TESTING](docs/TESTING.md) を参照してください。

## 変更とPR

1. ROADMAPの一つの作業を選び、基準revision・対象・成功条件・停止条件を決めます。
2. 一つのwriterが小さな変更を担当し、利用者データと無関係なdirty変更を保持します。
3. 影響する振る舞いを下の層から確認し、差分と必要なCIを確認します。
4. PRに問題・変更後の動作・確認結果・未確認項目を書き、ROADMAPへ結果と次の一手を反映します。

文書だけの変更にはリンクと内容の確認で十分です。意味のない件数達成や同じ台帳の複製を避け、実際の回帰を防ぐテストを残します。音の変更は [AUDIO](docs/AUDIO.md)、配布の変更は [RELEASE](docs/RELEASE.md) の契約を満たしてください。依存の追加時はNOTICEと配布条件も更新します。

音声・制作ファイル・鍵・tokens・生成物・個人パスは添付しません。再現には合成素材を使い、脆弱性は [非公開の報告手順](SECURITY.md) に従います。
