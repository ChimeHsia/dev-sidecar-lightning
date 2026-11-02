# Native downstream TLS certificate foundation

This is isolated JVM code in `netcore`, based on commit
`754ff423b59c743cf94e68b3417c1fde4a6f42b7`. It is a prerequisite for a
future native MITM engine after the styles merge. No application wiring,
Node replacement, device operation, Android build change, CA installation,
or persistent certificate/key storage is included.

## Stable integration API

All types are in `com.dsha.sidecar.net.proxy.tls`.

```kotlin
val authority = MitmCertificateAuthority.fromPem(
    certificatePem = suppliedCertificatePem,
    privateKeyPem = suppliedPrivateKeyPem,
)
val cache = LeafCertificateCache(authority, maxSize = 256)
val leaf = cache.get("localhost")
val context = leaf.sslContext       // shared, lazily initialized
check(context === leaf.sslContext()) // method form is also supported
val managers = leaf.keyManagers()
val engine = leaf.newServerEngine() // server mode, TLS 1.2/1.3 only
val listener = leaf.newServerSocket() // unbound; caller binds and closes
```

- `fromPem(String, String, Clock = systemUTC(), leafLifetime = 24 hours,
  backdate = 5 minutes)` validates a supplied issuer and retains it in memory.
- `authority.issue(host)` always generates a fresh RSA-2048 leaf key.
  `authority.certificate` and `authority.identity` expose public issuer
  information. Identity is SHA-256 of the complete DER certificate.
- `LeafCertificateCache(authority, maxSize = 256, renewBefore = 1 minute)`
  permanently binds to that authority instance. `get(host)` blocks until
  credentials are ready; `size`, `maxSize`, and `clear()` expose cache controls.
- `LeafCertificate` exposes `host`, `authorityIdentity`, `certificate`,
  `certificateChain` (leaf then root), `keyManagers()`, and the two context
  access forms. Both forms return the same cached `SSLContext`.

Consumers using the raw context must configure server mode and enable only
supported TLS 1.2/1.3 protocols on their sockets/engines. The convenience
server factories do this. The context is shared and must not be reinitialized.
KeyManager arrays are copied on return; their managers contain the leaf
credentials. There is no permissive TrustManager or global default context
change. This context is for downstream service, not upstream trust policy.

The caller owns routing/SNI selection, downstream I/O, timeouts, cancellation,
connection limits, upstream trust and hostname verification, and closing
connections. Fetch credentials through the cache for each new connection.
Cache removal cannot terminate existing TLS connections or credentials already
held by a caller. RSA generation and the synchronous cache API belong on
bounded worker execution, outside a network event loop.

## Issuer acceptance and migration policy

