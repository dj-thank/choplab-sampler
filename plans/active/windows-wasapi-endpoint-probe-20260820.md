# Windows WASAPI endpoint probe through JNA — 2026-08-20

## Purpose and user-visible outcome

Replace the invalid assumption that Java Sound is usable on every Windows host. The first slice must prove that ChopLab can reach the current default render and capture endpoints through Windows Core Audio, report their exact engine formats, and fail safely when WASAPI is unavailable. This is the prerequisite for native playback and universal loopback capture.

## Current state

- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-wasapi-20260820`.
- Branch: `codex/choplab-wasapi`; baseline main `6f5805eed22f3c640d1e6d7a6f76e8766b4fb544`.
- Existing Windows product uses `JavaSoundWavPlayer` and TargetDataLine recorders.
- Current host: Java Sound mixers `0`; Windows Audio services running; MMDevices registry contains active render/capture endpoints.
- Primary-source contract is recorded in `docs/research/windows-wasapi-jna-2026-08-20.md`.

## Constraints and invariants

- No provider audio extraction, DRM circumvention, capture-policy bypass, or silent microphone fallback.
- COM initialization, endpoint access, and release happen on one dedicated STA owner thread.
- Every HRESULT, pointer, WaveFormat allocation, and COM reference is checked and released.
- Audio callbacks may not touch Compose state, file I/O, logging loops, or unbounded allocation.
- Non-Windows and unavailable-endpoint paths fail closed with a precise capability result.
- Target gate is `LOCAL_PASS` for source/tests and a narrowly scoped `DEVICE_PASS` only for observed endpoint enumeration on this host.

## Architecture and interfaces

- `desktop/audio/wasapi/WasapiNative.kt`: GUIDs, constants, Ole32 calls, HRESULT handling, and vtable base.
- `desktop/audio/wasapi/WasapiInterfaces.kt`: minimal enumerator/device/audio-client wrappers.
- `desktop/audio/wasapi/WasapiEndpointProbe.kt`: STA ownership, endpoint/mix-format receipt, and cleanup.
- `desktop/audio/wasapi/WaveFormat.kt`: bounded `WAVEFORMATEX/EXTENSIBLE` parsing.
- The existing `DesktopSamplerAudioEngine` is not changed until the probe passes.

## Milestones

### Milestone 1: host-testable native contracts

- Add JNA/JNA Platform 5.19.1 desktop dependencies.
- Test WaveFormat parsing, HRESULT conversion, platform gating, and receipt formatting.
- Compile/package on Windows and compile tests in CI-supported JVM environments.

### Milestone 2: current-device endpoint observation

- Query default render and capture endpoints in STA.
- Read ID, state, mix format and device period, and release all native allocations. Buffer size belongs to the later initialized-stream slice.
- Store a redacted device receipt without raw microphone or audio data.

### Milestone 3: review and handoff

- Run focused/full tests, public-surface scan, packageWindows, Standards/Spec review, and commit.
- If probe passes, select the next plan for shared-mode render and loopback streaming. If it fails, preserve exact HRESULT/API boundary and do not claim device support.

## Progress

- [x] 2026-08-20 — Fixed baseline, target root, owner, rollback and evidence ceiling.
- [x] 2026-08-20 — Confirmed Java Sound mixer count 0 while Windows services and active MMDevice endpoints exist.
- [x] 2026-08-20 — Captured Microsoft/JNA primary-source contracts.
- [x] 2026-08-20 — Implemented host-tested COM/WaveFormat boundary and redacted JSON receipt.
- [x] 2026-08-20 — Ran JNA and independent C# probes: MMDevice render/capture all-state counts are 0 and every default role is not found. PnP AudioEndpoint present count is 0.
- [x] 2026-08-20 — Added native `診断 > Windows 音声エンドポイント` action with background execution and truthful status output.
- [x] 2026-08-20 — Completed local Standards/Spec review and committed candidate `5c0b84d`.
- [ ] GitHub sync and the next streaming slice remain pending; streaming requires at least one present Windows AudioEndpoint.

## Discoveries

- Hardware presence (`Win32_SoundDevice`) does not prove a Java Sound provider or usable endpoint.
- Endpoint enumeration must be proven through MMDevice/WASAPI, not inferred from registry inventory.
- This host has audio adapter hardware records but no present Windows `AudioEndpoint`; streaming work is externally blocked until an endpoint becomes present.

## Decision log

- 2026-08-20 — Use JNA because this host has no reproducible MSVC/CMake/Rust/.NET toolchain and JNA ships a supported native dispatcher.
- 2026-08-20 — Start with endpoint/mix-format proof before touching the live engine, keeping the product buildable and falsifiable.

## Validation log

- `:desktop:test --tests 'com.choplab.desktop.audio.wasapi.*' --tests 'com.choplab.desktop.provider.WindowsAudioDiagnosticsTest'`: PASS.
- `:desktop:runWasapiProbe`: expected exit 2 with a redacted receipt; COM calls succeed, render/capture active and all-state counts are 0, defaults return not found.
- Independent in-memory C# MMDevice probe: `renderAll=0 captureAll=0`, enum/count HRESULT `S_OK`, default HRESULT `0x80070490` for both flows.
- `:desktop:test :desktop:packageWindows :app:compileDebugKotlin`: BUILD SUCCESSFUL with task-local `ANDROID_SDK_ROOT`; public-surface scan PASS.
- Local parent two-axis review: no hard Standards violation and no missing in-scope Spec implementation. Streaming is externally blocked by endpoint absence.

## Risks and rollback

- Incorrect COM vtable signatures can crash the JVM. Keep wrappers minimal, test structures separately, and stop immediately on invalid pointers/HRESULTs.
- JNA overhead may be unacceptable for the final render callback. The probe does not decide the long-term callback implementation.
- Roll back by reverting this plan's commits; the existing Java Sound adapter remains intact until a later gated migration.

## Remaining device validation

- Audible render, loopback PCM, microphone PCM, latency/xruns, exclusive-device conflict, route changes, Bluetooth, sleep/resume, and Human audio quality.
