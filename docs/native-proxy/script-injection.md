# Controlled native script serving and HTML injection

This phase adds only `com.dsha.sidecar.net.http.native.script`, its tests,
separate synthetic fixtures, and this document. The accepted planner, action
executor, scheduler, prerequisites, and their fixtures remain unchanged.
No runtime hook, application/browser operation, production Node payload,
Gradle configuration, dependency, or device operation is changed.

The module provides JavaScript **as browser data**. It never executes script
on the proxy/JVM, embeds a JS engine, downloads a script, or scans an asset
directory. No real/unknown-provenance script content is copied into this repo.

## Actual original call path

1. options.js/buildIntercepts calls res/script.js/handleScriptInterceptConfig.
   Absolute script URLs are rewritten to `/____ds_script_proxy____/...` and
   gain ordinary proxy/header/cache rules. Relative script URLs gain
   Content-Type/cache rules. This is configuration expansion, not a file read.
2. createRequestHandler.js conditionally prepends
   InsertScriptMiddleware.requestIntercept when setting.script.enabled is true.
   That middleware serves `/____ds_script____/<key>`, aliases global to
   tampermonkey, and responds with script content from monkey.get.
3. monkey.get lazily calls monkey.load once. The source loads fixed builtin
   github/google userscripts and the tampermonkey bootstrap from disk;
   monkey.loadScript parses metadata and builds browser event/GM bindings.
   Neither that load nor serving evaluates the code in Node.
4. With the same script-enabled gate, the response loop calls selected
   responseIntercept functions and concatenates returned head/body append data.
   script.js skips GitHub repo-content-turbo-frame, requires text/html, creates
   ordered script tags with optional CSP nonce, prepends bootstrap, and adds
   GitHub's compatibility fakefile tag.
5. InsertScriptMiddleware.responseInterceptor handles the append. It checks
   HTML/content-length, writes response headers, decodes gzip/deflate/br,
   inserts bytes before closing head/body tags per chunk, then reencodes.
   Without an append, createRequestHandler streams the original response.

The new module replaces file-based loading with an injected read-only catalog,
and returns decisions/prepared entities for future engine integration.

## Public API

```kotlin
val catalog = ReadOnlyScriptCatalog(
    mapOf(
        "reviewed" to CatalogSource(reviewedJavaScript),
        "tampermonkey" to CatalogSource(reviewedBootstrap),
        "userscript" to CatalogSource(reviewedUserScript, CatalogFormat.USER_SCRIPT),
    ),
    ScriptLimits(),
)
val references = ScriptReferencePolicy(setOf("/approved.js"))
val planner = ScriptTagPlanner(catalog, references)
val tags = planner.plan(
    request, responseHead,
    ResponseAction.ScriptInjection(script = listOf("reviewed"), tampermonkeyScript = null),
    enabled = actualScriptEnabled,
)
val decision = HtmlScriptInjector().prepare(request, responseHead, tags, framedEntityStream)
when (decision) {
    is ScriptResponseDecision.PassThrough -> streamOriginalWith(decision.head)
    is ScriptResponseDecision.Blocked -> sendFailureWithoutReusingConsumedInput(decision.entityConsumed)
    is ScriptResponseDecision.Prepared -> decision.response.writeBodyTo(transportEntitySink)
}

val routes = ScriptRouteService(catalog)
val local = routes.serve(request, actualScriptEnabled, explicitNowMillis)
```

There are no default script assets. A trusted integration must supply reviewed
catalog content; missing bootstrap is explicitly blocked. CatalogSource is
already-JavaScript by default. USER_SCRIPT uses UserScriptCompiler's safe
metadata subset; the generated browser wrapper is data only.

ScriptCatalog.lookup is a read-only bounded contract taking a validated key,
never a path. The provided ReadOnlyScriptCatalog snapshots all supplied
content, validates size/count and Unicode, and returns immutable byte assets.
A caller-provided catalog must honor the same no-I/O/size contract.

ScriptTagDecision distinguishes Skipped, Blocked, and Ready, with safe problem/
skip enums and optional-asset notices. Ready binds the tags to the exact
request identity and response head used for the nonce/CSP decision. If later
header actions or route changes alter that binding, injection blocks; call
tag planning after other response-head transforms.

