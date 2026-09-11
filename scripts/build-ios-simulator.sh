#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
metadata_json="$(python3 "${ROOT_DIR}/scripts/release_metadata.py" --properties "${ROOT_DIR}/gradle.properties")"
metadata_value() {
  local key="$1"
  printf '%s' "${metadata_json}" | python3 -c \
    'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "${key}"
}
VERSION="${CHOPLAB_VERSION:-$(metadata_value version)}"
BUILD_NUMBER="${CHOPLAB_BUILD_NUMBER:-$(metadata_value build_number)}"
DERIVED_DATA="${ROOT_DIR}/ios/build"
DIST_DIR="${ROOT_DIR}/dist"
PROJECT="${ROOT_DIR}/ios/ChopLab.xcodeproj"

[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "CHOPLAB_VERSION must be numeric SemVer (for example 0.16.2): ${VERSION}" >&2
  exit 2
}
[[ "${BUILD_NUMBER}" =~ ^[1-9][0-9]*$ ]] || {
  echo "CHOPLAB_BUILD_NUMBER must be a positive integer: ${BUILD_NUMBER}" >&2
  exit 2
}

command -v xcodegen >/dev/null 2>&1 || {
  echo "xcodegen is required" >&2
  exit 2
}

cd "${ROOT_DIR}/ios"
xcodegen generate --spec project.yml
cd "${ROOT_DIR}"
xcodebuild \
  -project "${PROJECT}" \
  -scheme ChopLab \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "${DERIVED_DATA}" \
  MARKETING_VERSION="${VERSION}" \
  CURRENT_PROJECT_VERSION="${BUILD_NUMBER}" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  build

APP_PATH="${DERIVED_DATA}/Build/Products/Debug-iphonesimulator/ChopLab.app"
INFO_PLIST="${APP_PATH}/Info.plist"
test -d "${APP_PATH}"
test -f "${INFO_PLIST}"

actual_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${INFO_PLIST}")"
actual_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${INFO_PLIST}")"
[[ "${actual_version}" == "${VERSION}" ]] || {
  echo "iOS embedded version mismatch: expected ${VERSION}, found ${actual_version}" >&2
  exit 1
}
[[ "${actual_build}" == "${BUILD_NUMBER}" ]] || {
  echo "iOS embedded build mismatch: expected ${BUILD_NUMBER}, found ${actual_build}" >&2
  exit 1
}

mkdir -p "${DIST_DIR}"
ARCHIVE="${DIST_DIR}/ChopLab-${VERSION}-ios-simulator.app.zip"
rm -f "${ARCHIVE}" "${ARCHIVE}.sha256"
ditto -c -k --sequesterRsrc --keepParent "${APP_PATH}" "${ARCHIVE}"
printf '%s  %s\n' "$(shasum -a 256 "${ARCHIVE}" | awk '{print $1}')" "$(basename "${ARCHIVE}")" > "${ARCHIVE}.sha256"
echo "Built ${ARCHIVE} (version ${actual_version}, build ${actual_build})"
