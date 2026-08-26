# CHOKE live/export loop-session parity

## Objective

Make offline WAV export select the same Beat-loop owner and eligible vocal companions as live loop start. A VOCAL in the owner's same nonzero CHOKE group must not silence the loop only in export; CHOKE OFF and other-group vocals remain intentional layers.

## Exact boundary

- Root / branch: `work/choplab-choke-export-parity-20260826` / `codex/choplab-choke-export-parity-20260826`.
- Base: integrated wave-5 closeout `611a58932fff6faf4ae6178acdc1f7c575cb0a7b`, tree `0c3e15f75a81d0f1b78e19d7a316266de86b04c0`.
- Product commit: `b445c18a6bb50abfd878f95a2d2e6c3397cb3222`, tree `a650dae4c5bc7a18d321c303fdcad8c268f2888e`.
- Portfolio/review: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE6_20260826.md` and `work/PAD_CHOPLAB_GOAL_WAVE6_REVIEW_20260826.md`.
- Gate: `LOCAL_PASS`; project/schema/autosave, realtime callback, Java Sound/physical fade, UI, ADB/device, provider/public, signing/secret and Human actions were excluded.

## Closed defect and contract

1. Offline frame-zero event selection now reuses the shared live loop-companion policy when exactly one assigned loop owner exists.
2. A vocal in the owner's same nonzero CHOKE group is excluded, so export cannot silence only the offline owner.
3. CHOKE OFF and other-group vocals remain intentional layers.
4. No-loop and multiple-assigned-loop legacy/ambiguous inputs keep all non-loop vocals; the renderer does not invent an owner.
5. Android realtime engine/callback, Desktop Java Sound, project/archive/autosave, schema and UI are unchanged.

## TDD, challenge and validation

- RED full-bar oracle: maximum delta `9,262` PCM units at frame `49` (`offline=7,121`, `realtime=-2,141`).
- GREEN: same-group owner-only and other-group-layer output match Android realtime `SamplerEngine.Voice` plus the shared limiter within `<=1` for every frame.
- Negative controls: CHOKE OFF, other group, no-loop and multiple-loop. Existing step/retrigger/polyphony/loop/vocal/final-sample/non-finite suites remain green.
- Full gate: 190 tasks, exit `0`. Android 250, shared Android/Desktop 40/40, JVM-core 55, Desktop 84; 469 tests / 87 suites, failures/errors/skips 0. Lint errors 0/warnings 7.
- Product-checkpoint configured validation, Python policy 40 and public-surface 417, three APKs, Windows app-image/ProductVersion `0.17.0`, CycloneDX 650/651 and `git diff --check` PASS. The closeout-only tree passes Python policy 40, public-surface 418 and `git diff --check` without rebuilding product bytes.
- Local parent Standards/Spec unresolved: 0/0. Artifact bytes and test XML were read back after interruption without rerunning the unchanged high-cost gate.

## Rollback and remaining gates

The migration-free product commit can be reverted ordinarily. Physical Android/Windows audio, click/pop and latency, route/device behavior, provider/public and `HUMAN_GO` remain outside this local parity proof.
