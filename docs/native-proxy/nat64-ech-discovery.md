# Automatic NAT64 ECH discovery adapter (additions only)

Worktree:

```text
C:\Users\HONOR\.codex\worktrees\native-dns-orchestration\dev-sidecar-lightning
```

This change closes the NAT64 eligibility gap without modifying any accepted
232-test source, fixture, report, runner, or manifest. In particular,
`NativeEchResolver`, `NativeNat64Transport`, and `SocketDnsTransport` remain
byte-identical to the accepted snapshots. This batch contains only six new
files, intended as a separate integration patch over the parent's accepted
snapshots, not a replacement for those prerequisites.

## Feasibility audit and dependencies

The frozen `NativeEchResolver` has an injected catalog/typed transport and
private candidate allowlist for UDP/TCP/TLS/HTTPS. Its `DnsProviderSelection`
has no NAT64 domain argument. As a result, passing a NAT64 descriptor directly
does not make it eligible for ECH discovery.

An additions-only solution is possible with a paired private eligibility
projection and typed transport adapter. No minimum patch to frozen files is
needed, and no reflection, inheritance, ordinary A-question substitution, or
new production dependency is used.

Dependencies remain JDK 21, cached Kotlin 2.0.21 and JUnit 4.13.2. Cached BC 1.79
is used only by the unchanged in-memory TLS test certificate fixture. The
adapter requires the accepted ECH/NAT64 snapshots listed in
`native-ech-provenance.json` and `native-nat64-provenance.json`.

## New API

Production addition:

```text
netcore/src/main/kotlin/com/dsha/sidecar/net/dns/native/NativeNat64EchDiscovery.kt
```

```kotlin
val discovery = NativeNat64EchDiscovery.create(
    providers = rawDnsProviders,
    dnsOptions = rawDnsOptions, // ech and nat64 objects
    transport = nativeNat64Transport,
    clock = monotonicClock,
    ownsTransport = false, // default: share with address resolver
)
val result: CompletableFuture<NativeEchResult?> = discovery.resolve(hostname)
discovery.invalidate(hostname = hostname)
discovery.invalidate(providerKey = nat64Descriptor.key)
discovery.close()
discovery.awaitTermination(5_000)
```

The caller injects a real `NativeNat64Transport`, which uses the accepted
socket-backed pinned JSON DoH interface in production. The adapter delegates
all ECH choice/cache/alias/shared/single-flight/deadline behavior to the actual
`NativeEchResolver`, not a second ECH implementation.

`resolve` returns null for inactive or nonmatching routes and clean ECH absence.
It propagates active-route failures exceptionally. Null or a configuration
result never establishes required ECH acceptance; the TLS dialer retains that
policy decision. For required NAT64 routes, callers must not convert failures
or absence into an ordinary IPv4 connection attempt.

## Private projection and real transport routing

The factory snapshots caller configuration and first applies the frozen
`NativeNat64Config.normalizeCatalog` pre-selection gate. It then constructs
the existing `DnsProviderSelection` with the actual NAT64 domain rules.
Only matching active NAT64 hosts can call the inner ECH resolver.

For the private inner resolver only, the original NAT64 descriptor is copied
with `transport="https"` so it satisfies the frozen eligibility check. No
URL, service hostname, IP address, SNI, or verification host is synthesized or
changed. This inert descriptor has no invented server. The paired
`HttpsRecordTransport` validates the projected descriptor and immediately
restores the complete original `nat64` descriptor before invoking
`NativeNat64Transport.queryHttps`.

The projection is not exposed as a catalog to application callers and cannot
reach `SocketDnsTransport` as an ordinary DoH provider. The inner catalog
contains only the one NAT64 provider. The DoH endpoint list, bootstrap mapping,
staggering, last-winner preference, cancellation, queue bounds, and strict TLS
identity remain in the frozen NAT64/socket components.

AliasMode and shared-name follow-ups use the same dedicated NAT64 provider even
when the follow-up name is outside the original domain rules. They cannot
escape to the ordinary provider catalog. Shared queries happen only after
clean absence, as in the frozen resolver; failures do not trigger shared retry.

Result mapping restores the original provider key/name and `transport="nat64"`.
It preserves the full original length-prefixed ECHConfigList, all parsed
configs, selected config, original public name, query/route names, shared flag,
service priority/target, effective TTL and expiry. It does not reconstruct or
truncate the config list, rewrite public name, or change HTTP authority, SNI,
certificate verification, bootstrap order, or the original descriptor.

## Configuration and disabled behavior

NAT64 discovery requires all of:

- `nat64.enabled == true`;
- nonempty exact valid prefix accepted by the frozen portable /96 codec;
- nonempty configured domains and a matching hostname;
- ECH enabled/use and the NAT64 descriptor's ECH enabled flag.

An empty prefix or missing/empty domain list disables this route before
provider selection. Calling `resolve` then returns null without any
transport query, endpoint resolution, or network request. The adapter does
not dispatch ordinary ECH for nonmatching/inactive domains; that is a separate
route/planner decision. A nonempty invalid active prefix fails construction.

Explicit provider settings are checked more strictly than generic source
fallback helpers:

- If `nat64.dns` is supplied, it must name an existing DoH provider with ECH
  enabled and an eligible IPv4 bootstrap. Missing, non-DoH, disabled, or
  unbootstrapped explicit selections fail construction. They do not silently
  become the source default/public provider list.
- `ech.dns` may be absent, the `Nat64` binding, or exactly the same configured
  service name as `nat64.dns`. Matching names preserve existing dedicated
  settings while using the NAT64 channel. An unknown or conflicting ordinary
  ECH provider name fails construction; it is never used for direct IPv4 ECH.
