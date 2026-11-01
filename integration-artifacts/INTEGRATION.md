# Isolated browser style integration

Update: coordinator COMPILE_GO was subsequently completed with payload8.
See `BUILD-ACCEPTANCE-V8.md` and `build-acceptance-v8.json` for the validated APK,
marker-only build commit, current source identities, tests, and signing checks.
The remainder of this file records the earlier source-integration handoff.

Prepared for coordinator review on November 2, 2026 (Asia/Singapore).
Source integration is complete. Terra acceptance, coordinator device validation,
and any Gradle validation remain pending. No merge, push, device access, Gradle
invocation, payload packaging, or Node removal was performed.

## Checkout and commit boundaries

Managed detached worktree:
`C:\Users\HONOR\.codex\worktrees\browser-style-integration\dev-sidecar-lightning`

Base: `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.

Local commits, in order:

| Commit | Purpose |
| --- | --- |
| `c8aa068d510b6714a7b50c2e382b43c3c892df55` | Stable dirty-source snapshot, plus capture script/hash manifest |
| `adc9fc3afefc1a77a6d69483a5258641664f25eb` | Cherry-pick of `128cca9515a3c2f6d420e810ca3e78c3ee9b77e1`, with menu conflict resolved |
| `8726a42599ab44d0ce3d6ec869ab0e5f8d780dbc` | Cherry-pick of `d9231f4c4e5b0bf2705a121815d0d83a0012b35c` |
| `0cbada8d4256ed2c4b321d8c00e7f96ccad4c580` | Cherry-pick of `ba03219be4c83268cdb1b0b9038ef544b5fe627b` |
| `e1d4e4cc88e6842f27d2eca26d305349195f5954` | Minimal missing style dependencies |

The final evidence commit adds documentation and verification artifacts only.
Use `git diff c8aa068 e1d4e4c -- lightning-browser` to review the new integration
delta independently of the original concurrent browser work.

## Upstream identity and stable capture

Original checkout:
`D:\File\User\code\dev-sidecar-lightning`.
Its HEAD was `754ff423b59c743cf94e68b3417c1fde4a6f42b7` throughout capture.

UI origin:
`D:\File\User\code\dev-sidecar-lightning\.claude\worktrees\swift-beaming-shell`,
branch `worktree-swift-beaming-shell`, HEAD
`ba03219be4c83268cdb1b0b9038ef544b5fe627b`.
The three requested commits are consecutive, authored by ChimeHsia.
Their parents, dates, subjects, and local counterparts are in
`commit-origins.json`. The earlier `53085a7` merge, `9628836` documentation
commit, and historical UI commits were not cherry-picked.

Capture used exactly
`git ls-files -m -o --exclude-standard -- lightning-browser`, then allowed only
browser source, resource XML, tests, and tracked Gradle configuration.
It copied 36 files and hashed all 414 eligible source/config files before and
after copying. Destination hashes matched the source; the file inventory and
HEAD also matched. The first attempt was consistent. The manifest records each
full content hash, including source-before, source-after, and copied hashes.

Excluded candidates were `lightning-browser/hs_err_pid16728.log` and
`lightning-browser/replay_pid16728.log`. No `.devtest`, `.gradle`, assets,
private/key material, runtime binaries, payload archives, or local machine
configuration were copied. Deletion of four tracked launcher PNGs belongs to
the requested vector-launcher commit.

Aggregate SHA-256 identities:

| Identity | SHA-256 |
| --- | --- |
| Copied 36-file source snapshot | `97ecd94a3363e9805f1523135580f0e0e56425f2c78dfe54cdb5d69bbe2497a2` |
| Original 414-file eligible source/config tree | `3017a8a1b075d715b882381da4a69f36fac36567140d5d06b59912fca55081ee` |
| Integrated eligible source/config tree | `40a3e99347e9d361462e1b95f7c41824553ab634efeb91109883e7625b2e1d8c` |

The aggregate format is SHA-256 over sorted UTF-8 records:
`path + NUL + raw-content-sha256 + LF`. Individual file hashes use raw bytes;
Git may normalize line endings in stored blobs.

## Exact conflict and dependency decisions

One Git conflict occurred, in `BrowserOverflowMenu.kt`, while applying
`128cca9`. The snapshot lacked an earlier UI-branch icon line on the old
Settings entry. Resolution keeps the requested Exit incognito label, logout
icon, and event; Settings is unconditional. The complete original duplicate-tab
entry, enabled condition, callback, and menu dismissal remain unchanged.
All other original overflow entries and visibility conditions remain unchanged.
The other two requested cherry-picks applied without conflicts.

`BrowserContract.kt`, `BrowserPresenter.kt`, and `MenuSelection.kt` merged
automatically. Their only additions beyond the snapshot are respectively the
`exitIncognito` contract, the six-line exit handler, and `EXIT_INCOGNITO`.
The verifier subtracts these additions and compares the entire remaining
files with the captured snapshot. All 32 other copied files remain byte-for-byte
identical, including startup fixes, tab/COW/lifecycle/color implementations,
build configuration, new resource files, and tests.

Applying only the three requested commits exposed missing dependencies from
earlier UI history. The separate dependency commit adds exactly:

- The `MaterialAlertDialogBuilder` import in `TabWebChromeClient.kt`.
  Its existing geolocation `AlertDialog` behavior remains unchanged.
- Nine referenced vector XMLs: `ic_add`, `ic_arrow_back`, `ic_bookmark_add`,
  `ic_bookmark_remove`, `ic_build`, `ic_close`, `ic_home`, `ic_settings`, `ic_tab`.
  These were copied from committed `ba03219` blobs, with individual SHA-256 and
  Git blob identities in `style-dependencies.json`. Their last source change
  was `ad7b306b3be67ba72c63069f50a39cf157e20cd5`; that historical commit was
  not cherry-picked. No other historical UI code or resources were imported.

There are 44 browser paths in the style/dependency delta and 76 browser paths
in the cumulative delta from the requested base. The exact path lists and
per-path final hashes are in `integration-verification.json`.

The four browser-only commit diffs are exported individually:

| Review file | Browser delta |
| --- | --- |
| `01-adc9fc3.diff` | 9 files, +45/-3: navigator/contract/presenter/menu, logout vector, three exit-label translations |
| `02-8726a42.diff` | 15 files, +247/-387: bookmark and three tab UIs, shared tab components/shape, three new vectors, six removed old vectors |
| `03-0cbada8.diff` | 14 files including four PNG deletions, +186/-52: JS dialogs/binding layout/translations and launcher vectors/adaptive references |
| `04-e1d4e4c.diff` | 10 browser files, +109/-0: exactly nine dependency vectors and one import |

The dependency commit also records `style-dependencies.json`, accounting for
its 11 total Git paths. `review-index.json` records exact commit/path lists,
diff hashes, and tree IDs. `original-browser.diff` is the combined 44-path
delta against the original browser snapshot, reverified to match current
eligible original browser source. Binary PNG deletions are represented by
text-only markers; the local Git commits contain the full deletion semantics.

Git browser subtree IDs:

- Original source snapshot: `f8746fc77dddd422744e17a90c31c714eca35021`.
- Final integrated browser: `1d98cc2844175697b16b453a9a81dffc8f7ee6db`.

## Menu, privacy, and lifecycle review

| Entry | Regular browser | Incognito browser |
| --- | --- | --- |
| New tab | Present | Present |
| Duplicate tab | Enabled when tabs exist | Enabled when tabs exist |
| New incognito tab | Present | Hidden |
| Share / copy link | Full-menu condition | Full-menu condition |
| History / downloads | Present | Hidden |
| Find / bookmarks | Present | Present |
| Add to home / add bookmark | Original full-menu condition | Hidden |
| Settings | Present | Present |
| Exit incognito | Hidden | Present |

The selected upstream action is explicitly destructive to the incognito
session: it deletes the incognito tabs, updates state, starts the regular
browser, runs existing incognito cleanup, and removes the incognito task.
The English label remains `Exit incognito`; both Chinese resources remain
the selected commit's exit label. It is not a session-preserving return.
The manifest's separate regular/incognito processes and task affinities remain
unchanged. The normal and incognito presenters retain separate snapshot pools,
and duplicate-tab histories remain lazy, immutable snapshots as captured.

The captured process-local Activity recreation handoff, background-save owner,
tab disposal, color preferences, and window gamut behavior are unchanged.
`DshaAccel.kt` is preserved with raw SHA-256
`2cd04410eb38a25442943b5655efee11119cc4716e7957f5fe43385f8d6f8280`.
`ProxyStartupGate.kt` is preserved with raw SHA-256
`8f9c149ed0abcc457eda354fa6b00e407555d6d88236768c3d493753eb2cef3c`.
The lifecycle test fixture already contains the captured `AppCoroutineScope`
wiring; its raw SHA-256 is
`7c92d19c97eed2bd0bfb4494bec3a60c6b1bdca6a61c717c9d0dc60d8a48295e`.
Compilation is not claimed.

## Checks, pending validation, and drift

`python integration-artifacts/verify_integration.py` passed:

- Parsed all 81 browser resource XML files.
- Scanned local drawable/mipmap/string/layout/dimen/id references across 336
  source/test Kotlin files; no missing references in the scanned categories.
- Checked changed XML references and source/resource conflict markers.
- Passed `git diff --check`.
- Verified 32 copied files byte-for-byte and all four merged snapshot files
  against their exact permitted changes.
- Rehashed the original eligible browser source twice and rechecked its
  inventory; no drift from the capture was observed.

These are static source checks, not Kotlin compilation, Android resource
linking, unit tests, or runtime acceptance. Existing JavaScript result callbacks,
dialog cancellation/teardown, theme compatibility, vector launcher rendering,
all three tab layouts, and incognito cleanup/return need coordinator validation.
No runtime or payload9 changes are included, and no payload was packaged.
Gradle waits for parent GO after Terra releases the build slot; device tests,
merging, and subsequent Node removal belong to the coordinator.

The drift comparison covers the eligible browser source/config tree; unrelated
root files being worked on concurrently were outside the snapshot. The final
verification JSON records the check time and any later detected browser drift.
No original checkout file, index entry, or branch was edited by this task.

After the coordinator's follow-up, drift was explicitly rechecked:
`source_drift_changed_paths = []`; `source-drift.diff` is empty. In particular,
`BrowserSessionVisibility.kt` and `BrowserSessionVisibilityTest.kt` were already
included in the initial 36-file copy, so no append merge was needed. If the
original changes after this check, refresh the stable source capture and
three-way integrate its delta into this checkout before any merge; do not copy
the older snapshot over the original checkout.

No patch work needs coordinator takeover now. The handoff is to review
`original-browser.diff` and the recorded source/tree identities, then issue
explicit compile GO after Terra releases the build slot. These artifacts
provide no authorization to merge or change payload selection.

Repository `AGENTS.md` was read in the source and managed worktree. Its referenced
`RTK.md` was absent from the repository and checked parent/user locations.
