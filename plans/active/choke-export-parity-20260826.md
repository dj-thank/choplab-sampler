# CHOKE live/export loop-session parity

## Objective

Make offline WAV export select the same Beat-loop owner and eligible vocal companions as live loop start. A VOCAL in the owner's same nonzero CHOKE group must not silence the loop only in export; CHOKE OFF and other-group vocals must remain intentional layers.

## Exact boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-choke-export-parity-20260826` / current root task
- Base: integrated goal closeout `611a58932fff6faf4ae6178acdc1f7c575cb0a7b`, tree `0c3e15f75a81d0f1b78e19d7a316266de86b04c0`
- Portfolio: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE6_20260826.md`
- Files: offline `PatternRenderer`, full-bar parity/renderer tests and revision-bound docs
- Target gate: `LOCAL_PASS`
- Excluded: project/schema/autosave, realtime callback, Java Sound/physical fade, UI, ADB/device, provider/public, signing/secret and Human actions

## Current defect

Live loop start uses `vocalCompanionPadIndicesForLoopStart`, which excludes a VOCAL in the owner's same nonzero CHOKE group. `PatternRenderer` separately emits every non-loop VOCAL at frame 0 after the loop owner; its choke release therefore silences the owner only in the exported WAV.

## Delivery contract

1. Full-bar expected PCM is generated from Android realtime `Voice` instances selected by the shared live ownership policy and passed through the shared master limiter.
2. Same-group VOCAL is excluded and offline output matches the owner-only realtime session with max PCM delta ≤1.
3. CHOKE OFF and other-group VOCAL remain layers and match the same realtime oracle.
4. No-loop export retains all vocals. Multiple-loop ambiguous/legacy input does not silently choose one owner and drop vocals.
5. Existing step timing, retrigger, polyphony, loop, vocal, full-bar final-sample and resource bounds remain green.

## Stop / rollback

Stop if the repair changes project bytes/schema, drops an eligible vocal, touches callback code, introduces nondeterminism or requires a physical claim. Roll back one migration-free renderer/test commit.
