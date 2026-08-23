# ADR-0001: Production command/effect seam

- Status: Accepted for incremental migration
- Date: 2026-08-24
- Scope: Android, Windows, and future iPhone application semantics

## Context

The shared deck currently calls a wide method-per-action controller interface. Android and Windows implement almost the same surface separately, but validation, history, autosave, status, and playback ownership already differ. Adding more DAW capabilities at this seam multiplies semantic drift.

## Decision

Introduce a shared `ProductionCommand -> ProductionCommandResult` reducer.

The result classifies a mutation as:

- `PROJECT`: durable creative content changed; record history and schedule persistence.
- `SESSION`: selection, guidance, or transient UI truth changed; do not create Undo or autosave churn.
- `NONE`: command made no observable change.

The result also emits typed `ProductionEffect` values. Platform controllers translate only those effects into audio, document, permission, and lifecycle operations.

Migration is incremental. Existing controller methods may remain as compatibility adapters that dispatch commands. Commands are moved only when shared tests define their complete acceptance and negative paths.

## Consequences

- One rule change can serve Android, Windows, MIDI, and future assist proposals.
- History and autosave become domain decisions instead of platform accidents.
- Runtime side effects remain explicit and independently falsifiable.
- The initial code temporarily contains both migrated commands and legacy methods.
- A later `ProductionSession` can own history/revision/autosave without changing command semantics.

## Rejected alternatives

- A one-shot rewrite of both controllers: too much lifecycle and audio risk for one review boundary.
- UI-only normalization: identical pixels would still hide different editing behavior.
- Native-engine-first migration: improves latency but does not fix application semantic drift.
- Generic event bus without typed outcomes: lowers compile-time guarantees and obscures persistence/effect ownership.

## Rollback

Each migrated method retains its public controller signature. Reverting the reducer integration restores the prior platform implementation without changing project schema or user data.
