# ADR-0004: Full-bar pattern/master parity gate

- Status: Accepted for single-event oracle
- Date: 2026-08-24
- Scope: one PAD event, realtime Voice expectation and offline mono WAV master

## Context

Shared numeric primitives and a direct PAD Voice/host renderer comparison do not cover event-loop ordering or master output. A pitched cursor can return a final nonzero sample and become finished in the same render call. Offline code previously removed that voice before mixing the returned value.

## Decision

Build the expected full-bar PCM from `SamplerEngine.Voice` and shared `softLimit`, then compare every frame to `PatternRenderer` WAV with a maximum one-unit PCM tolerance. Mix a rendered value before checking whether the offline voice should be removed.

This oracle is the required gate for the single-event/master dimension before Voice/event-kernel extraction or native-engine replacement.

## Consequences

- Offline export retains the last sample that realtime playback returns.
- Event-loop ordering is now measured independently from the numeric primitive tests.
- Existing WAV bytes can change by the restored terminal sample.
- Polyphony, choke, repeated events, loop voices, vocals, stereo and physical perception are not inferred from this fixture.

## Rejected alternatives

- Raise tolerance above 61: hides a deterministic missing sample.
- Drop the realtime final sample: changes the behavioral baseline to match the bug.
- Extract a shared mutable Voice immediately: expands lifecycle and callback risk before event ordering is fully mapped.

## Rollback

The change is one mixing-order line plus a test. Project schema and user data are unaffected.
