import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "kotlin_payload", Path(__file__).resolve().parents[1] / "kotlin_payload.py"
)
payload = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(payload)


class KotlinPayloadTest(unittest.TestCase):
    def test_replaces_internal_closure_and_preserves_export_and_call_sites(self):
        source = (
            "before\nfunction toNat64Address (prefix, ipv4) {\n"
            "  return 'old'\n}\n"
            "after(toNat64Address(prefix, ip))\n"
            "module.exports.toNat64Address = toNat64Address\n"
        )
        patched = payload.patch_nat64(source)
        self.assertNotIn("return 'old'", patched)
        self.assertIn("require(process.env.DSHA_KOTLIN_HELPERS).toNat64Address(prefix, ipv4)", patched)
        self.assertIn("after(toNat64Address(prefix, ip))", patched)
        self.assertIn("module.exports.toNat64Address = toNat64Address", patched)
        self.assertEqual(patched, payload.patch_nat64(patched))
        self.assertEqual(patched, payload.patch_nat64(source.replace("\n", "\r\n")))

    def test_unsupported_upstream_fails_instead_of_retaining_js_implementation(self):
        with self.assertRaises(ValueError):
            payload.patch_nat64("function changedFunction () {}")

    def test_existing_payload_refresh_keeps_other_assets_and_avoids_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "project.zip"
            library = Path(tmp) / "library"
            library.mkdir()
            (library / payload.MODULE).write_text("compiled", encoding="ascii")
            (library / "kotlin-kotlin-stdlib.js").write_text("runtime", encoding="ascii")
            with zipfile.ZipFile(archive, "w") as result:
                result.writestr("main.js", "old entrypoint")
                result.writestr("core/keep.txt", "untouched")
                result.writestr("kotlin/stale.js", "old compiled module")
                result.writestr(
                    payload.NAT64,
                    "function toNat64Address (prefix, ipv4) {\n  return 'old'\n}\n",
                )
            payload.update_project(archive, library)
            payload.update_project(archive, library)
            with zipfile.ZipFile(archive) as result:
                names = result.namelist()
                self.assertEqual(len(names), len(set(names)))
                self.assertNotIn("kotlin/stale.js", names)
                self.assertEqual(b"untouched", result.read("core/keep.txt"))
                self.assertEqual(b"compiled", result.read("kotlin/" + payload.MODULE))
                self.assertIn(b"Kotlin helpers active", result.read("main.js"))
                self.assertIn(b"require('./http-navigation').install(CORE)", result.read("main.js"))
                self.assertIn(b"https-navigation", result.read("http-navigation.js"))
                self.assertEqual(
                    (payload.ROOT / "port/node/navigation-rules.json").read_bytes(),
                    result.read("navigation-rules.json"),
                )
                self.assertIn(b"DSHA_KOTLIN_HELPERS", result.read(payload.NAT64))


if __name__ == "__main__":
    unittest.main()
