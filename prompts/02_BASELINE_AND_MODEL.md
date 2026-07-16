Read the active ExecPlan and continue from the verified baseline. Complete the baseline/domain-model milestone only.

- Make the MVP Gradle build and tests reproducible.
- Add characterization tests for current chop ranges, auto-next assignment, pad parameters, sequencing and WAV export.
- Introduce a versioned, stereo-capable project model and pure transformation APIs without breaking the current UI.
- Define engine and renderer interfaces that allow temporary legacy/native coexistence.
- Add migration/adaptation from the current MVP model.
- Keep memory/data bounds explicit.

Run unit tests, offline validation, lint and assembleDebug. Update the ExecPlan and project-state docs. Use `qa_reviewer`, fix material findings, and commit a stable checkpoint. Do not implement the full Oboe engine in this task.
