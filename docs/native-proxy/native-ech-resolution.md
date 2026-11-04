# Native HTTPS/SVCB and ECH configuration resolution

This is an isolated extension in:

```text
C:\Users\HONOR\.codex\worktrees\native-dns-orchestration\dev-sidecar-lightning
```

It completes an actual DNS-to-ECH-configuration data path through the local
transport foundation. It does not perform an ECH handshake, encrypt a
ClientHello, prove ECH acceptance, synthesize NAT64, or apply changes to the
application. No Conscrypt dependency is introduced.

## New files and safe extension

All new production files use package `com.dsha.sidecar.net.dns.native`:

| File under `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/native/` | Purpose |
| --- | --- |
| `HttpsRecordTransport.kt` | Separate HTTPS/SVCB query, service record, answer, and transport API. |
| `NativeHttpsWire.kt` | Strict typed service-record transaction validation using unchanged `SvcbCodec`. |
| `NativeEchModels.kt` | Raw-option conversion, bounded limits, detached ECH result, explicit failures. |
| `NativeEchResolver.kt` | Provider selection, hedging, AliasMode/shared fallback, caching, cancellation, lifecycle. |

`SocketDnsTransport.kt` is the only accepted implementation extended in place.
It now implements both `DnsTransport` and `HttpsRecordTransport`. Its private
work/future lifecycle is generic so A/AAAA and HTTPS/SVCB share exactly the same
bounded pool, deadlines, resource ownership, bootstrap, UDP/TCP/TLS/HTTP I/O,
and cancellation. Existing `query(NativeDnsQuery)` and public models/options
retain their signatures and behavior. A private bootstrap helper is shared.
The new `queryHttps(HttpsRecordQuery)` has a separate type-aware wire path;
ordinary A/AAAA is never rerouted as a fake service query.

`NativeDnsWire.kt`, `NativeDnsModels.kt`, `NativeDnsResolver.kt`, accepted tests,
HTTP framing, SVCB sources, and earlier reports/runners remain unchanged.
The prior `dns-transport.md` describes the accepted earlier stage; this report
supersedes its statement that service-record lookups are unsupported.

New tests under the corresponding test package:

- `NativeHttpsWireTest.kt`: 9 typed wire tests.
- `SocketHttpsTransportTest.kt`: 7 socket transport tests.
- `NativeEchResolverTest.kt`: 38 resolver tests.
- `NativeEchTestData.kt`: loads unchanged upstream-generated ECH fixture bytes
  and builds independent DNS RDATA fixtures.

The new runner is `docs/native-proxy/run-native-ech-tests.ps1`.

## Typed service-record transport

```kotlin
interface HttpsRecordTransport : AutoCloseable {
    fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer>
}
```

`HttpsRecordQuery` carries the exact provider descriptor, hostname, and
`HttpsRecordType.HTTPS` (65, default) or `.SVCB` (64). Both types work over UDP,
TCP, DoT, HTTP DoH, and HTTPS DoH at the same supported provider endpoints.
JSON/legacy DoH, unsupported URLs, NAT64, and unknown transport types retain
explicit exceptional failures. Provider bootstrap and strict TLS identity
behavior are unchanged.

`HttpsRecordAnswer` contains detached `HttpsServiceRecord` objects, DNS answer
code, negative TTL in milliseconds, and CNAME dependency names.
Each service record exposes owner, millisecond TTL, and a defensive copy of
the accepted `SvcbRecord`/parameters, preserving the complete raw ech value.
Canonical names permit invalidation of dependent original ECH lookups.

`NativeHttpsWire.question` creates actual type-64/65 IN questions with random
IDs supplied by the socket transport. Its parser verifies ID, QR/opcode,
one exact question name/type/class, count/section/byte limits, compression,
RCODE/EDNS errors, increasing unique service parameter keys, CNAME chains,
and complete RDATA with the unchanged structural codec. Only matching
IN answer-section records contribute service results. Additional or unrelated
service records cannot supply ECH. A matched UDP TC reply triggers TCP;
truncation over a stream/HTTP is a failure.

DNS CNAME follow-up uses the same endpoint and shared deadline, carries the
smallest alias TTL across replies, rejects cycles, and returns dependency
names. Authority SOA negative TTLs remain bounded by canonical-name alias TTL.
The existing `maxRecords`, `maxAddresses` (also service-result count),
`maxCnameHops`, message/body size, and total query deadline bounds apply.

