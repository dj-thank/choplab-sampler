# Simple Chop and project isolation

## Outcome

Make the main workflow understandable without music-production knowledge: load one source, play from the beginning or a tapped waveform position, capture chops into empty PADs, loop or arrange them, and reach drums/voice and Scratch directly.

## Required behavior

- A different imported source starts a separate project and cannot expose old PADs, steps, slice markers, loop/scratch references, or edit history.
- Entering Chop defaults to A Melody; A/B/C/D and both 16-PAD pages remain one-tap switches.
- Existing assigned PADs play; only empty PADs capture a new live chop.
- Long-pressing an assigned PAD opens start/end trim with independent controls and preview.
- Source pitch changes remain available and audible during playback.
- Scratch selects Source or PAD and exposes gesture sensitivity with visible active feedback.
- The fixed console remains free of scroll containers.

## Validation

- [x] Project replacement and reset host tests
- [x] PAD routing and trim host tests
- [x] 103-test full suite
- [x] Android Lint and APK assemble
- [x] Offline project validation and no-scroll scan
- [x] Data-preserving final APK install on Pixel 9a
- [ ] Physical destructive replacement/reset test, intentionally skipped to preserve the user's project
- [ ] Physical subjective audio, scratch latency, and long-press trim audition
- [ ] Provider CI, public GitHub artifact, and release identity

## Evidence

Final local APK: `outputs/ChopLab-v0.10.0-preview.1-local-debug.apk`, versionCode 14 / versionName 0.10.0, 30,641,099 bytes, SHA-256 `2AD63450619685094DBFAB4B5E49E10AD4A51432181995767091023F8AF28E9C`.
