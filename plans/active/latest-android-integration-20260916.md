# Latest local integration and Android parity — 2026-09-16

Owner: this Claude Code session is the sole writer of `work/choplab-latest-android-20260916` (branch `claude/choplab-latest-android-20260916`).

## Request

「pad 最新版にして andoroid も適用して最新版インストール」

## Scope

1. Merge the local production line `codex/choplab-spotify-auto-import-20260913@8a279bc` (Spotify automatic library import and search/add, built-in Windows drum separation, BEAT finishing hub) with the unmerged draft PR chain #92 → #94 → #95 → #96 → #97 (`origin/ui/readability-controls-20260911@348d341`).
2. Resolve conflicts without dropping either side: directory-locked autosave with metadata-first recovery; concurrent audio/project startup with pending-autosave ordering; the readable header with the loop-aware BEAT status; PR #97 SAVE geometry with the 2026-09-05 simplified four-row SAVE screen; the Compose four-argument transform gesture callback that PR #97 CI rejected.
3. Bring the Windows-only features to Android: Spotify liked-track automatic import, Spotify search → add, and built-in drum separation from CAPTURE.
4. Build and install: the Windows app image into the per-user install root with the signed-JDK launcher shortcut used by the current install; the Android debug APK onto the Pixel 9a with data-preserving `adb install -r` after package, version and signer checks.

## Out of scope

GitHub push or merge, tags, Releases, dependency PRs #98–#100, PR #89 CI hardening, signing material, and uninstall or clear-data on any device.

## Stop conditions and rollback

- A failing local test that cannot be fixed inside this scope stops installation.
- Android installation stops if the installed signer differs from the local debug signer, because replacing it would need an uninstall and would delete app data.
- Rollback: earlier Windows install directories stay in place and shortcut backups are kept; Android can be restored with an earlier APK signed by the same debug certificate.

## Gates

Target `LOCAL_PASS` for the merged product plus install and launch observation on Windows and, when reachable, the Pixel 9a. Physical audio quality, live Spotify/YouTube retrieval, on-device separation speed and Human acceptance remain separate gates.

## Progress

- [ ] Conflict resolution committed
- [ ] Full local gate: shared, jvm-core, desktop and app tests, lint, APK, Windows package, `scripts/validate_project.sh`
- [ ] Android Spotify automatic import and search
- [ ] Android drum separation
- [ ] Windows install and launch check
- [ ] Android install and launch check
