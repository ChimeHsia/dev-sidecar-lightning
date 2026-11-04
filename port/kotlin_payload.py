"""Package Kotlin/JS helpers and Android proxy fixes in the Node payload."""
import argparse
import json
import os
from pathlib import Path
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LIBRARY = ROOT / "netcore-js/build/dist/js/productionLibrary"
MODULE = "dev-sidecar-lightning-netcore-js.js"
NAT64 = "core/node_modules/@blue-frontier/mitmproxy/src/lib/dns/nat64.js"
REQUEST_HANDLER = "core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/createRequestHandler.js"
REQUEST_OPTIONS = "core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/common/util.js"
UPGRADE_HANDLER = "core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/createUpgradeHandler.js"
PATCH_TARGETS = {NAT64, REQUEST_HANDLER, REQUEST_OPTIONS, UPGRADE_HANDLER}
START = "function toNat64Address (prefix, ipv4) {"
END = "\n}\n"

def replace_once(source, old, new):
    if new in source:
        if source.count(new) != 1 or old in source.replace(new, "", 1):
            raise ValueError("Unsupported upstream proxy implementation: ambiguous patch")
        return source
    if source.count(old) != 1:
        raise ValueError("Unsupported upstream proxy implementation: patch target changed")
    return source.replace(old, new, 1)


def patch_transport(name, source):
    """Keep client Connection: close from changing TLS policy; bound TLS startup."""
    source = source.replace("\r\n", "\n")
    if name == REQUEST_OPTIONS:
        source = source.replace(
            "// Connection: close 时不走 Agent，需把 TLS 版本映射写到直连请求 options",
            "// Connection: close still uses the configured TLS/DNS/SNI policy.",
        )
        return replace_once(
            source, "      agent = false\n",
            "      // Android: preserve policy without reusing this connection.\n"
            "      agent = createAgent(protocol, timeoutConfig, serverSetting.verifySsl)\n",
        )
    if name == REQUEST_HANDLER:
        source = replace_once(
            source, "              if (!sock || !sock.connecting) {",
            "              if (!sock || (!sock.connecting && !sock.secureConnecting)) {",
        )
        source = replace_once(
            source, "            if (socket.connecting) {\n              startConnectTimer()",
            "            if (socket.connecting || socket.secureConnecting) {\n              startConnectTimer()",
        )
        source = replace_once(
            source, "              socket.once('connect', () => {\n                clearConnectTimer()",
            "              socket.once(socket.encrypted ? 'secureConnect' : 'connect', () => {\n                clearConnectTimer()",
        )
        return replace_once(
            source, "          proxyReq.on('timeout', () => {",
            "          proxyReq.once('close', clearConnectTimer)\n          proxyReq.on('timeout', () => {",
        )
    return source


def patch_nat64(source):
    """Replace the internal function, not just the unused module export."""
    source = source.replace("\r\n", "\n")
    if source.count(START) != 1:
        raise ValueError("Unsupported upstream NAT64 implementation: expected one function")
    start = source.index(START)
    end = source.index(END, start) + len(END)
    replacement = (
        START + "\n"
        "  return require(process.env.DSHA_KOTLIN_HELPERS).toNat64Address(prefix, ipv4)\n"
        "}\n"
    )
    return source[:start] + replacement + source[end:]


def patch_request_runtime(source):
    """Replace pure request fields while leaving transport/proxy branches upstream."""
    source = source.replace("\r\n", "\n")
    patches = json.loads((ROOT / "port/node/request-runtime-patches.json").read_text(encoding="utf-8"))
    for patch in patches:
        source = replace_once(source, patch["old"], patch["new"])
    return source


def patch_request_validation(name, source):
    """Contain rejected client input at the real HTTP and upgrade boundaries."""
    source = source.replace("\r\n", "\n")
    patches = json.loads((ROOT / "port/node/request-validation-patches.json").read_text(encoding="utf-8"))
    for patch in patches.get(name, []):
        source = replace_once(source, patch["old"], patch["new"])
    return source


def patch_core_file(name, source):
    if name == NAT64:
        return patch_nat64(source)
    source = patch_transport(name, source)
    if name == REQUEST_OPTIONS:
        source = patch_request_runtime(source)
    return patch_request_validation(name, source)


def helper_entries(library=LIBRARY):
    library = Path(library)
    if not (library / MODULE).is_file():
        raise FileNotFoundError(
            "Build Kotlin helpers first: gradle :netcore-js:jsNodeProductionLibraryDistribution"
        )
    entries = {
        "main.js": (ROOT / "port/node/main.js").read_bytes(),
        "kotlin-helpers.js": (ROOT / "port/node/kotlin-helpers.js").read_bytes(),
        "kotlin-wave2.js": (ROOT / "port/node/kotlin-wave2.js").read_bytes(),
        "kotlin-match-runtime.js": (ROOT / "port/node/kotlin-match-runtime.js").read_bytes(),
        "kotlin-choice-runtime.js": (ROOT / "port/node/kotlin-choice-runtime.js").read_bytes(),
        "kotlin-request-runtime.js": (ROOT / "port/node/kotlin-request-runtime.js").read_bytes(),
        "request-runtime-patches.json": (ROOT / "port/node/request-runtime-patches.json").read_bytes(),
        "request-validation-patches.json": (ROOT / "port/node/request-validation-patches.json").read_bytes(),
        "http-navigation.js": (ROOT / "port/node/http-navigation.js").read_bytes(),
        "proxy-runtime.js": (ROOT / "port/node/proxy-runtime.js").read_bytes(),
        "navigation-rules.json": (ROOT / "port/node/navigation-rules.json").read_bytes(),
    }
    for file in library.glob("*.js"):
        entries["kotlin/" + file.name] = file.read_bytes()
    return entries


def update_project(archive, library=LIBRARY):
    """Atomically refresh an existing payload without downloading Node or CAs."""
    archive = Path(archive).resolve()
    entries = helper_entries(library)
    handle, temporary = tempfile.mkstemp(prefix=archive.name, suffix=".tmp", dir=archive.parent)
    os.close(handle)
    try:
        with zipfile.ZipFile(archive) as original:
            if not PATCH_TARGETS.issubset(original.namelist()):
                raise ValueError("Payload is missing upstream proxy modules")
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as result:
                for item in original.infolist():
                    if item.filename in entries or item.filename.startswith("kotlin/"):
                        continue
                    data = original.read(item.filename)
                    if item.filename in PATCH_TARGETS:
                        data = patch_core_file(item.filename, data.decode("utf-8")).encode("utf-8")
                    result.writestr(item, data)
                for name, data in entries.items():
                    result.writestr(name, data)
        os.replace(temporary, archive)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--library", type=Path, default=LIBRARY)
    args = parser.parse_args()
    update_project(args.archive, args.library)
    print("Updated Kotlin helpers:", args.archive)
