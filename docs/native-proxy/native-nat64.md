# Native NAT64 DNS transport (isolated)

Worktree:

```text
C:\Users\HONOR\.codex\worktrees\native-dns-orchestration\dev-sidecar-lightning
```

This step adds an actual NAT64 DoH query coordinator and socket-backed JSON
DoH channel. No production wiring, phone/network gateway test, Gradle build,
application/payload change, Node dependency, or commit is made. A real public
NAT64 gateway has not been contacted or accepted.

## Default gate and integration status

The current raw default `nat64.enabled=true` with `prefix=""` is **inactive**.
No NAT64 gateway/prefix is invented. Call the new
`NativeNat64Config.normalizeCatalog(providers, options)` before provider
selection: it suppresses the NAT64 catalog entry unless enabled is exactly true,
domains is a nonempty list, and a nonempty valid prefix is supplied. This
models the upstream `options.js` gate before the unchanged catalog helper.

The accepted `DnsProviderConfig` and `DnsProviderSelection` remain frozen.
Calling the old catalog helper directly does not acquire this new gate.
A caller that bypasses the gate and sends an empty-prefix NAT64 descriptor
receives `NativeNat64DisabledException`, never an ordinary IPv4 resolution.
The unused default feature therefore need not block a default native-proxy
switch, provided catalog assembly applies this gate.

Custom-prefix address queries can use the frozen `NativeDnsResolver` with
`NativeNat64Transport` injected as its transport. An integration regression
proves synthesized candidate order, hostname fallback, and port-isolated
feedback through that unchanged resolver.

Typed NAT64 HTTPS/SVCB queries are implemented and tested, including full ECH
bytes. The frozen `NativeEchResolver` currently filters eligible provider
transports to UDP/TCP/TLS/HTTPS and does **not** automatically select a `nat64`
descriptor. A separately reviewed route/provider-eligibility adapter is still
required before claiming automatic NAT64-domain ECH integration. Call
`NativeNat64Transport.queryHttps` explicitly for this data path today. Do not
silently substitute an ordinary IPv4 ECH resolver to bypass the NAT64 route.

Remaining custom-prefix acceptance gates:

- Parent integration applies the pre-selection empty-prefix gate and injects
  the wrapper for NAT64 address dispatch.
- Parent integration selects the typed NAT64 HTTPS/SVCB path for ECH metadata
  and preserves the TLS dialer's required-ECH policy.
- Device/network acceptance with the user's configured and trusted gateway
  confirms IPv6 reachability, address translation, provider interoperability,
  certificate trust, real route behavior, and actual ECH acceptance.

These are explicit integration/device gates. The localhost fixtures do not
demonstrate any gateway's availability or forwarding behavior.

## New files and API

New files under `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/native/`:

| File | Responsibility |
| --- | --- |
| `NativeNat64Models.kt` | Typed pinned DoH interface/query/result, finite coordinator limits, explicit failures. |
| `NativeNat64Config.kt` | Pre-selection gate, descriptor plan, accepted DoH-list helper, source bootstrap map, strict prefix/options. |
| `NativeNat64Json.kt` | Bounded JSON decoding, question/record validation, A/CNAME/TTL and HTTPS/SVCB presentation conversion. |
| `NativeNat64Transport.kt` | `DnsTransport`/`HttpsRecordTransport` wrapper, provider hedging, cache, single-flight, cancellation, lifecycle. |

The only accepted source extended in place is `SocketDnsTransport.kt`. It now
also implements the new `NativeNat64DohDialer`:

```kotlin
interface NativeNat64DohDialer : AutoCloseable {
    fun queryNat64Doh(
        query: NativeNat64DohQuery,
    ): CompletableFuture<NativeNat64DohAnswer>
}
```

The query retains a real HTTPS provider descriptor/URL, original question
hostname, A/HTTPS/SVCB type, and explicit IPv6 dial destination. Socket code
validates that destination as a complete IPv6 literal and never calls its
endpoint resolver for pinned NAT64 queries. URL host/service identity, SNI,
and HTTP authority stay separate from the dial address.

