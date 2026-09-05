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

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--out",type=Path,required=True);args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    manifest=out/"runtime.json"
    if manifest.exists():
        prior=json.loads(manifest.read_text(encoding="utf-8"))
        if all((out/name).is_file() and digest(out/name)==value for name,value in prior["sha256"].items()):return
    release="https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/"
    sums=urllib.request.urlopen(release+"SHA2-256SUMS",timeout=30).read().decode("utf-8")
    expected=next(line.split()[0] for line in sums.splitlines() if line.split()[-1].lstrip("*")=="yt-dlp.exe")
    download(release+"yt-dlp.exe",out/"yt-dlp.exe")
    assert digest(out/"yt-dlp.exe")==expected, "yt-dlp checksum mismatch"
    ffmpeg=shutil.which("ffmpeg.exe") or shutil.which("ffmpeg")
    node=shutil.which("node.exe")
    if ffmpeg and Path(ffmpeg).is_file():
        ffdir=Path(ffmpeg).resolve().parent
        for name in ["ffmpeg.exe","ffprobe.exe"]:shutil.copyfile(ffdir/name,out/name)
        license_path=ffdir.parent/"LICENSE"
        if license_path.exists():shutil.copyfile(license_path,out/"FFmpeg-LICENSE.txt")
    else:
        url="https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip"
        archive=out/"ffmpeg-package.zip";download(url,archive)
        expected_ff=urllib.request.urlopen(url+".sha256",timeout=30).read().decode().split()[0]
        assert digest(archive)==expected_ff, "FFmpeg checksum mismatch"
        with zipfile.ZipFile(archive) as z:
            for leaf in ["ffmpeg.exe","ffprobe.exe","LICENSE"]:
                member=next(n for n in z.namelist() if n.endswith("/"+leaf))
                target=out/("FFmpeg-LICENSE.txt" if leaf=="LICENSE" else leaf)
                with z.open(member) as source,target.open("wb") as dest:shutil.copyfileobj(source,dest)
        archive.unlink()
    if node:
        shutil.copyfile(node,out/"node.exe")
    else:
        version="v24.19.0"
        archive=out/"node-package.zip";url=f"https://nodejs.org/dist/{version}/node-{version}-win-x64.zip"
        download(url,archive)
        sums=urllib.request.urlopen(f"https://nodejs.org/dist/{version}/SHASUMS256.txt",timeout=30).read().decode()
        expected_node=next(line.split()[0] for line in sums.splitlines() if line.split()[-1]==archive.name.replace("node-package",f"node-{version}-win-x64"))
        assert digest(archive)==expected_node,"Node checksum mismatch"
        with zipfile.ZipFile(archive) as z:
            with z.open(f"node-{version}-win-x64/node.exe") as source,(out/"node.exe").open("wb") as dest:shutil.copyfileobj(source,dest)
        archive.unlink()
    for url,name in [("https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/LICENSE","yt-dlp-LICENSE.txt"),("https://raw.githubusercontent.com/nodejs/node/v24.19.0/LICENSE","Node-LICENSE.txt")]:
        download(url,out/name)
    manifest.write_text(json.dumps({"ytDlp":"2026.08.19","sources":[release,"https://www.gyan.dev/ffmpeg/builds/","https://nodejs.org/"],"sha256":{p.name:digest(p) for p in sorted(out.iterdir()) if p.name!="runtime.json"}},indent=2)+"\n",encoding="utf-8")
    print("Windows import tools prepared and hashed")
if __name__=="__main__":main()