## ECH resolver API

```kotlin
val resolver = NativeEchResolver(
    catalog = configuredCatalog,
    transport = socketDnsTransport,
    clock = monotonicClock,
    options = NativeEchOptions.fromRaw(decodedEchObject),
    mapping = hostnameDnsMappings,
    ownsTransport = false, // when address resolution shares this transport
)
val pending: CompletableFuture<NativeEchResult?> = resolver.resolve("example.test")
resolver.invalidate(hostname = "example.test")
resolver.invalidate(providerKey = "provider:configured-name")
resolver.close()
```

The resolver owns the transport by default. Set `ownsTransport=false` when
sharing a `SocketDnsTransport` with the address resolver. Closing this ECH
resolver cancels only its own futures in that case. `close` is idempotent;
`awaitTermination(timeoutMillis)` optionally joins its hedge/deadline scheduler.
`state()` exposes bounded entry/flight/waiter counts and lifecycle state.

`NativeEchResult` exposes:

- Complete original length-prefixed `echConfigList` bytes, all parsed `configs`,
  and the selected `EchConfig`; all byte/list access is detached.
- Selected config `publicName`, provider key/name/transport, original request
  hostname, final queried name, service priority/target, and whether fallback
  used the configured shared name.
- Effective cache `ttlMillis` and absolute `expiresAtMillis` in the supplied
  nonnegative monotonic clock epoch.

The configuration is selected using unchanged
`EchConfigList.selectEchConfig`: FE0D, X25519, HKDF-SHA256/AES-128-GCM, no unknown
mandatory ECH extensions, and exact structural vectors. This is a metadata
profile, not cryptographic key validation. Full lists can retain additional
unsupported/unknown configurations; the returned byte array is never reduced
to the selected contents-only config.

At a later connector boundary the intended conversion is:

```kotlin
// In the connector module, which owns its TypedEchConfigList dependency:
val typed = TypedEchConfigList.parse(result.echConfigList)
```

This module uses only raw full bytes plus the accepted `EchConfigList` types.
It neither copies the connector's class nor depends on Conscrypt. A null
result or failure must be passed to `NativeTlsDialer` policy; neither outcome
asserts that required ECH succeeded. Even a nonnull result proves only that
metadata was obtained, never that a server accepted ECH.

## Raw settings and upstream defaults

`NativeEchOptions.fromRaw` accepts the decoded raw `ech` object:

| Knob | Default and behavior |
| --- | --- |
| `enabled`, `use` | Both true; either false disables this resolver without queries. |
| `dns` | No invented provider name. A supplied nonempty name must exist; it restricts lookup to that provider. An unknown name fails construction. Pass the actual upstream configuration's `dns` value when using that config. |
| `tryAllProviders` | True. Without dedicated DNS, first mapped provider, then `ForSNI`, then remaining catalog bindings, deduplicated by provider key. |
| `publicName` | `cloudflare-ech.com`, matching the inspected upstream configuration. This is a fallback query name, not a new DNS provider. Explicit empty string/null options disable shared fallback. No test queries that public name. |
| `emptyTtl` | 600,000 ms for clean no-ECH results. |
| `minTtl`, `maxTtl` | 60,000 / 3,600,000 ms positive cache clamp. |
| `parallelDelay` | 200 ms hedge delay; mapped/first provider starts immediately, others start after that shared delay. Zero starts all immediately. |

The typed constructor permits zero empty/min TTL for explicit no-retention
tests/policy. Raw conversion follows upstream positive-TTL defaults: raw zero
empty/min/max TTL uses its default. It accepts whole numeric/string TTLs only,
rejects negative/non-finite/fractional values, invalid names, inconsistent
min/max, and options outside bounded ranges. It does not apply JavaScript
`parseInt` prefix truncation. Raw `publicName:null` behaves as omitted; use
an empty string or typed `publicName=null` to disable fallback.

Only descriptors with supported real transports and ECH enabled are candidates.
Disabled/preset/NAT64 descriptors are skipped. No provider or external address
is added to the supplied catalog, and the ECH resolver does not perform bootstrap
or public-data fetching itself. Only the injected transport can perform queries.

