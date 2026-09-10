# UI clarity and responsive control quality

## Purpose and user-visible outcome

Improve the existing おとひろい UI rather than replacing its product identity: more readable actions, distinct selected/pressed/focused states, unique BANK/PAD descriptions, safe save-screen targets, and full status details. Keep the four stages and 128 PADs.

## Current state

Exact source baseline: PR96 `3fe5906bbc29e54bb46689606ffb0cac3599e1ca`, tree `28a68a190fcfc3ef01acdae4cef0aac58ef31cf2`. Its Windows/iOS/policy workflows succeeded. Android run `34493987568` failed three StartupProjectPreparationTest assertions; H13 passed 24/24 in that run. The assertions assumed undecorated thread names and exact exception objects across coroutine debug stacktrace recovery. The current turn reproduces the source boundary and verifies both debug configurations through a supplemental host runner.

## Constraints and invariants

No audio DSP/PCM/gain, recording behavior, project schema/history/autosave, dependency, signing, release or merge changes. Preserve low-latency PAD pointer ownership and H13 waveform-range semantics. Quick performance surfaces remain non-scrollable except existing large-text policy; the SAVE screen may scroll. Keep the cream/charcoal/orange/green identity. No external assets or real user recordings.

## Architecture and interfaces

Extract existing shared control signatures into `DeckControlComponents.kt`; `OtohiroiDeck.kt` keeps navigation/routing and original controls' callbacks. `DeckControlPresentation.kt` holds pure text/geometry/accessibility policies. `FinishWorkspace.kt` reserves five real action rows. `HeaderStatusControl.kt` is a read-only details dialog. `BankVisuals.kt` owns opaque PAD palettes; PAD gestures and the unchanged voice ownership path remain in `PadGrid.kt`.

The dedicated `:desktop:desktopUiQualityTest` uses actual shared Compose UI, synthetic in-memory state and a strict silent controller proxy. PNG/semantics output is a component rendering, not a phone screenshot or audio test. Windows CI adds the test and scan-gated evidence upload with unchanged read-only permissions, pinned actions and release pipeline.

## Milestones

1. Establish exact baseline and inspect current CI/log/XML before changing production.
2. Fix controls/labels/contrast and bound SAVE action heights; retain existing navigation and destructive confirmation.
3. Add pure regressions, confirm startup tests in debug on/off, compile and render the actual UI in supported CI.
4. Inspect rendered screenshots, fix any real layout regression, publish exact source and evidence in Draft only.

## Progress

- [x] Reconstructed baseline Git tree exactly; ran doctor/validator and attempted normal Gradle.
- [x] Implemented common UI controls, PAD contrast/identity, semantic step toggles and responsive SAVE layout.
- [x] Supplemental host: 74 selected test bodies passed with coroutine debug on and off; not 148 distinct tests.
- [x] Python policy214:212 pass/2skip; public scan passed before documentation finalization.
- [ ] Supported fresh-head compilation/unit/H13/UI review and screenshot inspection.
- [ ] Exact-tree GitHub publication/read-back and final independent review.
- [ ] Physical phone/desktop input, accessibility speech and audio acceptance.

## Discoveries and decisions

- Existing SAVE actions used five weighted rows inside202/238dp, below48dp before spacing. The new layout budgets each row and scrolls rather than shrinking.
- Existing PAD pressed text was dark on bank accent; translucent assigned surfaces depended on the underlying panel. Opaque palettes pass32 combinations at >=4.5:1 for the primary text; press uses bright amber and dark ink.
- Buttons used8sp compact text. New policy keeps10sp primary compact/12sp regular and drops only redundant English captions when the smallest row budget cannot fit them at the selected system font scale. Numeric/Japanese state is retained.
- Avoided BoxWithConstraints per button: presentation is remembered by label/compact/font scale. Only the header and SAVE viewport need adaptive layout constraints.
- Keep all legacy PAD description prefixes, adding unique BANK/address suffixes. Do not weaken H13 or recording semantics.
- CI test repair asserts the actual worker Thread instance and accepts only the original exception or a same-type/message recovery copy directly caused by it. No production error-catching policy changed.

## Validation log

Local JDK21/Kotlin1.9, exact PR96 runtime JARs. Pure/model/contrast/startup test bodies use a documented JUnit-API adapter delegating to kotlin.test, not a JUnit engine. Actual selected production sources are compiled; the PAD description declarations are extracted verbatim from their Compose file. `doctor.sh`: no SDK/ADB. `validate_project.sh`: unchanged Kotlin2.4 EditHistory.addLast cannot compile with local1.9. Gradle stops downloading its distribution at DNS resolution. Supplemental UI type diagnostics are not a supported Compose compiler run.

## Risks and rollback

UI typography can expose previously hidden clipping; screenshots and 320/360/412/1280dp plus font100/130/200% test coverage are required. Status dialog dismissal/focus needs platform verification; do not infer it from offscreen captures. Revert this UI follow-up relative to PR96 without touching data or prior optimization commits. No force pushes, clean/reset, or native user dialogs in tests.

## Remaining device validation

Pixel portrait/landscape/split-window, 100/130/200% fonts, save scrolling, all128 PAD pages, pointer timing/long-press/GATE, keyboard Tab/Enter focus, status dialog read/dismiss/back, TalkBack/Narrator speech, launch/first PAD, recording/playback/routes. No physical evidence is claimed by this plan.
