# Android Pixel 9a update receipt — 2026-09-03

## Exact product

- Remote product line: `main@bed7a550a71b1ae91556b2b2af25d7c482083c98`.
- Installed source tree: PR #88 head `7b70d0a11fef932eec316e18cee0adc23227acba`; its tree `c68ae49f314d87902d4bfa171cf78aa0831e5633` equals `main@bed7a55` exactly.
- Local debug APK: 33,030,280 bytes; SHA-256 `771D631E3CBDEB90CE0176D8789F539661C91CB10BD2C0B782FF055FE1B9EC4B`.
- APK signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`, equal to the previously installed `0.17.0 (27)` signer.

## Data-preserving update

- Device class: Pixel 9a, physical Android device.
- `adb install -r --no-streaming`: PASS; no uninstall or clear-data operation was used.
- Installed metadata after update: package `com.choplab.sampler`, version `0.17.2 (29)`, minSdk 29, targetSdk 36.
- App-owned project storage before install, after install and after launch: 6 files, 31,612 KiB, aggregate SHA-256 `E7409498AB5DB500474A0C1AB6A0B59BA129ABAE8E49F4B9276C8AC239B7D2DA` at all three checkpoints.

## Runtime observation

- Cold launch: status `ok`, activity `com.choplab.sampler/.MainActivity`, 930 ms.
- The activity was top-resumed with a live app process; focused fatal/AndroidRuntime/ANR matches: 0.
- The restored BEAT workspace visibly showed the existing A-02 selection, waveform, assigned PADs, loop action and `ALL STOP`; no clipped or overflowing layout was observed in the 1080×2424 capture.
- Local evidence files: task output `choplab-0172-pixel9a-current.png` and its UI hierarchy XML.

The installation/runtime/data-preservation slice reaches `DEVICE_PASS`. Subjective speaker/headphone fidelity, route-specific xRun/latency and human listening acceptance are not inferred from the screen or DSP tests.
