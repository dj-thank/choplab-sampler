"""Derive a Windows-only ORT JAR from the complete, immutable Maven artifact.

All Java/resources/notices and win-x64 libraries retain their exact bytes. ZIP_STORED
and fixed metadata make the derived JAR reproducible across Python/zlib platforms;
the outer distribution ZIP performs compression. No download, codec or inference change.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / "config/windows-onnxruntime.json"
NATIVE = "ai/onnxruntime/native/"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def selected_entries(source):
    rows = []
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate runtime entry")
        for name in sorted(names):
            if name.endswith("/"):
                continue
            if name.startswith("/") or ".." in Path(name).parts or "\\" in name:
                raise ValueError("Unsafe runtime entry")
            if name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")):
                raise ValueError("Signed JAR needs a separately reviewed derivation")
            if name.startswith(NATIVE) and not name.startswith(NATIVE + "win-x64/"):
                continue
            data = archive.read(name)
            rows.append((name, data))
    if not any(name.startswith(NATIVE + "win-x64/") for name, _ in rows):
        raise ValueError("Windows native runtime is missing")
    if not any(name.endswith("OnnxTensor.class") for name, _ in rows) or not any(name == "ThirdPartyNotices.txt" for name, _ in rows):
        raise ValueError("Runtime API or notices are missing")
    return rows


def entry_manifest(rows):
    return [{"name": name, "bytes": len(data), "sha256": sha256(data)} for name, data in rows]


def entries_digest(entries):
    return sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def write_jar(rows, output):
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in rows:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def derive(source, output, receipt, pins):
    source, output, receipt = map(Path, (source, output, receipt))
    if source.resolve() in (output.resolve(), receipt.resolve()) or output.resolve() == receipt.resolve():
        raise ValueError("Upstream, derived JAR and receipt must be distinct")
    upstream = pins["upstream"]
    if source.stat().st_size != upstream["bytes"] or sha256(source.read_bytes()) != upstream["sha256"]:
        raise ValueError("ORT upstream artifact does not match its reviewed pin")
    rows = selected_entries(source)
    entries = entry_manifest(rows)
    expected = pins["windows"]
    if len(entries) != expected["entryCount"] or entries_digest(entries) != expected["entriesSha256"]:
        raise ValueError("Derived runtime entries changed")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ort-derive-", dir=output.parent) as temporary:
        candidate = Path(temporary) / output.name
        write_jar(rows, candidate)
        if candidate.stat().st_size != expected["bytes"] or sha256(candidate.read_bytes()) != expected["sha256"]:
            raise ValueError("Derived runtime bytes changed")
        candidate.replace(output)
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"schemaVersion": 1, "upstream": upstream, "derived": expected,
                                   "entries": entries}, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    pins = json.loads(PINS.read_text(encoding="utf-8"))
    derive(args.input, args.output, args.receipt, pins)
    print(f"Windows ORT derivation verified: {pins['windows']['bytes']} bytes, {pins['windows']['entryCount']} unchanged entries")


if __name__ == "__main__":
    main()
