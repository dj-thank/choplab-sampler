#!/usr/bin/env python3
"""Run the existing codec fixture plus DLL/filter/TLS/yt-dlp checks on Windows.

Only synthetic loopback content is used. This does not observe an audio endpoint,
provider, human listening, signing, or the whole application's editing workflow.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.server
import ipaddress
import json
import math
from pathlib import Path
import platform
import re
import re
import ssl
import struct
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts import windows_audio_runtime
from scripts.prepare_media_tools import cache_valid


# FFmpeg 8.1.2 release archive pinned by config/windows-ffmpeg-audio.json.
# configure uses ff_<component>_<decoder|encoder>; the CLI uses that FFCodec's .p.name.
# Entries below were checked in this exact archive, with registration locations under libavcodec/.
CODEC_NAMES_SOURCE_SHA256 = "464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
CODEC_NAME_ALIASES = {
    "decoder": {
        "acelp_kelvin": "acelp.kelvin",  # g729dec.c:767
        "adpcm_g722": "g722",          # g722dec.c:144
        "adpcm_g726": "g726",          # g726.c:505
        "adpcm_g726le": "g726le",      # g726.c:519
        "atrac3p": "atrac3plus",        # atrac3plusdec.c:420
        "atrac3pal": "atrac3plusal",    # atrac3plusdec.c:433
        "ffwavesynth": "wavesynth",    # ffwavesynth.c:463
        "interplay_acm": "interplayacm",  # interplayacm.c:649
        "ra_144": "real_144",          # ra144dec.c:129
        "ra_288": "real_288",          # ra288.c:197
    },
    "encoder": {
        "adpcm_g722": "g722",          # g722enc.c:374
        "adpcm_g726": "g726",          # g726.c:403
        "adpcm_g726le": "g726le",      # g726.c:420
        "ra_144": "real_144",          # ra144enc.c:538
        "sonic_ls": "sonicls",         # sonic.c:817
    },
}


def run(tool, *args, success=True):
    result = subprocess.run([str(tool), *map(str, args)], capture_output=True, timeout=90)
    if (result.returncode == 0) != success:
        raise AssertionError(result.stderr.decode(errors="replace")[-2000:])
    return result.stdout, result.stderr


def codec_names(text):
    return {parts[1] for line in text.splitlines() if len(parts := line.split()) >= 2
            and re.fullmatch(r"[VAS][F.][S.][X.][B.][D.]", parts[0]) and parts[1] != "="}


def codec_requirements(profile, kind):
    if profile["ffmpeg"]["sha256"] != CODEC_NAMES_SOURCE_SHA256:
        raise ValueError("Codec name mapping requires the reviewed FFmpeg source identity")
    prefix = "--enable-" + kind + "="
    configured = {name for value in profile["configure"] if value.startswith(prefix)
                  for name in value[len(prefix):].split(",") if name}
    if not configured:
        raise ValueError("Missing configured " + kind + " requirements")
    aliases = CODEC_NAME_ALIASES[kind]
    return {name: aliases.get(name, name) for name in sorted(configured)}


def inventory(ffmpeg, profile):
    result = {"codecNameSourceSha256": CODEC_NAMES_SOURCE_SHA256}
    for kind in ("decoder", "encoder"):
        names = codec_requirements(profile, kind)
        text = run(ffmpeg, "-hide_banner", "-" + kind + "s")[0].decode()
        available = codec_names(text)
        missing = {configured: public for configured, public in names.items() if public not in available}
        if missing:
            raise AssertionError(f"Missing {kind}: {sorted(missing.values())}; configure names: {sorted(missing)}")
        result[kind + "s"] = sorted(available)
        result[kind + "Aliases"] = {configured: public for configured, public in names.items() if configured != public}
    protocols = {line.strip() for line in run(ffmpeg, "-hide_banner", "-protocols")[0].decode().splitlines()}
    # The public CLI names differ from configure's libsrt/libssh/libzmq names.
    required_protocols = {"file", "pipe", "http", "https", "tls", "tcp", "udp", "srt", "sftp", "zmq"}
    if required_protocols - protocols:
        raise AssertionError("Missing public protocol: " + ", ".join(sorted(required_protocols - protocols)))
    result["requiredProtocols"] = sorted(required_protocols)
    filters = run(ffmpeg, "-hide_banner", "-filters")[0].decode()
    # FFmpeg 8.1 removed the command-support column: actual -filters flags are two characters.
    # Accept the earlier three-column form too; require valid flags and a registered name.
    available = {parts[1] for line in filters.splitlines() if len(parts := line.split()) >= 3
                 and re.fullmatch(r"[TS.]{2}|[TSC.]{3}", parts[0])}
    required_filters = {"aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat", "amovie", "azmq"}
    if required_filters - available:
        raise AssertionError("Missing audio filter: " + ", ".join(sorted(required_filters - available)))
    result["requiredFilters"] = sorted(required_filters)
    return result


class FixtureServer:
    def __init__(self, payload, certificate=None, key=None):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/fixture.flac":
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "audio/flac")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_):
                pass
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        if certificate:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(certificate, key)
            self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.url = f"{'https' if certificate else 'http'}://127.0.0.1:{self.server.server_port}/fixture.flac"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        if self.thread.is_alive():
            raise AssertionError("Owned fixture server did not stop")


def temporary_certificate(work):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "ChopLab synthetic loopback")])
    now = datetime.now(timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
                   .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
                   .not_valid_after(now + timedelta(hours=1))
                   .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
                   .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
                   .sign(key, hashes.SHA256()))
    certificate_path, key_path = work / "loopback.crt", work / "ephemeral.key"
    certificate_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    key_path.chmod(0o600)
    return certificate_path, key_path


def check_tls(ffmpeg, source, work):
    certificate, key = temporary_certificate(work)
    decode = ["-map", "0:a:0", "-vn", "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"]
    expected = run(ffmpeg, "-nostdin", "-v", "error", "-i", source, *decode)[0]
    try:
        with FixtureServer(source.read_bytes(), certificate, key) as server:
            actual = run(ffmpeg, "-nostdin", "-v", "error", "-tls_verify", "1", "-ca_file", certificate, "-i", server.url, *decode)[0]
            if actual != expected:
                raise AssertionError("TLS delivery changed decoded PCM")
            error = run(ffmpeg, "-nostdin", "-v", "error", "-tls_verify", "1", "-i", server.url, *decode, success=False)[1].decode(errors="replace").lower()
            if not any(value in error for value in ("certificate", "tls", "verify")):
                raise AssertionError("Untrusted TLS failed for a different reason")
        return {"verifiedCaDeliveryExact": True, "untrustedCaRejected": True, "pcmSha256": hashlib.sha256(expected).hexdigest()}
    finally:
        key.unlink(missing_ok=True)
        certificate.unlink(missing_ok=True)


def check_rubberband(ffmpeg, source):
    output = run(ffmpeg, "-nostdin", "-v", "error", "-i", source, "-af", "rubberband=tempo=1.25:pitch=1.059463094359",
                 "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1")[0]
    frames = len(output) // 8
    samples = [value[0] for value in struct.iter_unpack("<f", output)]
    if abs(frames - 76800) > 2048 or not samples or not all(math.isfinite(value) for value in samples):
        raise AssertionError("Rubber Band did not produce bounded finite audio")
    if math.sqrt(sum(value * value for value in samples) / len(samples)) < .03:
        raise AssertionError("Rubber Band produced silence")
    return {"tempo": 1.25, "pitch": 1.059463094359, "frames": frames, "finiteNonSilent": True, "listeningVerified": False}


def check_ytdlp(tools, source, work):
    output = work / "ytdlp"
    output.mkdir()
    with FixtureServer(source.read_bytes()) as server:
        run(tools / "yt-dlp.exe", "--ignore-config", "--no-playlist", "--no-cache-dir", "--no-mtime",
            "--ffmpeg-location", tools, "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0",
            "--output", output / "synthetic.%(ext)s", server.url)
    converted = output / "synthetic.mp3"
    info = json.loads(run(tools / "ffprobe.exe", "-v", "error", "-show_streams", "-of", "json", converted)[0])["streams"][0]
    if info["codec_name"] != "mp3" or int(info["sample_rate"]) != 48000 or info["channels"] != 2:
        raise AssertionError("yt-dlp's actual FFmpeg postprocessor changed the expected format")
    return {"localDownloadAndFfmpegMp3Postprocess": True, "bytes": converted.stat().st_size, "providerVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", type=Path, required=True, help="The actual app-image tools directory")
    parser.add_argument("--work", type=Path, required=True, help="A new isolated synthetic fixture directory")
    args = parser.parse_args()
    if platform.system() != "Windows":
        raise SystemExit("This entry point requires actual Windows; a host probe is a separate check")
    tools, work = args.tools.resolve(), args.work.resolve()
    if work.exists():
        raise ValueError("Use a new fixture directory to preserve all existing data")
    profile = windows_audio_runtime.load_profile()
    if not cache_valid(tools, profile):
        raise ValueError("The packaged tools do not match the admitted source/artifact pins")
    fixture = Path(__file__).with_name("native_audio_codecs.py")
    if not fixture.is_file():
        raise ValueError("The existing native_audio_codecs.py fixture must be integrated first")
    work.mkdir(parents=True)
    ffmpeg, ffprobe = tools / "ffmpeg.exe", tools / "ffprobe.exe"
    inventories = inventory(ffmpeg, profile)
    subprocess.run([sys.executable, str(fixture), "--ffmpeg", str(ffmpeg), "--ffprobe", str(ffprobe),
                    "--work-dir", str(work / "codecs"), "--receipt", str(work / "codecs.json")], check=True)
    source = work / "codecs/precision.flac"
    original = hashlib.sha256(source.read_bytes()).hexdigest()
    receipt = {"status": "LOCAL_PASS", "scope": "actual-windows-synthetic-native-audio-tools", "platform": platform.platform(),
               "profileInputsSha256": windows_audio_runtime.inputs_hash(profile), "ffmpegSha256": hashlib.sha256(ffmpeg.read_bytes()).hexdigest(),
               "ffprobeSha256": hashlib.sha256(ffprobe.read_bytes()).hexdigest(), "inventories": inventories,
               "codecReceipt": "codecs.json", "tls": check_tls(ffmpeg, source, work),
               "rubberband": check_rubberband(ffmpeg, source), "ytdlp": check_ytdlp(tools, source, work),
               "audioEndpointVerified": False, "providerVerified": False, "wholeApplicationVerified": False,
               "signingVerified": False, "publicDistributionVerified": False, "humanVerified": False}
    if hashlib.sha256(source.read_bytes()).hexdigest() != original:
        raise AssertionError("Source fixture bytes changed")
    (work / "windows-audio-tools.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"status": "LOCAL_PASS", "scope": receipt["scope"], "receipt": str(work / "windows-audio-tools.json")}))


if __name__ == "__main__":
    main()
