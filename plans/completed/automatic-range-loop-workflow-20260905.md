# Automatic selected-range editing and loop-first Beat

## User correction (authoritative)
Selected audio should fit the screen automatically. Remove manual zoom, precision-mode and beat-fitting controls from this editor. Keep S/E rolling dials visible. Preserve the full-source overview with cut locations and allow tapping it to rechop from that point. Make continuous looping the center of Beat, adding drums and scratch around it.

## Contract
Base 1e55128 plus current uncommitted validated playback fixes. Root owns this checkout; earlier button-heavy UI plan is superseded, not resumed. Retain Android live-loop rebind, layered transport, rejection/STOP and scratch-return fixes. LOCAL_PASS target; preserve original dirty canonical checkout and all user audio. Rollback only this task's diff; no device/public operations.

## Criteria
AF1: selected waveform shows exactly S..E, with no manual viewport controls or gestures.
AF2: two always-visible dials move actual frame boundaries; relative actions read the current model and preserve other PADs.
AF3: full-source overview shows source-owned cut locations; tapping resumes source chopping without clearing assigned PADs.
AF4: default Beat centers a continuously playing loop and its S/E dials; drum/scratch actions remain reachable. Step arranging stays secondary.
AF5: current backend loop/transport/failure and persistence tests remain valid; retire only tests for UI behavior explicitly removed by this correction.

## Progress
- [x] User clarification: keep whole-source view; simplify selected-range editor.
- [x] Automatic waveform + source map + rolling dials.
- [x] Loop-first Beat and model/controller actions.
- [x] Replacement UI tests, full serial gate and screenshots.

## Limits
No physical sound/touch/recording, iOS runtime, public release or Human acceptance claim. Scratch remains live interruption/return rather than recorded overdub.

## Replaced test contract
User explicitly removed manual zoom/one-second focus/beat-fit controls. The corresponding component tests are replaced by automatic exact-range display, relative dial/live audio binding, source-map rechop, and continuous loop-first Beat scenarios. Existing capture/GATE/confirmation/recording safety tests remain. Retired UI scenarios: longerBeatFitKeepsTheNewEndVisible, portraitMusicalTrimKeepsLoopAndBeatControlsReachable, trimToDrumsStopsSourceAuditionWithoutAutostartingTheBeat, trimLoopToBeatsThenLayerDrumsAndSelectItForScratch, waveformOrdinaryMouseClickEditsTheBoundaryWithoutPrecisionZoom, sourceEndChopKeepsOneSecondFloorAndClampsEndFocus, sourceStartChopKeepsOneSecondFloorAndClampsStartFocus, assignedPadMouseLongPressOpensItsExistingFittedTrim, beatPadMouseLongPressPreservesItsRangeAndOpensFittedTrim, waveformMouseLongPressMovesTheCloserEndAndFocusesOneSecond.

## Final local evidence
789 standard tests and 28 UI/controller checks (12 controller overlaps), all final XML failures/errors/skips zero. Lint/APK/Windows package/project validation passed; final Android fixture-only rebuild recorded in the report receipt. No device or public action. Earlier parallel async timeouts remain an unproven scheduling lead, not a repaired flake.
