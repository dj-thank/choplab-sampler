#!/usr/bin/env bash
# Local, idempotent Linux x64 setup. Existing project SDK and JDK21 take priority.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
check_only=false
accept_licenses=false
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    --accept-licenses) accept_licenses=true ;;
    --help) echo 'Usage: scripts/dev-setup.sh [--check] [--accept-licenses]'; exit 0 ;;
    *) echo 'Unknown setup argument' >&2; exit 2 ;;
  esac
done
tools="$ROOT/build/dev-tools"
java_home="${JAVA_HOME:-}"
java_bin="${java_home:+$java_home/bin/}java"
major="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1 || true)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -f local.properties ]]; then
  project_sdk="$(sed -n 's/^sdk.dir=//p' local.properties | head -1 | sed 's#\\:#:#g; s#\\\\#\\#g')"
  [[ -z "$project_sdk" ]] || sdk="$project_sdk"
fi
sdk="${sdk:-$tools/android-sdk}"
if $check_only; then
  [[ "$major" == 21 && -d "$sdk/platforms/android-37.0" && -d "$sdk/build-tools/36.0.0" ]] || { echo 'Missing JDK21 or required SDK components' >&2; exit 1; }
  echo 'JDK21 and project SDK detected; no files changed'
  exit 0
fi
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || { echo 'Automatic downloads support Linux x64. On Windows use JDK21 and Android Studio SDK Manager; see docs/TESTING.md.' >&2; exit 1; }
mkdir -p "$tools/downloads"
fetch() {
  local url="$1" checksum="$2" target="$3"
  if [[ ! -f "$target" ]] || ! printf '%s  %s\n' "$checksum" "$target" | sha256sum --check --status; then
    curl --fail --location --retry 3 --output "$target.part" "$url"
    printf '%s  %s\n' "$checksum" "$target.part" | sha256sum --check --status
    mv "$target.part" "$target"
  fi
}
if [[ "$major" != 21 ]]; then
  # Official Adoptium release asset and SHA-256, verified 2026-09-25.
  java_home="$tools/jdk-21.0.12.1+1"
  if [[ ! -x "$java_home/bin/javac" ]]; then
    fetch 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 "$tools/downloads/jdk21.tar.gz"
    tar -xzf "$tools/downloads/jdk21.tar.gz" -C "$tools"
  fi
else
  [[ -n "$java_home" ]] || java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$manager" ]]; then
  manager="$(find "$sdk/cmdline-tools" -path '*/bin/sdkmanager' -type f 2>/dev/null | sort | tail -1 || true)"
fi
if [[ -z "$manager" || ! -x "$manager" ]]; then
  # Android Developers command-line tools table, verified 2026-09-25.
  fetch 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 "$tools/downloads/android-tools.zip"
  destination="$sdk/cmdline-tools/choplab-15859902"
  [[ ! -e "$destination" ]] || { echo 'Incomplete existing command-line tools; inspect before retrying' >&2; exit 1; }
  staging="$(mktemp -d "$tools/sdk-stage.XXXXXX")"
  unzip -q "$tools/downloads/android-tools.zip" -d "$staging"
  mkdir -p "$sdk/cmdline-tools"
  mv "$staging/cmdline-tools" "$destination"
  manager="$destination/bin/sdkmanager"
fi
missing=()
[[ -d "$sdk/platforms/android-37.0" ]] || missing+=('platforms;android-37.0')
[[ -d "$sdk/build-tools/36.0.0" ]] || missing+=('build-tools;36.0.0')
[[ -d "$sdk/platform-tools" ]] || missing+=('platform-tools')
if (( ${#missing[@]} )); then
  if $accept_licenses; then
    echo 'Accepting Android SDK licenses as explicitly requested by --accept-licenses.'
    set +o pipefail
    yes | "$manager" --sdk_root="$sdk" --licenses >/dev/null
    status=${PIPESTATUS[1]}
    set -o pipefail
    [[ "$status" -eq 0 ]] || exit "$status"
  elif [[ ! -t 0 ]]; then
    echo 'SDK components are missing. Re-run scripts/dev-setup.sh in an interactive terminal to review SDK licenses, or use --accept-licenses for an authorized unattended setup.' >&2
    exit 2
  else
    echo 'Android SDK Manager may ask you to review and accept the SDK licenses.'
  fi
  "$manager" --sdk_root="$sdk" "${missing[@]}"
fi
printf 'export JAVA_HOME=%q\nexport ANDROID_HOME=%q\nexport ANDROID_SDK_ROOT=%q\nexport PATH=%q:"$PATH"\n' "$JAVA_HOME" "$sdk" "$sdk" "$JAVA_HOME/bin:$(dirname "$manager"):$sdk/platform-tools" > "$tools/env.sh"
echo 'Setup ready. Source build/dev-tools/env.sh; existing local.properties was preserved.'
