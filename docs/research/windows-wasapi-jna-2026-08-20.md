# Windows WASAPI through JNA — primary-source notes

Date: 2026-08-20 JST

## Fresh readback — 2026-09-28 JST

The observations below under “Observed local problem” describe the August host state. They are historical, not the current endpoint gate. On 2026-09-27 at 20:40:57 UTC, the isolated probe built from `e5cf7fd53a3449d5063d46324c41a307f21a794a` and JNA 5.19.1 successfully opened the current default render and capture endpoints on Windows. Both were active and reported 48 kHz stereo IEEE float, 32-bit containers, `WAVE_FORMAT_EXTENSIBLE`, channel mask 3, and device periods of 10,000 µs default / 3,000 µs minimum. All five transported JAR hashes were checked; the temporary transport was removed and the existing application, launchers and data were preserved.

The former subtype constants had an incorrect GUID suffix and classified both standard formats as unknown. PCM is `{00000001-0000-0010-8000-00AA00389B71}` and IEEE float is `{00000003-0000-0010-8000-00AA00389B71}`. The correction is covered by independent native-byte fixtures for both formats, valid bits, lowercase GUIDs and unknown subtypes; seven format/probe tests passed. Primary definitions: [Microsoft audio GUIDs](https://github.com/microsoft/psi/blob/master/Sources/Audio/Microsoft.Psi.Audio.Windows/ComInterop/Guids.cs), [WAVEFORMATEXTENSIBLE](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ksmedia/ns-ksmedia-waveformatextensible).

This removes the endpoint/format prerequisite only. Event output, microphone/loopback streaming, audible results, round-trip latency and Human acceptance remain unverified. Current progress and the next implementation slice are indexed only in [ROADMAP](../ROADMAP.md).

## Observed local problem

- The current Temurin JDK 17 process reports `AudioSystem.getMixerInfo().length == 0` on this Windows host.
- Windows Audio and Audio Endpoint Builder services are running.
- The Windows MMDevices registry contains historical render/capture entries, but PnP reports 10 `AudioEndpoint` devices and zero are present.
- Independent JNA and in-memory C# MMDevice calls both report render/capture collection count `0`; all default roles return `0x80070490` (`not found`).
- Therefore the Java Sound adapter is not a valid physical-audio proof on this host, and WASAPI streaming cannot start until Windows exposes at least one present endpoint.

## API contracts

- JNA provides prebuilt native dispatch and COM support without requiring a local C/C++ toolchain. The current official JNA download is 5.19.1; `jna-platform` contains Win32 and COM helpers. Sources: [JNA project](https://github.com/java-native-access/jna), [JNA Platform COM support](https://github.com/java-native-access/jna/blob/master/www/PlatformLibrary.md).
- A WASAPI client activates `IAudioClient` on an endpoint, obtains the shared-mode engine format with `GetMixFormat`, and calls `Initialize` once for that client. The first Windows 8+ device access should occur on an STA thread. Source: [IAudioClient::Initialize](https://learn.microsoft.com/windows/win32/api/audioclient/nf-audioclient-iaudioclient-initialize).
- Shared-mode device formats are described by `WAVEFORMATEX` / `WAVEFORMATEXTENSIBLE`; `GetMixFormat` is authoritative for the engine's internal shared-mode format. Source: [Device Formats](https://learn.microsoft.com/windows/win32/coreaudio/device-formats).
- `AUDCLNT_STREAMFLAGS_LOOPBACK` opens capture on a rendering endpoint and is valid only in shared mode. The client then requests `IAudioCaptureClient`. This is standard global-mix loopback, not a DRM or capture-policy bypass. Sources: [stream flags](https://learn.microsoft.com/windows/win32/coreaudio/audclnt-streamflags-xxx-constants), [Capturing a Stream](https://learn.microsoft.com/windows/win32/coreaudio/capturing-a-stream).
- Capture buffers must be consumed as packet pairs: `IAudioCaptureClient::GetBuffer` followed by `ReleaseBuffer`. Source: [Capturing a Stream](https://learn.microsoft.com/windows/win32/coreaudio/capturing-a-stream).

## Implementation decision

1. Add JNA/JNA Platform 5.19.1 to `:desktop` only.
2. Build minimal vtable wrappers for `IMMDeviceEnumerator`, `IMMDevice`, and `IAudioClient` on a dedicated STA thread.
3. First gate: enumerate the default render/capture endpoints, IDs, states, mix formats, and device periods; release every COM pointer and `CoTaskMem` allocation.
4. Keep WaveFormat parsing host-tested with synthetic native memory and make non-Windows behavior fail closed.
5. Only after the endpoint probe succeeds on this host, add a bounded shared-mode renderer and loopback capture client. UI/state integration remains outside the native callback thread.

## Evidence boundary

A successful endpoint probe proves Core Audio access and current mix-format discovery. It does not prove audible output, microphone contents, loopback PCM validity, latency, xruns, Bluetooth/route behavior, or Human audio quality.

The August observation was a truthful unavailable result, not `DEVICE_PASS`: COM initialization and calls succeeded, but both endpoint collections were empty. The app must preserve this distinction on unavailable routes and must not fall back to recording an arbitrary microphone. Use the fresh readback above for the current endpoint gate.
