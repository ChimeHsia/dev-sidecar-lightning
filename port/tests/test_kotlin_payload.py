import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "kotlin_payload", Path(__file__).resolve().parents[1] / "kotlin_payload.py"
)
payload = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(payload)

OPTIONS_SOURCE = "      agent = false\n"
HANDLER_SOURCE = (
    "              if (!sock || !sock.connecting) {\n"
    "            if (socket.connecting) {\n              startConnectTimer()\n"
    "              socket.once('connect', () => {\n                clearConnectTimer()\n"
    "          proxyReq.on('timeout', () => {\n"
)
REQUEST_PATCHES = json.loads(
    (payload.ROOT / "port/node/request-runtime-patches.json").read_text(encoding="utf-8")
)
REQUEST_SOURCE = "\n".join(patch["old"] for patch in REQUEST_PATCHES)
VALIDATION_PATCHES = json.loads(
    (payload.ROOT / "port/node/request-validation-patches.json").read_text(encoding="utf-8")
)
RECOVERY_PATCHES = json.loads(
    (payload.ROOT / "port/node/transport-recovery-patches.json").read_text(encoding="utf-8")
)
RECOVERY_SOURCES = {
    name: "\n".join(patch["old"] * patch.get("count", 1) for patch in patches)
    for name, patches in RECOVERY_PATCHES.items()
}


class KotlinPayloadTest(unittest.TestCase):
    def test_repeated_transport_patch_requires_the_exact_expected_count(self):
        patched = payload.patch_core_file(payload.DNS_LOOKUP, RECOVERY_SOURCES[payload.DNS_LOOKUP])
        self.assertEqual(patched, payload.patch_core_file(payload.DNS_LOOKUP, patched))
        for source in ["", "if (res) {" * 4, "if (res) {" * 6, patched + "if (res) {"]:
            with self.assertRaises(ValueError):
                payload.patch_core_file(payload.DNS_LOOKUP, source)

    def test_request_validation_is_patched_in_both_real_handlers_and_is_strict(self):
        for name, patches in VALIDATION_PATCHES.items():
            source = "\n".join(patch["old"] for patch in patches)
            patched = payload.patch_request_validation(name, source)
            self.assertIn("DSHA_INVALID_REQUEST", patched)
            self.assertEqual(patched, payload.patch_request_validation(name, patched))
            self.assertEqual(
                patched, payload.patch_request_validation(name, source.replace("\n", "\r\n")),
            )
            with self.assertRaises(ValueError):
                payload.patch_request_validation(name, "// unsupported upstream")

    def test_request_runtime_patch_is_strict_and_idempotent(self):
        patched = payload.patch_request_runtime(REQUEST_SOURCE)
        for patch in REQUEST_PATCHES:
            self.assertIn(patch["new"], patched)
        self.assertEqual(patched, payload.patch_request_runtime(patched))
        self.assertEqual(patched, payload.patch_request_runtime(REQUEST_SOURCE.replace("\n", "\r\n")))
        with self.assertRaises(ValueError):
            payload.patch_request_runtime("// unsupported upstream")

    def test_transport_patches_cover_tls_handshake_and_preserve_close_policy(self):
        options = payload.patch_transport(payload.REQUEST_OPTIONS, OPTIONS_SOURCE)
        self.assertIn("createAgent(protocol, timeoutConfig, serverSetting.verifySsl)", options)
        handler = payload.patch_transport(payload.REQUEST_HANDLER, HANDLER_SOURCE)
        self.assertIn("sock.secureConnecting", handler)
        self.assertIn("socket.secureConnecting", handler)
        self.assertIn("socket.encrypted ? 'secureConnect' : 'connect'", handler)
        self.assertIn("proxyReq.once('close', clearConnectTimer)", handler)
        for name, source, patched in [
            (payload.REQUEST_OPTIONS, OPTIONS_SOURCE, options),
            (payload.REQUEST_HANDLER, HANDLER_SOURCE, handler),
        ]:
            self.assertEqual(patched, payload.patch_transport(name, patched))
            self.assertEqual(patched, payload.patch_transport(name, source.replace("\n", "\r\n")))
            with self.assertRaises(ValueError):
                payload.patch_transport(name, "// unsupported upstream")

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
                result.writestr(payload.REQUEST_OPTIONS, OPTIONS_SOURCE + REQUEST_SOURCE)
                result.writestr(
                    payload.REQUEST_HANDLER,
                    HANDLER_SOURCE + "\n".join(p["old"] for p in VALIDATION_PATCHES[payload.REQUEST_HANDLER])
                    + "\n" + RECOVERY_SOURCES[payload.REQUEST_HANDLER],
                )
                result.writestr(payload.DNS_LOOKUP, RECOVERY_SOURCES[payload.DNS_LOOKUP])
                result.writestr(
                    payload.UPGRADE_HANDLER,
                    "\n".join(p["old"] for p in VALIDATION_PATCHES[payload.UPGRADE_HANDLER]),
                )
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
                self.assertIn(b"require('./kotlin-wave2').install", result.read("main.js"))
                for name in [
                    "kotlin-wave2.js", "kotlin-match-runtime.js",
                    "kotlin-choice-runtime.js", "kotlin-request-runtime.js",
                ]:
                    self.assertEqual(
                        (payload.ROOT / "port/node" / name).read_bytes(), result.read(name),
                    )
                self.assertIn(b"require('./http-navigation').install(CORE)", result.read("main.js"))
                self.assertIn(b"require('./proxy-runtime').start(CORE, config)", result.read("main.js"))
                self.assertIn(b"waitForListening", result.read("proxy-runtime.js"))
                self.assertIn(b"sock.secureConnecting", result.read(payload.REQUEST_HANDLER))
                self.assertIn(b"error.requestMayHaveBeenSent", result.read(payload.REQUEST_HANDLER))
                self.assertIn(b"!res.headersSent", result.read(payload.DNS_LOOKUP))
                self.assertIn(b"kotlinRequestRuntimeVersion = 1", result.read(payload.REQUEST_OPTIONS))
                for name in [payload.REQUEST_HANDLER, payload.UPGRADE_HANDLER]:
                    self.assertIn(b"DSHA_INVALID_REQUEST", result.read(name))
                self.assertIn(b"https-navigation", result.read("http-navigation.js"))
                self.assertEqual(
                    (payload.ROOT / "port/node/navigation-rules.json").read_bytes(),
                    result.read("navigation-rules.json"),
                )
                self.assertIn(b"DSHA_KOTLIN_HELPERS", result.read(payload.NAT64))


if __name__ == "__main__":
    unittest.main()
