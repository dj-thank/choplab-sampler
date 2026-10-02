#!/usr/bin/env python3
"""Run pinned yt-dlp's real QuickJS/EJS bridge without a network or media provider.

Use an isolated environment with yt-dlp[default]==2026.8.19. On Windows pass
the qjs.exe from the final app image, and retain the JSON receipt with that ZIP.
The synthetic functions below are our own fixtures, not a provider's player.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import socket
import time


class QuietLogger:
    prefix = "offline-EJS-fixture"

    def debug(self, *_args, **_kwargs):
        pass

    info = debug

    def warning(self, message, *_args, **_kwargs):
        raise AssertionError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qjs", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    import yt_dlp
    from yt_dlp.extractor.youtube import YoutubeIE
    from yt_dlp.extractor.youtube.jsc._builtin.quickjs import QuickJSJCP
    from yt_dlp.extractor.youtube.jsc.provider import JsChallengeRequest, JsChallengeType, NChallengeInput, SigChallengeInput
    from yt_dlp.version import __version__

    if __version__ != "2026.08.19":
        raise ValueError("Fixture must use the packaged yt-dlp version")
    qjs = args.qjs.resolve(strict=True)
    if platform.system() == "Windows":
        pin = json.loads((Path(__file__).resolve().parents[2] / "config/windows-quickjs.json").read_text())["windows"]
        if qjs.stat().st_size != pin["bytes"] or hashlib.sha256(qjs.read_bytes()).hexdigest() != pin["sha256"]:
            raise ValueError("The app-image QuickJS binary differs from its upstream pin")

    def no_network(*_args, **_kwargs):
        raise AssertionError("An offline fixture attempted a network operation")

    socket.create_connection = no_network
    socket.socket.connect = no_network
    started = time.monotonic()
    with yt_dlp.YoutubeDL({"quiet": True, "cachedir": False, "remote_components": [],
                         "js_runtimes": {"quickjs": {"path": str(qjs)}}}) as downloader:
        provider = QuickJSJCP(YoutubeIE(downloader), QuietLogger(), {})
        if not provider.is_available() or provider.runtime_info.name != "quickjs-ng" or provider.runtime_info.version != "0.17.0":
            raise AssertionError("Pinned QuickJS-NG was not recognized by the actual EJS adapter")
        # The normal provider validates the installed EJS script versions/hashes.
        # Use its real construction and subprocess paths with synthetic inputs.
        requests = [
            JsChallengeRequest(JsChallengeType.N, NChallengeInput("https://example.invalid/offline", ["EarthSong", "音ひろい", ""])),
            JsChallengeRequest(JsChallengeType.SIG, SigChallengeInput("https://example.invalid/offline", ["abcdef", "12345"])),
        ]
        functions = """
            _result.n = value => Array.from(value).reverse().join('') + ':' + BigInt(37).toString();
            _result.sig = value => { const out = new Uint8Array(Array.from(value, x => x.charCodeAt(0)));
                return JSON.stringify(Array.from(out).filter((_, i) => i % 2 === 0)); };
        """
        result = json.loads(provider._run_js_runtime(provider._construct_stdin(functions, True, requests)))
        expected = {"type": "result", "responses": [
            {"type": "result", "data": {"EarthSong": "gnoShtraE:37", "音ひろい": "いろひ音:37", "": ":37"}},
            {"type": "result", "data": {"abcdef": "[97,99,101]", "12345": "[49,51,53]"}},
        ]}
        if result != expected:
            raise AssertionError("QuickJS/EJS output changed: " + repr(result))
        malformed = provider._construct_stdin("_result.n = null; _result.sig = null;", True, requests[:1])
        refused = json.loads(provider._run_js_runtime(malformed))
        if refused["responses"][0]["type"] != "error":
            raise AssertionError("Missing synthetic function was accepted")
        scripts = {s.type.value: {"version": s.version, "sha3_512": s.hash} for s in (provider._lib_script, provider._core_script)}
    receipt = {
        "status": "LOCAL_PASS", "scope": "offline-yt-dlp-ejs-quickjs-fixture",
        "platform": platform.system(), "machine": platform.machine(), "ytDlp": __version__,
        "ejsPackage": importlib.metadata.version("yt-dlp-ejs"), "quickjs": "0.17.0",
        "runtimeBytes": qjs.stat().st_size, "runtimeSha256": hashlib.sha256(qjs.read_bytes()).hexdigest(),
        "scripts": scripts, "milliseconds": round((time.monotonic() - started) * 1000),
        "syntheticNAndSig": True, "unicode": True, "emptyInput": True, "missingFunctionRejected": True,
        "networkUsed": False, "providerVerified": False, "audioDeviceVerified": False,
    }
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
