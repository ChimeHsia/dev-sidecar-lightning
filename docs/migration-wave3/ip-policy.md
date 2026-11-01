# IP candidate policy migration

Implemented in the isolated `wave3-ip-policy` worktree, based on
`754ff423b59c743cf94e68b3417c1fde4a6f42b7`. No runtime wiring or changes to
the first-wave `IpAddress.kt`. These production files use common Kotlin APIs
and have no Java imports.

## API and upstream behavior

`IpCandidatePolicy` has:

- `selectPreset(preset: Any?, ignorePreset: Boolean = false): PresetSelection?`
  consumes the preset value **after hostname matching**. A null result requests
  normal resolution; otherwise `addresses` and `isPreset == true` describe the
  preset. Arrays preserve duplicates and order. Object flags disable only JSON
  false, null, numeric zero, empty string, and the exact strings `"false"` and
  `"0"`. Empty arrays and objects used as flags remain truthy. JavaScript
  enumeration order is preserved: canonical unsigned array-index keys from
  `0` through `4294967294` come first numerically; other keys follow map iteration
  order. Supply insertion-ordered maps from a JSON reader. Result storage copies
  input strings, and every `addresses` access returns a defensive list copy;
  metadata objects are never retained.
- `dnsCandidates(candidates: List<String>?, hostname: String, ipv6Available: Boolean)`
  mirrors the uncached `BaseDNS.lookup` candidate filter and hostname append.
  It returns a new list, keeps zero addresses and duplicate candidates, filters
  IPv6 only when unavailable, and appends the hostname even if already present.
  The appended hostname is never filtered. It does not select/cache a candidate.
- `speedCandidates(candidates: List<String>)` returns a new list excluding zero
  addresses, for either fresh speed candidates or historical backup candidates.
  It preserves ordering and duplicates. SpeedTester deduplication, provider
  attribution and Cloudflare rewriting remain outside this primitive.
- `isZeroIp(input: Any?)` and `isIpv6(input: String)` are compatibility policy
  classifiers derived from the original `util.ip.js`, **not strict validators**.

`Ipv6Availability.fromInterfaceAvailability(available: Boolean)` starts an
immutable availability epoch at count zero. `reportUnreachable(address)` returns
the next state. Only addresses classified as IPv6 count, three cumulative reports
disable IPv6, and the count saturates at three. An initially unavailable epoch
stays unavailable at count zero. Reports on unavailable states have no effect.
Callers supply interface capability and report only applicable `ENETUNREACH`
events; the upstream caller gates on that error code and an intercepted IP.
No error-code classification, interface discovery, timers, recovery, success reset
or global state is introduced here.

The interface boolean intentionally places upstream's `os.networkInterfaces()`
scan at the adapter boundary. This module does not reinterpret upstream's
case-sensitive `family === 'IPv6'` / `startsWith('fe80:')` scan.

## Compatibility classifiers and strict parsing

The existing `IpAddress` parser remains unchanged. It validates complete literals.
The policy adapter preserves upstream behavior instead of replacing it with that
stricter contract:

| Input | Upstream/policy behavior | Strict `IpAddress` |
| --- | --- | --- |
| `" :: "`, `"\uFEFF::"` | zero; ECMAScript whitespace trimmed | rejected |
| `"[::"`, `"::]"` | zero; each outer bracket independently removed | rejected |
| `"[0.0.0.0]"` | zero | rejected |
| `"[[::]]"` | not zero; only one bracket from each end removed | rejected |
| `"::0.0.0.0"` | IPv6, but not zero under the colon-group zero test | valid zero |
| `"::%eth0"` | not zero | valid zero |
| `"garbage["` | IPv6 by unconditional `[` shortcut | rejected |
| `"2001:db8::1%eth0"` | not IPv6 under upstream regex | valid IPv6 |
| `"\u0085::"` | not zero; U+0085 is not ECMAScript trim whitespace | rejected |

`isZeroIp` handles only strings and returns false for other input types. It adapts
ECMAScript whitespace and single outer brackets, then reuses `IpAddress` to check
the resulting colon-only IPv6 literal. It excludes dotted tails and scopes to
preserve upstream's group test. The permissive `isIpv6` uses the original regex
and `[` shortcut; it intentionally does not call `IpAddress.isIpv6`.

## Intentional schema boundary

The supported preset transport is null, a dense JSON array of strings, or a plain
string-keyed JSON object whose values are JSON enable flags/opaque metadata.
Non-IP strings are retained as upstream does. There is no added literal validation.
Nested metadata is opaque, so callers must already have JSON-shaped data; this
API is not a recursive serializer or object graph validator.