The input must contain exactly one `CERTIFICATE` PEM block and exactly one
unencrypted RSA `RSA PRIVATE KEY` (PKCS#1) or `PRIVATE KEY` (PKCS#8) block.
Extra objects, unrelated text, encrypted keys, and unsupported key types fail.
No method reads a path or generates a production root.

The accepted issuer is a currently valid v3 self-issued, cryptographically
self-signed RSA root with at least 2048 bits, critical CA basicConstraints,
and explicit keyCertSign usage. RSA modulus comparison and a random signed
challenge both establish key correspondence. Root self-signatures are limited
to SHA-256/384/512 with RSA. If an issuer EKU is present, it must allow
serverAuth or anyExtendedKeyUsage. Validity is checked at import, issuance,
and cache access. The exact notAfter instant is treated as expired.

Supported critical issuer extensions are basicConstraints, keyUsage, EKU,
SKI, and AKI. Unknown critical extensions fail. Name constraints, policy
constraints/mappings, and inhibitAnyPolicy are rejected even if noncritical,
because this initial issuer does not implement their enforcement. Root path
length zero is allowed because only end-entity certificates are issued.
Intermediate chains, chain construction, revocation, and constraint-aware
issuance are outside this API.

The actual packaged application CA and private key have not been inspected;
compatibility is unknown. Future integration must surface import failure.
It must not silently weaken these checks, generate a replacement trust anchor,
or assume that the packaged root is acceptable. Migration requires an explicit
design: either provision/rotate to a compliant protected root with a deliberate
trust-installation flow, or implement and review the additional issuer/path/
constraint handling needed for that input. This prerequisite performs neither.

## Leaf identities and certificate profile

Exactly one caller-supplied identity becomes a SAN. DNS is ASCII LDH syntax
with labels of 1-63 characters and a total of at most 253 characters.
Case is folded and a single terminal DNS dot is removed. `localhost` and
already encoded IDN A-labels are accepted. Raw Unicode, wildcards, URLs,
ports, whitespace, control characters, empty labels, underscores, and leading
or trailing label hyphens are rejected. IDNA conversion is a future caller
responsibility; this parser does not validate an A-label's decoded IDNA meaning.

IPv4 requires four decimal octets with no ambiguous leading zeroes. All-numeric
dotted inputs are treated as IP candidates rather than falling back to DNS.
IPv6 is accepted without brackets, scopes, or CIDR suffixes; equivalent
representations canonicalize to the same cache identity. IPv4-mapped IPv6
remains a 16-byte IPv6 SAN. SANs use dNSName for DNS and iPAddress for IP,
without resolving any hostname.

Leaves use SHA256withRSA, independent RSA-2048 keys, nonzero positive random
159-bit serials, critical basicConstraints CA=false, critical digitalSignature
and keyEncipherment usage, and only serverAuth EKU. They contain SKI and AKI.
AKI honors a declared issuer SKI; if absent it derives the issuer key identifier.
The subject is the fixed `CN=Native MITM leaf`; identity matching uses the SAN.
Upstream certificates, CNs, SAN aliases, and serials are never copied.

notBefore is the later of issuer notBefore and the current time minus the
backdate; notAfter is the earlier of issuer notAfter and current time plus
leafLifetime. Times are encoded at whole-second precision, with notAfter
rounded down. No usable remaining interval is an error. The lifetime must be
positive and at most seven days; backdating is limited to zero through five
minutes. Checks after generation prevent time changes from publishing a leaf
which is already unusable.

## Cache behavior

Completed entries use access-order LRU and never exceed maxSize. Distinct
in-flight hosts are independently limited to maxSize; overflow fails fast.
The cache creates no executor. Callers for one canonical host share a future
and one generation, while distinct hosts may generate concurrently outside
the monitor. Generation failures remove that future so later calls can retry.
An interrupted waiter retains its interrupt flag and does not cancel shared
generation.

Every hit checks leaf and issuer time against the authority's injected clock.
A leaf with remaining lifetime at or below renewBefore is regenerated.
A fresh leaf within that window can be served while valid but is not retained;
near issuer expiry repeated requests can therefore regenerate. `size` counts
retained entries, including expired entries not yet accessed, rather than
promising an eagerly swept live count. A clock moving before leaf notBefore
also invalidates that entry.

`clear()` removes completed entries and prevents already-started work from
repopulating the LRU. It does not cancel existing callers. Requests arriving
before that work finishes may still join its future. To change issuers,
construct a new authority and a new cache; no cache can be rebound.
Context lifetime follows the leaf object, including caller-held references.
There is no secure erasure guarantee for JVM private keys or input PEM strings.

## Resources and future Android compatibility

The only build change is
`implementation("org.bouncycastle:bcpkix-jdk18on:1.79")` in netcore.
It brings matching `bcprov-jdk18on` and `bcutil-jdk18on` 1.79 transitively.
No certificate assets, embedded root keys, filesystem resources, Node payload,
native library, or network download is required at runtime.

All BC-backed signing, RSA generation, PEM/key conversion, certificate
conversion, and issuer signature verification use an explicit
`BouncyCastleProvider` instance. The code never calls Security.addProvider,
insertProviderAt, or removeProvider. JSSE and the in-memory PKCS12/KeyManager
implementation use the runtime's installed defaults.

The verified runtime is desktop JDK 21, compiled to the module's JVM 17 target.
This is not evidence of Android runtime compatibility. Future minSdk 24
integration must enable and verify core-library desugaring for `java.time`
(Clock, Instant, Duration, and related calls); they are not uniformly available
as platform APIs at that minimum. No desugaring dependency/configuration is
added here. Android integration also needs a D8/R8 and dependency audit for the
BC 1.79 artifacts, possible platform `org.bouncycastle` class/provider
collisions and isolation/packaging strategy, PKCS12 and KeyManager behavior,
and actual JSSE protocol support. Android's installed BC must not be replaced.
Where TLS 1.3 is unavailable, the server factories allow supported TLS 1.2;
they reject a runtime with neither. Protocol, chain, SNI routing, and trust
installation behavior still need emulator/device acceptance tests after the
Android build integration exists. No hardware or device testing is claimed.

## Reusable tests and standalone verification

`EphemeralTestCa.kt` is an internal helper accessible to sibling proxy tests
within the same netcore test module:

```kotlin
val fixture = EphemeralTestCa.create()
val issuer = fixture.authority()
val certificatePem = fixture.certificatePem
val pkcs8 = fixture.privateKeyPem()
val pkcs1 = fixture.privateKeyPem(pkcs1 = true)
```

It generates all CA/key material in memory and can configure dates, extensions,
and test keys. No production CA, private key, or fixture file is read or written.
`MutableCertificateClock` supports deterministic cache/validity tests.

The three test classes cover import policy, key correspondence, extension
encoding, AKI fallback, DNS/IP identities, serial/key uniqueness, cache bounds,
LRU, concurrent generation, failure eviction, interruption, clear, clock changes,
renewal, and issuer identity. Loopback JSSE tests use a normal
TrustManagerFactory with only the ephemeral root and HTTPS endpoint identity
checking. They verify TLS 1.2 and TLS 1.3 for localhost/IPv4, IPv6 SAN identity
over IPv4 loopback transport, and failures for an unrelated root, wrong DNS/IP
identity, a DNS SAN used as an IP, and an expired leaf.

Verification on 2026-11-02 uses no Gradle, application build, APK, device, or
external network. It invokes the cached Kotlin 2.0.21 K2JVMCompiler with
`-no-stdlib -no-reflect -jvm-target 17 -Xbackend-threads=1`, then cached JUnit
4.13.2 JUnitCore for these classes:

```text
com.dsha.sidecar.net.proxy.tls.MitmCertificateAuthorityTest
com.dsha.sidecar.net.proxy.tls.LeafCertificateCacheTest
com.dsha.sidecar.net.proxy.tls.MitmCertificateAuthorityTlsTest
```

Result: all 43 focused tests passed, including real verified loopback TLS
exchanges and negative trust/identity/expiry cases.

The compiler runs with `-Xmx256m -XX:ActiveProcessorCount=2`; JUnit runs after
it exits with `-Xmx192m -XX:ActiveProcessorCount=2`. Both use the original
checkout's `.jdk/jdk-21.0.2/bin/java.exe`. Dependencies come from
`C:/Users/HONOR/.gradle/caches/modules-2/files-2.1`; compilation output is
ignored `netcore/build/native-certificates-standalone` inside this worktree.
Only the new TLS production/test package is compiled for this focused run.
The compiler classpath includes compiler-embeddable/stdlib/script-runtime
2.0.21, reflect 1.6.10, trove4j 1.0.20200330, annotations 13.0, and coroutines
1.6.4. The source/test runtime classpath includes stdlib 2.0.21, all three
BC 1.79 jars, JUnit 4.13.2, Hamcrest core 1.3, and annotations 13.0.

## Primary references

- RFC 5280 sections 4.1.2.2, 4.1.2.5, 4.2.1.1-3, 4.2.1.6, 4.2.1.9,
  and 4.2.1.12: https://www.rfc-editor.org/rfc/rfc5280
- BC 1.79 extension utility source:
  https://github.com/bcgit/bc-java/blob/r1rv79/pkix/src/main/java/org/bouncycastle/cert/jcajce/JcaX509ExtensionUtils.java
- Android core-library desugaring:
  https://developer.android.com/studio/write/java8-support#library-desugaring
