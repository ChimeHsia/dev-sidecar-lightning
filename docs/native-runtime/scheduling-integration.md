# Runtime scheduling integration: supported closure and engine gaps

November 2, 2026 rescue review: real TCP connect outcomes now feed the DNS
selection ticket exactly once. A failed connect can change the candidate for
the next exchange; the failed exchange still returns its single error response.
TLS/HTTP failures are not TCP connect failures. Retry/backup remain rejected at
configuration load. See `native-retry-rescue-review.md` for the reviewed scope.

This follows the completed 83-test options/assembly milestone in the same
isolated native-runtime-assembly worktree. No frozen engine, action executor,
planner, DNS, certificate, crypto, or scheduler source has been edited.

## Frozen handoff

Only these paths were copied from the scheduler worker:

- `netcore/src/main/kotlin/com/dsha/sidecar/net/http/native/schedule/`
- `netcore/src/test/kotlin/com/dsha/sidecar/net/http/native/schedule/`
- `port/tests/fixtures/native-schedule/`
- `docs/native-proxy/request-scheduling.md`

The 15 exact file hashes/source paths are in `schedule-prerequisites.json`.
The original 59-file prerequisite manifest remains unchanged. The copied
57 scheduling tests include 20 generated upstream-oracle cases. The worker's
146-test handoff also included 89 already-frozen planner/action tests; those
were not copied a second time or claimed as newly run here.

The original cacheRequest/cacheResponse sources were read from the pinned
offline proxy fixture and checked against the scheduler's source manifest.
No Node process, Gradle, application entry point, device, disk cache or
external website is involved.

## Actual runtime support

`NativeRuntimeCacheCompatibility` binds only the upstream
`CacheCompatibility` operations. It does not create MemoryResponseCache,
RequestAttemptScheduler or BackupSelector instances.

For a configured positive cache duration such as `cacheDays: 1`:

1. The adapter iterates the planner's ordered request steps.
2. OPTIONS preflight, success and abort retain their earlier priority.
   Their terminal plans contain no later cache lookup.
3. At the actual CacheLookup step, source-compatible conditional304 runs
   against the original method/headers, before requestReplace and proxy.
4. A hit returns an empty local 304 before route/DNS/upstream TLS. A miss
   is accounted for and only that handled descriptor is removed from the
   executor projection; other executor guards are not suppressed.
5. Later redirect/transform/proxy actions execute normally. In particular,
   a fresh timestamp can win before redirect, while no-cache lets redirect
   execute.
6. On final responses, the adapter flushes action batches around the Cache
   response step: CORS (201), cache headers (202), responseReplace (203).
   The actual `setting.script.enabled` gate applies to this response path.
   Disabling response interception does not disable request-side 304.

Unknown, script/body and unsupported scheduling actions are still audited.
The frozen executor itself retains its original rejection of unhandled
cache/retry/backup descriptors; only the runtime's explicitly handled cache
lookup is removed from its request projection. Header actions after a cache
step continue through the executor's framing/encoding validation.

### Upstream header caching is not an entity cache

The original cacheRequest stores **zero entity bytes**. A usable
If-Modified-Since within the configured time window, including the exact
boundary and future timestamps, can yield 304 without a stored response.
Request no-cache/no-store or corresponding Pragma bypasses that operation.
This behavior proves neither that a client has a usable representation nor
that the native proxy stored one.

The original cacheResponse rewrites GET cache headers (configured status
range/exclusions, max-age + 1, Last-Modified and Expires); it does not apply
that cache policy to HEAD. The runtime preserves those semantics. A HEAD
request still reaches the origin and can return a 65 MiB representation
length without transferring/allocating the entity. Default GET body bytes
continue through fixed-size framing buffers; the 65 MiB test combines CORS,
cache headers and responseReplace without full buffering.

`runtime.cacheState()` exposes conditionalChecks, conditionalHits,
responseHeaderChanges and **memoryStorageEnabled=false**. An unconditional
second GET still reaches the origin. There is no entity-cache entry/count
budget being secretly consumed: retained response entity bytes are zero.

Dates follow the scheduler's supported RFC 1123/numeric millisecond boundary,
not every JavaScript legacy date syntax. The frozen Boolean CacheDescriptor
cannot model the source's mixed Boolean/string `"false"` immutable branch.
Enabled `cacheImmutable: "false"` therefore fails config load explicitly;
use actual Boolean false when that is the intended normalized policy.

The source can rewrite an origin no-store header. Runtime compatibility
does that when configured and does **not** treat rewritten headers as
permission to store the entity. A future native store must inspect the
original upstream head before compatibility rewriting.

## Why retries/backup are still blocked

The current engine owns private connect/upload/response control:

- `onRequest` produces a forward or local response, then relinquishes
  transport ownership.
- `onResponseHead` can validate/change headers; it cannot return a retry
  decision or an owned upstream response handle.
- Throwing from that hook produces 502 and closes the exchange. It does
  not restart an attempt.
- The final response is committed and its body streamed by the engine.
  There is no public release/discard callback for a scheduler to use.
- Request bodies are real one-shot reader state, potentially concurrent
  with upstream responses. A policy callback has neither a complete,
  bounded replay token nor delivery proof.
