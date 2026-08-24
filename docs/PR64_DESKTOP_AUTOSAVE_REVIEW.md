# PR #64 Desktop autosave review receipt

This revision-bound receipt covers the final focused review repair for Desktop startup recovery and close-time autosave. It does not change the archive schema, three-generation policy, Android/iOS behavior, release artifacts, or device/provider claims.

- Product commit: `955aa020eced2dbcc24224ef673484c0a5195eaa`
- Product tree: `370b0f5fc186ef49c68818f3f33b6cac902a68d0`
- Parents: prior exact PR head `0ad318b5f2af7f3f0d2f5ab21568762d409b788f` plus exact merged `main@a0b356c2e5820b7f9a8288ebcdd555c19e0cb6b5`
- Main preservation: #73 recorder source/test/hardening-plan blobs remain exact `3dec52b4598e3222503cbdc3abeade543e22e046`, `eea213f8716d92fe63f3f13d8180da94b3399657`, and `dcfb9196413fc7c3294fe320d056ea8a6101024e`
- Repair: recovery, project replacement, and save/export status use separate ownership. Failed replacement retains recovery fallback; stale recovery failure cannot label a successful replacement as an error placeholder. Recovered PCM tracks playback readiness, unchanged successful recovery does not rotate generations, and teardown failure cannot skip the final flush.
- Focused regressions: save/export during hydration; failed replacement fallback; successful replacement versus stale recovery failure during close; playback after device-load failure; unchanged recovery byte preservation; teardown-exception flush.
- Local gates: Python policy 39/39, public-surface 395 candidates, conflict-marker scan, and `git diff --check` pass. Gradle 9.7.1 is unavailable locally, so hosted Windows compilation/tests/package and exact-head review are required.

