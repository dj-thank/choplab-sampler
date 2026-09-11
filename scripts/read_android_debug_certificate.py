#!/usr/bin/env python3
"""Read an Android debug keystore's certificate identity without exposing its key."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


_FINGERPRINT_PATTERN = re.compile(
    r"(?im)^\s*SHA-?256:\s*([0-9a-fA-F:]+)\s*$"
)
_HEX_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class CertificateReadError(RuntimeError):
    """Raised when a keystore certificate identity cannot be read safely."""


def _normalize_fingerprint(value: str) -> str:
    normalized = re.sub(r"[^0-9a-fA-F]", "", value).lower()
    if not _HEX_PATTERN.fullmatch(normalized):
        raise CertificateReadError("Android debug certificate SHA-256 must contain 64 hex digits")
    return normalized


def parse_keytool_certificate_sha256(output: str) -> str:
    fingerprints = {
        _normalize_fingerprint(match.group(1))
        for match in _FINGERPRINT_PATTERN.finditer(output)
    }
    if not fingerprints:
        raise CertificateReadError("keytool output did not contain a SHA-256 certificate identity")
    if len(fingerprints) != 1:
        raise CertificateReadError("keytool reported conflicting SHA-256 certificate identities")
    return next(iter(fingerprints))


def _find_keytool() -> str:
    found = shutil.which("keytool")
    if found:
        return found
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
        if candidate.is_file():
            return str(candidate)
    raise CertificateReadError("keytool is required to read the Android debug certificate")


def read_keystore_certificate_sha256(
    keystore: Path,
    *,
    alias: str = "androiddebugkey",
    store_password: str,
    keytool: str | None = None,
) -> str:
    if not keystore.is_file():
        raise CertificateReadError(f"Android debug keystore is absent: {keystore}")
    if not alias or alias.startswith("-") or any(character.isspace() for character in alias):
        raise CertificateReadError("Android debug keystore alias is invalid")
    if not store_password:
        raise CertificateReadError("Android debug keystore password is empty")
    command = [
        keytool or _find_keytool(),
        "-list",
        "-v",
        "-keystore",
        str(keystore),
        "-alias",
        alias,
        "-storepass",
        store_password,
    ]
    result = subprocess.run(
        command,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if result.returncode != 0:
        # Do not include keytool output: a failed provider can echo sensitive
        # material, while callers only need the fail-closed classification.
        raise CertificateReadError(f"keytool failed while reading {keystore}")
    return parse_keytool_certificate_sha256(f"{result.stdout}\n{result.stderr}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument(
        "--store-password-env",
        default="CHOPLAB_DEBUG_KEYSTORE_PASSWORD",
        help="Environment variable holding the keystore password (never a CLI value).",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    password = os.environ.get(args.store_password_env)
    if password is None:
        raise CertificateReadError(
            f"Keystore password environment variable is missing: {args.store_password_env}"
        )
    print(
        read_keystore_certificate_sha256(
            args.keystore,
            alias=args.alias,
            store_password=password,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CertificateReadError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
