# Native proxy engine (JVM only)

Implemented in `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/native/` on
`754ff423b59c743cf94e68b3417c1fde4a6f42b7`, in managed worktree
`C:\Users\HONOR\.codex\worktrees\native-proxy-engine\dev-sidecar-lightning`.
This is an independently testable engine; no Android/runtime entry point,
application setting, Node payload, application CA, device, or production proxy
has been changed. Nothing has been committed.

## API and routing

- `NativeProxyServer(resolver, certificates, options, transformer, tlsConnector, exchangePolicy)`
  is `Closeable`. `start()` binds synchronously and returns the actual
  `InetSocketAddress`. The default endpoint is `127.0.0.1:0`; the caller chooses
  the integration port.
- `NativeProxyOptions` controls the loopback address, backlog, workers, bounded
  accepted-connection queue, connection/request counts, deadlines, downstream
  TLS protocols, optional duplex idle/lifetime limits, and **`Http1Limits`**.
- `RouteResolver.resolve(NativeProxyRequest)` is an injected, synchronous route
  decision. It receives the validated head, logical authority, secure flag and
  optional CONNECT authority. It must return promptly; the engine cannot
  forcibly interrupt arbitrary user callback code.
- `NativeProxyRoute.destination` is a **resolved TCP address**. DNS resolution
  and live configuration are outside this engine, so a hidden blocking DNS
  lookup cannot escape its TCP connect deadline.
- `connectPolicy` selects `MITM`, `TUNNEL` or `BLOCK` for CONNECT. MITM resolves
  again for each decrypted request. A decrypted HTTPS request requires an
  explicit `NativeTlsPolicy`; a missing TLS policy fails with 502, with no
  plaintext fallback. TUNNEL is opaque TCP and rejects a non-null TLS policy.
- `NativeTlsPolicy` independently names `verifyHost`, optional `sniHost`,
  trust mode, optional caller-supplied trust `SSLContext`, and protocols.
  Explicit `sniHost = null` sets an **empty server-name list**, suppressing
  JSSE's implicit peer-host SNI.

For example, CONNECT `browser.test:443` can terminate with a generated
`browser.test` certificate, route TCP to `127.0.0.1:<origin-port>`, send SNI
`routing.test`, and verify the origin certificate against `origin.test`.
The tests exercise that exact separation with distinct ephemeral downstream
and upstream roots.

`STRICT` is the upstream default: JSSE validates the chain against the route
context (or a fresh context with platform trust), followed by an independent
SAN identity check against `verifyHost`. This separate check avoids binding
verification to a different SNI name. DNS wildcards match exactly one label
and require a multi-label suffix; IP literals require an IP SAN. CN-only
certificates are intentionally rejected. `CHAIN_ONLY` explicitly omits the
identity check while retaining chain validation. `UNVERIFIED` explicitly
uses an engine-local insecure context, and cannot also supply a trust context.
No failure switches trust modes, retries another trust context, or replays a
request. Caller-supplied contexts must implement the intended chain trust.
The connector never reinitializes them.

`tlsConnector` accepts the injectable `NativeTlsDialer` fun interface:

```kotlin
fun connect(raw: Socket, policy: NativeTlsPolicy, handshakeTimeoutMillis: Int): SSLSocket
```

The default `NativeTlsConnector` implements that interface. A composite/provider
dialer may look up injected ECH configuration by `policy.verifyHost` without
adding fields to this engine's route policy. An adapter must obey STRICT
chain/identity checks, route protocols and other security policy, and fail
closed for required features rather than silently invoking a weaker fallback.
The engine cannot recover trust guarantees if a custom dialer deliberately
ignores that contract. TCP connect, handshake and exchange deadlines remain
server-owned timers that close the raw transport, including when an injected
dialer changes SO_TIMEOUT. No Conscrypt/ECH provider dependency is copied here.

