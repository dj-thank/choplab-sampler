#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ok() { printf 'OK   %s\n' "$*"; }
warn() { printf 'WARN %s\n' "$*"; }
info() { printf 'INFO %s\n' "$*"; }

info "workspace: $ROOT"

if command -v git >/dev/null 2>&1; then
  ok "git: $(git --version)"
else
  warn "git is not installed"
fi

if command -v java >/dev/null 2>&1; then
  JAVA_LINE="$(java -version 2>&1 | head -n 1)"
  ok "java: $JAVA_LINE"
  JAVA_MAJOR="$(java -version 2>&1 | awk -F[\".] '/version/ {print ($2==1?$3:$2); exit}')"
  if [[ "$JAVA_MAJOR" != "17" ]]; then
    warn "JDK 17 is recommended; detected major version ${JAVA_MAJOR:-unknown}"
  fi
else
  warn "java is not installed; install JDK 17"
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
  SDK_ROOT="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1 | sed 's#\\:#:#g; s#\\\\#\\#g')"
fi

if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]]; then
  ok "Android SDK: $SDK_ROOT"
  for pkg in \
    "platforms/android-36" \
    "build-tools/36.0.0" \
    "platform-tools" \
    "ndk/29.0.14206865" \
    "cmake/3.22.1"; do
    if [[ -e "$SDK_ROOT/$pkg" ]]; then ok "SDK component: $pkg"; else warn "missing SDK component: $pkg"; fi
  done
else
  warn "ANDROID_HOME/ANDROID_SDK_ROOT or local.properties is not configured"
fi

if command -v adb >/dev/null 2>&1; then
  ok "adb: $(adb version 2>/dev/null | head -n 1)"
  DEVICE_COUNT="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {n++} END {print n+0}')"
  info "connected Android devices: $DEVICE_COUNT"
else
  warn "adb is not on PATH"
fi

if command -v codex >/dev/null 2>&1; then
  ok "codex: $(codex --version 2>/dev/null | head -n 1)"
  if codex login status >/dev/null 2>&1; then ok "Codex authentication is available"; else warn "Codex is installed but not signed in; run: codex login"; fi
else
  warn "Codex CLI is not installed or not on PATH"
fi

if [[ -x ./gradlew ]]; then
  ok "Gradle wrapper is executable"
else
  warn "Gradle wrapper is not executable; run chmod +x gradlew scripts/*.sh"
fi

if [[ -d .git ]]; then
  BRANCH="$(git branch --show-current 2>/dev/null || true)"
  ok "Git repository${BRANCH:+ on branch $BRANCH}"
  if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then warn "working tree has uncommitted changes"; else ok "working tree is clean"; fi
else
  warn "not a Git repository"
fi

info "Run ./scripts/bootstrap.sh, then ./scripts/verify.sh when the Android SDK is ready."
