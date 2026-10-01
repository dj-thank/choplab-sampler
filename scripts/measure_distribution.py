"""Measure exact distribution bytes. This is a size gate, never signing/device/public acceptance."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
import zipfile


def measure(path, kind):
    android = kind in {"android-release", "android-next-size-probe"}
    totals = defaultdict(lambda: {"expandedBytes": 0, "compressedBytes": 0})
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 4096 or len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("Invalid distribution entry count")
        for entry in entries:
            parts = entry.filename.split("/")
            group = parts[0] if android else (parts[1] if len(parts) > 2 else "root")
            totals[group]["expandedBytes"] += entry.file_size
            totals[group]["compressedBytes"] += entry.compress_size
        if android:
            abis = sorted({entry.filename.split("/")[1] for entry in entries if entry.filename.startswith("lib/") and not entry.is_dir()})
            if abis != ["arm64-v8a"]:
                raise ValueError("Android size gate must be measured on exactly arm64-v8a")
        else:
            abis = []
    size = path.stat().st_size
    limit = 50_000_000 if android else 150_000_000 if kind == "windows-next-candidate" else 200_000_000
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"schemaVersion": 1, "kind": kind, "filename": path.name, "bytes": size, "sha256": digest,
            "sizeGate": "PASS" if size <= limit else "FAIL", "limitBytes": limit,
            "finalTargetBytes": limit if android else 150_000_000,
            "candidateOnly": kind in {"android-next-size-probe", "windows-next-candidate"},
            "expandedBytes": sum(entry.file_size for entry in entries), "entries": len(entries), "abis": abis,
            "components": dict(sorted(totals.items())),
            "boundary": "Size only; signing, codec/runtime/device, first-use downloads, cache and public release are separate gates."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--kind", choices=("android-release", "android-next-size-probe", "windows", "windows-next-candidate"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--measurement-only", action="store_true", help="Record FAIL without failing this measurement command")
    args = parser.parse_args()
    if args.archive.resolve() == args.output.resolve():
        raise ValueError("Report must not overwrite the distribution")
    report = measure(args.archive, args.kind)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{args.kind}: {report['bytes']} bytes / {report['limitBytes']} limit: {report['sizeGate']}")
    if not args.measurement_only and report["sizeGate"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
