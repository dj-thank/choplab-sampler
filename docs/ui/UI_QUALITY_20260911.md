# UI quality follow-up — 2026-09-11

## Scope and identity

Parent: PR96 `3fe5906bbc29e54bb46689606ffb0cac3599e1ca`, exact source tree `28a68a190fcfc3ef01acdae4cef0aac58ef31cf2`. This is a review candidate, not a shipped app. The selected execution plan is [ui-quality](../../plans/active/ui-quality-20260911.md).

## Implemented

- Extract existing buttons, sliders, steppers, value displays and stage buttons into shared components with unchanged control callbacks. Compact primary text8→10sp, regular12sp, readable disabled text, stronger selected tabs and keyboard focus borders. Prefer the Japanese action over redundant English captions without cancelling system font scaling. Cache presentation instead of adding per-button subcomposition.
- Read-only header status control: Japanese branding and explicit status-details affordance; full status/source/selected PAD in a scrollable dialog. Secondary header bank text hides on narrow widths. Original fixed global chrome budget stays unchanged.
- SAVE screen gives each of five action rows at least48dp and additional space for larger text. Phones/narrow or large-text windows scroll this workspace; wider windows use two columns only when the actions fit. Existing confirmations, Undo/Redo and save/export callbacks remain.
- PAD colors are opaque and pass primary-text contrast>=4.5:1 across32 bank/assignment/press/hover combinations. Pressed amber stays distinct from bank color, selection, and hover. Mini waveform and type/keyboard labels follow the readable foreground. Append BANK and unique address after the legacy description so128 PADs are distinguishable without breaking existing prefix locators.
- Step cells expose checkbox/checked state and a non-color underline for configured steps; larger numbers and focus indication retain exact step routing.
- Add the actual shared-UI offscreen screenshot/semantics test target and scan-gated Windows artifact collection, without changing permissions, pinned actions, signing or public release behavior.

## Prior CI blocker, not a new audio bug

PR96 Android run34493987568/job102927683374:331 app tests,3 failures in StartupProjectPreparationTest. XML shows coroutine debug added ` @coroutine#25` to the worker name and recovered copies of IllegalStateException/OutOfMemoryError. H13 was24/24 PASS in the same log. Tests now compare actual Thread objects and assert the exact original failure or a same-type/message recovered exception whose immediate cause is the original. Production startup, cancellation and exception policy are unchanged.

## Evidence available at this checkpoint

74 distinct selected Kotlin test bodies pass in both debug-on and debug-off JVMs. Includes9 text/geometry tests,3 palette tests covering32 combinations,6 PAD accessibility tests including all128 unique IDs, existing workflow/layout tests, and12 startup tests. This is a supplemental host runner with the actual production Kotlin and exact parent runtime JARs; JUnit API compatibility wrappers delegate assertions to kotlin.test. It is NOT normal JUnit discovery, supported Compose compilation, Android framework execution or physical device testing.

Python policy214:212 pass/2skip; public surface and diff checks run separately. Host environment has JDK21/Kotlin1.9, not the supported JDK17/Kotlin2.4.10. Normal Gradle stops before compilation on distribution DNS resolution; no Android SDK/ADB. Source-only UI type diagnostics cannot certify rendering.

New supported UI target: `./gradlew :desktop:desktopUiQualityTest`. It exercises actual Compose layout with synthetic PCM/state and a strict silent controller proxy; no native audio, provider, real files or screen-reader speech. Its screenshot output must be inspected only after the actual run succeeds. No generated mockup is accepted as implementation evidence.

## Required gates

Fresh-head shared/JVM/Android unit tests, H13, new UI review, Android lint/APK/API36 instrumentation, Windows packaging and iOS preview. Then physical phone and Windows input, typography, status dialog focus/dismissal, TalkBack/Narrator speech and audio/latency checks. No merge, release, tag or device install is performed.

## Primary guidance consulted

- https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- https://developer.android.com/develop/ui/compose/accessibility/semantics
- https://developer.android.com/develop/ui/compose/touch-input/focus/react-to-focus

Exa and Context7 were used to cross-check the existing library APIs. A48dp layout allocation is not interchangeable with overlapping expanded hit rectangles. No accessibility certification or whole-app performance improvement is claimed from these UI changes.
