# Native proxy capability gates

This is a migration checklist, not an assertion that the native path is ready.
The real Lightning runtime still uses Node until the final integration gates
pass.

## Observable baseline

The pinned local dev-sidecar 2.3.1 default configuration has 56 hostname
intercept entries and 62 path-rule entries. A local read-only configuration
audit observed these behavior fields: proxy, SNI, verifyHost, requestReplace,
responseReplace, cacheDays, success/status, abort, options, backup and script
injection. These counts describe the default snapshot, not live remote or
personal configuration.

ECH is enabled for configured domains in that snapshot. NAT64 is configured
but has an empty default prefix. A native implementation must handle enabled
ECH behavior or explicitly leave the integration gate open; merely parsing
ECHConfigList is insufficient.

## Gates before switching Lightning

1. **Input and configuration:** load the supported local/remote layered data,
   preserve merge/deletion/order semantics, validate unsupported fields and
   keep user navigation settings intact.
2. **Rules:** preserve single/all hostname results, path captures/exclusions,
   interceptor activation and order/priority. Exercise representative real
   default rules plus independent boundary cases.
3. **Resolution and candidates:** real UDP/TCP/DoT/DoH transports, family and
   provider selection, presets, bounded caching, request feedback and
   per-destination-port history. NAT64 and HTTPS/SVCB remain separate gates.
4. **Transport:** real HTTP and CONNECT handling, downstream MITM and trusted
   leaf certificates, independent upstream destination/SNI/verifyHost/trust
   policy, streaming framing, timeouts, disconnects and stop cleanup.
5. **Application behavior:** request/response transforms, redirects, body
   mutation and compression where configured, bounded replay/retry and cache
   policy. Do not silently treat unknown interceptors as no-ops.
6. **ECH:** known-answer crypto vectors, structurally checked configuration,
   handshake/transcript/accept-confirmation correctness, certificate
   authentication and explicit fallback decisions. Test actual encrypted
   application data against an independent peer.
7. **Android:** lifecycle/ordinary/incognito proxy setup, actual SDK/provider
   compatibility, verified pages and request evidence, bind failure/recovery,
   cold/warm restart and data preservation.
8. **Packaging:** APK contains the native implementation and required rule
   data, with no Node executable, Node libraries/project archive, runtime
   startup dependency or hidden Node subprocess fallback.

Disabled desktop plugins, P2P services and OS system-proxy installers should
not be accidentally activated or ported merely because they exist upstream.
The supported Android behavior must be documented and tested independently
of package file counts.

## Bounded local script-route increment

The JVM runtime can explicitly inject a read-only catalog and exact allowed
origins through `NativeRuntimeScriptRoutes`. With the effective script gate
enabled, authorized GET/HEAD script routes use the real local-response path.
See `../native-runtime/local-script-routes.md` for the contract and offline
regressions. No assets or authorization are enabled by default.

This closes only local script serving for supplied, reviewed data. Configured
HTML/script injection and other body transformations still fail the runtime
audit. Android/browser acceptance and Node removal gates remain open.
