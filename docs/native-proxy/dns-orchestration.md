# Isolated native DNS orchestration

This is an unapplied JVM foundation based on
`754ff423b59c743cf94e68b3417c1fde4a6f42b7`, implemented in the managed checkout:

```text
C:\Users\HONOR\.codex\worktrees\native-dns-orchestration\dev-sidecar-lightning
```

It adds `NativeDnsResolver.kt` and `NativeDnsModels.kt` under
`netcore/src/main/kotlin/com/dsha/sidecar/net/dns/native/`, a focused
`NativeDnsResolverTest.kt`, and a direct compiler/JUnit script beside this
document. No production caller, existing `HostResolver`, build configuration,
runtime adapter, payload, or application is changed. No Node removal is performed
in this isolated step; the foundation prepares the later native proxy.

## Prerequisites and provenance

These five source files were copied byte-for-byte, without edits, into
`netcore/src/main/kotlin/com/dsha/sidecar/net/dns/`. The first four come from
`C:\Users\HONOR\.codex\worktrees\migration-wave3-review\dev-sidecar-lightning`
at the same relative paths. `DynamicChoice.kt` comes from
`D:\File\User\code\dev-sidecar-lightning` at the same relative path.

| File | SHA-256 |
| --- | --- |
| `DnsProviderConfig.kt` | `64C944D589C7F70B213BA45DC131859584216DBF8F3E94A02F76D11E99A84F15` |
| `DnsProviderSelection.kt` | `D129CF7CD7974F92FE56522FD4AF43951E251D501431F6E12F17A4EF02A19F84` |
| `IpCandidatePolicy.kt` | `CC29F220BFECBEF7339FF0C85C4E9863BC28934144F12F415B3BB221EBCD51D9` |
| `Ipv6Availability.kt` | `972BD6DC96D161529EE77D416BE3DA18A6D0152CFF7134A5FED1CEE70A556B21` |
| `DynamicChoice.kt` | `AD8466A18961218E0CD2133FD42E3D0B9C5636C2310C072632A7A3565D1AF13F` |

The checked-out `AGENTS.md` was read. The referenced `RTK.md` was absent from the
checkout, original root, and checked parent/home locations; no substitute rules
were invented. The existing `IpAddress` and `HostnameMatcher` remain unchanged.

## Public boundary

All new types use package `com.dsha.sidecar.net.dns.native`.

| API | Contract |
| --- | --- |
| `DnsTransport.query(NativeDnsQuery)` | Return a `CompletableFuture<NativeDnsAnswer>` without blocking. Receives the exact selected descriptor, normalized hostname, and effective family. |
| `NativeDnsAnswer` | Complete answer records, individual TTLs in milliseconds, `ANSWER`/`NXDOMAIN`/`NODATA`, and an explicit negative TTL. |
| `NativeDnsClock.nowMillis()` | Inject a cheap, thread-safe monotonic, nonnegative millisecond clock. No wall clock or global timer is used. |
| `NativeDnsCandidateOrder.order(query, addresses, destinationPort)` | Optional nonblocking ranking boundary. Return a unique subset in the exact desired attempt order. Default delegates to the original two-argument hook, preserving existing lambda policies. Port-aware policies override this overload. |
| `resolve(hostname, requestedFamily?, destinationPort = 443)` | Independent cancellable future for each caller; equivalent pending requests on the same port share one transport query and one ranking operation. Port must be 1..65535. |
| `feedback(result, success)` | Count request success/failure against the entry identity represented by this result. Returns false for stale, evicted, expired, foreign, uncached, or closed tickets. |
| `reset(result)` | Explicit retry epoch for a live entry with IPs; restores original ranked IP order and hostname fallback, and rejects pre-reset feedback. |
| `invalidate(hostname?, providerKey?, family?)` | Remove matching cache entries and cancel matching active queries. Null filters mean all variants. |
| `reportIpv6Unreachable(address)` | Caller reports only applicable IPv6 `ENETUNREACH` events; third event disables IPv6 and invalidates the old epoch. |
| `state()` | Bounded-state counts for diagnostics; also lazily purges expired entries. |
| `close()` | Close the owned transport exactly once, clear cache/flights, fail pending callers, reject new calls. Does not wait for workers. |

Successful construction transfers transport ownership to the resolver. Transport
`query`, cancellation, and `close` must be nonblocking and safe to race; a query
racing transport close must fail or be canceled. The transport must release its
resources on future cancellation and must enforce its own network deadlines.
There is no built-in timeout scheduler or executor. A hung transport consumes
one bounded flight slot until caller cancellation, invalidation, or close.
As with ordinary `CompletableFuture`, user completion callbacks may run inline;
blocking consumers should use an asynchronous continuation/executor. Rule
matching and the injected clock also need to return promptly.

