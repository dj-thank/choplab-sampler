#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
INSTALL_SDK=false
RUN_BUILD=false

for arg in "$@"; do
  case "$arg" in
    --install-sdk) INSTALL_SDK=true ;;
    --build) RUN_BUILD=true ;;
    -h|--help)
      echo "Usage: $0 [--install-sdk] [--build]"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

chmod +x gradlew scripts/*.sh

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java is missing. Install JDK 17." >&2
  exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
  SDK_ROOT="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1 | sed 's#\\:#:#g; s#\\\\#\\#g')"
fi

if [[ -n "$SDK_ROOT" ]]; then
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export ANDROID_HOME="$SDK_ROOT"
  if [[ ! -f local.properties ]]; then
    printf 'sdk.dir=%s\n' "$(printf '%s' "$SDK_ROOT" | sed 's#\\#\\\\#g; s#:#\\:#g')" > local.properties
    echo "Created local.properties"
  fi
fi

if $INSTALL_SDK; then
  if ! command -v sdkmanager >/dev/null 2>&1; then
    if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
      export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"
    else
      echo "ERROR: sdkmanager is missing. Install Android command-line tools or use Android Studio SDK Manager." >&2
      exit 1
    fi
  fi
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager \
    "platform-tools" \
    "platforms;android-37.0" \
    "build-tools;36.0.0" \
    "ndk;29.0.14206865" \
    "cmake;3.22.1"
fi

./scripts/validate_project.sh
./gradlew --version

if $RUN_BUILD; then
  ./scripts/verify.sh
else
  echo "Bootstrap complete. Run ./scripts/verify.sh for the full Android build gate."
fi
