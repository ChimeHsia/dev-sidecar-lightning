# Conscrypt ECH candidate: frozen JVM handoff

Validated November 2, 2026. Worktree:
`C:\Users\HONOR\.codex\worktrees\native-ech-conscrypt\dev-sidecar-lightning`,
base `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.

**Official Conscrypt 2.8.0 + the explicitly version-controlled policy adapter
negotiates real ECH with an independent Go standard-library server.**
The connector remains unintegrated. No app, engine-worker, phone, ADB, Gradle,
Node, global PATH, global TLS defaults, or global Security provider registration
was changed or invoked. No binary or private key is proposed for commit.

The older `native-ech-hpke` worktree remains frozen. Its accepted `Hpke.kt` SHA-256
is `eb1d42927522932251ebcb91e691e53eb0eaf0ef52b5379d50c1d0154b2af7f0`,
and `NativeTlsCrypto.kt` is
`c04413253efae1f3639fd65370d5d6a179a58959ef5d33297b28f50a797151ec`.
The previously copied `EchConfigList.kt` remains there; unfinished wire work was
not continued.

## Exact public API deficiency and chosen adapter

Source inspection used **tag 2.8.0**, commit
`7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6`, not master.
GitHub's official release metadata identifies 2.8.0 as a non-prerelease,
published October 20, 2026.

- `Conscrypt.setEchConfigList(SSLSocket, byte[])` and its SSLEngine overload are
  public in both official JAR and AAR.
- `EchOptions` is a public class annotated `@Internal`. Both constructors are
  package-private. The two-argument constructor defaults `failClosed` to false.
- There is **no `setEchOptions` setter** in these inspected Java classes.
  Package-private `SSLParametersImpl.setEchConfigList` stores the bytes;
  package-private `getEchOptions(hostname)` derives behavior from policy.
- Default `ConscryptNetworkSecurityPolicy` in both OpenJDK and unbundled Android
  returns `DomainEncryptionMode.UNKNOWN`. That mode gives no ECH options in
  `SSLParametersImpl`; a config setter alone does not turn ECH on.
- `ENABLED`/`OPPORTUNISTIC` policy returns GREASE-enabled, non-fail-closed options.
  `REQUIRED` requires config bytes, disables GREASE, and sets failClosed.
  `NativeSsl.enableEchBasedOnPolicy` then supplies the config to BoringSSL and
  calls `SSL_set_reject_unusable_ech_config`.

That native option specifically guards **unusable configuration fallback**.
An actually attempted ECH handshake that is rejected follows BoringSSL's ECH
rejection path, which Conscrypt converts to an exception; it is not converted
into a successful ordinary TLS connection by this connector. Both forms of
failure are covered below.

`netcore/src/main/java/org/conscrypt/NativeEchPolicyAdapter.java` is our thin,
version-pinned adapter. It deliberately shares `org.conscrypt`, directly binds
package-private `SSLParametersImpl`, and uses the `@Internal`
`NetworkSecurityPolicy`/`DomainEncryptionMode` types. It installs REQUIRED on a
per-context trust-manager wrapper. Before creating a ClientHello it constructs
the actual provider parameters and checks that the policy hook is consumed,
the config is preserved, failClosed is true, and GREASE is false.
Unsupported version, missing linkage, or failed preflight produces an explicit
checked failure; there is no fallback branch.

**This is not a promised Conscrypt public API.** Our adapter performs no private
reflection or accessibility overrides. Conscrypt itself invokes the public
`getNetworkSecurityPolicy()` method reflectively as its existing platform hook.
Package access relies on the adapter and unshaded provider sharing a runtime
package/class loader. Provider upgrades, shading, modules, Android shrinking and
packaging must be revalidated; failure must stay closed.

`netcore/consumer-rules.pro` keeps the adapter and the exact public manager
class/method. The future Android module must include it with
`consumerProguardFiles("consumer-rules.pro")` or include that file from the
packaging app's R8 rules. This repository's netcore is currently a JVM module,
so the rule file is supplied but not wired to an Android plugin here.
The official AAR also currently ships `-keep class org.conscrypt.** { *; }`;
the explicit local rule must survive any later narrowing of that rule.

## Frozen connector API

File: `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/ConscryptEchConnector.kt`.

```kotlin
val configs = TypedEchConfigList.parse(fullLengthPrefixedList)
val connection = ConscryptEchConnector().connect(
    raw = alreadyConnectedSocket,
    echConfigList = configs,
    innerHost = "inner.example",
    verifyHost = "inner.example",
    peerTrust = EchPeerTrust.SystemRoots,
    handshakeTimeoutMillis = 10_000,
    cancellation = EchCancellation()
)
// Only returned after required ECH, TLS 1.3, chain validation and DNS SAN checks.
val verifiedSocket: SSLSocket = connection.socket
val status = connection.echStatus // ACCEPTED_WITH_PINNED_REQUIRED_POLICY
```

| Public type | Contract |
| --- | --- |
| `TypedEchConfigList.parse(ByteArray)` | Exact full ECHConfigList; structurally validated using the unchanged typed prerequisite; requires a selectable FE0D/X25519/HKDF-SHA256/AES-128-GCM config. Copies input and returns detached wire/config lists. |
| `EchPeerTrust.SystemRoots` | Default strict chain validation using an explicit Conscrypt PKIX trust manager and its system-root behavior. |
| `EchPeerTrust.TrustAnchors(List<X509Certificate>)` | Explicit, nonempty caller trust anchors; snapshots certificate DER. Still checks the full chain and verifyHost. |
| `ConscryptEchConnector.connect(...)` | Takes ownership of a connected raw socket; returns `EchConnection` or throws `EchConnectionException`. Creates no raw connection or DNS lookup. |
| `EchConnection` | Owns the verified SSLSocket and explicit acceptance status; Closeable. |
| `EchCancellation.cancel()` | Closes an in-progress raw transport. Cancellation after successful transfer of ownership has no effect. |
| `EchConnectionException.failure` | `REQUIRED_POLICY_UNAVAILABLE`, `PROVIDER_UNAVAILABLE`, `INVALID_REQUEST`, `ECH_REJECTED`, `PEER_AUTHENTICATION`, `HANDSHAKE`, `TIMEOUT`, or `CANCELLED`. |

The status is inferred from successful TLS 1.3 under the verified REQUIRED
policy with a supported config, rather than from a public native acceptance
getter (none is used). Independent-peer `ConnectionState.ECHAccepted` proves
the acceptance inference in this pinned integration.

`innerHost` determines ECH inner SNI; `verifyHost` independently determines DNS
certificate identity. DNS names must be ASCII (punycode if needed); IP literal
verification and trailing-dot normalization are outside this frozen candidate.
SAN-only matching supports conservative single-label DNS wildcards and never
falls back to certificate CN.

Each connect creates a fresh explicit Conscrypt provider/context, pins TLS 1.3,
disables tickets, and offers only `http/1.1` ALPN. No shared session cache or
0-RTT application data is used. Read timeout is cleared on successful return;
the integrating dialer must set the subsequent I/O deadline. A separate daemon
deadline scheduler closes the raw socket to bound a stalled handshake; timeout,
cancellation and success arbitrate through one atomic state.

There is no trust-all or UNVERIFIED conversion. A future `NativeTlsDialer`
adapter must accept SystemRoots or explicit anchors and explicitly fail any
unsupported route trust policy. It must not translate UNVERIFIED into a
permissive ECH trust manager. CHAIN_ONLY is also not exposed by this API.
The separate composite dialer is intentionally deferred to integration.

Rejection closes the raw socket before the TLS wrapper. No application stream
is returned on any failure, and no automatic retry or TLS fallback is attempted.
Retry configs are not exposed or reused: a future authenticated retry workflow
needs its own public-name certificate and policy handling.

## Independent loopback results

**28 passing tests: the frozen 22 connector tests plus 6 direct adapter-policy
tests.** JVM: JDK 21.0.2, Kotlin 2.0.21, JVM 17 target. Java/Kotlin direct
compilation is capped at `-Xmx256m`; JUnit is capped at `-Xmx128m`.
These are heap caps, not total RSS caps. No Gradle daemon was started.

The peer is **Go 1.27.3 `crypto/tls`**, not Conscrypt, BoringSSL, or Node.
`netcore/src/test/fixtures/conscrypt/ech-peer.go` generates independent
ephemeral CA/leaf certificates and ECH keys in process memory, listens only
on numeric `127.0.0.1:0`, reports public config/root bytes, and records
`ECHAccepted`, the server-observed SNI and received application byte count.
Private keys never leave peer memory or enter logs. Its result is not computed
from our connector's status.

| Scenario | Peer ECH result | Received application bytes | Connector result |
| --- | --- | --- | --- |
| Pinned REQUIRED adapter, matching config | true; `inner.example` | 5 (`PING\n`), successful `PONG\n` reply | verified socket |
| Separate valid verifyHost | true; inner SNI unchanged | 5 | verified socket |
| Public setter without adapter | **false**; plaintext `inner.example` SNI | 5 | ordinary TLS negative control |
| Unknown/unusable config with REQUIRED | false; no ClientHello seen | 0 | SSL failure; no fallback |
| ECH key mismatch | false; `cover.example` | 0 | ECH_REJECTED |
| Server without ECH | false; `cover.example` | 0 | ECH_REJECTED |
| Altered ECH payload | false; cover path | 0 | HANDSHAKE failure |
| Altered encrypted server record | peer processed inner ECH | 0 | HANDSHAKE failure |
| Wrong-host, expired, or untrusted certificate | peer may report ECH true | 0 | PEER_AUTHENTICATION |
| Wrong independent verifyHost | true | 0 | PEER_AUTHENTICATION |
| Stall timeout / cancellation | no completed handshake | 0 | TIMEOUT / CANCELLED |

The suite also checks pre-cancellation, cancellation after ownership transfer,
three concurrent independent connections, input isolation, malformed configs,
invalid hosts/timeouts, and unchanged global Security providers, SSLContext and
HttpsURLConnection defaults. The six adapter tests directly validate actual
package API behavior, default-disabled policy, non-fail-closed EchOptions,
REQUIRED preflight, missing config/input rejection, and preserved chain failure.

Sanitized captured evidence:
`docs/native-proxy/conscrypt-loopback-2026-11-02.txt`.
SHA-256:
`d37388400129e85ab04b39d71150275d817b7cfcb6d62c11badb725d0b9ae844`.

## Binary and source provenance

Downloads reside only in ignored `.tools/`. Maven-published `.sha256` files
were checked against the downloaded JAR/AAR in addition to the pinned values:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `org.conscrypt:conscrypt-openjdk-uber:2.8.0` | 6997816 | `4022024808c54211275c7d58ed81e4e13ed8ba170351588cba19f6b947478668` |
| `org.conscrypt:conscrypt-android:2.8.0` AAR | 4985129 | `752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1` |
| AAR `classes.jar` | extracted | `2cd9b6bc444cfca69048e8327383b7f5a13fc7978465e6e0fd5111a2000822f6` |
| `go1.27.3.windows-amd64.zip` | 79010309 | `f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff` |
| Checked-in Go peer source | source only | `a5935bc2af389cbd8f3a18e44aad7f562fdbe3bffc3a3e946deaa7431fa82c18` |
| Frozen connector Kotlin source | source only | `9ae0f0f1367b74ab6499b778e81a7eb17cc0a0f5ad3e60ee81f7602b36bf6147` |
| Frozen Java policy adapter | source only | `732989cca7ad3d4556fbe9733132bc25fb82c4f4ea9d5ec262dac4f48d9a854e` |

Official URLs:

- Release and exact-tag API:
  <https://github.com/google/conscrypt/releases/tag/2.8.0>
  and <https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/Conscrypt.java>.
- Policy, options and native setup:
  <https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/SSLParametersImpl.java>,
  <https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/EchOptions.java>,
  <https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/NativeSsl.java>.
- Both default policies:
  <https://github.com/google/conscrypt/blob/2.8.0/openjdk/src/main/java/org/conscrypt/ConscryptNetworkSecurityPolicy.java>
  and <https://github.com/google/conscrypt/blob/2.8.0/android/src/main/java/org/conscrypt/ConscryptNetworkSecurityPolicy.java>.
- Official Maven binaries:
  <https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-openjdk-uber/2.8.0/conscrypt-openjdk-uber-2.8.0.jar>
  and <https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.8.0/conscrypt-android-2.8.0.aar>.
- Official Go download metadata and archive:
  <https://go.dev/dl/?mode=json>
  and <https://go.dev/dl/go1.27.3.windows-amd64.zip>.
- Go's public peer API: <https://pkg.go.dev/crypto/tls#Config>.
  ECH behavior: <https://www.rfc-editor.org/rfc/rfc9849.html>.

Selected exact-tag source download hashes:

```text
Conscrypt.java
e9fce8c1e32c3ccf34ebb86854b67668ace85c8c637383e97b6fe9b578499512
SSLParametersImpl.java
37b4c72dbb04fb2740616a287b239e74a0bffed913089dc6eb40da5573302231
NativeSsl.java
49b3e021a2f7ae077cb6ade8770493857ffadf079816183ffc3d647d11de83b6
EchOptions.java
9b5c47bab6f4f86dd5387dba09d976629c719dc18c23e931234bf36033d966b3
```

`netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt` was copied
unchanged from `migration-wave3-review`; SHA-256
`8d400cb3067bf9a07e683fd5de573482d821296ef56ac20f8246faabcd5daeb0`.
The sibling native engine's `NativeTlsPolicy` and `NativeTlsConnector` were
read only. `EphemeralTestCa.kt` from `native-certificate-authority` was copied
unchanged only to ignored `.tools/research`, hash
`620c5d24f051797d19614ef23d0916034b297e02aeeed0993a6e573a87393cc3`;
it is not compiled here, since the independent Go peer creates its own CA.

## Reproduction and Android gates

From this worktree:

```powershell
.\netcore\src\test\fixtures\conscrypt\prepare-tools.ps1
.\netcore\src\test\fixtures\conscrypt\direct-check.ps1 `
    -JavaHome 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2'
```

