#!/usr/bin/env python3
"""Materialize a CI signing key without logging secrets; never invent a fallback key."""
from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path
import re


def configure(channel: str, environment: dict[str, str]) -> Path:
    prefix = f"CHOPLAB_{channel.upper()}_"
    fields = ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "CERT_SHA256")
    values = {}
    for field in fields:
        value = environment.get(prefix + field, "")
        if not value or (field != "KEYSTORE_BASE64" and ("\n" in value or "\r" in value)):
            raise ValueError(f"Missing or invalid required signing secret: {prefix + field}")
        values[field] = value
    fingerprint = values["CERT_SHA256"].replace(":", "").lower()
    if re.fullmatch(r"[0-9a-f]{64}", fingerprint) is None:
        raise ValueError("Invalid expected certificate SHA-256")
    try:
        data = base64.b64decode(values["KEYSTORE_BASE64"], validate=True)
    except ValueError as error:
        raise ValueError("Invalid base64 signing keystore") from error
    if not data:
        raise ValueError("Decoded Android keystore is empty")
    target = Path(environment["RUNNER_TEMP"]) / f"choplab-{channel}.jks"
    # O_EXCL/O_NOFOLLOW prevents redirecting the secret into an existing file.
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(target, flags, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
    with Path(environment["GITHUB_ENV"]).open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"{prefix}KEYSTORE={target}\n")
        for field in fields[1:]:
            output.write(f"{prefix}{field}={values[field]}\n")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("android", "preview"), required=True)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    if args.cleanup:
        target = Path(os.environ["RUNNER_TEMP"]) / f"choplab-{args.channel}.jks"
        # Unlink only this run's exact transient file, never a directory or target of a link.
        target.unlink(missing_ok=True)
    else:
        configure(args.channel, dict(os.environ))
        print(f"Configured {args.channel} signing; final APK signer verification remains required")


if __name__ == "__main__":
    main()
