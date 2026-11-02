# Runtime integration review

Date: 2026-11-02. This isolated workspace combines the frozen runtime assembly
with the newer DNS, NAT64, configuration, script and Conscrypt candidates.
Earlier `freeze-150.md` and prerequisite manifests describe their original
handoff; they are historical evidence, not the current aggregate result.

The current aggregate runs 884 tests: 882 pass, two external-network smoke
tests are skipped by assumption, and none fail. It compiles the Java policy
adapter, Kotlin sources and independent Go ECH peers. Use
`scripts/test-native-core.py` with JDK 21, the pinned Conscrypt JAR and Go.
The PowerShell entry point here delegates to that same runner with
`-ConscryptJar` and `-GoExe` arguments.

The integration tests exercise actual local DNS and HTTP/CONNECT MITM traffic,
including source-compatible 304/cache headers. A new repository-to-proxy test
loads JSON5 configuration, verifies the Android seed is accepted, checks that
disabled or unsupported configurations are rejected without replacing the
previous snapshot, and verifies normal requests still reach a local origin.
The runtime now explicitly accepts `server.enabled=true` and refuses to start
disabled configurations. Test traffic binds ephemeral loopback ports.

Review also fixed the route resolver's concurrent shutdown iteration. Tests
deterministically reproduce a collection becoming empty after its size was
observed and verify cancellation can remove entries during traversal.

The browser still runs the accepted Node payload. This workspace has not
activated an Android runtime or changed the device. Automatic DNS-to-ECH
handshake wiring, retries/backup feedback, body-processing hooks, production
script assets/Brotli and Android lifecycle/dependency compatibility remain
open. Successful standalone components do not close those integration gates.
