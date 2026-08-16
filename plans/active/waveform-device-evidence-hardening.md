# Reproducible waveform device evidence

## Purpose and user-visible outcome

Make the phone waveform accessible and testable without depending on a user's autosave. A device run must bind one clean Git commit to one app APK, one test APK, one installed-base readback, and one instrumentation transcript without deleting project data.

## Current state

Commit `18e134e` added physical-Pixel observations and a Compose instrumentation test, but the test opened `MainActivity` and depended on the existing restored project. Its custom accessibility actions were invoked as Compose callbacks, not through a running TalkBack service. The receipt withheld full `DEVICE_PASS`, but raw build/install/test outputs were not bound by one manifest.

## Constraints and invariants

- Never uninstall, clear app data, or overwrite project archives outside the app.
- Candidate and installed signers must match before `adb install -r`.
- Instrumentation uses deterministic in-memory PCM and must not read or write autosave.
- `INSTRUMENTATION_PASS`, physical-device observation, spoken TalkBack, subjective audio, and `HUMAN_GO` remain separate gates.
- Audio frame models remain start-inclusive/end-exclusive. Spoken viewport text names the inclusive last displayed frame index.

## Architecture and interfaces

- `WaveformEditor` is the public Compose seam for gesture, viewport semantics, overview geometry, and handle targets.
- `WaveformViewportPolicyTest` covers pure viewport/action/overview contracts.
- `SourceWaveformDeviceTest` renders a deterministic `PcmAudio` fixture directly through Compose.
- `scripts/collect-device-evidence.ps1` captures source, build, APK, signer, device, install, autosave, readback, instrumentation, and log artifacts under one immutable run directory.

## Milestones

### Milestone 1: deterministic accessibility and gesture contracts
- Make viewport actions report false when clamped/no-op.
- Replace MainActivity/autosave-dependent instrumentation with in-memory fixture tests.
- Verify width, height, clipping, endpoints, exact reversible nudge, pinch, pan, and reset.
- Extract overview geometry for pure boundary testing.

### Milestone 2: reproducible evidence chain
- Collect clean HEAD/tree/status and Gradle output.
- Hash and sign app/test APKs.
- Before installation, pull the installed APK and compare signer.
- Capture autosave before/after, `install -r`, installed-base readback, instrumentation, package state, and logcat.

### Milestone 3: exact-device rerun
- Obtain exclusive Pixel ownership.
- Run the evidence collector on the exact committed source.
- Return the phone with ChopLab stopped, launcher foreground, original volume/rotation restored, and project data retained.

## Progress

- [x] 2026-08-16 — Luna adversarial review identified fixture, TalkBack-claim, action-boundary, overview, 48 dp, and provenance gaps.
- [x] 2026-08-16 — Viewport no-op action RED reproduced and Boolean action contract implemented.
- [x] 2026-08-16 — Deterministic Compose fixture and overview geometry added; focused JVM and Kotlin compilation pass.
- [x] 2026-08-16 — Full local gate and exact-device evidence rerun; final run is regenerated after the safe-return evidence addition.

## Discoveries

- Existing instrumentation passed only because Pixel autosave already contained source audio and chop markers.
- Direct `CustomAccessibilityAction.action()` proves the Compose semantics callback, not spoken TalkBack or focus traversal.
- The first deterministic device run exposed two real test-infrastructure defects: sequential pointer events cancelled pan movement, and `adb am instrument` returned a host exit code that did not reflect the reported JUnit failure. Pointer movement is now emitted as one event and the evidence runner parses the all-green summary fail closed.
- The visual handle-line offset is relative to a `TopCenter` parent; the review claim of an unconditional 24 dp shift was rejected, but endpoint rendering still needs device evidence.

## Decision log

- 2026-08-16 — Keep spoken TalkBack as a separate physical/Human acceptance gate; do not relabel semantics callback evidence as spoken output.
- 2026-08-16 — Use fake/in-memory state for recording and waveform automation; do not activate a real microphone merely to obtain automated evidence.
- 2026-08-16 — Preserve inclusive last-frame wording for human-readable frame IDs and document its distinction from the internal end-exclusive range.

## Validation log

- `:app:testDebugUnitTest --tests com.choplab.sampler.ui.WaveformViewportPolicyTest.accessibilityActionsReportWhetherTheViewportActuallyChanged` — RED then GREEN.
- `:app:testDebugUnitTest --tests com.choplab.sampler.ui.WaveformViewportPolicyTest.overviewGeometryRepresentsWholeAndZoomedViewports` — compile RED then GREEN.
- `:app:compileDebugAndroidTestKotlin` — PASS for deterministic fixture.

## Risks and rollback

- Gesture injection can still vary with Compose runtime; fixed in-memory geometry reduces but does not eliminate platform timing risk.
- Evidence collection refuses dirty tracked state and signer mismatch. It never contains uninstall or clear-data operations.
- Rollback is the parent of the final logical commit; archive schema and project bytes are unchanged.

## Remaining device validation

- Run deterministic instrumentation on Pixel 9a from the exact committed build.
- Capture raw accessibility hierarchy and manually verify TalkBack focus order/spoken Japanese if the user authorizes service-state changes.
- Real microphone quality, physical one-hand comfort, and subjective audio remain `HUMAN_GO` checks.
