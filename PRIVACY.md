# ChopLab プライバシー方針

最終更新: 2026-08-19

ChopLab は、音声制作を端末内で行うオープンソース Android / iOS アプリです。現在のアプリはインターネット権限や独自サーバーへの音声アップロードを実装していません。広告、アカウント、分析 SDK、クラッシュ送信も行いません。

## 扱うデータ

- 読み込んだ音声、マイク録音、端末音声録音、チョップ、PAD、シーケンス、設定。
- アプリ内の自動保存と一時録音はアプリ専用領域に保存されます。一時録音WAVは音声への変換が成功・失敗・取消のいずれで終了しても削除し、異常終了で残ったChopLab命名の一時録音だけを24時間後の起動時清掃対象にします。
- WAV と `.choplab` プロジェクトは、Android のファイル選択画面でユーザーが指定した保存先にのみ書き出します。
- アプリは Android バックアップを無効にしています。ユーザーが書き出したファイルは、選択した保存先の管理方法に従います。
- iOSで選択した音声は、security-scoped file accessを使って読み取り、アプリ専用のApplication Support内へコピーしてから再生します。ユーザー音源はこのGitリポジトリやGitHub Releaseへ送信・同梱しません。

Windows desktop preview is separate from the mobile permission model. Its optional Spotify login sends OAuth requests and current-playback metadata/control requests to Spotify, keeps the first-slice token in memory, and does not upload local WAV files or expose Spotify audio bytes to the sampler.

## 権限と目的

- `RECORD_AUDIO`: マイク素材、ボーカルテイク、および Android の端末音声キャプチャ開始に必要です。要求した操作を開始するときだけ許可を求めます。
- MediaProjection の画面共有同意: 端末音声録音を開始するたびに Android の確認画面を使います。録音元アプリが Playback Capture を許可した音声だけが対象です。
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: 端末音声録音を、Android が管理するフォアグラウンドサービスとして実行するために使います。
- `POST_NOTIFICATIONS`（Android 13 以降）: 端末音声録音中のサービス通知に使います。拒否してもアプリ内の停止操作は残ります。
- iOSのマイク権限: iOS版で録音を開始したときだけ、録音素材を作る目的で要求します。拒否した場合は録音を開始しません。

DRM、録音元アプリの制限、OS の権限を回避しません。録音・サンプリングする音源について必要な権利と利用条件を確認してください。

## iOS previewの境界

公開Releaseに含めるiOS artifactは署名なしのSimulator `.app.zip`だけです。Apple Developer certificate、provisioning profile、private signing key、App Store Connect credentialはソース、GitHub Actions artifact、Release asset、ログへ置きません。iPhone/iPadの実機配布には利用者自身の署名環境が必要です。

## 消去と持ち出し

アプリ内の「新しい制作を始める」は現在の制作状態を空にします。破損や保存途中から復旧するため、アプリ専用領域には直近の検証済み自動保存を最大三世代保持します。この操作は安全コピーを含む完全消去ではありません。アプリをアンインストールするとアプリ専用領域は Android により削除されます。ファイル選択画面から書き出した WAV や `.choplab` は自動削除されないため、保存先のファイルアプリで管理してください。

一時録音の清掃対象は、アプリ専用cacheの`captures`内にChopLab自身が`microphone_数字.wav`、`system_数字.wav`、`vocal_数字.wav`として作成したファイルだけです。ファイル選択画面で読み込んだ音声、ユーザーが書き出したWAV、`.choplab`プロジェクトはこの清掃で削除しません。

## 確認と問い合わせ

実装は公開リポジトリで確認できます。不具合やプライバシー上の懸念は、個人の音声・プロジェクト・認証情報を添付せずに GitHub Issues へ報告してください。
