# ADR-0005: Guided first-entry and adaptive workflow chrome

- Status: Accepted
- Date: 2026-08-24
- Scope: shared Android/Windows first entry, large-text chrome and playable-PAD selection

## Context

The starter production is intentionally playable, but the previous first screen showed an empty waveform and four source actions without explaining that BEAT and SAVE belonged to the included DUSTY JAZZ demo. At Android font scale 1.3 and 2.0, fixed-height one-row workflow chrome clipped or overlapped labels. The Desktop BEAT transition also selected B-01 without synchronizing the visible bank and page because it bypassed the shared state transition.

The product should stay simpler than a general DAW. MPC and Cubase are functional references for staged work and stable controls, not visual or structural templates to copy.

## Decision

Keep the shared four-stage model `入れる → チョップ → ビート → 保存`, and make the pristine CAPTURE workspace an explicit choice surface:

- own audio or a previous project remains the primary route;
- microphone and permitted device-audio recording remain visible but secondary;
- the built-in starter is named as a separate `DUSTY JAZZデモ` route;
- loading, loaded-source and active-recording states retain the existing waveform and stop/safety controls.

At font scale 1.2 or greater, the shared layout policy uses a simplified header, a two-by-two workflow strip and a two-line status region. The first-entry body scrolls when enlarged content or compact landscape height cannot fit. Large-text BEAT quick/detail bodies also use bounded scrolling with explicit waveform and 48 dp-safe PAD-grid heights; global chrome remains fixed. Normal text retains the one-row chrome and existing responsive BEAT composition.

Within a scrollable BEAT body, ONE SHOT PAD selection and playback commit only after the pointer gesture completes as a tap. GATE waits through a short scroll-classification window, then preserves the remaining physical hold until pointer-up. A vertical drag canceled by the parent before activation sends no PAD model or audio action. Non-scroll PAD surfaces keep their direct press-down performance path.

Both platform controllers use the shared `ensurePlayablePadSelected` state transition, which updates selected PAD, bank and page together.

## Consequences

- A first-time user can distinguish making with personal audio from trying the bundled demo.
- The demo keeps its existing pads, pattern and export readiness; no audio or project semantics change.
- Large text preserves full action labels and 48 dp minimum targets instead of shrinking typography.
- Constrained CAPTURE or large-text BEAT may require an intentional body scroll; global chrome and normal-text composition do not scroll.
- When a 48 dp large-text PAD cannot fit its secondary LOOP/DRM/VOX/key caption cleanly, the visible cell keeps its complete PAD identity while accessibility semantics always state both play mode and content kind.
- Android and Windows render the same entry and workflow policy while keeping platform I/O adapters separate.

## Rejected alternatives

- Disable BEAT and SAVE until user audio exists: removes the useful starter and conflicts with new-production behavior.
- Add permanent browser, mixer or timeline panes: increases first-run complexity and copies the problem shape of a full DAW.
- Keep one workflow row and reduce type size: preserves clipping pressure and weakens accessibility.
- Repair Desktop by copying only another index: leaves bank/page coherence duplicated and fragile.

## Rollback

The change is presentation policy plus one shared selection delegation. Reverting it restores the previous first screen and chrome. Project schema, audio bytes and user data require no migration.
