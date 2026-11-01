"""Capture only authorized browser source edits; never write to the source."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time

DEST = Path(__file__).resolve().parent.parent
SOURCE = Path(r"D:\File\User\code\dev-sidecar-lightning")
OUT = DEST / "integration-artifacts"


def git(*args):
    return subprocess.check_output(["git", "-C", str(SOURCE), *args])


def paths(*args):
    return sorted(set(git("ls-files", "-z", *args, "--", "lightning-browser")
                      .decode().rstrip("\0").split("\0")) - {""})


def eligible(path, tracked):
    parts = Path(path).parts
    if any(p in {"assets", "jniLibs", ".gradle", ".devtest", "build"} for p in parts):
        return False
    if any(token in path.lower() for token in ("private", ".pem", ".key", "payload")):
        return False
    if "/src/" in path:
        return ("/src/main/java/" in path or "/src/main/kotlin/" in path
                or "/src/main/res/" in path or "/src/test/" in path
                or "/src/androidTest/" in path) and Path(path).suffix in {
                    ".kt", ".java", ".xml", ".json", ".txt", ".properties"
                }
    return path in tracked and (
        path.endswith((".gradle", ".gradle.kts"))
        or path in {"lightning-browser/gradle.properties"}
        or path.startswith("lightning-browser/gradle/") and path.endswith((".toml", ".properties"))
    )


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity(hashes):
    return digest("".join(f"{p}\0{hashes[p]}\n" for p in sorted(hashes)).encode())


def inventory():
    tracked = set(paths("--cached"))
    candidates = paths("-m", "-o", "--exclude-standard")
    selected = [p for p in candidates if eligible(p, tracked)]
    universe = sorted({p for p in tracked | set(candidates) if eligible(p, tracked)})
    return candidates, selected, universe


def capture():
    attempts = []
    for attempt in range(1, 6):
        head_before = git("rev-parse", "HEAD").decode().strip()
        candidates, selected, universe = inventory()
        before = {p: digest((SOURCE / p).read_bytes()) if (SOURCE / p).exists()
                  else None for p in universe}
        for p in selected:
            target = DEST / p
            if before[p] is None:
                if target.exists():
                    target.unlink()
            else:
                data = (SOURCE / p).read_bytes()
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        after = {p: digest((SOURCE / p).read_bytes()) if (SOURCE / p).exists()
                 else None for p in universe}
        copied = {p: digest((DEST / p).read_bytes()) if (DEST / p).exists()
                  else None for p in selected}
        candidates_after, selected_after, universe_after = inventory()
        head_after = git("rev-parse", "HEAD").decode().strip()
        stable = (before == after and selected == selected_after
                  and universe == universe_after and head_before == head_after
                  and copied == {p: before[p] for p in selected})
        attempts.append({"attempt": attempt, "stable": stable,
                         "changed": [p for p in before if before[p] != after[p]]})
        if stable:
            record = {
                "captured_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "source": str(SOURCE), "source_head": head_before,
                "destination": str(DEST), "attempts": attempts,
                "selection_command": "git ls-files -m -o --exclude-standard -- lightning-browser",
                "excluded_candidates": sorted(set(candidates) - set(selected)),
                "selected_files": selected,
                "source_before_sha256": before, "source_after_sha256": after,
                "destination_copied_sha256": copied,
                "source_tree_sha256": identity(before),
                "selected_snapshot_sha256": identity(copied),
                "digest_format": "SHA256(sorted(path + NUL + sha256-or-None + LF), UTF-8)",
            }
            (OUT / "source-snapshot.json").write_text(
                json.dumps(record, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({k: record[k] for k in (
                "source_head", "captured_utc", "source_tree_sha256",
                "selected_snapshot_sha256", "excluded_candidates")}, indent=2))
            print(f"Verified {len(selected)} copied files; {len(universe)} eligible source/config files.")
            return
        time.sleep(1)
    raise RuntimeError(f"No consistent source snapshot after retries: {attempts}")


if __name__ == "__main__":
    capture()
