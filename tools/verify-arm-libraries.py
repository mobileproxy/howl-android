"""Fail closed when an AAR/APK cannot serve both Android ARM ABIs."""
import argparse
import json
import struct
import zipfile
from pathlib import Path

ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183)}


def verify(path):
    prefix = "jni" if path.suffix == ".aar" else "lib"
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt archive")
        found = {n.split("/")[1] for n in archive.namelist()
                 if n.startswith(prefix + "/") and n.endswith(".so")}
        if path.suffix == ".apk" and found != set(ABIS):
            raise ValueError(f"Unexpected APK architectures: {sorted(found)}")
        libraries = {}
        for abi, (elf_class, machine) in ABIS.items():
            names = [n for n in archive.namelist()
                     if n.startswith(f"{prefix}/{abi}/") and n.endswith(".so")]
            if len(names) != len(set(names)):
                raise ValueError("Duplicate native library")
            libraries[abi] = sorted(Path(n).name for n in names)
            if "libbox.so" not in libraries[abi]:
                raise ValueError(f"Missing libbox for {abi}")
            for name in names:
                header = archive.read(name)[:20]
                if (len(header) < 20 or header[:4] != b"\x7fELF"
                        or header[4] != elf_class or header[5] != 1
                        or struct.unpack_from("<H", header, 18)[0] != machine):
                    raise ValueError(f"Wrong ELF architecture: {name}")
        if libraries["armeabi-v7a"] != libraries["arm64-v8a"]:
            raise ValueError("Different native library sets across ARM ABIs")
    return libraries


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive), indent=2))
