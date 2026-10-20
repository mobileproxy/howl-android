import importlib.util
import io
import struct
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ApkBrandingTests(unittest.TestCase):
    def guard(self):
        path = Path(__file__).with_name('verify-apk-branding.py')
        self.assertTrue(path.exists(), 'Packaged APK branding/path guard is missing')
        spec = importlib.util.spec_from_file_location('apk_branding', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_all_existing_onboarding_translations_use_howl(self):
        found = []
        for path in sorted((ROOT / 'app/src/main/res').glob('values*/strings.xml')):
            strings = {x.get('name'): ''.join(x.itertext()) for x in ET.parse(path).getroot() if x.tag == 'string'}
            if 'howl_onboarding_body' not in strings:
                continue
            found.append(path.parent.name)
            with self.subTest(locale=path.parent.name):
                self.assertIn('Howl', strings['howl_onboarding_body'])
                self.assertNotIn('vpn.how', strings['howl_onboarding_body'])
        self.assertEqual(len(found), 9)
        self.assertIn('values-ru-rRU', found)

    def test_rejects_personal_paths_in_native_or_dex_data(self):
        guard = self.guard()
        for value in [r'C:\Users\build-agent\project\App.kt', r'D:/Users/build-agent/project/Main.java',
                      r'C:\Documents and Settings\build-agent\project',
                      '/home/build-agent/project/main.go', '/Users/build-agent/project/App.kt']:
            for encoding in ['utf-8', 'utf-16-le']:
                for prefix in [b'\0', b'\x01\0']:
                    with self.subTest(value=value, encoding=encoding, prefix=prefix):
                        with self.assertRaises(ValueError):
                            guard.check_paths(prefix + value.encode(encoding) + b'\0\0', 'fictional.dex')

    def test_preserves_real_url_and_existing_certificate_metadata(self):
        guard = self.guard()
        for encoding in ['utf-8', 'utf-16-le']:
            guard.check_paths(('https://www.quad9.net/home/privacy/\0'
                               'C=RU,O=vpn.how,CN=Howl VPN\0'
                               'Russian Standard Time\0github.com/example/module/path.go').encode(encoding), 'core.so')

    def test_source_names_and_mapped_paths_are_allowed(self):
        self.guard().check_paths(b'\0SourceFile\0/_/app/App.kt\0github.com/example/module/file.go\0', 'classes.dex')

    def test_arbitrary_url_cannot_hide_a_personal_path(self):
        guard = self.guard()
        for text in ['https://example.invalid/home/build-agent/project/App.kt',
                     'https://www.quad9.net/home/privacy/home/build-agent/App.kt']:
            with self.assertRaises(ValueError):
                guard.check_paths(text.encode() + b'\0', 'core.so')

    def test_http_api_user_routes_are_not_macos_home_directories(self):
        try:
            self.guard().check_paths(b'\0/users/{username}\0/users/stats\0', 'core.so')
        except ValueError:
            self.fail('Lowercase HTTP API routes are not the macOS /Users build directory')

    def test_checks_installed_application_label(self):
        guard = self.guard()
        guard.check_badging("package: name='how.vpn.howl' versionCode='823'\napplication-label:'Howl'\n")
        for text in ["application-label:'Old name'\n", "application-label:'Howl VPN'\n", '']:
            with self.assertRaises(ValueError):
                guard.check_badging(text)

    def test_rejects_escaped_or_malformed_localized_labels(self):
        guard = self.guard()
        base = "package: name='how.vpn.howl' versionCode='823'\napplication-label:'Howl'\n"
        for extra in ["application-label-fr:'Someone\\'s VPN'\n", "application-label-de:'Broken\n"]:
            with self.assertRaises(ValueError):
                guard.check_badging(base + extra)

    def archive(self, payload=b'\0SourceFile\0', resources=b'Howl', duplicate=False):
        with io.BytesIO() as buffer:
            with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'fictional manifest')
                archive.writestr('classes.dex', payload)
                archive.writestr('resources.arsc', resources)
                if duplicate:
                    archive.writestr('classes.dex', b'duplicate')
            buffer.seek(0)
            return self.guard().scan_archive(buffer)

    def test_scans_compressed_apk_entries(self):
        self.archive()
        with self.assertRaises(ValueError):
            self.archive(payload=b'\0/home/build-agent/project/App.kt\0')

    def test_rejects_stale_packaged_branding_in_both_encodings(self):
        guard = self.guard()
        for encoding in ['utf-8', 'utf-16-le']:
            with self.assertRaises(ValueError):
                self.archive(resources='Howl account at vpn.how'.encode(encoding))
        # Legacy links in executable code remain compatible; visible resource copy changes.
        self.archive(payload=b'https://vpn.how/legacy-subscription')

    def test_resource_package_identity_is_not_visible_branding(self):
        # ResTable_package.name is a fixed UTF16 field and must retain the app ID.
        def resource_table(copy, package='how.vpn.howl'):
            package_header = bytearray(288)
            struct.pack_into('<HHII', package_header, 0, 0x0200, 288, 288 + len(copy), 0x7f)
            name = package.encode('utf-16-le')
            package_header[12:12+len(name)] = name
            return struct.pack('<HHII', 0x0002, 12, 12 + len(package_header) + len(copy), 1) + package_header + copy

        self.archive(resources=resource_table(b'Howl'))
        for encoding in ['utf-8', 'utf-16-le']:
            with self.assertRaises(ValueError):
                self.archive(resources=resource_table('account at vpn.how'.encode(encoding)))
        with self.assertRaises(ValueError):
            self.archive(resources=resource_table(b'Howl', package='other.vpn.how'))
        # The same bytes in a visible string are not the package header exemption.
        with self.assertRaises(ValueError):
            self.archive(resources='how.vpn.howl'.encode('utf-16-le'))

    def test_rejects_duplicate_archive_entries(self):
        self.guard()
        with self.assertWarns(UserWarning):
            with self.assertRaises(ValueError):
                self.archive(duplicate=True)


if __name__ == '__main__':
    unittest.main()
