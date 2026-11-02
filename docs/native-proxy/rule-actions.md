# Bounded rule action execution

This module adds execution over the frozen RuleRequest/RequestPlan/ResponseAction
models without changing the planner or its prerequisites. It has no network,
DNS, clock, TLS/trust mutation, application hook, external asset loader, new
dependency, or build configuration. Engine policy assembly is separate.

New production package: `com.dsha.sidecar.net.http.native`.
Sources: RuleActionExecutor.kt and RuleActionModels.kt at their canonical package
paths. The frozen planner and its original 35 fixtures remain unchanged.

## Stable API

```kotlin
val executor = RuleActionExecutor(ActionLimits())

// No request body is read or replayed. Routes/rewrites were resolved by the planner.
when (val result = executor.executeRequest(plan, request, dateHeader = null)) {
    is RequestExecution.Forward -> consume(result.request)
    is RequestExecution.Terminal -> result.response.writeHttp1(output)
}

// Large-download path: no entity argument, no body read, no body-buffer quota.
val streaming = executor.transformResponseHead(
    plan, request, upstreamHead, responseInterceptorsEnabled = actualScriptEnabled,
)
consume(streaming.head)
// The engine continues streaming its original entity with its established framing.

// Optional bounded entity path, primarily for explicitly resolved literal insertion.
val prepared = executor.prepareResponse(
    plan, request, upstreamHead, framedEntityStream,
    responseInterceptorsEnabled = actualScriptEnabled,
    append = ResolvedHtmlAppend(head = trustedLiteralHead, body = trustedLiteralBody),
)
prepared.writeHttp1(output)
```

`executeResponse(...)` is shorthand for prepareResponse followed by writeHttp1.
It cannot write any headers/body before preparation succeeds. I/O failures
while writing an already prepared response can still partially write to the
caller-owned sink. No caller stream is closed or flushed.

`ActionResponseHead(status, headers: List<ActionHeader>, reason)` retains
duplicate field lines, including Set-Cookie. The module takes final responses
only, status 200-599. Protocol conversion, interim responses, CONNECT, Upgrade,
WebSocket, trailers, and raw HTTP parsing remain transport responsibilities.
The prepared wire writer deliberately emits HTTP/1.1; do not use it as a generic
protocol serializer.

RuleActionException carries an ActionFailure and action name. Unsupported
plans/actions, unsupported encoding, malformed bodies, header/body limits,
and invalid input fail explicitly. No unsupported descriptor is silently
treated as executed.

## Request execution and static responses

Forward returns ResolvedRequest with the planner's primary route, method,
resolved path/headers, TLS policy data, and ECH flag. It does not resolve DNS,
connect, apply trust, or select backup candidates. The integration must supply
the matching original RuleRequest used to produce the plan. It must also map
route authority changes through the trusted policy boundary; this module does
not weaken an engine's ordinary transformer authority check.

Request Content-Length, Transfer-Encoding, and Content-Encoding must remain
identical to the original request. Their modification requires a request body
executor. Header names/values and resolved origin-form request targets are
validated before handoff.

Terminal returns PreparedActionResponse for success/abort/redirect/preflight.
Static strings become UTF-8 bytes with exact Content-Length. HEAD sends no body
while retaining representation length; 204 has neither body nor Content-Length.
The executor rejects inconsistent configured lengths and non-identity static
Content-Encoding. OPTIONS requires an explicit valid RFC 1123 Date from its
caller when addDateAtExecution is set; no clock is consulted.

Pending cache lookups, enabled retry policies, backup ranking, and unknown
request implementations fail rather than bypassing conditional work. A disabled
retry descriptor carries no execution requirement. The engine or later
scheduler must implement these actions before enabling their plans.

## Streaming head path

transformResponseHead has no body parameter. Its response is a StreamingActionHead,
with Content-Length, Transfer-Encoding, and Content-Encoding fields unchanged
from the upstream head. It does not apply any input/output body-buffer quota.
Changing any of those fields through a rule fails before body I/O. Repeating
the exact existing Content-Length is accepted.

