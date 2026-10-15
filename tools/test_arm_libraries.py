import importlib.util
from pathlib import Path
import struct
import uuid
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify-arm-libraries.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class NativeCompatibility(unittest.TestCase):
    def check(self, suffix, missing=False, wrong=False, mismatch=False):
        path = Path(__file__).parent / ("test-" + uuid.uuid4().hex + suffix)
        try:
            with zipfile.ZipFile(path, "w") as z:
                prefix = "jni" if suffix == ".aar" else "lib"
                for abi, (cls, machine) in mod.ABIS.items():
                    if missing and cls == 1:
                        continue
                    data = bytearray(20)
                    data[:6] = b"\x7fELF" + bytes([2 if wrong else cls, 1])
                    struct.pack_into("<H", data, 18, machine)
                    z.writestr(f"{prefix}/{abi}/libbox.so", data)
                    if mismatch and cls == 2:
                        z.writestr(f"{prefix}/{abi}/libextra.so", data)
            return mod.verify(path)
        finally:
            path.unlink(missing_ok=True)

    def test_dual_aar(self): self.check(".aar")
    def test_dual_apk(self): self.check(".apk")
    def test_arm64_only_rejected(self):
        with self.assertRaises(ValueError): self.check(".apk", missing=True)
    def test_mislabeled_elf_rejected(self):
        with self.assertRaises(ValueError): self.check(".aar", wrong=True)
    def test_missing_dependency_rejected(self):
        with self.assertRaises(ValueError): self.check(".apk", mismatch=True)
