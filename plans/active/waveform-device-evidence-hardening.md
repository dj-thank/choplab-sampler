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
- The same fixture is audited by Compose Accessibility Test Framework and inspected through `UiAutomation` / `AccessibilityNodeInfo`; `docs/TESTING.md` defines the test-layer claim boundaries.
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
- [x] 2026-08-16 — Clustered handle fix deployed from clean `6943b5e`; exact app/test readbacks, retained autosaves, and three deterministic device tests passed.
- [x] 2026-08-16 — Real TalkBack exposed S/E/chop-1/chop-2 and focused the formerly occluded cluster; real microphone ownership rejected source playback and BACK cancelled without autosave mutation.
- [x] 2026-08-16 — User-authorized continuation proved selected-loop/source-preview to microphone ownership takeover; both captures were cancelled and baseline autosave hashes retained.
- [x] 2026-08-16 — Android official `testing-setup` guidance applied: UI Automator 2.4.0 and Compose ATF added. A historical API 36 AVD receipt passed four waveform tests; the current candidate is virtual-device `BLOCKED` until the missing Google Play image is repaired and the suite is rerun.
- [x] 2026-08-16 — Second Luna adversarial panel added an explicit accessibility click action whose label matches the waveform tap instruction, and hardened the evidence runner to use deterministic Gradle limits, a fixed androidTest APK path, and signer-first retained-data upgrades.
- [x] 2026-08-16 — Follow-up adversarial review raised compact portrait targets, font scaling, and the scratch platter's missing semantic actions; 48 dp portrait rows, non-shrinking `sp`, and state-aware start/stop/left/right scratch actions are covered by focused LOCAL tests.

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
- `scripts/collect-device-evidence.ps1 -InstallAndTest -Serial 5A121JEBF08094` — authoritative exact run `20260816-185953-b3579f05`; strict serial/device, package/version/signer, app/test readback, autosave preservation, instrumentation, bounded logcat, failure-path cleanup, and pre/post phone-state restoration PASS.
- Pixel instrumentation — `OK (3 tests)` in `5.138 s`; app/readback SHA-256 `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`; test/readback SHA-256 `DE97432A1C1278E7661FD656DFCC054CFABA6A4BCC6D9DECF44B810564F83EC8`.
- Final exact Pixel run — `work/device-evidence/20260816-220355-233297e3`; clean HEAD `233297e39f404bb8e0080110c3d29a528dd8c615`; app/readback `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162`; test/readback `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE`; Pixel `OK (4 tests)` in `7.484 s`; real TalkBack service focus-path, final autosave preservation, 929 ms cold relaunch, fatal/ANR 0, and exact phone-state restoration recorded.

## Risks and rollback

### 2026-08-16 actual TalkBack continuation

- Actual service/touch-exploration focus found a production-only overlap: markers at the source start could fully occlude the S accessibility node.
- The local candidate preserves the full-height visual line while placing each marker's 48 dp draggable/semantics target in rotating top/center/bottom lanes and giving S, E, and numbered markers deterministic traversal indices.
- A near-start `1/2` marker fixture checks distinct bounds and reversible actions for both markers. Clean full build passed.
- The corrected APK was installed and read back exactly. The accessibility stream was restored to `9` while
  TalkBack was active; Android exposes inactive stream alias `1` after TalkBack is disabled. Service, enabled,
  touch-exploration, media-volume, rotation, foreground, and autosave readbacks were restored and recorded.

- Gesture injection can still vary with Compose runtime; fixed in-memory geometry reduces but does not eliminate platform timing risk.
- The configured `medium_phone` AVD points at a missing Google Play image while the installed API 36 image is Google APIs, and `avdmanager` cannot recreate the dedicated review AVD because that image lacks `devices.xml`. Virtual DEVICE execution is therefore blocked until the SDK image is repaired; LOCAL build and androidTest compilation do not substitute for that run.
- Evidence collection refuses dirty tracked state and signer mismatch. It never contains uninstall or clear-data operations.
- Rollback is the parent of the final logical commit; archive schema and project bytes are unchanged.

## Remaining device validation

- If full `DEVICE_PASS` is required, verify spoken S-to-E-to-marker traversal and invoke previous/next/reset
  through the running TalkBack service rather than Compose callbacks.
- Real selected-loop/source-preview to microphone transitions were verified without retaining capture content.
- Real microphone quality, physical one-hand comfort, and subjective audio remain `HUMAN_GO` checks.
