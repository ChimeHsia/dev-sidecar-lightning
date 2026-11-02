# Socket DNS transport (isolated, unapplied)

Implemented in the existing managed checkout:

```text
C:\Users\HONOR\.codex\worktrees\native-dns-orchestration\dev-sidecar-lightning
```

This step adds actual UDP, TCP, DNS-over-TLS, and RFC 8484 DNS-over-HTTP
requests behind the accepted `DnsTransport` interface. It does not apply these
classes to production, change `HostResolver`, alter the accepted resolver/models
or its port isolation, update build configuration, or touch application/payload
files. The earlier orchestration report remains unchanged.

## Stable files and API

Production additions in `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/native/`:

- `SocketDnsTransport.kt`: `SocketDnsTransport`, `SocketDnsLimits`, and
  `NativeDnsEndpointResolver`.
- `NativeDnsWire.kt`: `NativeDnsWire` and `NativeDnsProtocolException`.

Test additions in `netcore/src/test/kotlin/com/dsha/sidecar/net/dns/native/`:

- `SocketDnsTransportTest.kt` (36 tests).
- `NativeDnsWireTest.kt` (18 tests).
- `NativeDnsTestPackets.kt`, `NativeDnsTestServers.kt`, and
  `NativeDnsTestTls.kt` (test fixtures).

The independent runner is `docs/native-proxy/run-dns-transport-tests.ps1`.
It leaves the accepted orchestration runner unchanged.

```kotlin
val transport = SocketDnsTransport(
    endpointResolver = NativeDnsEndpointResolver.SYSTEM,
    sslContext = SSLContext.getDefault(),
    limits = SocketDnsLimits(),
)
val future: CompletableFuture<NativeDnsAnswer> = transport.query(query)
future.cancel(true) // closes this query's owned sockets and removes queued work
transport.close()  // fails active/queued callers; closes all owned sockets
transport.awaitTermination(5_000) // optional bounded join after close
```

`query` returns promptly; bootstrap and network operations run in bounded
blocking workers. Unsupported providers/URLs and malformed responses complete
the future exceptionally. `state()` exposes pending query/resource/queue/worker
counts and the closed flag. This is a diagnostic API, not a synchronization
barrier; inspect it after a query future completes for its cleanup result.

`NativeDnsEndpointResolver.resolve(host)` remains a one-argument SAM for simple
pins. The family-aware overload receives the **provider's bootstrap family**,
which is separate from the question's requested record family. A custom lambda
may ignore the overload but its returned address must still match a configured
provider family. `SYSTEM` selects the first platform address matching that
family and recognizes strict IP literals without a name-service call. There
is one selected endpoint per query, not multi-address failover.

Default bootstrap explicitly uses `InetAddress.getAllByName` for provider DNS
names. This is platform/system resolution; it does not claim to bypass system
DNS. To pin bootstrap while preserving TLS/HTTP identity, configure a DNS
service hostname in the descriptor/URL and inject its IP. Bootstrap never
resolves the question hostname through the OS.

## Transport behavior

| Descriptor transport | Behavior |
| --- | --- |
| `udp` | Connected datagram socket, default port 53, peer address/port constrained by the socket and checked again; matched TC response falls back to TCP at the same endpoint with the same question/ID. |
| `tcp` | Default port 53; two-byte unsigned DNS length, exact reads across fragmented prefixes/bodies, one request per owned connection. |
| `tls` | Default port 853; same DNS framing over a verified TLS connection. |
| `https` | Descriptor server must be an `http://` or `https://` URL; RFC 8484 POST with `application/dns-message`, default URL port 80/443. The descriptor's historical name `https` also supports explicit plaintext HTTP endpoints. |

Provider ports must be whole numbers in 1..65535. DoH uses the URL's port and
rejects a separate descriptor port, userinfo, fragments, unsupported schemes,
scoped addresses, and malformed bracketed hosts. Non-TLS transports reject an
SNI option. Invalid question names/options fail before provider bootstrap.

