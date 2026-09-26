import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error

from scripts.prepare_media_tools import REQUIRED_FILES, TOOL_VERSIONS, cache_valid, digest, download, fetch


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


class MediaToolsDownloadRetryTest(unittest.TestCase):
    """Upstream mirrors sometimes answer 503 or drop the connection; a bounded retry keeps CI honest."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.waits = []

    def opener(self, *outcomes):
        calls = []

        def open_url(url, timeout):
            calls.append((url, timeout))
            outcome = outcomes[min(len(calls), len(outcomes)) - 1]
            if isinstance(outcome, BaseException):
                raise outcome
            return io.BytesIO(outcome)
        open_url.calls = calls
        return open_url

    @staticmethod
    def http(code):
        return urllib.error.HTTPError('https://example.invalid/tool.zip', code, 'status', {}, None)

    def test_transient_answers_are_retried_with_growing_waits(self):
        opener = self.opener(self.http(503), urllib.error.URLError('reset'), b'tool bytes')
        target = self.root / 'tool.zip'
        download('https://example.invalid/tool.zip', target, opener=opener, wait=2.0, sleep=self.waits.append)
        self.assertEqual(b'tool bytes', target.read_bytes())
        self.assertEqual(3, len(opener.calls))
        self.assertEqual([2.0, 4.0], self.waits)
        self.assertFalse((self.root / 'tool.zip.part').exists())

    def test_permanent_answer_fails_at_once_and_leaves_no_file(self):
        opener = self.opener(self.http(404))
        target = self.root / 'tool.zip'
        with self.assertRaises(urllib.error.HTTPError):
            download('https://example.invalid/tool.zip', target, opener=opener, sleep=self.waits.append)
        self.assertEqual(1, len(opener.calls))
        self.assertEqual([], self.waits)
        self.assertEqual([], list(self.root.iterdir()))

    def test_retries_are_bounded(self):
        opener = self.opener(self.http(503))
        with self.assertRaises(urllib.error.HTTPError):
            fetch('https://example.invalid/SHASUMS', opener=opener, attempts=3, sleep=self.waits.append)
        self.assertEqual(3, len(opener.calls))
        self.assertEqual(2, len(self.waits))

    def test_interrupted_stream_never_looks_complete(self):
        class Broken(io.RawIOBase):
            def readable(self):
                return True

            def readinto(self, buffer):
                raise ConnectionResetError('peer closed mid-file')

        target = self.root / 'tool.zip'
        opener_calls = []

        def open_url(url, timeout):
            opener_calls.append(url)
            return io.BufferedReader(Broken()) if len(opener_calls) == 1 else io.BytesIO(b'whole file')
        download('https://example.invalid/tool.zip', target, opener=open_url, sleep=self.waits.append)
        self.assertEqual(b'whole file', target.read_bytes())
        self.assertEqual(2, len(opener_calls))
        self.assertFalse((self.root / 'tool.zip.part').exists())
