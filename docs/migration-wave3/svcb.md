# Wave 3: isolated HTTPS/SVCB codec

This migration adds `com.dsha.sidecar.net.dns.SvcbCodec` and `SvcbModels.kt`.
It is intentionally unapplied: no build include, export, runtime caller, browser,
payload, network, device operation, or ECH crypto changes accompany it.
Production code uses common Kotlin and has no Java imports, platform APIs,
resolver, clock, randomness, or I/O. JVM tests and the Node oracle perform local
fixture I/O. The parent owns aggregate JVM and Kotlin/JS compilation.

## Source and reproducibility

Source: `@blue-frontier/mitmproxy` 2.3.1, `src/lib/dns/util.svcb.js` (MPL-2.0).
The actual supplied source SHA-256 is:

```text
8f2fc47ae1ea9e9df4e020b581810d97f3192e026031952464fee7ee960ee6b4
```

The oracle requires this exact hash before loading the supplied module. It never
copies or edits that source. Pass its path explicitly:

```powershell
node port/tests/oracles/wave3/svcb.cjs --generate --upstream '<path-to-util.svcb.js>'
node port/tests/oracles/wave3/svcb.cjs --verify --upstream '<path-to-util.svcb.js>'
```

`--generate` is the only mode that writes `port/tests/fixtures/wave3/svcb.json`.
`--verify` reads the existing array, calls upstream with each stored input, and
compares each stored expected projection without writing any artifact. These modes
are mutually exclusive. The source hash is pinned in the oracle and this document;
fixture and oracle file hashes can be computed with `Get-FileHash -Algorithm SHA256`.
Fixture cases are upstream valid-case evidence, not malformed-input equivalence.
The generated fixture SHA-256 is
`7a5ed0a06372ba441beeb2b193d916371bd30f33b91a3778d150f2d83cfb73e0`.

## Public API

| Operation | Contract |
| --- | --- |
| `typeName(Int)` | HTTPS/SVCB/CNAME/OPT names; otherwise decimal type string |
| `isSvcType(Int/String)` | Exact 64/65 or uppercase SVCB/HTTPS |
| `typeCode(Int/String)` | Upstream helper semantics: SVCB/64 becomes 64; everything else 65 |
| `paramName(Int)` | Upstream underscore names for keys 0..8, otherwise `keyN` |
| `encodeName(String): ByteArray` | UTF-8 labels, empty labels skipped like upstream, root terminator |
| `encodeQuery(name, id, type=65, udpPayloadSize=1232, dnssecOk=false): ByteArray` | One IN question, RD set, one OPT RR; explicit unsigned 16-bit ID |
| `decodeName(wire, offset, endOffset=wire.size): SvcbDecodedName` | Name, copied raw labels, and continuation offset after original name/pointer |
| `decodeSvcParam(key, bytes): SvcbParam` | Numeric key, canonical name, copied raw bytes and typed value |
| `decodeSvcRecord(wire, offset, length): SvcbRecord` | Priority, target (`.` for root), all parameters in input order |
| `parseResponse(wire): SvcbResponse` | Header, questions, all three RR sections in packet order, copied original wire |
| `tokenize(text): List<String>` | Upstream whitespace, quotes, and in-quote single-character escapes |
| `parsePresentation(text): SvcbPresentation` | Small DoH JSON data-field parser, separate textual parameter model |

`SvcbValue` variants are `Strings(items)`, `Number(number)`, `Flag`, `Text(text)`,
and `Bytes(bytes)`. Mandatory values are canonical parameter names, ALPN is a
list of UTF-8 strings, IPv4 hints are dotted decimal, IPv6 hints are eight
uncompressed lowercase hexadecimal groups, port is an integer, and dohpath is
text. ECH (key 5) and every unknown wire key are opaque bytes. There is no
`parseEchConfigList` or `pickEchConfig`.

`SvcbRecord` and `SvcbPresentation` expose `paramMap` with first-occurrence
semantics and an `ech` convenience property. Ordered `params` preserves duplicates,
including unsorted input; the codec does not impose service-selection policy.
Wire keys are unsigned integers. Presentation keys mirror upstream: recognized
hyphen/underscore spellings map to 0..8, unknown textual names (including `key123`)
have key -1. Original presentation spelling is preserved.

`SvcbResourceRecord` exposes `name`, `typeCode`, computed `type`, `klass`, unsigned
32-bit `ttl` as `Long`, `section`, copied `raw` RDATA, optional `service`, and
optional `cname`. Unlike upstream, raw CNAME RDATA is retained too. Response
`answers` includes answer, authority, and additional sections, matching upstream's
combined array; `section` distinguishes them. Unknown RRs are retained verbatim.
Response `id` and `flags` preserve the wire fields; `rcode` is the low four flag
bits and `truncated` tests TC. QR, expected transaction ID, DNSSEC, and EDNS extended
rcode are caller concerns. Query type is written verbatim, including unknown
unsigned types; `typeCode` is a separate compatibility helper, not applied to it.

Arrays are fresh result-owned copies, not views into input. They remain mutable;
callers should use `contentEquals` for byte comparisons and avoid mutating shared
results. `raw` preserves exact bytes even when UTF-8 display decoding substitutes
invalid input. Names are not lowercased, IDNA-normalized, or DNS-escaped.

## Explicit safety boundary and differences

