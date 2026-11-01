"""Verify the isolated release against accepted payload8 without using a device."""
import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / "port/.run/browser-style-integration-v8-20261102"
JDK = Path(r"D:/File/User/code/dev-sidecar-lightning/.jdk/jdk-21.0.2")
TOOLS = Path(r"D:/App/Dev/Android/build-tools/37.0.0")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def command(args):
    process = subprocess.run(
        [str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    assert process.returncode == 0, process.stdout + process.stderr
    return process.stdout + process.stderr


def verify():
    inputs = json.loads((RUN / "inputs.json").read_text(encoding="utf-8"))
    for task in ("browser-jvm-tests", "release"):
        assert int((RUN / f"{task}.exitcode").read_text().strip()) == 0, task
        assert "BUILD SUCCESSFUL" in (RUN / f"{task}.log").read_text(encoding="utf-8"), task

    results = ROOT / "lightning-browser/app/build/test-results/testLightningPlusDebugUnitTest"
    suites = []
    for path in sorted(results.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        suites.append({"name": suite.attrib["name"], **{
            key: int(suite.attrib.get(key, 0))
            for key in ("tests", "failures", "errors", "skipped")
        }})
    totals = {key: sum(s[key] for s in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    assert totals["tests"] > 0 and totals["failures"] == totals["errors"] == totals["skipped"] == 0

    tree = command(["git", "-C", ROOT, "rev-parse", "HEAD:lightning-browser"]).strip()
    assert tree == inputs["browser_tree"], "Browser source tree changed during validation"
    built = ROOT / "lightning-browser/app/build/outputs/apk/lightningPlus/release/app-lightningPlus-release.apk"
    assert built.exists(), built
    apk_hash = sha(built.read_bytes())
    artifact = RUN / f"browser-style-integration-payload8-{inputs['source_commit'][:7]}-release.apk"
    if artifact.exists():
        assert sha(artifact.read_bytes()) == apk_hash
    else:
        shutil.copyfile(built, artifact)
    assert sha(artifact.read_bytes()) == apk_hash

    signer = command([JDK / "bin/java.exe", "-jar", TOOLS / "lib/apksigner.jar",
                      "verify", "--verbose", "--print-certs", artifact])
    (RUN / "release-apksigner.txt").write_text(signer, encoding="utf-8")
    expected_signer = "d8755927514c111bf255341f3f8171d6edc61c89be0ac4f3c45fe7909e36950b"
    assert re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", signer) == [expected_signer]
    alignment = command([TOOLS / "zipalign.exe", "-c", "-P", "16", "4", artifact])
    (RUN / "release-zipalign.txt").write_text(alignment, encoding="utf-8")
    badging = command([TOOLS / "aapt.exe", "dump", "badging", artifact])
    (RUN / "release-badging.txt").write_text(badging, encoding="utf-8")
    assert "package: name='com.dsha.browser'" in badging
    assert "native-code: 'arm64-v8a'" in badging
    resources = command([TOOLS / "aapt2.exe", "dump", "resources", artifact])
    (RUN / "release-resources.txt").write_text(resources, encoding="utf-8")
    # Release resource optimization renames files; resolve the CA through its resource entry.
    raw_ca_match = re.search(r"resource [^\n]+ raw/dsha_ca\n\s+\(\) \(file\) (\S+)", resources)
    assert raw_ca_match, "Packaged raw/dsha_ca resource is missing"
    raw_ca_path = raw_ca_match.group(1)

    approved = Path(inputs["accepted_asset_source"])
    isolated = Path(inputs["isolated_assets_override"])
    asset_hashes = {}
    nested = {}
    with zipfile.ZipFile(artifact) as apk, zipfile.ZipFile(inputs["accepted_baseline_apk"]) as baseline:
        assert apk.testzip() is None
        for name, expected in inputs["assets_sha256"].items():
            data = apk.read(f"assets/{name}")
            assert sha(data) == expected, name
            assert data == (approved / name).read_bytes() == (isolated / name).read_bytes(), name
            assert data == baseline.read(f"assets/{name}"), name
            asset_hashes[name] = expected
            if name.endswith(".zip"):
                with zipfile.ZipFile(io.BytesIO(data)) as inner:
                    assert inner.testzip() is None, name
                    nested[name] = {"sha256": expected, "entries": len(inner.infolist()), "crc_valid": True}
        node_paths = [name for name in apk.namelist()
                      if name.startswith("lib/") and name.endswith("/libnodebin.so")]
        assert node_paths == ["lib/arm64-v8a/libnodebin.so"]
        node = apk.read(node_paths[0])
        assert sha(node) == inputs["jni_sha256"]
        assert node == Path(inputs["jni_source"]).read_bytes() == baseline.read(node_paths[0])
        certificate = x509.load_pem_x509_certificate(apk.read("assets/dsha_ca.crt"))
        raw_certificate = x509.load_pem_x509_certificate(apk.read(raw_ca_path))
        to_der = lambda cert: cert.public_bytes(serialization.Encoding.DER)
        assert to_der(certificate) == to_der(raw_certificate)
        dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        assert any(b".v8.done" in data for data in dex)
        assert not any(b".v9.done" in data for data in dex)
        extra_assets = sorted(name for name in apk.namelist()
                              if name.startswith("assets/") and not name.endswith("/")
                              and name[7:] not in inputs["assets_sha256"])

    report = {
        "verified_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source_commit": inputs["source_commit"],
        "browser_git_tree": tree,
        "source_eligible_tree_sha256": json.loads(
            (ROOT / "integration-artifacts/integration-verification.json").read_text()
        )["integrated_source_tree_sha256"],
        "payload_version": "8",
        "apk_path": str(artifact),
        "apk_size": artifact.stat().st_size,
        "apk_sha256": apk_hash,
        "apk_signer_certificate_sha256": expected_signer,
        "signer_matches_accepted_v8": True,
        "zipalign_16k_check": "passed",
        "jvm_test_totals": totals,
        "jvm_test_suites": suites,
        "assets_sha256": asset_hashes,
        "all_15_assets_identical_to_accepted_v8_and_isolated_override": True,
        "extra_generated_assets": extra_assets,
        "nested_zip_checks": nested,
        "jni_sha256": inputs["jni_sha256"],
        "jni_matches_ui_checkout_and_accepted_v8": True,
        "packaged_raw_ca_matches_payload_ca": True,
        "packaged_raw_ca_archive_path": raw_ca_path,
        "ca_certificate_der_sha256": sha(to_der(certificate)),
        "ca_input_checks": json.loads((RUN / "ca-verification.json").read_text()),
        "dex_marker_v8_present_v9_absent": True,
        "gradle_tests": "passed",
        "gradle_release": "passed",
        "installed": False,
        "device_access": False,
        "acceptance_scope": "JVM and release packaging; device acceptance remains with coordinator",
    }
    (RUN / "build-verification.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "source_commit", "browser_git_tree", "apk_path", "apk_size", "apk_sha256",
        "apk_signer_certificate_sha256", "jvm_test_totals", "nested_zip_checks",
        "extra_generated_assets", "dex_marker_v8_present_v9_absent"
    )}, indent=2))


if __name__ == "__main__":
    verify()
