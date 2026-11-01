# Native memory cache and attempt scheduling

This phase adds only new source/tests in
`com.dsha.sidecar.net.http.native.schedule`, a separate synthetic oracle, and
this document. RulePlanner, RuleActionExecutor, their prerequisites and their
89 accepted tests/fixtures are frozen. There is no runtime adapter, build-root
change, network, application operation, disk cache, or device integration.

## Stable composable APIs

```kotlin
val input = ScheduledRequest(
    method = "GET", scheme = "https", host = "example.test", port = 443,
    path = "/file?q=x", headers = originalHeaderLines,
    policy = PolicyEpoch(policy = "rules", epoch = configGeneration),
)
val cache = MemoryResponseCache(injectedClock, CacheLimits())
val hit = cache.get(input, cacheDescriptor)

when (val decision = cache.beginCapture(input, cacheDescriptor, upstreamHead)) {
    is CaptureDecision.Bypass -> forwardWithoutCapture()
    is CaptureDecision.Capture -> {
        // Called by the streaming transport, not by this cache:
        forwardChunkToClient(chunk)
        decision.capture.append(chunk) // false drops caching; forwarding continues
        // Only after complete successfully framed EOF:
        val result = decision.capture.complete(cancellation)
    }
}

val scheduler = RequestAttemptScheduler(AttemptLimits(), BackupSelector())
val replay = ReplayableRequest.empty(input)
val outcome = scheduler.execute(replay, retryDescriptor, backupDescriptor, cancellation) { context, body ->
    // Caller-owned transport uses context.target, resolves DNS/routing, and obeys cancellation.
    AttemptResult.Response(status = 200, value = responseHandle, release = { responseHandle.close() })
}
```

These are synchronous APIs on the caller's execution context. They create no
thread pool, future, timer, sleep, socket, or DNS request. A runtime can invoke
them on its own bounded executor and wrap the result in its own future.
Cancellation is cooperative through ScheduleCancellation and interruption.
The injected attempt must honor deadlines/cancellation during blocking I/O;
the scheduler cannot preempt a callback that ignores them.

Other entry points:

- `CacheCompatibility.conditional304(request, descriptor, nowMillis)` reproduces
  source timestamp-cache decisions without relying on a stored entity.
- `CacheCompatibility.responseHeaders(request, descriptor, head, nowMillis)`
  applies the source cache header policy separately from storage.
- `ReplayableRequest.eligibleToBuffer`, `buffer`, and `oneShot` distinguish
  complete bounded replay from a single caller-owned stream.
- `BackupSelector.select(request, descriptor)` returns a candidate plus a
  failure/snapshot lease. `invalidate()` revokes state and late feedback.
- `MemoryResponseCache.invalidate()` clears entries and revokes active captures.
  Size/byte/capture counters are available for tests and integration telemetry.

The caller supplies the same logical request/descriptor through capture and
lookup, and increments PolicyEpoch on any relevant rules/config change.
The policy string identifies independently configured proxy sessions. Keys
separate scheme, canonical host, port, method, full path/query, policy/epoch,
cache descriptor, and any private partition/credentials.

## Actual upstream boundary

The source cacheRequest.js stores no response entities. For GET with a usable
If-Modified-Since value it compares the supplied timestamp with Date.now and the
configured duration, then directly returns 304. Request no-cache/no-store and
Pragma bypass this branch. It accepts the exact duration boundary and future
timestamps. Compatibility mode preserves this behavior; it is not proof that
the client or this proxy has a valid cached representation.

cacheResponse.js rewrites GET cache headers for its configured status range,
default 200-303, except excluded statuses. It emits configured max-age plus
one, Last-Modified now, and Expires now plus the duration. It can retain a
larger upstream max-age or skip rewriting an already immutable longer response.
It does not add Date/Age or inspect Authorization, cookies, no-store/private
for entity storage. CacheCompatibility preserves these normalized-descriptor
operations and does not secretly create entity entries.