No security provider, default SSL context, hostname verifier, trust store, or
system property is mutated. Only TLS 1.2 and TLS 1.3 can be enabled, intersected
with the local provider's supported protocols; an empty intersection fails.

## Request policy and transformations

The engine requires exactly one Host field, including for HTTP/1.0. It
normalizes DNS case/trailing dot and IP spellings, and compares effective
ports. An absolute target and Host must agree. Inside decrypted CONNECT, an
origin target's Host, an absolute target's authority and scheme, and the
CONNECT authority must agree. Nested CONNECT, plaintext `https://` absolute
requests, `http://` absolute requests inside TLS, user info, invalid
authorities, and leading `//` origin paths are rejected.

**This intentionally differs from the earlier RequestTransform Host-wins
behavior.** A conflicting absolute authority does not silently route using
Host. The framing codec itself permits a leading `//` origin path, while this
engine applies a stricter ambiguity policy. These are explicit policy choices,
not a claim of full Node/request-transform compatibility.

`NativeRequestTransformer` is separate from routing and receives a validated
request. It can edit the path and non-framing headers. After it runs, the
engine validates the authority, method, framing and serialized output head
again **before opening the upstream socket**. It rejects authority changes,
method changes, body-framing changes, duplicate Content-Length, TE+CL, malformed
header values and Connection nominations that hide framing/Host/negotiation.
Expect/Upgrade negotiation must remain identical after transformation; a
transformer cannot silently add/remove a continue gate or rewrite the offered
WebSocket key/protocols. The original body is forwarded at most once, with
early final responses canceling an unfinished upload. A future body transformer
must supply a separate streaming/reframing contract rather than changing Content-Length
behind this interface.

Hop-by-hop fields and Connection-nominated fields are stripped; Host is
regenerated from the validated logical authority. Valid Upgrade fields are
preserved explicitly and Connection is regenerated as Upgrade for negotiation.
This keeps client identity
independent of the upstream TCP/SNI/verification identity. There is no live
configuration adapter or request-policy parity layer in this change.

## Trusted rule integration hooks

The optional constructor parameter `exchangePolicy` is appended after
`tlsConnector` and defaults to `NativeExchangePolicy.Passthrough`. Existing
constructor calls, `NativeTlsDialer` signature, and `NativeTlsPolicy` types
remain unchanged.

```kotlin
interface NativeExchangePolicy {
    fun onRequest(request: NativeProxyRequest): NativeRequestDecision
    fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead): Http1ResponseHead
}
// Both callbacks have passthrough default implementations.
// Decisions:
NativeRequestDecision.Forward(head, route = null)
NativeRequestDecision.LocalResponse(head, body = byteArrayOf())
```

The boundary order is raw client validation -> strict untrusted
`NativeRequestTransformer` validation -> trusted `onRequest` -> trusted
decision revalidation -> optional resolver -> socket connect. A Forward
decision may change path and logical authority, including for a request
inside decrypted CONNECT, and optionally supply a resolved route directly.
If no route is supplied, the injected resolver receives the revalidated
rewritten request. Its `connectAuthority` still describes the original
downstream TLS identity; the new logical authority is in `authority` and
`head`. No downstream leaf/CONNECT binding is silently replaced.

Trusted rewrites must keep method, HTTP/HTTPS transport scheme, body framing,
and Expect/Upgrade negotiation unchanged. Host and target must agree with
each other after rewriting; syntax, head limits, hop nominations, duplicate
lengths and TE+CL are checked again before dialing. Trusted-policy failures
produce 502, while invalid untrusted input/transforms retain 400/417.
CONNECT itself supports local rejection or route selection, but cannot
rewrite its certificate/tunnel authority through this hook.

