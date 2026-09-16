# DDJ-200 Android Bluetooth and live mixer follow-up

## Current selected outcome — 2026-09-16

Android in-app Bluetooth MIDI discovery/control, two live sampler mix groups,
crossfader, CH faders, three-band EQ, explicit wired split-CUE output and LED
state feedback. Windows/iOS MIDI remains unimplemented. See `docs/DDJ200.md`.

## Baseline and publication

Baseline PR #102 head: `eeda0d28cac42374920dd1441fdde95a13b8bd78`.
Reconstructed source tree exactly matches `672f030b7e98df566e81d190898191e43ed6e4f5`.
The user requested publication of the previously delivered source/patch to
existing PR #102. This follow-up targets `feat/ddj200-controller-20260916` as a
non-force child of the observed head, with no unrelated code changes. The earlier
blocked handoff is historical; exact-head read-back and CI belong on PR #102.
No merge, release, APK or installation is included.

## Invariants and decisions

- Keep dependency versions, minSdk, signing, release and project/export formats.
- Live-only mixer state, immutable per-block audio snapshots; no per-frame locks,
  allocations, MIDI IO or device queries. Disconnected stereo path stays original.
- Stable left/right PAD groups, not two DJ decks. CH faders change live groups,
  not saved PAD gain. COLOR FX keeps the previous saved PAD-TONE mapping.
- Fail closed on reported split-route changes; explicitly document OS buffering
  and callback delay. Require a user-confirmed physical DJ splitter.
- BLE input and LED output are separate ports; output errors must not erase input.
- No permission request or scanning at application startup; explicit reconnect.

## Progress and acceptance

- [x] BLE discovery fallback, batch results, radio enable/settings and OFF handling.
- [x] Main-owned MIDI state, two live groups, mixer/EQ, split-CUE and LED messages.
- [x] Host tests: 74 distinct bodies PASS (32 session, 28 mixer, 6 LED, 8 lease/route).
- [x] Exact baseline tree verification; documentation and patch handoff.
- [ ] Supported new-code Android/Compose/Gradle/lint/APK gates.
- [ ] Physical DDJ BLE/USB, routing, lamps, audio and latency acceptance.
- [x] User-authorized publication preparation: archive/patch round-trip and 74 tests rechecked.
- [ ] Exact-head publication/CI read-back: record on PR #102; not inferred from this file.
- [ ] Windows/iOS MIDI adapters (out of this Android-priority candidate).

Existing old-head Android run 35040366506 failed during lint-engine dependency
download (TLS handshake), after earlier tests. It does not validate this local
follow-up. Local whole-project validation is blocked by old Kotlin CLI 1.9
`MutableList.addLast` resolution in unrelated EditHistory; no dependency or
baseline workaround was introduced. See `outputs/ddj200-bluetooth-20260916.md`.

## Historical initial implementation (superseded scope)

The following records the earlier iteration. Its exclusions, selected baseline
and 31-test count are historical; current scope and evidence are above.

# Make DDJ-200 an opt-in Otohiroi Android controller

## Purpose and user-visible outcome

Select a USB/BLE DDJ-200 from a Japanese connection dialog and play the sampler's
PADs, switch BANKs, control source/BEAT, scratch and adjust pitch/BPM/PAD gain/TONE.
This is sampler control, not a new two-deck mixer. See `docs/DDJ200.md` for the
exact map and the feature/evidence matrix for this change.

## Current state

Exact baseline: PR #101, `claude/choplab-latest-android-20260916`, commit
`6a508d3802ba11f6f6858270891ab48519282c6c`, root tree
`5420205e180be21f642b75fd3ecf0aaa53d97c85`. Feature branch:
`feat/ddj200-controller-20260916`. This is a stacked change; preserve PR #101
and do not merge unrelated dependency/release work. No existing user worktree
was reset or cleaned. The local directory is a partial source workspace, not
a complete checkout. Earlier product/device results do not validate this patch.

## Constraints and invariants

Keep minSdk 29, existing dependency versions, AudioTrack DSP, archive format and
release/signing settings. No app-start MIDI discovery; explicit device choice.
No saved Bluetooth identifiers, audio uploads or background location. Restrict
discovery to DDJ-200. Bound input memory, invalidate stale asynchronous opens,
release owned PADs and stop playback on disconnect. Do not infer physical or
desktop support from common code. Do not map an unavailable crossfader/EQ bus.

## Architecture and interfaces

- `shared/src/commonMain/kotlin/com/choplab/sampler/midi/Ddj200.kt`: pure parser,
  DDJ profile, pickup, PAD/button/scratch session.
- `shared/src/commonMain/kotlin/com/choplab/sampler/midi/Ddj200DeckTarget.kt`:
  bridge to current state and `SamplerDeckController`; no new engine path.