`ip-policy-rejections.json` is a **separate** assertion suite, not an upstream
parity corpus. Scalar presets, non-string array entries and objects with a
`length` property throw `IllegalArgumentException`. In upstream these can be
ignored, enumerate incidental scalar properties, return heterogeneous arrays or
throw during `.slice()`. Sparse arrays, prototypes/inherited enumerable keys,
functions, undefined, symbols and custom array-like objects are outside JSON
transport. Non-string map keys, non-finite numeric flags and non-JSON scalar flag
types are also rejected. An explicit ignore flag bypasses preset interpretation
before these checks, matching upstream's early branch.

## Oracle and fixture provenance

Read-only source:

```text
D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src
```

SHA-256 of the exact inspected files:

| Relative source | SHA-256 |
| --- | --- |
| `lib/dns/util.ip.js` | `0db0c7a14304b17403aea1753e9517aa43a340d76af12a53d978d527f2315f44` |
| `lib/dns/base.js` | `60d1cbe1c24d4a6d44391bf4d4dd630b739d3bcf84f7ec0c4b5e39b3e1ec7284` |
| `lib/speed/SpeedTester.js` | `f4ff4dcb53f4d4a9ae8486f95bbc596860f021fa984bd3b1ed3dac711341159f` |
| `utils/util.match.js` | `59450826eea8f4f73ffe56e021a577c5a5faa663d6b446482190fa3c27ca4d6a` |
| `lib/proxy/mitmproxy/createRequestHandler.js` | `64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690` |

`port/tests/oracles/wave3/ip-policy.cjs` checks the first three hashes before
execution. It directly requires `util.ip.js` and evaluates the actual
`base.js` and `SpeedTester.js` source in an isolated Node VM. Dependency stubs
provide logging, an already matched preset, deterministic interface capability
and a cache observation hook; they never implement the candidate policy.
The speed case calls the original `getIpListFromDns` prototype method, bypasses
its active constructor, supplies deterministic answers and identity route
rewriting. Its fixture has unique survivors because deduplication is outside the
list filter API. Historical speed cleanup uses the same original zero predicate;
the Kotlin duplicate-preservation assertion is scoped to that filter alone.
The DNS case observes the list passed by actual `lookup` to `setBackupList`,
without testing DynamicChoice selection.

The VM appends a test-only accessor for `reportIPv6Error` and its counters.
Upstream assigns `module.exports.reportIPv6Error` before replacing
`module.exports` with the class, losing the exported property. The fixture tests
the existing closure's behavior; this migration does not fix or wire that export.

The 56 accepted cases use `[{name,input,expected}]` with `input.operation`
equal to `zero`, `ipv6`, `speed`, `preset`, `dns`, or `availability`. Regeneration
and verification are separate:

```powershell
$sourceRoot = 'D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src'
node port/tests/oracles/wave3/ip-policy.cjs --generate --upstream $sourceRoot
node port/tests/oracles/wave3/ip-policy.cjs --verify --upstream $sourceRoot
```

Only `--generate` writes the accepted fixture. `--verify` replays the stored
cases against actual source and leaves the fixture bytes unchanged. Rejection
fixtures are authored assertions and never regenerated from Node.

## Validation and integration boundary

Local validation on 2026-11-02 used original-repository OpenJDK 21.0.2, cached
Kotlin compiler 2.0.21, JUnit 4.13.2 and Hamcrest 1.3, with a 512 MiB compiler
heap and 256 MiB test heap. No Gradle daemon was started:

- Compiled the unchanged `IpAddress.kt`, both new policy production files,
  `CheckIpPolicy.kt`, the unchanged `IpAddressTest.kt` and both new JUnit files
  using `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`, target JVM 17.
- Ran `org.junit.runner.JUnitCore` for `IpAddressTest`, `IpCandidatePolicyTest`
  and `Ipv6AvailabilityTest`: **14 tests passed**. The replay test independently
  reads the committed-style JSON fixtures in Kotlin, requiring neither Node nor
  upstream source at test time. Nine intentional JSON schema rejections pass.
- Node v24.15.0 `--verify`: **56 upstream fixtures passed**, with fixture hash
  unchanged before/after verification.

The JVM fixture reader locates `port/tests/fixtures/wave3` by walking upward from
`user.dir`, so root-project and `netcore` Gradle test working directories work.

`port/tests/wave3/src/jsMain/kotlin/CheckIpPolicy.kt` supplies exactly
`internal fun checkIpPolicy(input: Map<String, Any?>): Any?` for the parent's
cross-target replay wrapper, returning JSON-like values with the accepted fixture
output shapes. It introduces no exports or production wiring. Actual Kotlin/JS
compilation and aggregate checks belong to the parent review worktree and are
not claimed by the standalone JVM result.

No changes to existing Kotlin files, Gradle files, payloads/assets, adapters,
main checkout or device state. No commits, ADB, network or DNS calls were used.
