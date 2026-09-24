#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/scripts/check_repo.py"
cd "$ROOT"
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
echo 'PASS: repository and policy validation; Gradle and runtime gates run separately'
