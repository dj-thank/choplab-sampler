> SUPERSEDED: User rejected manual zoom/fine-control/beat-fit UI. Continue automatic-range-loop-workflow-20260905.md. Playback fixes remain in scope.

# Chop to musical loop workflow

## Purpose
User asks to keep improving chop precision, usable looping, drums and scratch. Base 1e55128, root sole writer in the existing isolated refinement branch. Build a continuous user workflow using existing audio/persistence boundaries.

## Selection criteria and directions
LP1: fit END to 1/2/4 beats at current BPM and effective pitch, without exceeding source; display duration and beats honestly (not beat detection/time-stretch).
LP2: audition a loop while trimming, preserve existing transaction/undo/stop guarantees.
LP3: reach drums directly from trim and explicitly listen to combined loop/pattern without hidden auto-play.
LP4: make the scratch source explicit and make the configured loop PAD easy to select after editing drums.
Selected: in-place loop trim and next actions. Deferred: automatic tempo detection/time-stretch and scratch recording/export subsystem (separate DSP/persistence work). Rejected: extra global toolbars.

## Scope and invariants
Shared UI/model helpers and existing Android/Desktop controller APIs. Frame ranges stay start-inclusive/end-exclusive; keep stereo frame identity. Existing source audio never rewritten. End fitting uses existing setSelectedPadEndFrame live transaction. New controls must remain reachable at 360x800 and 1100x520. No device/public actions. Rollback this milestone diff only; stop on ownership conflict or data-loss regression.

## Skill use
Scratch skill: scratch is source position p(t) plus gain g(t), not a pasted effect. This milestone improves source selection/return path only; it does not claim new scratch techniques or recorded scratch export. No bundled third-party scratch demo/audio is used.

## Progress
- [x] Read existing trim, loop and scratch contracts; independent bounded review started.
- [ ] Shared timing helpers and tests.
- [ ] UI workflow and controller/component scenario.
- [ ] Full validation, review, package and receipt.

## Validation and remaining limits
Pending; LOCAL_PASS target. Musical quality, actual audio/gestures, tempo recognition, microphone and Human acceptance remain external evidence.

## Discovered playback gaps and repairs
LP2: Android live Voice updates previously changed pitch/tone/gain only, leaving loop bounds stale. The audio-thread update now rebinds LOOP bounds/reverse with existing cursor reset, retaining ownership and avoiding allocation; a rendered PCM sign/range/STOP regression covers it.
LP3: legacy transport stopped standalone loop playback then ran only pattern steps. Both platforms now start the configured loop via their existing loop transaction before the step transport; rejection does not publish a successful combined session. Android transport command admission returns Boolean and rejection stops the partial start. STOP ends the combined session. Scratch returns to the whole transport rather than losing drums.
LP4 clarification: the shortcut intentionally selects a configured sound even when stopped; it now explicitly says loop-configured PAD. Return availability still derives only from active transport/loop state. It does not claim that a stopped source is playing.

## Validation diagnosis
Two combined max-workers=2 test runs timed out in different pre-existing asynchronous file tests (failed user load during startup; source recording decode). No assertion/deadline was relaxed. An isolated :desktop:test --max-workers=1 run passed all 196 tests with unchanged production/test predicates. This supports scheduling interference but does not prove its root cause; final validation is serialized and these transient failures remain disclosed. Source-to-drums transition additionally stops source audition conditionally while retaining an active loop; Android loop-only STOP retains audio focus while the transport still plays.

## Visual iteration
Observed the actual portrait and desktop trim images. Numeric precision controls are now progressive disclosure so the normal waveform gets more space; expanding them retains 48dp targets and a scrollable layout. The portrait component test proves scrolling does not mutate range (its helper now targets scroll containers, not numeric wheels). TRIM now uses the full workspace width on desktop instead of dedicating half the screen to mostly empty PADs; the current PAD is named in the trim readout. Beat fitting reframes the viewport so an extended END does not disappear offscreen.