- The DNS selection ticket now receives actual TCP outcomes. It still cannot
  substitute for the separate TLS/HTTP feedback needed by BackupSelector.

Wrapping `NativeRequestDecision.Forward` in scheduler.execute would measure
only a routing decision, not an actual network attempt. Returning a selected
backup string without rebuilding Host/TLS/DNS/ECH would also be wrong.
Neither workaround is implemented.

Consequently, configured retry/backup still fail at load with an explicit
precommit-attempt/feedback-boundary issue. Native implicit retries are not
enabled merely because a scheduler module is available. GET/HEAD status 500
responses are forwarded once. A POST whose origin consumed its eight-byte
entity produces exactly one side effect, and a truncated committed response
is not retried or followed by a second HTTP response.

## Minimal proposed engine boundary (review required, not implemented)

Before enabling RequestAttemptScheduler, split one owned upstream attempt
from downstream commitment. Preserve existing default behavior and append
any public constructor hook only after parent review.

The transport needs an owned `PreparedUpstreamResponse` carrying:

- the original response head and a retained framed/buffered body reader;
- a release/close operation for discarded responses (no pooling/drain
  requirement here);
- actual TCP outcome and whether request bytes may have been delivered;
- a downstream responseStarted/commit token that retries cannot revoke;
- cancellation/deadline ownership and actual attempt duration.

A bounded controller must be able to accept a final head or discard that
owned response and retry **before** the final head/body reaches the client.
100/103, CONNECT/101 handoff, Expect and upload cancellation must retain their
existing semantics; they cannot be hidden by a generic exception retry loop.

Request body ownership must be explicit:

- Empty replayable GET/HEAD/OPTIONS can use ReplayableRequest.empty.
- Any replay buffer must be completely framed/validated and fit admission,
  per-exchange and aggregate byte limits before the first attempt.
- Chunked/unknown/oversize/Expect/one-shot entities must never acquire a
  partial replay token or have their stream opened twice.
- A POST/PATCH method permission alone is not idempotency. After possible
  delivery or a status response, repeat requires a separate trusted
  server-side deduplication guarantee; otherwise forward the result once.
- No retry is permitted after responseStarted, on cancellation, or after
  an incomplete capture/partial buffer.

BackupSelector is selected once before the retry loop, matching upstream.
Retries retain that target; genuine final errors/slow/final >=400 outcomes
affect future exchanges. Each selected candidate must be recomposed from
the original rule/captures into a consistent target URL/path, Host, port,
SNI/verifyHost/trust policy, ECH requirement and new DNS ticket. Reusing the
primary plan's verification identity for a different candidate is unsafe.
TCP DNS feedback and HTTP/TLS backup feedback need separate typed events.

Acceptance for that next engine change must include live first-500/then-200
origin attempts, discarded-response release, zero replay after partial
commit, non-idempotent/one-shot delivery tests, candidate-specific Host and
verified TLS across two origins, and stop/cancellation cleanup.

## MemoryResponseCache: explicit additional capability, not auto-enabled

The current engine has no observer for the original head, encoded/dechunked
entity chunks successfully forwarded, valid framing completion/trailers,
and abort. Therefore no safe network-populated MemoryResponseCache exists
in this runtime yet. Direct entity-cache enablement remains unsupported.
The copied cache's isolated tests pass; that is not a live proxy cache-hit
claim.

After a reviewed body-observer boundary exists:

1. `beginCapture` must use the original head before CORS/cache compatibility,
   the original request, actual send/receive timing and an immutable runtime
   policy/epoch.
2. Append only successfully forwarded encoded/dechunked chunks, and abandon
   capture on its per-entry/global budgets without stopping forwarding.
3. Publish only after validated complete framing, known trailers/status
   policy and uncancelled ownership. Abort/invalidation must revoke late
   publication.
4. Respect source no-store/private/Vary/credentials/Accept-Encoding policy.
   No private entity storage is implied by rewriting browser cache headers.
5. Hits must enter before upstream dispatch and reapply header actions under
   the same policy epoch. GET/HEAD identity and metadata must not be
   conflated.
6. The existing LocalResponse body cap is 64 KiB. A first opt-in store must
   cap served entries to that size, or introduce a reviewed bounded
   streaming-local-response API. Do not silently feed default 1 MiB cache
   entries through an incompatible local-response type.

The default remains source-compatible header cache only. Any future
MEMORY_ENTITIES mode requires explicit opt-in, limits/privacy/epoch inputs,
real live hit/miss/expiry/overflow/invalidation tests and truthful telemetry.

## Verification

Run `.\docs\native-runtime\run-tests.ps1`. It compiles/runs using cached
dependencies with 256 MiB compiler and JUnit heaps, without Gradle or Node.
The suite includes frozen engine tests, runtime integration tests, and all
57 copied scheduling tests including 20 original-source fixtures.

Runtime tests use actual localhost UDP DNS, HTTP/TLS origins and a verified
client trusting only ephemeral test CA material. They cover timestamp 304
hits and bypasses, origin attempt counts, request priority, independent
response gate, correct response-action order, zero hidden entity storage,
65 MiB streaming/HEAD metadata, HTTPS proxy/capture/SNI/verifyHost coherence,
and explicit rejection of unsafe retry/backup/entity-cache/script actions.
