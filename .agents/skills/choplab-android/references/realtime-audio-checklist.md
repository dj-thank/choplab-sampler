# Real-time audio review checklist

- Audio callback has no heap allocation.
- Audio callback has no blocking mutex or condition wait.
- Audio callback has no file/network I/O.
- Audio callback has no log loop or UI interaction.
- Command queues are bounded and overflow behavior is defined.
- Sample buffers remain alive while voices reference them.
- Stream disconnect/restart is handled.
- Sample-rate conversion assumptions are explicit.
- Stereo interleaving and pan law are tested.
- Denormals, NaN/Inf, clipping, and silence are handled.
- Voice stealing and choke behavior are deterministic.
- Offline render and real-time playback share or compare DSP behavior.