LocalResponse supports terminal success, abort, redirect and OPTIONS-style
responses before **any resolver invocation or upstream socket**. The body
is copied only after a 64 KiB construction cap and must also fit the engine's
configured body quota. Local responses are final statuses only, cannot
create CONNECT/101 handoffs, reject Transfer-Encoding or inconsistent
Content-Length, and always close downstream. The engine applies HEAD/no-body
semantics, sets framing/Connection explicitly, and bounds writes/terminal
upload disposal. Local responses are already policy-generated and are not
passed through `onResponseHead` again.

`onResponseHead` runs on upstream informational and final heads before hop
sanitizing and downstream serialization. It can add CORS or rewrite ordinary
headers/reason text while retaining status/version, Content-Length,
Transfer-Encoding and body framing. For 101 it also retains Upgrade,
WebSocket Accept/subprotocol/extensions and undergoes handshake validation.
Malformed edits produce 502 before commit; after final commit, failures
close the stream. The body continues through the same fixed-buffer codec.

There is deliberately no body hook in this version. A later optional bounded
body processor should declare a maximum input/output size and explicit
reframing/content-encoding behavior; exceeding its limit must have a chosen
reject-or-passthrough policy before response commit. Whole-body edits must
never silently buffer unlimited downloads. Scheduling, retry, cache,
RulePlannerActionExecutor and DNS adapters remain parent-owned integration
work; no rule files were moved or copied into this engine.

## Streaming and persistence

All request and response bodies flow through the copied framing codecs with
fixed-size buffers; the engine does not collect complete bodies in memory.
Content-Length, chunked bodies/trailers and close-delimited responses are
supported. HEAD, 1xx, 204 and 304 have their no-body semantics. At most 16
informational responses are forwarded before the final response.

**There is no implicit 64 MiB engine body cap.** Its default limits are:

```kotlin
Http1Limits(maxHeadBytes = 65_536, maxBodyBytes = Long.MAX_VALUE)
```

The prerequisite codec's standalone default remains 64 MiB; that is not the
engine default. `NativeProxyOptions(limits = ...)` exposes the complete policy.
The default per-line limit remains 8 KiB, header count 100, trailer section
16 KiB, and transfer buffer 8 KiB. Integrators must consciously choose line,
head, trailer, byte quota and deadline policies. A 65,536-byte aggregate head
limit alone does not make this policy identical to Node's maxHeaderSize.

A finite `maxBodyBytes` is a **streaming byte quota**, not a buffering size:
known oversized lengths fail before forwarding, while excessive chunked or
close-delimited data fails when detected. Once a final response has started,
an error or quota violation terminates the connection instead of appending
another HTTP response. Small error bodies are omitted when the configured
quota is too small even for their fixed text.

Multiple sequential HTTP requests work on plaintext or decrypted TLS
connections, including requests already buffered behind the preceding body.
There is **no upstream connection pooling**: each request opens a fresh TCP
connection, optionally handshakes TLS, sends `Connection: close`, and closes
that transport after the response. Upgrade requests instead send
`Connection: Upgrade` so a validated 101 can retain that transport.
A self-delimited upstream response can
still leave the downstream connection persistent. A client/transformation
close request, request-count limit, or close-delimited response closes the
downstream connection. Normal downstream TLS completion sends close_notify,
with a raw socket abort deadline if graceful closing blocks.

For HTTP/1.0 clients, a chunked upstream body is decoded, Transfer-Encoding
and Trailer removed, and downstream EOF delimits the body. The connection is
closed. There is no concurrent HTTP multiplexing on one connection.

## Concurrent uploads and Expect

Each body-bearing request uses at most one helper in a separate fixed-size
I/O pool while its connection worker reads upstream responses. Both directions
use fixed buffers. Request heads are flushed before upload handling starts;
an upstream 403/413 can be read and relayed while the client is withholding
body data or a body writer is blocked by upstream backpressure.