ScriptResponseDecision.PassThrough has consumed no input. It preserves entity
framing/encoding and lets the engine stream large skipped/non-HTML responses.
Blocked reports whether any input was consumed. A consumed stream cannot be
reused as a supposedly intact fallback. No output sink is touched by prepare.
Prepared owns only a bounded entity and an immutable head snapshot.
writeBodyTo is for the engine's own protocol/framing layer; writeHttp1 is a
deliberate HTTP/1.1 test/local serializer. Caller streams/sinks are never closed
or flushed. Write I/O can still fail partially after successful preparation.

## Source-compatible behavior

- Script list order and duplicates are retained. global/tampermonkey list keys
  are ignored; default tampermonkey bootstrap is inserted first if actual tags
  exist. Missing optional keys are skipped with a notice; an all-missing list
  produces an explicit NO_TAGS skip.
- Custom bootstrap references, relative references, and explicit HTTPS URLs
  can generate the same original tags, subject to authorization below.
- GitHub turbo-frame bypass and fakefile ordering match the source. The
  external fakefile URL also needs explicit authorization.
- script.js's text/html substring gate is preserved, including its case
  sensitivity. XHTML accepted by the lower middleware alone does not cause
  this script interceptor to produce tags.
- Non-200 HTML is not automatically excluded; the original script.js and
  middleware have no 200-only gate. No-body statuses/HEAD and partial 206
  handling are made protocol-safe as described below.
- Normal validated nonce, crossorigin, defer, type, URL and CRLF tag formatting
  match original-function fixtures.
- The catalog route has global -> tampermonkey alias, source MIME/cache/date
  headers, complete UTF-8 content, and missing-key 404. Dates are explicit
  caller input; no clock is read.
- Safe userscript metadata yields the same original wrapper: event selection,
  DS_init data, enabled guard, GM bindings, source body, and browser console
  diagnostics. No grant or source is run by the JVM.

## Intentional safety differences

### URL, route, and catalog boundaries

All catalog keys use bounded ASCII identifiers. No slash, percent escape,
dot-segment, prototype-sensitive key, query, or path traversal reaches catalog
lookup. The builtin route serves GET/HEAD; other methods return 405. HEAD has
representation length and no body. Responses add exact Content-Length and
X-Content-Type-Options:nosniff.

Relative or external script references require an exact immutable allowlist,
including custom bootstrap and GitHub's fixed compatibility URL. Only absolute
HTTPS at the default TLS port or root-relative paths are accepted. User-info,
scheme-relative URLs, fragments, traversal/encoded separators/controls, unsafe
attribute characters, raw Unicode URLs, and other schemes are rejected.
This conservative subset also rejects ampersand-bearing query strings rather
than interpolating unsafe text into an HTML attribute. Add a reviewed encoding
policy before broadening this boundary. Errors expose enum codes, not the
rejected URL, nonce, asset body, or catalog exception details.

ScriptRouteService accepts optional exact proxy-prefix aliases bound to
existing catalog keys. Such an alias serves controlled catalog data and never
fetches its original remote URL. It is distinct from the frozen planner's
ordinary proxy route expansion; a runtime may use that separately authorized
proxy mechanism for those routes. Unknown proxy aliases return 404.
Serve only after the runtime has admitted the host/request under its script
policy. This module does not independently decide which browser origins may
receive the catalog.

The original loader's global filesystem cache is not reproduced. Catalog
lifetime/snapshot replacement is explicit. Stable script URLs retain the
source's one-day cache policy; reviewed asset updates need versioned keys or
a deliberate runtime/browser cache migration.

### CSP, nonce, and metadata risks

Original script.js concatenates script URLs and an extracted nonce directly
into double-quoted HTML attributes. Original monkey wraps version/name-derived
diagnostics and grant names into JavaScript source. Malicious configuration/
metadata can therefore break quoting or create executable text in the browser.
The new module refuses unsafe URL/nonce/metadata/grant values before output.
This is a browser injection boundary, not a reason to embed a server JS engine.

