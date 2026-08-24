# Turn one source into a reversible playable sketch

## Purpose and user-visible outcome

After loading a legal local source, a user who has not yet placed melody material can choose `8つの下書き / QUICK SKETCH`. ChopLab stops competing source playback, divides the current source selection into eight safe contiguous chops, assigns them to A01–A08, and places each once on alternating steps 1/3/5/7/9/11/13/15. Existing starter drums and every B/C/D sound or step remain intact. The whole sketch is one Undo/Redo/autosave unit.

## Current state

- Baseline: `8fa1dac79b76f851e035cd8abaa5db8f9b1f5532`, tree `ad3c54e8e07e2e9af181587e4e5127ef42db4f18`.
- Worktree/branch: `work/choplab-creative-improvement-20260825`, `codex/choplab-creative-improvement-20260825`.
- The shared deck has manual live chopping and an empty-state CHOP dock whose BEAT and PAD EDIT actions are disabled.
- `ProductionCommand` already owns shared reducer admission, zero-crossing policy, mutation classification and platform effects. `ProductionSession` already groups one accepted command into one history/revision/persistence transition.
- Baseline `pwsh scripts/doctor.ps1` reported a clean JDK 17 checkout; configured project validation passed public-surface/executable-mode checks; `:shared:desktopTest :jvm-core:test :desktop:test` passed.

## Constraints and invariants

- Bank A means all A01–A32 pads and all Bank-A step keys. Any existing A pad or A step rejects the command without clearing or overwriting.
- The current source selection must fit exactly eight slices, each at least `minimumChopFrames(sampleRate)` after zero-crossing snap. No silent seven/six-slice fallback.
- Frame ranges remain start-inclusive/end-exclusive, strictly increasing, in bounds and contiguous.
- Existing B/C/D pads and all non-A steps remain exactly equal.
- The action is rejected while loading or recording by the existing reducer admission.
- No schema, native engine, callback, recording, provider, device or public behavior changes.
- Android and Desktop receive only existing `RefreshPad` and `RefreshPattern` effects. Any platform effect failure must retain existing fail-closed feedback behavior.

## Architecture and interfaces

- Add `ProductionCommand.CreateQuickSketch` and reduce it entirely under `shared/model/ProductionCommand.kt`.
- Add a pure availability/presentation policy used by `ProductionDockPolicy.kt`; do not fork eligibility in the UI.
- Add `SamplerDeckController.createQuickSketch()` as a default dispatch method.
- In `OtohiroiDeck.kt`, show QUICK SKETCH only for the safe empty melody state. Keep manual PAD capture available. Stop source playback immediately before dispatch.
- The accepted command returns one `PROJECT` mutation, eight `RefreshPad` effects and one `RefreshPattern` effect.

## Milestones

### Milestone 1: Pure reducer and negative controls

- Files: `shared/.../ProductionCommand.kt`, shared common tests.
- Add strict fixed-eight range generation, Bank-A emptiness and stale-step checks.
- Prove pads, markers, melody steps, preserved drum/other-bank state, effects and actionable no-op feedback.

### Milestone 2: Context-aware shared deck action

- Files: `SamplerDeckController.kt`, `ProductionDockPolicy.kt`, `OtohiroiDeck.kt`, policy tests.
- Replace the disabled PAD EDIT slot with QUICK SKETCH only when safe and useful.
- Preserve four-button density and manual capture/BEAT/ADD/SCRATCH paths.

### Milestone 3: Cross-platform contract and closeout

- Run focused shared tests, shared Desktop/Android host tests, JVM/Desktop tests, Android unit/lint/build as available, public-surface and diff checks.
- Run Standards/Spec review and independent negative-path review.
- Update PROJECT_STATE, FEATURE_MATRIX, plan registry and a revision-bound receipt.

## Progress

- [x] 2026-08-25 — Framed opportunity, compared six directions and selected Quick Sketch.
- [x] 2026-08-25 — Independent challenge accepted conditionally; added strict no-overwrite and boundary controls.
- [ ] Reducer RED/GREEN.
- [ ] Shared deck RED/GREEN.
- [ ] Full local gate and review.

## Discoveries

- Current CHOP UI exposes no auto-chop/assign-all action even though Android retains older platform-specific helper methods.
- The public source already has a deep shared command/history seam, so Quick Sketch can avoid a new platform-specific shortcut.
- `ProjectPattern`/`SongSection` foundations exist, but current runtime/archive/export remain single-pattern; Song is intentionally deferred.

## Decision log

- 2026-08-25 — Selected a fixed eight-slice sketch instead of adaptive 2–8 output. Predictability beats silent degradation; short/unsafe ranges fail without mutation.
- 2026-08-25 — Preserve every existing step and add only new A melody steps. Starter drums are part of the user's immediate playable result.
- 2026-08-25 — Use existing Undo/autosave and platform effects; no new schema or engine owner.

## Validation log

- `pwsh -NoProfile -File scripts/doctor.ps1` — clean Git/JDK 17; Android SDK env not configured.
- `C:\Program Files\Git\bin\bash.exe scripts/validate_project.sh` — public surface and executable mode PASS; host Gradle validation launched.
- `gradlew.bat :shared:desktopTest :jvm-core:test :desktop:test --no-daemon --max-workers=1 --no-watch-fs` — BUILD SUCCESSFUL.

## Risks and rollback

- Boundary snap can collapse adjacent slices: validate all snapped ranges before any mutation and reject as a whole.
- UI density can regress at large text: retain four dock slots and test policy/labels; runtime screenshot remains a local visual gate.
- Engine refresh can fail after a state commit: preserve existing adapter error reporting and never call the action a playback success without runtime evidence.
- Rollback is the isolated branch/worktree; no canonical checkout or external surface is modified.

## Remaining device validation

- Physical touch clarity, TalkBack speech order, perceived musical usefulness, audio clicks/latency and human preference remain outside `LOCAL_PASS`.
