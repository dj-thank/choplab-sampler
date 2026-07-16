#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/choplab-smoke"
rm -rf "$OUT"
mkdir -p "$OUT"

kotlinc \
  "$ROOT/app/src/main/java/com/choplab/sampler/model/SamplerModels.kt" \
  "$ROOT/app/src/main/java/com/choplab/sampler/audio/TransientDetector.kt" \
  "$ROOT/app/src/main/java/com/choplab/sampler/audio/WavFileWriter.kt" \
  "$ROOT/app/src/main/java/com/choplab/sampler/audio/PatternRenderer.kt" \
  "$ROOT/scripts/SmokeMain.kt" \
  -include-runtime \
  -d "$OUT/choplab-smoke.jar"

java -jar "$OUT/choplab-smoke.jar"
