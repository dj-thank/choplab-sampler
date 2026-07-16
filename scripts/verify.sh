#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./scripts/validate_project.sh

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && ! -f local.properties ]]; then
  echo "ERROR: Android SDK is not configured. Run ./scripts/bootstrap.sh after setting ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi

./gradlew --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: Gradle completed but $APK was not found." >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$APK"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$APK"
fi

echo "PASS: full verification completed; APK=$APK"
