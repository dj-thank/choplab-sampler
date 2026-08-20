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

The Windows adapter connects local WAV decode, source/PAD playback, microphone
and driver-exposed playback-loopback recording, transport, scratch, project
archive/autosave, Undo/Redo, export and Spotify metadata/control. The provider
menu is native platform chrome and does not modify the shared deck. Device and
provider success must still be evidenced separately from a visible button.
