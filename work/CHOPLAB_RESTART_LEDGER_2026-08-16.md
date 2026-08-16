# ChopLab restart ledger from recent JSON history — 2026-08-16

## Authoritative checkout

- Repository: `C:\Users\rambo\Documents\ChatGPT\pad\work\codex-workspace\ChopLab-Codex-Workspace`
- Branch: `agent/gpt-pro-ui-integration`
- HEAD/shared main: `571d9bcdd030d18f7876db3731708a3fe439068a`
- Tracked/staged changes: none at recovery time
- Untracked `outputs/` and `work/` evidence is preserved and must not be deleted or bulk-staged.

## Recovered user intent

1. Finish the phone-first MPC workflow, not only isolated waveform code.
2. Treat portrait fit, one-hand reach, 48 dp targets, readable labels, and recoverable destructive actions as acceptance criteria.
3. Complete SourceWaveform pinch focus, pan, reset, overview, endpoints/handles, fine movement, and TalkBack actions through observable contract tests.
4. Unify BACK, STOP/STOP ALL, UNDO/REDO, and recording/playback ownership across screens.
5. Keep pure operation/history seams testable with red-to-green evidence; run focused checks regularly and full tests/lint/APK/diff review at the end.
6. Preserve every existing change and project archive; no uninstall, clear data, destructive replacement, unrelated revert, push, publication, or remote mutation.
7. Commit and integrate safe verified work into shared main; preserve generated artifacts and temporary evidence separately.
8. On Pixel 9a, verify exact serial/package/version/signer before `adb install -r`, preserve autosaves, then prove interactive device behavior.
9. Keep `LOCAL_PASS -> DEVICE_PASS -> PROVIDER_PASS -> PUBLIC_PASS -> HUMAN_GO` separate; subjective sound/usability stays Human evidence.
10. Continue safe local work while another app/task owns the device; never steal foreground input.

## Current evidence

- LOCAL_PASS: 220 unit tests, lint errors 0, debug/release APK assembly, and `git diff --check` passed at `f3fdab6`.
- DEVICE_DEPLOY_PASS: Pixel 9a serial `5A121JEBF08094`; signer matched; `adb install -r` succeeded; MainActivity launched.
- RETAINED_DATA_PASS: three autosave hashes matched before/after deployment.
- Current device owner conflict: `jp.sampo.debug/jp.sampo.MainActivity` is foreground, so ChopLab input E2E is paused.

## Remaining gates

- DEVICE_INTERACTION: portrait/one-hand reach; pinch/pan/reset/overview; endpoint/handle gestures; STOP/BACK/UNDO/REDO; destructive recovery.
- DEVICE_ACCESSIBILITY: TalkBack previous/next/reset, labels, focus order, and touch behavior.
- DEVICE_AUDIO_OWNERSHIP: source/pad/preview/loop/transport/recording exclusion without starting real recording or external export unnecessarily.
- DEVICE_RESILIENCE: rotation recovery, relaunch/process recreation, fatal/ANR/logcat scan, and autosave hashes after interaction.
- LOCAL_FOLLOW_UP: large `OtohiroiDeck.kt` decomposition and waveform/render performance measurements remain useful but must not replace the device gate.
- HUMAN_GO: subjective reachability, gesture feel, sound quality, and the ten-minute end-to-end product flow require the user.

## Next safe action

Recheck foreground ownership. Only when ChopLab is already foreground or the device is explicitly released,
run the bounded non-recording DEVICE interaction matrix on serial `5A121JEBF08094`, capture screenshots/UI trees/logcat,
recheck autosave hashes, and update the device receipt. Until then, continue read-only/local work only.
