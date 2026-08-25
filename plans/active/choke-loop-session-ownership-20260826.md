# Choke loop-session ownership

## Objective

When a PAD trigger uses the same nonzero choke group as the active Beat-loop owner, stop the complete owned loop session—vocal companions, owner audio and published loop state—before starting the requested PAD. Unrelated groups and normal different-PAD polyphony must remain available.

## Exact boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-choke-loop-session-20260826` / current root task
- Base: `639d5132c12bd3efe0d0346731cef9fbdaca15ec`, tree `4ee030eaddfc30d1727e09851092dac10e819270`
- Decision receipt: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE5_20260826.md`
- Files: shared `PlaybackLayeringPolicy` and tests, Android `SamplerViewModel`, Windows `DesktopSamplerController` and host tests, revision-bound docs
- Target gate: `LOCAL_PASS`
- Excluded: schema/project/audio bytes, UI layout/copy expansion, realtime callback work, Pixel/ADB, OAuth/provider, GitHub/public/release, signing/secret and Human operations

## Current defect

Both live engines enforce choke groups at the voice boundary. Android eventually clears the loop-owner read-back after its released voice ends, but controller-owned vocal companions and the old loop status remain. Windows closes the loop voice but has no loop-owner read-back, so `loopingPadIndex` and companions remain until another explicit stop.

## Delivery contract

1. A shared pure policy computes the complete stop plan only when the requested PAD and current loop owner share the same nonzero choke group and are different PADs.
2. The stop plan includes all vocal companions from the existing loop-start ownership rule and the loop owner exactly once.
3. Android and Windows stop that plan before the requested trigger and clear loop owner/playhead truth.
4. Windows stop failure rejects the requested trigger and does not publish a false cleared state.
5. Group 0, different groups, same owner, missing/unassigned PADs and ordinary different-PAD polyphony remain unchanged.

## Falsifiable checks

- RED Desktop host test: current code leaves loop state and companions after a matching choke trigger.
- Shared tests: matching plan plus group-0/different-group/same-owner/invalid negative controls.
- Desktop tests: exact companion/owner stops, requested trigger, state clear; different group preserved; stop failure fail-closed.
- Focused shared/Desktop tests, Android unit compile/tests, then the full cross-platform Gradle/lint/package/CycloneDX gate.
- Python public/release policy, configured validation, public-surface and `git diff --check`.

## Stop / rollback

Stop if the change removes unrelated voices, changes project history/autosave, blocks the requested PAD after successful stop, adds realtime callback allocation/I/O/blocking or cannot remain migration-free. Roll back the isolated product commit to restore the base.
