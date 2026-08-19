# Windows Desktop ChopLab MVP — 2026-08-19

## Purpose and user-visible outcome

Add a buildable Windows Desktop target without disturbing the existing Android AudioTrack MVP. The first vertical slice gives the user a desktop pad surface that can open and play a local WAV file, plus an explicit Spotify integration seam for PKCE login and metadata/playback-control calls. Spotify audio bytes are never imported, recorded, transformed, cached as a sample, or exported.

This is a local implementation milestone, not a finished Pro desktop sampler and not a Spotify-to-MP3 product.

## Current state

- Canonical source root: `C:/Users/rambo/Documents/ChatGPT/pad/work/codex-workspace/ChopLab-Codex-Workspace`.
- Implementation worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-exe-20260819`.
- Branch: `codex/choplab-desktop-exe`.
- Base revision: `6033d85b68c9b67f767a31b8878dbe4f4be3392c`, tree `39f8aa19e77b56acbd21c5bdde0f2aa911e6366f`.
- The canonical checkout had five tracked documentation changes and 169 status lines, mostly untracked evidence under `outputs/`, `plans/active/`, and `work/`. The five tracked changes and current plan documents were imported into this branch. Generated evidence, APKs, images, XML dumps, archives, and caches remain in the canonical checkout and are not product source.
- Baseline in this worktree: `:app:testDebugUnitTest` passed after creating an ignored machine-local `local.properties` pointing at `C:/Users/rambo/AppData/Local/Android/Sdk`.
- Existing production target is Android `:app`; `settings.gradle.kts` does not yet include a desktop target.

## Constraints and invariants

- Preserve Android minSdk 29 and the existing Android tests.
- Keep Android-specific capture/playback adapters out of the desktop core.
- Audio ranges remain start-inclusive/end-exclusive where the shared contract is reused.
- No Spotify stream ripping, DRM bypass, system-audio recording of Spotify, full-track download, content alteration, or Spotify audio export.
- Spotify OAuth uses Authorization Code with PKCE; no client secret is committed or embedded in the EXE.
- The desktop slice may use only user-selected local files. First decoder/output proof is WAV/PCM through JDK audio APIs; MP3/FLAC decoding and low-latency WASAPI are follow-up adapters, not silently claimed.
- No Spotify token is sent to Proxmox. Proxmox is out of scope for this local milestone; a later task may add project sync/backup only.
- Do not add user recordings, third-party audio, credentials, generated APKs, or machine-specific SDK paths to Git.
- Target gate is `LOCAL_PASS`; no device, provider, public, or Human gate is implied.

## Architecture and interfaces

The desktop target uses a small deep module seam so the UI does not know how local audio or Spotify transport is implemented.

```text
desktop/
  DesktopApp              Swing/JDK desktop shell for the first executable proof
  DesktopPadModel         4x4 pad state and local-source assignment
  LocalAudioPlayer        local file open/play/stop interface
  SpotifyOAuth            PKCE authorization URL and callback contract
  SpotifyApi              authorized metadata/playback-control HTTP calls
