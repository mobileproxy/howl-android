import importlib.util
from pathlib import Path
import unittest


class ReleaseSignatureOutputTests(unittest.TestCase):
    def verifier(self):
        path = Path(__file__).with_name('verify-release-apk.py')
        spec = importlib.util.spec_from_file_location('release_verifier', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(hasattr(module, 'signer_for_api'), 'SDK-targeted signature output is not supported')
        return module

    def test_legacy_single_signer(self):
        self.assertEqual(self.verifier().signer_for_api('Signer #1 certificate SHA-256 digest: ' + 'a' * 64), 'a' * 64)

    def test_v31_selects_the_sdk_target_instead_of_requiring_one_printed_certificate(self):
        text = ('Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ' + 'b' * 64
                + '\nSigner (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ' + 'a' * 64)
        module = self.verifier()
        for rendered in [text, text.replace('\n', '\r\n') + '\r\n']:
            for api in range(24, 37):
                self.assertEqual(module.signer_for_api(rendered, api), ('a' if api < 33 else 'b') * 64)

    def test_rejects_ambiguous_or_missing_signers(self):
        module = self.verifier()
        one = 'Signer (minSdkVersion=24, maxSdkVersion=2147483647) certificate SHA-256 digest: ' + 'a' * 64
        for text in ['', one + '\n' + one, one + '\nSigner #1 certificate SHA-256 digest: ' + 'a' * 64]:
            with self.assertRaises(ValueError):
                module.signer_for_api(text)


if __name__ == '__main__':
    unittest.main()
