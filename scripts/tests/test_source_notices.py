from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import prepare_source_notices as notices
from scripts.check_public_surface import scan_zip

ROOT = Path(__file__).resolve().parents[2]
REVISION = "a" * 40


def committed_git(root, *args):
    """An immutable Git view of the fixture's explicitly supplied source files."""
    if args == ("rev-parse", "HEAD"):
        return (REVISION + "\n").encode()
    if args[0] == "status":
        return b""
    if args[0] == "show":
        return (root / args[1].split(":", 1)[1]).read_bytes()
    raise AssertionError(args)


class SourceNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.output = self.directory / "notices"
        self.archive = self.directory / "source-notices.zip"
        self.git = patch.object(notices, "git", side_effect=committed_git)
        self.git.start()
        self.addCleanup(self.git.stop)

    def stage(self, **kwargs):
        return notices.stage(ROOT, self.output, "android", archive=self.archive, **kwargs)

    def rewrite_archive(self, replace):
        with zipfile.ZipFile(self.archive) as archive:
            files = [(row, archive.read(row)) for row in archive.infolist()]
        with zipfile.ZipFile(self.archive, "w") as archive:
            for row, data in files:
                archive.writestr(row, replace(row.filename, data))

    def test_notice_full_text_licenses_and_local_links_are_delivered_without_native_changes(self):
        self.output.mkdir()
        native = self.output / "private-existing-runtime.so"
        native.write_bytes(b"existing native bytes")
        index = self.stage(require_committed=True)
        self.assertEqual(b"existing native bytes", native.read_bytes())
        self.assertEqual((ROOT / "NOTICE.md").read_bytes(), (self.output / "NOTICE.md").read_bytes())
        for name in ("LICENSE", "licenses/onnxruntime-LICENSE.txt", "licenses/quickjs-LICENSE.txt"):
            self.assertEqual((ROOT / name).read_bytes(), (self.output / name).read_bytes())
        nio = (self.output / notices.NIO_LICENSE).read_bytes()
        self.assertEqual(1491, len(nio))
        self.assertEqual("68834f116f8ff545f05d14753357b620748156d60ee36b26beab4cb3f317efe4", notices.digest(nio))
        self.assertTrue((self.output / "docs/RELEASE.md").is_file())
        release = (self.output / "docs/RELEASE.md").read_text()
        self.assertIn(f"https://github.com/dj-thank/choplab-sampler/blob/{REVISION}/docs/ROADMAP.md", release)
        self.assertNotIn("](ROADMAP.md)", release)
        self.assertFalse(index["sourcePublicationVerified"])
        self.assertFalse(index["publicPass"])
        self.assertEqual(3, len(index["unresolved"]))
        with zipfile.ZipFile(self.archive) as archive:
            self.assertFalse(any("private-existing" in name for name in archive.namelist()))
        notices.validate_archive(self.archive, REVISION)

    def test_archive_is_deterministic_and_input_pins_are_unchanged(self):
        before = {name: (ROOT / name).read_bytes() for name in notices.INPUTS}
        self.stage()
        first = self.archive.read_bytes()
        self.stage()
        self.assertEqual(first, self.archive.read_bytes())
        self.assertEqual(before, {name: (ROOT / name).read_bytes() for name in notices.INPUTS})

    def test_recipe_display_preserves_exact_source_and_distinct_original_identity(self):
        index = self.stage(require_committed=True)
        recipes = [row for row in index["files"] if row.get("sourcePath", "").endswith(".py")]
        self.assertEqual(10, len(recipes))
        self.assertIn("scripts/mac_tool_policy.py", {row["sourcePath"] for row in recipes})
        for row in recipes:
            with self.subTest(source=row["sourcePath"]):
                original = (ROOT / row["sourcePath"]).read_bytes()
                displayed = (self.output / row["path"]).read_bytes()
                self.assertEqual(row["sourcePath"] + ".txt", row["path"])
                self.assertEqual("plain-source-text-v1", row["displayTransform"])
                self.assertEqual(len(original), row["sourceBytes"])
                self.assertEqual(notices.digest(original), row["sourceSha256"])
                self.assertEqual(notices.digest(displayed), row["sha256"])
                self.assertNotEqual(row["sha256"], row["sourceSha256"])
                self.assertEqual(notices.immutable_url(REVISION, row["sourcePath"]), row["immutableUrl"])
                header, body = displayed.split(notices.RECIPE_BEGIN, 1)
                self.assertTrue(header.startswith(b"Source recipe display (plain text; not executable)\n"))
                self.assertIn(row["sourcePath"].encode(), header)
                self.assertIn(row["sourceSha256"].encode(), header)
                self.assertIn(row["immutableUrl"].encode(), header)
                self.assertEqual(original, body[:row["sourceBytes"]])
                self.assertEqual(notices.RECIPE_END, body[row["sourceBytes"]:])
                displayed.decode("utf-8")
        self.assertIn("原source bytesは無改変", (self.output / notices.README).read_text())
        self.assertIn("再build用の元 `.py`", (self.output / notices.README).read_text())
        with zipfile.ZipFile(self.archive) as archive:
            self.assertEqual(30, len(archive.namelist()))
        notices.validate_archive(self.archive, REVISION)

    def test_displayed_recipes_pass_apk_scan_while_raw_scripts_and_secrets_still_fail(self):
        self.stage()
        apk = self.directory / "notice-assets.apk"
        files = {str(path.relative_to(self.output)).replace("\\", "/"): path.read_bytes()
                 for path in self.output.rglob("*") if path.is_file()}

        def write_apk(payload):
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in payload.items():
                    archive.writestr("assets/source-notices/" + name, data)

        write_apk(files)
        self.assertEqual([], scan_zip(apk))
        raw_recipes = dict(files)
        for name in notices.INPUTS:
            if name.endswith(".py"):
                raw_recipes[notices.packaged_path(name)] = (ROOT / name).read_bytes()
        write_apk(raw_recipes)
        findings = scan_zip(apk)
        self.assertEqual(5, len(findings))
        self.assertTrue(all("unknown executable script in APK" in finding for finding in findings))
        exposed = dict(files)
        name = notices.packaged_path("scripts/build_android_audio_runtime.py")
        exposed[name] = exposed[name].replace(notices.RECIPE_BEGIN,
            notices.RECIPE_BEGIN + b"# " + b"github_pat_" + b"a" * 24 + b"\n", 1)
        write_apk(exposed)
        self.assertTrue(any("secret-shaped content" in finding for finding in scan_zip(apk)))

    def test_recipe_header_and_body_cannot_be_replaced_with_coherently_rehashed_index(self):
        source = "scripts/build_android_audio_runtime.py"
        target = notices.packaged_path(source)
        for part in ("header", "body"):
            with self.subTest(part=part):
                self.stage()
                data = (self.output / target).read_bytes()
                replacement = (data.replace(b"Source recipe display", b"Unreviewed source claim", 1)
                               if part == "header" else data.replace(notices.RECIPE_BEGIN,
                                   notices.RECIPE_BEGIN + b"# modified original body\n", 1))

                def replace(name, value):
                    if name == notices.ARCHIVE_ROOT + target:
                        return replacement
                    if name == notices.ARCHIVE_ROOT + notices.INDEX:
                        document = json.loads(value)
                        row = next(row for row in document["files"] if row["path"] == target)
                        row.update(bytes=len(replacement), sha256=notices.digest(replacement))
                        return (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
                    return value

                self.rewrite_archive(replace)
                with self.assertRaisesRegex(ValueError, "canonical bytes"):
                    notices.validate_archive(self.archive, REVISION)

    def test_reused_output_refuses_legacy_raw_recipe_before_writing_or_removing_it(self):
        legacy = self.output / "scripts/build_android_audio_runtime.py"
        legacy.parent.mkdir(parents=True)
        original = (ROOT / "scripts/build_android_audio_runtime.py").read_bytes()
        legacy.write_bytes(original)
        with self.assertRaisesRegex(ValueError, "clean generated notice output"):
            self.stage()
        self.assertEqual(original, legacy.read_bytes())
        self.assertFalse((self.output / "LICENSE").exists())
        self.assertFalse((self.output / notices.packaged_path("scripts/build_android_audio_runtime.py")).exists())

    def test_dirty_recipe_has_no_false_immutable_link_and_publication_refuses(self):
        def dirty(root, *args):
            if args[0] == "status":
                return b" M scripts/build_windows_audio_runtime.py\n"
            if args == ("show", REVISION + ":scripts/build_windows_audio_runtime.py"):
                return b"old recipe"
            return committed_git(root, *args)
        with patch.object(notices, "git", side_effect=dirty):
            index = self.stage()
            row = next(row for row in index["files"] if row["sourcePath"] == "scripts/build_windows_audio_runtime.py")
            self.assertIsNone(row["immutableUrl"])
            self.assertFalse(row["matchesRevision"])
            self.assertTrue(index["hasLocalChanges"])
            self.assertIsNone(index["sourceSnapshotUrl"])
            with self.assertRaisesRegex(ValueError, "unchanged committed"):
                self.stage(require_committed=True)

    def test_symlink_destination_is_rejected_before_any_notice_is_written(self):
        outside = self.directory / "outside"
        outside.mkdir()
        self.output.mkdir()
        try:
            (self.output / "licenses").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("OS account cannot create symlinks")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.stage()
        self.assertFalse((self.output / "LICENSE").exists())
        self.assertEqual([], list(outside.iterdir()))

    def test_changed_embedded_license_is_rejected(self):
        original = notices.regular_file
        altered = self.directory / "NOTICE.md"
        altered.write_bytes((ROOT / "NOTICE.md").read_bytes().replace(b"All rights reserved.", b"Removed."))
        with patch.object(notices, "regular_file", side_effect=lambda root, name: altered if name == "NOTICE.md" else original(root, name)):
            with self.assertRaisesRegex(ValueError, "license differs"):
                self.stage()
        self.assertFalse(self.output.exists())

    def test_release_refuses_an_index_from_a_different_source_revision(self):
        self.stage()
        with self.assertRaisesRegex(ValueError, "revision"):
            notices.validate_archive(self.archive, "b" * 40)

    def test_release_refuses_unindexed_payload_and_changed_notice_bytes(self):
        self.stage()
        self.rewrite_archive(lambda name, data: b"changed" if name.endswith("/NOTICE.md") else data)
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            notices.validate_archive(self.archive, REVISION)
        self.stage()
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("source-notices/private-model.onnx", b"unreviewed payload")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            notices.validate_archive(self.archive, REVISION)

    def test_release_refuses_notice_or_markdown_with_coherently_rehashed_index(self):
        for target in ("NOTICE.md", "docs/RELEASE.md"):
            with self.subTest(target=target):
                self.stage()
                replacement = b"Replacement: original notice and links removed.\n"
                def replace(name, data):
                    if name == notices.ARCHIVE_ROOT + target:
                        return replacement
                    if name == notices.ARCHIVE_ROOT + notices.INDEX:
                        document = json.loads(data)
                        row = next(row for row in document["files"] if row["path"] == target)
                        row.update(bytes=len(replacement), sha256=notices.digest(replacement),
                                   sourceSha256=notices.digest(replacement))
                        return (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
                    return data
                self.rewrite_archive(replace)
                with self.assertRaisesRegex(ValueError, "canonical bytes"):
                    notices.validate_archive(self.archive, REVISION)

    def test_release_refuses_changed_readme_or_index_with_valid_payload_hashes(self):
        for target in (notices.README, notices.INDEX):
            with self.subTest(target=target):
                self.stage()
                def replace(name, data):
                    if name != notices.ARCHIVE_ROOT + target:
                        return data
                    if target == notices.README:
                        return data + b"\nUnsupported source-completeness claim.\n"
                    document = json.loads(data)
                    document["files"][0]["sourceSha256"] = "0" * 64
                    return (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
                self.rewrite_archive(replace)
                with self.assertRaisesRegex(ValueError, "canonical bytes"):
                    notices.validate_archive(self.archive, REVISION)

    def test_release_requires_verifiable_clean_source_at_the_declared_revision(self):
        self.stage()
        for state in ("different-head", "dirty", "missing-git", "missing-blob"):
            with self.subTest(state=state):
                def unavailable(root, *args):
                    if state == "different-head" and args == ("rev-parse", "HEAD"):
                        return ("b" * 40 + "\n").encode()
                    if state == "dirty" and args[0] == "status":
                        return b" M NOTICE.md\n"
                    if state == "missing-git":
                        raise FileNotFoundError("git unavailable")
                    if state == "missing-blob" and args == ("show", REVISION + ":NOTICE.md"):
                        raise subprocess.CalledProcessError(128, "git show")
                    return committed_git(root, *args)
                with patch.object(notices, "git", side_effect=unavailable):
                    with self.assertRaisesRegex(ValueError, "source checkout"):
                        notices.validate_archive(self.archive, REVISION)

    def test_release_verifies_a_real_git_checkout_and_refuses_subsequent_local_changes(self):
        self.git.stop()
        source = self.directory / "source"
        source.mkdir()
        for name in (*notices.INPUTS, "docs/ROADMAP.md", "docs/TESTING.md", "docs/adr/ADR-0010-ipad-native-support.md"):
            destination = source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        notices.git(source, "init", "--quiet")
        notices.git(source, "config", "core.autocrlf", "false")
        notices.git(source, "add", ".")
        hooks = self.directory / "empty-hooks"
        hooks.mkdir()
        notices.git(source, "-c", "user.name=Source Notice Fixture",
                    "-c", "user.email=source-notice-fixture@example.invalid",
                    "-c", "commit.gpgsign=false", "-c", f"core.hooksPath={hooks}",
                    "commit", "--quiet", "-m", "Commit fixture notice inputs")
        revision = notices.git(source, "rev-parse", "HEAD").decode().strip()
        notices.stage(source, self.output, "all", archive=self.archive, require_committed=True)
        notices.validate_archive(self.archive, revision, root=source)
        with (source / "NOTICE.md").open("ab") as notice:
            notice.write(b"\nUncommitted notice change.\n")
        with self.assertRaisesRegex(ValueError, "unchanged committed source checkout"):
            notices.validate_archive(self.archive, revision, root=source)

    def test_release_refuses_false_completeness_or_hidden_unresolved_mapping(self):
        for field in ("sourcePublicationVerified", "correspondingSourceComplete", "noticeCoverageComplete", "publicPass", "unresolved"):
            with self.subTest(field=field):
                self.stage()
                def replace(name, data):
                    if name != notices.ARCHIVE_ROOT + notices.INDEX:
                        return data
                    document = json.loads(data)
                    document[field] = [] if field == "unresolved" else True
                    return json.dumps(document).encode()
                self.rewrite_archive(replace)
                with self.assertRaisesRegex(ValueError, "publication gates|unresolved correspondence"):
                    notices.validate_archive(self.archive, REVISION)


if __name__ == "__main__":
    unittest.main()
