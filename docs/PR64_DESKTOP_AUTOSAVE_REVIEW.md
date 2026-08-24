# PR #64 Desktop autosave review receipt

This revision-bound receipt covers the focused Desktop startup-recovery, source-device, and close-time autosave repairs. It does not change the archive schema, three-generation policy, Android/iOS behavior, release artifacts, or provider claims.

- Current product/test repair: `bcc0c42ded0d87cd94b01c26255d5583fed39666`
- Current product tree: `5c3e6ac0a9889042ea413d367d0a85353a6a85eb`
- Latest-main product/test integration: `9d496d0ee5dda8e90042e879aa075c69dec6d315`, tree `3a154a33110b2b2222b0d5603631bd17bde2d1dd`
- Review repair product/test commit: `82d21a4a8dd353d8cd7f268410987a7fab2423ca`, tree `d93cbf53c02dd7ba6ed21a4044b97c8321b53071`
- Hosted-oracle correction: `815d4c3fffab5deca3f0004c828929642993e492`, tree `e466b79a589da0a63d8e2e2314e982346f52a2b4`
- Runtime repair commit: `74f79524a35880398960af1db82597e33de68198`, tree `a2ca8344ee9b4fd773dcedf6fc82edbe00aaf24a`
- Latest-main integration parents: prior exact PR head `fb97ecc0d2df250cd5dfb0a9625cbbd31598bcef` plus exact merged `main@029500ac63fe521814530acf4d70cab78365c9fd`, tree `1ec604a2564ad39ff5c44d50f835d237c1ac6639`
- Main preservation: #74 reverse one-shot PCM source/tests/Desktop no-device regression and current snapshot/validation/plan files are inherited unchanged from exact `main@029500a`; the five PR-owned files do not overlap its nine-file change.
- Main preservation: #73 recorder source/test/hardening-plan blobs remain exact `3dec52b4598e3222503cbdc3abeade543e22e046`, `eea213f8716d92fe63f3f13d8180da94b3399657`, and `dcfb9196413fc7c3294fe320d056ea8a6101024e`
- Repair: recovered source hydration reserves its device-load revision before state publication, uses the persisted pitch, and opens the device outside the recovery monitor. A newer pitch/source operation revokes queued hydration, close revokes admitted hydration without waiting for device open, and deferred player teardown runs after any in-flight load.
- Repair: recovery clears prior device readiness before publishing state, so a synchronous pitch edit can establish newer readiness without the publication tail erasing it; the immediate-pitch regression also requires source playback to reach the engine.
- Repair: pending recovered-audio hydration is distinct from device failure. A play request during device open reports preparation, successful hydration restores the prior recovery status, and a later play reaches the engine; a newer source load preserves the same restoration owner.
- Repair: a failed master-pitch reload stops retained source audio and publishes a non-playing error state. Explicit `.wav`/`.choplab` startup keeps the existing autosave untouched while the controller still owns only its revision-0 placeholder; successful replacement or a real edit restores normal persistence.
- Focused regressions: source play during pending recovered hydration; immediate recovered-state pitch edit; close during a blocked recovered-device load; failed pitch reload while playing; corrupt explicit startup preserving byte-identical autosave; plus the earlier save/export, failed replacement, stale recovery failure, device error, unchanged archive, and teardown-flush cases.
- Hosted correction: Windows compiled and reached 104 tests on `73b28b8`; only the new close regression raced between its completion latch and the thread's final termination. The corrected oracle joins the already-completed thread before asserting it is no longer alive; the runtime one-second close deadline is unchanged.
- Hosted exact-main evidence: head `95b5d43` passed Android `32737877285`, Windows `32737877323`, iOS `32737877440`, and supply-chain `32737877336`; the current pending-hydration repair requires a fresh exact-head run.
- Local gates: Python policy 39/39, public-surface 396 candidates, conflict-marker scan, and `git diff --check` pass. Gradle 9.7.1 remains unavailable locally because its uncached distribution cannot be reached, so hosted Windows compilation/tests/package and exact-head review are required.
- Documentation boundary: the current-snapshot promotion requested in review remains pending explicit authorization after the GitHub connector rejected writing the existing `docs/PROJECT_STATE.md` blob because it contains extensive environment/artifact metadata; this receipt does not claim that promotion is complete.