`use=false` gating is deliberate native data-path behavior; upstream separates
some use-policy decisions into its proxy caller. No domain ECH applicability
matcher is invented here: the route/planner decides when to call resolve.

## Record, provider, and fallback selection

Without dedicated DNS, providers hedge concurrently with mapped-first order.
The first usable result wins; all outstanding losing provider futures and
scheduled hedges are canceled. Provider errors do not prevent another provider
from winning. A provider that reports no data cannot finish the whole operation
while another provider is pending.

HTTPS AliasMode is followed on the same provider for at most three transitions
by default. Cycles, ambiguous aliases, AliasMode with parameters/service peers,
and limit violations fail. Root-target AliasMode yields clean absence.
ServiceMode records sort by ascending priority. Records with unknown/missing
mandatory SvcParam requirements are skipped. Malformed ECH values do not hide
a later usable service record; otherwise malformed or unsupported lists are
explicit configuration failures.

After all candidates return clean no-ECH, the configured shared name is tried
with its own mapped/provider policy. A shared name equal to the original
hostname is not queried again. Shared fallback occurs only after clean absence:
unresolved wire, transport, AliasMode, or configuration errors fail the lookup
and are never concealed by a shared/public fallback.

The lookup-wide deadline is 8,000 ms by default and includes hedge delay,
all providers, HTTPS aliases, and shared fallback, even for a nonresponding
injected transport. Actual sockets also retain their own per-query deadlines.

## Cache, invalidation, and bounds

One resolver instance is an immutable catalog/options/mapping epoch.
The single-flight/cache key is normalized original hostname in this epoch.
There is no destination-port key: ECH DNS configuration data is independent
of the connection port; this cache never contains per-port IP health feedback.
Address resolver port isolation remains untouched.

Positive lifetime is the selected record/CNAME/AliasMode minimum TTL, clamped
between configured min/max exactly as an explicit ECH cache policy. This
minimum can intentionally extend short/zero DNS TTLs, matching upstream ECH
cache settings rather than the ordinary address cache's strict TTL semantics.
Shared result retention is additionally capped by `emptyTtl`, so the original
domain is checked again within its clean-absence window.

Clean missing HTTPS/ECH, NXDOMAIN, NODATA, and unsupported mandatory service
requirements yield null and use `emptyTtl`, distinct from DNS's SOA negative
TTL. This is ECH absence policy modeled on upstream; no successful ECH config
is synthesized. Empty TTL zero yields no negative retention. Wire/config/
transport errors complete exceptionally and are never negative-cached.

Expiry is exact at `now >= expiresAt`, lazily swept, with saturating addition.
No stale entry, negative or positive, is served after expiry/invalidation.
Invalidation supports an original hostname, any observed CNAME/HTTPS AliasMode
dependency, a shared query name, a provider key, or all entries. Matching pending
flights are failed and their queries/hedges canceled. Identity checks reject
late uncooperative results, including ones racing a replacement flight.

Defaults: 512 LRU entries, 16 flights, 64 waiters per flight, 16 candidate
providers per stage, 32 service records per answer, 65,535 config bytes, and
three HTTPS AliasMode hops. All limit knobs have finite validated upper bounds.
Provider count overflow fails instead of silently dropping catalog providers.
There is one owned scheduler with remove-on-cancel timers, no per-host locks,
unbounded pending/error maps, or background refresh loop.

Canceling one waiter preserves joined callers. Canceling the final waiter
cancels all active and delayed work. Invalidating/closing releases callers
without waiting for external transport termination. The nonblocking transport
contract remains required; use asynchronous CompletableFuture continuations
for consumer work that may block.

## Verification

```powershell
.\docs\native-proxy\run-native-ech-tests.ps1
```

Verified 2026-11-02: **187 tests pass**: 54 new tests (9 typed wire, 7 actual
typed transport, 38 ECH resolver) plus the unchanged accepted 133 tests.
This is the local suite; the parent's separate aggregate count was not rerun
here.

Tests use localhost UDP/TCP/TLS/HTTP servers, numeric loopback bootstrap/pins,
an unchanged upstream-generated full ECHConfigList fixture, deterministic fake
clocks/futures, latches and bounded thread pools/joins, and no sleeps.
Real typed queries cover UDP, TCP, DoT, HTTP DoH, HTTPS DoH, UDP TC/TCP retry,
CNAME lifetimes, cancellation, and deadlines. A real localhost UDP DNS response
passes through both the socket transport and ECH resolver.

