#!/usr/bin/env python3
"""Build and inspect an unsigned iPad device app. Does not sign, install or contact a device."""
from __future__ import annotations

import datetime
import json
from pathlib import Path
import platform
import plistlib
import zipfile

from build_simulator import BUNDLE, ROOT, digest, read, run


def verify_device_app(app: Path) -> dict:
    info = plistlib.loads((app / "Info.plist").read_bytes())
    if info["CFBundleIdentifier"] != BUNDLE or info.get("UIDeviceFamily") != [2]:
        raise RuntimeError("Unexpected iPad device app identity")
    if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
        raise RuntimeError("App is not an iPhoneOS device build")
    binary = app / info["CFBundleExecutable"]
    if read("xcrun", "lipo", "-archs", str(binary)) != "arm64":
        raise RuntimeError("Unexpected device app architecture")
    load_commands = read("xcrun", "vtool", "-show-build", str(binary))
    if "platform IOS" not in load_commands or "IOSSIMULATOR" in load_commands:
        raise RuntimeError("App binary does not target an iOS device")
    if (app / "embedded.mobileprovision").exists() or (app / "_CodeSignature").exists():
        raise RuntimeError("Unsigned artifact unexpectedly contains device provisioning or app signing data")
    resources = list(app.rglob("*.cvr"))
    if not resources:
        raise RuntimeError("Compose localized resources are absent from the device app")
    return {"bundleId": BUNDLE, "platform": "iPhoneOS", "architecture": "arm64",
            "minimumOS": info.get("MinimumOSVersion"), "binaryBytes": binary.stat().st_size,
            "binarySha256": digest(binary), "composeResourceFiles": len(resources)}


def main() -> None:
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("An ARM64 Mac with Xcode and its iPhoneOS SDK is required")
    evidence = ROOT / "dist/ipad-native-foundation-evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    derived = ROOT / "apple/build/xcode-device"
    run("xcodebuild", "-project", "apple/ChopLab.xcodeproj", "-scheme", "ChopLab", "-configuration", "Debug",
        "-sdk", "iphoneos", "-destination", "generic/platform=iOS", "-derivedDataPath", str(derived),
        "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "build")
    app = derived / "Build/Products/Debug-iphoneos/ChopLab.app"
    report = verify_device_app(app)
    package = ROOT / "dist/ChopLab-iPad-Unsigned-Device.zip"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(app.rglob("*")):
            if path.is_symlink():
                raise RuntimeError("Unexpected device app symlink")
            if path.is_file():
                archive.write(path, "ChopLab.app/" + path.relative_to(app).as_posix())
        archive.writestr("INSTALLATION.txt", (
            "おとひろい / ChopLab — 未署名のiPad実機向けビルド\n"
            "この.appはarm64 / iPadOS 18以降を対象にしたビルドです。直接導入できるIPAではありません。\n"
            "このビルドでは署名Team・provisioning profileを使用せず、実機への導入は行っていません。\n"
            "所有iPadで動かすには、同じsource revisionをMacへ取得し、Xcode・JDK 21と\n"
            "apple/README.mdに記載したAndroid SDKを準備してください。\n"
            "apple/ChopLab.xcodeprojを開き、ChopLab schemeと所有iPadを選択します。\n"
            "Signing & Capabilitiesで所有者のTeamと利用可能なPreview bundle IDを設定し、\n"
            "XcodeからBuild / Runしてください。\n"
            "実機の音声・マイク・割込み・アクセシビリティは別の確認項目です。\n"
            "ビルドのrevisionとhashは検証artifact内のdevice-app-results.jsonに記録されています。\n"
        ))
    report.update({"scope": "unsigned-device-app-build-only", "appBuilt": True,
                   "appSigningPerformed": False, "deviceInstalled": False, "physicalDeviceTested": False,
                   "sourceRevision": read("git", "rev-parse", "HEAD"),
                   "observedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "package": {"name": package.name, "bytes": package.stat().st_size, "sha256": digest(package)}})
    (evidence / "device-app-results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    run("python3", "scripts/check_public_surface.py", "--archive", str(package))


if __name__ == "__main__":
    main()