The new MemoryResponseCache is explicit native functionality. Its conservative
storage policy is deliberately stricter than the browser-cache rewriting.
Use the original upstream headers for storage eligibility/freshness, before
compatibility rewriting; otherwise a rewrite could hide original no-store,
private, or age restrictions.

The immutable descriptor is a Boolean. A source raw string `"false"` is not
equivalent to Boolean false in one larger-max-age branch of cacheResponse.js,
and the frozen normalized descriptor cannot represent that distinction.
Compatibility claims cover normalized Boolean policies, not arbitrary
JavaScript coercions. Dates accept numeric millisecond request timestamps and
RFC 1123 HTTP dates; JavaScript's additional legacy date syntaxes are not
implemented.

## Bounded memory entity cache

Default limits are 256 entries, 1 MiB per encoded body, 16 MiB total retained
logical bytes, 16 active captures, 4 MiB aggregate capture body bytes, and a
seven-day maximum TTL. Limits have validated hard maxima. LRU uses access order.
Total retained weight includes body, headers, Vary metadata, and a fixed key/
entry allowance. This is a logical byte budget, not an exact JVM heap estimator:
object/string/array overhead and transient defensive copies also consume
memory. Entry/count/header/capture bounds keep that overhead bounded.

Capture is an observer of successfully forwarded entity chunks. It does not
read a stream or write a client response. Append overflow releases its buffer
and records a bypass; it never truncates successful forwarding or pretends an
incomplete body is a complete cache entry. A declared oversize Content-Length
bypasses capture before allocation. A successful complete validates the exact
declared length, status/body constraints, remaining TTL, cancellation, generation,
and total entry weight before atomic publication. Abort/close never publishes.
Completion/append on a revoked handle cannot repopulate the cache.

Capture contains exactly the encoded, framed/dechunked representation; no
compression decoder is involved. Content-Encoding and encoded entity bytes
survive storage. Stored heads remove transport hop fields, set exact
Content-Length (except 204), and preserve duplicate end-to-end fields.
Partial 206 responses, Content-Range, trailers, malformed CL/TE, unsupported
transfer coding, and Vary `*` are not stored. The engine still owns streaming,
framing validation, transport timeouts, and disposal.

Vary request field names/values are compared case-insensitively by name and
exactly by ordered values, preserving absent versus empty fields. Accept-Encoding
is always part of the variant boundary, even if the origin forgot Vary, to avoid
returning a gzip/br representation to an incompatible client. A changed Vary
field set removes old variants of that resource.

Only bodyless GET requests enter storage. Range and request conditional headers
bypass rather than getting an incorrect unconditional hit. Request no-cache,
no-store, max-age=0, and Pragma no-cache/no-store bypass storage and lookup.
Response no-cache/no-store and private policies are respected by default.
This is freshness-only caching, with no stale serving or automatic revalidation.
Positive request max-age and min-fresh restrictions also constrain hits.

PUBLIC_ONLY excludes Authorization, Cookie, response private, configured private,
and Set-Cookie. ISOLATED_PRIVATE requires a nonempty privatePartition for every
lookup/capture and still excludes Set-Cookie. The base key also hashes actual
Authorization/Cookie values, so a partition alone cannot mix credentials.
Credentials are not logged or exposed as base-key text. Vary-selected values
remain in private in-memory variant metadata. Neither mode reads or persists
configuration secrets. Private-mode opt-in belongs to trusted runtime policy.

Initial age is the greater of apparent Date age and upstream Age plus known
response delay. `beginCapture(..., requestSentAtMillis)` lets the caller provide
that delay; if omitted, transport delay before capture is unknown and treated
as zero. Capture duration and residence time continue aging the entry.
Freshness is the minimum of descriptor TTL, configured hard TTL, upstream
max-age (s-maxage preferred in public mode), or Expires where no max-age exists.
Malformed/ambiguous Age/Date/max-age bypass. Missing Date is filled at receipt;
hits preserve Date and emit increasing Age. At exact expiry a native entry is
stale, unlike the original timestamp helper's inclusive comparison. Clock
rollback before receipt invalidates that entry.

