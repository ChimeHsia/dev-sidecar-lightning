# Wave 2: pure request target and header transform

## API for the offline Kotlin/JS adapter

Package: `com.dsha.sidecar.net.http`

```kotlin
RequestTransform.transform(
    url: String,
    ssl: Boolean,
    method: String,
    headers: Map<String, Any?>,
): RequestTransform.Result
```

`Result` exposes exactly these comparable fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `protocol` | `String` | `"http:"` or `"https:"`, including the colon |
| `hostname` | `String` | Host header hostname, preserving case and IPv6 brackets |
| `port` | `Int` | Explicit Host port, otherwise the effective scheme's default |
| `path` | `String` | Path and query, including leading `/` or OPTIONS `*` |
| `headers` | `Map<String, Any>` | Values are strings or copied lists of strings |

The opt-in `wave2RequestTransform(inputJson)` export decodes
`{url,ssl,method,headers}` and encodes these five properties. The Kotlin module
has no JSON dependency or platform adapter. The separate Kotlin/JS build includes
the existing common-compatible
`com.dsha.sidecar.net.dns.IpAddress` source when compiling Kotlin/JS. Neither
source uses `java.net` or other JVM-only APIs.

Invalid or unsupported input throws `IllegalArgumentException`. Error messages
are diagnostic, not a stable serialization protocol. The adapter must report the
failure; it must not silently fall back to forwarding a rejected request.

## Attribution and selected semantics

Reviewed the actual local `@blue-frontier/mitmproxy` 2.3.1 sources (MPL-2.0,
author `docmirror.cn`) under:

```text
port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/@blue-frontier/mitmproxy/src/
```

The implementation adapts `lib/proxy/common/util.js` functions
`parseHostnameAndPort` and the pure fields of `getOptionsFromRequest` with
`externalProxy = null` and empty server settings. The exact `util.js` SHA-256 is
`68312e3d953e50ee25fa2ff37959b7d2ccd2815a434453e9bb135fb6a2f61f2d`.
Fixture generation used locally installed Node v24.15.0.

These upstream details are intentional:

- `headers.host` supplies the destination hostname and explicit port, even when
  an absolute URL names a different authority. The Host header itself stays
  unchanged. No cross-origin rewrite is performed.
- `ssl` selects HTTPS, except that a valid absolute HTTP URL forces HTTP. An
  absolute HTTPS URL with `ssl=false` still produces HTTP and defaults to port 80.
  An explicit URL port does not override the Host port.
- IPv6 hostnames retain their brackets. Host case, an accepted DNS trailing dot,
  percent-escape case, query order, `+`, and dot segments are preserved.
- Path/query behavior follows the bounded ASCII portion of Node's legacy
  `url.parse`, not WHATWG URL normalization. Absolute URLs without a pathname get
  `/`. Fragments are omitted. Existing `%HH` sequences are never decoded.
- Node's simple origin-path fast path preserves raw punctuation when there is no
  space, fragment, or `@` before the query. Otherwise spaces and
  ``" ' < > ` { } | ^`` are escaped in the path/query. An `@` only inside the query
  does not disable the fast path. This distinction is covered by fixtures.
- All colon-prefixed pseudo headers and `proxy-connection` are removed. Other
  headers survive, including Authorization, Proxy-Authorization, Upgrade,
  Keep-Alive, and headers named by Connection tokens.
- Connection values are interpreted as a comma-separated token list. For a
  string-list value, upstream JavaScript string coercion joins elements with
  commas. A case-insensitive exact `close` token after trimming preserves the
  original scalar or list unchanged. Without it, Connection becomes the scalar
  `"keep-alive"`. Merely containing the substring `close` does not qualify.

Also reviewed `lib/interceptor/impl/req/proxy.js`, `redirect.js`, and
`requestReplace.js`, plus `lib/interceptor/impl/res/responseReplace.js` and
`AfterOPTIONSHeaders.js`. They involve separate rewrite/rule or response
semantics and are deliberately excluded. In particular, no placeholder or
regular-expression rewrites, redirect responses, download markers, configured
header replacement, CORS changes, or response header mutation is implemented.
There is no invented Authorization removal policy.

No external-proxy branch, agent fields, TLS options, compatibility settings,
socket IDs/NTLM binding, DNS, socket activity, networking, or device work is
included. The result is an offline data transform; runtime integration is a
separate task.

## Validation contract and stricter differences

This function accepts a bounded subset of legacy Node input, rather than
reproducing its permissive partial parsing:

