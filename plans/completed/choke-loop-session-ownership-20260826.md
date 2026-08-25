# Choke loop-session ownership

## Objective

When a PAD trigger uses the same nonzero choke group as the active Beat-loop owner, stop the complete owned loop session—vocal companions, owner audio and published loop state—before starting the requested PAD. Unrelated groups and normal different-PAD polyphony must remain available.

## Exact boundary

- Root: `work/choplab-goal-ux-20260826`, branch `codex/choplab-goal-ux-20260826`.
- Base: `639d5132c12bd3efe0d0346731cef9fbdaca15ec`, tree `4ee030eaddfc30d1727e09851092dac10e819270`.
- Product commits: `79977de5bd955c867ed122b45017b25b1a2ae9cc` and follow-up `1853659ef56d40117e9f61d1c7f01a752ed02f33`, tree `e07a07c46500f14aaa09619f4463682a07890eef`.
- Decision receipt: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE5_20260826.md`.
- Gate: `LOCAL_PASS`; no schema/project/audio bytes, callback, Pixel/ADB, provider/public/signing or Human action.

## Closed defects and contract

1. A shared pure policy computes a stop plan only when requested PAD and loop owner are different assigned PADs in the same nonzero CHOKE group.
2. The plan includes every actually started vocal companion and the owner exactly once.
3. Android and Windows issue those stops before the requested trigger and clear loop/playhead runtime truth.
4. Desktop stop failure keeps published loop truth and rejects the requested trigger.
5. Group 0, different groups, same owner, invalid PADs and ordinary different-PAD polyphony remain unchanged.
6. A vocal companion in the owner's same nonzero group is excluded at loop start; otherwise it would immediately silence the owner. Group 0 and other groups remain layers.

## TDD and validation

- RED: Desktop controller retained loop state after a matching CHOKE trigger.
- Shared controls: matching plan, owner-wins companion group, group-zero/different-group/same-owner/invalid paths.
- Desktop controls: owner-only, exact vocal companion set, unrelated polyphony, requested trigger and stop failure.
- Full gate: 190 tasks PASS. Android 248, shared Android/Desktop 40/40, JVM-core 54, Desktop 84; 466 total, failures/errors/skips 0. Lint errors 0/warnings 7.
- Configured validation, Python policy 40, public-surface 416, Android three APKs, Windows app-image, CycloneDX and `git diff --check` PASS.
- Local parent Standards/Spec findings: 0/0. Review notes: parent PAD `work/CHOPLAB_CHOKE_LOOP_SESSION_EVIDENCE_20260826/`.

## Rollback and remaining gates

The two migration-free product commits can be reverted ordinarily. Physical fade/click behavior, listening quality, latency, device/provider/public and `HUMAN_GO` remain outside this local ownership proof.
