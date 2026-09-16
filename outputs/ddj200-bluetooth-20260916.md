# DDJ-200 Android Bluetooth follow-up — implementation and publication evidence

Date: 2026-09-16. Baseline PR #102 `eeda0d28cac42374920dd1441fdde95a13b8bd78`.
Reconstructed baseline Git tree exactly equals upstream:
`672f030b7e98df566e81d190898191e43ed6e4f5`.

## Publication and binary boundary

The earlier delivery remained local after a GitHub tool safety block; no branch
update occurred in that attempt. The user subsequently requested publication.
This candidate targets the existing `feat/ddj200-controller-20260916` branch
and PR #102, without merge, force-push, release, APK or installation.

On the publication retry, the mounted archive passed manifest SHA-256 checks.
Reconstructed source equals candidate tree `c96cb444a210b552e777358b42a8b64762143afa`;
reversing and reapplying its patch reproduced the exact baseline and candidate
trees. Production and test code are unchanged; only publication documentation
is updated. The existing PR head was read as the baseline commit above.
GitHub accepted source-tree creation on this retry. Final commit/ref read-back
and exact-head CI are recorded on PR #102, not inferred from a created tree.

The supplemental host suite was rerun: **74/74 PASS**. The current-tree
public-surface scan passed for **636 public candidates**. The full validator
still stopped before Gradle at the existing Kotlin CLI `addLast` incompatibility;
Android SDK and adb remain absent. No hardware or binary acceptance is claimed.

## Checks performed

- `python tools/run_ddj_host.py --output <temporary-log-directory>`: **74/74 PASS**
  on JDK 21.0.11 / Kotlin CLI 1.9.0; 32 session/parser + 28 mixer + 6 LED +
  8 pure Android-state owner/route test bodies. Temporary test copies remove
  annotations and remap JUnit assertions to kotlin.test; actual production
  sources and assertion bodies are compiled. No Android APIs/Compose UI are
  compiled by this supplemental runner. Full results are adjacent.
- Current-tree public-surface scan: PASS. Repeated after all deliverables; see
  bundled validation log for final candidate count. No credential/signing/audio
  candidate was reported. This does not certify arbitrary dependencies/content.
- `scripts/doctor.sh`: exit 0 WITH WARNINGS: Android SDK and adb absent; CLI not
  installed; JDK 21 versus older script recommendation; local dirty worktree.
  Exit zero is not Android build readiness.
- `scripts/validate_project.sh`: FAIL before Gradle. Kotlin CLI 1.9 cannot resolve
  existing `MutableList.addLast` at EditHistory.kt lines 24/31/38. This file is
  unchanged. No baseline change/dependency downgrade was introduced to hide it.
- Changed-file whitespace check and Android XML parsing: PASS.
- Patch is checked against an independent index of the exact baseline; the
  resulting tree must equal the staged source tree. Bundle integrity includes
  SHA-256 hashes. No live remote write succeeds by means of this validation.

## Not validated

Supported Gradle/Kotlin 2.3.20/JUnit, Android/Compose compilation, lint, APK,
USB/BLE device discovery and MIDI IO, actual AudioTrack output paths, splitter
routing/unplug behavior, LED lamps, long sessions, physical jog response,
latency and subjective audio quality. Windows/iOS MIDI adapters absent.

Old-head Android job 104618703868 in run 35040366506 reached lint then failed
resolving lint-gradle 9.0.1 / 31.13.2 from Google's Maven servers due TLS handshake
termination. That old-head result does not validate this new local code.

See `docs/DDJ200.md` for exact live-only mix/monitor semantics, Android connection
steps, official specifications and physical acceptance checklist.
