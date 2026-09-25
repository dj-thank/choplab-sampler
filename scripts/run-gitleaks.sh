#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Official v8.30.1 release asset digest, verified 2026-09-25.
version=8.30.1
checksum=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
destination="${RUNNER_TEMP:-$ROOT/build/tools}/choplab-gitleaks-$version"
mkdir -p "$destination"
archive="$destination/gitleaks.tar.gz"
curl --fail --location --retry 3 --output "$archive" "https://github.com/gitleaks/gitleaks/releases/download/v$version/gitleaks_${version}_linux_x64.tar.gz"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check --status
tar -xzf "$archive" -C "$destination" gitleaks
"$destination/gitleaks" git "$ROOT" --config "$ROOT/.gitleaks.toml" --redact --no-banner --log-opts=--all
