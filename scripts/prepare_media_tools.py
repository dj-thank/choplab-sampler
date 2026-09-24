"""Prepare pinned public Windows import tools, with upstream checksums and notices."""
from pathlib import Path
import hashlib, json, shutil, urllib.request, zipfile, argparse

def download(url, target):
    if not target.exists():
        with urllib.request.urlopen(url, timeout=60) as response, target.open("wb") as output:
            shutil.copyfileobj(response, output)

def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024*1024),b""):h.update(block)
    return h.hexdigest()

TOOL_VERSIONS = {"ytDlp": "2026.08.19", "ffmpeg": "8.1.2", "node": "24.19.0"}
REQUIRED_FILES = {"yt-dlp.exe", "ffmpeg.exe", "ffprobe.exe", "node.exe", "yt-dlp-LICENSE.txt", "FFmpeg-LICENSE.txt", "Node-LICENSE.txt"}


def cache_valid(out):
    try:
        prior = json.loads((out / "runtime.json").read_text(encoding="utf-8"))
        hashes = prior.get("sha256", {})
        return (all(prior.get(key) == value for key, value in TOOL_VERSIONS.items())
                and set(hashes) == REQUIRED_FILES
                and all(not (out/name).is_symlink() and (out/name).is_file()
                        and digest(out/name) == value for name, value in hashes.items()))
    except (OSError, ValueError, TypeError, AttributeError):
        return False


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--out",type=Path,required=True);args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    manifest=out/"runtime.json"
    if cache_valid(out):return
    release="https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/"
    sums=urllib.request.urlopen(release+"SHA2-256SUMS",timeout=30).read().decode("utf-8")
    expected=next(line.split()[0] for line in sums.splitlines() if line.split()[-1].lstrip("*")=="yt-dlp.exe")
    download(release+"yt-dlp.exe",out/"yt-dlp.exe")
    if digest(out/"yt-dlp.exe")!=expected:raise ValueError("yt-dlp checksum mismatch")
    # Never copy ambient PATH tools: notices and hashes describe these exact upstream versions.
    url="https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip"
    archive=out/"ffmpeg-package.zip";download(url,archive)
    expected_ff=urllib.request.urlopen(url+".sha256",timeout=30).read().decode().split()[0]
    if digest(archive)!=expected_ff:raise ValueError("FFmpeg checksum mismatch")
    with zipfile.ZipFile(archive) as z:
        for leaf in ["ffmpeg.exe","ffprobe.exe","LICENSE"]:
            candidates=[n for n in z.namelist() if n.endswith("/"+leaf)]
            if len(candidates)!=1:raise ValueError("Ambiguous FFmpeg archive member")
            target=out/("FFmpeg-LICENSE.txt" if leaf=="LICENSE" else leaf)
            with z.open(candidates[0]) as source,target.open("wb") as dest:shutil.copyfileobj(source,dest)
    archive.unlink()
    version="v24.19.0"
    archive=out/"node-package.zip";url=f"https://nodejs.org/dist/{version}/node-{version}-win-x64.zip"
    download(url,archive)
    sums=urllib.request.urlopen(f"https://nodejs.org/dist/{version}/SHASUMS256.txt",timeout=30).read().decode()
    expected_node=next(line.split()[0] for line in sums.splitlines() if line.split()[-1]==f"node-{version}-win-x64.zip")
    if digest(archive)!=expected_node:raise ValueError("Node checksum mismatch")
    with zipfile.ZipFile(archive) as z:
        with z.open(f"node-{version}-win-x64/node.exe") as source,(out/"node.exe").open("wb") as dest:shutil.copyfileobj(source,dest)
    archive.unlink()
    for url,name in [("https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/LICENSE","yt-dlp-LICENSE.txt"),("https://raw.githubusercontent.com/nodejs/node/v24.19.0/LICENSE","Node-LICENSE.txt")]:
        download(url,out/name)
    manifest.write_text(json.dumps({**TOOL_VERSIONS,"sources":[release,"https://www.gyan.dev/ffmpeg/builds/","https://nodejs.org/"],"sha256":{name:digest(out/name) for name in sorted(REQUIRED_FILES)}},indent=2)+"\n",encoding="utf-8")
    print("Windows import tools prepared and hashed")
if __name__=="__main__":main()
