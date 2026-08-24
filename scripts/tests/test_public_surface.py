from __future__ import annotations

import unittest
import zipfile
from io import BytesIO
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts.check_public_surface import (
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
        source_upload = workflow.index("name: choplab-source-snapshot")

        self.assertLess(public_scan, policy_tests)
        self.assertLess(policy_tests, source_archive)
        self.assertLess(source_archive, source_upload)


if __name__ == "__main__":
    unittest.main()
