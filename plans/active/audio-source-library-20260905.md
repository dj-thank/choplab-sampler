# Easy source import on Windows and Android

## User intent and scope
User requests easy audio addition, existing Luv(sic) session assets in the app's private library, YouTube download by the prior session method, and Spotify favorites as an import entry. User explicitly requires Android parity, not Windows-only. Root owns this worktree at dea87ed. Existing source session remains read-only; originals are copied into a private user library, never distributed in app/repository assets.

## Evidence and boundaries
Prior task01a0614e-ffc9-7a60-b090-6142c7343068 contains yt-dlp/FFmpeg commands (rollout custom call aroundline1424+). Existing originals: Part4 officialaudio MP4, QueMaravilha MP4, RoyPreparation MP3. No audio identification claim. Spotify official API exposes metadata/controls, not downloadable full audio. Favorites use one-tap corresponding YouTube search/import; ambiguous matches alone require selection, or local files; no Spotify cache extraction, DRM bypass, hidden recording, cookies or restricted-video bypass. Auth tokens stay in memory. No provider publication or device lease is assumed.

## Implementation
- [ ] Shared source hub and bounded personal-library storage; successful imports are durable, failures do not enter library.
- [ ] Windows FFmpeg multi-format decode and yt-dlp runtime; Android bundled yt-dlp/FFmpeg adapter, cancellation and bounded requests.
- [ ] URL/search -> matching candidate -> download -> library -> Chop flow on both platforms.
- [ ] Spotify favorites entry on Windows and Android via official OAuth; metadata-only query bridge to YouTube, clear source attribution.
- [ ] Preserve production when choosing another library source; retain legacy explicit-new-project operations.
- [ ] Copy stable existing originals into local private library with hashes, and provide portable user-library transfer for Android without including music in APK.
- [ ] Tests, runtime download/decode check where normal public access works, Android compile/runtime slice if independently available, full local gate and review.

## Completion and limits
LOCAL_PASS binds exact code/artifact bytes. YouTube live download is separately measured; Spotify account behavior needs actual ClientID/OAuth. Physical Android install/audio is separate from compilation. Android imports must not depend on a running Windows PC. Rollback only this task's diff and owned staging files; preserve all user originals/projects and other worktrees. Tool/runtime dependencies are pinned and attributed.
