import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.check_repo import contains_personal_path, structural_findings


class RepositoryCheckTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'gradle/wrapper').mkdir(parents=True)
        (self.root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'fixture')
        for name in ('gradlew', 'gradlew.bat'):
            (self.root / name).write_text('-Dfile.encoding=UTF-8', encoding='utf-8')
        (self.root / 'app/src/main').mkdir(parents=True)
        (self.root / 'app/src/main/AndroidManifest.xml').write_text('<manifest/>', encoding='utf-8')
        (self.root / '.github/workflows').mkdir(parents=True)
        self.workflow = self.root / '.github/workflows/ci.yml'
        self.workflow.write_text('uses: actions/checkout@' + 'a' * 40, encoding='utf-8')
        mocked = patch('scripts.check_repo.WRAPPER_SHA256', hashlib.sha256(b'fixture').hexdigest())
        mocked.start()
        self.addCleanup(mocked.stop)

    def test_valid_fixture_passes(self):
        self.assertEqual([], structural_findings(self.root))

    def test_tampered_wrapper_is_rejected(self):
        (self.root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'tampered')
        self.assertTrue(any('checksum' in finding for finding in structural_findings(self.root)))

    def test_mutable_action_ref_is_rejected(self):
        self.workflow.write_text('uses: actions/checkout@v7', encoding='utf-8')
        self.assertTrue(any('pinned' in finding for finding in structural_findings(self.root)))

    def test_malformed_xml_is_rejected(self):
        (self.root / 'app/src/main/AndroidManifest.xml').write_text('<manifest>', encoding='utf-8')
        self.assertTrue(any('Invalid Android XML' in finding for finding in structural_findings(self.root)))

    def test_wrapper_without_utf8_is_rejected(self):
        (self.root / 'gradlew.bat').write_text('java', encoding='utf-8')
        self.assertTrue(any('UTF-8' in finding for finding in structural_findings(self.root)))

    def test_personal_path_is_rejected_but_placeholder_and_ci_paths_are_allowed(self):
        private_user = 'private' + '-person'
        self.assertTrue(contains_personal_path('C:/Users/' + private_user + '/repo'))
        self.assertTrue(contains_personal_path('/home/' + private_user + '/repo'))
        self.assertFalse(contains_personal_path('C:/Users/USER/repo'))
        self.assertFalse(contains_personal_path('/home/runner/work/repo'))

    def test_container_exception_only_covers_gradle_volume_and_does_not_flag_its_definition(self):
        container_home = '/home/' + 'vscode'
        self.assertFalse(contains_personal_path(container_home + '/.gradle,type=volume'))
        self.assertTrue(contains_personal_path(container_home + '/documents'))
        self.assertTrue(contains_personal_path(container_home + '/.gradle-other'))
        checker = Path(__file__).resolve().parents[1] / 'check_repo.py'
        self.assertFalse(contains_personal_path(checker.read_text(encoding='utf-8')))

    def test_mac_jdk_layout_is_not_a_personal_home(self):
        self.assertFalse(contains_personal_path('ChopLab Preview.app/Contents/runtime/Contents/Home/lib/security/cacerts'))
        self.assertTrue(contains_personal_path('/home/' + 'private-person' + '/lib'))
