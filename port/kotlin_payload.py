"""Package Kotlin/JS helpers and patch the NAT64 closure in the Node payload."""
import argparse
import os
from pathlib import Path
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LIBRARY = ROOT / "netcore-js/build/dist/js/productionLibrary"
MODULE = "dev-sidecar-lightning-netcore-js.js"
NAT64 = "core/node_modules/@blue-frontier/mitmproxy/src/lib/dns/nat64.js"
START = "function toNat64Address (prefix, ipv4) {"
END = "\n}\n"


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


def helper_entries(library=LIBRARY):
    library = Path(library)
    if not (library / MODULE).is_file():
        raise FileNotFoundError(
            "Build Kotlin helpers first: gradle :netcore-js:jsNodeProductionLibraryDistribution"
        )
    entries = {
        "main.js": (ROOT / "port/node/main.js").read_bytes(),
        "kotlin-helpers.js": (ROOT / "port/node/kotlin-helpers.js").read_bytes(),
        "http-navigation.js": (ROOT / "port/node/http-navigation.js").read_bytes(),
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
            if NAT64 not in original.namelist():
                raise ValueError("Payload is missing the upstream NAT64 module")
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as result:
                for item in original.infolist():
                    if item.filename in entries or item.filename.startswith("kotlin/"):
                        continue
                    data = original.read(item.filename)
                    if item.filename == NAT64:
                        data = patch_nat64(data.decode("utf-8")).encode("utf-8")
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