- `app/src/main/java/com/choplab/sampler/midi/AndroidDdj200.kt`: MidiManager,
  BLE scan, ports, lifecycle epochs and bounded queue (32 × 4096 bytes maximum).
- `app/src/main/java/com/choplab/sampler/midi/Ddj200Controls.kt` plus existing
  `ui/SamplerScreen.kt`: opt-in connection/status/help UI, lifecycle disconnect.
- `app/src/main/AndroidManifest.xml`: optional MIDI/USB/BLE features and scoped
  legacy/modern BLE permissions.
- `shared/src/commonTest/kotlin/com/choplab/sampler/midi/Ddj200Test.kt`: 31 pure tests.

MIDI callbacks only copy bounded chunks to the control-thread queue. Application
commands run on Main and reuse ownership, playback admission and persistence.
The platform performs USB/BLE framing; the shared parser consumes MIDI bytes.

## Milestones

### Milestone 1: Byte-level sampler contract

Implement official E2 channels, streaming parser, release ownership and soft
takeover. Acceptance: all 31 assertion bodies pass; separately obtain supported
Gradle common-test results. Supplemental Kotlin CLI result is recorded below.

### Milestone 2: Android end-to-end source wiring

Implement lazy discovery, explicit device selection, permission denial,
scan/open timeout, stale completion cleanup, bounded queue and UI lifecycle.
Acceptance: Android compile/lint/APK and existing UI tests pass. Source is wired;
these supported-toolchain gates remain unverified locally.

### Milestone 3: Physical acceptance

Run the checklist in `docs/DDJ200.md` on USB and BLE DDJ-200. Record firmware,
phone/Android, connection, test steps, latency methodology and negative paths.
No physical evidence is currently available. Keep calibration and parity claims open.

## Progress

- [x] 2026-09-16 — Read exact PR #101 code and official MIDI/Android specifications.
- [x] 2026-09-16 — Created isolated feature branch at the exact baseline checkpoint.
- [x] 2026-09-16 — Implemented pure session and 31 passing supplemental host tests.
- [x] 2026-09-16 — Wired Android adapter/dialog and existing controller at source level.
- [ ] Supported Gradle/JUnit, Android lint/APK and UI verification on this head.
- [ ] USB/BLE physical device, audio-route, timing and human acceptance.

## Discoveries

Official PAD channels differ from the deck button channels. SHIFT changes the
PAD channel, so releases cannot be resolved against the current SHIFT/BANK.
PAD gain is 0..1.5 in the existing controller, unlike normalized MIDI 0..1; the
bridge explicitly scales both directions. Existing scratch starts set state
synchronously and already stop jog speed after the existing idle timeout.
The right jog shares the selected-PAD scratch owner, not a second independent deck.

## Decision log

- 2026-09-16 — Stack on #101 rather than old main to retain latest integration.
- 2026-09-16 — Android first, using platform MIDI with no new dependency. Keep
  Windows/iOS adapter, EQ/crossfader/headphone/LED behavior explicitly unimplemented.
- 2026-09-16 — Disconnect and stop on background/rotation rather than add a
  foreground MIDI service or implicit reconnection.
- 2026-09-16 — Require a fresh complete 14-bit CC pair; ignore partial values.
- 2026-09-16 — Require pickup through current values to avoid snapshot jumps.

## Validation log

2026-09-16, Linux, OpenJDK 21.0.11, Kotlin CLI 1.9.0: compiled unchanged production
`Ddj200.kt` with temporary annotation-only adapted test source and invoked every
one of the 31 test bodies. Result: 31 passed. Receipt:
`outputs/ddj200-host-20260916.txt`. This excludes bridge, Android, Compose and hardware.

Network DNS prevented a complete local Git checkout/toolchain acquisition. No
Android SDK is installed. Repository-wide doctor/validation/public-surface scan,
Gradle tests, Android lint/build and UI tests were not run locally. New text
candidates and manifest XML received a separate limited prepublication check.
Required CI commands are listed in `docs/DDJ200.md`; record exact-head results in
the PR rather than reuse base-branch results. No release, tag or merge is authorized
by the host test result.

## Risks and rollback

Unmeasured jog gain, Android BLE vendor variation, physical power and queue
behavior need device evidence. Avoid simultaneous screen/hardware scratching
until that ownership interaction is tested. Use ALL STOP and disconnect to return
to touch-only controls. Revert this focused change to remove MIDI support;
there is no persistent-state migration to undo. Do not rewind or force-push main.

## Remaining device validation

USB/BLE enumeration/open, all PADs and SHIFT/BANK changes, sustained GATE/LOOP,
rapid/chord input, scratch idle/release, pickup after screen edits, deny/revoke,
Bluetooth off, late open cancellation, unplug, background/rotation, sustained
use, real audio routes, sound quality and measured end-to-end latency.
