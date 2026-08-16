# ChopLab mobile UI local audit — 2026-08-16

## Completed locally

- Source and editor waveforms share pure frame mapping, zoom, and pan policy.
- Pinch zoom preserves the focus frame and clamps at the source edges.
- Two-finger horizontal pan is supported while zoomed.
- A bottom overview track shows the current viewport within the whole source.
- TalkBack exposes previous range, next range, and reset-to-full-range actions.
- Selection and chop marker gesture targets are 48 dp wide; their visual lines remain narrow.
- Range start/end crossing is prevented by `SamplerViewModel` using the minimum slice duration.
- STOP/BACK routing remains centralized through workflow navigation; destructive clear actions retain confirmation or edit-history recovery.
- Backup and device-transfer extraction explicitly exclude all app storage domains.

## Contract evidence

- `WaveformViewportPolicyTest`: whole-source zoom-out, source-edge focus, zero-width input,
  deterministic frame rounding, finite invalid inputs, and clamped panning.
- Clean host gate: 220 tests, failures 0, errors 0, skipped 0.
- Android lint: errors 0; the backup extraction warning was fixed. Remaining warnings are toolchain/dependency freshness,
  three existing Compose modifier-order advisories, and one adaptive-orientation advisory.
- Debug and unsigned release APK assembly passed.

## Artifact ledger

- `outputs/ChopLab-v0.13.1-mobile-waveform-local-debug.apk`
  - package `com.choplab.sampler`; version `0.13.1` (`21`)
  - SHA-256 `A257E9FBF654E7E4A265C9AAA4FF82447E20F0528474ED93BFD2352A1E588D00`
  - debug signer SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- `outputs/ChopLab-v0.13.1-mobile-waveform-local-release-unsigned.apk`
  - SHA-256 `9E0C7F71621BA096BCB13DD05F8B114EA4D7FDE6C8427DB7DBC1900CFA6EA275`
  - unsigned; not install/release ready

## Evidence boundary

This is LOCAL_PASS only. Portrait fit, one-hand reach, multi-touch feel, TalkBack behavior,
rotation/process recreation, retained app data, and audio ownership require the released Pixel 9a.
Do not install until serial, installed package/version/signer, and APK signer are reconciled.
