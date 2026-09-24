"""Prepare the pinned drum-separation ONNX model for the Windows app image.

Downloads the StemSplitio HT-Demucs FT drums specialist export (original
weights: Meta Demucs htdemucs_ft, MIT) from a commit-pinned HuggingFace URL and
verifies its SHA-256 before the app image is packaged.
"""
from pathlib import Path
import hashlib, json, shutil, urllib.request, argparse

MODEL_COMMIT = "55f929d333054c69ae0e829b15e8f8826a39d6eb"
MODEL_FILE = "htdemucs_ft_drums_fp16weights.onnx"
MODEL_URL = f"https://huggingface.co/StemSplitio/htdemucs-ft-drums-onnx/resolve/{MODEL_COMMIT}/{MODEL_FILE}"
# Recorded from the first verified download; the build refuses anything else.
MODEL_SHA256 = "047764dff888cfb87da917013377d4ec7a134f7419cbe486d9c339aa17975ddd"
MODEL_MIN_BYTES = 150_000_000

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    target = out / MODEL_FILE
    manifest = out / "manifest.json"
    if manifest.exists() and target.is_file():
        prior = json.loads(manifest.read_text(encoding="utf-8"))
        if prior.get("sha256") == MODEL_SHA256 and digest(target) == MODEL_SHA256:
            print("Separator model already prepared and hashed")
            return
    print(f"Downloading drum model ({MODEL_FILE}) ...")
    with urllib.request.urlopen(MODEL_URL, timeout=120) as response, target.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = digest(target)
    assert target.stat().st_size >= MODEL_MIN_BYTES, "Separator model download is truncated"
    assert actual == MODEL_SHA256, f"Separator model checksum mismatch: {actual}"
    manifest.write_text(json.dumps({
        "model": MODEL_FILE,
        "commit": MODEL_COMMIT,
        "url": MODEL_URL,
        "sha256": actual,
        "bytes": target.stat().st_size,
        "stems": ["drums", "bass", "other", "vocals"],
        "specialist": "drums",
        "sampleRate": 44100,
        "segment": 343980,
        "overlap": 85995,
    }, indent=2) + "\n", encoding="utf-8")
    print("Separator model prepared and hashed")

if __name__ == "__main__":
    main()
