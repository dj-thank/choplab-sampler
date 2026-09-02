from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.read_android_debug_certificate import (
    CertificateReadError,
    parse_keytool_certificate_sha256,
    read_keystore_certificate_sha256,
)


class AndroidDebugCertificateTest(unittest.TestCase):
    def test_reads_keytool_sha256_without_retaining_private_material(self) -> None:
        digest = ":".join(["AA"] * 32)
        output = """Alias name: androiddebugkey
Entry type: PrivateKeyEntry
Certificate fingerprints:
         SHA1: 00:11
         SHA256: PLACEHOLDER
"""
        output = output.replace("PLACEHOLDER", digest)

        self.assertEqual("aa" * 32, parse_keytool_certificate_sha256(output))

    def test_rejects_missing_or_conflicting_certificate_identity(self) -> None:
        with self.assertRaisesRegex(CertificateReadError, "SHA-256"):
            parse_keytool_certificate_sha256("Alias name: androiddebugkey\n")

        with self.assertRaisesRegex(CertificateReadError, "conflicting"):
            parse_keytool_certificate_sha256(
                f"SHA256: {':'.join(['AA'] * 32)}\n"
                f"SHA-256: {':'.join(['CC'] * 32)}\n"
            )

    def test_reads_only_certificate_identity_from_keystore(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "debug.keystore"
            keystore.write_bytes(b"not-a-real-keystore")
            completed = mock.Mock(
                returncode=0,
                stdout=(
                    "Certificate fingerprints:\n         SHA256: "
                    + ":".join(["AA"] * 32)
                    + "\n"
                ),
                stderr="",
            )
            with (
                mock.patch.dict(os.environ, {"JAVA_HOME": ""}, clear=False),
                mock.patch(
                    "scripts.read_android_debug_certificate.shutil.which",
                    return_value="keytool",
                ),
                mock.patch(
                    "scripts.read_android_debug_certificate.subprocess.run",
                    return_value=completed,
                ) as run,
            ):
                self.assertEqual(
                    "aa" * 32,
                    read_keystore_certificate_sha256(
                        keystore,
                        alias="androiddebugkey",
                        store_password="android",
                    ),
                )

            command = run.call_args.args[0]
            self.assertIn("-list", command)
            self.assertIn(str(keystore), command)
            self.assertNotIn("-keypass", command)

    def test_rejects_keytool_failure_without_echoing_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "debug.keystore"
            keystore.write_bytes(b"keystore")
            completed = mock.Mock(
                returncode=1,
                stdout="PRIVATE KEY DATA",
                stderr="bad password",
            )
            with (
                mock.patch(
                    "scripts.read_android_debug_certificate.shutil.which",
                    return_value="keytool",
                ),
                mock.patch(
                    "scripts.read_android_debug_certificate.subprocess.run",
                    return_value=completed,
                ),
            ):
                with self.assertRaisesRegex(CertificateReadError, "keytool failed") as error:
                    read_keystore_certificate_sha256(keystore, store_password="android")

            self.assertNotIn("PRIVATE", str(error.exception))
            self.assertNotIn("bad password", str(error.exception))


if __name__ == "__main__":
    unittest.main()