| Input | Contract and intentional differences |
| --- | --- |
| Target form | Nonempty origin-form starting with one `/`, absolute HTTP(S) with `://` (case-insensitive scheme), or exact `*` for exact method `OPTIONS`. Network-path `//...`, relative targets, CONNECT, and other schemes are rejected. |
| Target characters | Printable ASCII only; no raw controls, DEL, backslashes, or surrounding spaces. Internal spaces in path/query are escaped. Node may trim whitespace, normalize backslashes, or preserve Unicode; this module rejects those inputs. |
| Escapes | Every percent sign in the supplied URL must start a complete `%HH` escape, including any fragment. Encoded delimiters/controls in path/query remain encoded. Node accepts malformed escapes in some positions; this module rejects them. |
| Authorities | Both Host and the absolute URL authority are validated, even though routing uses Host. No userinfo, whitespace, URI delimiters, percent-encoded names, or IPv6 zone IDs. Node can ignore, truncate, or reinterpret parts of these inputs. |
| DNS/IPv4 | ASCII DNS labels of 1..63 characters with only letters, digits, internal hyphens; total unqualified name length at most 253; optional trailing dot. Numeric-only names must be canonical four-octet IPv4 without leading zeros. Unicode/IDNA input, underscores, abbreviated numeric addresses, and malformed numeric addresses are unsupported. |
| IPv6 | Must be a valid bracketed literal, with optional final decimal port. Compressed and IPv4-tail literals are accepted; scopes and partial brackets are rejected. Existing portable `IpAddress` performs the literal validation. |
| Port | Complete decimal text representing 1..65535; zero padding is allowed. Empty, signed, zero, overflow, suffix-bearing, and out-of-range ports are rejected instead of `parseInt` truncation or fallback. |
| Method | Nonempty ASCII HTTP token; exact CONNECT is excluded. No case normalization is performed. Method is used for validation and is not part of the result. |
| Header names | Already lowercase ASCII HTTP field tokens, optionally prefixed by one `:` for pseudo headers. Mixed-case names and invalid names are rejected rather than combined or guessed. This matches the normal Node incoming-header boundary. |
| Header values | Strings or lists containing only strings, including empty lists. Values may contain HTAB, printable ASCII, or byte characters U+0080..U+00FF. CR/LF, other controls, DEL, non-byte Unicode, nulls, booleans, numbers, and nested structures are rejected, including in headers that will be filtered out. |
| Host | Required as one scalar string, even for absolute URLs. Missing Host, a Host array, or only `:authority` is rejected. The caller must resolve raw duplicate Host fields before this map boundary; a map cannot represent duplicate scalar fields. |

Inputs must not be concurrently mutated during a call. The input map and all
input lists are never mutated. Each call allocates a new result map and copies
all retained lists, including a preserved multi-value Connection. Results do
not share mutable containers with inputs or each other. Kotlin's read-only
`Map`/`List` types are not a promise of runtime-frozen containers.

## Upstream-derived fixture format and regeneration

`port/tests/fixtures/wave2/request-transform.json` is an array:

```json
[
  {
    "name": "origin-http-default",
    "input": {
      "url": "/",
      "ssl": false,
      "method": "GET",
      "headers": {"host": "example.test"}
    },
    "expected": {
      "protocol": "http:",
      "hostname": "example.test",
      "port": 80,
      "path": "/",
      "headers": {"host": "example.test", "connection": "keep-alive"}
    }
  }
]
```

The 34 fixtures select all expected fields from actual upstream execution.
Invalid cases are separate Kotlin rejection tests, not falsely attributed
upstream fixtures. There are no expected agent internals or error strings.

To regenerate expectations for the existing fixture inputs, run this JavaScript
with local Node from the repository root (for example, feed it to `node` on
standard input). It evaluates the complete original source in an isolated VM
with only irrelevant dependencies stubbed; it does not reimplement the parser:

```javascript
const fs = require('node:fs')
const vm = require('node:vm')
const crypto = require('node:crypto')
const sourcePath = 'port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/common/util.js'
const fixturePath = 'port/tests/fixtures/wave2/request-transform.json'
const source = fs.readFileSync(sourcePath, 'utf8')
const hash = crypto.createHash('sha256').update(source).digest('hex')
if (hash !== '68312e3d953e50ee25fa2ff37959b7d2ccd2815a434453e9bb135fb6a2f61f2d') {
  throw new Error('Upstream source changed; review semantics before regenerating')
}
class UnusedAgent {
  constructor(options) { this.options = options }
}
const sandbox = {
  exports: {},
  require(id) {
    if (id === 'node:url') return require(id)
    if (id === 'tunnel-agent') return {}
    if (id === '../../../utils/util.log.server') {
      return { info() {}, error() {}, debug() {} }
    }
    if (id === '../../../utils/util.match') {
      return {
        matchHostname() { return undefined },
        domainMapRegexply(value) { return value },
      }
    }
    if (id === './ProxyHttpAgent' || id === './ProxyHttpsAgent') {
      return UnusedAgent
    }
    throw new Error(`Unexpected dependency: ${id}`)
  },
}
vm.runInNewContext(source, sandbox, { filename: sourcePath })
const fixtures = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
for (const fixture of fixtures) {
  const input = fixture.input
  const options = sandbox.exports.getOptionsFromRequest({
    url: input.url,
    method: input.method,
    headers: structuredClone(input.headers),
    socket: {},
  }, input.ssl, null, {})
  fixture.expected = Object.fromEntries(
    ['protocol', 'hostname', 'port', 'path', 'headers']
      .map(key => [key, options[key]]),
  )
}
fs.writeFileSync(fixturePath, JSON.stringify(fixtures, null, 2) + '\n')
```

The empty server settings and match stubs ensure no timeout or TLS mappings.
Each request gets fresh headers and a fresh mock socket; Authorization-related
socket bookkeeping cannot leak into the projection. Agent construction is
stubbed and none of its fields are serialized.

## Verification

`RequestTransformTest` exercises accepted targets, escape preservation and the
legacy fast path, Host/scheme behavior, IPv6, header filtering, exact close
tokens and list values, malformed rejection, and input/result independence.
Local verification on 2026-11-01 used the cached Kotlin 2.1.20 compiler and JDK 21,
without Gradle: all 15 JUnit methods passed. An additional temporary JUnit harness
compared all five result fields against every one of the 34 upstream fixtures;
all comparisons passed (16 JUnit methods total). The documented regeneration
script was also executed successfully against the pinned source.

The fixture projection is exercised by the opt-in Kotlin/JS differential suite.
See `docs/MIGRATION-WAVE2.md` for the final integrated results. Runtime adapters
and installed payloads do not call this module.
