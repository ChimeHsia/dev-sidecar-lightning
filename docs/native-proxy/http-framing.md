# Isolated JVM HTTP/1 framing candidate

Status: public API frozen for prerequisite integration; no pending API additions.
This candidate changes no existing files or runtime wiring. It has no listener,
socket creation, routing, TLS, Android, Bouncy Castle, JavaScript, or Node dependency.
Production continues to use the existing proxy until a separate integration is
accepted. The existing `LocalProxyServer` and its tests were read as context:
its head-then-raw-relay behavior is not used as an HTTP message framing engine.

## Public API

All classes are in `com.dsha.sidecar.net.proxy.http`. Runtime dependencies are
Kotlin stdlib and JDK classes only. Blocking I/O exceptions propagate to the caller.

| API | Contract |
| --- | --- |
| `Http1HeadCodec.readRequest(input, limits)` | Returns `Http1Request?` containing `head` and `framing`; null only at clean EOF before a new start line. |
| `Http1HeadCodec.readResponse(input, requestMethod, limits)` | Returns one `Http1Response?`, including informational responses. The caller supplies the exact originating method. |
| `Http1HeadCodec.writeRequest(output, head, limits)` | Validates and buffers a bounded complete head before emitting any bytes. |
| `Http1HeadCodec.writeResponse(output, head, requestMethod, limits)` | Same preflight contract; validates response framing in the originating method's context. |
| `Http1Framing.request(head, limits)` / `response(head, requestMethod, limits)` | Computes framing; already called by head readers and writers. |
| `Http1BodyCodec.transfer(input, output, framing, limits, mode)` | Transfers exactly one body; returns `Http1BodyResult(bodyBytes, trailers)`. |
| `Http1BodyCodec.writeChunked(input, output, trailers, limits)` | Encodes a finite EOF-terminated body source as chunks. Never pass an entire persistent socket input as this source. |
| `Http1Reader(input, limits)` | Optional convenience owner of a bounded read-ahead buffer; `readRequest()`, `readResponse(requestMethod)`, `transferBody(output, mode)`, `handoff()`. |
| `Http1Persistence.isPersistent(version, headers)` / `canReuse(request, response)` | Evaluates protocol persistence metadata on one hop; does not pool, read, send, or retry. |

Models are `Http1RequestHead(method, target, version, headers)` and
`Http1ResponseHead(version, status, reason, headers)`. `Http1Headers.fields` is
an immutable snapshot of ordered `Http1Header(name, rawValue)` entries, including
duplicates. `rawValue` preserves every byte after the colon, including leading
and trailing whitespace. Strings representing fields use ISO-8859-1 byte mapping;
`value` trims only SP and HTAB. `values(name)`, `tokens(name)`, and
`hasToken(name, token)` compare field names/tokens without case distinctions.
Token-list parsing rejects malformed and empty list elements. Methods remain
case-sensitive: `HEAD` and `CONNECT` have special semantics; `head` does not.

`Http1Target` has `Origin(raw)`, `Absolute(raw, authority, originForm)`,
`Authority(raw)` for CONNECT, and `Asterisk` for OPTIONS. Absolute HTTP/HTTPS
targets retain escaped path/query octets; an empty path becomes `/`, including
`http://example.test?x=1` -> `/?x=1`. The parser does not resolve a target.
To explicitly forward an absolute request as origin-form:

```kotlin
val target = request.head.target as Http1Target.Absolute
val originHead = request.head.copy(target = Http1Target.Origin(target.originForm))
Http1HeadCodec.writeRequest(upstreamOutput, originHead, limits)
Http1BodyCodec.transfer(clientInput, upstreamOutput, request.framing, limits)
```

This example illustrates framing only. The integrating engine must implement
authority/Host validation, Host replacement from an absolute target, hop-by-hop
field handling, and routing before sending anything. The codec preserves fields;
it does not silently rewrite or remove them.

## Stream ownership and persistent messages

`Http1HeadCodec` and `Http1BodyCodec` do **not** create buffers, close streams,
flush outputs, or read ahead past their own message boundary. They operate on
the exact caller-supplied streams. A caller may use a `BufferedInputStream`, but
must keep that **same instance** for every head, body, and subsequent protocol.
Do not switch from the buffer to the socket's raw input after parsing a head.

```kotlin
val clientInput = BufferedInputStream(socket.getInputStream(), limits.bufferBytes)
while (true) {
    val request = Http1HeadCodec.readRequest(clientInput, limits) ?: break
    // Decide how to process/forward this head. Send only when explicitly authorized.
    Http1BodyCodec.transfer(clientInput, selectedOutput, request.framing, limits)
    // The next read starts exactly at the next request line.
}
```

