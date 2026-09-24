# ADR-0002: ProductionSession transaction boundary

- Status: Accepted for Horizon 2 migration
- Date: 2026-08-24
- Scope: shared application-state history, revision and persistence admission

## Context

ADR-0001 unified the meaning of six cross-platform commands, but Android and Windows still repeat the reduce, blocking-effect, history, state-decoration and persistence-admission sequence. They also manipulate `EditHistory` directly in source load, recording, reset, recovery and Undo/Redo paths. Growing the command set at this seam would duplicate lifecycle rules again.

## Decision

Introduce one shared `ProductionSession` that owns:

- bounded edit history and merge-key coalescing;
- monotonic project revision;
- PROJECT / SESSION / NONE transition classification;
- `canUndo` / `canRedo` state decoration;
- persistence admission for durable project transitions;
- one-owner, one-use command plans.

Autosave recovery reads the verified generation revision with the state. The session adopts at least that revision and advances once for the replacement, so the next durable edit cannot be rejected as older than the project it recovered.

Command application is two-phase. `planCommand` reduces the pure command and exposes its typed effects. A platform adapter executes blocking effects such as `StopPad`. Only after they succeed may it call `commit`; on failure it calls `cancel`. This prevents history/revision/state success from preceding required runtime teardown.

Platform controllers still own lifecycle resources, StateFlow publication, audio/document adapters and the actual autosave scheduler. They serialize calls into the session using their existing state owner.

## Consequences

- Android and Windows share history/revision/persistence decisions without moving platform APIs into common code.
- A stale, foreign, cancelled or already committed plan fails closed.
- Legacy edits can migrate through `applyEdit` before every UI action becomes a typed command.
- The first migration retains platform-specific state publication and post-commit effect handling; a later state-owner refactor may remove those final adapter differences.
- Legacy archives without revision metadata still recover; the session advances from its current revision while the store retains its existing validation and generation fallback policy.

## Rejected alternatives

- Wrap only `EditHistory`: leaves mutation classification, revision and effect ordering duplicated.
- Let the reducer execute platform callbacks: couples pure domain rules to audio/lifecycle APIs.
- Commit state before blocking effects: can report a stopped loop while the old voice still owns playback.
- Rewrite controllers and runtime state at once: expands the rollback and device-validation surface beyond one tracer.

## Rollback

`ProductionSession` does not change project schema or public controller signatures. Reverting its integration restores the previous direct `EditHistory` and revision fields without data migration.
