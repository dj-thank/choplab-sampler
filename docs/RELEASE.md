# 配布とreleaseの契約

進捗と実際に取得可能な配布物は [ROADMAP](ROADMAP.md) と各releaseへ記録します。以下は再構築の配布要件です。段階1Cのworkflow/署名/readbackが通る前に、新方式で配布済み・保護済みとは報告しません。

## 種類・identity・公開

- version/buildNumberは `gradle.properties` の `choplabVersion` / `choplabBuildNumber` 一つから生成。moduleごとに手書きの版数を増やさない。
- Preview CI artifactの作成とGitHub Releaseの公開を分ける。PreviewはAndroid `com.choplab.sampler.preview`、ja「おとひろい Preview」/en「Earth Song Preview」、正式版と別の継続署名鍵。authority/OAuth/deep linkとWindowsデータ領域も併存確認。
- 正式release tagはmain由来の `v${choplabVersion}`。rootが認可された公開範囲を実行し、必要check、差分、対象commit、署名、公開するbytesを直前確認する。既存tag/assetを上書きせず、失敗した公開のversionを再利用しない。
- 段階1Cでsigned Android APK、Windows zip、SHA-256、依存一覧/SBOM、source manifestを一組にする。secretがなければdebug/unsignedへ黙ってfallbackしない。Windowsのcode-signing状態はAndroid署名と別に明記。
- fork PRへ署名鍵を渡さない。署名はtrusted commitの配布runに限り、workflowの権限は既定read-only、publication jobだけ必要なwrite scopeを持つ。
- 配布後に公開先からartifactを取得してpackage/version/signature/hash/内容をreadback。CIの成功やアップロード応答だけでPUBLIC_PASSにしない。

Windows app-imageはprivate Java runtimeを含む一式であり、EXE単体ではありません。署名installer・更新は段階11。既存版とPreviewは設定/autosave/library/cache/lockを分け、署名変更を含む正式切替前に音声救出と復元を確認します。利用者の既存app/dataを削除して問題を回避しません。

## 鍵と設定の区分

| 設定名 | 内容 | 取り扱い |
|---|---|---|
| `CHOPLAB_ANDROID_KEYSTORE_BASE64` | stable release keystore | secret。repository/protected environmentに保管 |
| `CHOPLAB_ANDROID_STORE_PASSWORD` | keystore password | secret |
| `CHOPLAB_ANDROID_KEY_ALIAS` | release-key alias | signing設定。CI secretとして扱う |
| `CHOPLAB_ANDROID_KEY_PASSWORD` | key password | secret |
| `CHOPLAB_ANDROID_CERT_SHA256` | 期待するrelease証明書のSHA-256 | 公開可能なfingerprint。private keyと混同しない |
| `CHOPLAB_SPOTIFY_CLIENT_ID` | 登録したSpotify appの識別子 | public ID。値と適用mode/scopeを確認して設定し、tokenとは別管理 |

Previewの鍵名/証明書は実装PRで明示し、正式鍵を使い回しません。keystoreと復旧情報はoffline暗号backupを保持。鍵の喪失/回転は通常CI変更ではなく製品移行です。秘密値をlog、artifact、サンプル、制作ファイルに入れません。

## 維持する検査能力

source/history scannerを短くすること自体は合格条件ではありません。旧検査の検出例・誤検出例を移し、新旧比較の後に置換します。

1. current tracked/nonignored候補とreachable Git historyを別に検査。key/token/private signing、個人path、音声/モデル/binary、symlink targetを扱い、secret-shaped findingはredactして出力。
2. Androidのsignature fingerprint、debuggable、package/version、permission/exported component、alignmentを検査。正式/Preview/debugは明示した別契約であり、見つかったbytesを後から適切な種類と呼び替えない。
3. Windows全app-imageのversionとruntime/library/resourceを含むhash、最終APK/ZIP内部を検査。source検査だけでは生成物の安全を証明しない。
4. ZIPは展開前にentry/path/count/size/CRC/local-central整合、compressed span/descriptorの連続所有を検証。safe名のtextでも音声magic/secretを走査。UTF-16/32、comment/extra field、nested ZIPも対象にする。
5. 既存上限の意味を保持: 4,096 entries、通常text512KiB/member、metadata/output4MiB/archive、decoded text入力4MiB、100:1、LZMA辞書16MiB。nested depth3、64archives、16MiB/member、256MiB共有container/expanded上限。root/history集計やJIMAGEの例外も無制限化せず、置換にはattack/negative fixtureを付ける。
6. 正式download後、manifest/hash/attestation/publication前にも最終配布surfaceを検査。SBOMとsource/artifact identityを結び付け、依存licenseと再配布条件をNOTICEへ反映。

公開証明書とprivate keyを区別し、合成fixtureの許容は必要箇所だけに限定します。第三者依存のGPL等はNOTICE追記だけで完了にせず、combined workの配布条件・対応source・build手順を確認します。

## CIと運用のreadback

段階1CではPR/main push/手動を使い、branch push重複を抑えます。移行中は `verify`、`Test and package EXE`、`History scan and dependency SBOM` の既存check名を維持。Linuxはcommon/JVM/Android test・lint・APK・小emulator、WindowsはOS test/package/start-stop smoke、policyはsource/history/artifactを担当します。skip時もrequired checkがpendingのまま残らない構成にします。artifactは7日を基本とし、SBOMの重い収集はrelease/full runへ集約可能です。

main/tag rules、CODEOWNERS、review、force-push/delete禁止、secret scanning/push protection、private vulnerability reportingは管理者設定です。設定のreadbackなしに有効と断定しません。404/403は保護存在の証拠になりません。失敗や保護を迂回せず原因を示します。

```bash
gh api repos/dj-thank/choplab-sampler/rulesets
gh api repos/dj-thank/choplab-sampler/branches/main/protection
gh api repos/dj-thank/choplab-sampler/actions/permissions/workflow
```

配布を受け取る人は同梱SHA-256を照合し、attestationがある場合はrepository/sourceと一致するか検証します。attestationはplatform code-signing、脆弱性不存在、実機品質の保証ではありません。