The integrating engine owns request/response association, request sequencing,
response completion, and socket lifetimes. It must not treat unsolicited extra
response bytes as a response without an outstanding corresponding request.
Informational responses are returned individually; a 100 or 103 does not finish
the exchange. `Expect: 100-continue` policy, interleaving, and early final responses
belong to the engine. There are no hidden sends, retries, drains, flushes, or
recovery attempts.

If using `Http1Reader`, do not access the original input while it is owned by the
reader. Each read leaves one pending body, including `None`. Call `transferBody`
even for `None` to acknowledge that boundary; reading another head or handing off
with an unconsumed body throws `IllegalStateException`. An I/O or framing error
permanently poisons the reader, including an output write failure. A completed
close-delimited response also makes it unavailable for reuse. State precondition
errors do not poison it. The reader is for sequential use by one owner and does
not close the underlying stream: the caller still owns connection cleanup.

## CONNECT and upgrade

`Http1BodyFraming` is one of `None`, `Fixed(length)`, `Chunked`,
`CloseDelimited`, or `Handoff`.

After a successful CONNECT response or a 101 response, framing is `Handoff`.
There is no HTTP body to copy. With the low-level codecs, pass the same input
instance directly to the TLS/tunnel/upgraded-protocol consumer. With
`Http1Reader`, call `handoff()` and use its returned input exclusively.
`handoff()` returns the reader's actual buffer with all prefetched bytes and
permanently detaches it; a second handoff or further reader operation fails.
The handoff does not close or flush either direction.

On the inbound CONNECT request side, a ClientHello may already follow the request
head in the same socket read. After accepting CONNECT and acknowledging its
`None` body, `reader.handoff()` retains that early TLS payload. The engine decides
when to send the CONNECT success response and start TLS. Similarly, 101 framing
merely reports a switch; the engine must validate that it requested and accepts
the advertised upgrade before handing off.

The exact same ownership rule applies if the caller supplied an already buffered
input to `Http1Reader`: keep using the stream returned by `handoff()`, not either
original underlying stream.

## Framing, forwarding, and limits

Supported versions are exactly HTTP/1.0 and HTTP/1.1. Content-Length is decimal
and bounded to a signed Long without conversion overflow. A request with neither
length nor transfer coding has `None`; it is never close-delimited. Responses to
HEAD and status 1xx (other than the 101 handoff), 204, and 304 have `None`
regardless of a syntactically valid length. Successful CONNECT responses hand off.
An ordinary response with neither length nor coding is close-delimited.
HEAD/304 length metadata is not subject to the body-byte limit because no body
is transferred.

`PRESERVE` is the default chunk mode. It streams exact chunk-size lines, extensions,
chunk data, CRLF delimiters, and trailer fields in their original order/case/
whitespace. `DECODE` streams only chunk data and returns trailer fields separately;
it does not rewrite the already parsed head. If decoding, the caller must adjust
Transfer-Encoding and the outgoing framing explicitly and keep trailers separate
unless a field's definition permits merging. `bodyBytes` counts payload bytes,
excluding chunk syntax and trailers. No compression coding is decoded.

| Default limit | Value |
| --- | --- |
| Complete request/response head, including start line, CRLFs and final CRLF | 32 KiB |
| Any start/field/chunk-size line, excluding its CRLF | 8 KiB |
| Head field count | 100 |
| Trailer section including CRLFs and final CRLF | 16 KiB |
| Trailer field count | 50 |
| Actual body bytes per message, aggregate across chunks | 64 MiB |
| Body copy buffer / optional reader input buffer | 8 KiB each |

Every limit is explicitly configurable via `Http1Limits`. Fixed bodies exceeding
the limit fail during head parsing. Chunked bodies fail before copying a chunk
that would exceed the remaining allowance; close-delimited bodies need EOF to
finish and can consume one extra byte to detect a limit violation. No body is
collected in memory. Heads, individual chunk lines, and trailer lists are bounded
by their configured limits; serialization uses bounded section buffers.
Caller-configured limits are resource policy, not protocol constants.

`canReuse` is only metadata eligibility after a **final response**. The caller must
also have successfully consumed both bodies and apply its connection policy.
HTTP/1.1 persists by default; HTTP/1.0 requires a keep-alive token; close wins over
keep-alive regardless of token case. Close-delimited and handoff responses are
never reusable. A proxy accepting an HTTP/1.0 client must additionally disable
persistence as required by RFC 9112 section 9.3; this generic helper does not know
the role of the connection. Socket deadlines/cancellation must be configured by
the caller: byte limits alone cannot prevent an indefinitely slow peer.