A final response observed before body completion cancels further upload
writes and sends downstream `Connection: close`. The final response body is
relayed before both transports close and the helper is joined. Remaining
request data is not forwarded, retried, replayed, or interpreted as another
request. After the final response is flushed, the proxy half-closes its
response output and allows a bounded terminal discard/close grace
(`earlyResponseCloseTimeoutMillis`, default 250 ms). This prevents unread
upload bytes from resetting TCP before the client receives the rejection
body. Terminal discarded bytes are never forwarded or parsed as a new
request. Data already in a socket write when a final is observed may have
reached the upstream; cancellation cannot revoke bytes previously sent.
If the complete request body was already consumed, normal downstream
persistence remains available after the helper has completed.

For HTTP/1.1 `Expect: 100-continue`, only the head is forwarded initially. A
bounded helper waits for an actual upstream 100. Upstream 103/other interim
responses are relayed without opening the body gate; the upstream 100 is
flushed to the client before the helper can consume body bytes. There is no
optimistic synthetic 100 or timer fallback. An upstream final response before
100 cancels the gate without passing even an eager buffered body to the
request-body codec/upstream; terminal close grace may discard transport data.
An origin that never supplies 100 or a final response reaches the configured
response/exchange timeout. Clients that choose to send body data early can
leave it buffered until the upstream permits it.

Unknown/multiple expectation values return 417 before contacting the origin.
HTTP/1.0 100-continue is ignored and stripped on forwarding. A bodyless
100-continue request does not create an upload gate. CONNECT with Expect is
explicitly rejected with 417.

While an ordinary upload is still progressing, a response head can be
received at any time, but its wait does not consume the separate response
timeout before the origin has the body. `NativeResponseWait` arms that
deadline once the upload helper finishes, or while waiting for explicit
100 permission. The total exchange deadline always remains active.

Client request-body parse/read failures are represented as
`NativeClientFailure` (400/408). Upstream write/handshake/response protocol
failures retain upstream identity and produce 502 or timeout 504 before
commit. The worker is the sole writer of HTTP responses; upload helpers never
write errors or informational responses to the client.

## CONNECT and HTTP Upgrade handoff

MITM issues the downstream leaf for the validated CONNECT host. It sends 200,
then layers a server-mode JSSE socket using the **consumed-input overload**:
`createSocket(rawSocket, httpReader.handoff(), true)`. All pre-read bytes are
retained, including a ClientHello sent in the same write as CONNECT. TLS is
terminated locally, and decrypted HTTP is parsed and forwarded to a separate
upstream connection. The tests verify that a localhost TLS origin actually
receives the decrypted request path; this is not just a successful tunnel.

TUNNEL forwards the same buffered handoff input unchanged using two bounded
directions and propagates TCP half-closes. A helper is reserved before 200,
and its writes wait until 200 has been flushed, so early upstream bytes cannot
precede the CONNECT response. BLOCK returns 403 without contacting the origin.
After a CONNECT success, downstream TLS/tunnel failures close the connection;
the engine cannot send a new plaintext HTTP error through that handoff.

HTTP/1.1 Upgrade is supported on plaintext connections and inside decrypted
CONNECT TLS. Upgrade and `Connection: upgrade` must appear together; protocol
names/optional versions must be valid tokens. A selected upstream 101 must
include matching Connection/Upgrade fields, select only offered protocols,
and contain no HTTP body framing. An unsolicited/malformed upstream 101 is
502; malformed client negotiation is 400. A 101 received before a generic
Upgrade request body is complete is rejected with 502.

WebSocket negotiation additionally requires GET, version 13, a 16-byte
base64 key, and no request body. The engine verifies the upstream
Sec-WebSocket-Accept and any selected subprotocol against the client's offer.
Extensions and all WebSocket frames (including masking, fragmentation,
ping/pong and close frames) are passed as opaque bytes after negotiation;
the engine does not decode or rewrite WebSocket application data.

Both `Http1Reader.handoff()` streams are retained, preserving client frames
buffered after the request head and server frames buffered after the 101.
A bounded helper is reserved before 101 is emitted, and server bytes wait
until that head has been flushed. Independent pumps forward each direction
and propagate half-closes. Protocol-level WebSocket close frames pass through
unchanged; TCP/TLS EOF, optional deadlines and stop clean up both sockets.

