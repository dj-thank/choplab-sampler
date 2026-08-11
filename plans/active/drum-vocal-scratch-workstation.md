# Drum, Vocal, and Scratch Workstation

## Purpose and user-visible outcome

ChopLab の固定画面を保ったまま、PAD を正方形にし、初心者でも「音色を選ぶ → ビートへ足す → 声を重ねる → 指でスクラッチする」まで辿れるレイヤー制作機能を追加する。Figma や既存機材の意匠コピーには依存せず、Compose でオリジナルの操作面を実装する。

## Current state

- Baseline is commit `c107bc85789fd5e6843abaaf504c6845b5a38b64` on branch `agent/drum-vocal-scratch-deck`.
- The app already has 4 banks × 16 PADs, chop playback including LOOP mode, a 16-step pattern, microphone capture, versioned project archives, undo/redo, offline export, and a no-scroll portrait/landscape console.
- `PadGrid` currently stretches each cell to the parent, so cells are not guaranteed square.
- Microphone capture currently replaces the source audio; there is no project-aware vocal take concept.
- The real-time engine has loop playback but no signed-speed scratch voice.
- Existing untracked `outputs/` and `work/` are preserved and remain outside this change.

## Constraints and invariants

- Do not ship artist recordings, unofficial artist-branded kits, or samples with unclear redistribution rights. “Nujabes-like” is only a direction; bundled sounds are original deterministic synthesis.
- Preserve the fixed no-scroll console in portrait and landscape, existing imports/chops/patterns, and the old HTML-derived workflow.
- Audio callback work must not allocate, block, perform I/O, log, or call UI code.
- Schema migration must read older schema 1–3 projects and fail safely on malformed archives.
- Vocal recording requires explicit microphone permission, releases resources on stop/teardown, and warns that headphones are recommended.
- UI exposes one coherent `LAYERS / 音を重ねる` surface rather than adding many top-level buttons.
- Existing user changes and transient directories are not staged or deleted.

## Architecture and interfaces

- `PadGridGeometry`: pure square-cell calculation used by Compose and host tests.
- `PadContentKind`: persisted `SAMPLE`, `DRUM`, or `VOCAL` role on each PAD.
- `BuiltInDrumKits`: deterministic original PCM one-shots plus beginner starter patterns. A ViewModel intent applies a kit to a selected bank while preserving other banks.
- `VocalTake`: microphone WAV is decoded and assigned to the next available vocal PAD in bank D. A base LOOP can be restarted at record start; vocal PADs start once alongside loop playback and render into export.
- `ScratchPlaybackCursor`: pure signed-speed, bounded cursor. `SamplerPlaybackEngine` exposes begin/update/end scratch; `SamplerEngine` owns one scratch voice and uses atomic speed updates.
- `LayerStudio`: fixed-height Compose surface with DRUMS, VOICE, and SCRATCH modes, reachable from the existing “音を足す/重ねる” action.

## Milestones

### Milestone 1: Baseline and proof

- Add RED tests at the four agreed seams: square geometry, drum-kit application, scratch cursor, and archive round-trip for vocal roles.
- Record legal-source findings and the decision to bundle original synthesized sounds.

### Milestone 2: Core implementation

- Implement square PAD geometry and deterministic built-in drum kits.
- Add persisted content roles and schema migration.
- Add vocal take assignment and export behavior.
- Add allocation-free scratch playback controls.

### Milestone 3: UI and lifecycle integration

- Add the no-scroll Layer Studio and professional kit selector.
- Connect microphone permission/lifecycle and vocal controls.
- Add an accessible touch jog/scratch surface and clear active-state feedback.

### Milestone 4: Validation and documentation

- Run targeted RED/GREEN tests, full unit tests, lint, assemble, validation scripts, and no-scroll/source scans.
- Perform independent Standards and Spec review against the baseline.
- Build/install the debug APK on the connected Pixel and verify launch and primary interaction paths without claiming unmeasured audio quality.

## Progress

- [x] 2026-08-11 00:00 JST — Baseline, repository instructions, completion criteria, and implementation seams confirmed.
- [x] 2026-08-11 — RED/GREEN tests completed for geometry, drum kits, scratch cursor, vocal export, and schema migration.
- [x] 2026-08-11 — Core domain/audio implementation completed.
- [x] 2026-08-11 — Layer Studio UI and Android permission/lifecycle integration completed.
- [x] 2026-08-11 — Dual-axis review completed; vocal-bank overwrite and realtime scratch allocation findings were fixed and regression-tested.
- [x] 2026-08-11 — Final implementation commits merged through PR #8; main/tag/release CI passed and public prerelease `v0.8.0-preview.1` was reverse-verified.
- [x] 2026-08-11 — Full local validation, APK build, exact-hash Pixel install, fixed-layout/device screenshots, kit application, and schema-4 cold recovery completed.

## Discoveries

- Existing no-scroll layout is entirely fixed/weighted Compose; there are no scroll containers in app source.
- `PadGrid` supports both 4×4 and 8×2 callers, so square sizing must be derived from both width and height and centered without changing PAD count.
- Existing microphone recording can be reused, but its stop path must not replace the source when recording a vocal layer.

## Decision log

- 2026-08-11 — Bundle five original, deterministic, synthesized drum kits. Do not bundle artist audio or depend on an online download at runtime.
- 2026-08-11 — Reserve bank D as the default vocal-take destination while keeping all PADs editable.
- 2026-08-11 — Use one Layer Studio entry with three modes to preserve beginner clarity and no-scroll density.
- 2026-08-11 — Scratch controls one selected/active loop at a time; ending a scratch leaves normal playback stopped until the user deliberately resumes it.

## Validation log

- Baseline `scripts/doctor.ps1`: PASS with optional NDK/CMake warnings only.
- Baseline `scripts/validate_project.sh`: PASS.
- Final Gradle test/lint/assemble: PASS with 66 unit tests and zero lint errors.
- Final review: BANK D full-capacity behavior is fail-safe; scratch voice construction occurs on the control thread and the audio loop performs no new per-frame allocation.
- Public release: PR #8 merged at `d99a27f4bdb3aa609500bb1334aa782382fe25f8`; tag and Release workflows passed; public APK/checksum and Pixel Download copy matched SHA-256 `D3C26D20023A9D25B19E316D1C77A44D067DCA7717DDA3BDA2F82067A58EC1A8`.

## Risks and rollback

- Procedural kit timbre needs human listening; tests prove determinism, bounded output, and routing but not artistic quality.
- Microphone monitoring can feed back through speakers; the UI recommends headphones and does not add live monitoring in this slice.
- Scratch DSP can click at abrupt velocity transitions; smoothing and boundary fades are required and covered by cursor/engine checks.
- Each domain layer is independently revertible; schema 4 remains backward-readable and does not overwrite old archives in place.

## Remaining device validation

- Install the final review-fixed APK on Pixel 9a and verify its exact hash.
- Physical microphone capture remains intentionally untested to avoid recording ambient user audio without an explicit privacy confirmation.
- Subjective audition of every kit and measured scratch latency remain human/device-audio checks.
