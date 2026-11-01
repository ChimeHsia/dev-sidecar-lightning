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
- Real UDP/TCP/DoT/DoH DNS transport and bounded resolver/candidate orchestration.
- Rule selection and typed action plans, with 35 independent original-source cases.
- Bounded request/local-response and response-header/entity action execution,
  including an independent 14-case response-transform oracle.
- HTTP/CONNECT MITM engine, WebSocket handoff, Expect/early-response handling,
  injected TLS dialer and explicit trusted request/response-head hooks.

The gather manifest under ignored `port/.run/native-core-review/` records each
source checkout, relative path and content hash. Duplicate prerequisites are
deduplicated by exact path and hash. No generated certificate or private key
material is gathered.

## Aggregate result

The frozen sources above compile together with cached Kotlin 2.0.21, JDK 21
and BC 1.79. The direct runner completed **483 tests: 481 passed, 2 external
network checks skipped by assumption, zero failures**. Its machine-readable
JUnit report counts assumption skips separately from passing tests.

```powershell
python scripts/test-native-core.py --java-home '<JDK-21>'
```

Compilation and tests each have a 256 MiB Java heap cap. The first complete
runner used the repository root as working directory; three legacy TLS tests
could not find their module-relative fixture. The runner now uses `netcore/`,
matching Gradle's test working directory, and the final aggregate passed.

This count includes existing reference-proxy tests and pure prerequisites;
it is not a count of fully migrated browsing features. It does not include
the separate Conscrypt independent-peer suite or Android device acceptance.

## Pending integration

HTTPS-record/ECH resolution, live configuration loading, retry/cache execution and
the released ECH provider are still being reviewed separately. Their later
snapshots require new aggregate checks. The gathered engine has no retries,
HTTP cache or production configuration entry point yet.

JDK tests do not establish Android API/provider compatibility. Actual browser
integration must preserve the supported rule and network capabilities before
Node packaging or startup is removed.
