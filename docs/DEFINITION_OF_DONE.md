# Definition of done

Updated: 2026-09-03

## Supported product

ChopLab is one versioned product line with:

- Android 10+ as the AudioTrack behavior baseline;
- Windows desktop as the JVM/Java Sound companion;
- iOS 16+ as an unsigned Simulator preview.

The incomplete files under `reference/pro-v0.2/` are design input, not another product line and not release work implicitly owed by the current version.

## Required user workflows

A supported target must provide the workflows it advertises without fake or placeholder state:

1. import or legally capture bounded audio;
2. select and adjust a source range and chop markers;
3. assign, edit, audition, release and stop PADs;
4. create the bounded pattern and A/B Song arrangement;
5. save, close, reopen, autosave/recover and Undo/Redo supported edits;
6. export the supported mono/stereo master;
7. release recording and playback resources on explicit stop, replacement, focus/route loss and lifecycle teardown.

Platform-specific UI may differ, but Android and Windows must retain the shared domain and DSP contracts. iOS claims remain limited to its implemented preview surface.

## Audio correctness

- Mono/stereo channel identity is preserved at supported import, realtime and export seams.
- Pitch, tone, gain, fade, choke, transport and the implemented pattern/Song rules are deterministic.
- Default Android master output remains linear below the documented overload knee; overload, silence, clipping, non-finite input, voice retirement, queue rejection and stream restart fail safely.
- Realtime and offline shared seams have explicit parity tests, including terminal samples and full-bar output.
- Expensive Windows source rendering occurs outside the audio-engine monitor; source replacement, cancellation and playback state have one serialized ownership boundary.
- Callback paths covered by the allocation-free contract do not add file I/O, logging or unbounded allocation.

## Persistence and input safety

- One versioned project schema owns save/load compatibility.
- Atomic or recoverable temporary-save behavior preserves the last valid project.
- Corrupt, oversized, traversal, duplicate and malicious project/archive inputs fail closed.
- Autosave/recovery, project replacement and coalesced edits preserve one monotonic production revision.
- Stale, foreign, cancelled and double-resolved command plans cannot publish effects or history.
- Credentials, signing keys, user audio, generated packages and machine-specific SDK paths are excluded from public source and release assets.

## Required engineering gates

Before merging a product change:

- the exact PR head passes shared Desktop/Android-host tests, Android unit/lint/APK/instrumentation, JVM core, Desktop tests/long-press/package, iOS build/tests, public-surface policy and dependency/SBOM checks;
- actionable review findings are reproduced or falsified, fixed when valid, and every conversation is resolved only after verification;
- merge is normal, without force/admin bypass, and the exact merged `main` runs the four platform/policy workflows successfully.

Before publishing a version:

- `gradle.properties`, Android and iOS metadata agree on one monotonic version/build;
- the annotated `vX.Y.Z` tag peels to the exact already-merged `main` commit;
- the immutable release workflow builds and scans the declared Android debug and unsigned iOS Simulator assets, generates the exact manifest/SBOM/checksums and attestations, and never publishes Windows verification bytes;
- authenticated reverse download, recorded asset digests and anonymous HTTP read-back agree before `PUBLIC_PASS` is recorded.

Repository rules must require the unique Android, Windows, iOS and supply-chain checks, conversation resolution, pull-request integration and non-fast-forward/deletion protection for `main`; `v*` tags must reject update and deletion.

## Evidence ceiling

Every result is bound to exact source revision and artifact bytes. Local host tests, hosted CI and AVD execution do not prove physical speaker/headphone quality, Bluetooth/wired routing, screen-reader speech, third-party account behavior or Human acceptance. Those claims require direct observation on the exact final artifact and remain separate from source/build completion.

## Explicit future non-scope

The current product contract does not include MIDI input/transport, independent time-stretch, stems, ADSR/LFO/inserts/sends, a native Oboe engine replacement, arbitrary Song authoring, signed iOS device/App Store distribution, or cloud/LLM audio assistance. These are not current TODOs. Any of them requires a separately accepted specification, implementation and release decision.
