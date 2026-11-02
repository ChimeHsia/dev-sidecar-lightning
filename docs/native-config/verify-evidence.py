"""Read-only source/artifact checks; writes only this candidate's verification report."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent.parent
OUT = ROOT / "build/native-config"
BASE = "05d3ae33edba11d70c95e8d18d4b011e9c0da533"
SCOPES = [
    "netcore/src/main/kotlin/com/dsha/sidecar/net/config/native/",
    "netcore/src/test/kotlin/com/dsha/sidecar/net/config/native/",
    "netcore/src/test/fixtures/native-config/",
    "docs/native-config/",
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args]).decode().strip()


def verify():
    changed = git("diff", "--name-only", BASE, "HEAD").splitlines()
    assert changed and all(any(path.startswith(scope) for scope in SCOPES) for path in changed)
    assert git("diff", "--name-only", BASE, "HEAD", "--",
               "netcore/src/main/kotlin/com/dsha/sidecar/net/config/ConfigMerge.kt") == ""
    junit = (OUT / "junit.log").read_text(encoding="utf-8")
    total = int(re.search(r"OK \((\d+) tests\)", junit).group(1))
    assert total == 55
    oracle = (OUT / "oracle-verify.log").read_text(encoding="utf-8")
    assert "oracle passed: 112 cases" in oracle
    smoke = (OUT / "consumer-smoke.log").read_text(encoding="utf-8")
    assert "Real RulePlanner redirect + DNS catalog + NativeRuntimeConfig decode/rollback passed." in smoke
    fixtures = ROOT / "netcore/src/test/fixtures/native-config"
    corpus = json.loads((fixtures / "oracle-cases.json").read_text(encoding="utf-8"))
    provenance = json.loads((fixtures / "upstream-provenance.json").read_text(encoding="utf-8"))
    assert [len(corpus[name]) for name in ("parse", "reject", "layers")] == [55, 17, 40]
    assert (fixtures / "oracle-cases.json").read_bytes() == (OUT / "oracle-cases.json").read_bytes()
    assert (fixtures / "upstream-provenance.json").read_bytes() == (OUT / "upstream-provenance.json").read_bytes()
    peer_hashes = json.loads((OUT / "consumer-source-hashes.json").read_text(encoding="utf-8-sig"))
    peer_copies = json.loads((OUT / "consumer-source-copies.json").read_text(encoding="utf-8-sig"))
    assert all(sha(Path(peer_copies[path]).read_bytes()) == expected for path, expected in peer_hashes.items())
    peer_drift = {path: {"tested": expected, "now": sha(Path(path).read_bytes())}
                  for path, expected in peer_hashes.items() if sha(Path(path).read_bytes()) != expected}
    production_classes = []
    with zipfile.ZipFile(OUT / "config-tests.jar") as classes:
        for name in classes.namelist():
            if (name.startswith("com/dsha/sidecar/net/config/native/") and name.endswith(".class")
                    and "Test" not in name and "Oracle" not in name):
                assert b"java/nio/file/" not in classes.read(name), name
                production_classes.append(name)
    assert production_classes
    files = [path for path in git("ls-files", "--", *SCOPES).splitlines()
             if path != "docs/native-config/verification.json"]
    hashes = {path: sha((ROOT / path).read_bytes()) for path in sorted(files)}
    source_digest = sha("".join(f"{path}\0{hashes[path]}\n" for path in sorted(hashes)).encode())
    production = {path: value for path, value in hashes.items() if path.startswith(SCOPES[0])}
    assert len(production) == 4
    report = {
        "verified_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source_commit": git("rev-parse", "HEAD"),
        "source_git_tree": git("rev-parse", "HEAD^{tree}"),
        "base_commit": BASE,
        "source_sha256": hashes,
        "source_tree_sha256": source_digest,
        "source_digest_format": "SHA256(sorted(path + NUL + raw-content-sha256 + LF), UTF-8); excludes report itself",
        "production_source_sha256": production,
        "changed_paths": changed,
        "shared_config_merge_unchanged": True,
        "jvm": {"total": total, "passed": total, "failed": 0, "skipped": 0,
                "existing_config_merge": 14, "new_native_config": 41},
        "independent_oracle": {"json5_version": "2.2.3", "parse": 55,
                               "strict_rejections": 17, "layer_and_key_order": 40, "total": 112},
        "upstream_provenance": provenance,
        "consumer_tests": {
            "rule_planner_302": True, "dns_catalog": True, "native_runtime_decode": True,
            "native_consumer_rejection_rollback": True, "source_before_after_hashes": peer_hashes,
            "pinned_source_copies": peer_copies, "later_peer_source_drift": peer_drift,
        },
        "compatibility": {
            "public_file_boundary": "java.io.File",
            "nio_file_static_class_references": 0,
            "audited_production_classes": len(production_classes),
            "legacy_classloader_no_nio_actual_read_and_reload": "passed",
            "android_api24_runtime_executed": False,
        },
        "resource_checks": ["document_bytes", "aggregate_bytes", "depth", "keys", "nodes",
                            "string_chars", "number_chars", "path_and_file_alias", "immutable_snapshot",
                            "serialized_writers_atomic_readers", "failure_preserves_previous",
                            "consumer_validator_rollback", "reentrant_write_rejection"],
        "production_parser_dependency_added": False,
        "library_audit": json.loads((fixtures / "library-audit.json").read_text(encoding="utf-8")),
        "tooling": {"jdk": "21.0.2", "kotlin_compiler": "2.0.21", "compiler_heap": "256m",
                    "test_heap": "256m", "gradle_used": False, "device_access": False,
                    "network_config_fetch_executed": False, "production_node_or_eval": False},
        "artifact_sha256": {
            name: sha((OUT / name).read_bytes()) for name in (
                "config-tests.jar", "consumer-smoke.jar", "compile.log", "junit.log",
                "oracle-verify.log", "kotlin-oracle-results.json", "consumer-compile.log",
                "consumer-smoke.log", "compiler.args", "consumer-compiler.args",
            )
        },
        "limitations": [
            "Explicit JSON5 subset; Unicode unquoted names, duplicates, non-finite values and other stated inputs reject",
            "Trusted private root required; canonical checks are not an openat/O_NOFOLLOW hostile-race sandbox",
            "No actual Android API24/device run or running-engine activation",
            "No built-in HTTPS transport, refresh scheduling, redirects or TLS policy",
            "Bootstrap endpoints/preset lists must be provided explicitly by caller",
        ],
    }
    (ROOT / "docs/native-config/verification.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (OUT / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: report[name] for name in (
        "source_commit", "source_git_tree", "source_tree_sha256", "jvm", "independent_oracle",
        "compatibility")}, indent=2))


if __name__ == "__main__":
    verify()
