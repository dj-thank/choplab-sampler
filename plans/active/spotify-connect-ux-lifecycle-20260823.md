# Spotify Connect UX lifecycle — 2026-08-23

## Contract

- Target root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-spotify-connect-20260823`, root integrator.
- Current baseline: `2500bbdbc8542d6de0470509f9f56b113719b2d0`.
- Scope: Windows `:desktop` Spotify Authorization Code with PKCE, metadata, current playback, saved-track library, and Spotify Connect pause/resume UX.
- Excluded: Spotify Content download, stream ripping, audio capture/recording, audio extraction, MP3 conversion, token/secret persistence, real account/browser OAuth, provider write other than the user-triggered existing playback controls, public release, and Human acceptance.
- Rollback: revert only this isolated worktree's feature commit; do not reset/clean the canonical dirty checkout.
- Stop: a change needing a real Spotify account, a Client Secret, user media, a provider write outside pause/resume, or publication becomes a separate authorised task.

## Acceptance criteria

1. UI represents `Client ID未設定 → 接続準備完了 → 認証中 → 接続済み → 接続エラー` without stale state.
2. Cancel and disconnect invalidate late OAuth completions; one busy operation at a time.
3. 204 playback, empty/malformed library, 401/403/404/429/5xx, and OAuth failure lead to actionable Japanese recovery guidance.
4. Client ID and tokens remain memory-only; UI never accepts a Client Secret; no Spotify audio byte/download/record/conversion API is added.
5. A keyboard-accessible Compose panel exposes state and guidance with polite live-region semantics.
6. Focused lifecycle/unit tests, full desktop tests, Windows app-image package, and public-surface/denylist scans pass.

## Evidence ceiling

The target is `LOCAL_PASS`. Unit tests and packaged bytes do not prove browser OAuth, Premium status, development-mode allowlisting, a Spotify Connect device, physical keyboard/screen-reader behavior, audio quality, provider operation, publication, or `HUMAN_GO`.

## Completion record

- Integrated branch: `codex/choplab-spotify-connect`; merge base `9a4e9edc2686914c28c91b2d614dfb95281935c2`; implementation HEAD `5e8da43fafd9642bead2c75d1bb28f1beb0dface`.
- Acceptance criteria 1–6 are complete at `LOCAL_PASS`. Final desktop tests are 62/62, the Windows app-image verifies as `0.16.2`, packaged launch responds, and current/history public-surface plus package denylist scans pass.
- Review-driven additions reject malformed environment Client IDs, distinguish OAuth denial/default-browser/loopback/network failures, expose an explicit library summary, honor the current Development Mode search limit, stream iOS imports under a hard byte budget, and validate Android decoder output format.
- The security review is sealed with complete 48/48 coverage and zero reportable findings. The one suppressed iOS robustness candidate was remediated and regression-tested at source level; Windows cannot execute the iOS target.
- Pixel install is prepared but not run because serial `5A121JEBF08094` is absent from ADB/mDNS/USB inventory. When attached, the only authorised deployment path is the existing clean-source collector using data-preserving `adb install -r`; no uninstall or data clear.
- Provider/public/Human gates remain deliberately separate and are not blockers to this local implementation plan.