Resolver tests cover dedicated/mapped/all-provider selection, hedging and loser
cancellation, full multi-config fidelity, unsupported/malformed configs, clean
absence vs errors, AliasMode cycles/depth/TTL, shared fallback/failure,
positive/negative expiry, dependency invalidation, late answers, LRU/concurrency
limits, borrowed/owned lifecycle, and scheduler shutdown.

The runner uses the original JDK 21, cached Kotlin 2.0.21/JUnit jars, 256 MB
compiler heap, and 192 MB test heap. Cached BC 1.79 is test-only for the accepted
in-memory TLS certificate fixture. Output/tmp stay inside ignored worktree
`netcore/build/native-ech-*`. The only compiler warning is the unchanged
`DnsClient.kt` deprecated URL constructor.

No external DNS/URLs, Node process, phone, Gradle/Android build, application
runtime, payload, public service call, NAT64 implementation, TLS crypto, or
Conscrypt dependency was used or added. Android runtime validation and actual
ECH acceptance remain integration work.

## Provenance and frozen hashes

Read-only upstream root:

```text
D:\File\User\code\dev-sidecar-lightning\port\.run\device-wave2-20261102\runtime\core\node_modules
```

| Read-only source relative to that root | SHA-256 |
| --- | --- |
| `@blue-frontier/mitmproxy/src/lib/dns/index.js` | `1B869ADD75ECA57ED60C74E5A34DFA15CE78BC2A095AFAFE24288C484AA25141` |
| `@blue-frontier/mitmproxy/src/lib/dns/base.js` | `60D1CBE1C24D4A6D44391BF4D4DD630B739D3BCF84F7EC0C4B5E39B3E1EC7284` |
| `@blue-frontier/dev-sidecar/src/config/index.js` | `CE89DC9EBC9D08B701E658B321FCD126C6006A074EEA5648454A5DD5422152AA` |

Upstream supplies the ECH cache defaults, mapped/dedicated provider ordering,
hedge delay, shared name, priority selection, and AliasMode limit. Native
intentionally adds strict transaction/owner/config validation, the stricter
accepted metadata profile, bounded fanout and retention, loser cancellation,
whole-lookup deadlines, dependency invalidation, alias TTL propagation,
error-vs-absence distinctions, and shared-retention capping. Upstream masks
several lookup/config errors as null; native exposes them to dialer policy.
These are reviewed design differences, not an upstream parity claim.

Copied unchanged from `migration-wave3-review/dev-sidecar-lightning` under
`C:\Users\HONOR\.codex\worktrees`:

| Source | SHA-256 |
| --- | --- |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt` | `8D400CB3067BF9A07E683FD5DE573482D821296EF56AC20F8246FAABCD5DAEB0` |
| `port/tests/fixtures/wave3/ech-config.valid.json` copied to `netcore/src/test/resources/ech/ech-config.valid.json` | `D36C0B921F783EDECAC79BAD391F5DC10259D2FE50EB801647B2AB9C87D59DF9` |

| Existing file | Frozen SHA-256/status |
| --- | --- |
| `NativeDnsModels.kt` | Unchanged `5EB2204CFFFE3375230387F90C72F0E93943646D456A22559469C9EE6D12A87D` |
| `NativeDnsResolver.kt` | Unchanged `23AF427DE13EB49CABABC2029CCBC4CCFCBEF8E10ACF3DEB4B9B918ED1765E18` |
| `NativeDnsWire.kt` | Unchanged `42C14055527B9C4B28E3E1AD9204CCEF3F3AD8D2964FBA37A154B70C10570D63` |
| `SocketDnsTransport.kt` before safe extension | `232E58F91048E8DF67441D97E315F5DDD9C0D4D6C60C7CD20322735578B0660C` |
| `SocketDnsTransport.kt` after safe extension | `5CD8F1327C408DB8CEEA6F1FB0D2E66D0E0672495DB15BC87DB5176C38AF6745` |

The machine-readable `native-ech-provenance.json` lists hashes for new sources,
accepted unchanged tests/reports/runners, copied prerequisites, and this safe
extension. Earlier upstream transport source hashes remain recorded in
`dns-transport.md`; prerequisite configuration hashes remain in
`dns-orchestration.md`.
