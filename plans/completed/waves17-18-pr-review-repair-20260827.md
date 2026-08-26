# Close PR #79 loop-admission review gaps without weakening prior playback

## Purpose and user-visible outcome

Keep current transport audible while a new Windows Beat-loop candidate is rendered, opened and started; publish the loop only after one race-safe handoff; never lose ownership of a candidate whose cleanup fails. On Android, a vocal take started just before focus or loop-session admission fails must be stopped and discarded, never decoded or saved into BANK D.

## Exact boundary

- Input: Wave 18 clean closeout `fa39c476df239a2c84ec7c9149c69ce70ba9d608`, tree `54a57715ba08b3f528cd1c10f48067bcba1663d1`.
- Product anchor: `11d01272758eda774852ea2af1e55fe9d3e5c3b4`, tree `83c205536006518aab5da4d3c33e02500f84c2dd`.
- Repository/branch: `dj-thank/choplab-sampler` / existing `codex/choplab-wave18-android-loop-admission-20260827`, updating existing PR #79 only.
- Gate ceiling: local source/package evidence only. GitHub review/check/merge, device, signing, tag/Release, OAuth/provider and Human are separate.

## Constraints and invariants

- Candidate PCM render, `Clip.open` and start must not hold `DesktopSamplerController.playbackTransitionLock`.
- Prior transport/source/PAD playback remains owned on recoverable preparation/start failure.
- Started candidates cannot be retired by a late transport hit before handoff; every active late hit is retired at handoff.
- A `Clip.close()` failure keeps the exact candidate in an engine-owned set and propagates outside the recoverable startup contract.
- Android rejection cleanup may stop and delete only app-owned capture files. It exposes no decoder, PAD assignment, autosave or project-save callback.
- No secret, signer identity, user audio, provider receipt or generated binary becomes tracked/public.

## Architecture

- `DesktopSamplerAudioEngine` returns a prepared session, then a started session. The controller performs both potentially slow phases before entering its short retire/commit boundary.
- `JavaSoundWavPlayer` owns opened clips as pending, started loop-session clips as staged candidates, and committed clips as active voices. Transport conflict selection sees only active voices; `stopAll()` owns all three sets.
- `discardVocalTakeAfterLoopAdmissionFailure` stops the recorder, deduplicates the returned/requested path, and calls only the bounded app-owned delete port. `SamplerViewModel` uses it for both focus and loop-session rejection.

## Milestones and progress

- [x] RED: slow failed controller startup stopped transport progress; Android cleanup helper was missing; failed candidate close lost ownership.
- [x] GREEN: split prepare/start/retire, add discard-only Android cleanup, retain cleanup ownership.
- [x] Adversarial pass: a late same-PAD hit superseded a started candidate, and candidate start held the Java Sound monitor. Both were reproduced then fixed with separately staged candidates and start outside the engine monitor.
- [x] Run two parent review passes, full gate, lint/policy/package/artifact read-back and repo SSOT closeout.

## Review

### Standards

- Module ownership remains Android ViewModel/recorder cleanup versus Desktop controller/Java Sound adapter; shared DSP and Android realtime callback code are unchanged.
- Every candidate lifecycle has an explicit pending/staged/active owner. Close failure removes no ownership, and aggregate cleanup retains all failures.
- Slow operations are outside the controller handoff lock; tests cover actual Java Sound monitor concurrency, not only a fake port.
- Unresolved findings: `0`.

### Spec

- Original review P2: failed slow startup preserves project/transport and does not suppress transport hits.
- Original review P2: Android focus/session rejection stops and deletes the take without decode/save.
- Original review P1: cleanup-close failure is non-recoverable and remains reachable by `stopAll()`.
- Additional controls: late same-PAD transport hit is retired while the staged loop survives; preparation failure closes earlier candidates; retirement failure propagates unchanged.
- Unresolved findings: `0`.

## Validation

- Clean full gate: 197 tasks (191 executed / 6 up-to-date), `BUILD SUCCESSFUL in 4m38s`.
- XML: Android 284 / 50 suites; shared Android 86 / 17; shared Desktop 86 / 17; JVM 88 / 9; Desktop 165 / 24; total 709 / 117, zero failure/error/skip.
- Lint debug/release fatal/error 0, warning 4 each. Python policy 64/64; public current/history 464 each; configured validator 18 tasks plus XML/mode/wrapper/UTF-8.
- Android unsigned positive exit 0 (`0.17.0`, code 27, `manifest_tool=aapt2`); signed-required negative exit 1. Windows ProductVersion `0.17.0`; CycloneDX 1.6 components/dependencies 650/651.
- Artifact hashes: debug APK `28317CE1547063E5F4BAC84711E58C0BA282569861874E65FFD415B2B07E389D`; androidTest `F8AC9B2C1FC97672FCFB8565127D6099D80E906F49F623BE334C61AF102FE622`; unsigned release `A9A70634E9587F8602390B26A0E3D2E36A8177E088788B4428FFFDE94210D53E`; Desktop JAR `B4D2B1D79338FC5E6D98C317CB8690C431DC555AB3920E910FD147C139A6943B`; Windows manifest `2D32051661ADF24F1DDA5FFD440A8A2914CD0DFD58F385A6C6389A2BE82934F7`; SBOM JSON `578DF19D4737D11FD57ABCE06076D0B579EC00E498664D1AACEB4D718D09FED9`, XML `31A3FEC6D5F7BDB15A74FA80741F3740960FF83ED942034003EBD08B85AD667A`.

## Risks, rollback and remaining gates

- Brief candidate/prior overlap is intentional until handoff; physical click, overlap length and endpoint behavior need bounded Windows audio/Human evidence.
- Android helper tests prove ownership and absence of a save callback at the seam; physical recorder/focus timing remains a device gate.
- Rollback is the single product commit on the isolated branch. Canonical dirty checkout and unrelated worktrees remain untouched.
- Existing PR #79 must be updated without force. Exact-head hosted checks and review threads must be read back before normal merge; merged-main checks follow. No tag or Release is part of this plan.
