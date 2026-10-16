import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("version_code", Path(__file__).with_name("next-version-code.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VersionCodeTests(unittest.TestCase):
    def test_separate_release_collision(self):
        self.assertEqual(module.next_code(700, 119, 819), 820)

    def test_normal_numbering_ahead(self):
        self.assertEqual(module.next_code(700, 125, 819), 825)

    def test_invalid_manifest(self):
        for value in ("819", None, True, -1):
            with self.assertRaises(ValueError):
                module.next_code(700, 119, value)

    def test_android_limit(self):
        with self.assertRaises(ValueError):
            module.next_code(700, 119, 2100000000)
