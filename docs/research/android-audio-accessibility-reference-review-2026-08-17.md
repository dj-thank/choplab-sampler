# Android audio and accessibility reference review — 2026-08-17

## Scope and evidence boundary

This review compares ChopLab with primary Android guidance and six shallow, read-only reference checkouts. It is a design and test-evidence review, not permission to copy third-party code or licenses. No reference repository is part of the app build, and no conclusion below promotes emulator evidence to physical `DEVICE_PASS` or `HUMAN_GO`.

## Primary sources

- Android low-latency audio checklist: <https://developer.android.com/games/sdk/oboe/low-latency-audio>
- Oboe repository and full guide: <https://github.com/google/oboe> and <https://github.com/google/oboe/blob/master/docs/FullGuide.md>
- Oboe live-effect sample: <https://github.com/google/oboe/blob/main/samples/LiveEffect/README.md>
- Compose accessibility semantics: <https://developer.android.com/develop/ui/compose/accessibility/semantics>
- Compose accessibility API defaults: <https://developer.android.com/develop/ui/compose/accessibility/api-defaults>
- Android 48 dp accessibility testing guidance: <https://developer.android.com/codelabs/basic-android-kotlin-compose-test-accessibility>
- MediaProjection lifecycle and `onStop()` recovery: <https://developer.android.com/media/grow/media-projection>
- `AudioRecord` blocking-read contract: <https://developer.android.com/reference/android/media/AudioRecord>
- Foreground-service type requirements: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- AndroidX Compose component API guidelines: <https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-component-api-guidelines.md>

## Reference snapshots

The checkouts live only under the ignored/untracked review workspace `work/reference-repos-20260817/`.

| Repository | Fixed revision | Review use |
|---|---|---|
| `google/oboe` | `2a45aa2d9e94d209c4636eec4014dd83cda110f4` | stream configuration, callbacks, disconnect and xrun guidance |
| `android/platform-samples` | `f751f682aa96a061a39ed4399c697ba513ac93d6` | current framework sample structure and lifecycle examples |
| `android/nowinandroid` | `7d45eae4f8720a0c77f507712ba2437ff974b6ed` | deterministic test layering and modular Android conventions |
| `google/Accessibility-Test-Framework-for-Android` | `c65cab02b2a845c29c3da100d6adefd345a144e3` | automated accessibility issue detection boundaries |
| `android/compose-samples` | `018c5207fb63c4f78e5841bd8ddd4faabdf19d3a` | Compose semantics and adaptive-layout patterns |
| `androidx/media` | `2bc207851df311340767e913931ca7b28cab1794` | media lifecycle, focus, and test organization |

## Findings applied to ChopLab

### Audio engine

`SamplerEngine` already selects the platform native output rate, requests `USAGE_GAME`, uses `PERFORMANCE_MODE_LOW_LATENCY`, reuses one streaming `AudioTrack`, and mixes preallocated voices before the blocking write. Those choices align with a substantial part of Android's low-latency checklist. A wholesale Java-to-Oboe migration is therefore not treated as an automatic quality improvement. It remains a separate architecture experiment that must compare measured callback timing, xruns, route changes, and audible behavior on physical hardware before adoption.

The immediate safe improvements are deterministic lifecycle/queue/filter tests, bounded teardown, source-bound build evidence, and operational diagnostics. Physical buffer tuning, exclusive-mode behavior, Bluetooth/wired route loss, sustained xrun pressure, and subjective latency remain `DEVICE` or `HUMAN_GO` work.

### Accessibility and adaptive UI

Compose semantics, custom actions, state descriptions, traversal grouping, and 48 dp target geometry can be checked deterministically with in-memory fixtures. Accessibility Test Framework and framework-node inspection add useful defect and action-exposure checks. They still do not prove TalkBack TTS wording, a person's swipe order, one-hand comfort, or physical touch feel.

The dedicated Google Play API 36 AVD is therefore used for `COMPOSE_INSTRUMENTATION` and `FRAMEWORK_NODE`, including portrait, landscape, and 1.0/1.3/2.0 font scales. Physical Pixel and `HUMAN_GO` stay separate.

### Capture lifecycle

MediaProjection and blocking `AudioRecord` calls require explicit cancellation, generation ownership, and bounded teardown. ChopLab's lifecycle fakes cover the deterministic state-machine portion; a real projection revocation, blocking device driver, focus/route interruption, and capture quality cannot be certified by the AVD.

## AVD reliability diagnosis

The first headless Google Play API 36 boot produced framework startup and Bluetooth-process instability under heavy I/O. The same deterministic four-test waveform suite then passed repeatedly after increasing emulator memory to 4096 MiB and launching with `-feature -BluetoothEmulation`. This is captured as a reproducibility workaround in the pinned config and launch script; it is not an app bug or physical Bluetooth result.

The AVD runner is intentionally fail closed: it accepts only an explicit `emulator-*` serial, checks API/image/locale, installs app and test APKs only on that emulator, runs the font/orientation matrix, scans bounded logs, restores settings, and never claims physical-device or human evidence.

## Deferred decisions

- Do not migrate to Oboe without an isolated prototype and physical benchmark.
- Do not treat AVD microphone or framework semantics as real microphone or TalkBack speech.
- Do not vendor or depend on the cloned repositories without a separate license and dependency decision.
- Add physical xrun/latency, wired/Bluetooth route-loss, call/focus contention, TalkBack wording, one-hand comfort, and subjective audio to their existing `DEVICE`/`HUMAN_GO` acceptance lanes.
