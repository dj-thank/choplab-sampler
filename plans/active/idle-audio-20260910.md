# ExecPlan: independent idle-audio and capture-cleanup optimization

## Goal and boundary

Reduce unnecessary work during/after startup without moving latency to the first PAD. Base is exact PR92 `5fb534c08559c98ba50439a42055bd44e6e28a5d`. This is an independent seven-file follow-up, not a merge or publication authorization. Preserve the existing realtime queue, DSP, AudioTrack lifecycle, recording ownership and canonical file boundary.

## Progress

- [x] Inspect startup, idle polling, realtime render and capture cleanup paths.
- [x] Implement a fixed-pool idle block decision after command drain; keep native writes running.
- [x] Filter cleanup candidates before canonical path work; retain final ownership checks.
- [x] Exercise 25 focused host test bodies and 12 actual-renderLoop output/state comparisons.
- [x] Compare host variants in independent A/B/B/A JVM runs; document measurement boundaries.
- [ ] Complete supported-toolchain and fresh exact-head hosted CI.
- [ ] Confirm physical audio latency, lifecycle/routes, startup and power behavior.
- [ ] Integrate the separate local WAV/startup-preparation patch and reconcile central status registries.

## Decisions and findings

The output stream remains warm. Skipping native writes or sleeping until a PAD event is not part of this patch. Running transport, release tails and scratch must never count as idle. File deletion still requires the existing canonical-parent check, including symlink protection. The local Kotlin toolchain is not the supported version, so host results do not promote device/build gates.

Do not replace the 24 ms poller with state-only suspension: optimistic state can disappear before queued engine start takes effect, losing acknowledgement. Do not move AudioTrack lifecycle across threads without cancellation and shutdown tests. See `docs/IDLE_AUDIO_20260910.md` for exact scenarios, measurements and remaining checks.

## Revalidation and rollback

Run the focused Android unit tests, normal lint/build, Python policy and public-surface scan. Compare only exact commit outputs. Reverting these seven paths restores the PR92 behavior without schema migration or user-data conversion. No tag, Release, credentials, audio fixture binaries or local SDK paths are committed.
