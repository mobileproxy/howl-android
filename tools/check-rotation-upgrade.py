"""Upgrade acceptance on a dedicated disposable Android emulator, with synthetic profiles only."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import shlex
import sqlite3
import struct
import subprocess
import tempfile
import time

PACKAGE = 'how.vpn.howl'
DATA = '/data/user/0/' + PACKAGE
ROOT = Path(__file__).resolve().parents[1]


def remote_file_exists(adb, path):
    # ADB failure must not look like an absent WAL and hide uncheckpointed changes.
    answer = adb('shell', 'if [ -f ' + shlex.quote(path)
                 + ' ]; then echo PRESENT; else echo ABSENT; fi').strip()
    if answer not in [b'PRESENT', b'ABSENT']:
        raise ValueError('No trustworthy remote file-presence result')
    return answer == b'PRESENT'


def parcel_string(value):
    raw = struct.pack('<i', len(value)) + value.encode('utf-16-le') + b'\0\0'
    return raw + bytes((-len(raw)) % 4)


def fixtures(directory):
    config = json.dumps({'outbounds': [{'type': 'direct', 'tag': 'direct'}]}, separators=(',', ':'))
    expected = {}
    for folder in ['databases', 'files/configs']:
        (directory / folder).mkdir(parents=True, exist_ok=True)
    for schema_name, file in [('ProfileDatabase/3.json', 'profiles.db'),
                              ('preference.KeyValueDatabase/1.json', 'settings.db')]:
        schema = json.loads((ROOT / ('app/schemas/io.nekohasekai.sfa.database.' + schema_name)).read_text())['database']
        with closing(sqlite3.connect(directory / 'databases' / file)) as database, database:
            for entity in schema['entities']:
                database.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for sql in schema['setupQueries']:
                database.execute(sql)
            database.execute('PRAGMA user_version=' + str(schema['version']))
    with closing(sqlite3.connect(directory / 'databases/profiles.db')) as database, database:
        for profile_id, kind in [(101, 0), (102, 1)]:
            name = 'Rotation fixture ' + str(profile_id)
            filename = f'files/configs/rotation-{profile_id}.json'
            path = DATA + '/' + filename
            url = '' if kind == 0 else 'https://example.invalid/rotation-subscription'
            blob = (struct.pack('<i', 1) + parcel_string(path) + struct.pack('<i', kind)
                    + parcel_string(url) + struct.pack('<iqi', 0, 1234567890000, 60))
            database.execute('INSERT INTO profiles(id,userOrder,name,typed) VALUES(?,?,?,?)',
                             (profile_id, profile_id, name, blob))
            source = 'local' if kind == 0 else url
            state = {'version': 1, 'source': hashlib.sha256(source.encode()).hexdigest(),
                     'received': config, 'applied': config, 'receivedHash': hashlib.sha256(config.encode()).hexdigest(),
                     'appliedHash': hashlib.sha256(config.encode()).hexdigest(), 'receivedAt': 1234567890000,
                     'appliedAt': 1234567890000, 'blocked': False, 'backupDisabled': False}
            for suffix, content in [('', config), ('.safe', json.dumps(state, separators=(',', ':')))]:
                encoded = content.encode()
                (directory / (filename + suffix)).write_bytes(encoded)
                expected[filename + suffix] = hashlib.sha256(encoded).hexdigest()
    with closing(sqlite3.connect(directory / 'databases/settings.db')) as database, database:
        database.execute('INSERT INTO KeyValueEntity(key,valueType,value) VALUES(?,?,?)',
                         ('selected_profile', 3, struct.pack('>q', 101)))
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--next', type=Path, required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--package-data-only', action='store_true',
                        help='Check PackageInstaller/data retention only; explicitly does not verify app startup or Room reads')
    args = parser.parse_args()
    command = [args.adb, '-s', args.serial]

    def adb(*arguments):
        return subprocess.check_output([*command, *map(str, arguments)], stderr=subprocess.STDOUT)

    # Refuse physical devices and existing, non-task emulators before touching app data.
    if not args.serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != b'1':
        raise ValueError('A disposable emulator is required')
    if not adb('emu', 'avd', 'name').decode().splitlines()[0].startswith('howl-rotation-'):
        raise ValueError('Refusing to modify an emulator not created for rotation acceptance')
    api = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
    bridge = adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge').decode().strip()
    print(json.dumps({'phase': 'emulator-preflight', 'api': api, 'abis': abis, 'nativeBridge': bridge}))
    if not {'arm64-v8a', 'armeabi-v7a'}.intersection(abis):
        raise ValueError('Acceptance image cannot run the ARM APK; this is an emulator prerequisite, not a signing result')
    if adb('shell', 'pm', 'list', 'packages', PACKAGE).strip():
        raise ValueError('Emulator must be fresh; existing Howl data must never be replaced')
    adb('root')
    adb('wait-for-device')
    if adb('shell', 'id', '-u').strip() != b'0':
        raise ValueError('Acceptance image must allow adb root for synthetic data inspection')
    adb('install', args.baseline)
    uid = re.search(rb'userId=(\d+)', adb('shell', 'dumpsys', 'package', PACKAGE))[1]

    def launch():
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/io.nekohasekai.sfa.compose.MainActivity')
        time.sleep(4)
        if not adb('shell', 'pidof', PACKAGE).strip():
            raise ValueError('App did not remain running on the acceptance image')
        adb('shell', 'am', 'force-stop', PACKAGE)

    if not args.package_data_only:
        launch()
    with tempfile.TemporaryDirectory(prefix='howl-rotation-data-') as temporary:
        directory = Path(temporary)
        expected = fixtures(directory)
        # Only synthetic files, in a fresh emulator, after all destructive-target guards.
        for name in ['profiles.db', 'settings.db']:
            adb('shell', 'rm', '-f', DATA + '/databases/' + name + '-wal', DATA + '/databases/' + name + '-shm')
        adb('push', str(directory) + '/.', DATA + '/')
        for part in ['databases', 'files/configs']:
            adb('shell', 'chown', '-R', uid.decode() + ':' + uid.decode(), DATA + '/' + part)
        adb('shell', 'restorecon', '-R', DATA)
        original = None
        snapshots = []

        def snapshot():
            destination = directory / ('snapshot-' + str(len(snapshots)))
            destination.mkdir()
            snapshots.append(destination)
            for name, digest in expected.items():
                if hashlib.sha256(adb('exec-out', 'cat', DATA + '/' + name)).hexdigest() != digest:
                    raise ValueError('Profile configuration or working reserve changed')
            values = []
            for name, query in [('profiles.db', 'SELECT id,userOrder,name,typed FROM profiles ORDER BY id'),
                                ('settings.db', "SELECT key,valueType,value FROM KeyValueEntity WHERE key='selected_profile'")]:
                local = destination / name
                local.write_bytes(adb('exec-out', 'cat', DATA + '/databases/' + name))
                wal = DATA + '/databases/' + name + '-wal'
                exists = remote_file_exists(adb, wal)
                wal_data = adb('exec-out', 'cat', wal) if exists else b''
                if wal_data:
                    local.with_name(local.name + '-wal').write_bytes(wal_data)
                with closing(sqlite3.connect(local)) as database:
                    values.append(database.execute(query).fetchall())
            if len(values[0]) != 2 or values[1] != [('selected_profile', 3, struct.pack('>q', 101))]:
                raise ValueError('Profile identity or selected profile was not preserved')
            if re.search(rb'userId=(\d+)', adb('shell', 'dumpsys', 'package', PACKAGE))[1] != uid:
                raise ValueError('Application UID changed')
            return values

        if not args.package_data_only:
            launch()
        original = snapshot()
        versions = []
        for apk in [args.candidate, args.next]:
            adb('install', '-r', apk)  # PackageInstaller upgrade, never uninstall/clear/downgrade.
            if not args.package_data_only:
                launch()
            if snapshot() != original:
                raise ValueError('Stored profile/subscription data changed across upgrade')
            version = re.search(rb'versionCode=(\d+)', adb('shell', 'dumpsys', 'package', PACKAGE))[1]
            versions.append(int(version))
        if versions[1] <= versions[0]:
            raise ValueError('The next acceptance APK must have a higher versionCode')
        print(json.dumps({'api': api, 'packageInstallerUpgrade': True, 'oldToCandidateToNext': versions,
                          'profilesPreserved': 2, 'selectedProfilePreserved': True, 'subscriptionPreserved': True,
                          'workingReservesPreserved': 2, 'uidPreserved': True,
                          'acceptanceScope': 'package-installer-data-retention' if args.package_data_only else 'app-upgrade',
                          'appLaunchesVerified': not args.package_data_only, 'appRoomReadVerified': not args.package_data_only,
                          'physicalDeviceTested': False, 'realVpnTested': False}, indent=2))


if __name__ == '__main__':
    main()