```kotlin
val catalog = NativeNat64Config.normalizeCatalog(providers, dnsOptions)
val socket = SocketDnsTransport()
val transport = NativeNat64Transport(
    underlying = socket,
    clock = monotonicClock,
    // dialer defaults to socket; injection supports local deterministic tests
)
val addressFuture = transport.query(nativeDnsQuery)
val serviceFuture = transport.queryHttps(httpsRecordQuery)
transport.invalidate(hostname = "example.test")
transport.invalidate(providerKey = nat64Descriptor.key)
transport.close()
transport.awaitTermination(5_000)
socket.awaitTermination(5_000)
```

Ordinary non-NAT64 queries delegate unchanged to the socket transport, whose
existing bounded admission and cancellation apply. NAT64 `ANY` and `IPV6`
address requests issue an A query and return only synthesized IPv6 records.
An explicit `IPV4`-only request fails as unsupported; the feature never
masquerades as ordinary IPv4 resolution.

The wrapper owns its underlying socket transport by default. Set
`ownsUnderlying=false` for a borrowed socket transport; already delegated
ordinary queries then remain the underlying owner's responsibility. A
separately injected dialer is owned by the wrapper and closed once.
Coordinator `awaitTermination` joins its scheduler; the socket transport has
its own bounded join. All APIs keep accepted signatures compatible.

## Descriptor planning and prefix policy

The NAT64 descriptor's `nat64Options` supplies:

- `prefix`: an explicit network accepted by unchanged `Nat64Address`; only
  `/96`, nonzero network, zero low 32 bits. A bare valid IPv6 /96 network is
  accepted by that existing codec. Brackets, scopes, whitespace, other prefix
  lengths, and populated low bits fail.
- `dns`, `doh`, and `providers`: consumed through the unchanged
  `DnsProviderConfig.resolveDohList`.
- `bootstrap`: hostname to strict IPv4-list overrides, at most 64 hosts and
  eight addresses per host. Empty lists explicitly disable bootstrap for that
  service. Only the first IPv4 is used, as in the inspected source.
- `cacheTtl`: positive whole milliseconds, default 300,000, bounded at
  86,400,000. Zero uses the upstream default.

Named DoH, explicit DoH URLs, then source default URLs are deduplicated using
the accepted pure helper. A non-DoH/absent named provider falls back according
to that helper; it is never converted into a different transport.
NAT64 routes require HTTPS URLs with no userinfo/fragment and valid ports.
Plaintext HTTP is explicitly rejected. URLs missing IPv4 bootstrap are
ineligible; no OS DNS or direct IPv4 fallback is attempted. Literal IPv4 URL
hosts bootstrap from themselves.

Only the source's existing defaults are included:

| DoH URL | Source bootstrap first/backup IPv4 |
| --- | --- |
| `https://dns.alidns.com/resolve` | `223.5.5.5`, `223.6.6.6` |
| `https://cloudflare-dns.com/dns-query` | `1.1.1.1`, `1.0.0.1` |
| `https://dns.google/resolve` | `8.8.8.8`, `8.8.4.4` |
| Configured `doh.pub` service (not a new default URL) | `1.12.12.12`, `120.53.53.53` |

These are provider bootstrap addresses, **not** NAT64 gateways. Every bootstrap
IPv4 is embedded into the caller's prefix for dialing, and each successful A
answer is independently embedded for the returned candidate list. There is no
prefix discovery, public-gateway default, interface scan, or OS lookup.

A descriptor key is one immutable configuration epoch. Reusing the same key
with changed options fails; create a fresh wrapper for a changed prefix,
provider list, pins, or cache settings. Plan retention has a fixed capacity.

## JSON DoH and socket reuse

Upstream `nat64.js` uses JSON GET, not ordinary wire POST. The new pinned API
implements that actual channel:

- GET keeps the URL path and unrelated query parameters, replaces name/type,
  and adds `ct=application/dns-json` if missing.
- Host is the original service authority (including an explicit port), SNI
  follows the descriptor override/service name, and verification remains
  against the URL service host, never the translated address or SNI override.
- It accepts DNS JSON Content-Types `application/dns-json`,
  `application/json`, and `application/dns+json`, status 200 only.
- Redirects, compressed bodies, invalid/oversized framing, and unexpected
  content types fail. Accepted HTTP framing handles fixed, chunked, or
  close-delimited bodies under existing size/head/trailer limits.

