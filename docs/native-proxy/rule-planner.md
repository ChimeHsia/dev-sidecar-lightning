# Pure native rule planner

This work is based on `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
It produces per-request data for a future native engine. It does not install,
apply, fetch, or execute configuration, open sockets, read a clock, modify
trust, or integrate with the application. The certificate worktree is frozen
and was not edited.

The new checkout is
`C:/Users/HONOR/.codex/worktrees/native-rule-planner/dev-sidecar-lightning`.
Application managed-worktree creation was rejected because the app's workspace
scope still pointed at a visualization directory. A detached Git worktree was
created from the specified original source and exact ref. The app then rejected
attachment because it is not a managed checkout. Managed registration remains
unresolved; the implementation is present in this independent Git worktree.

## Frozen public API

```kotlin
import com.dsha.sidecar.net.rules.native.RulePlanner
import com.dsha.sidecar.net.rules.native.RuleRequest

val planner = RulePlanner(listOf(defaultLayer, remoteLayer, userLayer))
val audit = planner.audit()
val plan = planner.plan(
    host = "api.example.test",
    path = "/items/42?q=x",
    method = "GET",
    headers = mapOf("host" to "api.example.test"),
    scheme = "https",
    port = 443,
)
val equivalent = planner.plan(RuleRequest("api.example.test", "/items/42?q=x"))
plan.requireSupported()
```

Input is decoded JSON: string-keyed maps, lists, finite safely representable
numbers, booleans, strings, and null. No JSON parser/dependency is introduced.
Layers are merged in order with deletion markers retained until final cleanup.
Pass either full layers containing `server`, or consistently server-only layers.
Do not mix those shapes. The existing ConfigMerge is reused unchanged.
Inputs are snapshotted, bounded by 64 nesting levels/100,000 JSON nodes;
cycles and prototype-sensitive object keys are rejected. Hostname matching
retains the prerequisite's 1,024-entry limit. Request host/path are bounded to
8,192 characters and path regexes to 4,096. Method input is an uppercase token.
Header names are normalized to lowercase; wire-level framing and validation
remain the engine's responsibility.

`RequestPlan` exposes:

- `selected`: original implementation selection order, priority, path key,
  numeric order, and path/hostname positional and named captures.
- `requestSteps`: ordered typed Retry, Preflight, CacheLookup, Transform,
  Proxy, Tls, Respond, or Unsupported steps. They are data, not executed work.
- `routeOverride`: the primary candidate route; `requestPath` and
  `requestHeaders`: pure transformations assuming earlier conditional gates
  fall through.
- `tls`: applicable protocol, explicit server-name Send/Suppress directive,
  verification hostname, and per-request trust compatibility reasons.
- `localResponse`: a static/redirect/preflight response reached only if earlier
  runtime conditions fall through. OPTIONS Date is `addDateAtExecution`.
- `responseSteps`: CORS, cache, header/download replacement, script injection,
  and unsupported body descriptors. Response actions require a forwarded
  upstream response and preserve source order.
- `retry`, `doDownload`, `echRequired`, and explicit `issues`.

Before interpreting a plan, an engine must check `hasBlockingIssues` or call
`requireSupported()`, which throws UnsupportedRulePlanException carrying the
issues. This guard only validates planning support; it does not provide the
executors for deferred actions. Consume the ordered steps, not just the
convenience route/localResponse fields.

If runtime backup ranking chooses an alternative, the engine must derive the
chosen candidate's route, Host header, default SNI, and default verification
hostname together. The convenience fields describe the primary candidate and
must not be reused unchanged for an alternative.

`audit()` reports enabled unknown actions, unsupported script/external/body
work, unsupported regex syntax, and global script middleware. It includes
unmatched rules. Per-request issues cover matching non-excluded configurations,
including enabled unsupported options on rules whose known actions lose to a
higher order. Invalid path regexes in a matched host are also surfaced.
Issues in unrelated host rules stay in the audit. Invalid structural JSON
and invalid order types throw rather than silently producing an empty rule set.

## Preserved source behavior

HostnameAllMatcher performs all-pattern merging, then fast-path overlays;
merged hostname captures, including the source's latest named-capture metadata,
remain available for target placeholders. Paths include the complete query
string. Exclusion object keys are patterns even when their values are false.
Each implementation has its own activation predicate: empty SNI, verifyHost-only
rules, cache units, and `ssl: false` cannot use a generic truthiness gate.

For each name, higher numeric order replaces the previous selection and equal
order retains the first. Replacements keep their original position before
stable ascending priority sort. The pinned implementation order is:

```text
options 101, success 102, abort 103, cacheRequest 104, redirect 105,
requestReplace 111, proxy 121, retry 100, sni 123, unVerifySsl 124,
baiduOcr 131, AfterOPTIONSHeaders 201, cacheResponse 202,
responseReplace 203, script 211
```

The actual file is `req/OPTIONS.js`; there are no `req/status.js` or
`req/backup.js` implementations. Status is inside success/abort response
configuration. Backup belongs to proxy. A top-level enabled `status` is
reported as an unknown action.

The source request loop stops on a truthy return, including proxy and SNI,
not only a locally completed response. Consequently proxy stops another
selected rule's SNI/unVerifySsl action. Within a proxy rule, nonempty inline
SNI and verifyHost are applied; an empty inline SNI is ignored because the
source tests it for truthiness. Request headers/download handling precedes
proxy and therefore can be overwritten by proxy's Host rewrite.

Target building preserves positional/named placeholders, first-occurrence-only
replacement, removal of leftover placeholders, protocol-relative targets, and
regex replace configuration within the supported regex boundary. Absolute
HTTP(S) targets without user-info are parsed using URI. Unsupported legacy
Node URL coercions or invalid targets produce blocking issues rather than
inventing a route.

Absolute and relative script helper routes generated by
handleScriptInterceptConfig are reproduced for selection. Script execution
itself remains unsupported, even when its script keys are selected. Generated
routes do not cause scripts or remote configurations to be loaded.

## TLS and ECH compatibility

TLS policy is data only; no SSLContext, trust manager, agent, or global security
setting is changed. The source's unsafe compatibility behavior is explicit:
standalone nonempty SNI, and even verifyHost-only/default-SNI paths, switch
to UPSTREAM_COMPAT_UNVERIFIED. Suppressing SNI with an empty string returns
before that switch and retains configured verification. The normal verification
hostname is the routed real host; explicit nonempty verifyHost overrides it.
Server setting verifySsl=false and explicit unVerifySsl have distinct reasons.
An engine must make a deliberate decision about implementing this compatibility
mode; this planner does not authorize a global trust bypass.

ECH membership honors enabled/use, domain array/object syntax, and configured
NAT64 domains with an enabled flag, nonempty prefix, and domain array. The source
forces those NAT64 domains into ECH even when the ordinary ECH switch is off.
For an ECH host, the standalone sni implementation, including verifyHost-only
use of that implementation, is skipped.

The upstream ECH ignore list does not cover inline SNI inside proxy. That
source exception is retained in the proposed data and reported as blocking
ECH_PROXY_SNI_CONFLICT. A future executor must resolve it explicitly instead
of treating it as safe ECH suppression.

## Execution deliberately deferred

The supplied default configuration inventory of 56 hosts/62 paths is a scope
inventory, not a claim that all those runtime behaviors execute natively here.
Full real configurations are not copied into fixtures.

| Configuration | Planning result | Execution boundary |
| --- | --- | --- |
| proxy, replace | Primary route and captures | Network connection/forwarding absent |
| sni, verifyHost, ssl/unVerifySsl | Explicit per-request TLS policy | No TLS/trust application |
| requestReplace | Pure headers and download-path result | No request I/O/body executor |
| success/status, abort, redirect, options | Static response data and conditional steps | No response written; Date deferred |
| responseReplace, options CORS | Ordered header/download descriptors, checked against real JS transforms | No response processing pipeline |
| cacheDays and other cache units | Request lookup and response policy descriptors | No clock, 304 decision, storage, or cache header dates |
| retry | Normalized count/status/methods and body eligibility | No buffering, replay, retry loop, or elapsed-time policy |
| backup | Primary/alternatives, counter key, runtime-ranking flag | No ranking state or failover |
| script/tampermonkeyScript | Selection and generated helper routes; explicit unsupported issue | No loading, injection, CSP handling, or script serving |
| baiduOcr / unknown enabled actions | Explicit unsupported issue | No external service or silent no-op |
| nested response/body options | UnsupportedBody descriptor and issue | Pinned responseReplace handles headers/download only |

CONNECT admission, whitelist decisions, DNS/IP selection, external proxy
middlewares, transport retries independent of rule retry, framing, and request/
response streaming belong to the engine and other prerequisites. This planner
assumes an already admitted per-request input. It is not a complete replacement
for all serverConfig consumers.

Kotlin/JVM regexes are not an arbitrary ECMAScript engine. The unchanged
prerequisites define the portable boundary; unsupported compilation is audited,
but this does not prove parity for every regex accepted by both engines.
Trusted configuration and bounded patterns are still required; there is no
regex timeout/interrupt executor. Generated script payload rewriting is not
used to claim script execution support.

## Original-function oracle and verification

`port/tests/fixtures/native-rules/generate-oracle.cjs` loads the original local
mitmproxy source through a CommonJS VM harness. It runs the original
options factory/createIntercepts, original implementation `is` predicates,
request transforms, response header transforms, script configuration expansion,
hostname utilities, and merge functions. Logger, DNS initialization, overwall,
OCR constructor, and script-file service dependencies are stubbed. Nothing
downloads configuration or connects to the network.

Recording wrappers capture the original selected closure's actual config key,
captures, order, and name. They do not implement another selector. After
recording, original request functions are called until a truthy return;
original header response functions are called for forwarded requests.
Cache clock decisions/cache response dates and script/OCR execution are
explicitly deferred on both sides. A fixed Date exists only in the JS oracle;
JVM planning has no clock.

Explicit `--generate` emits synthetic oracle.json, source-hashes.json, and
the Kotlin literal fixture UpstreamRuleOracle.kt. JVM tests therefore need no JSON parser.
Expected outcomes are never handwritten. The comparison normalizes JSON
numeric representation and combines setHeader/writeHead header data as Node
would on the wire. Response header descriptors are projected through the
unchanged HeaderTransforms helper; that test projection is not a production
response executor.

Use the read-only verification mode for parent aggregation and routine checks:

```powershell
node --max-old-space-size=128 port/tests/fixtures/native-rules/generate-oracle.cjs --verify --upstream 'D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src'
```

`--verify` reads the saved synthetic inputs/expected results and source pins.
It checks every pinned source before evaluating the original modules, requires
a pin for every consulted source, and rejects missing/invalid pins, missing
sources, source changes, or a different source inventory. Saved inputs must
match the script's synthetic definitions. Fresh original-function output must
match the saved expected JSON, and the generated Kotlin representation must
match its saved file. Verification neither writes artifacts nor creates output
directories, including on failure. It requires the three saved artifacts to
be present in their existing repository locations.

Only an intentional baseline update uses the write mode:

```powershell
node --max-old-space-size=128 port/tests/fixtures/native-rules/generate-oracle.cjs --generate --upstream 'D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src'
```

`--generate` explicitly refreshes all three expected artifacts and their source
pins. Review those changes together. Exactly one mode and one `--upstream`
argument are required; the old positional invocation is rejected. Both modes
use only the 35 synthetic cases and original implementation sources; neither
loads or copies a private/full application configuration.

The 35 original-source fixtures cover multirule captures, layers/deletions,
order/ties, object/array exclusions, SNI/verifyHost cases, ECH/NAT64 suppression,
proxy precedence, static responses, redirects, regex target replacement,
request download/header transforms, CORS/response headers, retry/cache policy
activation, generated script routes, and unknown actions. Fifteen additional
JVM tests cover blocking issues, deferred descriptors, per-request trust
boundaries, malformed input, audit scope, snapshot isolation, and concurrency.

Verification uses JDK 21 from the original `.jdk/jdk-21.0.2`, cached Kotlin
2.0.21/JUnit 4.13.2 artifacts under `C:/Users/HONOR/.gradle`, and no Gradle.
All netcore main Kotlin sources plus the new planner tests are compiled with
`-jvm-target 17 -Xbackend-threads=1 -no-stdlib -no-reflect`, heap 256 MB.
JUnitCore runs afterward in one process with heap 128 MB. Both processes use
ActiveProcessorCount=2. Output is ignored netcore/build/native-rule-planner-standalone.
The fixture generator uses Node with a 128 MB old-space cap; Node remains a
test-only oracle, not a production dependency.

Result on 2026-11-02: all 50 tests passed (35 original-source oracle cases and
15 JVM boundary tests). The all-main-source compilation reports one existing
URL-constructor deprecation warning in DnsClient.kt; no planner compile errors.

The generator was rerun and oracle.json/UpstreamRuleOracle.kt were verified
byte-for-byte identical. Source hashes are in source-hashes.json, including
all 22 original files loaded or consulted for loop/transport context.
prerequisite-hashes.json records original paths and exact SHA-256 values.
No build/dependency file or existing production file is changed.

The CLI-only follow-up passed 14 isolated checks covering read-only successful
verification, explicit byte-identical generation, missing/invalid pins, a changed
source, changed expected output, changed synthetic input, stale/missing Kotlin
fixtures, missing oracle artifacts, and rejected legacy/ambiguous arguments.
Successful verification preserved expected artifact hashes and modification
timestamps; failure cases also left expected artifacts untouched. This follow-up
changes only the oracle script and this documentation, so the accepted 50 JVM
tests were not recompiled or rerun.

## Unchanged prerequisites

The four copied files are byte-for-byte originals, with unchanged declarations,
now stored at their canonical package paths. Their earlier copies under
rules/native have been removed. They were never package-relocated implementations;
keeping both locations during aggregation would create duplicate definitions.
ConfigMerge already exists at the base and has not been copied or modified.

Canonical destinations relative to netcore/src/main/kotlin:

```text
com/dsha/sidecar/net/rules/HostnameAllMatcher.kt
com/dsha/sidecar/net/rules/PathRuleMatcher.kt
com/dsha/sidecar/net/http/HeaderTransforms.kt
com/dsha/sidecar/net/http/RequestRetryPolicy.kt
com/dsha/sidecar/net/config/ConfigMerge.kt
```

For parent aggregation, keep exactly one file at each canonical path. If the
prerequisite is already present, compare SHA-256 with the manifest and reuse
the identical file rather than adding another source definition. A mismatch
requires reconciliation; do not overwrite a different prerequisite silently.
Only RulePlan.kt, RulePlanner.kt, and RulePlannerSupport.kt remain under the
new production rules/native directory.

Exact original source paths are recorded in prerequisite-hashes.json. The two
matcher sources are under
`D:/File/User/code/dev-sidecar-lightning/netcore/src/main/kotlin/com/dsha/sidecar/net/rules/`.
The two HTTP sources are under
`C:/Users/HONOR/.codex/worktrees/migration-wave3-review/dev-sidecar-lightning/netcore/src/main/kotlin/com/dsha/sidecar/net/http/`.
ConfigMerge's source is
`D:/File/User/code/dev-sidecar-lightning/netcore/src/main/kotlin/com/dsha/sidecar/net/config/ConfigMerge.kt`.

| File | Provenance | SHA-256 |
| --- | --- | --- |
| HostnameAllMatcher.kt | original checkout | 701dce301cf548941653f8714544f43190a585786e727dc149513511dfcfe4cf |
| PathRuleMatcher.kt | original checkout | b6cb1f8bff8f16968544ae0dd91a9e47d1cbb7fa19047a61f597c6695b39481f |
| HeaderTransforms.kt | migration-wave3-review | 5fe297c104f7ccf60ddd568bb22e8c56077f077d36d886c65e767022bc3ba51b |
| RequestRetryPolicy.kt | migration-wave3-review | 32116f960133d41e4b22a1a478e59aac29132604f2fd16576ab726c3552ef4d4 |
| ConfigMerge.kt | already present at base; equals original | bdef55fc34ef90cea0c9c9a4982e7223efef969741c16680ac059b6626eae534 |

Pinned upstream options.js SHA-256:
`411a38af92019b15e829446f22d23ba9221e56f44253fa9e660fa95d7b63b356`.
The fixture manifest supplies exact hashes for every request/response
implementation and matching/merge source.
