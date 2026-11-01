# Browser style integration: payload8 build acceptance

Completed November 2, 2026, Asia/Singapore, after explicit coordinator COMPILE_GO.
This supersedes the pending-compilation status in the initial integration report.
The APK has not been installed and no device was accessed.

## Source and marker boundary

Built source commit: `640e93740c75fc0d73e73becc8a3b9a8d1d9c12b`.
This independent build commit changes exactly one source literal:
`DshaAccel.PAYLOAD_VERSION` from `"9"` to `"8"`. It reverses the coordinator's
early marker bump so that the integrated UI carries already accepted payload8.
Wave2/payload9 and Node removal remain outside this build.

Browser Git tree: `5c7cb3193fd6d7fdce0b81905e711a1fb4858b84`.

Eligible browser source/config SHA-256:
`2dc52b4dbc3b9babcee1167d0dbf7788e3794d4be8b79c9fb95e1a1a2eae3b57`.

The verifier restores just that marker in memory and checks the full raw-byte
SHA-256 against the original captured DshaAccel file. It matches, proving that
all startup fixes and line endings remain unchanged. Current DshaAccel SHA-256:
`e3bcd7da8b23f407bbb7d23d68602ad10c9b5688bc218cd652c446855cf38773`.

There were no application compile errors or JVM failures requiring source fixes.
No unrelated tabs functionality changed. Evidence/verifier updates are separate
from the one-literal application commit.

## Actual Gradle results

JDK was explicitly set to
`D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2` in JAVA_HOME and
`org.gradle.java.home`. The wrapper used Gradle 9.8.2.

The commands ran serially:

| Task | Result | Duration |
| --- | --- | --- |
| `testLightningPlusDebugUnitTest` | 136 tests in 31 suites, 0 failures/errors/skips | 2m 51s |
| `assembleLightningPlusRelease` | BUILD SUCCESSFUL | 2m 35s |

Both commands used `--no-daemon --max-workers=1 --no-parallel`,
disabled configuration cache and isolated projects via command-line overrides,
used a 1024 MiB Gradle heap / 512 MiB metaspace, SerialGC, two active processors,
and Kotlin compilation in process. No existing Java or Gradle process was stopped.

The test fork used one process and a 384 MiB heap, reusing the local init script
from
`D:\File\User\code\dev-sidecar-lightning\port\.run\merged-regression-20261101-1328eb6e\low-memory-tests.init.gradle`.
Exact test/release argument arrays, logs, return codes, and all 31 JUnit XML
reports are preserved in the independent evidence directory.

The first test launch failed during configuration, before compilation, because
isolated projects required configuration cache. The retry explicitly disabled
isolated projects; no project configuration was edited. Packaging verification
also required explicit UTF-8 decoding and resolving AAPT-optimized resource
paths through the resource table. Those were verifier fixes, not APK fixes.

## Payload, native binary, CA, and signing

Approved asset origin:
`D:\File\User\code\dev-sidecar-lightning\port\.run\device-v8-20261102\assets`.
All 15 files were copied to the integration checkout's ignored evidence assets
directory with stable source/destination hashes. An init script replaced the
main assets source directory for both Gradle commands with this isolated copy.
Original main assets and wave2/payload9 assets were not used.

Native binary origin:
`D:\File\User\code\dev-sidecar-lightning\.claude\worktrees\swift-beaming-shell\lightning-browser\app\src\main\jniLibs\arm64-v8a\libnodebin.so`.
It was copied only into this checkout's ignored `jniLibs` directory.

Both before and after building, every approved asset and the native binary were
checked byte-for-byte against the accepted
`lightning-startup-tls-v8.apk`, whose SHA-256 is
`ea3726c95d37289493daf3dad86f063d7b576a83a96f3827fda270d8c5c68672`.

| Packaged component | SHA-256 |
| --- | --- |
| `assets/nodejs-project.zip` | `d7a1c8d256272831698a03192997e9e15b1823a688d136f345f51986aacba981` |
| `assets/node-libs.zip` | `757d6d6ef6e94ffb0810a024245fce119ceefa578221b25accb114a44de870ff` |
| `lib/arm64-v8a/libnodebin.so` | `d6f38f48e0d34bd0ddae4c31ebf24dfaa8bf9f6ab6f21cb7c69556dab807c5c0` |
| APK signer certificate | `d8755927514c111bf255341f3f8171d6edc61c89be0ac4f3c45fe7909e36950b` |
| CA certificate DER | `c3143689c78bd8587b5ae9e50e851735a4704f1c3aa51f15c2b3212fa4a04a1f` |

The two inner ZIPs passed CRC checks: nodejs-project has 6,333 entries, node-libs
has 10. The APK ZIP also passed CRC verification.

The packaged CA matches its private key, its self-signature verifies, and its
certificate matches the Android network-security raw resource. AAPT renamed the
packaged raw file to `res/tZ.pem`; this was resolved from `raw/dsha_ca` in the
actual resource table. Its validity is October 31, 2026 through October 28, 2036.
No private key contents are included in tracked evidence.

`apksigner verify --verbose --print-certs` passed with the same Android debug
signer as the accepted v8 APK and local debug keystore. `zipalign -c -P 16 4`
passed. Badging confirms `com.dsha.browser`, version 5.1.0/code 102, arm64-v8a.
DEX inspection finds `.v8.done` and no `.v9.done`.

The only assets beyond the 15 approved input files are generated build/dependency
assets: PublicSuffixDatabase.list, baseline.prof, baseline.profm, and the license
report. No additional payload ZIP is present.

## APK and review evidence

Independent APK:
`C:\Users\HONOR\.codex\worktrees\browser-style-integration\dev-sidecar-lightning\port\.run\browser-style-integration-v8-20261102\browser-style-integration-payload8-640e937-release.apk`

Size: 60,853,222 bytes.

APK SHA-256:
`657a43d27b763189309ff73948fb55fdb4ac9b007eb4f761586985996071a888`.

The sibling ignored evidence directory contains the assets override, logs,
JUnit XML, signing/alignment/badging/resource dumps, complete input hashes, and
`build-verification.json`. A key-free copy of the full result is tracked as
`build-acceptance-v8.json`. The reproducible packaging verifier is
`verify_apk_v8.py`.

`original-browser-v8.diff` is the complete current code/resource delta relative
to the stable original-browser snapshot. `05-640e937.diff` isolates the marker
commit. The previous four style/dependency commit diffs remain unchanged.
These are review artifacts; binary PNG deletions use text markers, with full
deletion semantics preserved in the local commits.

At the final source comparison, source drift paths were `[]`. Original eligible
source/config SHA-256 remained
`3017a8a1b075d715b882381da4a69f36fac36567140d5d06b59912fca55081ee`;
the comparison was recorded at `2026-11-01T18:15:33.612952+00:00`
(November 2, 02:15:33 Asia/Singapore).
The original checkout, index, branch, payload assets, and UI checkout were not
modified by this task.

## Remaining acceptance

JVM and release packaging validation are complete. Combined UI/device acceptance
is still pending: all three tab layouts, JavaScript dialog behavior and
cancellation, launcher rendering, incognito settings/exit/cleanup, proxy/TLS
startup, and foreground/background behavior of this exact combined APK.
Clean-install/upgrade, reboot, long-duration, and load behavior were not tested
here. Terra retains the phone; installation, merging, and device scheduling
remain with the coordinator.