The engine retains its existing parser/framing state and streams the original
body. This API does not serialize, dechunk/rechunk, consume trailers, or convert
protocols. It preserves hop headers for the engine's normal transport policy;
the adapter must preserve status/protocol/framing when mapping this head.
Ambiguous Content-Length, CL+TE, unsupported transfer coding, and explicit
trailers fail rather than being guessed.

A test supplies Content-Length 65 MiB with all executor body-buffer limits set
to zero. Head transformation performs zero source reads, then a counting sink
receives all 65 MiB using the original stream. This confirms header action
execution is independent of bounded body preparation.

## Supported response actions

The caller must pass the actual `setting.script.enabled` value via
responseInterceptorsEnabled. The pinned createRequestHandler.js gates the
entire response-interceptor loop on that flag, including CORS/header actions.
false skips those selected response actions as the source does. Planner issues
still fail; the flag is not a way to hide unsupported configuration.

- CORS uses the unchanged HeaderTransforms.afterOptionsHeaders, including
  Vary handling, origin, credentials, and Cross-Origin-Resource-Policy.
- Replace runs only at its specified status (normally 200). It uses the
  unchanged original header transformation rules and source diagnostic fields.
  Existing `[remove]` response fields become empty values, matching upstream,
  rather than silently changing to physical removal.
- Download naming follows the source regex and encodeURIComponent behavior.
  Existing percent escapes are encoded again (for example `%20` becomes
  `%2520`). Missing Content-Type defaults to application/octet-stream.
  When no cache policy exists, Cache-Control/Last-Modified/Expires receive
  source removal markers.
- The pinned responseReplace.js has no text/body replacement rule. No such
  configuration is invented here.

## Bounded HTML primitive

ResolvedHtmlAppend is explicitly resolved literal insertion. It is not an
implementation of the planner's ScriptInjection descriptor and does not waive
its unsupported planning issue. It offers reusable byte insertion/compression
for a later trusted asset resolver without executing arbitrary configuration
as server-side JavaScript.

The primitive follows InsertScriptMiddleware's case-sensitive HTML MIME gate,
content-length-zero skip, lower/uppercase closing tag preference, head/body
insertion order, and CSP script-src/script-src-elem self adjustment. Report-only
CSP is unchanged. It does not impose a status-200 gate absent from that middleware;
responseReplace still has its independent 200 gate.

Body insertion operates on bytes, never decodes/reencodes the existing document
as a String. UTF-8 and ASCII declared charsets are supported; other declared
charsets and malformed insertion Unicode fail. Non-ASCII append with an ASCII
charset fails. Arbitrary opaque bytes pass through exactly when no insertion
runs. Compressed gzip and zlib-wrapped deflate are decoded and reencoded within
separate limits. Content-Encoding remains correct and final Content-Length is
the encoded byte count.

Whole-entity spooling deliberately fixes the source middleware's per-chunk
closing-tag miss: tags split across input reads are found once, not dropped.
For parity, oracle cases use one original upstream input chunk. Compressed
output need not be bit-identical across zlib/JDK versions; decoded bytes and
encoding/length consistency are verified.

No mature cached Brotli encoder/decoder pair is used, and no dependency is
added. Brotli insertion fails with UNSUPPORTED_ENCODING before output. An
untouched Brotli entity remains byte-identical and can use the streaming head
path. Multiple/unknown encoding transforms also fail.

Limits default to 1 MiB encoded input, 4 MiB decoded/encoded output, 64 KiB
append, 64 KiB headers, and 256 field lines. Configurable hard maxima prevent
unbounded allocations. Preparation holds bounded byte arrays, may use multiple
copies during decode/insert/encode, and is not suitable for large downloads.
Use transformResponseHead for those. Entity streams must end at the transport's
already framed/dechunked boundary, not at socket closure. The caller owns
timeouts/cancellation and must not pass a live raw keep-alive socket.

Prepared output removes hop-by-hop fields (including Connection nominations),
removes TE after spooling, and supplies exact Content-Length. HEAD/304 preserve
metadata length without sending a representation. The byte limit, truncated
entity, invalid framing, charset, compression, and action checks all complete
before writeHttp1 emits headers.