DoH uses the accepted native HTTP/1 framing codec with one owned connection.
It preserves the URL path/query and Host authority. It requires status 200 and
a single `application/dns-message` Content-Type. Fixed, chunked, and
close-delimited bodies are decoded under a finite body limit. Redirects,
compressed bodies, ambiguous framing, JSON content types, legacy
`application/dns-udpwireformat`, and HTTP error statuses fail explicitly.
There is no content-type downgrade, redirect following, proxy discovery,
cookie store, global HTTP pool, or JDK 11 `HttpClient`.

Record family policy is explicit:

- `IPV4`: query IN A.
- `IPV6`: query IN AAAA.
- `ANY`: query A, then AAAA, sequentially under one total deadline; preserve
  A-then-AAAA order and each record's TTL. This is not DNS QTYPE ANY.
- NODATA in one family permits addresses from the other. Any transport/DNS
  error fails the combined query. NXDOMAIN is name-wide, so mixed
  NXDOMAIN/non-NXDOMAIN answers fail as inconsistent. Both NXDOMAIN answers
  combine with the smaller negative TTL.

## DNS validation and TTL

Random 16-bit transaction IDs come from a transport-owned `SecureRandom`.
Every response must be QR response/opcode zero, have exactly one matching
question name/type/IN class, and match its expected ID. Name comparison is
ASCII/case-insensitive with one optional trailing dot. The structural parser
validates complete packet lengths, counts, bounded compression, labels, all
sections, CNAME RDATA, and trailing data. A and AAAA RDATA must have exactly
4 and 16 bytes respectively. Nonzero error RCODEs other than NXDOMAIN fail;
extended EDNS errors fail. No DNSSEC validation is claimed.

A TC datagram must still have a valid matched header and complete question.
Its partial RR body is not consumed; TCP retries the original question.
TC over a stream or HTTP is an error. Wrong-ID/question datagrams are rejected,
not accepted as fallback triggers. A connected UDP socket ignores other peers.

Only IN answers associated with the question through a bounded CNAME chain
contribute addresses. Unrelated and additional-section addresses cannot become
destinations. Conflicting CNAME targets, cycles, CNAME/address coexistence, and
oversized chains fail. If an answer contains only a CNAME chain without
authoritative negative data, the transport follows the canonical target through
the same provider. The hop and total deadline budgets span follow-up messages.

TTLs retain unsigned DNS seconds converted to milliseconds. Each address TTL is
limited by every traversed CNAME TTL. Duplicate addresses and duplicate identical
CNAMEs retain their shortest TTL. NXDOMAIN/NODATA negative TTL comes only from
an authority IN SOA covering the final canonical name, using the minimum of
SOA RR TTL and SOA MINIMUM, further capped by alias TTL. No SOA means zero
negative TTL; no lifetime or successful address is fabricated. Cache lifetime
capping still belongs to the unchanged orchestration layer.

## Deadlines, bounds, and lifecycle

Defaults are four workers, sixteen queued tasks (twenty pending queries total),
5,000 ms total query deadline, 65,535 bytes per DNS message/HTTP body, EDNS UDP
payload advertisement 1,232 bytes, 128 RRs, 32 returned addresses, and eight
CNAME hops. All are checked bounded options. The UDP receive buffer is one byte
larger than the permitted message to detect oversized datagrams. TCP validates
length before allocation. HTTP headers are capped at 16 KiB, lines at 4 KiB,
64 fields, and trailers at 4 KiB/16 fields with a 4 KiB streaming buffer.

Queue time, provider bootstrap, connect, TLS handshake, reads, writes,
follow-ups, and both ANY questions share a monotonic total deadline. A single
owned scheduler enforces it even when a read/write is blocked, and per-read/
connect timeouts use the remaining budget. Canceled/completed deadlines are
removed from the scheduler. Canceling queued tasks removes them from the
executor queue. Exceeding worker/queue capacity returns
`NativeDnsCapacityException` rather than retaining unbounded pending work.

