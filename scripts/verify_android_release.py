#!/usr/bin/env python3
"""Inspect the final Android APK rather than inferring release safety from source."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = f"{{{ANDROID_NS}}}"
ALLOWED_PERMISSIONS = {
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.POST_NOTIFICATIONS",
}
EXPORTED_COMPONENT_PERMISSIONS: dict[str, str | None] = {
    # The launcher is intentionally public and must not be permission-gated.
    "com.choplab.sampler.MainActivity": None,
    # AndroidX exports this receiver for shell/profile tooling, but protects every entry point
    # with the platform signature permission. Accepting the class name without the permission
    # check would turn a dependency manifest regression into a public attack surface.
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


class VerificationError(RuntimeError):
    pass


def run(command: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, check=False, text=True, capture_output=True)
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise VerificationError(f"Command failed: {' '.join(command)}\n{detail}")
    return result


def android_tool_executable_names(name: str, *, platform: str = os.name) -> tuple[str, ...]:
    if platform == "nt":
        # Android command-line wrappers use .bat, while native build tools such
        # as zipalign use .exe. Check both instead of guessing one extension.
        return (f"{name}.bat", f"{name}.exe", f"{name}.cmd", name)
    return (name,)


def find_android_tool(name: str) -> str:
    executables = android_tool_executable_names(name)
    for executable in executables:
        found = shutil.which(executable)
        if found:
            return found

    sdk_root_text = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_root_text:
        raise VerificationError(f"Cannot find {name}; ANDROID_SDK_ROOT is not set")
    sdk_root = Path(sdk_root_text)
    candidates: list[Path] = []
    if name == "apkanalyzer":
        for executable in executables:
            candidates.extend(
                [
                    sdk_root / "cmdline-tools" / "latest" / "bin" / executable,
                    sdk_root / "tools" / "bin" / executable,
                ]
            )
    for build_tools in sorted((sdk_root / "build-tools").glob("*"), reverse=True):
        candidates.extend(build_tools / executable for executable in executables)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise VerificationError(f"Cannot find Android SDK tool: {name}")


def normalize_component_name(package_name: str, name: str) -> str:
    if name.startswith("."):
        return package_name + name
    if "." not in name:
        return f"{package_name}.{name}"
    return name


def parse_manifest(xml_text: str) -> ET.Element:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise VerificationError(f"apkanalyzer returned invalid manifest XML: {error}") from error
    if root.tag != "manifest":
        raise VerificationError(f"Unexpected manifest root: {root.tag}")
    return root


def verify_manifest(root: ET.Element, *, expected_version: str, expected_version_code: int) -> None:
    package_name = root.attrib.get("package", "")
    if package_name != "com.choplab.sampler":
        raise VerificationError(f"Unexpected application ID: {package_name!r}")

    actual_version = root.attrib.get(f"{ANDROID}versionName")
    actual_version_code = root.attrib.get(f"{ANDROID}versionCode")
    if actual_version != expected_version:
        raise VerificationError(
            f"Android versionName mismatch: expected {expected_version!r}, found {actual_version!r}"
        )
    if actual_version_code != str(expected_version_code):
        raise VerificationError(
            "Android versionCode mismatch: "
            f"expected {expected_version_code}, found {actual_version_code!r}"
        )

    permissions = {
        element.attrib.get(f"{ANDROID}name", "")
        for element in root.findall("uses-permission")
    }
    generated_receiver_permission = f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    allowed_permissions = ALLOWED_PERMISSIONS | {generated_receiver_permission}
    unexpected_permissions = permissions - allowed_permissions
    missing_permissions = ALLOWED_PERMISSIONS - permissions
    if unexpected_permissions:
        raise VerificationError(
            "Published APK contains permissions outside the allowlist: "
            + ", ".join(sorted(unexpected_permissions))
        )
    if missing_permissions:
        raise VerificationError(
            "Published APK is missing expected permissions: "
            + ", ".join(sorted(missing_permissions))
        )

    generated_permission_declarations = {
        element.attrib.get(f"{ANDROID}name", "")
        for element in root.findall("permission")
    }
    unexpected_declarations = generated_permission_declarations - {generated_receiver_permission}
    if unexpected_declarations:
        raise VerificationError(
            "Published APK declares permissions outside the allowlist: "
            + ", ".join(sorted(unexpected_declarations))
        )

    application = root.find("application")
    if application is None:
        raise VerificationError("Android manifest has no application element")
    if application.attrib.get(f"{ANDROID}debuggable", "false").lower() == "true":
        raise VerificationError("Published APK is debuggable")

    exported: dict[str, str | None] = {}
    all_components: set[str] = set()
    for tag in COMPONENT_TAGS:
        for element in application.findall(tag):
            raw_name = element.attrib.get(f"{ANDROID}name", "")
            if not raw_name:
                continue
            normalized = normalize_component_name(package_name, raw_name)
            all_components.add(normalized)
            if element.attrib.get(f"{ANDROID}exported", "false").lower() == "true":
                exported[normalized] = element.attrib.get(f"{ANDROID}permission")

    forbidden_tooling = sorted(
        component
        for component in all_components
        if component.startswith("androidx.compose.ui.tooling")
        or component == "androidx.activity.ComponentActivity"
    )
    if forbidden_tooling:
        raise VerificationError(
            "Published APK contains debug/test tooling components: " + ", ".join(forbidden_tooling)
        )

    unexpected_exported = set(exported) - set(EXPORTED_COMPONENT_PERMISSIONS)
    if unexpected_exported:
        raise VerificationError(
            "Published APK exposes components outside the allowlist: "
            + ", ".join(sorted(unexpected_exported))
        )

    for component, required_permission in EXPORTED_COMPONENT_PERMISSIONS.items():
        if component not in exported:
            continue
        actual_permission = exported[component]
        if actual_permission != required_permission:
            expected = required_permission or "no permission"
            actual = actual_permission or "no permission"
            raise VerificationError(
                f"Exported component {component} must require {expected}; found {actual}"
            )

    main_activity = normalize_component_name(package_name, ".MainActivity")
    if main_activity not in exported:
        raise VerificationError("Launcher MainActivity is not exported")


def normalize_fingerprint(value: str) -> str:
    normalized = re.sub(r"[^0-9a-fA-F]", "", value).lower()
    if len(normalized) != 64:
        raise VerificationError("Expected certificate SHA-256 must contain exactly 64 hex digits")
    return normalized


def verify_signature(
    apk: Path,
    *,
    require_signed: bool,
    expected_cert_sha256: str | None,
) -> str | None:
    apksigner = find_android_tool("apksigner")
    result = run([apksigner, "verify", "--print-certs", "--verbose", str(apk)], check=False)
    if result.returncode != 0:
        if require_signed:
            detail = result.stderr.strip() or result.stdout.strip() or "APK is unsigned"
            raise VerificationError(f"Published APK signature verification failed: {detail}")
        return None

    match = re.search(
        r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
        result.stdout,
    )
    if match is None:
        if require_signed or expected_cert_sha256:
            raise VerificationError("Could not read signer certificate SHA-256 from apksigner")
        return None
    actual = normalize_fingerprint(match.group(1))
    if expected_cert_sha256 is not None:
        expected = normalize_fingerprint(expected_cert_sha256)
        if actual != expected:
            raise VerificationError(
                f"Android signing certificate mismatch: expected {expected}, found {actual}"
            )
    return actual


def verify_alignment(apk: Path) -> None:
    zipalign = find_android_tool("zipalign")
    run([zipalign, "-c", "-P", "16", "-v", "4", str(apk)])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--require-signed", action="store_true")
    parser.add_argument("--expected-cert-sha256")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.apk.is_file():
        raise VerificationError(f"APK does not exist: {args.apk}")

    apkanalyzer = find_android_tool("apkanalyzer")
    manifest_xml = run([apkanalyzer, "manifest", "print", str(args.apk)]).stdout
    verify_manifest(
        parse_manifest(manifest_xml),
        expected_version=args.version,
        expected_version_code=args.version_code,
    )
    verify_alignment(args.apk)
    fingerprint = verify_signature(
        args.apk,
        require_signed=args.require_signed,
        expected_cert_sha256=args.expected_cert_sha256,
    )
    print(
        "Verified Android release APK: "
        f"{args.apk} version={args.version} code={args.version_code} "
        f"certificate_sha256={fingerprint or 'unsigned-ci-candidate'}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
