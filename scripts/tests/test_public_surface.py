from __future__ import annotations

import struct
import unittest
import zipfile
import zlib
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts.check_public_surface import (
    main,
    scan_history,
    scan_public_path,
    scan_text,
    scan_zip,
    suspicious_path,
)


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
            archive.writestr("ChopLab/runtime/lib/ct.sym", b"PK\x03\x04binary")
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

    def test_zip_content_scan_does_not_treat_binary_or_audio_bytes_as_text(self) -> None:
        token = ("github_pat_" + "a" * 24).encode("ascii")
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "assets.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("data.bin", b"\x00\x01" + token)
                archive.writestr("samples/tone.wav", b"RIFF\x00\x00" + token)

            findings = scan_zip(archive_path)

        self.assertEqual(1, len(findings))
        self.assertIn("audio asset", findings[0])
        self.assertNotIn("secret-shaped content", findings[0])

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

    def test_public_zip_is_not_rescanned_as_one_text_container(self) -> None:
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

        self.assertEqual([], findings)

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

    def test_directory_marked_zip_entry_with_payload_is_rejected(self) -> None:
        token = "github_pat_" + "a" * 24
        with TemporaryDirectory() as directory:
            archive_path = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("docs/notes.txt/", token)

            findings = scan_zip(archive_path)

        self.assertTrue(any("directory-marked entry contains data" in item for item in findings))

    def test_history_scan_reads_reachable_zip_blob_content(self) -> None:
        object_id = "a" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "a" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} docs/removed.zip\n".encode("ascii")
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

    def test_history_scan_keeps_zip_alias_when_rev_list_uses_another_path(self) -> None:
        object_id = "b" * 40
        payload = BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.writestr("docs/notes.txt", "github_pat_" + "b" * 24)
        archive_bytes = payload.getvalue()

        def fake_run_git(arguments: list[str]) -> bytes:
            if arguments == ["rev-list", "--objects", "--all"]:
                return f"{object_id} copies/archive.dat\n".encode("ascii")
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

    def test_desktop_source_snapshot_is_scanned_before_archive_and_upload(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "desktop.yml"
        ).read_text(encoding="utf-8")

        policy_tests = workflow.index("python -m unittest discover -s scripts/tests")
        public_scan = workflow.index("python scripts/check_public_surface.py")
        source_archive = workflow.index("git archive --format=zip")
        archive_scan = workflow.index(
            "python scripts/check_public_surface.py --archive "
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

    def test_release_scans_exact_archives_before_manifest_and_publication(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "release.yml"
        ).read_text(encoding="utf-8")

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
            "name: Upload Windows assets",
            windows_stage_scan,
        )
        self.assertLess(ios_build, ios_stage_scan)
        self.assertLess(ios_stage_scan, ios_upload)
        self.assertLess(windows_archive, windows_stage_scan)
        self.assertLess(windows_stage_scan, windows_upload)

        download = workflow.index("name: Download platform assets", windows_upload)
        scan = workflow.index("name: Scan final public archives", download)
        ios_archive = workflow.index(
            'dist/ChopLab-${RELEASE_TAG}-ios-simulator.app.zip',
            scan,
        )
        final_windows_archive = workflow.index(
            'dist/ChopLab-${RELEASE_TAG}-windows-app-image.zip',
            scan,
        )
        manifest = workflow.index("name: Write source-bound manifest and checksums")
        attest = workflow.index("name: Attest build provenance")
        publish = workflow.index("name: Publish once without asset replacement")

        self.assertLess(download, scan)
        self.assertLess(scan, ios_archive)
        self.assertLess(scan, final_windows_archive)
        self.assertLess(ios_archive, manifest)
        self.assertLess(final_windows_archive, manifest)
        self.assertLess(manifest, attest)
        self.assertLess(attest, publish)


if __name__ == "__main__":
    unittest.main()
