# Offline ECHConfigList parser and metadata selection

This isolated implementation provides an offline byte parser and metadata choice
policies. It implements no TLS, ECH handshake, HPKE, cryptography, key derivation,
DNS resolution, socket handling, retry orchestration, or production adapter.
A parsed or selected config does not establish cryptographic usability or
handshake capability. Deterministic fixture keys are arbitrary octets.

Base: `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
Production source: `netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt`.
There are no production dependencies, `java.*` imports, other wave3 dependencies,
root build changes, exports, or wiring changes. The file uses common Kotlin APIs;
its location in the existing JVM source tree does not itself wire it into JS.

## Public API

Package: `com.dsha.sidecar.net.dns`.

```kotlin
EchConfigList.parseEchConfigList(data: ByteArray): EchConfigListResult
EchConfigList.pickEchConfig(configs: List<EchConfig>): EchConfig?
EchConfigList.selectEchConfig(configs: List<EchConfig>): EchConfig?
```

`EchConfigListResult.Success.configs` preserves wire order.
`Failure.error` contains `code: EchConfigErrorCode`, `offset: Int` (absolute input
byte offset), and `field: String`. A failure exposes no partial result.
Callers must check the result before passing configs to a picker. A successfully
parsed list may contain only unknown versions and have no selectable candidate.

`EchConfig` exposes `versionCode`, `length`, `raw: ByteArray`, and
`contents: EchConfigContents?`. `raw` contains only ECHConfig contents, excluding
the version and length header, matching upstream. Unknown versions retain their
opaque contents and have null `contents`. Known version `0xfe0d` contents expose:

- `configId`, `kemId`, `publicKey: ByteArray`.
- `cipherSuites: List<EchCipherSuite>` with `(kdfId, aeadId)` unsigned 16-bit IDs.
- `maximumNameLength`, `publicName` (case preserved).
- `extensions: List<EchConfigExtension>` with `type`, `data: ByteArray`, and
  `isMandatory` (the high bit of the type).

All byte-array getters and list getters return detached copies. Stored models
have no public setters or public constructors that accept unvalidated contents.
Unknown KEM/KDF/AEAD IDs remain numeric and inspectable. No string/base64 overload
is provided; callers must bound and decode external text before calling this API.

## Actual upstream behavior inspected

Read-only upstream capture:
`port/.run/offline-proxy-fixes-20261101/runtime/core/node_modules/@blue-frontier/mitmproxy/src`
in the original repository. All inspected source SHA-256 values are recorded in
`port/tests/fixtures/wave3/ech-config.provenance.json`.

- `lib/dns/util.svcb.js`: `parseEchConfigList` returns flat uint16 cipher IDs;
  `pickEchConfig` accepts entries with a nonempty key and name and prefers the
  first X25519 entry, otherwise the first such entry.
- `lib/dns/base.js`: the initial DNS ECH path calls that looser picker.
- `lib/proxy/common/ech/ech.js`: `supportedCipherSuite` consumes consecutive
  `(KDF, AEAD)` pairs. `selectEchConfig` applies `isUsableEchConfig`: version
  FE0D, X25519, nonempty key, bounded DNS label lengths, and an advertised `(1,1)`
  pair. These checks are not cryptographic validation.
- `EchHttpsAgent.js`: initial connections take the DNS-picked config; retry
  configs go through `ech.selectEchConfig`.
- `tls13.js`: consumes `maximumNameLength`, `publicName`, and the ECH context.
  `ech.js` encodes `version || length || raw` for that context; `tlsSocket.js`
  passes the selected config through. `hpke.js` defines the algorithm IDs.
  None of these handshake operations is ported or invoked by the oracle.

The Kotlin `pickEchConfig` preserves the upstream X25519 preference and wire-order
fallback among structurally valid understood candidates. It may return a config
with unknown KEM, KDF, or AEAD IDs. `selectEchConfig` adds the actual caller's
FE0D/X25519/(1,1) ID profile. A supported pair may occur anywhere in the vector;
IDs from adjacent pairs must never be combined.

## Deliberately stricter policies

- Exactly one list is accepted, with exact outer length, no trailing bytes, a
  nonempty list vector, and at most 65537 total bytes. Each nested cursor is
  bounded by its own parent. No clamping to available input or reading past the
  declared list boundary.
- Malformed known-version contents reject the entire list, including an earlier
  valid sibling. Upstream can return partial objects with `parseError`, then
  select them because the required fields had already been populated.
- Unknown versions are opaque and never selected; their zero-length opaque
  contents are allowed. Upstream attempts the FE0D field layout for every version.
- Public keys are nonempty. Known KEMs require encoded lengths P-256=65,
  P-384=97, P-521=133, X25519=32, X448=56. Unknown KEMs accept any nonempty bounded
  key vector. This checks length only, not point encoding, group membership,
  low-order keys, security properties, or key acceptability to any provider.
- Cipher vectors are nonempty and divisible by four bytes, with complete pairs.
  Unknown IDs are retained; they are not parse failures.
- Public names must be ASCII hostname labels of 1..63 characters with letters,
  digits, and interior hyphens; total length is at most 253. Empty labels,
  leading/trailing dots or hyphens, NULs, underscores, and non-ASCII bytes fail.
  No Unicode/IDNA conversion, case normalization, IP classification, or DNS
  resolution occurs. Single labels and numeric labels are accepted.
  The upstream parser decodes permissive UTF-8; its caller only checks label and
  total lengths. `maximumNameLength` remains a separate uint8 metadata value;
  zero is allowed and it does not bound `publicName`.
- The extensions length field is required, its bytes must exactly fit, every
  extension header/data vector must fit, and duplicate types fail. Unknown
  optional extensions are retained and may be selected. Unknown mandatory
  extensions are retained but exclude that config from either choice policy.
  This module understands no extension semantics. Upstream only reports the
  extensions length and ignores the extension bytes.

These conservative policies intentionally diverge from upstream malformed-input
acceptance. Passing these policies still says nothing about a successful ECH
handshake. Extension semantics and future version support require separate work.

## Fixture schema

`ech-config.valid.json` is an array of 13 `{name,input,expected}` records:

```json
{
  "name": "single-x25519",
  "input": {"kind": "wireHex", "value": "..."},
  "expected": {
    "configs": [{
      "versionCode": 65037,
      "length": 0,
      "rawHex": "...",
      "configId": 7,
      "kemId": 32,
      "publicKeyHex": "...",
      "cipherSuites": [1, 1],
      "maximumNameLength": 64,
      "publicName": "public.example",
      "extensionsLength": 0
    }],
    "pickIndex": 0,
    "selectIndex": 0,
    "supportedCipherSuites": [{"kdfId": 1, "aeadId": 1}]
  }
}
```

This is a shape illustration; use the checked-in fixtures for actual wire lengths.
Indices are zero-based into `configs`, or null for no choice. Kotlin cipher pairs
are projected with `flatMap { listOf(it.kdfId, it.aeadId) }` to compare with the
actual upstream flat uint16 array. `supportedCipherSuites` is one item per config:
the first matching `(1,1)` pair or null. `extensionsLength` is reconstructed as
the sum of each retained extension's four-byte header plus its data length.
There are no base64 cases or JSON byte-array representations.

`ech-config.safety.json` has the same envelope, but `expected` is
`{error: "<EchConfigErrorCode.name>", offset: <absolute byte offset>, field: "<field>"}`.
These eight rejection expectations are local policy tests, not upstream oracle
outputs. Additional Kotlin safety tests cover malformed fields and nesting,
unknown versions/algorithms, mandatory extensions, maximum input size, invalid
siblings, 20000 deterministic untrusted inputs, and detached result mutation.

The test-only file `port/tests/wave3/src/jsMain/kotlin/CheckEchConfig.kt` provides
`internal fun checkEchConfig(input: Map<String, Any?>): Any?`. It projects success
exactly to the valid fixture schema, or failure to the safety error schema.
It has private hex helpers, no production wiring, and no `@JsExport`.
The parent must explicitly include it with the production source in its JS runner.

## Regeneration and replay

Run from this isolated checkout, substituting the actual read-only upstream `src`:

```powershell
$upstream = 'D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src'
node port/tests/oracles/wave3/ech-config.cjs --upstream $upstream --generate
node port/tests/oracles/wave3/ech-config.cjs --upstream $upstream --verify
```

`--generate` writes only the valid fixture and provenance in this checkout.
`--verify` only reads, replays the saved valid inputs through the actual upstream
parser/pickers, compares every expected value, and checks source, generator,
fixture, and local safety-fixture hashes. It never refreshes expectations.
Caller-selection expectations are protected by hashes for `ech.js`, `hpke.js`,
and all inspected caller files, not just the DNS parser.

Provenance contains relative source identity and SHA-256 hashes only, with no
absolute invocation paths, cwd, timestamps, or date-dependent values.
Script and provenance must be collected together after generation: editing the
generator without regenerating provenance intentionally causes verification to
fail.

## Verification and remaining integration work

Standalone Kotlin 2.0.21 JVM compilation uses cached compiler/runtime jars,
JUnit 4.13.2, Hamcrest 1.3, and the original checkout's JDK 21.0.2. Compile
`EchConfigList.kt` with `EchConfigListTest.kt`, JVM target 17, then run
`org.junit.runner.JUnitCore com.dsha.sidecar.net.dns.EchConfigListTest` from the
repository root or `netcore`. No Gradle invocation is needed. The standalone
compiler uses a 512 MiB heap; JUnit uses 256 MiB.

The parent's serial aggregate owns the actual Kotlin/JS compilation and replay.
Final local verification: 10 JUnit tests passed, including the 13 upstream valid
fixtures and eight local safety fixtures. Production source and the test-only JS
projection also compiled together on JVM. Oracle `--verify` passed in this
checkout and after copying the oracle/fixtures into a separate directory tree
inside this worktree and invoking from a different cwd; replay left all fixture
hashes unchanged. The parent reported actual aggregate Kotlin/JS compilation and
replay passing all 13 ECH valid and eight ECH safety fixtures. That aggregate was
run by the parent, not by this isolated worker.

JVM success alone does not assert JS runtime parity. This patch does not alter
the parent review checkout, original checkout, phone, assets, build/export
configuration, adapters, or existing runtime behavior, and creates no commit.