## Rejections and RFC basis

Primary specifications consulted: [RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html)
sections 2-7 (syntax/framing), 9.3 (persistence), and 11 (smuggling/splitting);
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) sections 5-6 (fields/trailers),
8.6 (Content-Length), 9.3.2 (HEAD), 9.3.6 (CONNECT), and 7.8 (Upgrade).
This is an independent implementation, not copied third-party source.

Deliberate restrictions for this candidate:

- Require CRLF, exactly separated start-line components, ASCII token field names,
  no whitespace before a colon, and no obs-fold, control bytes, or leading empty
  request lines. Optional whitespace in field values is preserved.
- Reject **all** repeated Content-Length fields and comma-list lengths, even when
  identical. RFC 9112 permits recovering identical values; this codec does not.
- Reject Transfer-Encoding together with Content-Length rather than choosing a
  precedence and removing a field. Apply these header ambiguity checks even for
  no-body and handoff responses. RFC 9112 normally requires ignoring CL/TE on
  successful CONNECT; this candidate deliberately rejects malformed/ambiguous
  framing metadata there before handoff.
- Accept only a single `chunked` Transfer-Encoding token on HTTP/1.1. Reject
  additional codings, parameters, repeated chunked, non-final chunked, and
  HTTP/1.0 transfer coding. In particular, a response with `Transfer-Encoding:
  gzip` is rejected rather than treated as close-delimited. Extending this
  would require an explicit transfer-coding contract.
- Reject empty elements in token lists instead of applying RFC list recovery.
- Require parseable HTTP/HTTPS absolute targets without userinfo/fragments and
  with a JDK-parseable host. CONNECT requires an explicit port in 1..65535;
  asterisk-form requires OPTIONS. URI authority validity/normalization for
  routing and HTTP/1.1 Host cardinality remain integration checks.
- Reject trailer names Content-Length, Transfer-Encoding, Host, Connection,
  Trailer, Upgrade, and Proxy-Connection regardless of case. This is a conservative
  framing-sensitive denylist, not an exhaustive application trailer policy.
  Other trailer fields remain separate and uninterpreted.
- Reject decimal/hex values exceeding Long, oversize sections or body lengths,
  malformed chunk extensions/terminators, and premature EOF. No repair or retry.

Raw header preservation is not a promise that every syntactically valid field is
safe to forward. In particular, the future engine must remove connection-specific
fields and enforce application-specific trailer rules before relaying them.

Body transfer is streaming and can already have emitted a prefix when an input
error, output error, limit, or bad trailer is discovered. On any such failure,
discard/close affected connections; do not retry or emit another response onto
that output. The low-level codecs are stateless and cannot prevent the caller
from incorrectly attempting reuse. Only the optional reader tracks poisoned state.

## Verification

Use JDK 21 and cached standalone Kotlin compiler 2.0.21 with `-jvm-target 17`,
`-no-stdlib`, `-no-reflect`, and explicit stdlib/JUnit/Hamcrest classpaths.
Only the three candidate production files and the two candidate test files are
compiled. The compiler JVM uses `-Xmx256m -XX:+UseSerialGC`; JUnit uses
`-Xmx96m -XX:+UseSerialGC`.

Exact test entry point:

```text
org.junit.runner.JUnitCore
  com.dsha.sidecar.net.proxy.http.Http1CodecTest
  com.dsha.sidecar.net.proxy.http.Http1BodyCodecTest
```

Recorded on November 2, 2026: `OK (30 tests)` (17 head/reader tests and 13 body
tests), with no compiler warnings. Compiled output is ignored under
`build/native-http-framing/tests.jar`, entirely inside the isolated checkout.

The tests use fragmented in-memory streams, a prefetched buffer, generated
bounded-read bodies, and failing output streams. No external network, loopback
server, phone, Android build, or Gradle is used. They cover concatenated requests
with fixed/chunked/empty bodies; informational/final responses; exact lengths;
header order/duplicates/raw bytes; URI forms; CRLF and size failures; CL/TE
ambiguities; no-body precedence; chunk syntax/extensions/trailers; body caps;
chunk encoding/decoding; persistence; reader poisoning; early CONNECT TLS; and
upgrade handoff with unread frames already buffered.

This verifies the isolated codecs. It does not claim live proxy, MITM, browser,
TLS, transport cancellation, or production integration acceptance.
