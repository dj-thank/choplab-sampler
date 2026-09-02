from __future__ import annotations

import gzip
import stat
import struct
import subprocess
import sys
import unittest
import zipfile
import zlib
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts.check_public_surface import (
    DER_SEQUENCE_CANDIDATE_LIMIT,
    GitScanLimitError,
    ZIP_LOCAL_FILE_SIGNATURE,
    ZipCandidateScanBudget,
    historical_non_commit_tree_zip_objects,
    historical_structural_zip_objects,
    has_m4a_compatible_brand,
    historical_objects,
    main,
    scan_history,
    scan_historical_zip_blobs,
    scan_explicit_text_file,
    scan_public_path,
    scan_text,
    scan_zip,
    suspicious_path,
)

NON_COMMIT_REF_ARGUMENTS = [
    "for-each-ref",
    "--format=%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00",
]


class PublicSurfacePolicyTest(unittest.TestCase):
    def test_rejects_signing_and_audio_paths(self) -> None:
        self.assertEqual("signing or private-key material", suspicious_path(PurePosixPath("keys/release.jks")))
        self.assertEqual("audio asset", suspicious_path(PurePosixPath("samples/user.wav")))

    def test_rejects_secret_shaped_text(self) -> None:
        token = "github_pat_" + "a" * 24
        self.assertTrue(scan_text("fixture", token))

    def test_accepts_documentation_and_source_paths(self) -> None:
        self.assertIsNone(suspicious_path(PurePosixPath("docs/release-signing.md")))
        self.assertIsNone(suspicious_path(PurePosixPath("app/src/main/MainActivity.kt")))

    def test_rejects_secret_shaped_text_inside_safe_named_zip_member(self) -> None:
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(
                    "docs/notes.txt",
                    ("github_pat_" + "a" * 24).encode("utf-8-sig"),
                )

            findings = scan_zip(archive_path)

        self.assertTrue(any("docs/notes.txt" in finding for finding in findings))
        self.assertTrue(any("secret-shaped content" in finding for finding in findings))

    def test_zip_content_scan_reads_bounded_member_names(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "named.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(f"docs/{token}.txt", "safe")

            findings = scan_zip(archive_path)

        self.assertTrue(any("filename" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))
        self.assertNotIn(token, "\n".join(findings))

    def test_zip_content_scan_limits_ct_sym_exclusion_to_the_jdk_runtime_path(self) -> None:
        token = "github_pat_" + "c" * 24
        runtime_archive = BytesIO()
        with zipfile.ZipFile(runtime_archive, "w") as archive:
            archive.writestr("ChopLab/runtime/lib/ct.sym", b"bounded binary")
        unrelated_archive = BytesIO()
        with zipfile.ZipFile(unrelated_archive, "w") as archive:
            archive.writestr("docs/ct.sym", token)

        runtime_findings = scan_zip(
            BytesIO(runtime_archive.getvalue()),
            label="runtime.zip",
        )
        unrelated_findings = scan_zip(
            BytesIO(unrelated_archive.getvalue()),
            label="unrelated.zip",
        )

        self.assertEqual([], runtime_findings)
        self.assertTrue(any("secret-shaped content" in item for item in unrelated_findings))

    def test_zip_content_scan_fails_closed_at_member_and_total_limits(self) -> None:
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("first.txt", "first member")
                archive.writestr("second.txt", "second member")

            member_findings = scan_zip(archive_path, member_scan_limit=8)
            total_findings = scan_zip(archive_path, total_scan_limit=16)

        self.assertTrue(any("member content scan limit" in item for item in member_findings))
        self.assertTrue(any("total content scan limit" in item for item in total_findings))

    def test_zip_content_scan_rejects_extreme_compression_without_expanding_it(self) -> None:
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("repeated.txt", "a" * 1024)

            findings = scan_zip(archive_path, compression_ratio_limit=2)

        self.assertTrue(any("compression scan limit" in item for item in findings))

    def test_zip_content_scan_accepts_supported_safe_compression_methods(self) -> None:
        for compression in (
            zipfile.ZIP_STORED,
            zipfile.ZIP_DEFLATED,
            zipfile.ZIP_BZIP2,
            zipfile.ZIP_LZMA,
        ):
            with self.subTest(compression=compression):
                candidate = BytesIO()
                with zipfile.ZipFile(candidate, "w", compression) as archive:
                    archive.writestr("notes.txt", b"safe text")

                self.assertEqual(
                    [],
                    scan_zip(BytesIO(candidate.getvalue()), label="supported.zip"),
                )

    def test_zip_content_scan_rejects_lzma_dictionary_before_decoder_construction(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_LZMA) as archive:
            archive.writestr("notes.txt", b"safe text")
        forged = bytearray(candidate.getvalue())
        with zipfile.ZipFile(BytesIO(forged)) as archive:
            info = archive.infolist()[0]
        name_length, extra_length = struct.unpack_from(
            "<HH",
            forged,
            info.header_offset + 26,
        )
        payload_offset = info.header_offset + 30 + name_length + extra_length
        forged[payload_offset + 5 : payload_offset + 9] = (0xFFFFFFFF).to_bytes(
            4,
            "little",
        )

        with patch(
            "scripts.check_public_surface.lzma.LZMADecompressor",
            side_effect=AssertionError("decoder constructor must not be called"),
        ) as constructor:
            findings = scan_zip(BytesIO(forged), label="oversized-dictionary.zip")

        constructor.assert_not_called()
        self.assertTrue(any("LZMA dictionary" in item for item in findings))

    def test_zip_content_scan_caps_compressed_input_across_the_archive(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("first.txt", b"first safe text")
            archive.writestr("second.txt", b"second safe text")
        with zipfile.ZipFile(BytesIO(candidate.getvalue())) as archive:
            compressed_sizes = [info.compress_size for info in archive.infolist()]
        aggregate_limit = sum(compressed_sizes) - 1
        self.assertTrue(all(size <= aggregate_limit for size in compressed_sizes))

        findings = scan_zip(
            BytesIO(candidate.getvalue()),
            label="aggregate-input.zip",
            compressed_input_limit=aggregate_limit,
            total_scan_limit=1024,
        )

        self.assertTrue(
            any("archive compressed input" in item for item in findings),
            findings,
        )

    def test_current_zip_candidates_share_one_work_budget(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / "first.zip", root / "second.zip"]
            for path in paths:
                with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("notes.txt", b"x" * 32)
            budget = ZipCandidateScanBudget(
                archive_limit=2,
                compressed_input_limit=48,
                expanded_output_limit=48,
            )

            first_findings = scan_public_path(paths[0], zip_budget=budget)
            second_findings = scan_public_path(paths[1], zip_budget=budget)

        self.assertEqual([], first_findings)
        self.assertTrue(
            any(
                "current/explicit ZIP compressed input" in item
                for item in second_findings
            ),
            second_findings,
        )

    def test_zip_content_scan_allows_known_large_binary_but_rejects_large_text(self) -> None:
        known_binary = BytesIO()
        with zipfile.ZipFile(known_binary, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "ChopLab.app/ChopLab",
                b"\xca\xfe\xba\xbe" + b"\x00" * (600 * 1024),
            )
        large_text = BytesIO()
        with zipfile.ZipFile(large_text, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("docs/large.txt", b"x" * (600 * 1024))
        spoofed_binary = BytesIO()
        with zipfile.ZipFile(spoofed_binary, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "payload.dat",
                b"\x7fELF" + b"x" * (600 * 1024),
            )
        secret_app = BytesIO()
        token = b"github_pat_" + b"m" * 24
        with zipfile.ZipFile(secret_app, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "ChopLab.app/ChopLab",
                b"\xca\xfe\xba\xbe" + token + b"\x00" * (600 * 1024),
            )

        binary_findings = scan_zip(
            BytesIO(known_binary.getvalue()),
            label="known-binary.zip",
        )
        text_findings = scan_zip(
            BytesIO(large_text.getvalue()),
            label="large-text.zip",
        )
        spoofed_findings = scan_zip(
            BytesIO(spoofed_binary.getvalue()),
            label="spoofed-binary.zip",
        )
        secret_app_findings = scan_zip(
            BytesIO(secret_app.getvalue()),
            label="secret-app.zip",
        )

        self.assertEqual([], binary_findings)
        self.assertTrue(
            any("member content scan limit" in item for item in text_findings),
            text_findings,
        )
        self.assertTrue(
            any("member content scan limit" in item for item in spoofed_findings),
            spoofed_findings,
        )
        self.assertTrue(
            any("secret-shaped content" in item for item in secret_app_findings),
            secret_app_findings,
        )

    def test_zip_content_scan_does_not_exempt_large_icu_basename_alias(self) -> None:
        candidate = BytesIO()
        header = bytearray(24)
        header[0:2] = (24).to_bytes(2, "little")
        header[2:4] = b"\xda\x27"
        header[4:6] = (20).to_bytes(2, "little")
        header[12:16] = b"CmnD"
        token = b"github_pat_" + b"i" * 24
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "docs/icudtl.dat",
                bytes(header) + token + b"x" * (600 * 1024),
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="icu-alias.zip")

        self.assertTrue(
            any("member content scan limit" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_reads_full_bounded_skiko_icu_body(self) -> None:
        header = bytearray(24)
        header[0:2] = (24).to_bytes(2, "little")
        header[2:4] = b"\xda\x27"
        header[4:6] = (20).to_bytes(2, "little")
        header[12:16] = b"CmnD"
        token = b"github_pat_" + b"k" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "icudtl.dat",
                bytes(header) + b"x" * (600 * 1024) + token,
            )
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "ChopLab/app/skiko-awt-runtime-windows-x64-0.144.6.jar",
                inner.getvalue(),
            )

        findings = scan_zip(BytesIO(outer.getvalue()), label="skiko.zip")

        self.assertTrue(
            any("secret-shaped content" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_secret_scans_binary_and_flags_audio_path(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "assets.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("data.bin", b"\x00\x01" + token)
                archive.writestr("samples/tone.wav", b"RIFF\x00\x00" + token)

            findings = scan_zip(archive_path)

        self.assertTrue(any("audio asset" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)

    def test_zip_content_scan_detects_bom_secrets_in_binary_and_apk_signing_pairs(self) -> None:
        token = "github_pat_" + "b" * 24
        encoded_values = (
            b"\xff\xfe" + token.encode("utf-16-le"),
            b"\xfe\xff" + token.encode("utf-16-be"),
            b"\xff\xfe\x00\x00" + token.encode("utf-32-le"),
            b"\x00\x00\xfe\xff" + token.encode("utf-32-be"),
        )

        for index, value in enumerate(encoded_values):
            with self.subTest(index=index):
                candidate = BytesIO()
                with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("payload.bin", value)
                binary_findings = scan_zip(
                    BytesIO(candidate.getvalue()),
                    label=f"bom-binary-{index}.zip",
                )
                self.assertTrue(
                    any("secret-shaped content" in item for item in binary_findings),
                    binary_findings,
                )

                base = BytesIO()
                with zipfile.ZipFile(base, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("notes.txt", b"safe text")
                archive_bytes = bytearray(base.getvalue())
                with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
                    central_directory_start = archive.start_dir
                eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
                pair = (
                    struct.pack("<Q", 4 + len(value))
                    + struct.pack("<I", 0x42726577)
                    + value
                )
                signing_size = len(pair) + 24
                signing_block = (
                    struct.pack("<Q", signing_size)
                    + pair
                    + struct.pack("<Q", signing_size)
                    + b"APK Sig Block 42"
                )
                signed_apk = bytearray(
                    archive_bytes[:central_directory_start]
                    + signing_block
                    + archive_bytes[central_directory_start:]
                )
                shifted_eocd = eocd_offset + len(signing_block)
                struct.pack_into(
                    "<L",
                    signed_apk,
                    shifted_eocd + 16,
                    central_directory_start + len(signing_block),
                )
                with TemporaryDirectory() as directory:
                    apk_path = Path(directory) / f"bom-signing-{index}.apk"
                    apk_path.write_bytes(signed_apk)
                    apk_findings = scan_zip(
                        apk_path,
                        label=f"bom-signing-{index}.apk",
                    )
                self.assertTrue(
                    any("APK signing-block pair" in item for item in apk_findings),
                    apk_findings,
                )
                self.assertTrue(
                    any("secret-shaped content" in item for item in apk_findings),
                    apk_findings,
                )

    def test_zip_content_scan_detects_bomless_utf16_binary_secrets_without_broad_matches(self) -> None:
        token = "github_pat_" + "d" * 24
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            for encoding in ("utf-16-le", "utf-16-be"):
                archive.writestr(
                    f"resource-{encoding}.dll",
                    b"MZ\x90\x00" + token.encode(encoding),
                )
            archive.writestr(
                "ordinary-resource.dll",
                b"MZ\x90\x00" + "ordinary resource string".encode("utf-16-le"),
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="bomless-binary.zip")

        self.assertEqual(2, sum("secret-shaped content" in item for item in findings), findings)
        self.assertTrue(
            all("ordinary-resource.dll" not in item for item in findings),
            findings,
        )
        self.assertNotIn(token, "\n".join(findings))

    def test_zip_content_scan_detects_bomless_utf32_secrets(self) -> None:
        token = "github_pat_" + "u" * 24
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("little.dat", token.encode("utf-32-le"))
            archive.writestr("big.dat", token.encode("utf-32-be"))

        findings = scan_zip(BytesIO(candidate.getvalue()), label="bomless-utf32.zip")

        self.assertEqual(2, sum("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_bomless_utf32_decoy_cannot_suppress_a_later_alignment(self) -> None:
        token = "github_pat_" + "v" * 24
        encoded_prefix = "github_pat_".encode("utf-32-le")
        decoy = b"X" + encoded_prefix
        padding = b"Q" * ((-len(decoy)) % 4)
        content = decoy + padding + token.encode("utf-32-le")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("aligned.dat", content)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="utf32-alignments.zip")

        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_zip_content_scan_does_not_classify_bom_text_as_mpeg(self) -> None:
        token = "github_pat_" + "c" * 24
        encoded_values = (
            b"\xff\xfe" + token.encode("utf-16-le"),
            b"\xfe\xff" + token.encode("utf-16-be"),
            b"\xff\xfe\x00\x00" + token.encode("utf-32-le"),
            b"\x00\x00\xfe\xff" + token.encode("utf-32-be"),
        )

        for index, value in enumerate(encoded_values):
            with self.subTest(index=index):
                candidate = BytesIO()
                with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("notes.txt", value)

                findings = scan_zip(
                    BytesIO(candidate.getvalue()),
                    label=f"bom-text-{index}.zip",
                )

                self.assertTrue(
                    any("secret-shaped content" in item for item in findings),
                    findings,
                )
                self.assertFalse(
                    any("MPEG audio/MP3" in item for item in findings),
                    findings,
                )

    def test_zip_content_scan_detects_valid_adts_aac_under_neutral_name(self) -> None:
        payload = b"\x21" * 16
        frame_length = 7 + len(payload)
        header = bytearray(7)
        header[0] = 0xFF
        header[1] = 0xF1  # MPEG-4, layer 0, no CRC.
        header[2] = (1 << 6) | (4 << 2)  # AAC-LC, 44.1 kHz.
        header[3] = (2 << 6) | ((frame_length >> 11) & 0x03)
        header[4] = (frame_length >> 3) & 0xFF
        header[5] = ((frame_length & 0x07) << 5) | 0x1F
        header[6] = 0xFC  # 0x7FF buffer fullness, one raw data block.
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.bin", bytes(header) + payload)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="adts.zip")

        self.assertTrue(
            any("audio signature 'ADTS/AAC'" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_detects_m4a_compatible_brand_under_neutral_name(self) -> None:
        brands = b"isom" + (0).to_bytes(4, "big") + b"mp42" + b"M4A "
        ftyp = (8 + len(brands)).to_bytes(4, "big") + b"ftyp" + brands
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.dat", ftyp)
            archive.writestr("truncated.dat", b"\x00\x00\x00\x20ftypisom")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="m4a-compatible.zip")

        self.assertEqual(1, sum("audio signature 'M4A'" in item for item in findings), findings)
        self.assertTrue(any("payload.dat" in item for item in findings), findings)

    def test_m4a_brand_probe_iterates_size_zero_box_lazily(self) -> None:
        class HugeSizeZeroFtyp:
            def __len__(self) -> int:
                return 8_400_000

            def __getitem__(self, key: object) -> bytes:
                if key == slice(0, 4):
                    return b"\x00\x00\x00\x00"
                if key == slice(4, 8):
                    return b"ftyp"
                if key == slice(8, 12):
                    return b"M4A "
                if isinstance(key, slice) and (key.start or 0) >= 16:
                    raise AssertionError("brand probe must short-circuit before later slots")
                return b"\x00" * ((key.stop or 0) - (key.start or 0))

        self.assertTrue(has_m4a_compatible_brand(HugeSizeZeroFtyp()))

    def test_m4a_brand_probe_handles_size_zero_no_match_and_malformed_boxes(self) -> None:
        class HugeSizeZeroNoMatch:
            def __len__(self) -> int:
                return 1_000_000

            def __getitem__(self, key: object) -> bytes:
                if key == slice(0, 4):
                    return b"\x00\x00\x00\x00"
                if key == slice(4, 8):
                    return b"ftyp"
                if key == slice(8, 12):
                    return b"isom"
                return b"nope"

        self.assertFalse(has_m4a_compatible_brand(HugeSizeZeroNoMatch()))
        self.assertFalse(has_m4a_compatible_brand(b"\x00\x00\x00\x0cftypisom"))
        self.assertFalse(has_m4a_compatible_brand(b"\x00\x00\x00\x01ftypisom"))
        self.assertFalse(has_m4a_compatible_brand(b"\x00\x00\x00\x13ftypisomxxxx"))

    def test_zip_content_scan_rejects_zst_suffix_and_zstandard_magic(self) -> None:
        zstandard_magic = b"\x28\xb5\x2f\xfd" + b"\x00" * 12
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.zst", b"safe-looking content")
            archive.writestr("payload.bin", zstandard_magic)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="zstandard.zip")

        self.assertGreaterEqual(
            sum("nested archive format '.zst'" in item for item in findings),
            2,
            findings,
        )

    def test_zip_content_scan_detects_audio_under_binary_suffix(self) -> None:
        wave = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.bin", wave)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="binary-audio.zip")

        self.assertTrue(
            any("audio signature 'RIFF/WAVE'" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_detects_der_certificate_and_pkcs8_key_under_neutral_names(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            length = len(value)
            if length < 0x80:
                encoded_length = bytes([length])
            else:
                raw_length = length.to_bytes((length.bit_length() + 7) // 8, "big")
                encoded_length = bytes([0x80 | len(raw_length)]) + raw_length
            return bytes([tag]) + encoded_length + value

        rsa_oid = bytes.fromhex("06092a864886f70d010101")
        algorithm = der(0x30, rsa_oid + der(0x05, b""))
        pkcs8 = der(0x30, der(0x02, b"\x00") + algorithm + der(0x04, b"synthetic-key"))
        certificate = der(
            0x30,
            der(0x30, der(0x02, b"\x00") + algorithm)
            + der(0x30, rsa_oid)
            + der(0x03, b"\x00certificate"),
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.dat", pkcs8)
            archive.writestr("certificate.bin", certificate)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="der-neutral.zip")

        self.assertEqual(2, sum("DER signing material" in item for item in findings), findings)

    def test_zip_content_scan_allows_pe_certificates_but_rejects_embedded_private_keys(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes([tag, len(value)]) + value

        rsa_oid = bytes.fromhex("06092a864886f70d010101")
        algorithm = der(0x30, rsa_oid + der(0x05, b""))
        certificate = der(0x30, der(0x30, algorithm) + der(0x03, b"\x00cert"))
        private_key = der(
            0x30,
            der(0x02, b"\x00") + algorithm + der(0x04, b"synthetic-key"),
        )

        def pe_image(payload: bytes) -> bytes:
            pe_offset = 0x80
            optional_start = pe_offset + 24
            optional_size = 144
            certificate_offset = optional_start + optional_size
            record_size = 8 + len(payload)
            table_size = (record_size + 7) & ~7
            header = bytearray(certificate_offset)
            header[:2] = b"MZ"
            header[0x3C:0x40] = pe_offset.to_bytes(4, "little")
            header[pe_offset : pe_offset + 4] = b"PE\0\0"
            header[pe_offset + 20 : pe_offset + 22] = optional_size.to_bytes(2, "little")
            header[optional_start : optional_start + 2] = (0x10B).to_bytes(2, "little")
            header[optional_start + 92 : optional_start + 96] = (16).to_bytes(4, "little")
            security_directory = optional_start + 96 + 4 * 8
            header[security_directory : security_directory + 4] = certificate_offset.to_bytes(
                4,
                "little",
            )
            header[security_directory + 4 : security_directory + 8] = table_size.to_bytes(
                4,
                "little",
            )
            record = bytearray(table_size)
            record[:4] = record_size.to_bytes(4, "little")
            record[4:6] = (0x0200).to_bytes(2, "little")
            record[6:8] = (0x0002).to_bytes(2, "little")
            record[8:record_size] = payload
            return bytes(header + record)

        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("signed.dll", pe_image(certificate))
            archive.writestr("keyed.dll", pe_image(private_key))
            archive.writestr("renamed.dat", pe_image(certificate))

        findings = scan_zip(BytesIO(candidate.getvalue()), label="pe-signing.zip")

        self.assertFalse(any("signed.dll" in item and "DER" in item for item in findings), findings)
        self.assertTrue(
            any("keyed.dll" in item and "private-key" in item for item in findings),
            findings,
        )
        self.assertTrue(
            any("renamed.dat" in item and "DER signing material" in item for item in findings),
            findings,
        )

    def test_der_scan_requires_oidless_pkcs1_to_fill_its_sequence(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes([tag, len(value)]) + value

        integers = [b"\x00", b"\x01" + b"\x00" * 7] + [b"\x01"] * 7
        certificate_like = der(
            0x30,
            b"".join(der(0x02, value) for value in integers)
            + der(0x04, b"public-certificate-tail"),
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("runtime/lib/security/cacerts", certificate_like)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="cacerts-shape.zip")

        self.assertFalse(any("private-key" in item for item in findings), findings)

    def test_zip_content_scan_accepts_trusted_jdk_cacerts_but_rejects_key_entries(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes([tag, len(value)]) + value

        rsa_oid = bytes.fromhex("06092a864886f70d010101")
        certificate = der(0x30, der(0x30, rsa_oid) + der(0x03, b"\x00cert"))
        private_key = der(
            0x30,
            der(0x02, b"\x00")
            + der(0x30, rsa_oid + der(0x05, b""))
            + der(0x04, b"synthetic-key"),
        )

        def utf(value: bytes) -> bytes:
            return len(value).to_bytes(2, "big") + value

        trusted_entry = (
            (2).to_bytes(4, "big")
            + utf(b"trusted")
            + (0).to_bytes(8, "big")
            + utf(b"X.509")
            + len(certificate).to_bytes(4, "big")
            + certificate
        )
        invalid_certificate_entry = (
            (2).to_bytes(4, "big")
            + utf(b"not-a-certificate")
            + (0).to_bytes(8, "big")
            + utf(b"X.509")
            + (40).to_bytes(4, "big")
            + b"RIFF"
            + (32).to_bytes(4, "little")
            + b"WAVEfmt "
            + b"\x00" * 24
        )
        key_entry = (
            (1).to_bytes(4, "big")
            + utf(b"private")
            + (0).to_bytes(8, "big")
            + len(private_key).to_bytes(4, "big")
            + private_key
            + (0).to_bytes(4, "big")
        )

        def jks(entry: bytes) -> bytes:
            return (
                bytes.fromhex("feedfeed")
                + (2).to_bytes(4, "big")
                + (1).to_bytes(4, "big")
                + entry
                + b"\x00" * 20
            )

        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("safe/runtime/lib/security/cacerts", jks(trusted_entry))
            archive.writestr("unsafe/runtime/lib/security/cacerts", jks(key_entry))
            archive.writestr(
                "invalid/runtime/lib/security/cacerts",
                jks(invalid_certificate_entry),
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="jdk-truststores.zip")

        self.assertFalse(
            any("archive entry 'safe/runtime" in item and "DER" in item for item in findings),
            findings,
        )
        self.assertTrue(
            any("unsafe/runtime" in item and "private-key" in item for item in findings),
            findings,
        )
        self.assertTrue(
            any("invalid/runtime" in item and "invalid JKS" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_rejects_archive_valued_entry_comments(self) -> None:
        nested = BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("secret.txt", "ghp_" + "n" * 36)
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            info = zipfile.ZipInfo("safe.txt")
            info.comment = nested.getvalue()
            archive.writestr(info, "safe")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="metadata-archive.zip")

        self.assertTrue(any("archive-compatible metadata" in item for item in findings), findings)

    def test_zip_content_scan_detects_sec1_ec_private_key(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes([tag, len(value)]) + value

        sec1_key = der(
            0x30,
            der(0x02, b"\x01")
            + der(0x04, b"\x42" * 32)
            + der(0xA0, der(0x06, bytes.fromhex("2a8648ce3d030107"))),
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.dat", sec1_key)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="sec1-key.zip")

        self.assertTrue(any("private-key" in item for item in findings), findings)

    def test_zip_content_scan_detects_pem_certificate_under_neutral_name(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "payload.dat",
                b"-----BEGIN "
                + b"CERTIFICATE-----\nsynthetic\n-----END CERTIFICATE-----\n",
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="pem-certificate.zip")

        self.assertTrue(any("PEM certificate material" in item for item in findings), findings)

    def test_der_scan_fails_closed_after_bounded_sequence_candidates(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "payload.bin",
                b"\x30" * (DER_SEQUENCE_CANDIDATE_LIMIT + 1),
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="der-candidate-limit.zip")

        self.assertTrue(any("candidate scan limit exceeded" in item for item in findings), findings)

    def test_zip_content_scan_allows_conventional_apk_signature_certificate(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes((tag, len(value))) + value

        signed_data_oid = bytes.fromhex("06092a864886f70d010702")
        data_oid = bytes.fromhex("06092a864886f70d010701")
        sha256_oid = bytes.fromhex("0609608648016503040201")
        algorithm = der(0x30, sha256_oid)
        signer = der(
            0x30,
            der(0x02, b"\x01")
            + der(0x30, der(0x30, b"") + der(0x02, b"\x01"))
            + algorithm
            + algorithm
            + der(0x04, b"x"),
        )
        signed_data = der(
            0x30,
            der(0x02, b"\x01")
            + der(0x31, algorithm)
            + der(0x30, data_oid)
            + der(0x31, signer),
        )
        certificate = der(0x30, signed_data_oid + der(0xA0, signed_data))
        private_key = bytes.fromhex(
            "3015020100300d06092a864886f70d0101010500040178"
        )
        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "signed.apk"
            with zipfile.ZipFile(apk_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("META-INF/CERT.RSA", certificate)
                archive.writestr("META-INF/PRIVATE.RSA", private_key)

            findings = scan_zip(apk_path)

        self.assertEqual(
            1,
            sum("DER signing material (private-key)" in item for item in findings),
            findings,
        )

    def test_apk_signature_suffix_does_not_exempt_bare_x509_certificate(self) -> None:
        bare_certificate = bytes.fromhex("3010020100300b06092a864886f70d010101")
        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "bare-certificate.apk"
            with zipfile.ZipFile(apk_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("META-INF/EVIL.RSA", bare_certificate)

            findings = scan_zip(apk_path)

        self.assertTrue(any("not a valid PKCS#7" in item for item in findings), findings)
        self.assertTrue(any("DER signing material" in item for item in findings), findings)

    def test_apk_signature_suffix_rejects_empty_signeddata_wrapper(self) -> None:
        incomplete_signed_data = bytes.fromhex("300f06092a864886f70d010702a0023000")
        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "incomplete-signature.apk"
            with zipfile.ZipFile(apk_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("META-INF/EMPTY.RSA", incomplete_signed_data)

            findings = scan_zip(apk_path)

        self.assertTrue(any("not a valid PKCS#7" in item for item in findings), findings)

    def test_apk_signature_container_rejects_pkcs12_inside_signed_attributes(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            length = len(value)
            if length < 0x80:
                encoded_length = bytes((length,))
            else:
                raw = length.to_bytes((length.bit_length() + 7) // 8, "big")
                encoded_length = bytes((0x80 | len(raw),)) + raw
            return bytes((tag,)) + encoded_length + value

        signed_data_oid = bytes.fromhex("06092a864886f70d010702")
        data_oid = bytes.fromhex("06092a864886f70d010701")
        sha256_oid = bytes.fromhex("0609608648016503040201")
        legacy_pbe_oid = bytes.fromhex("060a2a864886f70d010c0103")
        algorithm = der(0x30, sha256_oid)
        pfx = der(
            0x30,
            der(0x02, b"\x03") + der(0x30, data_oid) + der(0x30, legacy_pbe_oid),
        )
        signer = der(
            0x30,
            der(0x02, b"\x01")
            + der(0x30, der(0x30, b"") + der(0x02, b"\x01"))
            + algorithm
            + der(0xA0, pfx)
            + algorithm
            + der(0x04, b"x"),
        )
        signed_data = der(
            0x30,
            der(0x02, b"\x01")
            + der(0x31, algorithm)
            + der(0x30, data_oid)
            + der(0x31, signer),
        )
        signature_container = der(0x30, signed_data_oid + der(0xA0, signed_data))
        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "attribute-pfx.apk"
            with zipfile.ZipFile(apk_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("META-INF/EVIL.RSA", signature_container)

            findings = scan_zip(apk_path)

        self.assertTrue(any("PKCS#12 container" in item for item in findings), findings)

    def test_zip_content_scan_rejects_legacy_pkcs12_under_neutral_name(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes((tag, len(value))) + value

        data_oid = bytes.fromhex("06092a864886f70d010701")
        legacy_pbe_oid = bytes.fromhex("060a2a864886f70d010c0103")
        pfx = der(
            0x30,
            der(0x02, b"\x03") + der(0x30, data_oid) + der(0x30, legacy_pbe_oid),
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.dat", pfx)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="legacy-pfx.zip")

        self.assertTrue(any("PKCS#12 container" in item for item in findings), findings)

    def test_zip_content_scan_rejects_embedded_legacy_pkcs12(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes((tag, len(value))) + value

        data_oid = bytes.fromhex("06092a864886f70d010701")
        legacy_pbe_oid = bytes.fromhex("060a2a864886f70d010c0103")
        pfx = der(
            0x30,
            der(0x02, b"\x03") + der(0x30, data_oid) + der(0x30, legacy_pbe_oid),
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("resource.dat", b"RESOURCE-PREFIX" + pfx + b"TRAILER")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="embedded-pfx.zip")

        self.assertTrue(any("PKCS#12 container" in item for item in findings), findings)

    def test_der_scan_detects_oidless_pkcs1_encrypted_and_pss_keys_with_wrappers(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            length = len(value)
            if length < 0x80:
                encoded_length = bytes([length])
            else:
                raw_length = length.to_bytes((length.bit_length() + 7) // 8, "big")
                encoded_length = bytes([0x80 | len(raw_length)]) + raw_length
            return bytes([tag]) + encoded_length + value

        integer_values = (
            b"\x00",
            b"\x01" + b"\x00" * 31,
            b"\x01\x00\x01",
            b"\x01" + b"\x11" * 31,
            b"\x01" + b"\x22" * 15,
            b"\x01" + b"\x33" * 15,
            b"\x01" + b"\x44" * 15,
            b"\x01" + b"\x55" * 15,
            b"\x01" + b"\x66" * 15,
        )
        pkcs1 = der(0x30, b"".join(der(0x02, value) for value in integer_values))
        pbes2 = bytes.fromhex("06092a864886f70d01050d")
        pss = bytes.fromhex("06092a864886f70d01010a")
        encrypted_pkcs8 = der(0x30, der(0x30, pbes2) + der(0x04, b"ciphertext"))
        pss_pkcs8 = der(0x30, der(0x02, b"\x00") + der(0x30, pss) + der(0x04, b"key"))
        wrapped_pkcs1 = b"prefix" + pkcs1 + b"trailing"
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload-pkcs1.dat", wrapped_pkcs1)
            archive.writestr("payload-encrypted.dat", encrypted_pkcs8)
            archive.writestr("payload-pss.dat", pss_pkcs8)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="der-variants.zip")

        self.assertEqual(3, sum("DER" in item and "material" in item for item in findings), findings)
        self.assertNotIn(pkcs1.hex(), "\n".join(findings))

        with TemporaryDirectory() as directory:
            text_path = Path(directory) / "key.dat"
            text_path.write_bytes(wrapped_pkcs1)
            text_findings = scan_explicit_text_file(text_path)

        self.assertTrue(any("DER" in item for item in text_findings), text_findings)

    def test_zip_content_scan_reads_secret_inside_nested_class_binary(self) -> None:
        token = b"github_pat_" + b"c" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("Generated.class", b"\xca\xfe\xba\xbe" + token)
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("generated.jar", inner.getvalue())

        findings = scan_zip(BytesIO(outer.getvalue()), label="compiled.zip")

        self.assertTrue(
            any("Generated.class" in item and "secret-shaped" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_bounds_recursive_finding_count_and_label_bytes(self) -> None:
        token = "github_pat_" + "q" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_STORED) as archive:
            for index in range(4_096):
                archive.writestr(
                    f"{index}-{token}.txt",
                    b"safe",
                )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(f"{'x' * 65_000}.zip", inner.getvalue())

        findings = scan_zip(
            BytesIO(candidate.getvalue()),
            label="finding-budget.zip",
        )

        self.assertLessEqual(len(findings), 256)
        self.assertLessEqual(sum(len(item.encode("utf-8")) for item in findings), 64 * 1024)
        self.assertTrue(
            any("finding" in item and "limit" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_keeps_secret_and_marker_across_deep_shared_finding_budget(self) -> None:
        token = "github_pat_" + "r" * 24
        deepest = BytesIO()
        with zipfile.ZipFile(deepest, "w", zipfile.ZIP_STORED) as archive:
            for index in range(4_096):
                archive.writestr(f"{index}-{token}.txt", b"safe")
        nested = deepest.getvalue()
        for _ in range(2):
            wrapper = BytesIO()
            with zipfile.ZipFile(wrapper, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr(f"{'y' * 65_000}.zip", nested)
            nested = wrapper.getvalue()

        findings = scan_zip(BytesIO(nested), label="deep-finding-budget.zip")

        self.assertLessEqual(len(findings), 256)
        self.assertLessEqual(sum(len(item.encode("utf-8")) for item in findings), 64 * 1024)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertTrue(
            any("finding" in item and "limit" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_reads_beyond_valid_jimage_header(self) -> None:
        token = b"github_pat_" + b"j" * 24
        header = struct.pack(
            "<7I",
            0xCAFEDADA,
            0x00010000,
            0,
            1,
            1,
            0,
            0,
        )
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(
                "ChopLab/runtime/lib/modules",
                header + token + b"\x00" * (512 * 1024),
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="jimage.zip")

        self.assertTrue(
            any("secret-shaped content" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_recurses_into_nested_zip_members(self) -> None:
        token = "github_pat_" + "n" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", token)
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.zip", inner.getvalue())

        findings = scan_zip(BytesIO(outer.getvalue()), label="outer.zip")

        self.assertTrue(any("payload.zip" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_zip_content_scan_detects_renamed_nested_zip_payload(self) -> None:
        token = "github_pat_" + "d" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", token)
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.dat", inner.getvalue())

        findings = scan_zip(BytesIO(outer.getvalue()), label="renamed.zip")

        self.assertTrue(any("payload.dat" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_zip_content_scan_rejects_prefixed_renamed_nested_zip(self) -> None:
        token = b"github_pat_" + b"p" * 24
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", token)
        prefixed = b"MZ" + b"\x00" * 30 + inner.getvalue()
        with zipfile.ZipFile(BytesIO(prefixed)) as archive:
            self.assertEqual(token, archive.read("notes.txt"))
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.dat", prefixed)

        findings = scan_zip(BytesIO(outer.getvalue()), label="prefixed-nested.zip")

        self.assertTrue(
            any("unclaimed prefix bytes" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_rejects_renamed_unsupported_nested_archive(self) -> None:
        token = b"github_pat_" + b"g" * 24
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.dat", gzip.compress(token))

        findings = scan_zip(BytesIO(outer.getvalue()), label="renamed-gzip.zip")

        self.assertTrue(
            any("nested archive format '.gz'" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_charges_recursive_output_to_shared_nested_budget(self) -> None:
        member_content = b"x" * 256
        inner = BytesIO()
        with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", member_content)
        outer = BytesIO()
        with zipfile.ZipFile(outer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("inner.zip", inner.getvalue())
        nested_limit = len(inner.getvalue()) + len(member_content) - 1

        findings = scan_zip(
            BytesIO(outer.getvalue()),
            label="shared-budget.zip",
            nested_total_limit=nested_limit,
        )

        self.assertTrue(
            any("recursively decoded content" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_rejects_unsupported_and_overdeep_nested_archives(self) -> None:
        unsupported = BytesIO()
        with zipfile.ZipFile(unsupported, "w") as archive:
            archive.writestr("payload.7z", b"not a supported nested archive")

        nested = b"safe"
        for depth in range(5):
            candidate = BytesIO()
            with zipfile.ZipFile(candidate, "w") as archive:
                archive.writestr(f"level-{depth}.zip", nested)
            nested = candidate.getvalue()

        unsupported_findings = scan_zip(
            BytesIO(unsupported.getvalue()),
            label="unsupported.zip",
        )
        depth_findings = scan_zip(BytesIO(nested), label="overdeep.zip")

        self.assertTrue(
            any("nested archive format" in item for item in unsupported_findings),
            unsupported_findings,
        )
        self.assertTrue(
            any("nested archive depth" in item for item in depth_findings),
            depth_findings,
        )

    def test_zip_content_scan_detects_safe_named_riff_wave_payload(self) -> None:
        wave = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("docs/readme.txt", wave)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="audio-alias.zip")

        self.assertTrue(any("audio signature" in item for item in findings), findings)

    def test_zip_content_scan_detects_id3less_mp3_frame(self) -> None:
        candidate = BytesIO()
        mpeg1_layer3_frame = b"\xff\xfb\x90\x64" + b"\x00" * 413
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("docs/readme.txt", mpeg1_layer3_frame)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="id3less-mp3.zip")

        self.assertTrue(
            any("audio signature 'MPEG audio/MP3'" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_reads_archive_and_entry_comments(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "comments.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.comment = token
                info = zipfile.ZipInfo("docs/notes.txt")
                info.comment = token
                archive.writestr(info, "safe")

            findings = scan_zip(archive_path)

        self.assertTrue(any("archive comment" in item for item in findings))
        self.assertTrue(any("docs/notes.txt' comment" in item for item in findings))

    def test_zip_content_scan_checks_tokens_before_control_character_filtering(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "controlled.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("docs/notes.txt", token + b"\x00")

            findings = scan_zip(archive_path)

        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_zip_content_scan_rejects_audio_signature_in_metadata(self) -> None:
        wave = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.comment = wave
            info = zipfile.ZipInfo("notes.txt")
            info.comment = wave
            archive.writestr(info, b"safe")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="metadata-audio.zip")

        self.assertGreaterEqual(
            sum("audio signature 'RIFF/WAVE'" in item for item in findings),
            2,
            findings,
        )

    def test_zip_content_scan_preserves_ascii_tokens_beside_malformed_bytes(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        malformed_text = token + b"\xff"
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "malformed.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.comment = malformed_text
                info = zipfile.ZipInfo("docs/notes.txt")
                info.comment = malformed_text
                archive.writestr(info, malformed_text)

            findings = scan_zip(archive_path)

        self.assertTrue(any("archive comment" in item for item in findings))
        self.assertTrue(any("docs/notes.txt' comment" in item for item in findings))
        self.assertTrue(
            any("docs/notes.txt'" in item and "comment" not in item for item in findings)
        )

    def test_zip_content_scan_decodes_bom_marked_utf16_text(self) -> None:
        token = "github_pat_" + "u" * 24
        for encoding in ("utf-16", "utf-32"):
            with self.subTest(encoding=encoding):
                candidate = BytesIO()
                with zipfile.ZipFile(candidate, "w") as archive:
                    archive.writestr("config.txt", token.encode(encoding))

                findings = scan_zip(
                    BytesIO(candidate.getvalue()),
                    label=f"{encoding}.zip",
                )

                self.assertTrue(
                    any("secret-shaped content" in item for item in findings),
                    findings,
                )

    def test_zip_content_scan_decodes_bomless_utf16_text_and_metadata(self) -> None:
        token = "github_pat_" + "v" * 24
        encoded = token.encode("utf-16-le")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.comment = encoded
            info = zipfile.ZipInfo("config.txt")
            info.comment = encoded
            info.extra = b"\xfe\xca" + len(encoded).to_bytes(2, "little") + encoded
            archive.writestr(info, encoded)

        findings = scan_zip(
            BytesIO(candidate.getvalue()),
            label="bomless-text.zip",
        )

        self.assertGreaterEqual(
            sum("secret-shaped content" in item for item in findings),
            4,
            findings,
        )
        self.assertNotIn(token, "\n".join(findings))

    def test_zip_content_scan_decodes_bomless_utf16_le_and_be_at_odd_offsets_with_malformed_tail(self) -> None:
        token = "github_pat_" + "w" * 24
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            for encoding in ("utf-16-le", "utf-16-be"):
                archive.writestr(
                    f"payload-{encoding}.txt",
                    b"x" + token.encode(encoding) + b"\xff",
                )

        findings = scan_zip(
            BytesIO(candidate.getvalue()),
            label="bomless-odd-offset.zip",
        )

        self.assertEqual(2, sum("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_zip_content_scan_reads_bounded_entry_extra_fields(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "extra.zip"
            info = zipfile.ZipInfo("docs/notes.txt")
            info.extra = b"\xfe\xca" + len(token).to_bytes(2, "little") + token
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(info, "safe")

            findings = scan_zip(archive_path)

        self.assertTrue(any("extra field" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_zip_content_scan_reads_local_header_only_extra_fields(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        local_extra = b"\xfe\xca" + len(token).to_bytes(2, "little") + token
        safe_payload = b"x" * len(token)
        central_extra = (
            b"\xfe\xca" + len(safe_payload).to_bytes(2, "little") + safe_payload
        )
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "local-extra.zip"
            candidate = BytesIO()
            info = zipfile.ZipInfo("docs/notes.txt")
            info.extra = local_extra
            with zipfile.ZipFile(candidate, "w") as archive:
                archive.writestr(info, "safe")

            archive_bytes = bytearray(candidate.getvalue())
            central_offset = archive_bytes.index(b"PK\x01\x02")
            filename_length = int.from_bytes(
                archive_bytes[central_offset + 28 : central_offset + 30], "little"
            )
            extra_length = int.from_bytes(
                archive_bytes[central_offset + 30 : central_offset + 32], "little"
            )
            self.assertEqual(len(central_extra), extra_length)
            central_extra_offset = central_offset + 46 + filename_length
            archive_bytes[
                central_extra_offset : central_extra_offset + extra_length
            ] = central_extra
            archive_path.write_bytes(archive_bytes)

            with zipfile.ZipFile(archive_path) as archive:
                stored_info = archive.infolist()[0]
                self.assertNotIn(token, stored_info.extra)
                self.assertEqual(b"safe", archive.read(stored_info))
            findings = scan_zip(archive_path)

        self.assertTrue(any("local header extra field" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_zip_content_scan_rejects_windows_member_separators(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("config/.env", b"safe text")
        forged = candidate.getvalue().replace(b"config/.env", b"config\\.env")

        findings = scan_zip(BytesIO(forged), label="backslash.zip")

        self.assertTrue(
            any("Windows path separator" in item for item in findings),
            findings,
        )

    def test_zip_content_scan_rejects_mismatched_local_filename(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "mismatched-name.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("notes.txt", token)

            archive_bytes = bytearray(archive_path.read_bytes())
            central_offset = archive_bytes.index(b"PK\x01\x02")
            central_name_offset = central_offset + 46
            archive_bytes[central_name_offset : central_name_offset + 9] = b"image.bin"
            archive_path.write_bytes(archive_bytes)
            findings = scan_zip(archive_path)

        self.assertTrue(
            any("filename does not match central directory" in item for item in findings)
        )

    def test_zip_content_scan_rejects_mismatched_local_sizes(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "mismatched-size.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("notes.txt", token)

            archive_bytes = bytearray(archive_path.read_bytes())
            central_offset = archive_bytes.index(b"PK\x01\x02")
            archive_bytes[central_offset + 16 : central_offset + 28] = b"\x00" * 12
            archive_path.write_bytes(archive_bytes)
            findings = scan_zip(archive_path)

        self.assertTrue(
            any("CRC or sizes do not match central directory" in item for item in findings)
        )

    def test_zip_content_scan_rejects_unclaimed_prefix_and_trailing_bytes(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("notes.txt", "safe")
        archive_bytes = candidate.getvalue()

        prefix_findings = scan_zip(BytesIO(token + archive_bytes), label="prefixed.zip")
        trailing_findings = scan_zip(
            BytesIO(archive_bytes + token),
            label="trailed.zip",
        )

        self.assertTrue(any("unclaimed prefix bytes" in item for item in prefix_findings))
        self.assertTrue(
            any("end-of-central-directory record not found" in item for item in trailing_findings)
        )

    def test_apk_content_scan_accepts_structural_signing_block(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", b"safe text")
        archive_bytes = bytearray(candidate.getvalue())
        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            central_directory_start = archive.start_dir
        eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
        self.assertGreaterEqual(eocd_offset, 0)
        pair_value = b"bounded-signature-value"
        pair = (
            struct.pack("<Q", 4 + len(pair_value))
            + struct.pack("<I", 0x7109871A)
            + pair_value
        )
        signing_size = len(pair) + 24
        signing_block = (
            struct.pack("<Q", signing_size)
            + pair
            + struct.pack("<Q", signing_size)
            + b"APK Sig Block 42"
        )
        signed_apk = bytearray(
            archive_bytes[:central_directory_start]
            + signing_block
            + archive_bytes[central_directory_start:]
        )
        shifted_eocd = eocd_offset + len(signing_block)
        struct.pack_into(
            "<L",
            signed_apk,
            shifted_eocd + 16,
            central_directory_start + len(signing_block),
        )

        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "signed.apk"
            apk_path.write_bytes(signed_apk)
            findings = scan_zip(apk_path, label="signed.apk")

        self.assertEqual([], findings)

        token_pair_value = b"github_pat_" + b"s" * 24
        token_pair = (
            struct.pack("<Q", 4 + len(token_pair_value))
            + struct.pack("<I", 0x42726577)
            + token_pair_value
        )
        token_signing_size = len(token_pair) + 24
        token_signing_block = (
            struct.pack("<Q", token_signing_size)
            + token_pair
            + struct.pack("<Q", token_signing_size)
            + b"APK Sig Block 42"
        )
        token_apk = bytearray(
            archive_bytes[:central_directory_start]
            + token_signing_block
            + archive_bytes[central_directory_start:]
        )
        token_eocd = eocd_offset + len(token_signing_block)
        struct.pack_into(
            "<L",
            token_apk,
            token_eocd + 16,
            central_directory_start + len(token_signing_block),
        )
        with TemporaryDirectory() as directory:
            token_apk_path = Path(directory) / "token.apk"
            token_apk_path.write_bytes(token_apk)
            token_findings = scan_zip(token_apk_path, label="token.apk")

        self.assertTrue(
            any("APK signing-block pair" in item for item in token_findings),
            token_findings,
        )
        self.assertTrue(
            any("secret-shaped content" in item for item in token_findings),
            token_findings,
        )

    def test_apk_content_scan_reads_secret_inside_dex_binary(self) -> None:
        token = b"github_pat_" + b"x" * 24
        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("classes.dex", b"dex\n035\x00" + token)

            findings = scan_zip(apk_path, label="candidate.apk")

        self.assertTrue(
            any("secret-shaped content" in item for item in findings),
            findings,
        )

    def test_apk_content_scan_recurses_into_archive_valued_signing_pair(self) -> None:
        token = b"github_pat_" + b"p" * 24
        nested = BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", token)

        base = BytesIO()
        with zipfile.ZipFile(base, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", b"safe text")
        archive_bytes = bytearray(base.getvalue())
        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            central_directory_start = archive.start_dir
        eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
        pair_value = nested.getvalue()
        pair = (
            struct.pack("<Q", 4 + len(pair_value))
            + struct.pack("<I", 0x42726577)
            + pair_value
        )
        signing_size = len(pair) + 24
        signing_block = (
            struct.pack("<Q", signing_size)
            + pair
            + struct.pack("<Q", signing_size)
            + b"APK Sig Block 42"
        )
        signed_apk = bytearray(
            archive_bytes[:central_directory_start]
            + signing_block
            + archive_bytes[central_directory_start:]
        )
        shifted_eocd = eocd_offset + len(signing_block)
        struct.pack_into(
            "<L",
            signed_apk,
            shifted_eocd + 16,
            central_directory_start + len(signing_block),
        )

        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "archive-valued-pair.apk"
            apk_path.write_bytes(signed_apk)
            findings = scan_zip(apk_path, label="archive-valued-pair.apk")

        self.assertTrue(any("nested archive" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)

    def test_apk_content_scan_rejects_private_der_inside_signing_pair(self) -> None:
        private_key = bytes.fromhex(
            "3015020100300d06092a864886f70d0101010500040178"
        )
        base = BytesIO()
        with zipfile.ZipFile(base, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", b"safe text")
        archive_bytes = bytearray(base.getvalue())
        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            central_directory_start = archive.start_dir
        eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
        pair = (
            struct.pack("<Q", 4 + len(private_key))
            + struct.pack("<I", 0x7109871A)
            + private_key
        )
        signing_size = len(pair) + 24
        signing_block = (
            struct.pack("<Q", signing_size)
            + pair
            + struct.pack("<Q", signing_size)
            + b"APK Sig Block 42"
        )
        signed_apk = bytearray(
            archive_bytes[:central_directory_start]
            + signing_block
            + archive_bytes[central_directory_start:]
        )
        shifted_eocd = eocd_offset + len(signing_block)
        struct.pack_into(
            "<L",
            signed_apk,
            shifted_eocd + 16,
            central_directory_start + len(signing_block),
        )

        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "private-signing-pair.apk"
            apk_path.write_bytes(signed_apk)
            findings = scan_zip(apk_path, label="private-signing-pair.apk")

        self.assertTrue(
            any(
                "APK signing-block pair" in item
                and "DER signing material (private-key)" in item
                for item in findings
            ),
            findings,
        )

    def test_apk_content_scan_rejects_pkcs12_inside_signing_pair(self) -> None:
        def der(tag: int, value: bytes) -> bytes:
            return bytes((tag, len(value))) + value

        data_oid = bytes.fromhex("06092a864886f70d010701")
        legacy_pbe_oid = bytes.fromhex("060a2a864886f70d010c0103")
        pfx = der(
            0x30,
            der(0x02, b"\x03") + der(0x30, data_oid) + der(0x30, legacy_pbe_oid),
        )
        base = BytesIO()
        with zipfile.ZipFile(base, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", b"safe text")
        archive_bytes = bytearray(base.getvalue())
        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            central_directory_start = archive.start_dir
        eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
        pair = (
            struct.pack("<Q", 4 + len(pfx))
            + struct.pack("<I", 0x7109871A)
            + pfx
        )
        signing_size = len(pair) + 24
        signing_block = (
            struct.pack("<Q", signing_size)
            + pair
            + struct.pack("<Q", signing_size)
            + b"APK Sig Block 42"
        )
        signed_apk = bytearray(
            archive_bytes[:central_directory_start]
            + signing_block
            + archive_bytes[central_directory_start:]
        )
        shifted_eocd = eocd_offset + len(signing_block)
        struct.pack_into(
            "<L",
            signed_apk,
            shifted_eocd + 16,
            central_directory_start + len(signing_block),
        )

        with TemporaryDirectory() as directory:
            apk_path = Path(directory) / "pfx-signing-pair.apk"
            apk_path.write_bytes(signed_apk)
            findings = scan_zip(apk_path, label="pfx-signing-pair.apk")

        self.assertTrue(
            any(
                "APK signing-block pair" in item and "PKCS#12 container" in item
                for item in findings
            ),
            findings,
        )

    def test_zip_content_scan_rejects_unclaimed_interior_bytes(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("notes.txt", "safe")
        archive_bytes = bytearray(candidate.getvalue())
        central_offset = archive_bytes.index(b"PK\x01\x02")
        archive_bytes[central_offset:central_offset] = token
        end_record_offset = archive_bytes.index(b"PK\x05\x06", central_offset)
        archive_bytes[
            end_record_offset + 16 : end_record_offset + 20
        ] = (central_offset + len(token)).to_bytes(4, "little")

        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            self.assertEqual(b"safe", archive.read("notes.txt"))
        findings = scan_zip(BytesIO(archive_bytes), label="interior-gap.zip")

        self.assertTrue(
            any("leave unclaimed bytes" in item for item in findings)
        )

    def test_zip_content_scan_rejects_compressed_directory_payload(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("folder/", token)
        archive_bytes = bytearray(candidate.getvalue())
        central_offset = archive_bytes.index(b"PK\x01\x02")
        archive_bytes[14:18] = b"\x00" * 4
        archive_bytes[22:26] = b"\x00" * 4
        archive_bytes[central_offset + 16 : central_offset + 20] = b"\x00" * 4
        archive_bytes[central_offset + 24 : central_offset + 28] = b"\x00" * 4

        findings = scan_zip(BytesIO(archive_bytes), label="directory-payload.zip")

        self.assertTrue(
            any("compressed payload" in item for item in findings)
        )

    def test_zip_content_scan_accepts_valid_empty_deflated_directory_records(self) -> None:
        candidate = BytesIO()
        info = zipfile.ZipInfo("META-INF/")
        info.compress_type = zipfile.ZIP_DEFLATED
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr(info, b"")
        with zipfile.ZipFile(BytesIO(candidate.getvalue())) as archive:
            directory = archive.infolist()[0]
            self.assertEqual(0, directory.file_size)
            self.assertGreater(directory.compress_size, 0)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="jar.zip")

        self.assertEqual([], findings)

    def test_zip_content_scan_rejects_trailing_compressed_stream_data(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "safe")
        archive_bytes = bytearray(candidate.getvalue())
        original_central_offset = archive_bytes.index(b"PK\x01\x02")
        original_compressed_size = int.from_bytes(archive_bytes[18:22], "little")
        expanded_compressed_size = original_compressed_size + len(token)
        archive_bytes[original_central_offset:original_central_offset] = token
        central_offset = original_central_offset + len(token)
        archive_bytes[18:22] = expanded_compressed_size.to_bytes(4, "little")
        archive_bytes[
            central_offset + 20 : central_offset + 24
        ] = expanded_compressed_size.to_bytes(4, "little")
        end_record_offset = archive_bytes.index(b"PK\x05\x06", central_offset)
        archive_bytes[
            end_record_offset + 16 : end_record_offset + 20
        ] = central_offset.to_bytes(4, "little")

        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            self.assertEqual(b"safe", archive.read("notes.txt"))
        findings = scan_zip(BytesIO(archive_bytes), label="compressed-trailing.zip")

        self.assertTrue(
            any("compressed payload contains trailing" in item for item in findings)
        )

    def test_zip_content_scan_rejects_output_beyond_declared_size(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", b"safe" + token)
        archive_bytes = bytearray(candidate.getvalue())
        central_offset = archive_bytes.index(b"PK\x01\x02")
        safe_crc = zlib.crc32(b"safe") & 0xFFFFFFFF
        archive_bytes[14:18] = safe_crc.to_bytes(4, "little")
        archive_bytes[22:26] = (4).to_bytes(4, "little")
        archive_bytes[central_offset + 16 : central_offset + 20] = safe_crc.to_bytes(
            4,
            "little",
        )
        archive_bytes[central_offset + 24 : central_offset + 28] = (4).to_bytes(
            4,
            "little",
        )

        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            self.assertEqual(b"safe", archive.read("notes.txt"))
            self.assertIsNone(archive.testzip())
        findings = scan_zip(BytesIO(archive_bytes), label="size-truncated-output.zip")

        self.assertTrue(
            any("output exceeds declared uncompressed size" in item for item in findings)
        )

    def test_zip_content_scan_accepts_signatureless_descriptor_crc_collision(self) -> None:
        payload = bytes.fromhex("ac0a7ad5")
        descriptor_signature_crc = 0x08074B50
        self.assertEqual(descriptor_signature_crc, zlib.crc32(payload) & 0xFFFFFFFF)
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", payload)
        archive_bytes = bytearray(candidate.getvalue())
        central_offset = archive_bytes.index(b"PK\x01\x02")
        archive_bytes[6:8] = (
            int.from_bytes(archive_bytes[6:8], "little") | 0x08
        ).to_bytes(2, "little")
        archive_bytes[14:26] = b"\x00" * 12
        archive_bytes[central_offset + 8 : central_offset + 10] = (
            int.from_bytes(
                archive_bytes[central_offset + 8 : central_offset + 10],
                "little",
            )
            | 0x08
        ).to_bytes(2, "little")
        descriptor = (
            descriptor_signature_crc.to_bytes(4, "little")
            + len(payload).to_bytes(4, "little")
            + len(payload).to_bytes(4, "little")
        )
        archive_bytes[central_offset:central_offset] = descriptor
        central_offset += len(descriptor)
        end_record_offset = archive_bytes.index(b"PK\x05\x06", central_offset)
        archive_bytes[
            end_record_offset + 16 : end_record_offset + 20
        ] = central_offset.to_bytes(4, "little")

        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            self.assertEqual(payload, archive.read("notes.txt"))
            self.assertIsNone(archive.testzip())
        findings = scan_zip(BytesIO(archive_bytes), label="descriptor-collision.zip")

        self.assertEqual([], findings)

    def test_zip_content_scan_rejects_excessive_entry_count_before_reading(self) -> None:
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "many.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("first.txt", b"")
                archive.writestr("second.txt", b"")
                archive.writestr("third.txt", b"")

            archive_bytes = bytearray(archive_path.read_bytes())
            end_record_offset = archive_bytes.rindex(b"PK\x05\x06")
            archive_bytes[end_record_offset + 8 : end_record_offset + 10] = (1).to_bytes(
                2,
                "little",
            )
            archive_bytes[
                end_record_offset + 10 : end_record_offset + 12
            ] = (1).to_bytes(2, "little")
            archive_path.write_bytes(archive_bytes)

            with patch(
                "scripts.check_public_surface.zipfile.ZipFile",
                side_effect=AssertionError("ZipFile must not be constructed"),
            ):
                findings = scan_zip(archive_path, entry_count_limit=2)

        self.assertEqual(1, len(findings))
        self.assertIn("at least 3 entries", findings[0])
        self.assertIn("2-entry content scan limit", findings[0])

    def test_public_zip_scans_binary_member_without_rescanning_container(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "assets.zip"
            archive_bytes = None
            for padding in range(256):
                candidate = BytesIO()
                info = zipfile.ZipInfo("data.bin", date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.create_system = 0
                info.external_attr = 1
                with zipfile.ZipFile(candidate, "w") as archive:
                    archive.writestr(info, token + str(padding).encode("ascii"))
                try:
                    candidate.getvalue().decode("utf-8")
                except UnicodeDecodeError:
                    continue
                archive_bytes = candidate.getvalue()
                break

            self.assertIsNotNone(archive_bytes)
            archive_path.write_bytes(archive_bytes or b"")
            self.assertIn(token.decode("ascii"), archive_path.read_text(encoding="utf-8"))
            findings = scan_public_path(archive_path)

        self.assertTrue(
            any("archive entry 'data.bin'" in item for item in findings),
            findings,
        )
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_current_zip_symlink_is_scanned_without_following_target(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            safe_link = Path(directory) / "latest.zip"
            secret_link = Path(directory) / "secret.zip"
            try:
                safe_link.symlink_to("releases/missing.zip")
            except OSError as error:
                if getattr(error, "winerror", None) == 1314:
                    self.skipTest("Windows host does not grant symbolic-link privilege")
                raise
            secret_link.symlink_to(token)

            safe_findings = scan_public_path(safe_link)
            secret_findings = scan_public_path(secret_link)

        self.assertEqual([], safe_findings)
        self.assertTrue(any("symbolic-link target" in item for item in secret_findings))
        self.assertTrue(any("secret-shaped content" in item for item in secret_findings))

    def test_zip_symlink_rejects_parent_traversal_target(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            info = zipfile.ZipInfo("safe-link")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, "../../escape")
            drive_relative = zipfile.ZipInfo("drive-link")
            drive_relative.create_system = 3
            drive_relative.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(drive_relative, "C:..\\escape")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="symlink.zip")

        self.assertTrue(any("symbolic-link target" in item for item in findings), findings)
        self.assertTrue(any("parent traversal" in item for item in findings), findings)
        self.assertTrue(any("drive-qualified" in item for item in findings), findings)

    def test_zip_content_scan_recognizes_checksum_valid_v7_tar(self) -> None:
        payload = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\0" * 28
        header = bytearray(512)
        header[:8] = b"clip.wav"
        header[100:108] = b"0000644\0"
        header[108:116] = b"0000000\0"
        header[116:124] = b"0000000\0"
        header[124:136] = f"{len(payload):011o}\0".encode("ascii")
        header[136:148] = b"00000000000\0"
        header[148:156] = b"        "
        header[156:157] = b"0"
        checksum = sum(header)
        header[148:156] = f"{checksum:06o}\0 ".encode("ascii")
        tar_bytes = bytes(header) + payload + b"\0" * (512 - len(payload)) + b"\0" * 1024
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.dat", tar_bytes)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="v7-tar.zip")

        self.assertTrue(any("nested archive format '.tar'" in item for item in findings), findings)

    def test_zip_content_scan_recognizes_gnu_tar_base256_size(self) -> None:
        payload = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\0" * 28
        header = bytearray(512)
        header[:8] = b"clip.wav"
        header[100:108] = b"0000644\0"
        header[108:116] = b"0000000\0"
        header[116:124] = b"0000000\0"
        encoded_size = bytearray(len(header[124:136]))
        encoded_size[-4:] = len(payload).to_bytes(4, "big")
        encoded_size[0] = 0x80
        header[124:136] = encoded_size
        header[136:148] = b"00000000000\0"
        header[148:156] = b"        "
        header[156:157] = b"0"
        header[257:263] = b"ustar "
        checksum = sum(header)
        header[148:156] = f"{checksum:06o}\0 ".encode("ascii")
        tar_bytes = bytes(header) + payload + b"\0" * (512 - len(payload)) + b"\0" * 1024
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("payload.dat", tar_bytes)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="gnu-base256-tar.zip")

        self.assertTrue(any("nested archive format '.tar'" in item for item in findings), findings)

    def test_directory_marked_zip_entry_with_payload_is_rejected(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("docs/notes.txt/", token)

            findings = scan_zip(archive_path)

        self.assertTrue(any("directory-marked entry contains data" in item for item in findings))

    def test_history_zip_change_enumeration_has_a_record_cap(self) -> None:
        object_id = "a" * 40
        raw_record = (
            f":100644 100644 {object_id} {object_id} M\0archive.zip\0"
        ).encode("ascii")

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if "--raw" in arguments:
                return raw_record * 2
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch("scripts.check_public_surface.HISTORICAL_ZIP_CHANGE_RECORD_LIMIT", 1),
        ):
            findings = scan_history()

        self.assertTrue(
            any("2 records exceed the 1-record scan limit" in item for item in findings),
            findings,
        )

    def test_history_scan_reads_reachable_zip_blob_content(self) -> None:
        object_id = "a" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "a" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} docs/removed.zip\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return (
                    f":000000 100644 {'0' * 40} {object_id} A\0"
                    "docs/removed.zip\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("docs/removed.zip" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_scan_reads_zip_compatible_blob_under_neutral_suffix(self) -> None:
        object_id = "9" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "z" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} deleted/payload.dat\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if "--raw" in arguments:
                return b""
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch(
                "scripts.check_public_surface.git_object_metadata",
                return_value={object_id: ("blob", len(archive_bytes))},
            ),
            patch(
                "scripts.check_public_surface.read_git_blob_prefixes",
                return_value={object_id: ZIP_LOCAL_FILE_SIGNATURE},
            ),
        ):
            findings = scan_history()

        self.assertTrue(any("deleted/payload.dat" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)

    def test_history_structural_probe_detects_prefixed_zip_under_neutral_suffix(self) -> None:
        object_id = "8" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "p" * 24)
        prefixed = b"MZ" + payload.getvalue()
        path = PurePosixPath("deleted/payload.dat")

        with (
            patch(
                "scripts.check_public_surface.git_object_metadata",
                return_value={object_id: ("blob", len(prefixed))},
            ),
            patch(
                "scripts.check_public_surface.read_git_blob_prefixes",
                return_value={object_id: b"MZ"},
            ),
            patch(
                "scripts.check_public_surface.read_git_blob_structural_candidates",
                return_value={object_id},
            ),
        ):
            candidates, findings = historical_structural_zip_objects(
                [(object_id, path)],
                set(),
            )

        self.assertEqual([(object_id, path)], candidates)
        self.assertEqual([], findings)

    def test_history_scan_rejects_prefixed_zip_reachable_under_neutral_suffix(self) -> None:
        object_id = "5" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "s" * 24)
        prefixed = b"MZ" + payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} deleted/payload.dat\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if "--raw" in arguments:
                return b""
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(prefixed)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return prefixed
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch(
                "scripts.check_public_surface.git_object_metadata",
                return_value={object_id: ("blob", len(prefixed))},
            ),
            patch(
                "scripts.check_public_surface.read_git_blob_prefixes",
                return_value={object_id: b"MZ"},
            ),
            patch(
                "scripts.check_public_surface.read_git_blob_structural_candidates",
                return_value={object_id},
            ),
        ):
            findings = scan_history()

        self.assertTrue(any("unclaimed prefix" in item for item in findings), findings)

    def test_history_scan_real_git_integration_rejects_prefixed_zip_with_special_path(self) -> None:
        token = "github_pat_" + "t" * 24
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", token)

        with TemporaryDirectory() as directory:
            repository = Path(directory)
            target = repository / "deleted" / "neutral payload_日本語.dat"
            target.parent.mkdir()

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments],
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            target.write_bytes(b"MZ" + payload.getvalue())
            git("add", "deleted/neutral payload_日本語.dat")
            git("commit", "--quiet", "-m", "add neutral archive")
            target.unlink()
            git("add", "-u")
            git("commit", "--quiet", "-m", "remove neutral archive")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"],
                cwd=repository,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("unclaimed prefix", result.stderr)

    def test_history_scan_keeps_zip_alias_when_rev_list_uses_another_path(self) -> None:
        object_id = "b" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "b" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} copies/archive.dat\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return (
                    f":000000 100644 {'0' * 40} {object_id} A\0"
                    "docs/removed.ZIP\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("docs/removed.ZIP" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_zip_limit_ignores_zip_named_tree_hints(self) -> None:
        tree_hints = "".join(
            f"{index:040x} directories/safe-{index}.zip\n" for index in range(129)
        ).encode("ascii")

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return tree_hints
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertEqual([], findings)

    def test_history_zip_scan_excludes_zip_named_symlink_blobs(self) -> None:
        object_id = "c" * 40

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} releases/latest.zip\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return (
                    f":000000 120000 {'0' * 40} {object_id} A\0"
                    "releases/latest.zip\0"
                ).encode("ascii")
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertEqual([], findings)

    def test_history_scan_reads_zip_created_only_by_merge_result(self) -> None:
        object_id = "d" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "d" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} unrelated/archive.dat\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return (
                    f":000000 100644 {'0' * 40} {object_id} A\0"
                    "merge-only.zip\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("merge-only.zip" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_scan_preserves_newlines_in_zip_paths(self) -> None:
        object_id = "e" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "e" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} unrelated/archive.dat\n".encode("ascii")
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return (
                    f":000000 100644 {'0' * 40} {object_id} A\0"
                    "evil\nname.zip\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("evil\nname.zip" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_scan_reads_zip_from_a_non_commit_tree_ref(self) -> None:
        tree_id = "f" * 40
        object_id = "a" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.writestr("notes.txt", "github_pat_" + "r" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{tree_id}\n{object_id} archive.dat\n".encode("ascii")
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{tree_id}\0tree\0\0\0".encode("ascii")
            if arguments == ["ls-tree", "-rz", "-r", "--full-tree", tree_id]:
                return f"100644 blob {object_id}\tarchive.zip\0".encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("archive.zip" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_scan_non_commit_tree_dispatches_neutral_text_zip_and_der(self) -> None:
        tree_id = "0" * 40
        text_id = "1" * 40
        zip_id = "2" * 40
        der_id = "3" * 40
        symlink_id = "4" * 40
        text_content = ("github_pat_" + "k" * 24).encode("ascii")
        symlink_content = ("github_pat_" + "s" * 24).encode("ascii")
        zip_payload = BytesIO()
        with zipfile.ZipFile(zip_payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "l" * 24)
        der_content = bytes.fromhex(
            "3015020100300d06092a864886f70d0101010500040178"
        )
        contents = {
            text_id: text_content,
            zip_id: zip_payload.getvalue(),
            der_id: der_content,
            symlink_id: symlink_content,
        }

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
                "--",
                ":(icase,glob)**/*.zip",
            ]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{tree_id}\0tree\0\0\0".encode("ascii")
            if arguments == ["ls-tree", "-rz", "-r", "--full-tree", tree_id]:
                return (
                    f"100644 blob {text_id}\tnotes.dat\0"
                    f"100644 blob {zip_id}\tpayload.bin\0"
                    f"100644 blob {der_id}\tkey.dat\0"
                    f"120000 blob {symlink_id}\tcurrent-link\0"
                ).encode("ascii")
            for object_id, content in contents.items():
                if arguments == ["cat-file", "-s", object_id]:
                    return f"{len(content)}\n".encode("ascii")
                if arguments == ["cat-file", "blob", object_id]:
                    return content
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("git history tree-ref notes.dat" in item for item in findings), findings)
        self.assertTrue(any("git history tree-ref key.dat" in item for item in findings), findings)
        self.assertTrue(any("payload.bin" in item and "secret-shaped" in item for item in findings), findings)
        self.assertTrue(any("tree symlink current-link" in item for item in findings), findings)

    def test_history_scan_reads_zip_from_a_direct_blob_ref(self) -> None:
        object_id = "b" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "b" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if "--raw" in arguments:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{object_id}\0blob\0\0\0".encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return archive_bytes
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(
            any("direct-ref-bbbbbbbbbbbb.zip" in item for item in findings),
            findings,
        )
        self.assertTrue(any("secret-shaped content" in item for item in findings))

    def test_history_scan_reads_secret_from_a_direct_non_zip_blob_ref(self) -> None:
        object_id = "1" * 40
        content = ("github_pat_" + "y" * 24).encode("ascii")

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if "--raw" in arguments:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{object_id}\0blob\0\0\0".encode("ascii")
            if arguments == ["cat-file", "-s", object_id]:
                return f"{len(content)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", object_id]:
                return content
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("direct-ref-111111111111" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(content.decode("ascii"), "\n".join(findings))

    def test_history_scan_recursively_peels_annotated_tags_to_a_blob(self) -> None:
        first_tag_id = "c" * 40
        second_tag_id = "d" * 40
        blob_id = "e" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "github_pat_" + "e" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{first_tag_id}\0tag\0{second_tag_id}\0tag\0".encode(
                    "ascii"
                )
            if arguments == ["cat-file", "-p", first_tag_id]:
                return f"object {second_tag_id}\ntype tag\n\n".encode("ascii")
            if arguments == ["cat-file", "-p", second_tag_id]:
                return f"object {blob_id}\ntype blob\n\n".encode("ascii")
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return archive_bytes
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("direct-ref-eeeeeeeeeeee.zip" in item for item in findings))
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)

    def test_history_scan_reads_annotated_tag_message_before_peeling(self) -> None:
        tag_id = "7" * 40
        blob_id = "6" * 40
        token = "github_pat_" + "n" * 24
        safe_blob = b"safe blob"

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{tag_id}\0tag\0\0\0".encode("ascii")
            if arguments == ["cat-file", "-p", tag_id]:
                return (
                    f"object {blob_id}\ntype blob\n\nrelease {token}\n"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(safe_blob)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return safe_blob
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("annotated tag" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_history_scan_applies_full_policy_to_annotated_tag_messages(self) -> None:
        tag_ids = ("6" * 40, "7" * 40, "8" * 40)
        blob_id = "9" * 40
        safe_blob = b"safe blob"
        messages = {
            tag_ids[0]: b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28,
            tag_ids[1]: bytes.fromhex(
                "3015020100300d06092a864886f70d0101010500040178"
            ),
            tag_ids[2]: b"\x1f\x8b" + b"compressed-tag-message",
        }

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return b"".join(
                    f"{tag_id}\0tag\0\0\0".encode("ascii") for tag_id in tag_ids
                )
            for tag_id, message in messages.items():
                if arguments == ["cat-file", "-p", tag_id]:
                    return (
                        f"object {blob_id}\ntype blob\n\n".encode("ascii")
                        + message
                    )
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(safe_blob)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return safe_blob
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("audio signature 'RIFF/WAVE'" in item for item in findings), findings)
        self.assertTrue(any("private-key" in item for item in findings), findings)
        self.assertTrue(any("nested archive format '.gz'" in item for item in findings), findings)

    def test_history_scan_reads_original_peeled_tag_message_before_using_target(self) -> None:
        tag_id = "2" * 40
        blob_id = "3" * 40
        token = "github_pat_" + "j" * 24
        safe_blob = b"safe blob"

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{tag_id}\0tag\0{blob_id}\0blob\0".encode("ascii")
            if arguments == ["cat-file", "-p", tag_id]:
                return (
                    f"object {blob_id}\ntype blob\n\nrelease {token}\n"
                ).encode("ascii")
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(safe_blob)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return safe_blob
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertTrue(any("annotated tag" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_history_scan_caches_repeated_annotated_tag_bodies(self) -> None:
        tag_id = "4" * 40
        blob_id = "5" * 40
        safe_blob = b"safe blob"
        tag_reads = 0

        def fake_run_git(arguments: list[str]) -> bytes:
            nonlocal tag_reads
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return (
                    f"{tag_id}\0tag\0{blob_id}\0blob\0"
                    f"{tag_id}\0tag\0{blob_id}\0blob\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-p", tag_id]:
                tag_reads += 1
                return f"object {blob_id}\ntype blob\n\nrelease note\n".encode("ascii")
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(safe_blob)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return safe_blob
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            findings = scan_history()

        self.assertEqual(1, tag_reads)
        self.assertEqual([], findings)

    def test_history_scan_bounds_recursive_annotated_tag_peeling(self) -> None:
        first_tag_id = "f" * 40
        second_tag_id = "a" * 40
        blob_id = "b" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", "safe")
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return b""
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return f"{first_tag_id}\0tag\0{second_tag_id}\0tag\0".encode(
                    "ascii"
                )
            if arguments == ["cat-file", "-p", first_tag_id]:
                return f"object {second_tag_id}\ntype tag\n\n".encode("ascii")
            if arguments == ["cat-file", "-p", second_tag_id]:
                return f"object {blob_id}\ntype blob\n\n".encode("ascii")
            if arguments == ["cat-file", "-s", blob_id]:
                return f"{len(archive_bytes)}\n".encode("ascii")
            if arguments == ["cat-file", "blob", blob_id]:
                return archive_bytes
            if "--raw" in arguments:
                return b""
            if arguments == [
                "log",
                "--all",
                "--format=",
                "--no-ext-diff",
                "--no-textconv",
                "-p",
            ]:
                return b""
            self.fail(f"unexpected git arguments: {arguments}")

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch("scripts.check_public_surface.HISTORICAL_TAG_PEEL_LIMIT", 1),
        ):
            findings = scan_history()

        self.assertTrue(any("annotated tag peel" in item for item in findings), findings)

    def test_history_scan_bounds_aggregate_annotated_tag_peeling(self) -> None:
        tag_ids = ("1" * 40, "2" * 40)
        cat_file_calls = 0

        def fake_run_git(arguments: list[str]) -> bytes:
            nonlocal cat_file_calls
            if arguments == NON_COMMIT_REF_ARGUMENTS:
                return (
                    f"{tag_ids[0]}\0tag\0\0\0"
                    f"{tag_ids[1]}\0tag\0\0\0"
                ).encode("ascii")
            if arguments == ["cat-file", "-p", tag_ids[0]]:
                cat_file_calls += 1
                return f"object {'3' * 40}\ntype commit\n\n".encode("ascii")
            self.fail(f"unexpected git arguments: {arguments}")

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch("scripts.check_public_surface.HISTORICAL_TAG_PEEL_OPERATION_LIMIT", 1),
        ):
            candidates, findings = historical_non_commit_tree_zip_objects()

        self.assertEqual([], candidates)
        self.assertEqual(1, cat_file_calls)
        self.assertTrue(any("annotated tag peel" in item for item in findings), findings)

    def test_history_scan_bounds_non_commit_ref_inventory_before_peeling(self) -> None:
        refs = (
            f"{'4' * 40}\0tag\0\0\0"
            f"{'5' * 40}\0tag\0\0\0"
        ).encode("ascii")
        with (
            patch("scripts.check_public_surface.run_git", return_value=refs) as run_git,
            patch("scripts.check_public_surface.HISTORICAL_NON_COMMIT_REF_LIMIT", 1),
        ):
            candidates, findings = historical_non_commit_tree_zip_objects()

        self.assertEqual([], candidates)
        self.assertEqual(1, run_git.call_count)
        self.assertTrue(any("non-commit refs exceed" in item for item in findings), findings)

    def test_history_zip_roots_and_children_share_decoder_work_budgets(self) -> None:
        object_ids = ("c" * 40, "d" * 40)
        archive_bytes = BytesIO()
        with zipfile.ZipFile(archive_bytes, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("notes.txt", b"safe")
        content = archive_bytes.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            for object_id in object_ids:
                if arguments == ["cat-file", "-s", object_id]:
                    return f"{len(content)}\n".encode("ascii")
                if arguments == ["cat-file", "blob", object_id]:
                    return content
            self.fail(f"unexpected git arguments: {arguments}")

        observed: list[dict[str, object]] = []

        def fake_scan_zip(source: object, **kwargs: object) -> list[str]:
            observed.append(kwargs)
            return []

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch("scripts.check_public_surface.scan_zip", side_effect=fake_scan_zip),
        ):
            findings = scan_historical_zip_blobs(
                [
                    (object_ids[0], PurePosixPath("first.zip")),
                    (object_ids[1], PurePosixPath("second.zip")),
                ]
            )

        self.assertEqual([], findings)
        self.assertEqual(2, len(observed))
        for key in (
            "_candidate_budget",
            "_nested_budget",
            "_apk_binary_budget",
            "_binary_secret_budget",
        ):
            self.assertIn(key, observed[0])
            self.assertIs(observed[0][key], observed[1][key])

    def test_current_zip_candidates_scan_renamed_zip_payload(self) -> None:
        token = "github_pat_" + "r" * 24
        with TemporaryDirectory() as directory:
            renamed = Path(directory) / "payload.dat"
            with zipfile.ZipFile(renamed, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("notes.txt", token)

            findings = scan_public_path(renamed, zip_budget=ZipCandidateScanBudget())

        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertNotIn(token, "\n".join(findings))

    def test_release_security_documents_actual_nested_limits(self) -> None:
        document = (
            Path(__file__).resolve().parents[2] / "docs" / "RELEASE_SECURITY.md"
        ).read_text(encoding="utf-8")

        for marker in (
            "depth 3",
            "64-archive",
            "16 MiB/member",
            "256 MiB compressed-container",
            "256 MiB expanded-work",
            "512-operation peel budget",
            "128 MiB per JIMAGE",
            "384 MiB binary-secret body budget",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, document)

    def test_desktop_source_snapshot_is_scanned_before_archive_and_upload(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "desktop.yml"
        ).read_text(encoding="utf-8")

        policy_tests = workflow.index("python -m unittest discover -s scripts/tests")
        public_scan = workflow.index("python scripts/check_public_surface.py")
        source_archive = workflow.index("git archive --format=zip")
        archive_scan = workflow.index(
            "python scripts/check_public_surface.py --source-archive "
            "dist/choplab-source-snapshot.zip"
        )
        source_upload = workflow.index("name: choplab-source-snapshot")

        self.assertLess(public_scan, policy_tests)
        self.assertLess(policy_tests, source_archive)
        self.assertLess(source_archive, archive_scan)
        self.assertLess(archive_scan, source_upload)

    def test_explicit_archive_cli_scans_safe_malicious_and_missing_paths(self) -> None:
        token = "github_pat_" + "z" * 24
        with TemporaryDirectory() as directory:
            root = Path(directory)
            safe = root / "safe.zip"
            malicious = root / "malicious.zip"
            missing = root / "missing.zip"
            with zipfile.ZipFile(safe, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("notes.txt", "safe text")
            with zipfile.ZipFile(malicious, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("notes.txt", token)

            def run_explicit(path: Path) -> tuple[int, str]:
                output = StringIO()
                errors = StringIO()
                with (
                    patch(
                        "scripts.check_public_surface.public_candidate_paths",
                        return_value=[],
                    ),
                    patch("sys.argv", ["check_public_surface.py", "--archive", str(path)]),
                    redirect_stdout(output),
                    redirect_stderr(errors),
                ):
                    exit_code = main()
                return exit_code, output.getvalue() + errors.getvalue()

            safe_exit, safe_output = run_explicit(safe)
            malicious_exit, malicious_output = run_explicit(malicious)
            missing_exit, missing_output = run_explicit(missing)

        self.assertEqual(0, safe_exit, safe_output)
        self.assertEqual(1, malicious_exit, malicious_output)
        self.assertIn("secret-shaped content", malicious_output)
        self.assertEqual(1, missing_exit, missing_output)
        self.assertIn("preflight failed", missing_output)

    def test_source_archive_cli_uses_dedicated_bounded_limits(self) -> None:
        token = "github_pat_" + "q" * 24
        with TemporaryDirectory() as directory:
            root = Path(directory)
            safe = root / "source.zip"
            malicious = root / "malicious-source.zip"
            oversized = root / "oversized-source.zip"
            with zipfile.ZipFile(safe, "w", zipfile.ZIP_STORED) as archive:
                for index in range(6):
                    archive.writestr(f"source-{index}.txt", "a" * (1024 * 1024))
            with zipfile.ZipFile(malicious, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("notes.txt", token)
            with zipfile.ZipFile(oversized, "w", zipfile.ZIP_STORED) as archive:
                for index in range(17):
                    archive.writestr(f"source-{index}.txt", "b" * (1024 * 1024))

            def run_source(path: Path) -> tuple[int, str]:
                output = StringIO()
                errors = StringIO()
                with (
                    patch(
                        "scripts.check_public_surface.public_candidate_paths",
                        return_value=[],
                    ),
                    patch(
                        "sys.argv",
                        ["check_public_surface.py", "--source-archive", str(path)],
                    ),
                    redirect_stdout(output),
                    redirect_stderr(errors),
                ):
                    exit_code = main()
                return exit_code, output.getvalue() + errors.getvalue()

            safe_exit, safe_output = run_source(safe)
            malicious_exit, malicious_output = run_source(malicious)
            oversized_exit, oversized_output = run_source(oversized)

        self.assertEqual(0, safe_exit, safe_output)
        self.assertEqual(1, malicious_exit, malicious_output)
        self.assertIn("secret-shaped content", malicious_output)
        self.assertEqual(1, oversized_exit, oversized_output)
        self.assertIn("16777216-byte", oversized_output)

    def test_explicit_text_file_cli_scans_safe_malicious_and_missing_paths(self) -> None:
        token = "github_pat_" + "t" * 24
        with TemporaryDirectory() as directory:
            root = Path(directory)
            safe = root / "safe.json"
            malicious = root / "malicious.json"
            missing = root / "missing.json"
            safe.write_text('{"components": []}', encoding="utf-8")
            malicious.write_text(
                '{"repository": "' + token + '"}',
                encoding="utf-8",
            )

            def run_explicit(path: Path) -> tuple[int, str]:
                output = StringIO()
                errors = StringIO()
                with (
                    patch(
                        "scripts.check_public_surface.public_candidate_paths",
                        return_value=[],
                    ),
                    patch("sys.argv", ["check_public_surface.py", "--text-file", str(path)]),
                    redirect_stdout(output),
                    redirect_stderr(errors),
                ):
                    exit_code = main()
                return exit_code, output.getvalue() + errors.getvalue()

            safe_exit, safe_output = run_explicit(safe)
            malicious_exit, malicious_output = run_explicit(malicious)
            missing_exit, missing_output = run_explicit(missing)

        self.assertEqual(0, safe_exit, safe_output)
        self.assertEqual(1, malicious_exit, malicious_output)
        self.assertIn("secret-shaped content", malicious_output)
        self.assertEqual(1, missing_exit, missing_output)
        self.assertIn("preflight failed", missing_output)

    def test_explicit_archive_and_text_file_cli_scan_der_pairs(self) -> None:
        certificate = bytes.fromhex("3010020100300b06092a864886f70d010101")
        private_key = bytes.fromhex(
            "3015020100300d06092a864886f70d0101010500040178"
        )
        with TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "candidate.apk"
            key = root / "key.dat"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("META-INF/CERT.RSA", certificate)
                archive.writestr("payload.dat", private_key)
            key.write_bytes(b"prefix" + private_key + b"trailing")

            output = StringIO()
            errors = StringIO()
            with (
                patch(
                    "scripts.check_public_surface.public_candidate_paths",
                    return_value=[],
                ),
                patch(
                    "sys.argv",
                    [
                        "check_public_surface.py",
                        "--archive",
                        str(apk),
                        "--text-file",
                        str(key),
                    ],
                ),
                redirect_stdout(output),
                redirect_stderr(errors),
            ):
                exit_code = main()

        self.assertEqual(1, exit_code, output.getvalue() + errors.getvalue())
        self.assertIn("DER signing material", errors.getvalue())

    def test_explicit_text_file_rejects_neutral_archive_payloads(self) -> None:
        token = ("github_pat_" + "m" * 24).encode("ascii")
        nested = BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("notes.txt", token)
        payloads = (
            nested.getvalue(),
            gzip.compress(token),
            b"\x28\xb5\x2f\xfd" + token,
        )

        with TemporaryDirectory() as directory:
            for index, payload in enumerate(payloads):
                with self.subTest(index=index):
                    path = Path(directory) / f"payload-{index}.dat"
                    path.write_bytes(payload)
                    findings = scan_explicit_text_file(path)
                    self.assertTrue(findings, payload)
                    self.assertNotIn(token.decode("ascii"), "\n".join(findings))

    def test_platform_archives_are_scanned_after_creation_before_upload(self) -> None:
        root = Path(__file__).resolve().parents[2]
        desktop = (root / ".github" / "workflows" / "desktop.yml").read_text(
            encoding="utf-8"
        )
        ios = (root / ".github" / "workflows" / "ios.yml").read_text(
            encoding="utf-8"
        )

        desktop_archive = desktop.index("Compress-Archive")
        desktop_scan = desktop.index(
            "python scripts/check_public_surface.py --archive $zip"
        )
        desktop_upload = desktop.index("name: choplab-windows-app-image")
        self.assertLess(desktop_archive, desktop_scan)
        self.assertLess(desktop_scan, desktop_upload)

        ios_archive = ios.index("bash scripts/build-ios-simulator.sh")
        ios_scan = ios.index("python3 scripts/check_public_surface.py --archive")
        ios_path = ios.index(
            '"dist/ChopLab-${CHOPLAB_VERSION}-ios-simulator.app.zip"',
            ios_scan,
        )
        ios_upload = ios.index("name: choplab-ios-simulator-${{ github.sha }}")
        self.assertLess(ios_archive, ios_scan)
        self.assertLess(ios_scan, ios_path)
        self.assertLess(ios_path, ios_upload)

    def test_active_plan_registry_points_next_action_to_pr69(self) -> None:
        registry = (
            Path(__file__).resolve().parents[2] / "plans" / "active" / "README.md"
        ).read_text(encoding="utf-8")
        current_selection = registry.split(
            "**wave 18 completed local; goal remains active:**",
            maxsplit=1,
        )[0]

        self.assertIn("existing PR #69", current_selection)
        self.assertNotIn("既存PR #79を同じbranch/headへ更新", current_selection)

    def test_completed_plan_records_the_exact_current_tree_policy_count(self) -> None:
        plan = (
            Path(__file__).resolve().parents[2]
            / "plans"
            / "completed"
            / "zip-publication-content-scan-20260827.md"
        ).read_text(encoding="utf-8")

        self.assertRegex(plan, r"Exact current-tree policy suite: [0-9]+ tests")
        self.assertNotIn("Complete Python policy: 124 tests,", plan)

    def test_release_scans_exact_archives_before_manifest_and_publication(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "release.yml"
        ).read_text(encoding="utf-8")

        android_stage = workflow.index(
            'apk_target="dist/ChopLab-${RELEASE_TAG}-android-debug.apk"'
        )
        android_stage_scan = workflow.index(
            "python3 scripts/check_public_surface.py \\",
            android_stage,
        )
        android_stage_apk = workflow.index(
            '--archive "$apk_target"',
            android_stage_scan,
        )
        android_stage_sbom = workflow.index(
            '--text-file "$sbom_target"',
            android_stage_apk,
        )
        android_upload = workflow.index(
            "name: Upload Android and SBOM assets",
            android_stage_sbom,
        )
        ios_build = workflow.index("bash scripts/build-ios-simulator.sh")
        ios_stage_scan = workflow.index(
            'python3 scripts/check_public_surface.py --archive "$target_archive"',
            ios_build,
        )
        ios_upload = workflow.index("name: Upload iOS assets", ios_stage_scan)
        windows_archive = workflow.index("Compress-Archive", ios_upload)
        windows_stage_scan = workflow.index(
            "python scripts/check_public_surface.py --archive $archive",
            windows_archive,
        )
        windows_upload = workflow.index(
            "name: Upload Windows verification artifacts",
            windows_stage_scan,
        )
        self.assertLess(android_stage, android_stage_scan)
        self.assertLess(android_stage_scan, android_stage_apk)
        self.assertLess(android_stage_apk, android_stage_sbom)
        self.assertLess(android_stage_sbom, android_upload)
        self.assertLess(ios_build, ios_stage_scan)
        self.assertLess(ios_stage_scan, ios_upload)
        self.assertLess(windows_archive, windows_stage_scan)
        self.assertLess(windows_stage_scan, windows_upload)

        android_download = workflow.index("name: Download Android release assets", windows_upload)
        ios_download = workflow.index("name: Download iOS release assets", android_download)
        scan = workflow.index("name: Scan final public archives", ios_download)
        android_archive = workflow.index(
            'dist/ChopLab-${RELEASE_TAG}-android-debug.apk',
            scan,
        )
        ios_archive = workflow.index(
            'dist/ChopLab-${RELEASE_TAG}-ios-simulator.app.zip',
            scan,
        )
        final_sbom = workflow.index(
            'dist/ChopLab-${RELEASE_TAG}-sbom.cdx.json',
            ios_archive,
        )
        manifest = workflow.index("name: Write source-bound manifest and checksums")
        attest = workflow.index("name: Attest build provenance")
        publish = workflow.index("name: Publish once without asset replacement")

        self.assertLess(android_download, ios_download)
        self.assertLess(ios_download, scan)
        self.assertNotIn("pattern:", workflow[android_download:scan])
        self.assertIn("name: choplab-android-release-assets", workflow[android_download:ios_download])
        self.assertIn("name: choplab-ios-release-assets", workflow[ios_download:scan])
        self.assertLess(scan, android_archive)
        self.assertLess(scan, ios_archive)
        self.assertLess(android_archive, manifest)
        self.assertLess(ios_archive, manifest)
        self.assertLess(final_sbom, manifest)
        self.assertNotIn("windows-app-image.zip", workflow[scan:manifest])
        self.assertLess(manifest, attest)
        self.assertLess(attest, publish)

    def test_release_scans_every_asset_in_the_publication_glob(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "release.yml"
        ).read_text(encoding="utf-8")

        scan_all = workflow.index("name: Scan every release asset")
        manifest = workflow.index("name: Write source-bound manifest and checksums")
        publication_scan = workflow.index("name: Scan final publication glob")
        publish = workflow.index("name: Publish once without asset replacement")

        self.assertLess(scan_all, manifest)
        self.assertLess(manifest, publication_scan)
        self.assertLess(publication_scan, publish)
        self.assertIn("find dist -maxdepth 1 -type f -print0", workflow[scan_all:publication_scan])
        self.assertIn('scan_args+=(--archive "$asset")', workflow[scan_all:publication_scan])
        self.assertIn('scan_args+=(--text-file "$asset")', workflow[scan_all:publication_scan])
        self.assertIn('find dist -maxdepth 1 -type f -print0', workflow[publication_scan:publish])
        self.assertIn("expected_assets=(", workflow[scan_all:publication_scan])
        self.assertIn("find dist -maxdepth 1 -type l", workflow[scan_all:publication_scan])
        self.assertIn("unsupported release asset", workflow[scan_all:publication_scan])
        self.assertIn("expected_assets=(", workflow[publication_scan:publish])
        self.assertIn("find dist -maxdepth 1 -type l", workflow[publication_scan:publish])
        self.assertIn("find dist -mindepth 1 -maxdepth 1 ! -type f", workflow[scan_all:publication_scan])
        self.assertIn("find dist -mindepth 1 -maxdepth 1 ! -type f", workflow[publication_scan:publish])

    def test_zip_scan_rejects_amr_classic_github_tokens_and_putty_keys(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("audio.dat", b"#!AMR\n" + b"\x04" + b"\x00" * 12)
            archive.writestr("credential.txt", "ghp_" + "a" * 36)
            archive.writestr(
                "neutral.dat",
                "PuTTY-" + "User-Key-File-" + "3: ssh-ed25519\n"
                "Encryption: aes256-cbc\nPrivate-Lines: 1\nAAAA\n",
            )

        findings = scan_zip(BytesIO(candidate.getvalue()), label="review-boundaries.zip")

        self.assertTrue(any("AMR-NB" in item for item in findings), findings)
        self.assertTrue(any("secret-shaped content" in item for item in findings), findings)
        self.assertTrue(any("PuTTY" in item or "private-key" in item for item in findings), findings)
        self.assertEqual("audio asset", suspicious_path(PurePosixPath("take.amr")))
        self.assertEqual("signing or private-key material", suspicious_path(PurePosixPath("id.ppk")))

    def test_m4a_probe_accepts_bounded_extended_size_ftyp(self) -> None:
        brands = b"isom" + (0).to_bytes(4, "big") + b"mp42" + b"M4A "
        box_size = 16 + len(brands)
        ftyp = b"\x00\x00\x00\x01ftyp" + box_size.to_bytes(8, "big") + brands

        self.assertTrue(has_m4a_compatible_brand(ftyp))
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("payload.dat", ftyp)
        findings = scan_zip(BytesIO(candidate.getvalue()), label="extended-ftyp.zip")
        self.assertTrue(any("audio signature 'M4A'" in item for item in findings), findings)

    def test_zip_scan_rejects_parent_absolute_and_drive_rooted_paths(self) -> None:
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("../escape.txt", "safe")
            archive.writestr("/absolute.txt", "safe")
            archive.writestr("C:/drive-rooted.txt", "safe")

        findings = scan_zip(BytesIO(candidate.getvalue()), label="unsafe-paths.zip")

        self.assertTrue(any("parent traversal" in item for item in findings), findings)
        self.assertTrue(any("absolute" in item for item in findings), findings)
        self.assertTrue(any("drive-rooted" in item for item in findings), findings)

    def test_pe_certificate_exemption_requires_authenticode_table(self) -> None:
        certificate = bytes.fromhex("3010020100300b06092a864886f70d010101")
        spoofed = bytearray(0x84)
        spoofed[:2] = b"MZ"
        spoofed[0x3C:0x40] = (0x80).to_bytes(4, "little")
        spoofed[0x80:0x84] = b"PE\0\0"
        candidate = BytesIO()
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("spoofed.exe", bytes(spoofed) + certificate)

        findings = scan_zip(BytesIO(candidate.getvalue()), label="spoofed-pe.zip")

        self.assertTrue(
            any("spoofed.exe" in item and "DER signing material" in item for item in findings),
            findings,
        )

    def test_historical_object_inventory_preserves_newline_paths(self) -> None:
        audio_id = "a" * 40
        safe_id = "b" * 40
        raw = (
            f"{audio_id} d/neutral\nclip.wav\n"
            f"{safe_id} docs/safe.txt\n"
        ).encode("utf-8")

        nul_safe = (
            f":000000 100644 {'0' * 40} {audio_id} A\0"
            "d/neutral\nclip.wav\0"
            f":000000 100644 {'0' * 40} {safe_id} A\0"
            "docs/safe.txt\0"
        ).encode("utf-8")

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return raw
            if arguments == [
                "log",
                "--all",
                "-m",
                "--format=",
                "--raw",
                "--no-abbrev",
                "--no-renames",
                "--root",
                "-z",
            ]:
                return nul_safe
            self.fail(f"unexpected git arguments: {arguments}")

        with patch("scripts.check_public_surface.run_git", side_effect=fake_run_git):
            objects = historical_objects()

        self.assertIn((audio_id, PurePosixPath("d/neutral\nclip.wav")), objects)
        self.assertIn((safe_id, PurePosixPath("docs/safe.txt")), objects)

    def test_nul_safe_historical_object_inventory_is_record_bounded(self) -> None:
        object_ids = ("a" * 40, "b" * 40)
        malformed = f"{object_ids[0]} d/neutral\nclip.wav\n".encode("utf-8")
        nul_safe = b"".join(
            f":000000 100644 {'0' * 40} {object_id} A\0path-{index}.dat\0".encode("ascii")
            for index, object_id in enumerate(object_ids)
        )

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return malformed
            return nul_safe

        with (
            patch("scripts.check_public_surface.run_git", side_effect=fake_run_git),
            patch("scripts.check_public_surface.HISTORICAL_OBJECT_CHANGE_RECORD_LIMIT", 1),
            self.assertRaisesRegex(GitScanLimitError, "record scan limit"),
        ):
            historical_objects()

    def test_history_scan_real_git_rejects_deleted_audio_with_newline_path(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)
            newline_name = b"neutral\nclip.wav"

            def git(*arguments: str, input_bytes: bytes | None = None) -> bytes:
                return subprocess.run(
                    ["git", *arguments],
                    cwd=repository,
                    check=True,
                    input=input_bytes,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                ).stdout

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            git("symbolic-ref", "HEAD", "refs/heads/main")
            audio = b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28
            object_id = git("hash-object", "-w", "--stdin", input_bytes=audio).strip()
            nested_tree = git(
                "mktree",
                "-z",
                input_bytes=b"100644 blob " + object_id + b"\t" + newline_name + b"\0",
            ).strip()
            first_tree = git(
                "mktree",
                "-z",
                input_bytes=b"040000 tree " + nested_tree + b"\td\0",
            ).strip()
            first_commit = git("commit-tree", first_tree.decode("ascii"), input_bytes=b"add audio\n").strip()
            git("update-ref", "refs/heads/main", first_commit.decode("ascii"))
            empty_tree = git("mktree", input_bytes=b"").strip()
            second_commit = git(
                "commit-tree",
                empty_tree.decode("ascii"),
                "-p",
                first_commit.decode("ascii"),
                input_bytes=b"remove audio\n",
            ).strip()
            git("update-ref", "refs/heads/main", second_commit.decode("ascii"))

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"],
                cwd=repository,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("audio asset", result.stderr)

    def test_history_scan_rejects_reachable_commit_message_credentials(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments],
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            token = "ghp_" + "c" * 36
            git("commit", "--quiet", "--allow-empty", "-m", "safe subject", "-m", token)

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"],
                cwd=repository,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("commit message", result.stderr)
        self.assertNotIn(token, result.stderr)

    def test_history_scan_rejects_deleted_neutral_binary_audio(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)
            target = repository / "payload.dat"

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments],
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            target.write_bytes(b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 28)
            git("add", "payload.dat")
            git("commit", "--quiet", "-m", "add payload")
            target.unlink()
            git("add", "-u")
            git("commit", "--quiet", "-m", "remove payload")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"],
                cwd=repository,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("audio signature", result.stderr)

    def test_history_scan_classifies_binary_bytes_after_first_four_kib(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)
            target = repository / "payload.dat"

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            token = b"github_pat_" + b"z" * 24
            target.write_bytes(b"A" * 5_000 + b"\0" + token)
            git("add", "payload.dat")
            git("commit", "--quiet", "-m", "add payload")
            target.unlink()
            git("add", "-u")
            git("commit", "--quiet", "-m", "remove payload")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"], cwd=repository,
                check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("history binary", result.stderr)
        self.assertIn("secret-shaped content", result.stderr)
        self.assertNotIn(token.decode("ascii"), result.stderr)

    def test_history_scan_rejects_printable_audio_signature(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)
            target = repository / "payload.dat"

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            target.write_bytes(b"#!AMR\n" + b"A" * 5_000)
            git("add", "payload.dat")
            git("commit", "--quiet", "-m", "add payload")
            target.unlink()
            git("add", "-u")
            git("commit", "--quiet", "-m", "remove payload")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"], cwd=repository,
                check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("AMR-NB", result.stderr)

    def test_history_scan_dispatches_each_commit_message_independently(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )

            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            git("commit", "--quiet", "--allow-empty", "-m", "RIFF1234WAVE")
            git("commit", "--quiet", "--allow-empty", "-m", "safe newest message")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"], cwd=repository,
                check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("commit", result.stderr)
        self.assertIn("RIFF/WAVE", result.stderr)

    def test_history_scan_rejects_commit_header_credentials(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )

            token = "github_pat_" + "h" * 24
            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", token)
            git("commit", "--quiet", "--allow-empty", "-m", "safe message")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"], cwd=repository,
                check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("commit", result.stderr)
        self.assertIn("header", result.stderr)
        self.assertNotIn(token, result.stderr)

    def test_history_scan_rejects_annotated_tag_header_credentials(self) -> None:
        with TemporaryDirectory() as directory:
            repository = Path(directory)

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )

            token = "github_pat_" + "t" * 24
            git("init", "--quiet")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Public Surface Fixture")
            git("commit", "--quiet", "--allow-empty", "-m", "safe commit")
            git("config", "user.name", token)
            git("tag", "-a", "v-fixture", "-m", "safe tag message")

            script = Path(__file__).resolve().parents[2] / "scripts" / "check_public_surface.py"
            result = subprocess.run(
                [sys.executable, str(script), "--history"], cwd=repository,
                check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8",
            )

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("annotated tag", result.stderr)
        self.assertIn("header", result.stderr)
        self.assertNotIn(token, result.stderr)


if __name__ == "__main__":
    unittest.main()
