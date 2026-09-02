#!/usr/bin/env python3
"""Inspect the final Android APK rather than inferring release safety from source."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
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
DEBUG_PREVIEW_TOOLING_COMPONENTS = {
    "androidx.compose.ui.tooling.PreviewActivity",
    "androidx.activity.ComponentActivity",
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


def find_android_tool_optional(name: str) -> str | None:
    executables = android_tool_executable_names(name)
    sdk_root_text = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    candidates: list[Path] = []
    if sdk_root_text:
        sdk_root = Path(sdk_root_text)
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

    for executable in executables:
        found = shutil.which(executable)
        if found:
            return found

    return None


def find_android_tool(name: str) -> str:
    found = find_android_tool_optional(name)
    if found:
        return found

    sdk_root_text = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_root_text:
        raise VerificationError(f"Cannot find {name}; ANDROID_SDK_ROOT is not set")
    raise VerificationError(f"Cannot find Android SDK tool: {name}")


def normalize_component_name(package_name: str, name: str) -> str:
    if name.startswith("."):
        return package_name + name
    if "." not in name:
        return f"{package_name}.{name}"
    return name


def read_manifest_boolean(
    element: ET.Element,
    attribute: str,
    *,
    default: bool,
) -> bool:
    raw_value = element.attrib.get(f"{ANDROID}{attribute}")
    if raw_value is None:
        return default
    normalized = raw_value.lower()
    if normalized not in {"true", "false"}:
        raise VerificationError(
            f"Android manifest {attribute} must be a literal boolean; found {raw_value!r}"
        )
    return normalized == "true"


def parse_manifest(xml_text: str) -> ET.Element:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise VerificationError(f"apkanalyzer returned invalid manifest XML: {error}") from error
    if root.tag != "manifest":
        raise VerificationError(f"Unexpected manifest root: {root.tag}")
    return root


def _parse_aapt2_attribute_name(raw_name: str, *, line_number: int) -> str:
    name = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", raw_name)
    if not name or any(character.isspace() for character in name):
        raise VerificationError(
            f"Malformed aapt2 manifest attribute name at line {line_number}: {raw_name!r}"
        )
    if name.startswith(("http://", "https://")):
        try:
            namespace, local_name = name.rsplit(":", 1)
        except ValueError as error:
            raise VerificationError(
                f"Malformed aapt2 namespaced attribute at line {line_number}: {name!r}"
            ) from error
        if not namespace or not local_name:
            raise VerificationError(
                f"Malformed aapt2 namespaced attribute at line {line_number}: {name!r}"
            )
        return f"{{{namespace}}}{local_name}"
    if ":" in name:
        raise VerificationError(
            f"Ambiguous aapt2 attribute namespace at line {line_number}: {name!r}"
        )
    return name


def _parse_aapt2_attribute_value(raw_value: str, *, line_number: int) -> str:
    value = raw_value.strip()
    raw_marker = " (Raw: "
    marker_index = value.rfind(raw_marker)
    if marker_index >= 0:
        if not value.endswith(")"):
            raise VerificationError(
                f"Malformed aapt2 Raw attribute at line {line_number}: {raw_value!r}"
            )
        value = value[:marker_index].rstrip()
    if not value:
        raise VerificationError(f"Empty aapt2 manifest attribute at line {line_number}")
    if value.startswith('"'):
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError as error:
            raise VerificationError(
                f"Malformed quoted aapt2 attribute at line {line_number}: {error.msg}"
            ) from error
        if not isinstance(decoded, str):
            raise VerificationError(
                f"Quoted aapt2 attribute is not a string at line {line_number}"
            )
        return decoded
    if '"' in value:
        raise VerificationError(
            f"Ambiguous quoted aapt2 attribute at line {line_number}: {value!r}"
        )
    return value


def parse_aapt2_manifest(output: str) -> ET.Element:
    """Convert strict ``aapt2 dump xmltree`` output to the canonical manifest tree."""

    root: ET.Element | None = None
    element_stack: list[tuple[int, ET.Element]] = []
    namespace_line = re.compile(r"^N: [^=\s]+=[^\s]+(?: \(line=\d+\))?$")
    element_line = re.compile(
        r"^(?P<indent> *)E: (?P<tag>[A-Za-z0-9_.:-]+)(?: \(line=\d+\))?$"
    )
    attribute_line = re.compile(r"^(?P<indent> *)A: (?P<payload>.+)$")

    for line_number, line in enumerate(output.splitlines(), start=1):
        if not line.strip():
            continue
        if "\t" in line:
            raise VerificationError(f"Unsupported aapt2 manifest tab at line {line_number}")
        stripped = line.lstrip(" ")
        if stripped.startswith("N:"):
            if line != stripped or root is not None or not namespace_line.fullmatch(stripped):
                raise VerificationError(
                    f"Malformed aapt2 namespace line at line {line_number}: {line!r}"
                )
            continue

        element_match = element_line.fullmatch(line)
        if element_match:
            indent = len(element_match.group("indent"))
            element = ET.Element(element_match.group("tag"))
            while element_stack and element_stack[-1][0] >= indent:
                element_stack.pop()
            if not element_stack:
                if root is not None:
                    raise VerificationError(
                        f"aapt2 manifest contains multiple roots at line {line_number}"
                    )
                root = element
            else:
                element_stack[-1][1].append(element)
            element_stack.append((indent, element))
            continue

        attribute_match = attribute_line.fullmatch(line)
        if attribute_match:
            if not element_stack:
                raise VerificationError(
                    f"aapt2 manifest contains an orphan attribute at line {line_number}"
                )
            indent = len(attribute_match.group("indent"))
            element_indent, element = element_stack[-1]
            if indent != element_indent + 2:
                raise VerificationError(
                    f"Ambiguous aapt2 attribute indentation at line {line_number}"
                )
            payload = attribute_match.group("payload")
            raw_name, separator, raw_value = payload.partition("=")
            if not separator:
                raise VerificationError(
                    f"Malformed aapt2 manifest attribute at line {line_number}: {payload!r}"
                )
            name = _parse_aapt2_attribute_name(raw_name, line_number=line_number)
            if name in element.attrib:
                raise VerificationError(
                    f"aapt2 manifest contains a duplicate attribute at line {line_number}: {name!r}"
                )
            element.attrib[name] = _parse_aapt2_attribute_value(
                raw_value,
                line_number=line_number,
            )
            continue

        raise VerificationError(
            f"Unsupported aapt2 manifest line at line {line_number}: {line!r}"
        )

    if root is None:
        raise VerificationError("aapt2 returned an empty manifest tree")
    if root.tag != "manifest":
        raise VerificationError(f"Unexpected manifest root from aapt2: {root.tag}")
    return root


def read_manifest(apk: Path) -> tuple[ET.Element, str]:
    apkanalyzer = find_android_tool_optional("apkanalyzer")
    if apkanalyzer:
        manifest_xml = run([apkanalyzer, "manifest", "print", str(apk)]).stdout
        return parse_manifest(manifest_xml), "apkanalyzer"

    aapt2 = find_android_tool("aapt2")
    manifest_tree = run(
        [aapt2, "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"]
    ).stdout
    return parse_aapt2_manifest(manifest_tree), "aapt2"


def verify_manifest(
    root: ET.Element,
    *,
    expected_version: str,
    expected_version_code: int,
    allow_debug_preview: bool = False,
) -> None:
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
    debuggable = read_manifest_boolean(application, "debuggable", default=False)
    if debuggable and not allow_debug_preview:
        raise VerificationError("Published APK is debuggable")
    if allow_debug_preview and not debuggable:
        raise VerificationError("Debug preview APK must be debuggable")

    exported: dict[str, str | None] = {}
    all_components: set[str] = set()
    for tag in COMPONENT_TAGS:
        for element in application.findall(tag):
            raw_name = element.attrib.get(f"{ANDROID}name", "")
            if not raw_name:
                continue
            normalized = normalize_component_name(package_name, raw_name)
            all_components.add(normalized)
            if read_manifest_boolean(element, "exported", default=False):
                exported[normalized] = element.attrib.get(f"{ANDROID}permission")

    tooling_components = {
        component
        for component in all_components
        if component.startswith("androidx.compose.ui.tooling")
        or component == "androidx.activity.ComponentActivity"
    }
    allowed_tooling = DEBUG_PREVIEW_TOOLING_COMPONENTS if allow_debug_preview else set()
    forbidden_tooling = sorted(tooling_components - allowed_tooling)
    if forbidden_tooling:
        raise VerificationError(
            "Published APK contains debug/test tooling components: " + ", ".join(forbidden_tooling)
        )

    allowed_exported = dict(EXPORTED_COMPONENT_PERMISSIONS)
    if allow_debug_preview:
        allowed_exported.update({component: None for component in DEBUG_PREVIEW_TOOLING_COMPONENTS})
    unexpected_exported = set(exported) - set(allowed_exported)
    if unexpected_exported:
        raise VerificationError(
            "Published APK exposes components outside the allowlist: "
            + ", ".join(sorted(unexpected_exported))
        )

    for component, required_permission in allowed_exported.items():
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


def read_apksigner_certificate_sha256(*outputs: str | None) -> str | None:
    fingerprints: set[str] = set()
    pem_pattern = re.compile(
        r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
        re.DOTALL,
    )
    for output in outputs:
        if not output:
            continue
        for match in re.finditer(
            r"^\s*Signer #1 certificate SHA-?256(?: digest)?\s*:\s*(.*?)\s*$",
            output,
            re.IGNORECASE | re.MULTILINE,
        ):
            fingerprints.add(normalize_fingerprint(match.group(1)))

        pem_blocks = pem_pattern.findall(output)
        begin_count = output.count("-----BEGIN CERTIFICATE-----")
        end_count = output.count("-----END CERTIFICATE-----")
        if begin_count != end_count or begin_count != len(pem_blocks):
            raise VerificationError("apksigner returned malformed PEM certificate output")
        for pem_body in pem_blocks:
            encoded = re.sub(r"\s+", "", pem_body)
            try:
                certificate_der = base64.b64decode(encoded, validate=True)
            except (binascii.Error, ValueError) as error:
                raise VerificationError("apksigner returned malformed PEM certificate output") from error
            if not certificate_der:
                raise VerificationError("apksigner returned an empty PEM certificate")
            fingerprints.add(hashlib.sha256(certificate_der).hexdigest())

    if not fingerprints:
        return None
    if len(fingerprints) != 1:
        raise VerificationError("apksigner reported conflicting signer certificate SHA-256 values")
    return next(iter(fingerprints))


def verify_signature(
    apk: Path,
    *,
    require_signed: bool,
    expected_cert_sha256: str | None,
) -> str | None:
    apksigner = find_android_tool("apksigner")
    result = run([apksigner, "verify", "--print-certs-pem", "--verbose", str(apk)], check=False)
    if result.returncode != 0:
        if require_signed:
            detail = result.stderr.strip() or result.stdout.strip() or "APK is unsigned"
            raise VerificationError(f"Published APK signature verification failed: {detail}")
        return None

    actual = read_apksigner_certificate_sha256(result.stdout, result.stderr)
    if actual is None:
        if require_signed or expected_cert_sha256:
            raise VerificationError("Could not read signer certificate SHA-256 from apksigner")
        return None
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
    parser.add_argument("--allow-debug-preview", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.apk.is_file():
        raise VerificationError(f"APK does not exist: {args.apk}")

    manifest, manifest_tool = read_manifest(args.apk)
    verify_manifest(
        manifest,
        expected_version=args.version,
        expected_version_code=args.version_code,
        allow_debug_preview=args.allow_debug_preview,
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
        f"manifest_tool={manifest_tool} "
        f"certificate_sha256={fingerprint or 'unsigned-ci-candidate'}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
