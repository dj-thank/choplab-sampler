# ADR-0003: Shared audio parity primitives before engine replacement

- Status: Accepted for numeric parity tracer
- Date: 2026-08-24
- Scope: Android realtime, JVM offline export and Windows PAD rendering

## Context

The three render paths independently implement pitch resampling, tone filtering, gain, boundary fade, swing and limiting. Their finite formulas are similar, but invalid input behavior differs and offline tone computes an exponential inside the per-sample loop. Replacing the engine first would preserve hidden divergence and enlarge the validation surface.

## Decision

Create allocation-free `SamplerDspPrimitives` in shared commonMain for:

- parameter sanitation and finite clamp policy;
- source-step calculation;
- tone-filter coefficient;
- forward/reverse boundary envelope;
- saturating soft limiter;
- swung step duration;
- exact transport deadline to first-not-earlier whole-frame quantization.

All existing renderers delegate these numeric rules. Voice ownership, cursor lifecycle, command queues, native/audio device handles and file output remain in their current modules.

Invalid values use explicit safe neutral values: pitch 0, tone bypass, gain silence, BPM 92 and straight swing. Tone alpha is calculated once when a voice starts or its live tone changes.

## Consequences

- Realtime/offline/desktop policy changes have one source and common host tests.
- The realtime callback gains no allocation, lock or I/O.
- Offline rendering removes one exponential calculation per active voice per sample.
- A direct realtime/host PCM oracle can detect drift before a shared voice kernel or native engine is introduced.
- Offline events and total WAV length retain fractional timing across bar boundaries instead of truncating each event or rounding every bar independently.
- This does not yet prove full-pattern/master equality, physical audio quality, latency or native-engine parity.

## Rejected alternatives

- Native-engine-first rewrite: too large and does not define an oracle for current audible behavior.
- Keep duplicate formulas and compare screenshots/files manually: cannot localize numeric drift or invalid values.
- Share a mutable Voice immediately: mixes lifecycle/real-time ownership with the smaller numeric policy seam.
- Silently clamp NaN through platform behavior: `coerceIn` does not provide a consistent cross-platform NaN policy.

## Rollback

The public model and project schema are unchanged. Reverting the primitive delegation restores the previous inline formulas without data migration.
