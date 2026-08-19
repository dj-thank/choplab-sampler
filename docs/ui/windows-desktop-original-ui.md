# Windows desktop Android-parity UI

The Windows EXE uses the Android-origin `OtohiroiDeck` directly through
Compose Multiplatform. The four workflow stages, Japanese/English labels,
accessibility descriptions, colors, corner radii, pad grid, waveform, beat
editor, save screen and responsive layout policy are not retyped in a second
desktop UI.

The machine-readable contract is
[`android-parity-contract-v2.json`](android-parity-contract-v2.json). The
implementation source is
`shared/src/commonMain/kotlin/com/choplab/sampler/ui/OtohiroiDeck.kt`.

The Windows adapter currently provides local WAV decode/playback and the shared
sampler editing state. Recording, project archive I/O, WASAPI loopback and
Spotify provider work are explicit adapter milestones; they must not be
represented as completed merely because the shared button is visible.