```

The first implementation uses JDK/Swing and `javax.sound.sampled.Clip` to avoid introducing an unverified UI/audio dependency before the seam is proven. The existing Android Compose UI and audio engine remain unchanged. A later milestone can replace the desktop UI and output adapter with Compose Desktop and WASAPI/miniaudio while retaining the tested interfaces.

The Spotify interface exposes track/playlist/current-playback metadata and control requests only. It must not expose a method named `download`, `record`, `exportAudio`, `renderSpotifyTrack`, or equivalent; the absence of an audio-byte interface is an invariant tested at the module boundary.

## Milestones

### Milestone 1: imported dirty baseline and desktop seam

- Scope: import tracked documentation and current plan state; add `:desktop` JVM application module and focused host tests.
- Files/interfaces expected to change: `settings.gradle.kts`, root `build.gradle.kts`, `desktop/build.gradle.kts`, `desktop/src/main/kotlin/**`, `desktop/src/test/kotlin/**`, this plan and registry.
- Implementation steps:
  1. Add Kotlin/JVM and application plugin configuration using the existing Kotlin version.
  2. Add a small local audio/pad model and JDK WAV playback adapter.
  3. Add a 4x4 desktop pad window with Open WAV, Play, Stop, and pad assignment feedback.
  4. Add PKCE verifier/challenge/state and loopback callback URL construction.
  5. Add Spotify API request builders that require an injected access token and return metadata/control responses, without audio payload methods.
- Tests/checks:
  - PKCE challenge uses the RFC/Spotify S256 base64url shape.
  - authorization URL contains exact redirect URI, state, client id, scopes, and code challenge.
  - state mismatch is rejected by the callback contract.
  - pad assignment and stop/play state are observable through public interfaces.
  - `:desktop:test`, `:desktop:jar`, and existing `:app:testDebugUnitTest` pass.
- Acceptance evidence: exact command output, Git diff, and a runnable desktop JAR. A GUI screenshot is optional and not a device/public gate.

### Milestone 2: Windows packaging and local project persistence

- Scope: add a self-contained Windows packaging task and import/export for the existing `.choplab` format after the core model is deliberately mapped.
- Not started by Milestone 1. Requires a separate review of schema ownership and desktop file/path bounds.

### Milestone 3: richer local audio and Spotify metadata UX

- Scope: add robust local decoder/output adapters, search/playlists/current playback UI, and Spotify attribution/open-in-Spotify links.
- Web Playback SDK is optional and remains a playback-only browser/embedded adapter. It never becomes a PCM source for ChopLab.

## Progress

- [x] 2026-08-19 — Created isolated implementation worktree and branch from exact current HEAD.
- [x] 2026-08-19 — Imported five tracked dirty documentation changes and two current plan documents without touching the canonical dirty checkout.
- [x] 2026-08-19 — Re-established ignored local Android SDK path and confirmed `:app:testDebugUnitTest` baseline.
- [x] 2026-08-19 — Added the desktop module and observed RED at the PKCE/pad seams before implementation.
- [x] 2026-08-19 — Implemented the minimum desktop UI/audio and Spotify request/token contracts without a Spotify audio-byte path.
- [x] 2026-08-19 — Ran desktop tests, built the JAR/app-image, and smoke-started the exact EXE through the tracked process wrapper.
- [x] 2026-08-19 — Re-ran the Android unit/lint/debug build and desktop tests/package after the final module and documentation diff.
- [x] 2026-08-19 — Completed the parent two-axis standards/spec review; no blocking findings remain before commit.
- [x] 2026-08-19 — Committed the merged desktop slice on `codex/choplab-desktop-exe`; the worktree is tracked-clean after the commit.

## Discoveries

- 2026-08-19 — The canonical dirty checkout contains documentation edits plus operational evidence, but no dirty `app/src` implementation files. Treating all evidence directories as product source would make the EXE branch non-reproducible and enlarge the distribution surface, so they remain preserved at their canonical paths.
- 2026-08-19 — The first Android baseline attempt stopped because the new worktree did not have ignored `local.properties`; after adding a worktree-local SDK path, the unit-test baseline passed.
- 2026-08-19 — The first fixed PKCE challenge expectation was wrong; an independent SHA-256/base64url calculation corrected the test literal, and the implementation then passed.
- 2026-08-19 — `jpackage --type app-image` produced a self-contained Windows launcher with JDK 17. This is an unsigned local app-image, not a public installer.
- 2026-08-19 — The tracked-process stop helper rejected the verified PID because its JSON date was coerced through the local PowerShell culture; the raw lease timestamp and CIM creation time matched exactly, so only PID 10824 was stopped and the lease was marked `stopped`.

## Decision log

- 2026-08-19 — Use a separate worktree and branch because the canonical checkout is operationally dirty and contains user-owned evidence. This avoids reset/clean and makes the desktop diff reviewable.
- 2026-08-19 — Use a JDK/Swing desktop proof first rather than adding a large Compose Desktop/audio dependency graph before a buildable desktop seam exists. Revisit Compose Desktop after the local audio and OAuth contracts pass.
- 2026-08-19 — Spotify integration is metadata/control-only. OAuth authentication does not grant a right to download or convert Spotify Content; the implementation deliberately has no Spotify audio-byte path.

## Validation log

- `./gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1` — 2026-08-19, JDK 17 / Gradle 9.5 / Android SDK from worktree-local `local.properties` — `BUILD SUCCESSFUL`.
- `./gradlew.bat :desktop:test --no-daemon --max-workers=1 --no-watch-fs` — 2026-08-19 — `BUILD SUCCESSFUL`, 11 tests, 0 failures/errors/skips.
- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktop:test :desktop:packageWindows --no-daemon --max-workers=1 --no-watch-fs` — 2026-08-19 — `BUILD SUCCESSFUL`, 60 actionable tasks (4 executed, 56 up-to-date).
- `./gradlew.bat :desktop:packageWindows --no-daemon --max-workers=1 --no-watch-fs` — 2026-08-19 — `BUILD SUCCESSFUL`; launcher `desktop/build/windows-app-image/ChopLab/ChopLab.exe`, size 449,024 bytes, SHA-256 `7F54213F5D17201BF4FA9CF05E7D5CB5AAD78AA3F76D1A0D6AD213CE4C56D49C`; JAR SHA-256 `67165D5A94907932D49D997D68DD567B54D5F9DE7EDFB2E8D17CA3423352A73E`.
- EXE smoke — 2026-08-19 — tracked process wrapper started the final app-image as PID 10824, verified `ChopLab.exe` was alive/responding with the expected executable path and creation time, then stopped that exact PID; lease status is `stopped`, with no remaining `ChopLab` process.
- `git diff --check 6033d85b68c9b67f767a31b8878dbe4f4be3392c..HEAD` — 2026-08-19 — `POST_COMMIT_DIFF_CHECK_PASS`.
- `scripts/validate_project.sh` — 2026-08-19 — Git Bash reached the pure Kotlin phase but stopped at `kotlinc: command not found`; XML parsing and Gradle wrapper SHA checks passed separately. This is an environment/toolchain limitation, not a desktop test failure.

## Risks and rollback

- Risk: desktop UI/audio adapters diverge from Android semantics. Mitigation: keep the first seam small, add tests at the public interface, and do not claim Android/desktop parity yet.
- Risk: token leakage. Mitigation: no client secret, no committed token, access token injected at call time, in-memory proof until a Windows credential-store adapter is separately reviewed.
- Risk: accidental Spotify content capture. Mitigation: no audio-byte interface, no WASAPI loopback capture in this milestone, and policy tests/docstrings at the Spotify seam.
- Rollback: delete only the `codex/choplab-desktop-exe` branch/worktree after review if the user rejects the direction. Never reset, clean, or delete the canonical checkout's dirty evidence.

## Remaining device validation

- Windows audio device latency, WASAPI route changes, microphone capture, and subjective pad feel are not covered by this milestone.
- Spotify Premium playback behavior, account/device availability, provider restrictions, and public distribution approval are not covered by local tests.
