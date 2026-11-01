"""Read-only source checks; deliberately does not invoke Gradle or a device."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(r"D:\File\User\code\dev-sidecar-lightning")
OUT = ROOT / "integration-artifacts"
SNAPSHOT_COMMIT = "c8aa068"
STYLE_HEAD = "ba03219be4c83268cdb1b0b9038ef544b5fe627b"
BASE = "754ff423b59c743cf94e68b3417c1fde4a6f42b7"


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", "-C", str(cwd), *args])


def hashes(paths, root=ROOT):
    return {p: hashlib.sha256((root / p).read_bytes()).hexdigest()
            if (root / p).exists() else None for p in paths}


def identity(values):
    return hashlib.sha256("".join(
        f"{p}\0{values[p]}\n" for p in sorted(values)).encode()).hexdigest()


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def verify():
    snapshot = json.loads((OUT / "source-snapshot.json").read_text())
    changed = git("diff", "--name-only", SNAPSHOT_COMMIT, "HEAD", "--",
                  "lightning-browser").decode().splitlines()
    cumulative = git("diff", "--name-only", BASE, "HEAD", "--",
                     "lightning-browser").decode().splitlines()
    sources = git("ls-files", "--", "lightning-browser").decode().splitlines()
    scanned_kt = [p for p in sources if p.endswith(".kt") and "/src/" in p]
    scanned_xml = [p for p in sources if p.endswith(".xml") and "/res/" in p]
    xml_errors = []
    definitions = {}
    for p in scanned_xml:
        try:
            tree = ET.parse(ROOT / p)
        except ET.ParseError as error:
            xml_errors.append({"path": p, "error": str(error)})
            continue
        kind = Path(p).parent.name.split("-")[0]
        if kind == "values":
            for element in tree.getroot():
                name = element.attrib.get("name")
                if name:
                    tag = element.attrib.get("type", element.tag)
                    definitions.setdefault(tag, set()).add(name)
        for element in tree.iter():
            for value in element.attrib.values():
                if value.startswith("@+id/"):
                    definitions.setdefault("id", set()).add(value[5:])
    # Include existing bitmap resources in symbol resolution, without copying or altering them.
    for p in sources:
        if "/res/" in p:
            kind = Path(p).parent.name.split("-")[0]
            if kind != "values":
                definitions.setdefault(kind, set()).add(Path(p).stem)
    checked_types = {"drawable", "mipmap", "string", "layout", "dimen", "id"}
    missing = []
    for p in scanned_kt:
        for kind, name in re.findall(r"(?<!android\.)\bR\.(\w+)\.(\w+)", read(p)):
            if kind in checked_types and name not in definitions.get(kind, set()):
                missing.append({"path": p, "type": kind, "name": name})
    changed_xml_missing = []
    for p in changed:
        if p.endswith(".xml") and (ROOT / p).exists():
            for kind, name in re.findall(r"@(\w+)/([\w.]+)", read(p)):
                if kind in checked_types and name not in definitions.get(kind, set()):
                    changed_xml_missing.append({"path": p, "type": kind, "name": name})
    conflict_markers = [p for p in scanned_kt + scanned_xml
                        if re.search(r"^(<<<<<<<|=======|>>>>>>>)", read(p), re.M)]
    marker_path = "lightning-browser/app/src/main/java/acr/browser/lightning/accel/DshaAccel.kt"
    marker_source = read(marker_path)
    marker_original = git("show", f"{SNAPSHOT_COMMIT}:{marker_path}").decode()
    marker_rollback = marker_source != marker_original
    if marker_rollback:
        assert marker_source.count('private const val PAYLOAD_VERSION = "8"') == 1
        assert marker_source.replace(
            'private const val PAYLOAD_VERSION = "8"',
            'private const val PAYLOAD_VERSION = "9"', 1) == marker_original
        restored_marker_bytes = (ROOT / marker_path).read_bytes().replace(
            b'private const val PAYLOAD_VERSION = "8"',
            b'private const val PAYLOAD_VERSION = "9"', 1)
        assert hashlib.sha256(restored_marker_bytes).hexdigest() == (
            snapshot["destination_copied_sha256"][marker_path])
    merged_snapshot_paths = [
        p for p in snapshot["selected_files"]
        if hashes([p])[p] != snapshot["destination_copied_sha256"][p]
        and not (marker_rollback and p == marker_path)
    ]
    browser = "lightning-browser/app/src/main/java/acr/browser/lightning/browser/"
    assert set(merged_snapshot_paths) == {
        browser + "BrowserContract.kt", browser + "BrowserPresenter.kt",
        browser + "compose/BrowserOverflowMenu.kt", browser + "menu/MenuSelection.kt",
    }, merged_snapshot_paths
    preserved = {p: hashes([p])[p] for p in snapshot["selected_files"]
                 if p not in merged_snapshot_paths and not (marker_rollback and p == marker_path)}
    assert len(preserved) == (31 if marker_rollback else 32)
    # Confirm that the shared presenter/contract/enum retain the complete captured source
    # after subtracting only the requested additions.
    additions = {
        browser + "BrowserContract.kt": (
            "\n        /**\n"
            "         * End the incognito session and return to the regular browser.\n"
            "         */\n        suspend fun exitIncognito()\n"
        ),
        browser + "BrowserPresenter.kt": (
            "            MenuSelection.EXIT_INCOGNITO -> {\n"
            "                model.deleteAllTabs()\n"
            "                state.updateSelf { updateTabViewState() }\n"
            "                navigator.exitIncognito()\n"
            "            }\n\n"
        ),
        browser + "menu/MenuSelection.kt": "    EXIT_INCOGNITO,\n",
    }
    for p, addition in additions.items():
        content = read(p)
        assert content.count(addition) == 1, p
        original = git("show", f"{SNAPSHOT_COMMIT}:{p}").decode()
        assert content.replace(addition, "", 1) == original, p
    menu = read(browser + "compose/BrowserOverflowMenu.kt")
    duplicate = (
        "            DropdownMenuItem(\n"
        "                text = { DropdownText(stringResource(R.string.action_duplicate_tab)) },\n"
        "                enabled = browserViewState.tabs.isNotEmpty(),\n"
        "                onClick = {\n"
        "                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.DUPLICATE_TAB))\n"
        "                    dropDownExpanded = false\n"
        "                }\n"
        "            )\n"
    )
    assert menu.count(duplicate) == 1
    original_menu = git("show", f"{SNAPSHOT_COMMIT}:{browser}compose/BrowserOverflowMenu.kt").decode()
    expected_menu = original_menu.replace(
        """            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.settings)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SETTINGS))
                        dropDownExpanded = false
                    }
                )
            }""",
        """            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.settings)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SETTINGS))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_exit_incognito)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.EXIT_INCOGNITO))
                        dropDownExpanded = false
                    }
                )
            }"""
    )
    assert menu == expected_menu
    chrome = read(browser + "tab/TabWebChromeClient.kt")
    assert "import com.google.android.material.dialog.MaterialAlertDialogBuilder\n" in chrome
    assert all(f"override fun {name}(" in chrome for name in (
        "onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload"))
    # Detect drift in the original eligible browser source, including added/removed files.
    # Use the exact same selection policy as the capture, loaded without generating pycache.
    capture_namespace = {"__file__": str(OUT / "capture_snapshot.py"),
                         "__name__": "snapshot_policy"}
    exec(compile(read("integration-artifacts/capture_snapshot.py"),
                 "capture_snapshot.py", "exec"), capture_namespace)
    _, _, upstream_paths = capture_namespace["inventory"]()
    upstream = hashes(upstream_paths, SOURCE)
    _, _, repeated_paths = capture_namespace["inventory"]()
    assert upstream_paths == repeated_paths and upstream == hashes(repeated_paths, SOURCE)
    before = snapshot["source_after_sha256"]
    upstream_drift = {p: {"snapshot": before.get(p), "now": upstream.get(p)}
                      for p in sorted(set(before) | set(upstream))
                      if before.get(p) != upstream.get(p)}
    eligible = capture_namespace["eligible"]
    tracked = set(sources)
    integrated = hashes([p for p in sources if eligible(p, tracked)])
    assert not xml_errors, xml_errors
    assert not missing, missing
    assert not changed_xml_missing, changed_xml_missing
    assert not conflict_markers, conflict_markers
    git("diff", "--check", SNAPSHOT_COMMIT, "HEAD", "--", "lightning-browser")
    report = {
        "checked_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source_commit": git("rev-parse", "HEAD").decode().strip(),
        "snapshot_commit": git("rev-parse", SNAPSHOT_COMMIT).decode().strip(),
        "base_commit": BASE, "requested_style_head": STYLE_HEAD,
        "snapshot_selected_sha256": snapshot["selected_snapshot_sha256"],
        "snapshot_source_tree_sha256": snapshot["source_tree_sha256"],
        "integrated_source_tree_sha256": identity(integrated),
        "integrated_source_sha256": integrated,
        "style_delta_sha256": hashes(changed),
        "style_changed_files": changed,
        "cumulative_changed_files": cumulative,
        "preserved_snapshot_files_sha256": preserved,
        "payload_marker": {
            "version": "8" if marker_rollback else "9",
            "authorized_rollback_only": marker_rollback,
            "all_other_dsha_source_matches_snapshot": True,
            "dsha_source_sha256": hashes([marker_path])[marker_path],
        },
        "merged_snapshot_paths": merged_snapshot_paths,
        "upstream_head_now": git("rev-parse", "HEAD", cwd=SOURCE).decode().strip(),
        "upstream_source_tree_sha256_now": identity(upstream),
        "upstream_drift": upstream_drift,
        "checks": {
            "xml_parsed": len(scanned_xml),
            "kotlin_resource_references_scanned": len(scanned_kt),
            "missing_local_resource_references": missing,
            "changed_xml_missing_references": changed_xml_missing,
            "conflict_markers": conflict_markers,
            "git_diff_check": "passed",
            "preserved_snapshot_files": len(preserved),
            "merged_snapshot_additions_only": True,
            "menu_duplicate_block_preserved": True,
            "gradle": "see separate build acceptance evidence",
            "device": "not accessed",
        },
    }
    (OUT / "integration-verification.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in (
        "source_commit", "integrated_source_tree_sha256",
        "upstream_source_tree_sha256_now", "upstream_drift", "checks")}, indent=2))
    print(f"Style delta: {len(changed)} paths; cumulative browser delta: {len(cumulative)} paths.")


if __name__ == "__main__":
    verify()
