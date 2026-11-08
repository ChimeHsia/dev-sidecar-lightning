"""Export reviewed dev-sidecar configuration data; the resulting Android app needs no Node."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "lightning-browser/app/src/main/assets/native"


def export(source, output=OUTPUT):
    raw = source.read_bytes()
    if len(raw) > 1_048_576:
        raise ValueError("Configuration exceeds 1 MiB")
    document = json.loads(raw.decode("utf-8-sig"))
    server = dict(document.get("server", document))
    settings = dict(server.get("setting", {}))
    for key in ("userBasePath", "rootDir", "rootCaFile"):
        settings.pop(key, None)
    server["setting"] = settings
    for key in ("app", "plugin", "proxy"):
        server.pop(key, None)
    # Native binds loopback itself. Runtime-local paths must never enter a distributable asset.
    server["host"] = "127.0.0.1"
    server["port"] = 31181
    result = {"server": server}
    app = document.get("app")
    if isinstance(app, dict):
        # Keep remote endpoint policy, not desktop window, update or account data.
        result["app"] = {key: app[key] for key in ("remoteConfig",) if key in app}
    output.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if any(value in rendered for value in ("userBasePath", "rootCaFile", "PRIVATE KEY", "D:\\\\")):
        raise ValueError("Machine-local configuration leaked into export")
    (output / "default-config.json").write_text(rendered, encoding="utf-8")
    (output / "navigation-rules.json").write_bytes((ROOT / "port/node/navigation-rules.json").read_bytes())
    provenance = {
        "upstream": "@blue-frontier/dev-sidecar",
        "version": "2.3.1",
        "source": "Reviewed effective Android configuration, exported as data only",
        "sourceSha256": hashlib.sha256(raw).hexdigest(),
        "configurationSha256": hashlib.sha256(rendered.encode()).hexdigest(),
        "omittedRuntimeSettings": ["userBasePath", "rootDir", "rootCaFile"],
    }
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(server.get('intercepts', {}))} hostname rules; {len(rendered.encode())} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--running-config", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    export(args.running_config, args.output)
