# H19 Windows startup smoke — 2026-09-01

## Fixed object

- Source HEAD/tree: `0dcf618ac09b2a5a596019895fe4159eee44d520` / `4fd89db62152a6495685023693334ed2396f220f`
- H16 EXE SHA-256: `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`
- H16 ZIP SHA-256: `1E4A5541B5FD233B4A9C55B1BD601EE307BF2E4BB7760DB5317C8E4AFDA247DA`
- Launch arguments: none; working directory was the H16 app-image.

This is historical startup evidence for the fixed H16 package, not for later merged or released bytes.

## Result

Final attempt 03 established `LOCAL_STARTUP_PASS`:

- The window title was `ChopLab — おとひろい PC` with a nonzero main-window handle.
- Launcher and same-EXE window-owner identities were observed; no unexpected different-path child appeared.
- The window remained responsive for 17.928 seconds across 9 samples, including 6 stable samples.
- TCP endpoints: 0; UDP endpoints: 0.
- `CloseMainWindow` returned true; forced cleanup was not used; remaining owned processes: 0.
- Only the isolated child profile changed: 3 Skiko runtime files, 2 isolated autosave files, and 4 shader-cache files. The real user profile was not used.

Earlier attempts 01 and 02 were retained as harness-classification failures: the launcher handoff and same-EXE child were initially misclassified. They were not counted as product startup failures, and exact process read-back found no remaining owned process.

## Gate

`LOCAL_STARTUP_PASS` for a responsive packaged window, bounded stability, zero observed network endpoints, and graceful close. No click, typing, file import, audio playback, recording, microphone, Spotify/OAuth, accessibility, signing, publication, or Human acceptance was performed.
