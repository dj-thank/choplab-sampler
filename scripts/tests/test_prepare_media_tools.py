import json
from pathlib import Path
import tempfile
import unittest

from scripts.prepare_media_tools import REQUIRED_FILES, TOOL_VERSIONS, cache_valid, digest


class MediaToolsCacheTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name in REQUIRED_FILES:
            (self.root / name).write_bytes(name.encode('ascii'))
        self.manifest = dict(TOOL_VERSIONS, sha256={name: digest(self.root/name) for name in REQUIRED_FILES})
        self.write()

    def write(self):
        (self.root/'runtime.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def test_exact_versions_and_hashes_pass(self):
        self.assertTrue(cache_valid(self.root))

    def test_empty_or_incomplete_hash_manifest_fails(self):
        self.manifest['sha256'] = {}
        self.write()
        self.assertFalse(cache_valid(self.root))

    def test_stale_or_ambient_version_manifest_fails(self):
        self.manifest['ffmpeg'] = 'ambient'
        self.write()
        self.assertFalse(cache_valid(self.root))

    def test_modified_binary_or_missing_license_fails(self):
        (self.root/'node.exe').write_bytes(b'changed')
        self.assertFalse(cache_valid(self.root))
        (self.root/'FFmpeg-LICENSE.txt').unlink()
        self.assertFalse(cache_valid(self.root))
