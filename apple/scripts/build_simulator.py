#!/usr/bin/env python3
"""Build the actual app, launch twice on the selected simulator, and bind package bytes to source."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import subprocess
import time
import uuid
import zipfile

BUNDLE = "com.choplab.sampler.preview.ipad"
ROOT = Path(__file__).resolve().parents[2]

def run(*args: str, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)

def read(*args: str) -> str:
    return run(*args, stdout=subprocess.PIPE).stdout.strip()

def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def verify_app(app: Path) -> dict:
    info = plistlib.loads((app / "Info.plist").read_bytes())
    if info["CFBundleIdentifier"] != BUNDLE or info.get("UIDeviceFamily") != [2]:
        raise RuntimeError("Unexpected iPad app identity")
    binary = app / info["CFBundleExecutable"]
    if read("xcrun", "lipo", "-archs", str(binary)) != "arm64":
        raise RuntimeError("Unexpected app architecture")
    if "IOSSIMULATOR" not in read("xcrun", "vtool", "-show-build", str(binary)):
        raise RuntimeError("App is not built for the simulator")
    resources = list(app.rglob("*.cvr"))
    if not resources:
        raise RuntimeError("Compose localized resources are absent from the app")
    return {"bundleId": BUNDLE, "binaryBytes": binary.stat().st_size, "binarySha256": digest(binary),
            "composeResourceFiles": len(resources), "platform": "iOS Simulator", "architecture": "arm64"}

def launch(device: str, container: Path) -> dict:
    token = str(uuid.uuid4())
    env = dict(os.environ, SIMCTL_CHILD_CHOPLAB_SIMULATOR_SMOKE_TOKEN=token)
    run("xcrun", "simctl", "launch", "--terminate-running-process", device, BUNDLE, env=env)
    receipt = container / "Documents" / ".simulator-smoke.json"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if receipt.is_file():
            value = json.loads(receipt.read_text(encoding="utf-8"))
            if value.get("token") == token:
                if value.get("padCount") != 128 or value.get("stage") != "CAPTURE":
                    raise RuntimeError("Unexpected composed editor state")
                capability = value.get("audioCapability")
                if not isinstance(capability, dict) or not capability.get("stage"):
                    raise RuntimeError("The app did not record its actual audio capability")
                return {key: value[key] for key in ("padCount", "stage", "outputAttached", "schemaVersion",
                        "driverPhase", "driverFault", "audioCapability", "livePlayback", "physicalSoundVerified")}
        time.sleep(0.5)
    raise RuntimeError("The app did not compose a ready editor within its deadline")

def verify_capability(value: dict) -> None:
    stage = value["audioCapability"]["stage"]
    if stage in ("UNEXPECTED_FAILURE", "DISABLED"):
        raise RuntimeError("Unexpected or disabled production audio driver; see app-capabilities.json")
    if value["outputAttached"] != (stage == "READY"):
        raise RuntimeError("The app lost its output route before readiness; see app-capabilities.json")

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("An ARM64 Mac with Xcode is required")
    evidence = ROOT / "dist/ipad-native-foundation-evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    derived = ROOT / "apple/build/xcode"
    run("xcodebuild", "-project", "apple/ChopLab.xcodeproj", "-scheme", "ChopLab", "-configuration", "Debug",
        "-sdk", "iphonesimulator", "-destination", "id=" + args.device,
        "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=NO", "build")
    app = derived / "Build/Products/Debug-iphonesimulator/ChopLab.app"
    report = verify_app(app)
    states = json.loads(read("xcrun", "simctl", "list", "--json", "devices"))["devices"]
    device = next(item for values in states.values() for item in values if item["udid"] == args.device)
    if device["state"] != "Booted":
        run("xcrun", "simctl", "boot", args.device)
    run("xcrun", "simctl", "bootstatus", args.device, "-b")
    run("xcrun", "simctl", "install", args.device, str(app))
    container = Path(read("xcrun", "simctl", "get_app_container", args.device, BUNDLE, "data"))
    report["firstLaunch"] = launch(args.device, container)
    (evidence / "app-capabilities.json").write_text(json.dumps({"firstLaunch": report["firstLaunch"]}, indent=2) + "\n", encoding="utf-8")
    verify_capability(report["firstLaunch"])
    run("xcrun", "simctl", "io", args.device, "screenshot", str(evidence / "ipad-editor.png"))
    run("xcrun", "simctl", "terminate", args.device, BUNDLE)
    report["relaunch"] = launch(args.device, container)
    (evidence / "app-capabilities.json").write_text(json.dumps({key: report[key] for key in ("firstLaunch", "relaunch")}, indent=2) + "\n", encoding="utf-8")
    verify_capability(report["relaunch"])
    run("xcrun", "simctl", "terminate", args.device, BUNDLE)
    package = ROOT / "dist/ChopLab-iPad-Simulator.zip"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(app.rglob("*")):
            if path.is_symlink():
                raise RuntimeError("Unexpected app symlink")
            if path.is_file():
                archive.write(path, "ChopLab.app/" + path.relative_to(app).as_posix())
    report.update({"scope": "simulator-app-build-launch-relaunch-only", "appBuilt": True,
                   "physicalDeviceTested": False, "appSigningPerformed": False,
                   "sourceRevision": read("git", "rev-parse", "HEAD"),
                   "observedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "package": {"name": package.name, "bytes": package.stat().st_size, "sha256": digest(package)}})
    (evidence / "app-results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    run("python3", "scripts/check_public_surface.py", "--archive", str(package))

if __name__ == "__main__":
    main()