The transport and ranker must treat query descriptors as read-only. The resolver
copies configuration inputs, but descriptors use the prerequisite model's
read-only Kotlin collection convention rather than a recursively frozen Java
object graph. A ranker wanting real asynchronous probes should collect those
measurements outside this synchronous ranking hook and provide a completed
ordering, or evolve the boundary in a separately reviewed integration.

## Rules and results

Selection delegates to the accepted `DnsProviderSelection`: NAT64 domains,
preset/ECH precedence, ECH provider choice, hostname mappings, and the `usa`
alias follow that prerequisite. There is no invented default provider.
Unmapped hosts yield `NO_PROVIDER` with unresolved hostname fallback, no
transport query, and no cache or feedback ticket.

Family precedence is explicit request override, then mapping override, then
descriptor family, then `ANY`. The native boundary accepts null/0, 4, and 6,
including their decimal string forms (`"4"` or `"4.0"`, for example); other
families fail validation. Provider descriptors are validated at construction;
matched mapping families and host input are validated synchronously by resolve.
Cache sizes are nonnegative whole numbers or numeric strings, capped by the hard
per-provider limit; zero disables retention.

Preset values are matched using the existing hostname matcher and interpreted
with `IpCandidatePolicy.selectPreset`. The built-in preset descriptor is handled
locally. All other descriptors, including NAT64, pass to the injected transport;
this layer implements no NAT64 synthesis, ECH exchange, UDP, TCP, DoH, or DoT.
An empty preset yields explicit NODATA fallback; no OS lookup is performed.

Hostnames are trimmed, lowercased with `Locale.ROOT`, and lose one trailing dot.
ASCII labels of 1..63 letters/digits/hyphens/underscores and at most 253 total
characters are supported; Unicode/IDNA conversion is not implemented. Reserved
selector prototype keys are rejected. Literal destinations bypass selection
and transport, preserve IPv6 scope case, and respect explicit requested family.
IPv6 availability filtering applies to DNS/preset answers, not explicit literals.

Records must contain complete strict IP literals and nonnegative TTLs. Address
strings are bounded to 253 characters and complete answers are capped at
`maxCandidates` records before filtering/deduplication. Wrong-family and
unavailable-IPv6 records are filtered. Duplicate textual addresses are removed
in first-seen order; equivalent IPv6 spellings are not canonicalized. Zero IPs
remain DNS candidates, matching the accepted policy. A probe/ranker can apply
`IpCandidatePolicy.speedCandidates` before supplying reachable order.

The ranker cannot inject addresses or duplicate attempts. Its first returned IP
is the exact `selectedIp`; its returned order is retained, followed by hostname.
When all probes fail, return an empty list: the result has `NO_REACHABLE_IP`,
no selected IP, and explicit unresolved hostname fallback. It never silently
chooses an IP that all probes rejected.

Each result exposes `selectedDestination`, `selectedIp`, `hostnameFallback`,
`candidateOrder` (remaining attempts), `addresses` (initial ranked IPs),
`status`, family/provider, `destinationPort`, and `cached`. Lists are detached defensive copies.
`CANDIDATES` describes available choices, never a successful connection.

The unchanged second-wave `DynamicChoice` switches immediately on the first
failure of the current candidate. A late failure of an older IP cannot skip
the current IP, and success cannot revive a previously failed choice. After
all IPs fail, the hostname becomes an explicitly unresolved fallback. Failure
of the hostname yields `EXHAUSTED`, null destination, and an empty attempt order.
Exhausted entries stay exhausted until explicit reset, expiry, or invalidation.
No automatic retry cycle is started. Reset resets primitive counts, restores
the initial ranked IPs plus hostname, and rotates ticket identity; it makes
those IPs eligible for attempts without claiming they have succeeded.

## Cache, scope, and expiry

The native key is `(provider descriptor key, normalized hostname, effective
family, destination port)`. It is used for both candidate history and single-flight requests.
Configuration is one resolver epoch: changing providers/rules requires closing
the resolver and constructing a new one. There is no mutable provider registry.

The upstream behavior preserved here is the appended hostname fallback and
`DynamicChoice` feedback/reset primitive, as documented in
`docs/migration-wave2/dynamic-choice.md` in the original checkout. This is
native orchestration, not a claim of parity with all upstream BaseDNS cache
timers or request/runtime behavior. The following native port improvements are
explicit: family/port-separated keys; normalized host keys; bounded LRU retention;
TTL enforcement; stale-feedback rejection; active-query invalidation; bounded
single-flight fanout; and explicit terminal exhaustion instead of an automatic
fallback retry loop. These orchestration semantics have local behavioral tests,
not an upstream runtime oracle.

