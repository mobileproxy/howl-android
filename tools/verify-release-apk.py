"""Check release install compatibility with a pinned, previously published APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

PREVIOUS_SHA256 = "19e5e54d4e268d89c819f6a7abeabc96f58e6b9dd2c15308b4bf5c0c4923b3de"
PREVIOUS_URL = "https://github.com/mobileproxy/howl-android/releases/download/ci-37228493605-1/Howl.apk"


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True)


def signer_for_api(signature, api=36):
    signature = '\n'.join(signature.splitlines())
    classic = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    targeted = re.findall(r"^Signer \(minSdkVersion=(\d+), maxSdkVersion=(\d+)\) certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    if classic and targeted:
        raise ValueError("Mixed signer output formats")
    if any(int(low) > int(high) for low, high, _ in targeted):
        raise ValueError("Invalid signer SDK range")
    selected = [cert for low, high, cert in targeted if int(low) <= api <= int(high)] if targeted else classic
    if len(selected) != 1:
        raise ValueError("Expected exactly one verified signer for the checked SDK")
    return selected[0].lower()


def inspect(apk, build_tools):
    signature = run(build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    cert = signer_for_api(signature)
    badging = run(build_tools / "aapt", "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if package is None:
        raise ValueError("Missing package metadata")
    return {
        "package": package[1], "versionCode": int(package[2]), "versionName": package[3],
        "signerSha256": cert, "signerForApi": 36, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "size": apk.stat().st_size, "debuggable": "application-debuggable" in badging,
    }


def main():
    folder = Path(sys.argv[1])
    matches = list(folder.rglob("*universal*.apk"))
    if len(matches) != 1:
        raise ValueError("Expected exactly one universal ARM APK")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools/36.0.0"
    baseline = folder / "previous-published.apk"
    props = dict(line.split("=", 1) for line in
                 (Path(__file__).resolve().parents[1] / "version.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    try:
        with urllib.request.urlopen(PREVIOUS_URL, timeout=90) as response:
            with baseline.open("wb") as target:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > 80 * 1024 * 1024:
                        raise ValueError("Previous APK exceeds expected bound")
                    target.write(chunk)
        if hashlib.sha256(baseline.read_bytes()).hexdigest() != PREVIOUS_SHA256:
            raise ValueError("Published baseline changed; review before releasing")
        previous = inspect(baseline, build_tools)
        run(sys.executable, Path(__file__).with_name("verify-arm-libraries.py"), matches[0])
        current = inspect(matches[0], build_tools)
        if previous["package"] != "how.vpn.howl" or current["package"] != previous["package"]:
            raise ValueError("Package identity changed")
        mode = os.environ.get("HOWL_SIGNING_MODE", "legacy")
        rotation = None
        if mode == "legacy":
            if current["signerSha256"] != previous["signerSha256"]:
                raise ValueError("Release certificate differs from installed release")
        elif mode == "rotated":
            rotation = json.loads(run(sys.executable, Path(__file__).with_name("signing-rotation.py"),
                "verify", "--apk", matches[0], "--build-tools", build_tools))
            if (rotation["oldCertificateSha256"] != previous["signerSha256"]
                    or rotation["newCertificateSha256"] != current["signerSha256"]):
                raise ValueError("Rotation does not extend the pinned installed release")
        else:
            raise ValueError("Unknown signing mode")
        if current["versionCode"] <= previous["versionCode"]:
            raise ValueError("versionCode must increase")
        expected_name = f"{props['VERSION_NAME']} ({current['versionCode']})"
        if current["versionName"] != expected_name or current["debuggable"]:
            raise ValueError("Unexpected version or debuggable APK")
        with urllib.request.urlopen("https://gethowl.app/downloads/howl/latest.json", timeout=30) as response:
            published = json.loads(response.read(65537))
        if current["versionCode"] <= int(published["versionCode"]):
            raise ValueError("versionCode must exceed the current public update")
        report = {"previous": previous, "release": current, "compatibleSigner": True,
                  "publicVersionCode": int(published["versionCode"]), "deviceAcceptance": "NOT_RUN",
                  "signingMode": mode, "rotation": rotation}
        (folder / "release-verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
    finally:
        baseline.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
