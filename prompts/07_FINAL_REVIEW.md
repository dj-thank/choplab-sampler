Perform a final evidence-based review of the current branch against `AGENTS.md`, the active ExecPlan and `docs/DEFINITION_OF_DONE.md`.

Delegate independent read-only reviews for:

- Android lifecycle/permissions/state;
- native audio/JNI/real-time safety;
- persistence/security/data-loss risk;
- DSP/render/MIDI/sequence correctness;
- build/test/release/documentation truthfulness.

Reproduce and fix material findings. Run the complete verification gate. Do not downgrade or suppress checks merely to pass. Move the finished ExecPlan to `plans/completed/` only if its acceptance evidence is satisfied. Produce a final report listing exact commands, results, APK/checksum if present, device evidence, unresolved risks and unverified physical-device tests.