DNS provider port is part of the selected descriptor; replacing an endpoint
requires a new configuration epoch. Connection destination ports (80/443/etc.)
have independent complete cache entries, selector counters, feedback/reset
tickets, and flights. Port 80 failure or exhaustion cannot reorder or exhaust
port 443 candidates. `resolve` defaults to 443 for existing callers and validates
explicit ports before dispatch. The port-aware ranking overload also receives
the exact destination port so measured ordering can be scoped to that service.

DNS records themselves are independent of the destination port:
`DnsTransport.query` and `NativeDnsQuery` remain unchanged. This initial design
deliberately does **not** deduplicate queries across destination ports; concurrent
80 and 443 misses issue separate identical DNS queries. All per-port entries
and flights count against the existing total/per-provider/concurrency bounds.
No second cache tier or cross-port probe/feedback reuse is introduced.
`invalidate(hostname)` removes every provider, family, and destination-port
variant of that host, including active queries; provider/family filters still
apply across all destination ports.

Positive expiry uses the minimum TTL of eligible answer records, capped by
`maxTtlMillis`; an IP excluded by ranking can shorten but never extend that TTL.
Preset expiry uses `presetTtlMillis` with the same cap. Zero TTL is usable for
the current single-flight result but never retained and accepts no feedback.
Expiry is exact at `now >= expiresAt`, with saturating addition on overflow.
There is no stale-while-refresh or historical carryover across expiry.

NXDOMAIN, NODATA, empty, family-filtered, and all-probes-failed answers carry no
fabricated IP. They are retained only if the answer explicitly supplies a
positive `negativeTtlMillis`, subject to the same cap/LRU bounds. Default negative
TTL is zero. A negative result can offer hostname fallback, which this layer
does not resolve; that fallback is not a successful DNS or connection result.
Transport exceptions, unsupported transport errors, malformed answers, and
invalid ranker output fail all joined callers exceptionally and are not cached.

Default limits are 256 total entries, 64 per provider, 32 records per answer,
32 active flights, 64 waiters per flight, 600,000 ms maximum TTL, and 60,000 ms
preset TTL. Feedback and reads count as LRU accesses. Expiry is lazily swept on
operations rather than via timers; idle retained state is still bounded.
No per-host lock registry or unbounded pinned/feedback map exists.

The caller injects initial IPv6 capability. Only the third relevant event
disables it; IPv4 events do not count. Disabling invalidates all old cache and
flight state atomically, so a late IPv6 answer cannot reintroduce that epoch.
Success does not reset this process-level count. Recovery requires a new
resolver instance; no global interface scan or recovery timer is introduced.

## Cancellation and lifecycle

Canceling one returned future detaches only that waiter. Canceling the last
waiter immediately removes the flight and cancels its transport future.
Invalidation and close detach matching flights under the monitor, cancel
transport work, then complete every pending caller exceptionally.
Flight object identity prevents late/uncooperative answers from deleting a
replacement flight or repopulating a cache after invalidation or shutdown.
Ranking runs outside the monitor; even a ranker paused by a test latch cannot
prevent shutdown or invalidation from releasing callers.

No threads, sockets, timers, persistence, OS DNS calls, interface discovery,
Android APIs, or new build dependencies are introduced by the resolver.

## Direct local verification

From this checkout:

```powershell
.\docs\native-proxy\run-dns-orchestration-tests.ps1
```

The script uses the original `.jdk/jdk-21.0.2` and preexisting
`C:\Users\HONOR\.gradle` jars: Kotlin compiler 2.0.21 and JUnit 4.13.2. It directly
compiles all existing netcore production sources plus the selected tests to JVM
17 bytecode with `-Xmx256m`, then runs JUnit with `-Xmx192m`. No Gradle process,
dependency download, daemon, Android build, phone, external DNS, or network
transport is used. Compiler output and Java temporary files stay in a unique
ignored `netcore/build/native-dns-direct-*` directory inside this worktree.

The focused suite has 41 tests, including descriptor/rule/family precedence,
preset and literal paths, exact ranked destination order, all-probe failure,
first failure/fallback/exhaustion/reset, late feedback, negative results,
TTL boundaries, LRU/provider limits, family scoping, malformed answers,
single-flight sharing, port 80/443 ordering and exhaustion isolation, port
validation, hostname invalidation across cached/pending ports, capacity
recovery, individual/last-waiter cancellation,
late answers, invalidation during ranking, process IPv6 state, and shutdown.
Concurrency uses bounded thread pools and latches with three-second failure
deadlines and no sleeps. The script also runs 38 unchanged literal, NAT64,
DNS packet, and hostname matcher tests. These checks do not demonstrate live
transport performance or actual native proxy/browser integration.

Verified on 2026-11-02: direct compilation succeeded and JUnit reported
`OK (79 tests)` (41 new orchestration tests plus 38 existing regressions).
Compilation emitted the existing `DnsClient.kt` deprecated `URL(String)`
warning; no warning originated in the new resolver sources.
