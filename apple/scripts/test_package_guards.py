"""Local package guards only; these fixtures do not prove an Apple build or device run."""
import hashlib
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_simulator as package

REVISION = "a" * 40


class StudioGuards(unittest.TestCase):
    def fixture(self, phase="first"):
        return {"livePlayback": "verified-real-output", "physicalSoundVerified": False,
                "studioAcceptance": {"phase": phase, "originalBytesPreserved": True,
                    "originalHash": "a" * 64,
                    "songSourceHandIndependent": True, "consumedFramesDelta": 2048, "exportBits": 24,
                    "exportFrames": 96000, "autosaveFlushed": True, "archiveVerified": True,
                    "newUndoRedoAfterRestart": phase == "relaunch", "livePlayback": "verified-real-output",
                    "physicalSoundVerified": False}}

    def test_both_process_phases_require_actual_studio_results(self):
        for phase in ("first", "relaunch"):
            package.verify_studio(self.fixture(phase), phase)

    def test_missing_restore_consumption_and_false_human_claims_are_rejected(self):
        for changes in ({"consumedFramesDelta": 0}, {"consumedFramesDelta": True},
                        {"newUndoRedoAfterRestart": False}, {"phase": "first"},
                        {"physicalSoundVerified": True}, {"exportBits": 16}, {"archiveVerified": False},
                        {"originalHash": None}, {"originalHash": "bad"}):
            fixture = copy.deepcopy(self.fixture("relaunch"))
            fixture["studioAcceptance"].update(changes)
            with self.assertRaises(RuntimeError):
                package.verify_studio(fixture, "relaunch")
        with self.assertRaises(RuntimeError):
            package.verify_studio({}, "first")


class NoticeGuards(unittest.TestCase):
    def setUp(self):
        parent = package.ROOT / "work"
        parent.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / "ChopLab.app"
        root = self.app / "source-notices"
        root.mkdir(parents=True)
        license_bytes = b"license fixture\n"
        self.index = {"revision": REVISION, "hasLocalChanges": False,
                      "noticeCoverageComplete": False, "sourcePublicationVerified": False, "publicPass": False,
                      "files": [{"path": "LICENSE", "bytes": len(license_bytes),
                                 "sha256": hashlib.sha256(license_bytes).hexdigest()}]}
        self.canonical = {"LICENSE": license_bytes, "SOURCE-INDEX.json": json.dumps(self.index).encode()}
        for name, data in self.canonical.items():
            (root / name).write_bytes(data)
        self.addCleanup(patch.stopall)
        patch.object(package, "canonical_notices", return_value=self.canonical).start()
        patch.object(package, "read", return_value=REVISION).start()

    def test_matches_actual_committed_source(self):
        self.assertEqual(1, package.verify_notices(self.app)["noticeFiles"])

    def test_modified_license_and_self_updated_index_are_rejected(self):
        root = self.app / "source-notices"
        data = b"replacement"
        (root / "LICENSE").write_bytes(data)
        self.index["files"][0].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        (root / "SOURCE-INDEX.json").write_text(json.dumps(self.index), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "canonical"):
            package.verify_notices(self.app)

    def test_missing_notice_is_rejected(self):
        (self.app / "source-notices/LICENSE").unlink()
        with self.assertRaisesRegex(RuntimeError, "canonical"):
            package.verify_notices(self.app)

    def test_extra_notice_is_rejected(self):
        (self.app / "source-notices/unknown").write_text("unknown", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "canonical"):
            package.verify_notices(self.app)

    def test_another_source_revision_is_rejected(self):
        with patch.object(package, "read", return_value="b" * 40):
            with self.assertRaisesRegex(RuntimeError, "source revision"):
                package.verify_notices(self.app)


if __name__ == "__main__":
    unittest.main()