`invalidate()` is linearized with lookup/publication under one lock, releases
all active buffers, and prevents old handles from publishing. A hit already
returned to an engine before invalidation is an independent byte snapshot;
invalidation cannot revoke bytes already sent. New epochs miss old entries,
which remain bounded until eviction or explicit invalidation. No deduplication
or waiting for another capture is implemented.

## Replay and retry execution

`ReplayableRequest.buffer` first validates eligibility and then reads at most
1 MiB of an exactly framed entity. Known Content-Length must match the complete
body. Chunked requests, Expect:100-continue, oversized bodies, ambiguous lengths,
and unknown-length body methods cannot be replay-buffered. Partial/error/
overflow/cancellation produces no replay token. The caller must not forward a
partially consumed original after buffer failure. The input stream is never
closed by buffering. Empty bodies use `empty`; single-pass forwarding uses
`oneShot`, whose stream can only be opened once and remains transport-owned.

Completed replay tokens snapshot input headers and body. Each retry receives
a fresh independent body stream. Scheduler-owned buffered attempt streams are
closed after each attempt; caller-owned one-shot streams are not. Request
buffer construction must itself run within the runtime's admission/memory
budget; the scheduler's active replay quota begins at execute and does not
govern callers constructing/retaining arbitrary tokens outside it.

Configured retries recheck methods, body eligibility, descriptor body limit,
successful buffering, and count/status configuration. The default hard cap is
eight retries, 32 concurrent exchanges, 1 MiB replay per exchange, and 8 MiB
active replay payload across scheduler calls. Capacity overflow fails before
transport; no unbounded queue is created.

The original loop shares one count between retryable transport errors and
configured status retries. It discards/resumes a retry response before trying
again. The native scheduler uses the same shared budget and calls release()
on every discarded response; final response ownership transfers to the caller.
Exceptions thrown by the attempt or release callback propagate and free
budgets; they are not blindly converted to retryable failures. Explicit
AttemptResult.Failure must say retryable and responseStarted=false.
Cancellation/interruption never retries and interruption preserves the flag.

Upstream implicitly retries one connection error for GET or some buffered
requests even without a retry descriptor. Native implicit retries are limited
to completely replayable GET/HEAD/OPTIONS. Status retries require an active
configured descriptor. One-shot/chunked/Expect requests never retry.
POST/PATCH after possible delivery or a status response require BOTH configured
method permission and explicit allowNonIdempotentReplay=true from a caller
that has a server-side idempotency guarantee. By default, configured buffered
POST may retry only a failure confirmed requestMayHaveBeenSent=false. This
deliberately tightens upstream behavior; no scheduler can infer or guarantee
server-side deduplication from a buffered body alone.

## Backup choice and feedback

BackupSelector implements the actual DynamicChoice transition semantics:
decreasing initial seed totals, initial selected-candidate count, proxy-start
count, first-error switch/reset while retaining success rate, stable stored-rate
ranking after exhaustion, duplicate-candidate behavior, and numeric key order.
It is not round-robin or a newly invented score. Snapshot oracle tests compare
the complete counts/rates against the original JavaScript class.

Defaults are 128 LRU keys and at most 16 candidates per configuration. Keys
include policy/epoch, scheme, host, port, source counterKey, and ordered candidate
configuration. Eviction/invalidation makes old feedback leases inert. Feedback
from a late older attempt can lower that candidate's score without switching
the current different candidate.

As in the original proxy request interceptor, selection happens once before
the retry loop. Retries keep the same target; feedback changes future exchanges.
Errors, slow responses, and a final status >=400 feed failure. A transient
status retried before a successful final response is not counted as a final
status failure. The caller supplies slow=true from its own timing policy;
the selector has no clock.

Candidate strings are unexpanded original proxy target configurations, not
necessarily resolved URLs. The attempt adapter must resolve placeholders,
route, Host, SNI, verification host, ECH, and DNS consistently for the selected
candidate. No authority change is applied by this module. Selection does not
turn backup into an immediate per-error failover; changing that would differ
from the actual upstream loop.

