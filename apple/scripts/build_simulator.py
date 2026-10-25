#!/usr/bin/env python3
"""Build the actual app, launch twice on the selected simulator, and bind package bytes to source."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import plistlib
import re
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
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(64 * 1024), b""):
            result.update(block)
    return result.hexdigest()

def canonical_notices() -> dict:
    spec = importlib.util.spec_from_file_location("choplab_source_notices", ROOT / "scripts/prepare_source_notices.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.create_files(ROOT, "all", require_committed=True)

def verify_notices(app: Path) -> dict:
    root = app / "source-notices"
    index_path = root / "SOURCE-INDEX.json"
    expected = canonical_notices()
    paths = list(root.rglob("*"))
    if root.is_symlink() or any(path.is_symlink() for path in paths):
        raise RuntimeError("App notices contain a symlink")
    actual = {path.relative_to(root).as_posix(): path for path in paths if path.is_file()}
    if set(actual) != set(expected) or any(actual[name].read_bytes() != data for name, data in expected.items()):
        raise RuntimeError("App notices differ from the canonical committed source")
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if index.get("revision") != read("git", "rev-parse", "HEAD") or index.get("hasLocalChanges") is not False:
        raise RuntimeError("App notices do not match the committed source revision")
    if any(index.get(key) is not False for key in ("noticeCoverageComplete", "sourcePublicationVerified", "publicPass")):
        raise RuntimeError("App notices cannot assert unresolved distribution acceptance")
    files = index.get("files", [])
    if not files:
        raise RuntimeError("App notice inventory is empty")
    for row in files:
        target = root / row["path"]
        if not target.resolve().is_relative_to(root.resolve()) or target.is_symlink() or not target.is_file():
            raise RuntimeError("Invalid app notice path")
        if target.stat().st_size != row["bytes"] or digest(target) != row["sha256"]:
            raise RuntimeError("App notice bytes differ")
    return {"noticeFiles": len(files), "noticeIndexSha256": digest(index_path), "noticeCoverageComplete": False}

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
            "composeResourceFiles": len(resources), "platform": "iOS Simulator", "architecture": "arm64",
            **verify_notices(app)}

def launch(device: str, container: Path, profile: str, phase: str) -> dict:
    token = str(uuid.uuid4())
    env = dict(os.environ, SIMCTL_CHILD_CHOPLAB_SIMULATOR_SMOKE_TOKEN=token,
               SIMCTL_CHILD_CHOPLAB_APP_ACCEPTANCE_PROFILE=profile,
               SIMCTL_CHILD_CHOPLAB_APP_ACCEPTANCE_PHASE=phase)
    run("xcrun", "simctl", "launch", "--terminate-running-process", device, BUNDLE, env=env)
    receipt = container / "Documents" / ".simulator-smoke.json"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        failure = container / "Documents" / ".simulator-smoke-error.json"
        if failure.is_file() and json.loads(failure.read_text(encoding="utf-8")).get("token") == token:
            raise RuntimeError("Production app studio acceptance failed during " + phase)
        if receipt.is_file():
            value = json.loads(receipt.read_text(encoding="utf-8"))
            if value.get("token") == token:
                if value.get("padCount") != 128 or value.get("stage") != "CAPTURE":
                    raise RuntimeError("Unexpected composed editor state")
                capability = value.get("audioCapability")
                if not isinstance(capability, dict) or not capability.get("stage"):
                    raise RuntimeError("The app did not record its actual audio capability")
                return {key: value[key] for key in ("padCount", "stage", "outputAttached", "schemaVersion",
                        "driverPhase", "driverFault", "audioCapability", "livePlayback", "physicalSoundVerified", "studioAcceptance")}
        time.sleep(0.5)
    raise RuntimeError("The app did not compose a ready editor within its deadline")

def verify_capability(value: dict) -> None:
    stage = value["audioCapability"]["stage"]
    if stage in ("UNEXPECTED_FAILURE", "DISABLED"):
        raise RuntimeError("Unexpected or disabled production audio driver; see app-capabilities.json")
    if value["outputAttached"] != (stage == "READY"):
        raise RuntimeError("The app lost its output route before readiness; see app-capabilities.json")


def verify_studio(value: dict, phase: str) -> None:
    acceptance = value.get("studioAcceptance", {})
    expected = {"phase": phase, "originalBytesPreserved": True, "songSourceHandIndependent": True,
                "exportBits": 24, "exportFrames": 96000, "autosaveFlushed": True, "archiveVerified": True,
                "newUndoRedoAfterRestart": phase == "relaunch", "livePlayback": "verified-real-output",
                "physicalSoundVerified": False}
    if any(acceptance.get(key) != expected_value for key, expected_value in expected.items()):
        raise RuntimeError("Incomplete app studio acceptance")
    delta = acceptance.get("consumedFramesDelta")
    original_hash = acceptance.get("originalHash")
    if not isinstance(original_hash, str) or re.fullmatch(r"[0-9a-f]{64}", original_hash) is None:
        raise RuntimeError("Original content hash is missing or invalid")
    if not isinstance(delta, int) or isinstance(delta, bool) or delta <= 1024:
        raise RuntimeError("Real AVAudioEngine buffer consumption was not observed")
    if value.get("livePlayback") != "verified-real-output" or value.get("physicalSoundVerified") is not False:
        raise RuntimeError("App playback receipt has an invalid evidence scope")

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
        "-derivedDataPath", str(derived), "ARCHS=arm64", "ONLY_ACTIVE_ARCH=YES", "CODE_SIGNING_ALLOWED=NO", "build")
    app = derived / "Build/Products/Debug-iphonesimulator/ChopLab.app"
    report = verify_app(app)
    states = json.loads(read("xcrun", "simctl", "list", "--json", "devices"))["devices"]
    device = next(item for values in states.values() for item in values if item["udid"] == args.device)
    if device["state"] != "Booted":
        run("xcrun", "simctl", "boot", args.device)
    run("xcrun", "simctl", "bootstatus", args.device, "-b")
    run("xcrun", "simctl", "install", args.device, str(app))
    container = Path(read("xcrun", "simctl", "get_app_container", args.device, BUNDLE, "data"))
    profile = uuid.uuid4().hex
    report["firstLaunch"] = launch(args.device, container, profile, "first")
    (evidence / "app-capabilities.json").write_text(json.dumps({"firstLaunch": report["firstLaunch"]}, indent=2) + "\n", encoding="utf-8")
    verify_capability(report["firstLaunch"])
    verify_studio(report["firstLaunch"], "first")
    run("xcrun", "simctl", "io", args.device, "screenshot", str(evidence / "ipad-editor.png"))
    run("xcrun", "simctl", "terminate", args.device, BUNDLE)
    report["relaunch"] = launch(args.device, container, profile, "relaunch")
    (evidence / "app-capabilities.json").write_text(json.dumps({key: report[key] for key in ("firstLaunch", "relaunch")}, indent=2) + "\n", encoding="utf-8")
    verify_capability(report["relaunch"])
    verify_studio(report["relaunch"], "relaunch")
    if report["firstLaunch"]["studioAcceptance"]["originalHash"] != report["relaunch"]["studioAcceptance"]["originalHash"]:
        raise RuntimeError("Original bytes changed across actual app restart")
    run("xcrun", "simctl", "terminate", args.device, BUNDLE)
    package = ROOT / "dist/ChopLab-iPad-Simulator.zip"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(app.rglob("*")):
            if path.is_symlink():
                raise RuntimeError("Unexpected app symlink")
            if path.is_file():
                archive.write(path, "ChopLab.app/" + path.relative_to(app).as_posix())
    report.update({"scope": "simulator-app-studio-playback-export-save-relaunch", "appBuilt": True,
                   "physicalDeviceTested": False, "appSigningPerformed": False,
                   "sourceRevision": read("git", "rev-parse", "HEAD"),
                   "observedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "package": {"name": package.name, "bytes": package.stat().st_size, "sha256": digest(package)}})
    (evidence / "app-results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    run("python3", "scripts/check_public_surface.py", "--archive", str(package))

if __name__ == "__main__":
    main()
