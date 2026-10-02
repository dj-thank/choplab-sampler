"""Prepare pinned public Windows import tools, with upstream checksums and notices."""
from pathlib import Path
import hashlib, http.client, json, shutil, time, urllib.error, urllib.request, zipfile, argparse
try:
    from scripts import windows_audio_runtime
except ModuleNotFoundError:
    import windows_audio_runtime

# Upstream mirrors answer these while overloaded or rate limiting; anything else is a real failure.
TRANSIENT_HTTP = {408, 425, 429, 500, 502, 503, 504}
TRANSIENT_NETWORK = (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError)


def retrying(action, attempts=4, wait=5.0, sleep=time.sleep):
    """Runs action(), retrying only transient HTTP and network failures with growing waits."""
    for attempt in range(1, attempts + 1):
        try:
            return action()
        except urllib.error.HTTPError as error:
            if error.code not in TRANSIENT_HTTP or attempt == attempts:
                raise
        except TRANSIENT_NETWORK:
            if attempt == attempts:
                raise
        sleep(wait * attempt)


def fetch(url, timeout=30, opener=urllib.request.urlopen, **retry):
    def read():
        with opener(url, timeout=timeout) as response:
            return response.read()
    return retrying(read, **retry)


def download(url, target, opener=urllib.request.urlopen, **retry):
    """Streams to a sibling .part file, so an interrupted download never looks complete."""
    if target.exists():
        return
    partial = target.with_name(target.name + ".part")

    def attempt():
        try:
            with opener(url, timeout=60) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    retrying(attempt, **retry)

def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024*1024),b""):h.update(block)
    return h.hexdigest()

QUICKJS_PIN = json.loads((Path(__file__).resolve().parents[1] / "config/windows-quickjs.json").read_text(encoding="utf-8"))
TOOL_VERSIONS = {"ytDlp": "2026.08.19", "ffmpeg": "8.1.2", "quickjs": QUICKJS_PIN["version"]}
REQUIRED_FILES = {"yt-dlp.exe", "ffmpeg.exe", "ffprobe.exe", "qjs.exe", "yt-dlp-LICENSE.txt", "FFmpeg-LICENSE.txt", "QuickJS-LICENSE.txt"}


def quickjs_valid(out):
    return all((out / pin["filename"]).is_file()
               and not (out / pin["filename"]).is_symlink()
               and (out / pin["filename"]).stat().st_size == pin["bytes"]
               and digest(out / pin["filename"]) == pin["sha256"]
               for pin in (QUICKJS_PIN["windows"], QUICKJS_PIN["license"]))


def cache_valid(out, audio_profile=None):
    try:
        prior = json.loads((out / "runtime.json").read_text(encoding="utf-8"))
        hashes = prior.get("sha256", {})
        audio_files = windows_audio_runtime.pinned_files(audio_profile) if audio_profile else {}
        audio_hash = windows_audio_runtime.inputs_hash(audio_profile) if audio_profile else None
        return (all(prior.get(key) == value for key, value in TOOL_VERSIONS.items())
                and prior.get("quickjsSource") == QUICKJS_PIN
                and prior.get("ffmpegAudioInputsSha256") == audio_hash
                and all((out / name).is_file() and not (out / name).is_symlink()
                        and (out / name).stat().st_size == pin["bytes"]
                        and digest(out / name) == pin["sha256"] for name, pin in audio_files.items())
                and quickjs_valid(out)
                and not any((out/name).exists() for name in ("node.exe", "Node-LICENSE.txt", "node-package.zip"))
                and set(hashes) == REQUIRED_FILES | set(audio_files)
                and all(not (out/name).is_symlink() and (out/name).is_file()
                        and digest(out/name) == value for name, value in hashes.items()))
    except (OSError, ValueError, TypeError, AttributeError):
        return False


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--out",type=Path,required=True)
    parser.add_argument("--ffmpeg-audio-runtime", type=Path, help="Exact pinned candidate directory; Windows execution is a separate gate")
    args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    manifest=out/"runtime.json"
    audio_profile = windows_audio_runtime.load_profile() if args.ffmpeg_audio_runtime else None
    audio_files = windows_audio_runtime.validate_directory(args.ffmpeg_audio_runtime, audio_profile) if audio_profile else {}
    if cache_valid(out, audio_profile):return
    if manifest.exists():
        prior = json.loads(manifest.read_text())
        if bool(prior.get("ffmpegAudioInputsSha256")) != bool(audio_profile):
            raise ValueError("Use a separate generated tool directory when switching FFmpeg variants")
    release="https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/"
    sums=fetch(release+"SHA2-256SUMS").decode("utf-8")
    expected=next(line.split()[0] for line in sums.splitlines() if line.split()[-1].lstrip("*")=="yt-dlp.exe")
    download(release+"yt-dlp.exe",out/"yt-dlp.exe")
    if digest(out/"yt-dlp.exe")!=expected:raise ValueError("yt-dlp checksum mismatch")
    # Never copy ambient PATH tools: notices and hashes describe these exact upstream versions.
    if audio_profile:
        for name in audio_files:
            target = out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(args.ffmpeg_audio_runtime / name, target)
    else:
        url="https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip"
        archive=out/"ffmpeg-package.zip";download(url,archive)
        expected_ff=fetch(url+".sha256").decode().split()[0]
        if digest(archive)!=expected_ff:raise ValueError("FFmpeg checksum mismatch")
        with zipfile.ZipFile(archive) as z:
            for leaf in ["ffmpeg.exe","ffprobe.exe","LICENSE"]:
                candidates=[n for n in z.namelist() if n.endswith("/"+leaf)]
                if len(candidates)!=1:raise ValueError("Ambiguous FFmpeg archive member")
                target=out/("FFmpeg-LICENSE.txt" if leaf=="LICENSE" else leaf)
                with z.open(candidates[0]) as source,target.open("wb") as dest:shutil.copyfileobj(source,dest)
        archive.unlink()
    for pin in (QUICKJS_PIN["windows"], QUICKJS_PIN["license"]):
        download(pin["url"], out / pin["filename"])
    if not quickjs_valid(out):raise ValueError("QuickJS upstream binary/license identity mismatch")
    download("https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/LICENSE",out/"yt-dlp-LICENSE.txt")
    # This directory is generated by this tool; remove only the previous known
    # Node payload after the replacement and its notice have been verified.
    for name in ("node.exe", "Node-LICENSE.txt", "node-package.zip"):
        (out / name).unlink(missing_ok=True)
    manifest.write_text(json.dumps({**TOOL_VERSIONS,"quickjsSource":QUICKJS_PIN,
        "ffmpegAudioInputsSha256": windows_audio_runtime.inputs_hash(audio_profile) if audio_profile else None,
        "sources":[release,audio_profile["ffmpeg"]["url"] if audio_profile else "https://www.gyan.dev/ffmpeg/builds/",QUICKJS_PIN["upstreamRelease"]],
        "sha256":{name:digest(out/name) for name in sorted(REQUIRED_FILES | set(audio_files))}},indent=2)+"\n",encoding="utf-8")
    print("Windows import tools prepared and hashed")
if __name__=="__main__":main()
