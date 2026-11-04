# Composite ECH dialer and Android proof preparation

**Current Android status: BLOCKED.** Terra subsequently ran both historical
version-1 APKs: accepted/reject each failed with HANDSHAKE in about 8-9 ms;
the independent Go peer reported EOF and zero application bytes. This is
failure evidence, not Android acceptance. The diagnostic/provider-service
source patch and the newly built version-2 debug packet are documented in
`ech-android-diagnostics.md`. Gradle has finished; the v2 APK awaits device
scheduling. Earlier version-1 build/host proof details below are historical.

November 2, 2026. Managed worktree:
`C:\Users\HONOR\.codex\worktrees\native-ech-android\dev-sidecar-lightning`.
Exact base: `befef499589d20cb1fd6aa7fe293d009524b7240`.
Only this worktree was written. The frozen HPKE and Conscrypt worktrees, browser,
original main checkout and engine worker were not modified.

## Current evidence and build gate

**48 JVM tests pass:** the unchanged 22 accepted connector tests, 6 accepted
provider-adapter tests, and 20 new composite dialer/proxy tests. Direct Java and
Kotlin compilation used JDK 21.0.2, Kotlin 2.0.21, JVM 17, compiler heap 256 MB;
JUnit heap 128 MB. After explicit BUILD_GO_ANDROID_PROBE, debug and R8 release
builds also passed offline with JDK 21, one worker and a 256 MB Gradle heap.
Both single-use Gradle processes have exited; the build slot was released
for the parent's browser payload10 build.

The new end-to-end proof uses a verified browser-like CONNECT TLS client:

```text
JDK client (validates ephemeral browser.test MITM certificate)
  -> CONNECT browser.test:443
  -> NativeProxyServer + ephemeral MITM CA + HTTP/1 codecs
  -> ConscryptNativeTlsDialer
  -> frozen ConscryptEchConnector + REQUIRED provider adapter
  -> independent Go crypto/tls HTTP origin, ECHAccepted=true
```

The origin receives exactly `GET /native/ech-proof?q=2 HTTP/1.1` and returns
`GO-ECH GET /native/ech-proof?q=2 HTTP/1.1` with CRLF. The verified client
checks the exact status/body. The config resolver receives
`("inner.example", "verify.example", actualOriginTcpPort)`.
This independently exercises different browser authority, inner SNI,
certificate verification name and TCP endpoint.

