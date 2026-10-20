"""Ephemeral SDK signing experiment. Never uses the Howl release key or modifies the input APK."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


def run(*args, env=None):
    return subprocess.check_output([str(a) for a in args], env=env, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    args = parser.parse_args()
    source_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    signer = args.build_tools / 'apksigner'
    signer_jar = args.build_tools / 'lib/apksigner.jar'
    env = dict(os.environ, HOWL_ROTATION_LAB_PASS=secrets.token_hex(24))
    with tempfile.TemporaryDirectory(prefix='howl-signing-rotation-lab-') as directory:
        lab = Path(directory)
        fingerprints = {}
        for name, dn in [('previous', 'CN=Howl Rotation Lab Previous,C=RU'),
                         ('next', 'CN=Howl Rotation Lab Next')]:
            key = lab / (name + '.p12')
            run('keytool', '-genkeypair', '-noprompt', '-alias', name, '-keystore', key,
                '-storetype', 'PKCS12', '-storepass:env', 'HOWL_ROTATION_LAB_PASS',
                '-keypass:env', 'HOWL_ROTATION_LAB_PASS', '-keyalg', 'RSA', '-keysize', '2048',
                '-validity', '3650', '-dname', dn, env=env)
            pem = run('keytool', '-exportcert', '-rfc', '-alias', name, '-keystore', key,
                      '-storepass:env', 'HOWL_ROTATION_LAB_PASS', env=env).decode()
            encoded = pem.split('-----BEGIN CERTIFICATE-----')[1].split('-----END CERTIFICATE-----')[0]
            fingerprints[name] = hashlib.sha256(base64.b64decode(encoded)).hexdigest()

        def key_args(name):
            return ['--ks', lab / (name + '.p12'), '--ks-key-alias', name,
                    '--ks-pass', 'env:HOWL_ROTATION_LAB_PASS', '--key-pass', 'env:HOWL_ROTATION_LAB_PASS']

        lineage = lab / 'test.lineage'
        run(signer, 'rotate', '--out', lineage,
            '--old-signer', *key_args('previous'), '--new-signer', *key_args('next'), env=env)
        original = lab / 'test-previous.apk'
        rotated = lab / 'test-rotated.apk'
        run(signer, 'sign', *key_args('previous'), '--min-sdk-version', '24',
            '--v1-signing-enabled', 'false', '--v4-signing-enabled', 'false',
            '--out', original, args.apk, env=env)
        run(signer, 'sign', *key_args('previous'), '--next-signer', *key_args('next'),
            '--lineage', lineage, '--rotation-min-sdk-version', '33', '--min-sdk-version', '24',
            '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
            '--out', rotated, args.apk, env=env)
        java_source = Path(__file__).with_name('SigningRotationLab.java')
        run('javac', '-cp', signer_jar, '-d', lab, java_source)

        def checked_signers(apk):
            output = run('java', '-cp', str(lab) + os.pathsep + str(signer_jar),
                         'SigningRotationLab', apk, '24', '36').decode()
            return {int(line.split()[0]): line.split()[1] for line in output.splitlines()}

        previous = checked_signers(original)
        selected = checked_signers(rotated)
        for sdk in range(24, 37):
            if previous.get(sdk) != fingerprints['previous']:
                raise ValueError('Unexpected baseline test signer')
            expected = fingerprints['previous' if sdk < 33 else 'next']
            if selected.get(sdk) != expected:
                raise ValueError(f'Unexpected rotated test signer on SDK {sdk}')
        if hashlib.sha256(args.apk.read_bytes()).hexdigest() != source_hash:
            raise ValueError('The real candidate APK was modified by the lab')
        report = {'usesEphemeralTestKeysOnly': True, 'productionKeyAccessed': False,
                  'realCandidateUnchanged': True, 'realCandidateSha256': source_hash,
                  'rotationThresholdApi': 33, 'verifiedApis': list(range(24, 37)),
                  'testSigners': fingerprints, 'selectedTestSignerByApi': selected,
                  'deviceInstallationOrMigrationTested': False}
        args.apk.with_name('rotation-lab-verification.json').write_text(
            json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
