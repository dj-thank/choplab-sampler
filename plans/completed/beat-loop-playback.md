# チョップ済みビートを連続ループできるようにする

## Purpose and user-visible outcome

`並べる`で音の入ったPADを選び、`ビートをループ`を押すと、そのチョップ範囲が末尾から先頭へ連続再生される。ループ単位は16-stepの拍間隔ではなく、PADへ割り当てた音声範囲そのものとする。4つ打ち・8分・16分は別の`配置プリセット`として詳細編集に残し、ドラムなど別BANKの音を重ねられる。

## Current state

- Build target: `app/`; branch `main`; baseline `c9f5ef1`.
- `PadPlayMode` is currently `ONE_SHOT` or `GATE` only.
- `SamplerEngine.Voice` stops at the start/end boundary.
- Arrange quick mode calls quarter/eighth/sixteenth placement `反復`, which conflicts with the requested meaning.
- Existing public v0.6.0 preview has LOCAL/PUBLIC/DEVICE evidence, but no whole-chop beat loop.

## Constraints and invariants

- Keep `minSdk=29`, the current AudioTrack engine, and mono MVP behavior.
- Frame ranges remain start-inclusive/end-exclusive.
- The audio thread must not allocate, block, perform file I/O, or call UI APIs.
- Existing ONE_SHOT and GATE behavior and schema-1/schema-2 project read compatibility must remain intact.
- No scrolling is introduced; portrait and landscape must keep the fixed console.
- Loop mode must be stoppable from the same primary control and `ALL STOP`.
- `配置プリセット` remains an optional sequencing tool and must not be described as the loop itself.

## Architecture and interfaces

- Add `LOOP` to `PadPlayMode`; persist it in schema 3 so older apps fail with an update boundary instead of misreading a schema-2 enum.
- Add a host-testable audio cursor seam that advances or wraps without Android dependencies.
- Let `SamplerEngine.Voice` use that seam and prevent duplicate infinite voices for the same PAD.
- Add explicit loop playback state and intents to `SamplerViewModel`; `ALL STOP` clears that state.
- Replace Arrange quick `反復` controls with one clear beat-loop control. Move quarter/eighth/sixteenth to the fine view under `配置プリセット`.
- Update guidance and README terminology.

## Milestones

### Milestone 1: Domain and cursor Red/Green

- Add tests for LOOP model/persistence and forward/reverse boundary wrapping.
- Implement the smallest cursor and enum changes that pass.
- Run focused model/audio/persistence tests and Kotlin compilation.

### Milestone 2: Engine and ViewModel wiring

- Wire LOOP into voice rendering and add exact-pad stop behavior.
- Track the actively auditioned loop PAD in state.
- Make PAD press and the primary Arrange control start/stop one bounded loop voice.
- Verify ONE_SHOT/GATE remain unchanged.

### Milestone 3: Beginner-first no-scroll UI

- Change quick guidance to `PADを選ぶ -> ビートをループ -> 音を重ねる`.
- Add the primary `ビートをループ / ループ停止` control.
- Rename and retain 4/8/16 options only as `配置プリセット` in fine controls.
- Verify source contains no scroll APIs and both orientation compositions compile.

### Milestone 4: Validation, review, APK, and device

- Run `scripts/validate_project.sh`, unit tests, lint, assemble, and `git diff --check`.
- Run Standards and Spec review against baseline `c9f5ef1` and this plan.
- Fix valid findings, update project state, and commit.
- If a physical device is connected, install the exact APK and run a focused launch/loop smoke check without claiming subjective latency quality.

## Progress

- [x] 2026-08-10 - Confirmed the user means whole-chop continuous playback, not beat-grid retriggering.
- [x] 2026-08-10 - Mapped current model, engine, ViewModel, UI, persistence, and existing placement tests.
- [x] 2026-08-10 - Attempted the bounded ChatGPT Pro proposal; no durable response was returned, so no recommendation was adopted and no duplicate request was sent.
- [x] 2026-08-10 - Completed Red/Green implementation slices for cursor wrapping, rendering, schema 3, placement separation, and beginner guidance.
- [x] 2026-08-10 - Completed local validation, final APK generation, exact-device installation, Download copy, and focused Pixel loop checks.
- [x] 2026-08-10 - Completed fixed-point Standards and Spec review and resolved both valid findings.
- [x] 2026-08-11 - Completed fix commit, PR #6, main/tag CI, public preview release, reverse-download verification, and exact public APK migration on Pixel 9a.