There is no HTTP/2 or HTTP/3 request parser, retry, body replay, or upstream pool.
Generic negotiated Upgrade protocols are opaque after 101.
ALPN is left unset on both TLS legs for HTTP/1.1 fallback. There are no direct
calls to JDK 9+ ALPN framework methods and h2 is never advertised by the engine.
Opaque tunnel/upgrade bytes
are governed by socket/lifetime bounds rather than the HTTP body byte quota.

## Lifecycle, bounds and deadlines

The single-use lifecycle is NEW -> STARTING -> LISTENING -> STOPPED.
Synchronous bind/start failures record `failure`, transition to FAILED, clean
up resources and throw. A fatal accept failure also records FAILED and closes
active sockets and executors. Per-connection malformed clients return 400
without stopping the listener. Upstream errors before final response commit
return 502; upstream read/handshake/connect/exchange timeouts return 504 when
the downstream is writable. Idle client read timeouts return 408.

Only loopback binds are allowed. A fixed worker pool and bounded accepted
connection queue cap accepted work; saturation closes the excess socket.
Uploads, tunnels and upgrades reserve at most one additional helper each in
a separate fixed-size pool (same size as the worker pool, with at most that
many queued helper tasks), avoiding worker-pool deadlock. Helpers are joined
before a connection worker accepts another request. Shutdown also cancels
queued helper futures so they cannot strand workers waiting for completion.
The OS listen backlog is a hint,
not an exact OS-level queue size guarantee. Canceled timer tasks are removed
immediately rather than accumulating until their original deadlines.

Defaults: connect 5 s, handshake 10 s, HTTP read inactivity 15 s, exchange/header
lifetime 60 s, graceful close/stop budget 5 s; four workers, sixteen
queued connections and one hundred requests per downstream connection.
`duplexIdleTimeoutMillis` and `duplexTimeoutMillis` default to zero: long-lived
WebSocket/opaque tunnels have no engine-imposed idle/lifetime expiry.
Their workers, helper counts and buffers remain bounded, and stop closes them.
Callers can set explicit duplex inactivity/lifetime policies; those replace
the HTTP socket read timeout and exchange deadline after handoff.
The exchange deadline bounds blocked socket writes as well as reads. Before
response commit, an upstream expiry can return 504, including while a client
upload is stalled. If the client is stalled
in a response write, aborting its socket may preclude a status
response. After commit, timeout means connection termination. Large downloads
may need a longer exchange deadline even with an unlimited byte quota.

`close()` is idempotent: it closes the listener, queued/active raw sockets,
blocked TLS handshakes and upstream reads, cancels timers, shuts down pools,
and waits within the stop budget. `state`, `failure`, `localAddress` and
`activeSocketCount` expose lifecycle observations. An external resolver or
transformer that blocks outside socket operations can exceed that wait budget;
production adapters must honor their own cancellation contracts.

## Validation and future Android integration

Run `.\docs\native-proxy\run-tests.ps1`. The script uses cached Kotlin 2.0.21,
JUnit 4.13.2, BC 1.79 (`bcprov`, `bcpkix`, `bcutil`) and the configured JDK 21,
emits JVM 17 bytecode, and directly invokes the compiler and JUnit with
**`-Xmx256m` on each JVM**. It does not invoke Gradle, Android builds, devices,
external websites or application key material. Generated classes and logs
stay in ignored `build/native-proxy/`.

