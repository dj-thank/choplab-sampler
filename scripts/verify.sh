#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./scripts/validate_project.sh

if [[ -n "$(git status --porcelain=v1 --untracked-files=no)" ]]; then
  echo "ERROR: tracked worktree must be clean before source-bound verification." >&2
  exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && ! -f local.properties ]]; then
  echo "ERROR: Android SDK is not configured. Run ./scripts/bootstrap.sh after setting ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi

./gradlew --stacktrace clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest \
  --no-daemon --max-workers=1 --no-watch-fs

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

echo "HEAD=$(git rev-parse HEAD)"
echo "TREE=$(git rev-parse 'HEAD^{tree}')"
echo "PASS: full verification completed; APK=$APK"