## Assembly responsibilities

The frozen RuleActionExecutor still rejects pending scheduler descriptors.
Do not disable that guard globally. An adapter must execute selected request
steps in priority order, including cache lookups before later routes/terminal
actions; explicitly account for each descriptor it has actually handled; then
call the appropriate action primitive on a plan projection containing only
handled steps removed. A cache capture is downstream of a successful upstream
response; its header policy has the original response-interceptor gate.
Neither this module nor the frozen executor automatically wires that ledger.

Local terminal responses bypass attempts. A cache hit must precede network
dispatch. On a miss, a scheduler must preserve the real body and not retry
after downstream headers/body start. Early/discarded attempt responses must be
released by the transport. ScriptInjection/Brotli insertion, asset serving,
WebSocket, disk persistence, distributed cache, stale revalidation, and arbitrary
server-side JS remain outside this phase.

## Source oracle and verification

New port/tests/fixtures/native-schedule/schedule-oracle.cjs supports:

```powershell
node --max-old-space-size=128 port/tests/fixtures/native-schedule/schedule-oracle.cjs --verify --upstream '<mitmproxy/src>'
node --max-old-space-size=128 port/tests/fixtures/native-schedule/schedule-oracle.cjs --generate --upstream '<mitmproxy/src>'
```

Verify checks saved source pins/synthetic input definitions, executes original
cacheRequest/cacheResponse, original DynamicChoice, and the original retry
loop/eligibility function extracted at checked source boundaries. Only fake
attempts, logging, and time are injected. Expected state/output is never
handwritten; UpstreamScheduleOracle.kt is generated for parser-free JVM tests.
Generate updates only new schedule artifacts explicitly; verify writes nothing.
The original cache code has no entity-store oracle, so native memory-cache
tests validate its documented storage invariants rather than claiming Node
response-storage parity.

All main sources plus scheduler/action/planner tests are compiled using the
original JDK 21 and cached Kotlin 2.0.21, one compiler process with 256 MiB heap.
JUnit 4.13.2 runs afterward with 128 MiB heap. No Gradle or phone. Existing
source fixtures and frozen production files are checked against their hashes.
The source-hashes.json manifest records exact paths and SHA-256 provenance;
no private/full configuration is copied.

Final verification on 2026-11-02 passed 146 tests: 57 new scheduling tests
(20 original-source oracle cases and 37 cache/replay/feedback boundary tests)
plus all frozen 89 planner/action tests. The schedule oracle verified 20 cases
and six source pins without changing artifact hashes or timestamps. Five
additional isolated failure checks confirmed missing/changed pins, changed
expectations/inputs, and a stale Kotlin fixture fail without artifact writes.
The only compile warning is the existing DnsClient deprecated URL constructor.

All inspected original sources are under
`D:/File/User/code/dev-sidecar-lightning/port/.run/offline-proxy-fixes-20261101/runtime/core/node_modules/@blue-frontier/mitmproxy/src`.
No Kotlin DynamicChoice prerequisite was copied or redefined: BackupSelector
has private state adapted from the JS class, independently checked against its
original snapshots, and introduces no duplicate package/class definitions.

| Original source | SHA-256 |
| --- | --- |
| lib/interceptor/impl/req/cacheRequest.js | fd7e10662322ed281cd03f174eb5d5aefeeca7579dbca200b1f85da3042f1bbd |
| lib/interceptor/impl/res/cacheResponse.js | 3937590e33f9fe699ae1bf37270ef72e238988c9ab57364f8411f6e206debc48 |
| lib/choice/index.js | 64626417b13025dcd7e93e1d3046c7483428883376d94f699668de3ff3e15d96 |
| lib/interceptor/impl/req/retry.js | 6d64453ecd15c78391629e14eefc333439645c216d0dc49ceb4e80790841372f |
| lib/proxy/mitmproxy/createRequestHandler.js | 64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690 |
| lib/interceptor/impl/req/proxy.js | bdf69b89e9fac46a39d91164c9be3f37537667c50b8b2ef13ae84308bfd73f39 |
