# PR #64 Desktop autosave review receipt

This revision-bound receipt covers the focused Desktop startup-recovery, source-device, and close-time autosave repairs. It does not change the archive schema, three-generation policy, Android/iOS behavior, release artifacts, or provider claims.

- Current product/test commit: `815d4c3fffab5deca3f0004c828929642993e492`
- Current product tree: `e466b79a589da0a63d8e2e2314e982346f52a2b4`
- Runtime repair commit: `74f79524a35880398960af1db82597e33de68198`, tree `a2ca8344ee9b4fd773dcedf6fc82edbe00aaf24a`
- Latest-main integration product: `955aa020eced2dbcc24224ef673484c0a5195eaa`, tree `370b0f5fc186ef49c68818f3f33b6cac902a68d0`, with parents prior exact PR head `0ad318b5f2af7f3f0d2f5ab21568762d409b788f` plus exact merged `main@a0b356c2e5820b7f9a8288ebcdd555c19e0cb6b5`
- Main preservation: #73 recorder source/test/hardening-plan blobs remain exact `3dec52b4598e3222503cbdc3abeade543e22e046`, `eea213f8716d92fe63f3f13d8180da94b3399657`, and `dcfb9196413fc7c3294fe320d056ea8a6101024e`
- Repair: recovered source hydration reserves its device-load revision before state publication, uses the persisted pitch, and opens the device outside the recovery monitor. A newer pitch/source operation revokes queued hydration, close revokes admitted hydration without waiting for device open, and deferred player teardown runs after any in-flight load.
- Repair: a failed master-pitch reload stops retained source audio and publishes a non-playing error state. Explicit `.wav`/`.choplab` startup keeps the existing autosave untouched while the controller still owns only its revision-0 placeholder; successful replacement or a real edit restores normal persistence.
- Focused regressions: immediate recovered-state pitch edit; close during a blocked recovered-device load; failed pitch reload while playing; corrupt explicit startup preserving byte-identical autosave; plus the earlier save/export, failed replacement, stale recovery failure, device error, unchanged archive, and teardown-flush cases.
- Hosted correction: Windows compiled and reached 104 tests on `73b28b8`; only the new close regression raced between its completion latch and the thread's final termination. The corrected oracle joins the already-completed thread before asserting it is no longer alive; the runtime one-second close deadline is unchanged.
- Local gates: Python policy 39/39, public-surface 396 candidates, conflict-marker scan, and `git diff --check` pass. Gradle 9.7.1 remains unavailable locally because its uncached distribution cannot be reached, so hosted Windows compilation/tests/package and exact-head review are required.
- Documentation boundary: the current-snapshot promotion requested in review remains pending explicit authorization after the GitHub connector rejected writing the existing `docs/PROJECT_STATE.md` blob because it contains extensive environment/artifact metadata; this receipt does not claim that promotion is complete.
