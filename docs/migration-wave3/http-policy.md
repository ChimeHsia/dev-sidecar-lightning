# Wave 3: request retry eligibility and header transforms

This is an isolated, data-only port from the locally installed
`@blue-frontier/mitmproxy` 2.3.1 source. The application does not reference it.
Base commit: `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
Implementation and tests are MPL-2.0, following the upstream package license.

## Public surface

All production types are in `com.dsha.sidecar.net.http`, under
`netcore/src/main/kotlin/com/dsha/sidecar/net/http/`.
The two source files use common Kotlin APIs and no platform imports.

```kotlin
object RequestRetryPolicy {
    fun canRetryWithBody(
        method: Any?,
        headers: Map<String, Any?>,
        methods: List<String>,
        maxRetryBodySize: Int,
    ): Boolean
}

data class ResponseHeadersResult(
    val rawHeaders: List<Any?>,
    val setHeaders: Map<String, Any?>,
    val previousHeaders: Map<String, Any?>?,
    val remainingReplacements: Map<String, Any?>,
)

object HeaderTransforms {
    fun replaceRequestHeaders(
        headers: Map<String, Any?>,
        replacements: Map<String, Any?>,
    ): Map<String, Any?>

    fun replaceResponseHeaders(
        rawHeaders: List<Any?>,
        replacements: Map<String, Any?>,
    ): ResponseHeadersResult