Every production parse/encode rejection throws `SvcbFormatException`, a subclass
of `IllegalArgumentException`, optionally carrying a byte offset or text index.
There are no partial records after a structural failure. Limits are fixed and
public: packet/parameter bytes 65,535; questions 256; total RRs 2,048; parameters
per record 1,024; pointer hops 128; presentation characters 65,535; tokens 2,048;
expanded DNS name 255 bytes and individual labels 63 bytes.

Name decoding is iterative. Pointer positions are tracked for cycles, every read
is bounded, and expanded length is checked even across pointers. The continuation
offset is after the original pointer, including when labels precede it. RDATA-local
`endOffset` restricts the original encoded name; pointer targets may reference
other locations in the whole packet, matching the supplied source's permissive
compression handling. Forward pointers are allowed. Pointer targets are checked
for bounds and termination, not verified as previously established label starts.
SVCB target compression is supported for upstream compatibility, even though
standards-conforming SVCB target encoders should not emit it.

The following are intentionally stricter than upstream and are independently
tested; they are **not** claimed as upstream malformed-case parity:

- Overlong expanded names, excessive counts/limits, impossible header counts,
  truncated RR bodies, short HTTPS/SVCB RDATA, and leftover packet bytes reject.
- Names cannot consume bytes beyond their local RDATA. CNAME RDATA must consist
  of exactly one encoded name. Parameter headers/values must fit completely.
- Mandatory keys require a whole number of 16-bit values, ports exactly two
  bytes, IPv4/IPv6 hints whole addresses, flag parameters no bytes, and each ALPN
  ID a nonempty, complete length-prefixed value. Upstream may truncate, ignore,
  or return partial values here.
- Presentation requires priority and a nonempty target, complete quotes/escapes,
  an unsigned decimal priority/port of at most five digits in 0..65535, and required values for recognized
  non-flag parameters. Flag parameters reject assigned values.
- ECH presentation base64 accepts the standard alphabet with canonical padding
  or omitted padding, including an empty value. Garbage, URL-safe alphabet,
  whitespace, inconsistent padding, and nonzero padding bits reject instead of
  inheriting Node Buffer's permissive behavior.

This remains a structural codec. Empty mandatory/ALPN/hint vectors are accepted
like upstream. Presentation hint/list values stay textual (including IPv6
compression), unknown valued parameters stay text, and unknown bare parameters
stay flags. The small tokenizer only removes backslash escapes inside quotes;
it does not implement DNS decimal escapes, escaped comma semantics, `$ORIGIN`,
comments, parentheses, full zone files, key ordering/uniqueness policy, mandatory
presence policy, AliasMode policy, or service endpoint selection.

## Fixtures, projection, and validation

The fixture is a neutral JSON array of `{name,input,expected}`. `input.op` is
`type`, `encodeName`, `query`, `decodeName`, `param`, `record`, `response`,
`tokenize`, or `presentation`. Wire decoders use `wire` (hex), optional `key`,
and appropriate `offset`/`length`; string operations use `text`; queries use
`name` and `options` containing the explicit ID. `type` uses numeric `code`
plus a string or numeric `type`.

Encoded results and `raw` fields are lowercase hex strings. `SvcbValue.Bytes`
projects to `{"hex":"..."}`; other values project to JSON string arrays,
integers, strings, or `true` flags. Parameters project to `{key,name,value,raw}`
for wire decoding and `{key,name,value}` for presentation. Records project to
`{priority,target,params}`. Name results project to `{name,offset}`.

Response projection contains `{id,flags,rcode,truncated,questions,answers}`.
Questions contain `{name,type,typeCode,klass}`. Each RR contains
`{name,type,typeCode,klass,ttl,section,raw}` plus `service` or `cname` when
applicable. TTL projects to a JSON number (the JS adapter converts `Long` to
`Double`, exact for every 32-bit TTL). Maps, duplicate convenience fields,
response raw wire, and copied raw name labels are omitted from fixture projection
and tested directly on JVM instead.

`port/tests/wave3/src/jsMain/kotlin/CheckSvcb.kt` provides the internal
`checkSvcb(input: Map<String, Any?>): Any?` adapter implementing these projections.
It is common Kotlin with no export annotations or runtime wiring; the parent
explicitly includes it in the aggregate JS runner.

`SvcbCodecTest` replays every stored fixture and independently tests pointer
continuation offsets, compression cycles/hop limits, expanded size, local RDATA
boundaries with external pointer targets, malformed parameter lengths, all packet
truncations, count limits, trailing bytes, copied byte ownership, duplicate maps,
unsigned TTL/IDs, tokenization/base64 failures, and 3,000 deterministic random
byte inputs. Tests catch only `SvcbFormatException` for arbitrary input; an
accidental bounds/runtime exception fails the test. Set `-Dsvcb.fixtures=<path>`
if running outside the repository/netcore directory.

Local validation uses the original checkout's `.jdk/jdk-21.0.2`, cached Kotlin
2.0.21 compiler and JUnit 4.13.2 directly, with a 384 MiB compiler heap and
192 MiB test heap. Outputs go under ignored `build/svcb-standalone` in this
worktree. No Gradle, ADB, device, or network tests are needed.

Result: standalone Kotlin 2.0.21/JDK 21 compilation passed, including the
common Kotlin JS fixture adapter compiled on JVM; JUnit reports `OK (19 tests)`.
All 27 stored valid-case projections passed JVM replay. The pinned Node oracle's
`--verify` passed all 27 cases, with the fixture SHA-256 unchanged before/after.
The parent aggregate Kotlin/JS run is separate; this worktree did not invoke it.
