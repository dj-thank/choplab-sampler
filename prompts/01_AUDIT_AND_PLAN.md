Read `AGENTS.md`, `.agent/PLANS.md`, `docs/PROJECT_STATE.md`, `docs/PRO_REFERENCE_GAPS.md`, and `docs/DEFINITION_OF_DONE.md`. Audit the root MVP and all files under `reference/pro-v0.2/`.

Run the offline validation and the smallest Gradle tasks available. Create `plans/active/choplab-pro-integration.md` with:

- actual build baseline and exact failures;
- execution-path map from UI to ViewModel to capture/playback/export;
- inventory of Pro reference interfaces and missing dependencies;
- proposed stereo-capable domain model;
- native/JNI thread and ownership design;
- persistence schema/migration strategy;
- ordered compiling milestones with tests and rollback points.

Delegate independent read-only audits to `android_architect`, `audio_dsp_engineer`, and `build_engineer`, wait for them, reconcile disagreements, and have `qa_reviewer` critique the plan. Do not start broad feature implementation yet, but fix a trivial environment or baseline build defect if it is required to establish the baseline. Commit the plan and baseline fixes.
