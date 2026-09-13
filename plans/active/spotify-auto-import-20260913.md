# Spotify連携からライブラリへ自動追加

## Purpose and user-visible outcome
WindowsでSpotify連携後、お気に入りを選ばず順次ライブラリへ取り込む。追加済みは再取得せず、候補は曲名・アーティスト・長さ・版の適合から自動選定。取得不可は結果に残し次へ進む。

## Current state
Root owner: task 01a098f9-d884-7b01-bdfe-2ffe86bac8a2。work/choplab-spotify-auto-import-20260913、base e1b9d42cdf0e7620964437e5023ffe3d83f39c0b。旧canonical dirty checkoutと稼働EXEを保持。

## Constraints and invariants
Spotifyはmetadata-only。音源は既存YouTube backend。tokens永続化なし。2000曲・単曲10分/256MiBの既存容量境界。active project/PAD/再生を自動変更しない。旧版app-imageへの上書きなし。Androidの手動Spotify導線は維持。

## Architecture and interfaces
desktop SpotifyDesktopSessionがbounded metadata paginationを担当、SpotifyAutoImportが接続と同期要求を一度だけ実行。jvm-core AudioSourceControllerの単一queueが検索・取得・検証・保存・中止を担当。LocalAudioLibraryのprivate .spotifyリンクは公開済みaudio IDのみを参照し、再起動後の重複取得を防ぐ。UIはライブラリと進捗・未取得結果を表示。

## Milestones
1. 自動queueと永続重複防止、接続からの配線、失敗と中止の回帰テスト。
2. shared UIの自動同期表示とdesktop component input確認。
3. project mandatory checks、Windows新規app image、起動shortcutのreadback。

## Progress
- 2026-09-13: implementation and focused regression validation in progress.

## Discoveries
起動中の既存インストール版にはDesktopYoutubeBackendがなく、9月5日の完成版に含まれている。9月5日app-imageは414ファイルhash一致。

## Decision log
- 2026-09-13: 同名の適合候補が複数でも長さ差と安定ID順で決定。非適合音源を強制取得せず未取得へ。画面を閉じても同期継続、明示中止/解除/アプリ終了は停止。
- Spotify paging: https://developer.spotify.com/documentation/web-api/reference/get-users-saved-tracks (2026-09-13 checked); fixed API endpoint with limit/offset, never follow an arbitrary next URL.

## Validation log
Pending: focused tests, full required project validation, Android unit/lint/build, Desktop package/UI tests.

## Risks and rollback
取り違えはmetadata適合で低減するが音響的同一性は未証明。rollbackは旧EXE起動、コードはisolated worktreeに保持。既存ファイルをreset/clean/deleteしない。新EXE起動前は既存制作の保存と旧版終了を要する。

## Remaining device validation
Live Spotify login/download、Windows native pointer/audio、Android physical、iOS、公開、Human未確認。LOCAL_PASSはsource/host-test/packageのみ。