- When there is no explicit NAT64 DoH service name, the accepted source default
  list and bootstrap policy remain available, in original order. No new public
  gateway, resolver URL, prefix, or bootstrap address is added.
- An explicitly disabled DoH selection fails closed. Disabled global ECH/use
  or NAT64 descriptor ECH returns null without network under valid config.

For inactive empty-prefix routes, ordinary ECH settings do not trigger a query
through this adapter. The raw input maps/domain lists are snapshotted; later
caller mutation cannot enable or reroute a lookup.

## Cancellation, cache, and lifecycle

There is one actual `NativeEchResolver` per active adapter, not one per host.
Its accepted LRU, flight/waiter bounds, deadline, AliasMode limits, failure
distinction, TTL clamping, shared retention, and dependency invalidation apply.
The adapter adds no cache, worker pool, timer, or per-host lock registry.

Canceling the returned mapped future detaches its inner resolver waiter.
Joined callers survive until their own cancellation; the last cancellation
reaches the NAT64 coordinator and socket resource owner. Late uncooperative
answers remain guarded by the frozen flight identities.

`invalidate` invalidates inner ECH dependencies and the NAT64 transport's
matching hostname/provider state. It does not clear ordinary-provider data
in a shared transport. `close` is idempotent and closes the inner resolver.
The NAT64 transport is borrowed by default; `ownsTransport=true` explicitly
transfers ownership. An owning adapter closes it once. A bounded scheduler
join is exposed for teardown; the transport's own joins remain separate.

## Tests and local evidence

New files:

- `netcore/src/test/kotlin/com/dsha/sidecar/net/dns/native/NativeNat64EchDiscoveryTest.kt`
  (21 tests).
- `netcore/src/test/kotlin/com/dsha/sidecar/net/dns/native/NativeNat64EchDiscoveryIpv6Test.kt`
  (8 tests).
- `docs/native-proxy/run-native-nat64-ech-discovery-tests.ps1`.
- This report and `nat64-ech-discovery-provenance.json`.

Run:

```powershell
.\docs\native-proxy\run-native-nat64-ech-discovery-tests.ps1
```

Verified on 2026-11-02: **261 exercised passes, zero failures, zero capability
skips**: 29 new tests plus the unchanged complete 232-test suite.
The host supports IPv6 loopback and every new IPv6 test actually ran.

The fake dialer first checks the exact synthesized prefix-plus-bootstrap
IPv6 address and original service settings, then substitutes numeric `::1`
only in the local test fixture. The real `NativeEchResolver`,
`NativeNat64Transport`, and `SocketDnsTransport` then query an independent
loopback TLS/JSON DNS server. Tests trust only the existing in-memory test CA.
An ordinary endpoint resolver that fails immediately catches IPv4/system-DNS
bypasses; zero ordinary calls are asserted.

Actual socket tests cover full multi-config delivery and supported selection,
unaltered public name/TTL, request Host, distinct SNI and verification identity,
AliasMode and shared names outside the original domain rule, redirect rejection
without retry, SNI-only certificate rejection, default platform trust rejecting
the private CA, malformed ECH lists, joined-waiter cancellation and peer EOF.
The local server cannot prove public NAT64 gateway forwarding or ECH handshake
acceptance.

Additional tests cover empty-prefix pre-selection, missing domains, disabled
providers/use, missing/conflicting explicit settings, bad prefixes, bootstrap
requirements, apex/wildcard matching, snapshot isolation, exact expiry,
invalidation and late answers, borrowed/owned lifecycle, original DoH ordering,
recent-winner priority, and cancellation of losing routes.

The capability-aware accepted runner reports IPv6 bind failures as explicit
`SKIPPED_CAPABILITY`, with exercised passes counted separately. No such skip
occurred. All tests use bounded joins/latches/futures with no sleeps.

Compilation uses the original JDK 21 and cached compiler/runtime jars at
256 MB; tests use 192 MB. They execute serially in one Java process at a time.
Output and Java tmp remain inside ignored worktree
`netcore/build/native-nat64-ech-discovery-*`. The preexisting deprecated
`URL(String)` warning in `DnsClient.kt` is unchanged.

No ADB, phone, Gradle, Android build, external DNS/URL, real gateway, Node
execution, new production dependency, global trust/provider change, main
branch modification, or alteration to the frozen accepted files is performed.

## Freeze and delivery

The new manifest records every preserved file hash from the accepted ECH and
NAT64 manifests, plus the frozen NAT64 manifest itself, original prefix codec,
and socket transport. Accepted 232-test source hashes remain:

| File | SHA-256 |
| --- | --- |
| `NativeEchResolver.kt` | `D5F474978B6A898533CE7EE946F6168693AADD8F95B4F75B05717354288D98FD` |
| `NativeNat64Transport.kt` | `C95036B79B01F13056600096074144EF980F956C9C9091EBD6DD0AA0049F5985` |
| `SocketDnsTransport.kt` | `919DCA8C76161677636D7034C0ED9F59CF1CCAB8E12B7371E6C46FAD1A96E1EC` |
| `NativeDnsResolver.kt` | `23AF427DE13EB49CABABC2029CCBC4CCFCBEF8E10ACF3DEB4B9B918ED1765E18` |
| `Nat64Address.kt` | `7A79E1ACA5AE4AD61CE112EBC54DEA0FB70D4975AF903AD492A768116D0265C6` |
| `native-nat64-provenance.json` | `7B9DADC361CA179F8925884305604811C6967C173B381F7C8ABBE384553DC917` |

Only this batch's six new files belong to the separate commit. Prior accepted
snapshot files remain outside it. Applying the commit to the original bare base
without those parent prerequisites is not a standalone integration build; apply
it over the parent's accepted DNS/ECH/NAT64 snapshot.
