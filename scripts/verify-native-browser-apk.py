"""Check that the browser packages the Kotlin runtime and data without any Node payload."""
import argparse
import json
from pathlib import Path
import zipfile


def verify(path):
    with zipfile.ZipFile(path) as apk:
        entries = apk.namelist()
        forbidden = [name for name in entries if any(token in name.lower() for token in
            ("nodejs-project", "node-libs.zip", "libnodebin", "libnode.so", "libuv.so", "node_modules/"))]
        if forbidden:
            raise ValueError(f"Node payload remains in APK: {forbidden[:10]}")
        required = ["assets/native/default-config.json", "assets/native/navigation-rules.json",
                    "assets/native/provenance.json"]
        for name in required:
            if name not in entries:
                raise ValueError(f"Missing native data: {name}")
        config = json.loads(apk.read(required[0]))
        if not config["server"]["dns"]["ech"]["enabled"]:
            raise ValueError("Default ECH was disabled")
        dexes = [name for name in entries if name.startswith("classes") and name.endswith(".dex")]
        needles = [b"Lcom/dsha/sidecar/net/runtime/NativeRuntime;",
                   b"Lacr/browser/lightning/accel/NativeProxyService;"]
        found = set()
        for name in dexes:
            data = apk.read(name)
            found.update(needle for needle in needles if needle in data)
        if len(found) != len(needles):
            raise ValueError("Native runtime/service missing from DEX")
        if not any("conscrypt" in name.lower() and name.endswith(".so") for name in entries):
            raise ValueError("Conscrypt native library missing")
        return {"apk": str(path), "bytes": path.stat().st_size, "nodeEntries": 0,
                "nativeData": required, "runtimeClasses": len(found),
                "hostnameRules": len(config["server"]["intercepts"]), "echEnabled": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.apk)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