Every active UDP socket, raw TCP socket, and TLS wrapper is registered with its
query before use. Terminal completion closes owned resources before completing
the returned future. Canceling a query interrupts its task, closes its resources,
and discards late results. Raw sockets close before TLS wrappers to release
handshakes/reads. `close` is idempotent and closes active resources, rejects new
queries, fails queued/active callers, and shuts down both owned executors.
`awaitTermination` offers an optional bounded process/test join.

Platform hostname lookup can be uninterruptible inside the JVM/OS. A deadline
or close still releases its caller and prevents later socket registration, but
cannot forcibly terminate that platform call. Such workers remain bounded by
the fixed pool until the OS returns. Use a prompt, interrupt-aware pin/bootstrap
hook when deterministic worker termination is required. `CompletableFuture`
callbacks can run inline; consumers must not block worker/deadline/close
completion callbacks (use an asynchronous continuation for blocking work).

## TLS identity and trust

Default TLS uses the platform `SSLContext` and strict chain verification plus
HTTPS endpoint identification. It also independently checks the certificate SAN
against the provider service hostname (DoT) or URL hostname (DoH). A certificate
matching only configured SNI is insufficient. DNS SAN wildcard matching accepts
only a whole leftmost label and a multi-label suffix; IP services require an IP
SAN. CN-only fallback is intentionally not supported.

`descriptor.sni` controls ClientHello SNI only. It never replaces the verification
host, disables validation, or changes HTTP Host. With no override, DNS service
hosts send their own SNI; IP services send no DNS SNI. Tests inject an SSLContext
trusting their in-memory test CA; there are no trust-all managers or global
security-provider/default-context/trust-store mutations.

## Unsupported scope

NAT64/preset/unknown transport dispatch fails with
`UnsupportedOperationException`; presets remain the resolver's local path.
The frozen query API requests addresses only. Native wire question construction
rejects non-A/AAAA types, so there is no ECH/HTTPS/SVCB lookup result or
placeholder ECH data. Descriptor ECH metadata does not turn an A/AAAA query into
an ECH exchange. NAT64 synthesis, DNSSEC validation, HTTP JSON endpoints,
HTTP/2/3, proxy routing, endpoint failover, pooling, Unicode/IDNA conversion for
service/query names, and production wiring remain separate work.

The implementation uses JVM sockets/JSSE and existing HTTP framing, with no
new production dependencies and no JDK 11 HTTP client. Android API/runtime
compatibility has not been verified by an Android/Gradle build in this task.

## Verification

Run from the managed checkout:

```powershell
.\docs\native-proxy\run-dns-transport-tests.ps1
```

Verified on 2026-11-02: **133 tests passed**: 36 actual socket transport tests,
18 wire validation tests, the accepted 41 orchestration tests, and 38 existing
literal/NAT64/packet/matcher regressions. Compilation uses the original JDK 21,
cached Kotlin 2.0.21, and `-Xmx256m`; JUnit uses `-Xmx192m`. The only compiler
warning is the preexisting deprecated `URL(String)` call in `DnsClient.kt`.

All network fixtures use numeric loopback addresses and injected provider pins;
the default bootstrap tests use literals only. There are no external DNS/URLs,
phone actions, Gradle processes, application builds, Node executions, or
production runtime/payload edits. Latches coordinate cancellation/handshake/
bootstrap tests with bounded joins; no sleeps are used. Packet tests cover every
truncated prefix of a valid positive response, mismatched IDs/questions/classes,
malformed RDATA, pointers, record limits, and alias lifetimes. Socket tests
observe peer EOF or UDP port rebinding after cancellation, fragmented frames,
UDP TC fallback, DoH limits/framing, strict TLS failures, overload recovery,
and actual use through the frozen port-isolated resolver.

Certificate fixtures are newly written test-only code, not copies of the sibling
`EphemeralTestCa`/CA implementation. Bouncy Castle 1.79
(`bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on`) is read only from cached
jars on this runner's test/compiler classpath. It is not installed as a provider
or added to Gradle/production dependencies. Certificates and keys stay in memory.
Output jars and Java temporary files stay under this worktree's ignored
`netcore/build/native-dns-transport-*`.

## Source and prerequisite hashes

