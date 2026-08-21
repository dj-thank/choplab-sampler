#!/usr/bin/env bash
set -euo pipefail

# 2.46.0 moved a dependency to Swift tools 6.0 and cannot be built by the
# Swift 5.10 toolchain on the macOS 14 runner. Pin the newest compatible
# release to its immutable full commit until the CI image is moved to Swift 6.
XCODEGEN_VERSION="2.45.4"
XCODEGEN_COMMIT="8d3d3476a69ae3e5d68e1adccc701c410c05eb36"
INSTALL_ROOT="${RUNNER_TEMP:-/tmp}/choplab-xcodegen"
BIN_DIR="${RUNNER_TEMP:-/tmp}/choplab-bin"

rm -rf "${INSTALL_ROOT}"
mkdir -p "${INSTALL_ROOT}" "${BIN_DIR}"
git -C "${INSTALL_ROOT}" init --quiet
git -C "${INSTALL_ROOT}" remote add origin https://github.com/yonaskolb/XcodeGen.git
git -C "${INSTALL_ROOT}" fetch --quiet --depth 1 origin "${XCODEGEN_COMMIT}"
git -C "${INSTALL_ROOT}" checkout --quiet --detach FETCH_HEAD

actual_commit="$(git -C "${INSTALL_ROOT}" rev-parse HEAD)"
[[ "${actual_commit}" == "${XCODEGEN_COMMIT}" ]] || {
  echo "XcodeGen commit mismatch: expected ${XCODEGEN_COMMIT}, found ${actual_commit}" >&2
  exit 1
}

swift build --package-path "${INSTALL_ROOT}" --configuration release --product xcodegen
cp "${INSTALL_ROOT}/.build/release/xcodegen" "${BIN_DIR}/xcodegen"
chmod 0755 "${BIN_DIR}/xcodegen"

version_output="$("${BIN_DIR}/xcodegen" version)"
grep -Fq "${XCODEGEN_VERSION}" <<<"${version_output}" || {
  echo "XcodeGen version mismatch: ${version_output}" >&2
  exit 1
}

if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "${BIN_DIR}" >> "${GITHUB_PATH}"
else
  export PATH="${BIN_DIR}:${PATH}"
fi

echo "Installed XcodeGen ${XCODEGEN_VERSION} from ${XCODEGEN_COMMIT}"
