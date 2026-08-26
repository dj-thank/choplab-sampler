#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/choplab-smoke"
rm -rf "$OUT"
mkdir -p "$OUT"

kotlinc \
  "$ROOT"/shared/src/commonMain/kotlin/com/choplab/sampler/format/*.kt \
  "$ROOT"/shared/src/commonMain/kotlin/com/choplab/sampler/model/*.kt \
  "$ROOT/shared/src/commonMain/kotlin/com/choplab/sampler/audio/SamplerDspPrimitives.kt" \
  "$ROOT/app/src/main/java/com/choplab/sampler/audio/SamplerPlaybackEngine.kt" \
  "$ROOT/jvm-core/src/main/kotlin/com/choplab/sampler/audio/VoicePlaybackCursor.kt" \
  "$ROOT/app/src/main/java/com/choplab/sampler/audio/TransientDetector.kt" \
  "$ROOT/jvm-core/src/main/kotlin/com/choplab/sampler/audio/WavFileWriter.kt" \
  "$ROOT/jvm-core/src/main/kotlin/com/choplab/sampler/audio/StereoPcm.kt" \
  "$ROOT/jvm-core/src/main/kotlin/com/choplab/sampler/audio/PatternRenderer.kt" \
  "$ROOT/scripts/SmokeMain.kt" \
  -include-runtime \
  -d "$OUT/choplab-smoke.jar"

java -jar "$OUT/choplab-smoke.jar"