The socket implementation factors a private HTTP request helper from its
existing wire DoH path. Wire POST remains a separate path accepting only
`application/dns-message`; JSON is not silently accepted by ordinary DNS
requests. Both paths share existing workers, resource registration,
connect/read/total deadlines, socket cancellation, framing, TLS checks, and
lifecycle. No socket/TLS implementation is copied.

JSON has no DNS transaction ID to authenticate. This channel relies on strict
TLS service authentication and validates the returned question name/type.
Both a one-question array and a single-question object are supported.
`Status` must be integer NOERROR or NXDOMAIN, TC must not be true, and record
counts/classes/types/unsigned TTLs are checked. Unsupported DNS errors remain
exceptional. Invalid integer coercions and malformed IPv4 answers are rejected.

Only matching answer owners or their bounded CNAME chains contribute data;
unrelated answers cannot become candidates. CNAME conflicts/cycles/coexisting
owner data fail, and CNAME-only replies follow through the same pinned route
under the same deadline. The smallest CNAME TTL limits final address/service
TTLs. Authority SOA negative TTL uses the smaller RR TTL/MINIMUM for the
covering zone when present; absence has no fabricated address.

HTTPS/SVCB presentation values use unchanged `SvcbCodec` parsing and parameter
decoding, including raw full ECHConfigList bytes, mandatory/ALPN/hints/port/
flags, and supported numeric unknown keys. Unsupported presentation values
fail explicitly. Address synthesis does not alter ECHConfigList data, SNI,
Host, or service target/public-name metadata.

The JSON reader is bounded to 65,535 bytes, depth 16, and 8,192 nodes. It rejects
duplicate keys, trailing input, invalid UTF-8, malformed numbers, and malformed
JSON. The socket's configured maximum body can be smaller.

## Scheduling, cache, cancellation, and failures

Routes start at index times 300 ms by default. The most recently successful
DoH URL is preferred on the next hostname query. The first valid response,
including authoritative absence, wins. Losing requests and delayed starts
are canceled for **that flight only**, avoiding upstream's global
active-request cancellation across unrelated hostname queries.

The socket's default per-query deadline is 5,000 ms, including queueing and
I/O. The coordinator's hard window defaults to 5,000 ms plus the last stagger
offset, so even a nonresponding injected dialer is bounded. A caller can set
smaller bounded limits. Socket and coordinator deadlines are independent;
the first expiry cancels resources/work.

Address cache keys are `(NAT64 provider key, normalized hostname, A)`.
Destination ports do not split raw DNS data; the frozen outer resolver still
keeps per-port candidate health and fallback state. Returned records preserve
order and deduplicate textual IPv4 answers before synthesis. Positive TTL is
capped by configured `cacheTtl` and DNS/CNAME TTL. Hits subtract elapsed
monotonic time, preventing a second resolver cache from resetting an old TTL.
TTL zero is usable only for the current flight and is not retained.

Clean empty/NXDOMAIN results contain no address and are cached under configured
record-cache policy, shortened by a nonzero authoritative negative TTL.
The wrapper never appends hostname/IP placeholders; the unchanged outer
resolver owns hostname fallback and request feedback.

All-endpoint failures use the source's default 30,000 ms cooldown. Native
replays an explicit cached failure throughout that window, rather than
turning subsequent hits into upstream's empty-list result. Invalid config
fails before query admission. Cancellation and invalidation are not
failure-cached. TTL expiry is exact at the boundary with lazy pruning and
saturating addition.

HTTPS/SVCB uses bounded single-flight but has no extra service-record cache
in this wrapper. ECH configuration caching remains a consumer's responsibility.
Address and service queries have distinct flight keys. Host/provider
invalidation clears all matching flight types and cached addresses; late
uncooperative answers cannot revive invalidated state. The outer resolver's
own invalidate method does not automatically invalidate this lower cache:
integration must forward a complete cache reset to both layers when desired.

Defaults: eight configuration plans, 256 LRU address/failure entries,
16 flights, 64 waiters per flight, eight eligible-list DoH entries,
32 addresses, and one removable-task scheduler. All limits have finite upper
bounds. Admission overflow fails rather than growing state. Canceling one
waiter preserves others; the last cancellation cancels its active/timed work.
Close is idempotent, releases callers, clears state, and shuts down owned
resources without waiting for external work.

## TLS and intentional differences