Preparation verifies official downloads, extracts only under `.tools`, and
builds the peer with standard library only, `GOPROXY=off`, `GOTOOLCHAIN=local`,
`-p=1`, `GOMAXPROCS=2`, and `GOMEMLIMIT=192MiB` (Go's soft runtime limit).
It restores its process environment and never installs Go globally.
The direct check compiles Java adapter + Kotlin connector against the JVM
JAR and separately against the extracted Android AAR classes, then runs the
loopback tests with the official Windows native library. Fresh JVM test
execution does not download anything.

Netcore has a compile-only JVM Conscrypt dependency plus a test
dependency. The future Android app must package **conscrypt-android:2.8.0**,
not the OpenJDK uber JAR. No Android runtime dependency was wired here.

The official AAR manifest declares minSdk 21 and contains arm64-v8a,
armeabi-v7a, x86 and x86_64 native libraries. Both repository apps target
minSdk 24 at this base. The connector and package-private adapter compile
against the AAR classes, including its default-policy classes. OpenJDK exposes
`EchConfigMismatchException`; the AAR instead retains internal
`EchRejectedException`. Failure classification handles both names without
private field access.

**Still unverified:** Android native loading, API 24 execution, system-root
and network-security-config behavior, adapter/runtime-package identity after
D8/R8, release minification and keep rules, APK ABI/page-size packaging,
cancellation under Android sockets, and real configured-domain device ECH.
The adapter's explicit PKIX/system-root policy also does not claim app-specific
CT, pinning, custom trust-manager or Android network-security-config parity.
Those policies need deliberate integration rather than silent substitution.

A future parent-owned `NativeTlsDialer` integration must preserve checked
failure, explicit config resolution, innerHost/verifyHost separation and strict
trust semantics. Keep the currently working Android ECH/runtime path until
those Android and full proxy acceptance gates pass. JVM loopback success
does not establish that Node can already be removed from the shipped app.
