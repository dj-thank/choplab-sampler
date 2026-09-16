# Pattern editing refinement

## Purpose and user-visible outcome
Share Android/Windows pattern preset and clear commands, add reversible selected-PAD step rotation and clear only the selected A/B variation.

## Current state
Base: origin/main bed7a55. Root integrator owns this isolated checkout, work/choplab-production-refinement-20260905. Canonical dirty checkout and existing worktrees are preserved. Existing pattern edits are duplicated in platform controllers; selected-variation clear and rotation are absent.

## Constraints and invariants
LOCAL_PASS target. Preserve other PADs, the other variation, Song structure, sample bytes and archive schema. Loading/recording admission remains shared; selected-variation clear requires stopped transport. No device or publication work in this milestone. Rollback is this task's own diff against bed7a55. Stop on ownership conflict or data-loss regression.

## Architecture and interfaces
ProductionCommand owns edit semantics, ProductionSession owns history/revision/autosave admission, platform dispatch owns effects. Shared deck invokes default controller dispatch methods. No new dependencies.

## Milestones
1. Shared edit commands, safety/no-op contracts and one-step history.
2. UI controls and platform deduplication; controller save/reopen proof.
3. Full Android/shared/JVM/desktop checks and Windows package; independent review.

## Progress
- [x] 2026-09-05: main fetched, isolated checkpoint created, existing instructions and current state read.
- [x] Implementation and regression tests.
- [x] Full validation and review; compact-screen issue fixed and covered by real component input.

## Discoveries
Canonical checkout is an older dirty baseline. Current product is the fetched main, including PR 88. Existing retained release plan remains historical input for this new explicitly authorized product task.

## Validation
769 standard tests + 25 UI/controller checks; failure/error/skip 0. Full Android/shared/JVM/Desktop gate, Windows package, validate_project.sh and public surface passed. See outputs/pattern-editing-refinement-20260905.md. No physical audio, device, public or Human result claimed.
