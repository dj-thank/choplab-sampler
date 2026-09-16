# State-aware UI audit and refinement

## Purpose and user-visible outcome
User requested a thorough audit, including unnecessary UI. Review the actual four-stage deck and modal workflows, preserve production data, and implement actionable fixes.

## Current state
Root owns this checkout, branch codex/choplab-production-refinement-20260905, checkpoint e5aa359. Existing canonical dirty checkout remains untouched. Parent action scope: local UI/model fixes and validation, no publication/device actions. Rollback this milestone diff only; stop on data-loss or ownership conflict.

## Criteria
UX1: controls agree with loading/recording admission. UX2: destructive confirmation expires and is bound to affected content. UX3: controls remain reachable and minimum 48dp at compact/portrait viewports. UX4: Save prioritizes saving/export, avoiding redundant navigation and destructive editing.

## Directions
- Selected: state-aware UI simplification plus content-bound confirmation. Smallest proof: failing shared admission tests and 360x800 rendered Save buttons, then UI input tests.
- Deferred: full visual rebrand/typography overhaul; no evidence that replacing the design helps the core workflow.
- Rejected: adding more global toolbars; worsens the limited portrait space.

## Architecture and interfaces
Shared presentation policies mirror reducer admission. ConfirmActionButton is the sole two-click confirmation implementation. Save removes redundant BACK and moves A/B-all-clear into the A/B editor. Existing archive/audio schema unchanged.

## Milestones
1. Audit the four stages, arrangement/layer/pad controls and production gates.
2. Shared policy and confirmation repair, simplify Save layout.
3. Component input/screenshots at 360x800 and 1100x520; complete existing host/lint/package validation.

## Progress
- [x] Base and existing receipts pinned; read-only Luna audit started (model gpt-5.6-luna max, parent edge verified, runtime writable; no writes delegated).
- [x] Regression/prototype and implementation. Save baseline actual button height 37dp (RED), after 56dp; baseline screenshot retained in parent work/choplab-ui-audit-save-before-20260905.png.
- [x] Review and full validation: 777 standard + 29 UI/controller checks, failure/error/skip 0; full Gradle/lint/APK/Windows package and validate_project.sh PASS.

## Discoveries
Save currently divides 202dp into 6 rows, below 48dp per action. A/B copy uses independent non-expiring confirmation. Arrangement editEnabled ignores recording/loading. Selected pattern clear and Save all-clear keys can outlive changed content.

## Validation and limits
Pending. LOCAL_PASS target. Actual OS gestures, physical sound and Human acceptance remain unverified.

## Audit findings and dispositions
- UX3 / Save: 8 buttons across 6 undersized rows and tall counters. Selected fix: six actions in four 56dp rows, compact counters and scroll-safe centered form. BACK redundant with stage navigation removed; all-clear relocated to A/B.
- UX2 / confirmations: standalone copy confirmation had no timeout and content keys omitted changes. Shared timed confirmation now covers copy, pattern/PAD/all clears; selected-PAD key retains exact selection identity.
- UX1 / misleading controls: arrangement and pattern buttons ignored busy/recording state; no-op shifts and empty clears remained available. Shared state-derived presentation now disables these with guidance.
- UX1 / kit replacement: Android and Desktop could stop a drum loop before commitEdit rejected recording. Shared projectEditBlockedReason guards both entry points before sample production or playback effects; the shared reducer and UI use the same rule.
- UX1 / CHOP replacement: old source remains during load. Primary action now says loading and CHOP play/KEY/dock are unavailable until loading completes.
- UX4 / A/B footer: promised opening 16-step editor but only dismissed the modal. Removed duplicate misleading footer; existing header close remains.

## Review scope and evidence
Root inspected four workflow stages, arrangement/layer/PAD/play controls and shared/platform edit admission. Independent Luna UX audit returned four actionable findings plus overlapping Save evidence; root inspected and fixed each. The separate Luna Standards/correctness review identified busy-route inconsistencies and an async ownership race. Root fixed these; the final bounded ST1-004 source recheck passed with no new finding. ST2 confirmation ownership passed; ST3 render evidence is supplied by root. Runtime models and parent edges were read from session_meta/turn_context; actual sandbox is writable danger-full-access and agents were assigned no-write work, not sandbox-enforced read-only.

## Remaining limits
Not a repository-wide DSP/security audit. Only local/JVM UI input and host behavior are tested here. Physical touch/audio/recording, TalkBack speech, iOS runtime, provider and public release are not re-observed.

## Final standards pass follow-up
ST1 found that the general Android commitEdit accepted loading and that Capture/Beat/preset routes still disagreed with shared admission. The Android entry now rejects busy user edits, Capture START/RESET and callback are gated, Beat keeps safe QUICK/STEPS navigation while blocking action modals, and visible step/preset/PAD controls share admission. Root traced valid asynchronous completions before adding the guard: auto-transient analysis clears its loading barrier only after its existing revision/source check, and a current vocal-stop completion releases its recording state before committing the decoded take. This also repairs the prior STOPPING-state rejection of vocal take assignment. No Android ViewModel/device E2E claim is made from the shared-policy host test alone.

## Async ownership correction from final review
ST1-004 identified that source/revision equality alone does not prove ownership when a newer project load has started but not completed. autoChopTransient now joins the existing ProjectOperationEpoch: admission and epoch/loading publication occur synchronously, detection runs on Default, and success/discard/failure/cancellation cleanup all require completeIfCurrent on the ViewModel dispatcher. A stale analysis cannot release a newer loader's barrier or increment its revision. Existing epoch tests cover out-of-order completion and stale-failure loading preservation; final Android source is rebuilt separately after this late fix. No synthetic reproduction is represented as an Android ViewModel race test.

## Final visual readback
Root viewed the actual loading-BEAT image and aligned the remaining PLAY/REC/PAD-details/tempo enablement with the same busy policy. Stop transport and global ALL STOP remain available. Final source is frozen for the final combined Gradle/package/policy run.

## Calibration correction
A final rg usage check found autoChopTransient only at its Android declaration. ST1-004 is therefore retained as dormant internal-API hardening, not an observed current-UI defect. It remains correctly epoch-guarded, but is not evidence of a currently reachable user workflow. This distinction is included in the user report.

## Outcome
LOCAL_PASS. Final receipts and before/after images: outputs/ui-audit-refinement-20260905.md and .json. Two reviewer workstreams completed; first UX packet request was incomplete and rejected before the full contract was supplied, then actionable findings were accepted after root verification. Follow-up reviews closed identified issues; no broader no-bugs claim.
