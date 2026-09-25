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
  if [[ "$JAVA_MAJOR" != "21" ]]; then
    warn "JDK 21 is recommended; detected major version ${JAVA_MAJOR:-unknown}"
  fi
else
  warn "java is not installed; install JDK 21"
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
  SDK_ROOT="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1 | sed 's#\\:#:#g; s#\\\\#\\#g')"
fi

if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]]; then
  ok "Android SDK: $SDK_ROOT"
  for pkg in \
    "platforms/android-37.0" \
    "build-tools/36.0.0" \
    "platform-tools"; do
    if [[ -e "$SDK_ROOT/$pkg" ]]; then ok "SDK component: $pkg"; else warn "missing SDK component: $pkg"; fi
  done
else
  warn "ANDROID_HOME/ANDROID_SDK_ROOT or local.properties is not configured"
fi

if command -v adb >/dev/null 2>&1; then
  ok "adb: $(adb version 2>/dev/null | head -n 1)"
else
  warn "adb is not on PATH"
fi

if [[ -x ./gradlew ]]; then
  ok "Gradle wrapper is executable"
else
  warn "Gradle wrapper is not executable; run chmod +x gradlew scripts/*.sh"
fi

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  BRANCH="$(git branch --show-current 2>/dev/null || true)"
  ok "Git repository${BRANCH:+ on branch $BRANCH}"
  if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then warn "working tree has uncommitted changes"; else ok "working tree is clean"; fi
else
  warn "not a Git repository"
fi

info "Run ./scripts/dev-setup.sh, then ./scripts/verify.sh when the Android SDK is ready."
