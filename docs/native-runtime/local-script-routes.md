# Opt-in local script responses

Date: 2026-11-02. Base: `2ce4277`. This increment binds the existing
`ReadOnlyScriptCatalog` and `ScriptRouteService` to the actual
`NativeRuntimeRuleAdapter` request boundary. It does not enable response-body
rewriting or HTML injection.

```kotlin
val scripts = NativeRuntimeScriptRoutes(
    sources = reviewedSources,
    allowedOrigins = setOf("https://approved.example"),
)
val runtime = NativeRuntime.fromDecoded(
    decodedConfiguration,
    injectedAuthority,
    scriptRoutes = scripts,
)
```

Both an explicit `scriptRoutes` input and the existing effective
`server.setting.script.enabled` gate are required. No scripts or origins are
provided by default. The origin comparison includes scheme, canonical host
and effective port; wildcard hosts, credentials, paths, queries and fragments
are not authorization syntax. Outside that set, requests use the ordinary
rule/origin path. The catalog snapshots bounded data and never evaluates,
fetches or scans scripts. No production script assets are included here.

An authorized script-prefix request is served before rule forwarding and
origin resolution. Builtin keys use `/____ds_script____/<key>`; `global`
maps to `tampermonkey`. Proxy-prefix aliases resolve only explicit catalog
keys. GET serves bytes, HEAD retains the same representation length while
the existing engine suppresses its body, unknown keys return 404, and other
methods return 405. Invalid references fail before a response is committed.
Missing keys cannot fall through to an upstream script URL.

The adapter admits at most 64 KiB per asset to match the engine's existing
`LocalResponse` contract. UTF-8 byte size, including generated userscript
wrappers, is checked by the catalog. Construction also checks the configured
asset limit against the engine's body quota before DNS executors are
allocated. No engine API or framing implementation is changed.

The runtime supplies the full representation to `LocalResponse` even for
HEAD. This is intentional: the engine validates Content-Length against that
representation and independently emits no HEAD body. Passing the script
service's already-empty HEAD body would lose metadata or produce a 502.

## Offline regression

Run `docs/native-runtime/run-body-tests.ps1` with the official pinned
Conscrypt 2.8.0 JAR and existing Go executable. It holds
`Local\DshaNativeRescueBuild`, polls `WaitOne(1000)`, and releases the mutex
in `finally`. It invokes the existing bounded-memory aggregate runner and
uses cached dependencies and loopback test peers.

New runtime cases cover real HTTP requests, verified downstream CONNECT
MITM, GET/HEAD length parity, explicit aliases, global/bootstrap alias,
404/405/invalid references, disabled and unauthorized origin forwarding,
normal origin traffic after local requests, immutable catalog snapshots,
the exact 65,536-byte UTF-8 boundary, oversize rejection, invalid origin
authorization, and engine quota admission. CONNECT still resolves its
bound authority through the existing resolver; a script request within the
tunnel does not make an additional upstream connection.

The final aggregate run on 2026-11-02 compiled all main/test Kotlin and the
Java Conscrypt adapter with cached Kotlin 2.0.21, JDK 21, BC 1.79 and the
official pinned Conscrypt 2.8.0 JAR, then built the independent Go peers with
`GOPROXY=off`. All 67 JVM suites ran: **948 executed, 946 passed, two
external-network checks skipped by assumption, zero failures**. This adds
six runtime integration tests to the 942-test base. JUnit execution took
39.632 seconds; compilation is additional. The machine-readable local report
is `port/.run/native-core-review/junit-summary.json` (ignored build output).
`git diff --check` also passed. No dependencies were downloaded or added.

## Gates still open

Configured response `script`/`tampermonkey`, HTML/body append and replacement
remain rejected by the runtime configuration audit. Merely supplying this
catalog does not relax those checks. Brotli, production asset review,
response entity interception, automatic script tag generation, retries,
backup selection and entity-cache integration remain separate work.

This is a local JVM integration claim. It does not establish Android
provider/desugar/R8 compatibility, WebView execution/CSP behavior, browser
lifecycle or ordinary/incognito operation. Browser activation and Node
packaging removal remain disabled. No device operation, Node payload,
configuration migration, on-disk CA or user private key is involved.