The source sets `verifySsl` only when explicitly true. Native retains the
accepted socket transport's strict chain verification, endpoint
identification, and independent service SAN validation by default and always.
Raw `verifySsl=false` is not an escape hatch. Injected test contexts trust only
their in-memory test root; there are no trust-all changes or installed global
providers/trust stores.

Other deliberate native differences: strict portable /96 parsing, strict
JSON/question/IPv4/TTL validation, bounded state/fanout, TTL aging/capping,
failure replay instead of cached empty success, immutable plan epochs,
literal bootstrap support without the upstream prefilter bug, IPv4-only
request rejection, and per-flight loser cancellation. This is not an exact
oracle replay of every upstream error/coercion behavior.

## Local verification

```powershell
.\docs\native-proxy\run-native-nat64-tests.ps1
```

Verified 2026-11-02: **232 exercised passes, zero failures, zero capability
skips**: accepted 187 regressions plus 45 NAT64 tests (12 JSON, 25 coordinator/
configuration/integration, eight pinned/IPv6 socket tests).

The IPv6 fixture binds numeric `::1`. Failure to bind reports a distinct JUnit
capability assumption and the runner prints `SKIPPED_CAPABILITY` plus exercised
pass/skip counts. Such a skip must not be described as tested IPv6 support.
IPv6 was available and its socket tests ran on this host.

For these tests only, an injected dialer first asserts the exact prefix-derived
bootstrap IPv6 destination, then substitutes `::1` to reach a local TLS server.
All default external routes have their bootstrap lists disabled in those local
fixtures. This exercises actual IPv6 sockets, TLS, HTTP GET/authority/SNI/
certificate identity, JSON decoding, CNAME follow-up, service-record/ECH data,
body deadlines, and cancellation. It does **not** exercise a real translating
gateway. No external service or default provider URL is contacted.

Additional deterministic tests cover the disabled default catalog gate,
strict prefixes/options, source bootstrap defaults, named DoH/SNI, first-pin
mapping, duplicate/empty/negative answers, cache aging/cooldown/expiry,
single-flight, staggered winner preference, bounded retention/concurrency,
late answers, scoped invalidation, ordinary UDP delegation, and frozen
resolver port feedback/fallback.

The runner uses original JDK 21/cached Kotlin 2.0.21/JUnit, compiler heap
256 MB and test heap 192 MB, sequentially in one Java process at a time.
Cached BC 1.79 remains test-only. Output and Java tmp remain in this worktree's
ignored `netcore/build/native-nat64-*`. No sleeps, Gradle, phone, Node execution,
external lookup, JDK HTTP client, interface enumeration, new production
dependency, or global security mutation is introduced. Android build/device
and real network gateway acceptance remain pending.

## Provenance and accepted-source freeze

Read-only source root:

```text
D:\File\User\code\dev-sidecar-lightning\port\.run\device-wave2-20261102\runtime\core\node_modules\@blue-frontier\mitmproxy\src
```

| Source | SHA-256 |
| --- | --- |
| `lib/dns/nat64.js` | `7371B61B06187952520CE63C6891CB599DF83E223BED780E6F1F4001178BE2AB` |
| `options.js` | `411A38AF92019B15E829446F22D23BA9221E56F44253FA9E660FA95D7B63B356` |
| Frozen `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/Nat64Address.kt` | `7A79E1ACA5AE4AD61CE112EBC54DEA0FB70D4975AF903AD492A768116D0265C6` |

The source supplied the empty-prefix/domain gate, URLs/bootstrap map, JSON GET
format, 300 ms staggering, recent winner preference, 5-second timeout,
5-minute record cache, and 30-second failure cooldown. Accepted pure provider
helpers and portable prefix codec are used unchanged.

Only `SocketDnsTransport.kt` is safely extended with the explicit pinned
interface and common private HTTP helper:

- Accepted 187-test version:
  `5CD8F1327C408DB8CEEA6F1FB0D2E66D0E0672495DB15BC87DB5176C38AF6745`
- Extended version:
  `919DCA8C76161677636D7034C0ED9F59CF1CCAB8E12B7371E6C46FAD1A96E1EC`

All other accepted sources, tests, fixtures, reports, and runners remain
byte-identical. `native-nat64-provenance.json` records baseline comparisons,
new file hashes, and source provenance. No tracked base file is changed.
