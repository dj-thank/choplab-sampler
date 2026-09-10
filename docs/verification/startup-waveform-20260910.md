# PR #93 startup / waveform validation receipt

## Scope

Requested repair on `fix/startup-waveform-20260910`, baseline `bed7a550a71b1ae91556b2b2af25d7c482083c98`. Product integration is commit `4e4dd5e4e0e1c8f72ed2e1d7b380eed7e889bb62`; subsequent commits preserve that code while correcting evidence placement. See `plans/active/startup-waveform-20260910.md` for design and device acceptance checks.

## Host result

16/16 standalone Kotlin host test bodies passed: 11 waveform, 5 startup/concurrency/cancellation. The committed app test imports JUnit Assert to match its existing dependency; the standalone runner uses Kotlin assertions and no test annotations. This verifies pure production helpers and the extracted envelope seam, not Android/Compose builds, JUnit discovery or actual microphone behavior.

## Integration result

Run `34425650967` applied exact-hash guarded source replacements and passed `git diff --check`. The temporary patch workflow/script were then removed; no new persistent automation is retained.

## CI failure found and correction

At head `edbb116205dbb18bf909e44e6aafffdf1b9f26c6`, Android PR run `34425941580`, job `102711071344`, passed the public-surface check but failed `test_project_state_has_one_top_current_snapshot` in the release-policy tests (214 run, 1 failure, 2 skipped). The new heading in `docs/PROJECT_STATE.md` conflicted with the repository's fixed current-release snapshot contract. Gradle/build steps were consequently skipped.

Correction: restore `docs/PROJECT_STATE.md` byte-for-byte to its base blob `9ae7a62f0849c108061eff5ded5be6844bce47da`. This scoped repair receipt, the ExecPlan and the appended `docs/VALIDATION.md` section carry the new evidence instead. The existing release-policy test is NOT weakened, bypassed or removed, and the existing release snapshot is NOT reclassified. This failure is introduced by our documentation change; it is not claimed to be a baseline or environmental failure.

The next CI revision must be evaluated independently; the failed run is not a build success. Physical cold-start timing, first playback, real microphone recordings and waveform layout remain unverified. No measured speedup percentage, audio-level increase, APK delivery or release is claimed.