## Discoveries

- The current quick flow labels 4/8/16 step placement as `反復`, so it cannot express the requested audio-loop concept.
- `SamplerEngine.Voice` already has bounded immutable pad snapshots and a click-fade envelope, providing a contained place to add looping.
- Project archives serialize `PadPlayMode.name`; schema 3 makes the new enum explicit while schema 1/raw PCM and schema 2/WAV remain readable.
- Physical testing found that selecting BANK B while A-04 looped made the old control show START. The control now follows the active loop PAD and remains STOP until that loop is stopped.

## Decision log

- 2026-08-10 - Treat `Beat loop` and `Pattern placement` as separate domain concepts.
- 2026-08-10 - Keep placement presets available for drum layering, but remove them from the default quick path.
- 2026-08-10 - Use one explicit start/stop loop action instead of requiring a step trigger.
- 2026-08-10 - Treat one PAD as the project beat-loop base. Activating another loop returns the previous loop PAD to ONE_SHOT and clears beat-grid events from the loop PAD.
- 2026-08-10 - Write new archives as schema 3; keep schema 1 and 2 readers.
- 2026-08-10 - When any loop is active, the primary control follows that active PAD and stops it even if another BANK/PAD is selected.

## Validation log

- `scripts/validate_project.sh`: PASS before and after implementation.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon`: PASS; 53 tests, zero failures/errors/skips; lint zero errors and nine existing advisories.
- `git diff --check`: PASS; source scan found zero scroll API matches.
- Final local APK: 30,942,730 bytes; SHA-256 `AD0E2079574DB72B28C928439F3CF3C45BB59322C2ADECBD8E3637F67A1C945A`.
- Pixel 9a: exact final APK installed as v0.7.0/code 8 and copied byte-for-byte to Download; it restored the project and started A-04 with a live 26–29% loop readout. The preceding same-version candidate supplied the 23%→75%, cross-BANK active-waveform/STOP, and zero-focused-fatal evidence; later review fixes were limited to engine allocation and export gating.
- Review execution: `Execution: local parent two-pass (no substitute child model used)` against fixed point `c9f5ef15ced1464b8f3593969bf0bbd7c4f2e23b` and this plan.
- Standards finding resolved: replaced new audio-thread `filter` allocations with indexed release scans, including the adjacent existing gate/choke paths.
- Spec finding resolved with Red/Green evidence: loop-only export was incorrectly rejected by the ViewModel precondition even though the renderer supported it; `hasAudiblePatternContent` now accepts an assigned LOOP PAD without step events.
- PR #6 merged as `9d09228c7d19cdd709b7c864e21eddaa69715d67`; branch/PR/main/tag Android runs `31400890047`, `31400928956`, `31401298050`, and `31401606925` passed.
- Release run `31401606890` published prerelease `v0.7.0-preview.1`. Reverse-downloaded public APK: 30,346,187 bytes, SHA-256 `3393A60EBB8FDD3CE76CD459150049807D63DC39CF62BB4CF213365FB5FD1CB2`; GitHub digest and checksum attachment matched.
- Pixel 9a exact-public migration: backed up/restored 12,003,628-byte autosave at SHA-256 `75C8BB8E5FFC8E6FA0006212E4A869593A2C8D680B44DD5DA7474A862CC45B42`, cold-launched v0.7.0/code 8, restored `Without You.mp3`, and found zero focused fatal matches.

## Risks and rollback

- A loop boundary can click or produce a level dip. Keep the cursor/crossfade logic isolated and host-tested; revert the loop mode without changing stored ONE_SHOT/GATE values if device audio is unacceptable.
- An infinite voice can leak or duplicate. Stop exact PAD voices on toggle/retrigger and clear loop state on `ALL STOP` and project restoration.
- Fixed layouts can clip after copy/control changes. Keep labels short and verify compact portrait/landscape.

## Remaining device validation

- Audible seam quality on headphones and speakers.
- Long-session loop stability and thermal/latency behavior.
- Physical multi-touch while a beat loop and drum sequence play together.
- TalkBack order and haptic clarity for start/stop loop states.
