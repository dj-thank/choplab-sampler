#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXPECTED_WRAPPER_SHA="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

python "$ROOT/scripts/check_public_surface.py"
"$ROOT/scripts/run_pure_logic_smoke.sh"

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

echo "PASS: project-level offline validation completed"
