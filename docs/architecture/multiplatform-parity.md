# Multiplatform parity architecture

## Decision

Android's `OtohiroiDeck` is the single presentation source of truth. Windows and
iPhone use the same Compose Multiplatform deck and the same sampler domain model;
only platform capabilities are supplied through adapters.

The previous Swing `DesktopDeckModel` and its five-stage presentation were
parallel shallow models. They are retired rather than maintained as a second
source of truth.

## Deep modules and seams

- `shared/src/commonMain/kotlin/com/choplab/sampler/model`: immutable sampler
  state, pad editing, pattern policy, project limits and exact workflow policy.
- `shared/src/commonMain/kotlin/com/choplab/sampler/ui`: the Android-origin
  `OtohiroiDeck` and all deck subcomponents, including copy, semantics, density
  and landscape/portrait layout rules.
- `SamplerDeckController`: the narrow UI interface. It exposes sampler commands,
  not platform APIs, so the UI cannot accidentally acquire Android or JVM code.
- `jvm-core`: one Android/Windows implementation of the bounded `.choplab`
  archive, three-generation autosave, PCM-16 WAV writer, four-bar renderer,
  PAD voice rendering, and playback cursor rules. Platform shells do not fork
  these formats or offline DSP rules.
- Android `SamplerViewModel`: existing Android audio/recording/persistence
  adapter implementing the shared controller.
- Windows `DesktopSamplerController`: the lifecycle integrator for Java Sound
  source/PAD voices, 16-step transport, scratch stream, microphone/driver
  loopback capture, shared persistence, edit history, autosave, export, and the
  provider menu.
- iOS: `ChopLabShared` framework target is reserved for the same shared deck and
  controller seam; AVAudioSession remains a platform adapter.

## Invariants

1. Workflow stage count and labels are four: `入れる / チョップ / ビート / 保存`.
2. User-visible copy comes from shared UI/policy source; platform shells do not
   translate or rename labels.
3. A platform implementation may be incomplete only behind an explicit adapter
   status, never by silently changing shared UI behavior.
4. Spotify OAuth/API is a provider adapter for metadata/control. It must not be
   used to bypass Spotify's protected audio delivery or create MP3 downloads.

## Verification boundary

The current migration has `LOCAL_PASS` for the UI contract, shared JVM tests,
desktop tests, Android regression tests/Lint/APK, Windows app-image packaging,
and a responding packaged process. A complete 200% DPI empty-state capture
shows all four stages and all three capture choices. Actual driver loopback,
microphone quality/latency, Spotify account authorization, physical iPhone,
signed installers, public release, loaded-state visual comparison and Human
audio/visual approval remain separate gates.
