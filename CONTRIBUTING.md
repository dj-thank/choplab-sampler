# Contributing to ChopLab

ありがとうございます。ChopLabはAndroid 10（API 29）以上を対象にしたオープンソースのモバイル・サンプラーです。

## 開発環境

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Gradle Wrapper（リポジトリ同梱）

ローカル環境の確認:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
```

Git BashまたはmacOS/Linuxでは:

```bash
./scripts/validate_project.sh
./scripts/doctor.sh
```

## 変更前後の確認

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

音声コールバック内では、ブロッキング、ファイルI/O、ログループ、ヒープ確保、重いJNI処理を行わないでください。音声フレーム範囲はstart-inclusive/end-exclusiveで扱い、ステレオ化する場合はチャンネル順を明示してください。

## プルリクエスト

- 変更の目的とユーザーへの影響を説明してください。
- 実行したコマンドと結果を記載してください。
- 実機で未確認の項目は未確認と明記してください。
- 大きなPro参照コードを一括コピーせず、コンパイル可能な縦切りで追加してください。

## 取り扱わないもの

音声データ、秘密情報、`local.properties`、署名鍵、生成APK、端末固有のSDKパスはコミットしないでください。DRM回避、録音ポリシーの回避、AKAI/MPCのロゴ・固有画面・プロジェクト形式の複製も行いません。