PRESERVE is the default ScriptCspMode. It never widens an enforced CSP. It
supports a validated base64/base64url-like nonce from the chosen source policy,
or a conservative existing source list allowing the script references.
Sandbox without allow-scripts, conflicting/duplicate policies, strict-dynamic
without an applicable nonce, and unsupported authorization cases block.
Same-host URLs on a different scheme/port are not treated as CSP 'self'.
The scanner is not a full CSP evaluator; policies beyond the supported subset
must be reviewed rather than guessed.

EXPLICIT_UPSTREAM_SELF_COMPAT opts into the original middleware's addition of
'self' to script-src/script-src-elem or insertion of a missing script-src.
It returns a review notice when relying on widening. This can weaken policy,
and strict-dynamic can still cause browsers to ignore 'self'; it is not proof
that a browser will execute every tag. Sandbox remains blocked.
Report-only policy is left unchanged.

Nonce content is bounded to safe base64/base64url characters; malformed
attribute-breaking content blocks even if the original would concatenate it.
Multiple enforced/report-only headers block rather than selecting one silently.
Invalid catalog behavior is typed CATALOG_FAILURE without private details.

UserScriptCompiler supports metadata plus bounded GM_* or GM.member grants.
It rejects unknown executable grant shapes, duplicate grants, malformed Unicode,
missing userscript delimiters, and unsafe quote/backslash/control metadata.
Raw body JavaScript is deliberately not parsed or executed: it is supplied
reviewed browser code. Unsupported metadata/grants remain blocked, including
@grant none in this initial source-compatible subset. No real userscript or
tampermonkey runtime is packaged by this phase.

### Byte/HTML/protocol boundary

Input must be exactly one already framed/dechunked entity, not chunked wire
syntax or an unbounded keep-alive socket. Caller owns read timeouts and
cancellation. prepare checks cancellation/interruption throughout body reads
and writes; no sink is touched until the result is fully prepared.

Bounds default to 1 MiB encoded input, 4 MiB decoded/encoded output, 64 KiB tags,
32 configured scripts, 64 catalog entries, 256 KiB per asset, and 2 MiB catalog
total. Configurable hard maxima prevent unlimited buffering. There are bounded
transient copies during decompression/insertion/encoding, so these are logical
payload limits rather than exact heap accounting. Concurrent invocation/admission
budgets belong to the runtime.

The module buffers only when injection is needed. Known oversize input and
unsupported encoding/charset block before any body read. A limit failure after
consumption is Blocked(entityConsumed=true), never a truncated success.
Large non-HTML/disabled/turbo-frame responses remain a no-read streaming skip.

Existing document bytes are never converted to String. UTF-8 fragments survive
arbitrary stream-read boundaries; ASCII closing tags are scanned in bounded
bytes. UTF-8/ASCII declared charset is supported; other/multiple/contradictory
charset declarations and UTF-16/32 BOMs block. Gzip and zlib-wrapped deflate use
JDK codecs with independent decoded/output limits. Identity bytes are also
supported. No cached Brotli encoder/decoder pair was found, no dependency is
added, and Brotli/unknown/stacked injection encodings block before consumption.
The original oracle demonstrates Brotli output, while the JVM explicitly
reports that unsupported boundary.

Original chunkByteReplace can miss a closing tag split across chunks, insert
again in multiple chunks, or match a literal tag inside JS/comment text. The
new conservative scanner finds a single explicit head boundary across reads,
recognizes mixed case and quoted attributes, and skips comments/raw-text
script/style/title/textarea elements. Missing/malformed head is blocked. It is
not a general HTML DOM parser and does not synthesize an implied head.

HEAD/204/205/304 and zero-length responses skip without consuming an entity.
Partial 206/Content-Range and Cache-Control:no-transform block injection.
Ambiguous CL/TE, duplicate Content-Type, trailers, invalid headers, and changed
request/head bindings block. Actual transformed output removes hop-by-hop and
Connection-nominated fields, TE, stale ETag/Last-Modified/digest metadata, and
Accept-Ranges, then supplies exact encoded Content-Length. Content-Encoding
stays correct. Unchanged pass-through leaves framing and validators intact.
These validator/no-transform safeguards deliberately improve the source,
which otherwise retains metadata for a different representation.

