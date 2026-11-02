# Restricted remote configuration transport

This is an independent candidate built on `d6548e1aab12729254a7806f47a0028b3f491f4a`.
It does not change the accepted JSON5 parser, merge or repository, application,
Node payload, any file on disk, or any system-wide proxy, TLS or security state.
Creating a fetcher or session performs no network request. The application must
explicitly call `reloadWithFetcher`; `reload` and `reloadFiles` remain offline.

## Source comparison

The upstream reference inspected is the installed synthetic runtime snapshot
`port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules` in the
original checkout, not an assertion that this snapshot is current production:

| Relative reference | SHA-256 |
| --- | --- |
| `@blue-frontier/dev-sidecar/src/config-api.js` | `94b98a22febecf2b04d24a7291e8894f8a30175a927bb638b24c18ef344b5d5e` |
| `@blue-frontier/dev-sidecar/src/config/remote-config-url.js` | `0506473b203a2c2edb1c66bd117c3a9f3b73dd628bdd87ff348153c39f474e5e` |
| `request/lib/redirect.js` (`request` 2.88.2) | `4056494e80f0354a6253c6fc08a526bf10920c1b2bb517efeb1249945a1291672` |

Upstream explicitly GETs shared then personal, requires status 200, sends
no-cache/pragma and a browser-like UA, uses 30-second request inactivity and
60-second per-attempt overall limits, retries twice per configured strategy,
and prefers the local proxy with direct fallback. It periodically refreshes,
compares bodies, and stores changed valid remote layers. Its configuration URL
rule rewrites a leading case-insensitive HTTP URL to HTTPS once. The underlying
request redirect implementation follows GET redirects up to ten times and
allows origin/protocol changes. Its proxy route can disable TLS verification
when the proxy CA is absent; it can log URLs and response snippets.

This candidate deliberately differs:

* Explicit one-shot direct GET only, no scheduler, retry, proxy fallback, cache
  write, URL/query/body logging, or insecure trust fallback. `Proxy.NO_PROXY` is
  specified for each connection. The UA identifies the native fetcher and is
  not the upstream browser-like UA, so some servers/WAFs may reject it.
* Only a validated HTTPS URI is accepted, including after resolving every
  redirect. No userinfo, fragment, malformed authority, non-ASCII URI, or
  invalid port. The existing repository may perform its upstream-compatible
  one-time rewrite before calling the fetcher; direct HTTP input and downgrade
  redirects are never upgraded by this transport.
* Only 301, 302, 303, 307 and 308 redirect, at most three by default and at
  most ten if explicitly configured. Every hop must have the same HTTPS host
  and effective port; loops are rejected. No redirect body is consumed.
* Every hop creates an SSLContext from the platform default trust managers,
  configures HTTPS endpoint identification on each TLS socket, and uses a
  fail-closed hostname-verifier fallback. No trust-all verifier is provided.
  Internal connection/SSLContext injection exists solely for synthetic tests;
  the public constructor always uses platform trust and direct connections.
* Only status 200 is accepted. Wire and decoded byte caps apply independently
  (defaults 1 MiB each; per-call decoded cap can only reduce the configured
  cap). Only identity or explicitly declared gzip is accepted. The request
  advertises identity to avoid Android transparent decompression. The fetched
  bytes must be well-formed UTF-8, with an optional explicit UTF-8 or ASCII
  charset (ASCII also rejects non-ASCII bytes). Content-Type is absent or one
  of `application/json`, `application/json5`, `text/plain`, and
  `application/octet-stream`; at most one charset parameter is accepted.
  Malformed, duplicated, or other content headers fail closed. These constraints
  intentionally reject some responses accepted by upstream.
* Connect, read-idle, and whole-fetch deadlines are separately bounded. The
  total deadline applies to all hops and validation, not separately to retries.
  Redirect and response headers are limited after the platform has parsed them;
  URLConnection exposes no portable pre-parser header memory limit.

`https-upstream-mock.cjs` is an independent **synthetic approximation** of
upstream request options, not an invocation of the private function. It runs
only loopback HTTP fixtures to demonstrate request 2.88.2 follows same-origin
and cross-port redirects and returns 404 responses. It does not claim TLS or
full upstream end-to-end parity. The production fetcher never accepts HTTP.

## Cancellation and resource ownership

For an explicitly cancellable refresh, keep one `newSession()` for the entire
`reloadWithFetcher` call, invoke it off the UI thread, and close both the
session and fetcher afterwards. `Session.cancel()` is thread-safe and sticky:
it prevents subsequent requests in that session; a `true` result means an
active fetch was cancelled before its success linearization. It cancels its
future, asynchronously closes tracked sockets/stream and disconnects, while
the I/O worker also closes all resources in `finally`. The repository treats
that fetch failure as `FETCH`, preserving the exact previous snapshot; the
concurrent-cancellation regression verifies this using a real TLS connection.

The existing interface has no hook between the last successful `fetch` return
and repository publication. A `false` cancel result can mean there was no
in-flight fetch or its successful return already won; it cannot revoke a
snapshot published or being parsed/validated after that return. In particular,
callers must not present `cancel() == false` as proof a whole refresh was
aborted. Guaranteeing cancellation through the repository's final publication
requires an integration-level commit/cancel protocol or repository change,
outside this candidate's authorized scope.

A platform DNS/connect operation may ignore interruption or delay closure.
The caller's total deadline still returns, socket close/disconnect is attempted,
and each fetcher has at most two daemon I/O workers and two cleanup workers.
If a platform operation never returns, its worker/resource may linger; further
requests can fail `BUSY` rather than create an unbounded worker queue. No
portable URLConnection API can guarantee that a native DNS call has stopped.
Closing a fetcher permanently prevents new work, but cannot force such native
code to return.

## Evidence and remaining validation

`pwsh -NoProfile -File docs/native-config/run-tests.ps1` directly compiles with
JDK 21 and a 256 MiB heap and runs both
the existing tests and new real local `HttpsServer` tests. The server keypair
is generated in a temporary directory by JDK keytool on each run, trusted only
by the test client, and removed after the test; no certificate or key is
committed. Default system trust rejects that test certificate, and trusting it
does not bypass a hostname mismatch. Tests cover success, 404, same-origin,
cross-origin, downgrade and loop/limit redirects, streaming and compressed
size limits, invalid encoding, a stalled local TLS handshake, idle and total
timeouts, concurrent cancellation,
and old-snapshot preservation.
The candidate verification run reported 69/69 JUnit tests (55 baseline and
14 transport tests) and 3/3 cases in the upstream-request synthetic oracle.

The installed Android SDK `platforms/android-35/data/api-versions.xml` lists
`SSLParameters.setEndpointIdentificationAlgorithm` since API 24, and the
SSLSocket parameter accessors since API 9. This is API metadata, not Android
runtime evidence. No Gradle, Android API 24 device/emulator, TLS provider,
network-security-config, production CA/proxy, or real remote service was
tested. Android TLS/hostname behavior and app wiring require separate device
acceptance. This transport does not yet implement the upstream proxy-assisted
bootstrap required on networks that cannot directly reach the server.
