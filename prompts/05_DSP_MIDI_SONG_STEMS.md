Continue the active ExecPlan with advanced DSP, MIDI, Pattern/Song and stems.

- Implement independent pitch/time stretch with documented real-time quality limits and tests.
- Add ADSR, LFO waveforms/targets/sync, filter/resonance, per-pad insert effects, sends and stable master processing.
- Ensure real-time/offline equivalence through shared code or numerical/audio fixture comparisons.
- Add multiple patterns with 16/32/64 steps and deterministic Song expansion.
- Implement Android MIDI note/velocity, CC learn, clock and transport with device disconnect/reconnect safety.
- Implement stereo master and selected bank/pad stem exports with documented master-FX/send policy, progress, cancellation and resource bounds.
- Add tests for DSP stability, MIDI parser/clock, sequence compilation and stem contents.

Use focused subagents, serialize writes, run full verification, review with `qa_reviewer`, update docs/plan and commit.