## Still unsupported

ScriptInjection needs builtin asset lookup, script-serving routes, tampermonkey
assets, URL/config resolution, nonce integration, and the original script.js
special cases. Those are not provided by the literal primitive. No original
script assets or private/full configurations are packaged. Arbitrary server-side
JavaScript execution is not equivalent and is not supported.

Cache response dates/304 decisions, retry buffering/replay, backup ranking,
DNS resolution, engine policy adapters, and browser application remain separate.
This module does not claim that ordinary script-enabled default configurations
are fully executable. The script gate remains open work.

## Original-function oracle and validation

New fixtures are separate under port/tests/fixtures/native-actions. Their
action-oracle.cjs supports:

```powershell
node --max-old-space-size=128 port/tests/fixtures/native-actions/action-oracle.cjs --verify --upstream '<mitmproxy/src>'
node --max-old-space-size=128 port/tests/fixtures/native-actions/action-oracle.cjs --generate --upstream '<mitmproxy/src>'
```

Verify is read-only, checks pinned sources/synthetic inputs, reruns original
functions, and compares JSON plus generated Kotlin data. Generate explicitly
refreshes only these new action artifacts. The 35 frozen planner fixtures,
their generator, source pins, and generated Kotlin file are unchanged.

The action oracle executes original responseReplace, AfterOPTIONSHeaders, and
InsertScriptMiddleware with actual Node streams/zlib. It stubs logging and
asset lookup; no script asset/config is read. Four executed and two inspected
source files are SHA-256 pinned in source-hashes.json. Inputs are 14 synthetic
cases for headers/download/CORS/gates, gzip/deflate/identity insertion, untouched
Brotli, non-HTML skips, uppercase/missing tags, Unicode, and CSP.
Generated UpstreamActionOracle.kt allows JVM tests without a JSON parser.

New boundary tests include engine-like HTTP/1.1 wire head/body bytes, 65 MiB
streaming without executor reads, invalid framing, opaque invalid UTF-8,
input/decode/append/output limits, decompression bombs, malformed compression,
split tags, stream ownership, charset failure, method/status body constraints,
static responses and explicit Date, and unsupported action rejection.

Verification uses the original JDK 21, cached Kotlin 2.0.21/JUnit 4.13.2, direct
compilation of all netcore main sources and planner/action tests, one compiler
process with 256 MiB heap, then one JUnit process with 128 MiB heap. No Gradle,
application/device operation, build-root modification, or engine code copy.
The existing DnsClient URL-constructor deprecation warning is unchanged.

Final result on 2026-11-02: 89 tests passed: 14 original-function action
fixtures, 25 executor boundary tests, and the frozen 50 planner tests. Both
read-only oracles passed (14/6 action cases/source pins and 35/22 planner
cases/source pins). Five additional isolated action-oracle failure checks
verified missing/changed pins, changed expected data, changed synthetic inputs,
and stale Kotlin fixtures fail without writing artifacts. The action verify
run preserved both expected artifact hashes and modification timestamps.

The action source manifest records these SHA-256 values, relative to the
original local mitmproxy src:

| Source | SHA-256 |
| --- | --- |
| lib/interceptor/impl/res/responseReplace.js | 3b5a7dc465049e19980a955d88c391c269ee0cd65fb85b4d910a94aa5788d10c |
| lib/interceptor/impl/req/cacheRequest.js | fd7e10662322ed281cd03f174eb5d5aefeeca7579dbca200b1f85da3042f1bbd |
| lib/interceptor/impl/res/AfterOPTIONSHeaders.js | d455246bffa630ea7e34f86e71bb58de0a9d5f82c0c372ee2f9961e47ac4ae72 |
| lib/proxy/middleware/InsertScriptMiddleware.js | bfd3aa4bcdeda4982e588568effb6ca45a5c765308879a3b6646d6dc16ba9761 |
| lib/interceptor/impl/res/script.js | 0c38b1327ec5e3bcb27331b203ca6443d1c64a81ec4ac54dcd1791b1c70c7d03 |
| lib/proxy/mitmproxy/createRequestHandler.js | 64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690 |
