# Bank-role workflow, safe persistence, and reliable playback

## Purpose and user-visible outcome

ChopLabの操作を「入れる → チョップ（切る／鳴らす）→ ビート → 完成」の4工程へ整理する。BANK Aはメロディー、BANK Bはドラム、BANK Cはワンショット、BANK Dはボイスとして常に表示し、演奏PADとビート配置をMPC/DAW的に読みやすくする。既存PAD・プロジェクト・自動保存を無警告で上書きせず、曲が再生中でも演奏画面のPADが確実に鳴るようにする。

追加フィードバックとして、BANKごとに32 PADを16 PADずつページ表示し、Aメロディーを新規チョップの既定先にする。ビート画面からKEY/TONEを直操作できるようにし、試聴直後のループ二重発音を止める。Layer Studioはドラム以外の既存音も配置できるようにし、Scratchは元曲の選択範囲を直接対象にする。最下部へ新しい完成説明文は追加しない。

## Current state

- Baseline: `0e938b1d7ff8e1b3f86c3ec011f647186d6a6ee7` on `agent/bank-role-safe-playback-ui`.
- v0.8.0 has five workflow tabs. `triggerPad()` treats every PAD press during source playback as live-chop capture, even when the caller is the performance or arrangement surface.
- Built-in kits can replace a target bank, slice assignment wraps within a bank, and autosave keeps one previous generation.
- The current play screen is a generic 4x4 grid; arrange shows one large waveform and a second 8x2 PAD grid.
- Existing untracked `outputs/` and `work/` are preserved.

## Constraints and invariants

- No Figma and no scroll containers.
- Preserve project schema compatibility and existing imported audio, PAD parameters, pattern, loop, vocal, scratch, Undo/Redo, export, and HTML-derived beginner workflow.
- No silent replacement of assigned PAD audio. Destructive replacement requires an explicit confirmation path and remains undoable where applicable.
- Audio callback rules remain strict: no allocation, blocking, I/O, logging, or UI work. Loop-start voice replacement uses an allocation-free reverse removal in the existing command-drain phase.
- Existing autosaves must remain readable; safety copies must be bounded.

## Architecture and interfaces

- `PadPressAction` is a host-testable public routing seam. Capture mode may create a chop; performance/arrangement mode may preview an assigned PAD even while source playback is active.
- `BankRole` is a stable UI/domain label mapping: A Melody, B Drums, C One-shots, D Voice. It does not change archive indexes.
- `assignRangesToPads` and built-in-kit application use explicit no-overwrite decisions.
- `AtomicProjectStore` writes a validated pending archive, rotates bounded generations, and recovers newest valid state.
- Compose uses one combined Chop stage with CUT/PADS submodes and a four-lane beat arranger.

## Agreed test seams

1. `resolvePadPressAction`: source playing + performance mode routes to audible preview; capture mode routes to live chop; empty performance PAD remains selection-only.
2. `assignRangesToPads` and kit replacement policy: assigned audio is not silently replaced.
3. `AtomicProjectStore`: interrupted writes and multiple generations preserve a valid prior project.
4. `WorkflowStage`/`BankRole`: four-stage journey and stable bank labels.
5. 32-PAD paging: old 16-PAD banks migrate without index drift; visible pages contain exactly 16 pads.
6. Loop start ordering: an existing preview voice for the loop target is stopped before the loop starts.
7. Source scratch target: selected source start/end-exclusive range becomes the scratch snapshot.

## Milestones

### Milestone 1: Reproduce playback routing defect
- Add a red host test for source-playing performance PAD presses.
- Add the minimal public routing seam and wire real call sites.

### Milestone 2: Non-destructive bank and save behavior
- Add bank-role model and UI labels.
- Make sample/kit replacement explicit and bounded.
- Extend atomic autosave recovery generations and tests.

### Milestone 3: UI redesign
- Combine Slice/Play into one Chop stage with CUT/PADS modes.
- Replace the generic performance-pad presentation with role-aware sample cards inside square pads.
- Replace Arrange with a compact four-lane beat board, live playhead, and selected-sound controls.

### Milestone 4: Validation and delivery
- Run targeted tests, full tests, lint, assemble, project validation, no-scroll scan, and code review.
- Install the exact local APK on Pixel 9a, verify primary paths without activating the microphone, and publish only after CI evidence.

