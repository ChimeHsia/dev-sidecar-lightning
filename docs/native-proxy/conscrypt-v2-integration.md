# Conscrypt ECH integration candidate

This isolated candidate adds a strict Conscrypt 2.8.0 ECH dialer to the native
proxy. A required ECH request cannot fall back silently to ordinary TLS.
Explicitly non-ECH routes retain the existing transport policy. The adapter
uses version-pinned provider internals, so dependency updates require renewed
compatibility, R8 and device verification.

On JDK 21, `netcore/src/test/fixtures/conscrypt/integration-check.ps1` builds
two independent Go peers and runs 56 tests. The 2026-11-02 run passed with
zero failures, including an actual CONNECT/MITM proxy to ECH to Go HTTP origin,
and rejection, tamper, wrong-host, expiration, cancellation and shutdown
cases. It requires a local Go toolchain and an official Conscrypt JAR matching
the runner's SHA-256. The initial run could not find the basic ECH peer; the
runner now builds both peers before testing.

Separate Android 16 / SDK 36 debug and R8 probe APKs each passed an accepted
ECH exchange and rejection/wrong-host negative cases against the independent
peer. This does not establish compatibility with older Android releases or
prove that ECH is wired into the browser.

Configuration discovery, DNS result selection, application lifecycle and
Android dependency/keep-rule integration remain separate acceptance gates.
The browser still uses Node and is not modified by this candidate.
