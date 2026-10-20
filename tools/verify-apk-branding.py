"""Inspect real APK entries without running the app or changing its signature."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

WINDOWS_HOME = re.compile(r'[A-Za-z]:[\\/](?:Users|Documents and Settings)[\\/][^\x00\s\\/]+', re.I)
POSIX_HOME = re.compile(r'/(?:home|Users)/[^\x00\s/]+')
# Reviewed in the pinned core: this complete NUL-terminated URL is not a home path.
# Do not exempt arbitrary URL-shaped binary fragments: Go strings may be adjacent.
REVIEWED_URLS = ('https://www.quad9.net/home/privacy/',)
MAX_ENTRY = 256 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024


def check_paths(data, entry):
    # Android resources can use UTF16 at either byte alignment. DEX and Go
    # metadata also contain ordinary byte strings, independent of string length prefixes.
    texts = (data.decode('latin-1'),
             data.decode('utf-16-le', errors='ignore'),
             data[1:].decode('utf-16-le', errors='ignore'))
    for text in texts:
        if WINDOWS_HOME.search(text):
            raise ValueError(f'Personal Windows build path in {entry}')
        for match in POSIX_HOME.finditer(text):
            reviewed = False
            for url in REVIEWED_URLS:
                start = match.start() - url.index('/home/')
                end = start + len(url)
                if start >= 0 and text.startswith(url, start) and (end == len(text) or text[end] == '\x00'):
                    reviewed = True
                    break
            if not reviewed:
                raise ValueError(f'Personal POSIX build path in {entry}')


def check_badging(text):
    if not re.search(r"^package: name='how\.vpn\.howl' ", text, re.M):
        raise ValueError('Application ID must remain how.vpn.howl')
    labels = [line for line in text.splitlines() if line.startswith('application-label')]
    if not labels or any(not re.fullmatch(r"application-label(?:-[^:]+)?:'Howl'", line) for line in labels):
        raise ValueError('Every application label must be Howl')


def scan_archive(path):
    with zipfile.ZipFile(path) as archive:
        files = [i for i in archive.infolist() if not i.is_dir()]
        names = [i.filename for i in files]
        if len(names) != len(set(names)) or len(files) > 20000:
            raise ValueError('Duplicate or excessive APK entries')
        if not {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'} <= set(names):
            raise ValueError('Missing required APK content')
        total = sum(i.file_size for i in files)
        if total > MAX_TOTAL or any(i.file_size > MAX_ENTRY for i in files):
            raise ValueError('APK decompression limit exceeded')
        for info in files:
            name = info.filename
            if name.startswith(('/', '\\')) or '..' in name.replace('\\', '/').split('/'):
                raise ValueError('Unsafe APK entry name')
            check_paths(name.encode('utf-8'), 'archive entry name')
            data = archive.read(info)  # ZipFile checks each entry's CRC while reading.
            check_paths(data, name)
            if name == 'resources.arsc' and any(value in data.lower() for value in
                    (b'vpn.how', 'vpn.how'.encode('utf-16-le'))):
                raise ValueError('Old visible branding in compiled resources')
    return {'entriesChecked': len(files), 'uncompressedBytes': total,
            'personalBuildPaths': 0, 'oldBrandInResources': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt', type=Path, required=True)
    args = parser.parse_args()
    report = scan_archive(args.apk)
    badging = subprocess.check_output([str(args.aapt), 'dump', 'badging', str(args.apk)], text=True)
    check_badging(badging)
    report.update({'applicationId': 'how.vpn.howl', 'label': 'Howl',
                   'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()})
    args.apk.with_name('branding-verification.json').write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
