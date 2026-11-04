# Android blocker and next diagnostic packet

November 2, 2026. This worker does not own the device and has not invoked ADB.
Terra reported both version-1 APKs (debug and R8 release) failing accepted and
reject cases in about 8-9 ms, category HANDSHAKE. The independent Go peer saw
EOF and zero application bytes. **Android ECH acceptance is not established.**
Those APKs are historical failure evidence.

After the parent released the browser build slot and granted a fresh BUILD_GO,
the version-2 debug APK built successfully with JDK 21, one worker and a
256 MB Gradle heap. Gradle has exited and the slot is released again.
No ADB, installation or version-2 device execution occurred.
Android remains blocked pending the parent's device schedule.

Artifact:
`android-ech-probe/artifacts/native-ech-probe-v2-debug.apk` (4051807 bytes).
SHA-256:
`d32ff875e357f1076a113cc5106d8a6baeb9fef98f22ecca3def2d9bdc462da0`.
Actual package/version: `com.dsha.nativeechtest`, versionCode 2,
versionName `0.2-diagnostic`, minSdk 24, arm64-v8a, INTERNET only.
APK v2 signature passed; signer certificate SHA-256:
`d8755927514c111bf255341f3f8171d6edc61c89be0ac4f3c45fe7909e36950b`.
Actual DEX contains schema-2 diagnostic markers, named stages, cause metadata,
and the retained REQUIRED hook; there is no JVM native DLL/JAR payload.

## Exact-tag source and binary finding

The inspected upstream sources are pinned to official Conscrypt tag `2.8.0`,
commit `7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6`, not master:

- `android/src/main/java/org/conscrypt/Platform.java`, SHA-256
  `f80a67a2185d9cc7c815675d0041a9d194dc2bacac9e138244bb176aba3801ba`.
  Its `provideTrustManagerByDefault()` returns false. Direct javap inspection
  of the actual pinned Android AAR `classes.jar` also shows `iconst_0; ireturn`.
- `common/src/main/java/org/conscrypt/Conscrypt.java` initializes
  ProviderBuilder.provideTrustManager from that platform default.
  The public `provideTrustManager(boolean)` builder method is present in the
  exact 2.8.0 AAR.
- `common/src/main/java/org/conscrypt/OpenSSLProvider.java`, SHA-256
  `25616070757470750b501b2ed649884745903fe1d9c77ca586f01062ad0f771f`.
  It registers `TrustManagerFactory.PKIX` only when includeTrustManager is true.
- Android `Platform.checkServerTrusted` first tries a socket/engine-aware
  method, then a hostname-aware method, and falls back to the ordinary two-arg
  method when absent. It does not require our wrapper to implement three args
  or construct X509TrustManagerExtensions in that path.
- `ConscryptEngineSocket.getDelegatingTrustManager` returns our non-extended
  wrapper unchanged. No trust-manager signature workaround is applied here.

The prior connector requested `TrustManagerFactory.getInstance("PKIX",
Conscrypt.newProvider())`. On Android the explicitly selected provider omits
that service by default. This identifies a real cross-platform service-selection
bug and a source-backed explanation consistent with immediate pre-ClientHello
failure. **It is not yet proof that this was the device's only failure.**
The next packet's stage/cause data and independent Go result must confirm.

The local connector copy now explicitly builds a provider with
`Conscrypt.newProviderBuilder().provideTrustManager(true).build()` for the
PKIX factory. That provider is passed directly; nothing is globally registered.
TrustAnchors and SystemRoots retain real Conscrypt PKIX chain validation and
independent verifyHost SAN checks. No trust-all, ordinary TLS fallback, deleted
REQUIRED policy, native patch, or bypassed certificate verification was added.

The accepted original `native-ech-conscrypt` worktree remains frozen. The local
copy in `native-ech-android` is deliberately changed for this documented bug
and diagnostics, with regression tests. The Java REQUIRED adapter and
consumer-rules remain unchanged.

## Next packet API and safe result schema

Next app versionCode is 2, versionName `0.2-diagnostic`.
Log tag remains `NativeEchProof`; JSON identifies
`schema=2`, `probeRevision=diag2-explicit-tmf`.

Connector changes are additive:

```kotlin
enum class EchStage { /* named, static stages */ }
class EchConnectionException(
    val failure: EchFailure,
    cause: Throwable? = null,
    val stage: EchStage = EchStage.UNKNOWN
) : IOException(...)
```