The 52 local integration tests cover verified MITM and independent identities,
coalesced CONNECT/ClientHello, two sequential HTTP and HTTPS requests, fixed
and chunked bodies, 65 MiB streaming, explicit quotas, HEAD/103/204, HTTP/1.0
decoding, close delimiting, strict wrong-host/untrusted rejection and continued
service, explicit per-route trust modes, null SNI, TLS 1.2/1.3, unchanged global
defaults, authority/transform validation, malformed framing, invalid
negotiation rejection, tunnel early bytes/half-close, BLOCK, read/handshake timeouts,
bind conflict and stop during blocked work/unfinished TLS.
The compatibility suite uses independent raw HTTP and masked/unmasked
WebSocket frame readers at its local peers, rather than the production
codecs. It exercises buffered frames in both directions, close frames, generic
Upgrade/half-close, verified TLS WebSockets, real 103/100 with a waiting client,
final-before-100 with withheld and eager buffered bodies, sequential requests
after Continue, paused fixed/chunked and verified TLS uploads, writer
backpressure with an origin that never drains the body, a progressing upload
that outlasts the response timeout, a completed upload with no response,
invalid upstream 101 as 502 with continued service, configurable duplex
deadlines, client cancellation/worker reuse, and stop during duplex/upload/
Continue waits with assertions that no new native-proxy threads survive.
An injected counting dialer delegates real local TLS, retains strict
wrong-host rejection, and proves the injection is used. Another injected
dialer disables its own socket timeout and blocks; the server's raw-close
handshake deadline still returns 504.
Hook tests verify local success/abort/302/OPTIONS with zero resolver and
upstream calls, trusted authority/path/route rewrite inside verified CONNECT,
continued rejection of untrusted authority changes, trusted framing-smuggling
rejection, invalid response-head edits as 502, and local-body bounds.
The 65 MiB streaming test now also verifies injected CORS/response headers.

Android minSdk 24 integration is future work. The certificate prerequisite's
`java.time` use needs core library desugaring. ALPN framework methods cannot
be made available by java.time desugaring; they are deliberately unused here.
Check the layered consumed-input SSLSocket behavior, TLS provider/protocol
availability, certificate generation latency, cancellation, BC dependencies,
and release bytecode/toolchain choices on Android before adopting this engine.
The direct JVM results are not Android/device acceptance. Root netcore Gradle
dependencies are unchanged; aggregation must supply the certificate module's
matching BC dependencies. No CA or key is persisted: tests use only
`EphemeralTestCa.create().authority()` and memory-only PEM strings.

## Copied prerequisite provenance

Copied read-only from the sibling worktrees, with no edits to their files:

- HTTP: `C:\Users\HONOR\.codex\worktrees\native-http-framing\dev-sidecar-lightning`
- TLS: `C:\Users\HONOR\.codex\worktrees\native-certificate-authority\dev-sidecar-lightning`

Paths below are relative to `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/`,
except the test helper. SHA-256 hashes are of the exact copied file bytes.

| File | SHA-256 |
| --- | --- |
| `http/Http1Models.kt` | `9522F1EA7E77C6347FEF9561BE4DDF3CBAFEC92CB522EAB74CF374F87E856CC0` |
| `http/Http1Codec.kt` | `6AB4EDA2A7F9969C792B6816C329B29D59800DCFD5AEDB44A2014215429A5B1F` |
| `http/Http1BodyCodec.kt` | `3A96B9449653FCBF779D9E4616B07C5EA5BFA396131DF5D2B6412577DCBE392E` |
| `tls/MitmCertificateAuthority.kt` | `E03DB2C7B4676F3E7C9C263638EFB724C276E25BC13172B57813B19DF30FE93A` |
| `tls/LeafCertificateCache.kt` | `C52F8EE13BB482A42DA28E5D948B31F1C34E1CDDB15BBB3F07EAEA38B2E2BA4D` |
| `tls/CertificateHost.kt` | `D3B64FE028E5B8F02A987D234E2BE16D9BBA0F71E57FB27C9B4C66224E592A5F` |
| test `proxy/tls/EphemeralTestCa.kt` | `620C5D24F051797D19614EF23D0916034B297E02AEEED0993A6E573A87393CC3` |
