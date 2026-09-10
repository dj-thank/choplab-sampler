# Repeated startup/performance experiments after PR #92

## Purpose

Reduce avoidable startup work and recording-sized temporary allocations without
weakening recovery, changing sound, or moving the delay onto the first PAD hit.
This task-local lane follows `perf/startup-recovery-20260910`; it does not take over
the separately selected release-convergence plan or PR #89.

## Input and checkpoint

Exact input commit `5fb534c08559c98ba50439a42055bd44e6e28a5d`, source tree
`f06aea953c7247d9e81f56374adc73747c3bf095`. Source and unchanged runtime jars were
read from that head's successful Windows workflow. The local Git checkpoint
reconstructs this source tree, not GitHub history. Existing main/PRs are preserved.

## Constraints

Keep minSdk, dependencies, signing, release/publication, PCM values/channel order,
archive formats/budgets/digests/backup order and real-time audio paths unchanged.
No user audio, external sample assets or destructive device commands. Measure real
executed work; host microbenchmarks must not be represented as device startup/RSS.

## Interfaces

The internal WAV codec allocates only its final short array plus bounded heap
scratch. Android `prepareStartupProject` owns I/O/CPU dispatch only; the ViewModel
still owns revision/epoch checks, engine updates and UI publication. Trace sections
and an opt-in host benchmark make later comparisons reproducible.

## Milestones and progress

- [x] Read current repository/PR guidance, checkpoint exact source, run doctor and
      baseline validator; record unsupported local toolchain rather than claim PASS.
- [x] Audit startup, archive copies, waveforms, drum synthesis, engine startup,
      transport polling, shrinking and Baseline/Startup Profiles using primary docs.
- [x] Reproduce the intermediate-allocation issue with two failing tests.
- [x] Implement and compare control, scalar 8/32 KiB and bulk 8/32 KiB variants.
- [x] Select bulk 8 KiB; rebuild final Android-compatible Buffer descriptor and run
      separate control/candidate/candidate/control confirmation.
- [x] Move first-run synthesis to a CPU worker with failure/cancellation and stale
      admission tests; preserve original PCM and instrument control-path stages.
- [x] Execute 82 host test bodies; retain raw CSV/logs and explicit gate limits.
- [ ] New exact-head supported-toolchain CI, lint/build and review.
- [ ] Physical TTID/TTFD, p50/p90, first-PAD latency, peak/retained memory, lifecycle,
      recording, real audio and human acceptance.
- [ ] Merge-time SSOT/feature/registry reconciliation with the independent lane.

## Experiments and decisions

Scalar block conversion lowers allocation but worsens raw decode in the measured
host cases, so it is rejected. Bulk 8 KiB performs well with a smaller bound; this
is not a universal victory over 32 KiB. Final confirmation reports a 24.2% lower
recovery median and 48.4% lower per-thread allocated bytes; 30s raw-decode p90 is
slightly worse and is disclosed rather than suppressed. No total app speed or
peak-memory improvement is asserted from these measurements.

Do not asynchronously initialize AudioTrack until first-use/lifecycle ownership has
been tested. Do not add cache retention, weaken checksums, drop samples or invent
Baseline Profiles. Do not switch public APK/build/signing contracts to disguise
implementation changes as a performance result.

## Validation and next experiment

See `docs/STARTUP_ITERATION_20260910.md` for exact commands, experimental methods,
primary references and evidence boundaries. Run supported Gradle/JUnit and public
policy checks before integration. Profile the three new Android trace sections on
physical hardware, then choose the largest remaining critical-path operation.
Record one hypothesis, a control, safety regressions and a before/after trace for
each follow-up; reject changes that worsen first-use or lose lifecycle correctness.

## Rollback

Revert this bounded follow-up without reverting PR #92. Files on disk require no
migration. Stop at failed validation and retain evidence; never reset/clean user
workspaces, force-push, replace releases or clear app data to obtain a green result.

## Publication blocker

GitHub tree publication was blocked by the connector safety check. No new branch,
PR or ref update was made. Do not claim new-head CI, merged code or installed
product. Deliver the local patch and raw measurement evidence without retrying
the write through a different endpoint.
