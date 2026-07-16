Continue the active ExecPlan with the native Oboe milestone.

Use `audio_dsp_engineer` for C++/DSP implementation and `build_engineer` for Gradle/NDK/CMake/Oboe configuration, but serialize shared-file edits.

- Add complete native build plumbing and locked dependency versions verified against primary sources.
- Reconstruct the missing `SamplerCore.h` and Kotlin `NativeSamplerEngine` from the JNI contract and current domain model.
- Implement deterministic create/start/stop/destroy and stream-disconnect recovery.
- Prove a minimal sample playback path, then bounded commands, sample lifetime, pad parameters, voice stealing/choke, transport and diagnostics.
- Add host/native tests and Kotlin boundary tests.
- Enforce no allocation/blocking/file I/O/heavy JNI in the callback.
- Keep the legacy engine available until functional parity is tested; provide a debug switch if useful.

Run native builds for configured ABIs, unit tests, offline validation, lint and assembleDebug. Update docs/plan, obtain `qa_reviewer` findings, fix material issues and commit.
