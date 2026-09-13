# 検索して追加すると取得済み音源がライブラリに入る

## Purpose and user-visible outcome
通常の検索欄で曲名・アーティストを入力し「追加」。検索backendはSpotify、取得は既存YouTube経路。保存完了した音源のみライブラリへ表示。

## Current state
専用worktree choplab-spotify-auto-import-20260913、base f416362、root担当。お気に入りの自動同期は維持。

## Constraints and invariants
既存制作・音源・稼働EXEを保持。Spotifyはmetadataだけ。既存10分/256MiBの取込検証、永続重複防止、取消を再利用。検索10件、追加待ち100件上限。公開/実機/実認証は別の未確認事項。

## Architecture and interfaces
SpotifyDesktopSession検索状態をお気に入り状態と分離。SpotifySearchPanelは通常の検索・追加操作。SpotifyAutoImportが検索曲を予約し、現在の同期後に同じ保存処理へ渡す。

## Milestones / Progress
- 実装済み。API状態・検索応答parser・予約・検索→取得→実ファイル保存のhost testとUI操作確認、Windows再packageを実行中。

## Decision log
- 2026-09-13 user訂正: Spotifyはbackend。画面は「検索」「追加」。メタデータだけを追加済みとしない。

## Validation log
最終receiptに記録する。既存testsの重複実行・追加reviewは必要な場合だけ行う。

## Risks and rollback
候補照合はmetadata準拠で音響同一性の保証ではない。旧EXE一式を残し、新しいapp-imageへpackage。既存ライブラリを削除しない。

## Remaining device validation
実Spotifyログイン、実YouTube取得、音声、公開は未確認。