### Milestone 5: Additional workflow consolidation
- Expand each role bank to 32 PADs with a 1–16 / 17–32 fixed page selector and migration tests.
- Reset new imported/recorded source chopping to BANK A Melody while preserving explicit later bank choices.
- Put selected PAD KEY/TONE on the normal Beat surface.
- Stop a preview voice before starting the same PAD as a beat loop.
- Add a general sample-layer page and source-range scratch workflow.

## Progress

- [x] 2026-08-11 — Repository, public baseline, device evidence, and user feedback fixed.
- [x] 2026-08-11 — Baseline `scripts/validate_project.sh` PASS; PowerShell doctor required ExecutionPolicy bypass and reported missing process-level Java/SDK PATH despite pinned tools being available.
- [x] Playback routing and source-end restart red/green loops.
- [x] Bank roles and non-destructive persistence.
- [x] Performance and beat-board UI redesign.
- [x] Two-pass local code review; dead legacy Chop UI and duplicated BANK role colors were resolved, with zero open findings.
- [x] 32-PAD/schema-5 follow-up, direct BEAT controls, loop de-duplication, general layering, and source-range scratch implemented and locally/emulator validated.
- [ ] Public evidence and latest physical-screen/audio validation; the final APK is on the locked Pixel, but no latest UI/audio claim is made.

## Discoveries

- The five-stage UI sends Capture directly to Play in one path, making the numbered 1→3 jump real rather than merely visual.
- `triggerPad()` infers capture intent only from `sourcePlaying`; the Compose caller cannot distinguish sampling from performance.
- Current Arrange spends most of portrait height on an empty single-pad waveform and repeats all 16 PADs below it.
- A completed source leaves the UI playhead at its final frame; replaying that exact frame ended immediately. Restarting only completed/out-of-range positions from zero preserves pause/resume behavior.

## Decision log

- 2026-08-11 — Use four top-level stages and preserve CUT/PADS as local Chop modes.
- 2026-08-11 — Treat BANK roles as stable semantics without changing physical bank indexes or archive format.
- 2026-08-11 — Do not auto-download or ship artist-branded samples; retain original built-in synthesis.

## Validation log

- `scripts/validate_project.sh` — PASS on baseline with pinned JDK/Kotlin PATH.
- `powershell -ExecutionPolicy Bypass -File scripts/doctor.ps1` — completed with environment warnings; no mutation.
- targeted red/green tests — PASS for PAD routing, source-end restart, bank roles, no-overwrite assignment, three-generation recovery, and beat-lane state.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — PASS; 81 unit tests, zero failures/errors/skips; lint errors 0.
- `scripts/validate_project.sh`, `git diff --check`, and zero-scroll source scan — PASS.
- Pixel 9a local build — installed as 0.9.0; fixed PADS/BEAT layouts observed; source restarted from end; B-01 performance press kept source playing and did not change autosave bytes.
- Pixel 9a follow-up — condensed source waveform is fully visible in PADS; MANUAL plus a waveform tap added a numbered chop boundary while all 16 square PADs remained on-screen.
- Local parent two-pass review — Standards: 0 open / 2 resolved. Spec: 0 open. No substitute child model used.
- Exact Pixel schema-4 archive migration — PASS in a one-off local probe and Pixel 9/API 36 emulator cold recovery; source waveform, chop markers, PADs, and pattern restored under schema 5.
- Pixel 9/API 36 emulator — fixed CHOP/PADS, PAD 17–32, BEAT direct controls, SOUNDS layering, and original-source Scratch screens observed with no scrolling. One stale `PADS_PER_BANK` assertion crash was reproduced and fixed before the final run.
- Pixel 9a latest local APK — in-place install and `/sdcard/Download/ChopLab-0.9.0-latest.apk` copy succeeded with matching SHA-256; phone remained locked, so current-screen and subjective-audio validation remain open.

## Risks and rollback

- Dense four-lane arrangement must remain finger-usable on Pixel 9a and landscape.
- Existing projects may have drums in A or samples in B; labels describe recommended roles and must not migrate or delete them.
- Keep the v0.8 release tag unchanged; this work ships as a new version and is independently revertible.

## Remaining device validation

- Public CI/release asset must still be built, reverse-downloaded, hash-checked, and copied to the device before claiming `PUBLIC_PASS`.
- Do not activate physical microphone recording without explicit privacy confirmation.
