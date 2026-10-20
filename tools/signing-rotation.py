"""Prepare/verify compatible Android key rotation using the official Android SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CAPABILITIES = {'installedData': True, 'permission': True, 'sharedUid': False, 'auth': False, 'rollback': False}


def validate_policy(policy):
    required = {'applicationId': 'how.vpn.howl', 'minSdk': 24, 'rotationApi': 33, 'newSubject': 'CN=Howl', 'oldCapabilities': CAPABILITIES}
    if any(policy.get(key) != value for key, value in required.items()):
        raise ValueError('Rotation must preserve application identity, API24 compatibility and installed data; rollback is disabled')
    pins = [policy.get(key, '') for key in ('oldCertificateSha256', 'newCertificateSha256')]
    if any(not isinstance(pin, str) or not re.fullmatch('[0-9a-f]{64}', pin) for pin in pins) or pins[0] == pins[1]:
        raise ValueError('Two distinct pinned certificate SHA256 values are required')


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entry')
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create-lineage', 'sign', 'verify'])
    parser.add_argument('--policy', type=Path, default=ROOT / 'signing/rotation-policy.json')
    parser.add_argument('--lineage', type=Path, default=ROOT / 'signing/howl-2026.lineage')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    policy = json.loads(args.policy.read_text(encoding='utf-8'))
    validate_policy(policy)
    command = [args.java, '-cp', str(args.build_tools / 'lib/apksigner.jar'), str(Path(__file__).with_name('AndroidSigning.java')),
               args.action, policy['oldCertificateSha256'], policy['newCertificateSha256'], str(args.lineage)]
    if args.action == 'create-lineage':
        args.lineage.parent.mkdir(parents=True, exist_ok=True)
    else:
        if args.apk is None:
            parser.error('--apk is required')
        command.append(str(args.apk))
        original_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        if args.action == 'sign':
            if args.output is None or args.output.exists() or args.output.resolve() == args.apk.resolve():
                parser.error('--output must be a separate, new APK path')
            command.append(str(args.output))
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode:
        safe = [line for line in result.stderr.splitlines() if re.fullmatch(
            r'Official Android signing operation rejected: [A-Za-z]+|Signing check: [A-Za-z]+:[0-9]+', line)]
        raise ValueError('; '.join(safe) or 'Official Android signing operation rejected the key, lineage or APK')
    report = json.loads(result.stdout)
    report.update({'lineageSha256': hashlib.sha256(args.lineage.read_bytes()).hexdigest(),
                   'oldCertificateSha256': policy['oldCertificateSha256'], 'newCertificateSha256': policy['newCertificateSha256']})
    if args.action != 'create-lineage':
        target = args.output if args.action == 'sign' else args.apk
        if hashlib.sha256(args.apk.read_bytes()).hexdigest() != original_hash:
            raise ValueError('Source APK was modified')
        if args.action == 'sign' and payload(args.apk) != payload(target):
            raise ValueError('Signing changed packaged application data')
        report.update({'apkSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                       'sourceApkUnchanged': True, 'rotationApi': 33, 'deviceAcceptance': 'NOT_RUN'})
        target.with_name('rotation-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
