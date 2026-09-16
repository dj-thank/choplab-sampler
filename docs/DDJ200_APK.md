# DDJ-200版をAPKからインストールする

この追補は2026-09-16の「クリックしたらAPKとして読み込めるように」という依頼に対応する。
PR #102のDDJ-200実装を、ZIPの展開なしでダウンロードできるAndroid試験版として配布する。
従来の「APK配布なし」という記録はこの追加依頼より前の状態。通常版のマージやv*リリースは行わない。

## インストール

成功した `DDJ-200 installable APK preview` のSummary、またはその実行が作成した
`ddj200-preview-*` Pre-releaseの **APKをダウンロード** を開く。
ダウンロードした `Otohiroi-DDJ200-preview.apk` をAndroidで開き、インストールを確認する。
Androidから求められた場合に限り、そのファイルを開くブラウザ/ファイルアプリに
「この提供元のアプリを許可」を設定する。Play Protectを無効化する必要はない。
APKが生成・公開される前の実行はダウンロード可能と扱わない。

アプリ名は **おとひろい DDJ-200試験版**。Android 10以降。
通常版とは異なるID `com.choplab.sampler.ddj200preview` で並行インストールする。
通常版・通常版の保存データを削除しない。保存データは自動共有されない。
必要なプロジェクトは通常版からファイルへ保存し、試験版で明示的に開く。

デバッグ署名の試験版であり、通常版の上書きアップデートではない。
CIのデバッグ署名は実行間で変わり得るため、以前の試験版への上書きも保証しない。
署名不一致でインストールできない場合、通常版をアンインストールしない。
試験版を削除する場合も、先に試験版の作品をファイルに保存する。
本番の署名鍵を取得・変更・公開する処理はない。

## DDJ-200をつなぐ

USB給電したDDJ-200を他のDJアプリから切断し、試験版の
**DDJ-200 → Bluetoothで接続する → 権限許可 → DDJ-200を選択** を操作する。
実機接続・LED点灯・音声分離・音質/遅延は、APKのビルド成功とは別の未確認項目。
[機能・制限・物理確認項目](DDJ200.md) を参照。

## 配布経路と検査

`-PchoplabDdjPreview=true` を明示したdebugビルドだけを別ID・別表示名の試験版にする。
通常のdebug/releaseではID・署名・版数を維持する。試験版も既存の
`testDebugUnitTest`・`lintDebug`・`assembleDebug` を同じ明示プロパティで実行する。
初回のカスタムbuildTypeはホストで単体テストtaskが生成されなかったため廃止した。
テストの省略ではなく、既存の検証可能なdebug経路を使用する。
`.github/workflows/ddj200-apk.yml`は指定された自分のリポジトリのDDJブランチだけで動作する。
PRから公開しない。ビルドジョブにはread権限だけを与え、テスト・lint・APK検査・
公開対象スキャンを通過した同一実行の成果物だけを別のpublishジョブへ渡す。
失敗・未完了のビルドをリリースしない。

公開前に実APKのID・版数・SDK・権限・外部公開コンポーネント・デバッグ区分・
APK署名・16KiB対応alignment・testOnly無効を検査する。SHA-256とsource commit、署名の指紋を同梱する。
秘密鍵・keystore・ユーザー音声・トークンは成果物に含めない。
公開はrun/attempt/sourceで一意な新しいPre-releaseを作成し、既存タグ・資産を上書きしない。
本番用v*リリースとlatestの指し先を変更しない。

ローカル検査: `python3 -m unittest discover -s scripts/tests -p 'test_ddj_preview.py'`。
実APKの検査: `scripts/stage_ddj_preview.py`。
Android SDKのない環境でPython検査だけ通してもAPK作成済みとは言えない。

## 受入基準

- [ ] このソースのpreview用単体テスト・lint・assembleが成功。
- [ ] 実APKの署名・metadata・公開対象スキャンが成功。
- [ ] 作成したReleaseのasset URLからAPKが取得できる。
- [ ] Android実機で通常版を保持したままインストール・起動できる。
- [ ] 実機DDJ-200のBluetooth接続と操作を確認。

成功の証拠はPR #102のコメントと該当Actions実行で記録する。
