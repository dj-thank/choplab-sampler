#!/usr/bin/env python3
"""Verify and stage a side-by-side DDJ-200 APK; never publish from this script."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_android_release import (
    ANDROID, COMPONENT_TAGS, DEBUG_PREVIEW_TOOLING_COMPONENTS,
    EXPORTED_COMPONENT_PERMISSIONS, VerificationError, normalize_component_name,
    read_manifest, read_manifest_boolean, verify_alignment, verify_signature,
)

PACKAGE = "com.choplab.sampler.ddj200preview"
APK_NAME = "Otohiroi-DDJ200-preview.apk"
PERMISSIONS = {
    "android.permission.INTERNET", "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN", "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
}


def verify_preview_manifest(root: ET.Element, version: str, code: int) -> None:
    if root.attrib.get("package") != PACKAGE:
        raise VerificationError("Preview must use the isolated DDJ-200 application ID")
    if root.attrib.get(ANDROID + "versionName") != version + "-ddj200-preview":
        raise VerificationError("Unexpected preview versionName")
    if root.attrib.get(ANDROID + "versionCode") != str(code) or code <= 0:
        raise VerificationError("Unexpected preview versionCode")
    sdk = root.find("uses-sdk")
    if sdk is None or sdk.get(ANDROID + "minSdkVersion") != "29" or sdk.get(ANDROID + "targetSdkVersion") != "36":
        raise VerificationError("Preview must preserve Android 10+ / target 36")
    declarations = root.findall("uses-permission")
    names = {e.get(ANDROID + "name", "") for e in declarations}
    dynamic = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    if names - (PERMISSIONS | {dynamic}) or PERMISSIONS - names:
        raise VerificationError("Preview permissions differ from the explicit app/BLE allowlist")
    if any(e.get(ANDROID + "name") != dynamic for e in root.findall("permission")):
        raise VerificationError("Unexpected custom permission")
    for short in ("BLUETOOTH", "BLUETOOTH_ADMIN", "ACCESS_FINE_LOCATION"):
        rows = [e for e in declarations if e.get(ANDROID + "name") == "android.permission." + short]
        if len(rows) != 1 or rows[0].get(ANDROID + "maxSdkVersion") != "30":
            raise VerificationError("Legacy Bluetooth/location permission must stop at API 30")
    scan = next(e for e in declarations if e.get(ANDROID + "name") == "android.permission.BLUETOOTH_SCAN")
    if scan.get(ANDROID + "usesPermissionFlags") != "neverForLocation":
        raise VerificationError("BLE scan must not be used for location")
    app = root.find("application")
    if app is None or not read_manifest_boolean(app, "debuggable", default=False):
        raise VerificationError("This artifact must be explicitly marked as a debug preview")
    if read_manifest_boolean(app, "allowBackup", default=True) or read_manifest_boolean(app, "usesCleartextTraffic", default=True):
        raise VerificationError("Backup/cleartext restrictions must be preserved")
    allowed = dict(EXPORTED_COMPONENT_PERMISSIONS)
    allowed.update({name: None for name in DEBUG_PREVIEW_TOOLING_COMPONENTS})
    exported: set[str] = set()
    for tag in COMPONENT_TAGS:
        for node in app.findall(tag):
            if not read_manifest_boolean(node, "exported", default=False):
                continue
            name = normalize_component_name(PACKAGE, node.get(ANDROID + "name", ""))
            if name not in allowed or node.get(ANDROID + "permission") != allowed[name]:
                raise VerificationError("Unexpected exported component or protection: " + name)
            exported.add(name)
    if "com.choplab.sampler.MainActivity" not in exported:
        raise VerificationError("Production launcher class must be present in the preview")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stage(apk: Path, destination: Path, version: str, code: int, commit: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise VerificationError("An exact source commit is required")
    if apk.is_symlink() or not apk.is_file() or not 0 < apk.stat().st_size < 1024**3:
        raise VerificationError("Expected one regular APK smaller than 1 GiB")
    if destination.exists() and any(destination.iterdir()):
        raise VerificationError("Refusing to overwrite an existing staging directory")
    root, tool = read_manifest(apk)
    verify_preview_manifest(root, version, code)
    verify_alignment(apk)
    signer = verify_signature(apk, require_signed=True, expected_cert_sha256=None)
    if not signer:
        raise VerificationError("A verified APK signer is required")
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / APK_NAME
    shutil.copyfile(apk, target)
    digest = sha256(target)
    (destination / (APK_NAME + ".sha256")).write_text(f"{digest}  {APK_NAME}\n", encoding="utf-8")
    (destination / "build.json").write_text(json.dumps({
        "source_commit": commit, "application_id": PACKAGE,
        "version_name": version + "-ddj200-preview", "version_code": code,
        "apk": APK_NAME, "sha256": digest, "certificate_sha256": signer,
        "manifest_reader": tool, "distribution": "side-by-side-debug-preview",
        "physical_ddj200_verified": False,
        "upgrade_compatibility": "Requires the same signer; CI debug keys are not stable across builds.",
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    files = sorted(destination.iterdir())
    (destination / "SHA256SUMS").write_text("".join(f"{sha256(p)}  {p.name}\n" for p in files), encoding="utf-8")
    print(f"Verified preview: {target}; source={commit}; sha256={digest}; signer={signer}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--commit", required=True)
    a = parser.parse_args()
    stage(a.apk, a.destination, a.version, a.version_code, a.commit)


if __name__ == "__main__":
    try:
        main()
    except (VerificationError, OSError) as error:
        raise SystemExit(str(error)) from error
