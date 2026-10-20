import importlib.util
from pathlib import Path
import subprocess
import unittest


class UpgradeEvidenceTests(unittest.TestCase):
    def checker(self):
        path = Path(__file__).with_name('check-rotation-upgrade.py')
        spec = importlib.util.spec_from_file_location('upgrade_checker', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_wal_presence_requires_an_explicit_remote_answer(self):
        module = self.checker()
        for answer, expected in [(b'PRESENT\r\n', True), (b'ABSENT\n', False)]:
            self.assertEqual(module.remote_file_exists(lambda *args: answer, '/data/test.db-wal'), expected)

    def test_adb_transport_failure_never_counts_as_missing_wal(self):
        def failed(*args):
            raise subprocess.CalledProcessError(1, ['adb'], b'device offline')
        with self.assertRaises(subprocess.CalledProcessError):
            self.checker().remote_file_exists(failed, '/data/test.db-wal')

    def test_empty_or_unexpected_answer_fails_acceptance(self):
        module = self.checker()
        for answer in [b'', b'device offline', b'PRESENT\nABSENT\n']:
            with self.assertRaises(ValueError):
                module.remote_file_exists(lambda *args: answer, '/data/test.db-wal')


if __name__ == '__main__':
    unittest.main()
