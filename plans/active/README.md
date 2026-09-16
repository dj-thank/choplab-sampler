# ExecPlan registry

Updated: 2026-09-16

## Current selection

Exactly one plan is selected on this feature branch: [DDJ-200 Android controller](ddj200-controller-20260916.md).

This is a stacked change on PR #101 at `6a508d3802ba11f6f6858270891ab48519282c6c`.
The follow-up candidate adds Bluetooth-first control, live mixer/EQ, explicit wired
split-CUE and LED feedback. Publication targets the existing PR #102 branch;
see the DDJ-200 plan and the PR for exact-head publication/CI status.
The initial PR adds an opt-in USB/BLE MIDI sampler controller. Pure host evidence is not
Android/device acceptance. It does not merge, tag, release or change signing.
The change-specific current status and feature/evidence matrix are in
[DDJ-200](../../docs/DDJ200.md); older project snapshots are historical baseline
receipts, not claims about this new controller.

## Retained prior plans

[Latest local integration and Android parity](latest-android-integration-20260916.md)
was the PR #101 selection. It merges local production `8a279bc` with the
unmerged PR #92–#97 chain `348d341` and brings Windows Spotify/library and drum
separation to Android. That baseline is preserved, not independently revalidated.

[UI clarity and responsive control quality](ui-quality-20260911.md) was the PR #97 selection. [Runtime-quality integration](runtime-quality-20260910.md) preceded it.

The local production line's complete registry, through the 2026-09-13 Spotify search selection, remains byte-for-byte in [README_20260914.md](README_20260914.md). The older complete registry remains byte-for-byte in [README_20260903.md](README_20260903.md).

PR89 release hardening and dependency PRs #98–#100 remain separate unaccepted work. Historical PR69 and release intentions are not new merge instructions.

Historical constraint: the old registry's "existing PR #69" checkpoint is retained in the archive. It is not a new merge instruction or a second selected plan.