Origin application bytes are **zero** for rejected/missing ECH, ECH-payload
tampering, encrypted-record tampering, wrong/expired certificate, handshake
timeout and engine-stop cancellation. These upstream failures become 502
(timeout becomes the engine's 504), with no ordinary TLS retry.
Tests also exercise the frozen connector's default system-root rejection.
An origin can report ECH accepted while the client subsequently rejects its
certificate; that is not a successful verified connection.

Captured evidence: `ech-dialer-loopback-2026-11-02.txt`.

**APK status: debug and R8 release built; neither installed nor run.**
Android source/API-only compilation also passed against
the actual SDK `android-37.2/android.jar` and the pinned Android AAR classes,
including the package-private adapter. Subsequent builds passed DEX, manifest
merge, R8 and packaging. Both APKs passed signature/package/permission/ABI
inspection; release DEX/mapping preserve the exact policy hook.
Android execution and minSdk 24 device behavior remain unverified.
No ADB or phone command ran. PHONE_GO waits until candidate10 acceptance.

## New composite API

File: `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/native/ConscryptNativeTlsDialer.kt`.

```kotlin
val dialer: NativeTlsDialer = ConscryptNativeTlsDialer(
    configResolver = NativeEchConfigResolver { key ->
        // key.innerHost, key.verifyHost, key.port; resolved in memory.
        NativeEchSelection.Required(preparsedConfig)
    },
    trustSupplier = NativeEchTrustSupplier { key ->
        EchPeerTrust.SystemRoots // or explicit EchPeerTrust.TrustAnchors(...)
    }
)
```

- `NativeEchRouteKey(innerHost: String?, verifyHost: String, port: Int)` uses
  the connected raw socket's port. Names are those from `NativeTlsPolicy`;
  resolver matching/canonicalization is a caller policy decision.
- `NativeEchSelection.NotRequired` explicitly delegates the original policy
  unchanged to `NativeTlsConnector`, including deliberately scoped STRICT,
  CHAIN_ONLY or UNVERIFIED behavior and its opaque trust context. Tests prove
  CHAIN_ONLY/UNVERIFIED do not invoke the ECH trust supplier.
- `Required(configList)` uses only the frozen REQUIRED ECH connector. It rejects
  CHAIN_ONLY/UNVERIFIED, opaque `SSLContext` trust, a TLS 1.2-only policy, and
  absent inner SNI before any TLS handshake. Trust is SystemRoots by default,
  or explicit anchors from the supplier. It cannot derive anchors from an
  arbitrary caller SSLContext.
- `RequiredButUnavailable` and resolver errors are explicit terminal failures.
  A config lookup failure cannot become `NotRequired`.
- `NativeEchConfigResolver.resolve(key)` and
  `NativeEchTrustSupplier.trustFor(key)` are synchronous in-memory callbacks.
  Resolve DNS, refresh config, and perform any blocking setup before this
  boundary. Engine raw-socket deadlines still bound network operations.
- Failures close the owned raw socket. New policy errors use
  `NativeEchDialException.failure`; connector errors preserve the checked
  `EchConnectionException` classification. There is no catch-and-fallback path.

No engine worker files were edited. The engine's source files were copied once
as a **working-tree snapshot** solely to compile and exercise its actual
`NativeTlsDialer` injection. They were not represented as a new upstream commit.
`ech-android-inputs.sha256` records every borrowed file and source worktree.
The interface-bearing files matched the source again after JVM verification:

```text
NativeTlsConnector.kt (NativeTlsDialer signature and platform implementation)
406b8ae345d8a05bbd5cd4367fa2e0203ee5b6b0f4c393892654096d82f70d54
NativeProxyRoute.kt (NativeTlsPolicy)
6004834ae05ec92605f4c923c3997761fd1afac1e75f2cba2acadaa4f0217a65
```

The Java adapter and accepted connector/config/keep rules were copied unchanged
from `native-ech-conscrypt` and hash-compared again. Keep those APIs and the two
engine interface-bearing files stable while the Android proof is built; changes
need a deliberate resnapshot/retest. No new public Java provider API is needed.
The split-package adapter still intentionally depends on Conscrypt 2.8.0 internals.

## Independent Android test project

Project: `android-ech-probe/`, independent settings and application.
Package **`com.dsha.nativeechtest`**, minSdk 24, arm64-v8a, Java 17.
It never uses `com.dsha.browser` or `top.panming`.
Only INTERNET permission is declared; backup is disabled and the application
is marked test-only. It does not load application CA files, user private keys,
browser data or system trust configuration.

The app stages and compiles only the unchanged typed ECH config, connector and
Java policy adapter alongside its own small UI/runner. It packages only the official
`conscrypt-android:2.8.0` AAR, not the OpenJDK uber JAR, BC, Node, the full
netcore engine, or MITM CA helpers. These selected sources do not use java.time;
Java 17 D8 processing is required, but java.time library desugaring is not
required for this reduced probe.

AGP 9.5.0 and Kotlin 2.4.21 match the browser's cached tool versions;
the app uses AGP built-in Kotlin. The project reuses the existing repository
Gradle wrapper (9.8.2) only when the build gate is granted.
No extra wrapper binary is added.

- Debug build: small nonminified diagnostic APK.
- Release build: R8 enabled, test-only local debug signing.
  `netcore/consumer-rules.pro` is included explicitly, preserving the exact
  provider hook and manager class. The official AAR also carries provider
  keep rules. Both variants must run the same accepted/rejection cases;
  compile success alone does not prove preservation of the reflection hook.
- `EchProbeInstrumentation` is a same-package runner; no AndroidX runner or
  browser process is required.
- `EchProbeActivity` displays the same probe result JSON and logs only the
  sanitized result under `NativeEchProof`. It can also receive the same input
  names through intent string extras.

The probe uses the **public connector plus pinned adapter**, never the unsafe
raw public Conscrypt config-setter path. Its trust is always strict (explicit
ephemeral root anchors or SystemRoots for the untrusted-root negative test).
It connects only to numeric device loopback, supplied by Terra's reverse mapping.
It requests `/native/android-ech-proof?q=24`; successful response matching,
TLS version, provider socket, enforced-ECH status, SDK/ABI and unchanged
global defaults are included in result JSON. Exceptions are classified without
logging messages, configs, certificates, keys or stack traces.

## Reproduction and Terra handoff

Host-only checks (no Gradle, APK, ADB or device):

```powershell
.\netcore\src\test\fixtures\conscrypt\integration-check.ps1
.\android-ech-probe\probe-api-check.ps1
```

`integration-check.ps1` reads the already verified Go executable from the
frozen Conscrypt worktree; it writes the HTTP peer binary and all build/cache
files under this worktree. Override `-GoExe` if needed. Go uses standard library
only, `GOPROXY=off`, `GOTOOLCHAIN=local`, `-p=1`, two runtime processors and a
192 MiB soft runtime limit. The original accepted PING/PONG Go peer and its 28
tests remain unchanged; `ech-http-peer.go` is an explicit HTTP derivative.

Already executed sequentially after parent granted **BUILD_GO_ANDROID_PROBE**:

```powershell
.\android-ech-probe\build-after-go.ps1 -BuildGo -Variant debug
.\android-ech-probe\build-after-go.ps1 -BuildGo -Variant release
```

The gated script uses offline Gradle, one worker, in-process Kotlin, no
parallelism, and a 256 MB Gradle heap. It prints APK SHA-256 only after
successful build. This build budget is separate from the 256/128 MB
direct compiler/JUnit checks. If cached offline resolution or 256 MB is
insufficient, report the exact failure; do not silently expand memory/network.

Built APKs:
`android-ech-probe/app/build/outputs/apk/debug/app-debug.apk` and
`android-ech-probe/app/build/outputs/apk/release/app-release.apk`.
Both exist; verified hashes/signatures are in `android-ech-probe/HANDOFF.md`.
Installation belongs to Terra after separate approval. Run
`android-ech-probe/terra-install-guard.ps1` first.
Its corrected checks are scoped to explicit current user 0 and require exact
global package-not-found confirmation. A native error, SecurityException in
either stream, stderr, unexpected/empty global output or globally retained
package blocks installation. Android multi-user shell restrictions must not be
interpreted as absence. The guard only reads; Terra's separate copy is untouched.
The app is test-only, so an approved fresh installation needs
`adb install --user 0 -t`. Do not overwrite
any existing `com.dsha.nativeechtest` installation without inspecting/approving
that situation. No uninstall is authorized by this handoff.

For each independently fresh peer session:

```powershell
.\android-ech-probe\start-peer-for-terra.ps1 -Scenario accepted
```

This starts only the host Go peer and prints commands. It does **not** run ADB.
Terra substitutes the explicitly selected device serial and executes the
generated `adb reverse` and `am instrument` commands. Public config/root
bytes travel as instrumentation or Activity extras.
The small live fixture is `.tools/device-sessions/<id>/public-fixture.json`;
its schema is `android-ech-probe/PUBLIC-FIXTURE.schema.json`.
Generate a fresh session immediately before testing. The peer waits up to 120 seconds
for setup and then bounds the connection to eight seconds.
The session's `.tools/device-sessions/<id>/peer.jsonl` contains the independent
ready and result events, and `terra-commands.txt` retains the commands.
Remove only the reverse mapping created for this session.

Run scenarios `accepted`, `reject`, `noech`, `wrong-host`, `expired`, `untrusted`,
`tamper`, `record-tamper`, `stall`, and `cancel`.
For every case require both:

1. Device `result_json`: `pass=true`, `globalDefaultsUnchanged=true`, expected
   acceptance/failure classification, and the actual SDK/ABI/provider version.
2. Host Go result: accepted case has `ech_accepted=true`, inner SNI
   `inner.example`, and exact
   `GET /native/android-ech-proof?q=24 HTTP/1.1`; all negative cases have
   `application_bytes=0`. A peer may process inner ECH before a certificate or
   record authentication error; do not mistake that for a verified client.

`am instrument` result codes alone are insufficient: inspect the JSON.
For the Activity alternative, substitute `--es` for the generated `-e` extras
and use `am start -n com.dsha.nativeechtest/.EchProbeActivity`; result is visible
in the Activity and the `NativeEchProof` log tag.
Do not log or collect any real user CA/private material.

## Frozen inputs, hashes and remaining gates

Official binaries were reused read-only from the accepted worktree into ignored
local `.tools` and retain the accepted SHA-256 values:

| Input | SHA-256 |
| --- | --- |
| OpenJDK uber 2.8.0 JAR (host only) | `4022024808c54211275c7d58ed81e4e13ed8ba170351588cba19f6b947478668` |
| Android 2.8.0 AAR (APK runtime) | `752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1` |
| AAR classes.jar (compile-only) | `2cd9b6bc444cfca69048e8327383b7f5a13fc7978465e6e0fd5111a2000822f6` |
| Unchanged Java adapter | `732989cca7ad3d4556fbe9733132bc25fb82c4f4ea9d5ec262dac4f48d9a854e` |
| Unchanged connector | `9ae0f0f1367b74ab6499b778e81a7eb17cc0a0f5ad3e60ee81f7602b36bf6147` |
| New composite dialer | `aaf620f5d00e544bf42247d9260684dcc65e6edf0c00cccd0f5e73d0667f0c65` |
| HTTP Go peer derivative, with clean close_notify | `3b20dd4b295b49e4728c8f635249b2d6818f3f5fbc51c093e22d786492908081` |

Maven URLs, exact tag 2.8.0 provenance, checksum verification and the official
Go ZIP hash remain documented in the accepted
`native-ech-conscrypt/docs/native-proxy/conscrypt-ech.md`; no binary is committed.

Remaining gates: explicit engine interface snapshot/freeze coordination,
PHONE_GO, debug native loading and independent peer evidence on Android,
minSdk 24 coverage (a newer device is not API 24 proof), release R8 device run,
native page-size runtime compatibility, Android system-root
and app network-security-policy compatibility, parent resolver/trust policy
integration, and full configured-domain browser/proxy acceptance.
Do not remove the working Node/ECH Android path on the strength of JVM or
source/API-only checks.
