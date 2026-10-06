#!/usr/bin/env bash
set -euo pipefail

CHOPLAB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$(uname -s)" != Darwin ]]; then
  printf 'This launcher requires macOS. Use :desktop:runLinkedPreview on other hosts.\n' >&2
  exit 2
fi

CHOPLAB_ARGS=(--console=plain :desktop:runLinkedPreview)
case "${1:-}" in
  "") ;;
  --debug)
    CHOPLAB_ARGS+=(--debug-jvm)
    printf 'Waiting for a JVM debugger at 127.0.0.1:5005 before opening the editor.\n'
    ;;
  --help|-h)
    printf 'Usage: %s [--debug]\n' "$0"
    printf 'Run the current NEXT source. --debug waits for a Remote JVM debugger on port 5005.\n'
    exit 0
    ;;
  *) printf 'Usage: %s [--debug]\n' "$0" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [--debug]\n' "$0" >&2
  exit 2
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  printf 'Set JAVA_HOME to an installed JDK 21.\n' >&2
  exit 2
fi
if ! xcrun --find swiftc >/dev/null 2>&1; then
  printf 'Install the Xcode Command Line Tools to build the system audio helper.\n' >&2
  exit 2
fi
cd "$CHOPLAB_ROOT"
exec ./gradlew "${CHOPLAB_ARGS[@]}"