`connect` arguments, returned connection and failure enum remain unchanged.
The connector records the phase that failed:
`INPUT_VALIDATION`, `CANCELLATION_REGISTRATION`, `TRUST_MANAGER_CREATION`,
`REQUIRED_POLICY_PREFLIGHT`, `CONTEXT_CREATION`, `SOCKET_FACTORY`,
`ENGINE_SOCKET_POLICY`, `LAYERED_SOCKET_CREATION`, `SOCKET_CONFIGURATION`,
`ECH_CONFIG_INSTALLATION`, `HANDSHAKE_START`, `PEER_VERIFICATION`, or
`CONNECTION_HANDOFF`.
Thus generic HANDSHAKE no longer conceals whether a ClientHello was even started.

New `EchDiagnostics.capture(failure, fallbackStage)` returns only:

- A static stage name.
- `causeClasses`: at most eight `{simpleName, fullName}` entries.
- `causeChainTruncated`, including cycle detection.

It never reads throwable messages, toString, stack traces, suppressed
exceptions, certificate bytes, ECHConfig bytes, account URLs or private keys.
R8 rules keep the outer diagnostic exception identifiable; provider classes
already have pinned AAR keep rules.

Probe metadata adds `nativeAvailable`, Conscrypt version, `configValidated`,
config byte count and static trust mode. Its outer stages identify public-root
parsing, native availability, raw loopback connect, connector invocation and
application I/O. No application write happens before a verified connection.
The app still connects only to numeric local loopback and accepts public test
config/root inputs through Intent or runner extras.

Example next-packet failure shape (class names are examples, not device evidence):

```json
{
  "schema": 2,
  "probeRevision": "diag2-explicit-tmf",
  "failure": "HANDSHAKE",
  "stage": "TRUST_MANAGER_CREATION",
  "nativeAvailable": true,
  "causeClasses": [
    {"simpleName": "EchConnectionException", "fullName": "com.dsha.sidecar.net.proxy.ech.EchConnectionException"},
    {"simpleName": "NoSuchAlgorithmException", "fullName": "java.security.NoSuchAlgorithmException"}
  ],
  "causeChainTruncated": false
}
```

## Source-only verification

**56 JVM tests pass**: previous 48 plus eight new diagnostics/provider tests.
The new tests reproduce missing PKIX with an explicit no-TM provider, verify a
real PKIX TM with the builder option, check unchanged global providers, capture
an actual connector validation stage, bound chains to eight, terminate cyclic
causes, and prove diagnostic capture does not access messages/toString/stack.
All strict certificate rejection, REQUIRED fail-closed and independent Go
proxy-chain tests still pass.

`probe-api-check.ps1` compiles the diagnostic app and connector directly against
the actual Android SDK/AAR API. Compiler heap remains 256 MB, JUnit heap 128 MB.
The subsequent debug APK build and inspection passed as recorded above.
There has been **no diagnostic device run**. Do not reuse the old debug/release
hashes as the version-2 packet. No version-2 R8 build was requested or run.

## Corrected Android multi-user guard

`android-ech-probe/terra-install-guard.ps1` now:

1. Captures actual per-process native exit code and separate stdout/stderr,
   without relying on stale PowerShell `$LASTEXITCODE`.
2. Requires exact `device` state and current user matching `-UserId` (default 0).
3. Uses `pm list packages --user 0 -u com.dsha.nativeechtest` at default scope.
   Any error, SecurityException in either stream, stderr or unexpected nonempty
   output stops the guard.
4. Requires exact global
   `Unable to find package: com.dsha.nativeechtest` from a successful
   `dumpsys package com.dsha.nativeechtest`. Empty/denied output and any existing
   global package identity, including user-100 installed or retained data, stop.
5. Returns success only after all four checks. It performs no installation,
   overwrite, start, stop or uninstall.

**20 offline native-process regression cases pass**, including exit=0 plus
SecurityException, nonzero exit despite plausible stdout, stale LASTEXITCODE,
wrong current user, global ambiguity, and packages installed/retained for
user 100. No actual ADB or device was used in these tests.

Guard SHA-256:
`4a3e69219b9859c7ed7f0a4b28711dcd6e1b109b42b1f210fb4932f969a119ce`.
Terra's separate helper copy was not edited.

Android shell permissions can prohibit cross-user enumeration. A denied
query is **unknown**, never absence. Fresh installation requires the parent's
separate approval and explicit `install --user 0 -t`. An already-installed
known probe, including the version-1 probes Terra just tested, must not be
treated as globally absent or overwritten based on this guard.
