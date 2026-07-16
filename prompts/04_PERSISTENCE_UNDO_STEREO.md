Continue the active ExecPlan with stereo, project persistence and Undo/Redo.

- Preserve L/R identity from decode/record through waveform, pad playback and offline output.
- Implement a versioned `.choplab` package and missing WAV codec using safe bounded reads/writes.
- Prevent ZIP traversal, decompression bombs, duplicate IDs and oversized frame/asset totals.
- Use atomic/recoverable save semantics and autosave debounce without losing the last valid project.
- Implement bounded Undo/Redo with coalescing for sliders and shared immutable audio buffers.
- Wire open/save/autosave/recovery/history to ViewModel and UI with lifecycle-safe error handling.
- Add round-trip, migration, corruption, truncation, oversized input, atomic-save and history tests.

Run all relevant tests, offline validation, lint and assembleDebug. Update the feature matrix only for verified behavior. Review with `qa_reviewer`, fix material findings and commit.
