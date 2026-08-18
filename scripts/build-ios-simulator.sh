#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${CHOPLAB_VERSION:-0.14.0-preview.1}"
DERIVED_DATA="${ROOT_DIR}/ios/build"
DIST_DIR="${ROOT_DIR}/dist"
PROJECT="${ROOT_DIR}/ios/ChopLab.xcodeproj"

command -v xcodegen >/dev/null 2>&1 || {
  echo "xcodegen is required; install it with: brew install xcodegen" >&2
  exit 2
}

cd "${ROOT_DIR}"
xcodegen generate --spec ios/project.yml --project "${PROJECT}"
xcodebuild \
  -project "${PROJECT}" \
  -scheme ChopLab \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "${DERIVED_DATA}" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  build

APP_PATH="${DERIVED_DATA}/Build/Products/Debug-iphonesimulator/ChopLab.app"
test -d "${APP_PATH}"
mkdir -p "${DIST_DIR}"
ARCHIVE="${DIST_DIR}/ChopLab-${VERSION}-ios-simulator.app.zip"
ditto -c -k --sequesterRsrc --keepParent "${APP_PATH}" "${ARCHIVE}"
shasum -a 256 "${ARCHIVE}" > "${ARCHIVE}.sha256"
echo "Built ${ARCHIVE}"
