# JVM native runtime assembly

Managed worktree:
`C:\Users\HONOR\.codex\worktrees\native-runtime-assembly\dev-sidecar-lightning`.
Base: `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
Accepted prerequisites were copied from
`C:\Users\HONOR\.codex\worktrees\native-core-review\dev-sidecar-lightning`,
commit `de9532717eb6fe925cddef3bb77f0a01859ffb5c`.

`prerequisites.json` records the exact SHA-256, source path, and source commit
of all 59 copied files. It includes main-source modules, the frozen 52-test
engine suite, the ephemeral CA helper, and reference docs. No copied
prerequisite was edited. In particular, NativeProxyServer, NativeTlsDialer,
NativeTlsPolicy, RulePlanner, RuleActionExecutor, codecs, DNS transports and
certificate classes retain the accepted bytes.
The subsequent schedule handoff adds 15 files under its separately recorded
`schedule-prerequisites.json`; those copied files are also unchanged.

This is a working JVM integration, not Android application activation.
There are no Android/global-proxy calls, Node processes/payload changes, CA
files, persistence calls, external sites, phone operations or commits.

## Public API

```kotlin
val snapshot = NativeRuntimeConfig.decode(decodedJsonMap)
// Or NativeRuntimeConfig.decodeLayers(orderedDecodedLayers).
val runtime = NativeRuntime(
    config = snapshot,
    authority = injectedAuthority,
    options = NativeRuntimeOptions(),
    tlsDialer = injectedDialer,                  // default NativeTlsConnector()
    upstreamTrustContext = originTrustOrNull,    // never reinitialized
    echRequirement = requiredEchPreflightOrNull,
    dnsTrustContext = providerTrustOrNull,       // separate from origin trust
)
val listeningAddress = runtime.start()
runtime.close()
```

`NativeRuntime.fromDecoded(...)` is a convenience factory with the same
principal configuration/TLS inputs. Constructor `endpointResolver` controls
DNS-provider bootstrap only. By default it uses the accepted explicit SYSTEM
bootstrap adapter; tests configure literal provider addresses and never send
origin hostnames to system DNS.

`config.decoded` is a bounded, deeply immutable, sanitized configuration
snapshot (64 layers/depth and 100,000 nodes per input, inherited from accepted
JSON prerequisites). Changing input containers cannot change a running
runtime. `config.diagnostics` reports the deliberately limited capability
set and nonbound config fields. There is no live configuration apply/reload:
the repository adapter can construct a new snapshot/runtime and explicitly
coordinate a later application switch.

The decoded input can have a `server` object or be the server object itself.
The bindings in this assembly are:

```json
{
  "server": {
    "port": 0,
    "setting": {
      "script": {"enabled": true}
    },
    "dns": {
      "providers": {
        "local": {
          "type": "udp",
          "server": "127.0.0.1",
          "port": 53053,
          "family": 4
        }
      },
      "mapping": {"*": {"dnsName": "local"}}
    },
    "preSetIpList": {},
    "intercepts": {
      "browser.test": {
        "^/source/(\\w+)$": {
          "requestReplace": {"headers": {"X-Rule": "applied"}},
          "proxy": "https://origin.test:9443/mirror/${p[1]}",
          "sni": "routing.test",
          "verifyHost": "origin.test",
          "options": true,
          "responseReplace": {"headers": {"X-Response": "streaming"}}
        }
      }
    }
  }
}
```

`server.port` is honored, including zero for an isolated ephemeral port. A
conflicting nonzero runtime option is rejected before transport allocation.
If absent, `options.proxy.port` applies. `runtime.proxyOptions` exposes the
effective engine options; `options` preserves the supplied options.

Unknown active DNS keys, provider transport types and unbound server/settings
features are rejected at config load with named, countable blocking issues.
Explicitly disabled feature objects (`enabled: false`), empty containers and
false/empty values do not require implementations. This is not a
claim that all fields in the real public default configuration are supported.
Preset IPs can also be supplied under `dns.preSetIpList`; server-level
presets take precedence when nonempty. Native DNS provider normalization,
selection and cache logic are the accepted prerequisites.

### Caller normalization before frozen helpers

The runtime now applies the relevant pure `options.js` caller logic before
constructing either the planner or the DNS catalog/resolver. The original
`decoded` snapshot remains unchanged; the runtime's effective DNS options
contain the normalized mapping and NAT64 flag.

Source checked read-only:
`D:/File/User/code/dev-sidecar-lightning/port/.run/device-wave2-20261102/runtime/core/node_modules/@blue-frontier/mitmproxy/src/options.js`,
SHA-256 `411a38af92019b15e829446f22d23ba9221e56f44253fa9e660fa95d7b63b356`
(matches the accepted source pin).

The NAT64 declaration requires `enabled === true` and a nonempty **array**
of domains. The effective flag additionally requires a nonblank prefix
after ECMAScript whitespace trimming. In particular, the default-like
`{enabled:true,prefix:"",domains:["*chatgpt.com"]}` is inactive. It neither
creates a Nat64 descriptor nor forces an ECH/route decision. Both planner
and provider initialization receive `enabled:false`; a blank-prefix
declaration adds a diagnostic without blocking ordinary DNS/HTTP traffic.
Disabled NAT64 does not require a bound transport.

The source's options gate checks prefix nonemptiness, not IPv6 syntax.
An active nonblank-prefix declaration is still an explicit unsupported
configuration until Native NAT64 transport integration; an invalid nonblank
prefix is not quietly interpreted as permission to bypass that failure.
Active non-string prefix values are rejected rather than accepting object
coercions.

`handleDnsMapping` behavior is implemented without mutating caller data:

- A string mapping `"host": "provider"` becomes
  `{"dnsName":"provider","family":4|6}`.
- Only the exact matching `dns.familyMapping[host]` key is consulted.
  `Number.parseInt(...) === 6` chooses IPv6; otherwise the string mapping
  defaults to IPv4. Scalar decimal/hex prefixes and source-style whitespace
  are handled; complex family-map values are rejected explicitly.
- Object mappings retain their own fields/family; familyMapping does not
  overwrite them or act as a second hostname matcher. Their provider's
  family remains the default when the object has no family.
- Null mapping entries are removed by the existing snapshot sanitizer.
  Missing/non-string dnsName, unsupported object family/active extra fields,
  or non-string/non-object mappings produce config-load issues. This
  intentionally fails closed where the source would log/drop malformed
  entries.

## Real execution chain

1. NativeProxyServer validates the client head and untrusted transformation.
2. NativeRuntimeRuleAdapter builds the original `RuleRequest`, preserving
   duplicate headers as lists and keeping host/path metadata for captures.
3. RulePlanner selects implementations and RuleActionExecutor executes the
   request plan. Success, abort, redirect and OPTIONS use a terminal local
   response before any route resolution/upstream socket. Static HEAD retains
   representation length without sending the entity.
4. A forward plan crosses the engine's existing trusted policy boundary.
   The adapter prepares the exact route/TLS metadata, while the engine still
   validates the rewritten head's authority, framing and negotiation.
5. NativeRuntimeRouteResolver waits on the asynchronous NativeDnsResolver
   future **inside RouteResolver.resolve**, with one total wait deadline.
   DNS waiting deliberately occurs outside `onRequest`: the frozen engine
   wraps policy errors as 502, while resolver SocketTimeoutException retains
   its intended 504 classification.
6. SocketDnsTransport performs real UDP/TCP/DoT/DoH wire I/O, using provider
   bootstrap/trust independently. The runtime requires a literal selected
   destination and constructs a resolved InetSocketAddress. Unmapped hosts,
   negative/empty answers, exhausted choices or unresolved hostname fallback
   fail explicitly; origin system resolution is not silently substituted.
7. NativeProxyServer opens the real upstream socket. TLS uses the injected
   NativeTlsDialer, with server-owned raw-close handshake/exchange deadlines.
   In CONNECT MITM the downstream leaf is for the client CONNECT host, while
   TCP destination, upstream SNI and verification identity remain independent.
8. NativeRuntimeRuleAdapter retains the original plan on the bounded engine
   worker and calls only RuleActionExecutor.transformResponseHead for final
   entity heads. The result never reads or buffers the body. Informational
   and 101 handshakes remain the engine's responsibility.

The copied engine currently invokes request, route and response hooks on
the same connection worker. Two thread-local slots hold at most one prepared
route/plan per bounded worker, are cleared on each request, and cannot grow
with the number of requests. A future asynchronous hook API must carry
explicit exchange context instead of moving these callbacks across threads.

## Trust, ECH and clocks

Runtime upstream trust is deliberately **STRICT**. The planner may describe
legacy compatibility relaxations for inline SNI/settings; this assembly
does not map those reasons to UNVERIFIED. Diagnostics state that choice.
Independent SNI/verifyHost tests prove that a trusted chain with a wrong
verification identity still returns 502. A later opt-in compatibility
decision needs its own explicit runtime policy and tests.

`upstreamTrustContext` is used only for origin TLS policy.
`dnsTrustContext` is used only by SocketDnsTransport for provider TLS.
Neither context is reinitialized. When no provider context is supplied, a
new TLS context uses platform trust, without reading/replacing the global
default context. Global hostname verification, security providers and trust
defaults are never mutated.

ECH configuration with active domains requires both a nonordinary injected
dialer and `NativeRuntimeEchRequirement.requireConfiguration(route)`.
The latter is a memory-only availability/config check with
`NativeRuntimeEchRoute(innerHost, verifyHost, port)`. The parent can adapt the
separate NativeEchConfigResolver to it and inject the matching REQUIRED
composite dialer. The composite itself must enforce REQUIRED/fail-closed
ECH; the callback is not cryptographic evidence of ECH negotiation. Ordinary
NativeTlsConnector is explicitly rejected for an ECH-required snapshot.
No Conscrypt implementation or dependency is copied into the runtime.
This suite tests that unsafe default wiring is rejected, not the independent
provider's already separate Go ECH proof.

DNS TTLs and route wait deadlines use monotonic System.nanoTime, independent
of wall-clock changes. Injected runtime `Clock` controls OPTIONS Date and the
constructor's root validity precheck. It does **not** replace the authority's
own issuance/validity clock: create the injected authority with a coherent
clock when deterministic certificate-time testing is required.

## Configuration and execution limits

The actual upstream `setting.script.enabled` value gates response header
interception. A true global flag is accepted with an explicit header-only
diagnostic; it does not make all native execution fail.
Only the planner's GLOBAL_SCRIPT_MIDDLEWARE marker is acknowledged at that
boundary. Concrete script/tampermonkey/unknown action issues remain blocking.
With the flag false, response replacement and after-OPTIONS CORS actions are
not applied. Static/preflight request actions retain their own semantics.

Enabled retry, backup, concrete script injection, external/body actions and
framing/encoding/negotiation-changing header replacements are rejected at
config load. Positive cache descriptors now execute only the pinned
timestamp-304/browser-header operations, with no native entity store.
The string form `cacheImmutable: "false"` is rejected where its legacy
mixed-coercion branch cannot fit the frozen Boolean descriptor.
No attempt scheduler is silently invented. Effective enabled NAT64 provider execution is also not bound in
this assembly; a raw enabled checkbox with an empty prefix remains inactive.
The default real configuration's backup/script entries still need future
implementation or explicit supported configuration; cacheDays support alone does not
establish full public-default parity.

The frozen engine disallows HTTPS-to-HTTP upstream downgrades. An explicit
HTTP proxy target is diagnosed at load and can serve plaintext requests,
but an HTTPS request requiring that route fails explicitly. OPTIONS `*`
without a local preflight action is outside the executor's origin-path
forwarding contract. Request transforms cannot relabel body length/encoding.
No runtime body processor, complete script-asset serving, retry, body replay,
upstream pool, memory entity-cache integration or backup scheduler is provided.
See `scheduling-integration.md` for the supported cache closure and proposed
precommit attempt/body-observer interfaces.

Bodies use the engine's configurable streaming policy; defaults allow
65,536-byte aggregate heads and unlimited streamed body bytes, subject to
deadlines. Local entities are capped at 64 KiB. The 65 MiB integration test
applies real CORS/cache/header rules while counting bytes directly into a sink;
it never materializes the complete response.

## Candidate feedback: precise remaining gate

`NativeRuntimeRouteResolver.select(host, port, tls)` returns a
`NativeRuntimeRouteSelection(route, dnsResult)` ticket. Selection is not
reachability or success. Runtime engine requests currently take one literal
candidate and do not call feedback, even after a DNS answer.

`feedback(ticket, NativeRuntimeSocketOutcome.CONNECTED/CONNECT_FAILED)` is
ready for an actual engine attempt observer. Tickets are runtime-owned and
single-use; stale cache-entry feedback remains guarded by NativeDnsResolver.
The test reports CONNECT_FAILED only after a real Socket.connect failure to
127.0.0.2, then proves the next request selects 127.0.0.1. Another test proves
ordinary repeated failed engine requests do not invent feedback or failover.
This distinction is intentional, not an automatic failover claim.

Before production integration, parent review should choose a minimal
nonmutating optional engine observer:

- after a real TCP connect succeeds: `(route, attemptToken, Connected)`;
- after TCP connect fails/times out: `(route, attemptToken, ConnectFailed)`;
- report TLS/protocol failure separately, never equate it with TCP/DNS failure;
- maintain exact ticket identity across concurrent equal-host requests;
- optionally choose a bounded next literal candidate within the original
  connect/exchange deadline **before writing request bytes**, with no body
  replay or silent trust downgrade.

No such observer or route-attempt loop was added to the frozen engine in
this handoff. Same-request candidate failover remains an explicit gate.

## Lifecycle and validation

NativeRuntime is single-use: NEW -> LISTENING -> STOPPED, or FAILED on bind
failure. Start returns the actual bound loopback address. Bind failure
closes DNS resources. Close is idempotent: it cancels route/DNS waits first,
closes the real transport through resolver ownership, closes the engine and
active sockets, and clears the in-memory leaf cache. `failure`, `state`,
`localAddress`, `activeSocketCount`, `dnsState`, `transportState` and route
state expose observations. A late fatal engine failure is reflected by
runtime state; callers still own closing the runtime.

Run `.\docs\native-runtime\run-tests.ps1`. It directly compiles all accepted
main sources plus the frozen 52 engine tests, ephemeral test CA helper,
runtime integration suite, and the 57 copied schedule tests, with cached Kotlin 2.0.21/BC 1.79/JUnit 4.13.2 and
JDK 21. Compiler and JUnit both use `-Xmx256m`; JVM 17 bytecode/logs stay under
ignored `build/native-runtime`. The inherited DnsClient deprecated URL
constructor warning is unchanged.

The runtime suite covers real UDP-to-HTTP routing, verified TLS MITM,
proxy path captures/header changes/independent SNI and verifyHost, strict
rejection with continued service, zero-DNS/upstream local actions, HEAD,
header gate on/off, 65 MiB CORS streaming, unsupported config rejection,
immutable layered snapshots, isolated ports/bind failure, timeout 504,
in-flight DNS cancellation, no hostname fallback, honest single-candidate
behavior and explicit real-socket feedback, REQUIRED-ECH default rejection,
independent DoT custom trust, clock behavior, config port conflicts, active
origin stop/thread cleanup, and unchanged global TLS/provider state.

Completed options-audit milestone: **83 tests passed** (31 runtime integration
tests plus the frozen 52 engine tests). This is a targeted assembly suite;
it does not replace the parent's independent 483-test prerequisite aggregate.
The options-audit regressions cover inactive/default/ECMAScript-whitespace
NAT64 gates, explicit active-NAT64 rejection, real HTTP through ordinary DNS
with the default blank prefix, family normalization/object preservation,
real A and AAAA queries selected by familyMapping, exact blocking-issue
counts for enabled unknown features, and acceptance of disabled features.

Current schedule-integration verification: **150 tests passed**:
41 runtime integration tests, 52 frozen engine tests and 57 frozen scheduling
tests (including 20 generated upstream-oracle cases). Runtime evidence now
includes actual 304 hits/unchanged unconditional origin attempts, verified
MITM proxy cache round-trip with one origin TLS attempt, response-action
order/gating, HEAD/65 MiB streaming, exactly one POST side effect, and no retry
after a truncated committed response. These conditional hits are source
timestamp behavior, not a claim of MemoryResponseCache entity hits.

Android acceptance, config repository persistence/reload, REQUIRED-ECH
composite assembly tests, production attempt feedback/failover, default-rule
retry/backup, opt-in entity cache and script/body support remain subsequent work.