Upstream source was read only under:

```text
D:\File\User\code\dev-sidecar-lightning\port\.run\device-wave2-20261102\runtime\core\node_modules
```

The first six entries below are relative to
`@blue-frontier/mitmproxy/src/lib/dns/`; the last is relative to node_modules.

| Read-only source | SHA-256 |
| --- | --- |
| `udp.js` | `8CF2301F3F1A7837148317B2638DA5151C320EA6D4F48B5138290A89E0B3B6B4` |
| `tcp.js` | `D812027741EF94A72F32757E77A8778E9AA1D2040C63C94F73693A536233DA15` |
| `tls.js` | `1F84B2FD86A09E75FE18CC489B49A6E3EBDE34665A6F857CC45D5E514095BF57` |
| `https.js` | `90892865B5F3FB1A7174485BDC24416E72F8DAAD48675B06BECEF530C432D2C3` |
| `base.js` | `60D1CBE1C24D4A6D44391BF4D4DD630B739D3BCF84F7EC0C4B5E39B3E1EC7284` |
| `util/dns-over-tls.js` | `C7CA0402720CFE72C241F027F5E5B0342AD7EE44DFDD7762C50C4DE95F45B1CF` |
| `dns-over-http/index.js` | `610F57FF45E07B57EC4C1886AE679BBA730B2E34DC447A41FD55E9401FB06645` |

The upstream A/AAAA UDP path accepts the first datagram without transaction/peer
checks; its TCP path reads the first data event. Native adds strict matching,
fragment-safe framing, TC fallback, and bounded total deadlines. Upstream TLS/
HTTPS turn off certificate checks for configured SNI; native always validates.
The legacy `dns-over-http` package uses POST with
`application/dns-udpwireformat`; upstream SVCB DoH also has RFC 8484 wire/JSON
paths. Native deliberately supports RFC 8484 wire only, with no silent fallback.
Upstream BaseDNS coerces lookup family to A unless explicitly 6; native's
two-question ANY policy is an intentional improvement, not an upstream parity
claim. No upstream runtime oracle or Node process was executed.

Copied unchanged prerequisite source files:

| File | Source checkout | SHA-256 |
| --- | --- | --- |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/SvcbCodec.kt` | `migration-wave3-review` | `11689AB46C1866D64C1A69EDD4C9F409FBA2C51978DEE4FF27D86EBC4B7C8D12` |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/SvcbModels.kt` | `migration-wave3-review` | `FD6C876AD05706FE84C0BEE20BAA683BAD7BEF768BDF3A837E1581138422ACFF` |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/http/Http1Models.kt` | `native-http-framing` | `9522F1EA7E77C6347FEF9561BE4DDF3CBAFEC92CB522EAB74CF374F87E856CC0` |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/http/Http1Codec.kt` | `native-http-framing` | `6AB4EDA2A7F9969C792B6816C329B29D59800DCFD5AEDB44A2014215429A5B1F` |
| `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/http/Http1BodyCodec.kt` | `native-http-framing` | `3A96B9449653FCBF779D9E4616B07C5EA5BFA396131DF5D2B6412577DCBE392E` |

Source checkout roots are
`C:\Users\HONOR\.codex\worktrees\<checkout>\dev-sidecar-lightning`.
Existing orchestration prerequisites remain unchanged; their hashes are in
`dns-orchestration.md`.

| New production file | SHA-256 |
| --- | --- |
| `native/SocketDnsTransport.kt` | `232E58F91048E8DF67441D97E315F5DDD9C0D4D6C60C7CD20322735578B0660C` |
| `native/NativeDnsWire.kt` | `42C14055527B9C4B28E3E1AD9204CCEF3F3AD8D2964FBA37A154B70C10570D63` |

Frozen resolver SHA-256:
`23AF427DE13EB49CABABC2029CCBC4CCFCBEF8E10ACF3DEB4B9B918ED1765E18`.
Frozen models SHA-256:
`5EB2204CFFFE3375230387F90C72F0E93943646D456A22559469C9EE6D12A87D`.
