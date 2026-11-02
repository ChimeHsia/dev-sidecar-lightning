# Android process ownership followup

This change fixes one browser lifecycle gate, without activating `NativeRuntime`
or changing the core implementation.

## Audit

- Lightning is a separate Android Gradle build. Its settings include only the
  browser app; it does not depend on the root `netcore` JVM project.
- `netcore` currently targets Java 17 bytecode and uses BC 1.79 plus a compile-only
  Conscrypt OpenJDK 2.8.0 dependency. This is not Android dependency packaging or
  proof that all supported Android API levels implement its JVM calls.
- `BrowserApp` still starts `DshaAccel` in the main process and attaches the
  incognito process to that proxy. `DshaAccel` still starts the embedded Node
  process. Readiness is gated by the startup generation and proxy callback;
  activity recreation must not start a second process.
- The prior Android 9 API guard treated every Android 7/8 process as the main
  process. Incognito therefore received normal-process dependency injection and
  could start its own core on the shared port.
- `BrowserApp.onTerminate()` is not a production Android shutdown contract.
  No new background service, Android native-runtime owner, cancellation bridge,
  CA storage adapter, native capability selector, or automatic ECH assembly is
  established by this change. Existing runtime configuration capability
  rejections remain in force.

## Implemented gate

`BrowserProcessIdentity` uses `Application.getProcessName()` on API 28+.
On older versions, or when that lookup is unavailable, it selects the
`ActivityManager` process entry matching the current PID. If the manager cannot
identify the process, it reads only the bounded first argument of
`/proc/self/cmdline`. This fallback reads process metadata only.

Only the exact package name and exact `:incognito` name are accepted.
Unrecognized or unavailable identity throws before dependency injection or
core startup, rather than guessing main-process ownership. Incognito identity
also reaches the existing dependency-injection flag on API 24–27. Its WebView
data-directory suffix remains guarded by API 28, where that API is available.

The existing main/attach-only `DshaAccel` calls now use this gate.
No payload preparation, CA persistence, proxy port, app data, or core selection
has been changed.

## Offline verification

`BrowserProcessIdentityTest` covers exact ownership, API 24–27 method gating,
modern lookup/fallback, current-PID selection using Robolectric's real Android
service adapter, bounded NUL-delimited cmdline parsing, and rejection of an
unrecognized Android process. The tests use `TestApplication`, so they cannot
start `DshaAccel` or prepare application payload/data.

The Android compile and test commands run with `--offline`, one Gradle worker,
in-process Kotlin compilation, and bounded build/test heaps. Heavy work must
acquire `Local\DshaNativeRescueBuild` using a `WaitOne(1000)` loop and release it
in `finally`.

On November 2, 2026, `:app:testLightningPlusDebugUnitTest --tests
acr.browser.lightning.BrowserProcessIdentityTest` passed **6 tests, 0 failures,
0 errors, 0 skips**. This invocation compiled the actual browser production and
test sources, using JDK 21.0.2 and cached Gradle 9.8.2. Gradle completed in
2m 27s; the JUnit suite took 8.202s. It acquired the named mutex with the required
`WaitOne(1000)` loop and released it in `finally`.

Robolectric used the locally cached API 36 Android image, with both Gradle and
Robolectric in offline mode. API 24–27 gating was checked with injected version
values; this is not execution on those Android images. The initial command
failed before compilation because Gradle resolved a relative initialization
script against the nested browser build; using its absolute path fixed the
invocation. No production source adjustment was needed for that failure.

Local, ignored evidence:

- `port/.run/android-followup/targeted.log`
- `port/.run/android-followup/test.init.gradle` (bounded heaps and local SDK JAR)
- `port/.run/android-followup/BrowserProcessIdentityTest.xml`

After integration into the main checkout, the full offline
`:app:testLightningPlusDebugUnitTest` suite also passed: 211 tests in 34
classes, with no failures, errors or skips. The run used the same local
Robolectric API 36 image, bounded test heaps, one Gradle worker and the
shared build mutex.

Android 7/8 device behavior, separate real processes, WebView behavior,
ABI/provider packaging, native ECH acceptance, and complete runtime lifecycle
remain outside this host-only verification. No APK was assembled or installed,
no device was used, and no product core switch is claimed.
