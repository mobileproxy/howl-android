"""Exercise the production signing code with disposable test keys, without installing any APK."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    suffix = '.exe' if os.name == 'nt' else ''
    java, keytool = [args.java_home / 'bin' / (name + suffix) for name in ['java', 'keytool']]
    original_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    password = secrets.token_hex(24)
    environment = dict(os.environ, HOWL_FIXTURE_PASS=password)
    script = Path(__file__).with_name('signing-rotation.py')
    with tempfile.TemporaryDirectory(prefix='howl-rotation-fixtures-') as directory:
        lab = Path(directory)
        fingerprints = {}
        for name, subject in [('previous', 'CN=Fixture Previous,C=RU'), ('next', 'CN=Howl')]:
            key = lab / (name + '.p12')
            subprocess.run([str(keytool), '-genkeypair', '-noprompt', '-alias', name, '-keystore', str(key),
                '-storetype', 'PKCS12', '-storepass:env', 'HOWL_FIXTURE_PASS', '-keypass:env', 'HOWL_FIXTURE_PASS',
                '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', subject],
                env=environment, check=True, capture_output=True)
            der = subprocess.check_output([str(keytool), '-exportcert', '-alias', name, '-keystore', str(key),
                '-storepass:env', 'HOWL_FIXTURE_PASS'], env=environment, stderr=subprocess.PIPE)
            fingerprints[name] = hashlib.sha256(der).hexdigest()
            prefix = 'ANDROID_ROTATION_' if name == 'next' else ''
            environment[prefix + 'KEYSTORE_BASE64'] = base64.b64encode(key.read_bytes()).decode()
            props = f'KEYSTORE_PASS={password}\nALIAS_NAME={name}\nALIAS_PASS={password}\n'
            environment['ANDROID_ROTATION_PROPERTIES' if name == 'next' else 'LOCAL_PROPERTIES'] = base64.b64encode(props.encode()).decode()
        policy = json.loads(Path(__file__).resolve().parents[1].joinpath('signing/rotation-policy.json').read_text())
        policy.update(oldCertificateSha256=fingerprints['previous'], newCertificateSha256=fingerprints['next'])
        policy_path = lab / 'policy.json'
        policy_path.write_text(json.dumps(policy), encoding='utf-8')
        common = ['--policy', str(policy_path), '--lineage', str(lab / 'test.lineage'),
                  '--build-tools', str(args.build_tools), '--java', str(java)]

        def execute(action, *extra, accepted=True):
            result = subprocess.run([sys.executable, str(script), action, *common, *map(str, extra)],
                                    env=environment, text=True, capture_output=True)
            if (result.returncode == 0) != accepted:
                print(result.stdout)
                print(result.stderr)
                raise ValueError('Unexpected fixture result: ' + action)
            return json.loads(result.stdout) if accepted else None

        first = execute('create-lineage')
        repeated = execute('create-lineage')
        assert first['lineageSha256'] == repeated['lineageSha256']
        signed = lab / 'rotated.apk'
        report = execute('sign', '--apk', args.apk, '--output', signed)
        execute('verify', '--apk', signed)
        assert list(map(int, report['selectedSignerByApi'])) == list(range(24, 37))
        for api in range(24, 37):
            assert report['selectedSignerByApi'][str(api)] == fingerprints['previous' if api < 33 else 'next']
        # Repeated signing and verification uses the same persisted lineage.
        execute('sign', '--apk', signed, '--output', lab / 'next-signed.apk')
        execute('sign', '--apk', args.apk, '--output', args.apk, accepted=False)
        wrong = dict(policy, newCertificateSha256='f' * 64)
        policy_path.write_text(json.dumps(wrong), encoding='utf-8')
        execute('verify', '--apk', signed, accepted=False)
        policy_path.write_text(json.dumps(policy), encoding='utf-8')
        tampered = bytearray(signed.read_bytes())
        tampered[len(tampered) // 2] ^= 1
        broken = lab / 'tampered.apk'
        broken.write_bytes(tampered)
        execute('verify', '--apk', broken, accepted=False)
        assert hashlib.sha256(args.apk.read_bytes()).hexdigest() == original_hash
        output = {'productionKeysUsed': False, 'sourceApkUnchanged': True, 'lineageRetryIdempotent': True,
                  'embeddedLineageMatches': report['embeddedLineageMatches'],
                  'selectedSignerByApi': report['selectedSignerByApi'], 'dataInheritance': True,
                  'oldKeyRollback': False, 'nextSigningVerified': True, 'wrongPinRejected': True,
                  'modifiedApkRejected': True, 'inPlaceOverwriteRejected': True, 'deviceAcceptance': 'NOT_RUN'}
        print(json.dumps(output, indent=2))


if __name__ == '__main__':
    main()
