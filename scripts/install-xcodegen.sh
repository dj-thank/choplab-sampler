#!/usr/bin/env bash
set -euo pipefail

# The macOS 14 runner currently provides Swift 5.10. XcodeGen 2.45.x resolves
# XcodeProj 9.x, whose package requires Swift tools 6.0. XcodeGen 2.43.0 is the
# newest release verified here whose locked dependency graph remains on Swift
# tools 5.9, so build that exact source commit instead of floating Homebrew.
XCODEGEN_VERSION="2.43.0"
XCODEGEN_COMMIT="7193eb447a6f60061f069e07bc1efd32d73c0e19"
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