## Assembly boundaries

Consume only a selected ResponseAction.ScriptInjection after the actual
setting.script.enabled gate, route/context resolution, and other response-head
actions. The frozen RequestPlan still reports script issues and the frozen
RuleActionExecutor still rejects ScriptInjection; this module does not weaken
those guards or silently remove issues. A future trusted adapter must recognize
precisely the script action it executes, validate remaining issues, and expose
head/body hooks. No current engine was copied or modified.

Actual builtin asset provisioning, review/versioning, browser execution
acceptance, richer CSP/charset/HTML support, remote proxy routes, Brotli,
browser extensions/GM runtime implementation, and runtime wiring remain gates.
Without an injected reviewed bootstrap/catalog, normal script injection remains
explicitly blocked. No complete-default-script execution claim is made.

## Independent original-function evidence

`port/tests/fixtures/native-script/script-oracle.cjs` supports:

```powershell
node --max-old-space-size=128 port/tests/fixtures/native-script/script-oracle.cjs --verify --upstream '<mitmproxy/src>'
node --max-old-space-size=128 port/tests/fixtures/native-script/script-oracle.cjs --generate --upstream '<mitmproxy/src>'
```

Verify reads pinned sources and saved synthetic inputs, executes original
res/script.js, InsertScriptMiddleware route and compression pipeline, and
monkey.loadScript against synthetic strings, then compares JSON plus generated
Kotlin fixtures without writes. Generate explicitly updates only new script
artifacts. Logger/catalog are injected; actual script filesystem reads are
stubbed to throw. No real script, private config, or remote content is loaded.
Only the original implementation source files are read.

The 26 cases cover builtin/list ordering, missing/reserved keys, custom/remote
references, nonce/report-only/self repair, GitHub/turbo, non-HTML/non-200,
uppercase HTML, identity/gzip/deflate and original Brotli evidence, catalog/
bootstrap failures, route serving/alias/404, and userscript wrappers.
Safe supported cases compare actual tags, route bytes/headers, and decompressed
HTML bytes. Catalog failures and Brotli have explicit asserted safety differences
rather than a false parity claim. Compressed bytes need not be identical between
Node/JDK codecs; representation bytes and framing/encoding are checked.

Validation uses the original JDK 21 and cached Kotlin 2.0.21/JUnit 4.13.2,
one compiler process with 256 MiB heap and one JUnit process with 128 MiB.
The source-hashes.json manifest records original relative paths and exact SHA-256;
original sources are under
`D:/File/User/code/dev-sidecar-lightning/port/.run/offline-proxy-fixes-20261101/runtime/core/node_modules/@blue-frontier/mitmproxy/src`.

Final verification on 2026-11-02 passed 205 targeted combined tests: 59 new
script tests (26 original-function oracle cases and 33 boundary/protocol tests)
plus all frozen 146 planner/action/scheduler tests. The read-only oracle passed
26 cases against five pinned sources while preserving expected artifact hashes
and timestamps. Five isolated failure checks confirmed missing/changed pins,
changed inputs/expectations, and stale Kotlin fixtures fail without writes.
Only the existing DnsClient URL-constructor deprecation warning was emitted.
No Gradle, Android/browser/device test, network fetch, or actual script file
loading was performed.

| Original source | SHA-256 |
| --- | --- |
| lib/interceptor/impl/res/script.js | 0c38b1327ec5e3bcb27331b203ca6443d1c64a81ec4ac54dcd1791b1c70c7d03 |
| lib/proxy/middleware/InsertScriptMiddleware.js | bfd3aa4bcdeda4982e588568effb6ca45a5c765308879a3b6646d6dc16ba9761 |
| lib/monkey/index.js | f1c095db89f036b2b49d1e95ebb5a19f62b8bf11991cace39893cf4e2cc39e73 |
| lib/proxy/mitmproxy/createRequestHandler.js | 64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690 |
| options.js | 411a38af92019b15e829446f22d23ba9221e56f44253fa9e660fa95d7b63b356 |
