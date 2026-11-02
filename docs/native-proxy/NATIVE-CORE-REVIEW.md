# Native core aggregate review

This isolated checkout combines accepted pure candidates and native foundations
for local JVM verification. It does not change the Lightning application,
production Node payload or device configuration.

## Gathered foundations

- Third-wave configuration, address/candidate, HTTP policy and SVCB/ECH metadata.
- Second-wave `DynamicChoice`, as an unchanged prerequisite.
- Native HTTP/1 heads, framing, streaming bodies and buffered handoff.
- Native CA/leaf issuance, bounded certificate cache and downstream TLS contexts.
- Native HPKE/TLS cryptographic primitives and independent known-answer fixtures.
- Fail-closed Conscrypt ECH dialer, including an independent Go TLS peer.
- Real UDP/TCP/DoT/DoH DNS transport and bounded resolver/candidate orchestration.
- Typed HTTPS/SVCB DNS transport and bounded ECH configuration resolution.
- NAT64 coordinator, pinned IPv6 DNS-over-HTTPS transport and an ECH
  discovery adapter, not yet verified against a real gateway.
- Rule selection and typed action plans, with 35 independent original-source cases.
- Bounded request/local-response and response-header/entity action execution,
  including an independent 14-case response-transform oracle.
- Standalone bounded cache, retry and backup scheduling APIs, not yet wired
  into the proxy engine.
- Bounded JSON5-subset parsing, atomic layered snapshots and a restricted
  HTTPS configuration fetcher.
- Standalone bounded HTML script injection and catalog APIs; Brotli injection
  and production browser/runtime wiring remain unsupported.
- HTTP/CONNECT MITM engine, WebSocket handoff, Expect/early-response handling,
  injected TLS dialer and explicit trusted request/response-head hooks.

The gather manifest under ignored `port/.run/native-core-review/` records each
source checkout, relative path and content hash. Duplicate prerequisites are
deduplicated by exact path and hash. No generated certificate or private key
material is gathered.

## Aggregate result

The frozen sources above compile together with cached Kotlin 2.0.21, JDK 21
and BC 1.79. The direct runner completed **840 tests: 838 passed, 2 external
network checks skipped by assumption, zero failures**. Its machine-readable
JUnit report counts assumption skips separately from passing tests.

```powershell
python scripts/test-native-core.py --java-home '<JDK-21>' `
  --conscrypt-jar '<conscrypt-openjdk-uber-2.8.0.jar>' --go-exe '<go executable>'
```

Compilation has a 512 MiB Java heap cap and tests have a 256 MiB cap. The
expanded compiler input saturated the earlier 256 MiB cap. The first complete
runner used the repository root as working directory; three legacy TLS tests
could not find their module-relative fixture. The runner now uses `netcore/`,
matching Gradle's test working directory. The HTTPS/SVCB test fixture loads
from the test classpath so it does not depend on the working directory.
The first configuration-inclusive run exposed a concurrent socket-cleanup
iteration failure; the cleanup loop and a deterministic regression test were
fixed before the successful aggregate run.

This count includes existing reference-proxy tests and pure prerequisites;
it is not a count of fully migrated browsing features. It includes the
Conscrypt independent-peer suite, but not Android device acceptance.

## Pending integration

Production configuration wiring, retry/cache wiring and the released ECH
provider wiring remain separate integration steps. The gathered engine has no retries,
HTTP cache or production configuration entry point yet. ECH configuration
resolution does not perform an ECH handshake. A separate Conscrypt probe passed
accepted, rejected and wrong-certificate cases on Android 16 in debug and R8
builds; those Android results are not part of this JVM aggregate.

JDK tests do not establish Android API/provider compatibility. Actual browser
integration must preserve the supported rule and network capabilities before
Node packaging or startup is removed.
