import importlib.util
import hashlib
import json
import unittest
from pathlib import Path


class SigningRotationTests(unittest.TestCase):
    def module(self):
        path = Path(__file__).with_name('signing-rotation.py')
        self.assertTrue(path.exists(), 'Production signing rotation tool is missing')
        spec = importlib.util.spec_from_file_location('rotation', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def policy(self):
        return {'applicationId': 'how.vpn.howl', 'minSdk': 24, 'rotationApi': 33,
                'oldCertificateSha256': 'a' * 64, 'newCertificateSha256': 'b' * 64,
                'newSubject': 'CN=Howl', 'oldCapabilities': {'installedData': True,
                'permission': True, 'sharedUid': False, 'auth': False, 'rollback': False}}

    def test_accepts_fixed_compatibility_contract(self):
        self.module().validate_policy(self.policy())

    def test_rejects_unsafe_rotation_or_identity_changes(self):
        module = self.module()
        for key, value in [('applicationId', 'another.app'), ('minSdk', 28), ('rotationApi', 28),
                           ('newSubject', 'CN=Howl,C=US'), ('newCertificateSha256', 'a' * 64),
                           ('oldCertificateSha256', 'not-a-sha256')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.validate_policy(dict(self.policy(), **{key: value}))

    def test_requires_data_inheritance_and_rejects_old_key_rollback(self):
        module = self.module()
        for key, value in [('installedData', False), ('rollback', True), ('sharedUid', True)]:
            policy = self.policy()
            policy['oldCapabilities'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.validate_policy(policy)

    def test_rejects_missing_capability_instead_of_using_defaults(self):
        policy = self.policy()
        del policy['oldCapabilities']['installedData']
        with self.assertRaises(ValueError):
            self.module().validate_policy(policy)

    def test_committed_public_certificate_matches_the_pinned_new_identity(self):
        root = Path(__file__).resolve().parents[1]
        policy = json.loads((root / 'signing/rotation-policy.json').read_text())
        self.module().validate_policy(policy)
        self.assertEqual(hashlib.sha256((root / 'signing/howl-2026.cer').read_bytes()).hexdigest(),
                         policy['newCertificateSha256'])


if __name__ == '__main__':
    unittest.main()
