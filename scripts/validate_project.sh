#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXPECTED_WRAPPER_SHA="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"

python "$ROOT/scripts/check_public_surface.py"
if command -v kotlinc >/dev/null 2>&1; then
  "$ROOT/scripts/run_pure_logic_smoke.sh"
else
  echo "INFO: standalone kotlinc unavailable; using Gradle JVM-core/Desktop tests"
  "$ROOT/gradlew" :jvm-core:test :desktop:test --no-daemon --max-workers=1 --no-watch-fs
fi

python - "$ROOT" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
paths = sorted((root / "app" / "src" / "main").rglob("*.xml"))
if not paths:
    raise SystemExit("No Android XML files found")
for path in paths:
    ET.parse(path)
    print(f"XML OK: {path.relative_to(root)}")
PY

actual_sha="$(sha256sum "$ROOT/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')"
if [[ "$actual_sha" != "$EXPECTED_WRAPPER_SHA" ]]; then
  echo "Gradle Wrapper checksum mismatch: $actual_sha" >&2
  exit 1
fi
echo "Wrapper SHA-256 OK: $actual_sha"

for wrapper_script in "$ROOT/gradlew" "$ROOT/gradlew.bat"; do
  if ! grep -Fq -- '-Dfile.encoding=UTF-8' "$wrapper_script"; then
    echo "Gradle wrapper must force UTF-8: $wrapper_script" >&2
    exit 1
  fi
done
echo "Gradle wrapper UTF-8 policy OK"

echo "PASS: project-level offline validation completed"
