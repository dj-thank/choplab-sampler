"""Regressions for refreshed publication history without changing release identity."""
from __future__ import annotations

import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/release.yml"
GATE_NAME = "Revalidate annotated tag and release history before publication"
MAIN_REF = "refs/remotes/origin/main"


def gate_script() -> str:
    text = WORKFLOW.read_text(encoding="utf-8")
    step = text.split(f"      - name: {GATE_NAME}\n", 1)[1]
    step = step.split("\n      - name:", 1)[0]
    return "\n".join(line[10:] for line in step.split("        run: |\n", 1)[1].splitlines())


def command(script: str, prefix: str) -> list[str]:
    """Read a command from the actual workflow, without requiring bash on Windows."""
    lines = script.replace("\\\n", " ").splitlines()
    matches = [shlex.split(line.strip()) for line in lines if line.strip().startswith(prefix)]
    if len(matches) != 1:
        raise AssertionError(f"Expected one {prefix!r} command, found {len(matches)}")
    return matches[0]


class PublicationHistoryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.origin = self.root / "origin.git"
        self.seed = self.root / "seed"
        self.publisher = self.root / "publisher"
        self.run_git(self.root, "init", "--bare", str(self.origin))
        self.run_git(self.root, "init", "-b", "main", str(self.seed))
        self.run_git(self.seed, "config", "user.name", "Release fixture")
        self.run_git(self.seed, "config", "user.email", "release@example.invalid")
        self.run_git(self.seed, "config", "commit.gpgsign", "false")
        self.run_git(self.seed, "config", "tag.gpgsign", "false")
        self.run_git(self.seed, "remote", "add", "origin", str(self.origin))
        self.add_version("0.16.1", 1601, tag=True)
        self.release_sha = self.add_version("0.16.2", 1602, tag=True)
        self.publish_refs()
        self.run_git(self.root, "clone", "--branch", "main", str(self.origin), str(self.publisher))
        self.run_git(self.publisher, "checkout", "--detach", self.release_sha)

    def run_git(self, path: Path, *args: str) -> str:
        result = subprocess.run(["git", "-C", str(path), *args], text=True, encoding="utf-8",
                                errors="replace", capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def add_version(self, version: str, build: int, *, tag: bool) -> str:
        (self.seed / "gradle.properties").write_text(
            f"choplabVersion={version}\nchoplabBuildNumber={build}\n", encoding="utf-8")
        self.run_git(self.seed, "add", "gradle.properties")
        self.run_git(self.seed, "commit", "-m", f"Version {version} build {build}")
        if tag:
            self.run_git(self.seed, "tag", "-a", f"v{version}", "-m", f"Release {version}")
        return self.run_git(self.seed, "rev-parse", "HEAD")

    def publish_refs(self) -> None:
        self.run_git(self.seed, "push", "origin", "main", "--tags")

    def refresh(self) -> subprocess.CompletedProcess[str]:
        args = command(gate_script(), "git fetch ")
        return subprocess.run(args, cwd=self.publisher, capture_output=True,
                              text=True, encoding="utf-8", check=False)

    def validate(self, *, old_gate: bool = False, tag: str = "v0.16.2") -> subprocess.CompletedProcess[str]:
        args = command(gate_script(), "python3 scripts/release_metadata.py")
        args[0:2] = [sys.executable, str(ROOT / "scripts/release_metadata.py")]
        args = [arg.replace("$GITHUB_SHA", self.release_sha).replace("$RELEASE_TAG", tag) for arg in args]
        if old_gate:
            args[args.index("--reachable-ref") + 1] = self.release_sha
        return subprocess.run(args, cwd=self.publisher, capture_output=True,
                              text=True, encoding="utf-8", check=False)

    def test_paused_old_release_cannot_publish_after_new_main_release(self) -> None:
        self.add_version("0.16.3", 1603, tag=True)
        self.publish_refs()
        self.assertEqual(self.run_git(self.publisher, "rev-parse", MAIN_REF), self.release_sha)
        self.assertEqual(self.refresh().returncode, 0)
        self.assertEqual(self.validate(old_gate=True).returncode, 0, "Old gate must reproduce the bug")
        result = self.validate()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not monotonic", result.stderr)
        self.assertEqual(self.run_git(self.publisher, "rev-parse", "HEAD"), self.release_sha)

    def test_untagged_main_version_does_not_replace_publishing_metadata(self) -> None:
        self.add_version("9.0.0", 9000, tag=False)
        self.publish_refs()
        self.assertEqual(self.refresh().returncode, 0)
        result = self.validate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('"version": "0.16.2"', result.stdout)
        self.assertEqual(self.run_git(self.publisher, "rev-parse", "HEAD"), self.release_sha)

    def test_future_tag_not_reachable_from_main_is_not_release_history(self) -> None:
        self.run_git(self.seed, "checkout", "-b", "experiment")
        self.add_version("9.0.0", 9000, tag=True)
        self.run_git(self.seed, "push", "origin", "experiment", "--tags")
        self.assertEqual(self.refresh().returncode, 0)
        self.assertEqual(self.validate().returncode, 0)

    def test_new_main_preview_tag_with_duplicate_build_number_is_rejected(self) -> None:
        self.add_version("0.16.1", 1602, tag=False)
        self.run_git(self.seed, "tag", "-a", "v0.16.1-preview.2", "-m", "Historical preview")
        self.publish_refs()
        self.assertEqual(self.refresh().returncode, 0)
        self.assertEqual(self.validate(old_gate=True).returncode, 0)
        result = self.validate()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build number is not monotonic", result.stderr)

    def test_missing_origin_main_fails_closed(self) -> None:
        self.run_git(self.origin, "config", "receive.denyDeleteCurrent", "ignore")
        self.run_git(self.seed, "push", "origin", ":main")
        self.assertNotEqual(self.refresh().returncode, 0)

    def test_retargeted_release_tag_is_not_silently_overwritten(self) -> None:
        self.add_version("0.16.3", 1603, tag=False)
        self.run_git(self.seed, "tag", "-fa", "v0.16.2", "-m", "Invalid retarget")
        self.run_git(self.seed, "push", "--force", "origin", "refs/tags/v0.16.2")
        self.assertNotEqual(self.refresh().returncode, 0)

    def test_whitespace_tag_input_remains_invalid(self) -> None:
        self.assertNotEqual(self.validate(tag=" v0.16.2 ").returncode, 0)


class PublicationContractTests(unittest.TestCase):
    def test_fresh_gate_is_last_step_before_immutable_publication(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        names = re.findall(r"^      - name: (.*)$", text, flags=re.MULTILINE)
        self.assertEqual(names[names.index("Publish once without asset replacement") - 1], GATE_NAME)
        script = gate_script()
        fetch = command(script, "git fetch ")
        self.assertIn("+refs/heads/main:refs/remotes/origin/main", fetch)
        self.assertIn("refs/tags/*:refs/tags/*", fetch)
        self.assertNotIn("--force", fetch)
        metadata = command(script, "python3 scripts/release_metadata.py")
        self.assertEqual(metadata[metadata.index("--commit") + 1], "$GITHUB_SHA")
        self.assertEqual(metadata[metadata.index("--reachable-ref") + 1], MAIN_REF)
        self.assertIn("--verify-annotated-tag", metadata)
        self.assertIn("--check-monotonic", metadata)
        self.assertIn('git merge-base --is-ancestor "$GITHUB_SHA" refs/remotes/origin/main', script)
        self.assertIn("exit 1", script)
        self.assertNotIn("git checkout", script)


if __name__ == "__main__":
    unittest.main()
