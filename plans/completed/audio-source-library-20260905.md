# Easy source import on Windows and Android

## User intent and scope
User requests easy audio addition, existing Luv(sic) session assets in the app's private library, YouTube download by the prior session method, and Spotify favorites as an import entry. User explicitly requires Android parity, not Windows-only. Root owns this worktree at dea87ed. Existing source session remains read-only; originals are copied into a private user library, never distributed in app/repository assets.

## Evidence and boundaries
Prior task01a0614e-ffc9-7a60-b090-6142c7343068 contains yt-dlp/FFmpeg commands (rollout custom call aroundline1424+). Existing originals: Part4 officialaudio MP4, QueMaravilha MP4, RoyPreparation MP3. No audio identification claim. Spotify official API exposes metadata/controls, not downloadable full audio. Favorites use one-tap corresponding YouTube search/import; ambiguous matches alone require selection, or local files; no Spotify cache extraction, DRM bypass, hidden recording, cookies or restricted-video bypass. Auth tokens stay in memory. No provider publication or device lease is assumed.

## Implementation
- [x] Shared source hub and bounded personal-library storage; successful imports are durable, failures do not enter library.
- [x] Windows FFmpeg multi-format decode and yt-dlp runtime; Android bundled yt-dlp/FFmpeg adapter, cancellation and bounded requests.
- [x] URL/search -> matching candidate -> download -> library -> Chop flow on both platforms.
- [x] Spotify favorites entry on Windows and Android via official OAuth; metadata-only query bridge to YouTube, clear source attribution.
- [x] Preserve production when choosing another library source; retain legacy explicit-new-project operations.
- [x] Copy stable existing originals into local private library with hashes, and provide portable user-library transfer for Android without including music in APK.
- [x] Tests, runtime download/decode check where normal public access works, Android compile/runtime slice if independently available, full local gate and review.

## Completion and limits
LOCAL_PASS binds exact code/artifact bytes. YouTube live download is separately measured; Spotify account behavior needs actual ClientID/OAuth. Physical Android install/audio is separate from compilation. Android imports must not depend on a running Windows PC. Rollback only this task's diff and owned staging files; preserve all user originals/projects and other worktrees. Tool/runtime dependencies are pinned and attributed.

## Integration checkpoint
Product 6f7bc7c36a3ee667d4890eeed9c2c762f5e784e4. Standard test suites: 826 passed, 0 failures/errors/skips. Android lint/APK/test-APK build passed. Default Windows image was in use; packaging completed in desktop/build/windows-app-image-audio-source-20260905 without stopping the existing app. Final shared UI/project validation passed on the unchanged recheck; a prior existing loop test timed out once. This is recorded rather than claimed repaired.

Real Spotify Desktop OAuth and saved-track metadata returned 20 entries; no track titles or credentials were persisted in evidence. User approved adding Android choplab://spotify/callback to the existing ChopLab app; both redirect URIs were read back. Browser callback response race found in live test was fixed and a concurrent receiver regression passed. Windows YouTube Part 4 public download decoded successfully. These are scoped provider observations, not Android physical verification.

Four existing originals were copied into the actual Windows private library after decode; the original session's files were untouched. PAD outputs/ChopLab-personal-audio.choplib provides explicit Android file import. Music is absent from APK/repository/public artifacts. Transfers are capped at 32 audio entries/128 total entries/1GiB expanded; oversize export refuses before touching its destination. Files are validated by each platform's decoder before publication; bundle validation stages all entries. Unexpected commit-time filesystem I/O failure can retain earlier valid entries, never unvalidated bytes.

Both independent reviewers' required code findings were addressed. Actual Android import/download/audio and iOS remain unverified. Android uses its pinned bundled extractor, with no blocking network updater at first import. An extractor update requires a new tested app build.