    fun afterOptionsHeaders(
        method: Any?,
        requestHeaders: Map<String, Any?>,
        rawHeaders: List<Any?>,
        optionsCrossPolicy: Any? = null,
    ): ResponseHeadersResult
}
```

`setHeaders` contains data corresponding to the original `res.setHeader` calls.
It does not execute them or perform Node's validation/coercion of header values.
`previousHeaders` is null when upstream returned null. Otherwise it records the
last changed value per lowercase raw header name; an added name maps to null.
`remainingReplacements` is the original response function's mutated configuration
projected into a fresh map, including unused null/removal entries.

`afterOptionsHeaders` returns the diagnostic
`DS-AfterOPTIONSHeaders-Interceptor` value as data in `setHeaders`. A skipped
call has empty `setHeaders`, null `previousHeaders`, and empty
`remainingReplacements`. It does not evaluate the interceptor's `.is()` feature
gate; a future caller must decide whether the options feature applies.

## JSON boundary

Inputs represent JSON snapshots: string-keyed maps, lists, strings, booleans,
null, and finite Byte/Short/Int/Long/Float/Double numbers. Long values must be
within the binary64 safe integer interval. Other Number implementations,
non-string map keys, sets, byte arrays, functions, sockets and arbitrary objects
are unsupported. Cycles are rejected by identity without platform-specific
collections; nesting is limited to 64 containers. Shared acyclic input
containers are copied separately, as a JSON serialization would do.

Every call validates and deep-copies all input JSON containers, including
inputs ignored by an early return. All result containers are detached from
inputs and from other calls. Kotlin read-only Map/List types do not promise
runtime unmodifiable wrappers: a consumer that casts and mutates its result
cannot change the input or another invocation. Internal aliasing between fields
of one result is allowed, matching replacement values reused in raw headers.

Header keys and retry method strings are ASCII. This avoids platform differences
in Unicode case conversion. Arbitrary Unicode header *values* are supported.
Object-prototype names such as `__proto__`, `constructor`, `toString`,
`hasOwnProperty`, legacy getter/setter names, etc. are rejected as JSON keys;
header names reject their case variants too. JavaScript inherited-property,
accessor, symbol, sparse-array and custom-prototype behavior is outside the
boundary. Absent keys and JSON null are equivalent at the lookups used here.
Canonical array-index object keys enumerate first in numeric order, as in
JavaScript; other keys retain insertion order.

A truthy retry method must be a string; falsy null/false/zero/empty-string
defaults to GET. A truthy Expect must be a string when its branch is reached.
The first Vary value, if truthy, must be a string when CORS examines it.
These coercion boundaries throw IllegalArgumentException explicitly instead of
trying to emulate methods on arbitrary JavaScript objects.

Content-Length follows decimal `Number.parseInt` semantics for strings,
including ECMAScript whitespace, optional sign, leading decimal digits,
trailing junk, and overflow. Null/boolean/object/empty-array values produce an
invalid length. Arrays follow their leading element recursively (JS comma
joining cannot provide digits after a nonnumeric first element). Numeric
Content-Length values support zero and magnitudes from 1e-6 inclusive to 1e21
exclusive, truncated toward zero. Other finite numeric magnitudes are rejected:
JavaScript switches to exponential string notation there; callers can pass the
original string instead. This restriction also applies inside leading arrays.

No syntax, CRLF, origin trust, forbidden-header, HTTP method idempotency or
credential policy is added. Values accepted by these data transforms can still
be rejected by a real HTTP implementation. This port preserves the source's
decisions within the stated boundary rather than defining an HTTP policy.

## Preserved source decisions

Retry:

- Request method is uppercased after its falsy GET default; configured methods
  are matched exactly. The caller supplies an already normalized list.
- Only exact lowercase `transfer-encoding`, `expect`, and `content-length` keys
  are consulted. Any truthy transfer encoding rejects eligibility, including
  empty arrays and objects. Empty strings, zero, false and null do not.
- A truthy Expect string containing `100-continue`, ignoring case, rejects.
- Only POST, PUT, PATCH and DELETE need a finite decimal parsed length at or
  below the explicit threshold. Negative lengths and digit prefixes are
  accepted by the source. Other configured methods ignore content length.
- Threshold equality passes. The pinned source's threshold is 1,048,576 bytes.

Caller review: `req/retry.js` normalizes numeric/object retry configuration,
defaulting methods to GET/HEAD/OPTIONS and statuses to 500.
`createRequestHandler.js` also has a separate 2 MiB opportunistic buffering
condition, fallback GET/body-buffer retry limits, error retryability checks,
status matching, response consumption, counters and scheduling. None of that
caller behavior is implemented here. Eligibility alone does not prove a body
was fully buffered, a retry is enabled, or an error is retryable.

Request replacement:

- Only replacement keys are lowercased; existing mixed-case names are retained.
- Exact `"[remove]"` becomes null. Truthy values assign; a falsy replacement
  deletes only a truthy existing value. An existing zero/false/empty string/null
  therefore survives. Empty arrays and maps are truthy and assign.

Response replacement:

- Configured names normalize with the original delete-and-reinsert order and
  collision behavior. Every matching raw pair is examined independently.
- Null replacements are ignored. Zero, false and empty strings replace and add;
  `"[remove]"` sets an existing raw value to `""`, preserving its pair and case,
  and is skipped for an absent name.
- Duplicate raw names remain duplicated. The last changed previous value wins
  in `previousHeaders`; unchanged duplicates do not overwrite that record.
- Values compare with JS strict equality: numbers compare numerically, while
  container values compare by identity. JSON snapshots cannot encode an object
  shared across independent raw/replacement input fields.

CORS:

- Only exact method `"OPTIONS"` or a falsy lowercase `origin` skips execution.
  No origin validation or feature configuration gate is added.
- Emits credentials `"true"`, the request's origin value, and the truthy cross
  policy or `"cross-origin"`. Arrays/maps are truthy values here as upstream.
- Examines only the first raw Vary pair. An absent pair yields `"Origin"`;
  an existing falsy value is retained. An exact `"Origin"` token after splitting
  on `/\s*,\s*/` prevents appending. Token case is significant; outer spaces
  away from a comma remain significant. `"*"` gets `", Origin"` appended.
- The response replacement function then updates every duplicate Vary pair
  with that first-pair decision. It does not combine duplicate Vary values.

## Fixtures, oracle and checks

`port/tests/fixtures/wave3/http-policy.json` is an array of 86
`{name,input,expected}` cases. Each input has an `operation` of `retry`,
`request`, `response` or `cors`. Expected values are produced from original
upstream code, not a second handwritten implementation.

`port/tests/oracles/wave3/http-policy.cjs` accepts a source directory:

```powershell
node port/tests/oracles/wave3/http-policy.cjs --generate --upstream <mitmproxy/src>
node port/tests/oracles/wave3/http-policy.cjs --verify --upstream <mitmproxy/src>
```

Exactly one mode is required. Generate is the only writing mode; verify compares
an in-memory replay to the existing fixture, byte for byte. Both modes verify
the pinned source hashes first. Fixture ordering is deterministic.

The oracle executes the unmodified request module plus an export of its local
function, the actual response module with the real installed lodash, and the
actual CORS module. It extracts `canRetryWithBody` verbatim and supplies only
its lexical BODY_METHODS/threshold dependencies. It never loads or executes
the network request handler. Logging and setHeader effects are inert/captured;
the unused download cache dependency is stubbed. No replacement, Vary, retry
eligibility, or expected-value logic is reimplemented in the oracle.

JUnit 4 tests consume every fixture with a dependency-free test-only JSON
reader and compare nested values and object key order. Other tests exercise
deep copying, repeat invocations, invalid values, cycles, and coercion bounds.
Fixtures are discovered upward from `user.dir`, or explicitly using
`-Dhttp.policy.fixtures=<absolute path>`. No build dependency or Gradle file
changes are needed.

Test-only aggregate bridge:
`port/tests/wave3/src/jsMain/kotlin/CheckHttpPolicy.kt` provides
`internal fun checkHttpPolicy(input: Map<String, Any?>): Any?`.
It dispatches all four operations and projects exactly the fixture schema.
The parent owns JSON conversion and compiler inclusion. The bridge has no
`@JsExport` annotation and no production linkage.

Local verification used the original `.jdk/jdk-21.0.2`, cached Kotlin compiler
2.0.21 and JUnit 4.13.2/Hamcrest 1.3 in `C:/Users/HONOR/.gradle`, invoking
`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` with `-Xmx384m`, `-jvm-target 17`,
`-no-stdlib -no-reflect` and explicit cached classpaths, then JUnitCore with
`-Xmx128m`. No Gradle daemon, Android build, device or ADB was used.

Stable validation on November 2, 2026:

- Local JVM compilation includes both production files, both test files and the
  test-only bridge. All nine JUnit tests pass.
- All 86 source-generated fixtures pass local JVM comparison and Node oracle
  replay: 29 retry, 11 request, 17 response, and 29 CORS cases.
- The parent reports that its aggregate Kotlin/JS replay passed all 86 HTTP
  fixtures, its Node oracle verified all 86, and its current HTTP JVM tests
  passed. These are parent-run results, not a second local JS compiler run.
- The parent will copy the stable files and run its final aggregate JVM/JS check.

The complete owned file set is:

```text
netcore/src/main/kotlin/com/dsha/sidecar/net/http/RequestRetryPolicy.kt
netcore/src/main/kotlin/com/dsha/sidecar/net/http/HeaderTransforms.kt
netcore/src/test/kotlin/com/dsha/sidecar/net/http/RequestRetryPolicyTest.kt
netcore/src/test/kotlin/com/dsha/sidecar/net/http/HeaderTransformsTest.kt
docs/migration-wave3/http-policy.md
port/tests/fixtures/wave3/http-policy.json
port/tests/oracles/wave3/http-policy.cjs
port/tests/wave3/src/jsMain/kotlin/CheckHttpPolicy.kt
```

## Source provenance

Read-only source:
`D:/File/User/code/dev-sidecar-lightning/port/.run/offline-proxy-fixes-20261101/runtime/core/node_modules/@blue-frontier/mitmproxy/src`.
SHA-256 hashes below cover the original file bytes, including line endings.
The retry config caller was read and pinned for scope review only.

| Source relative to src | SHA-256 |
| --- | --- |
| lib/proxy/mitmproxy/createRequestHandler.js | 64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690 |
| lib/interceptor/impl/req/retry.js | 6d64453ecd15c78391629e14eefc333439645c216d0dc49ceb4e80790841372f |
| lib/interceptor/impl/req/requestReplace.js | d38ab57e0d0d670a7b2aca890c8b1b5dd27bce551316209fbc98e8474cffcb9f |
| lib/interceptor/impl/res/responseReplace.js | 3b5a7dc465049e19980a955d88c391c269ee0cd65fb85b4d910a94aa5788d10c |
| lib/interceptor/impl/res/AfterOPTIONSHeaders.js | d455246bffa630ea7e34f86e71bb58de0a9d5f82c0c372ee2f9961e47ac4ae72 |

## Scope and remaining work

No adapters, exports, build configuration, assets, payload, URL/body rewriting,
streams, timers, sockets, interceptor application, retry execution, commits or
device actions are included. No source outside this worktree was modified.
This is not full JavaScript compatibility outside the explicit JSON boundary.
The parent must run its aggregate JVM/JS suite before integrating. Any future
runtime application needs independent design and tests for transport header
coercion, fully buffered bodies, scheduling and feature/configuration gates.
