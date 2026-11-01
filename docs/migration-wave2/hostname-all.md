# Wave two: hostname configuration merge

`com.dsha.sidecar.net.rules.HostnameAllMatcher` is a pure Kotlin snapshot matcher
for JSON object configurations. It has no `java.*`, Android, Node, JSON-parser,
or runtime-install dependency. Its collections and regex APIs are available on
Kotlin/JVM and Kotlin/JS. This wave does not install an adapter or change active
runtime behavior.

## Public API

```kotlin
val matcher = HostnameAllMatcher(entries: Map<String, Any?>)
val values: Map<String, Any?>? = matcher.match(hostname: String)
val result: HostnameAllMatcher.MatchResult? = matcher.matchWithMetadata(hostname)
```

`MatchResult` has `values: Map<String, Any?>` and
`captureMetadata: CaptureMetadata?`. `CaptureMetadata` has
`groups: Map<String, String?>?`, `index: Int?`, and `input: String?`.

The opt-in `wave2HostnameAll(entriesJson, hostname): String` export in
`netcore-js/src/jsMain/kotlin/MigrationExports.kt` serializes the values and
capture metadata separately. The matcher itself does not parse or serialize
JSON. `null` represents a miss;
an empty map is a successful result whose fields were all deleted.

Entry values must be JSON objects or null. Nested values may be string-keyed
maps, lists, strings, finite numbers, booleans, or null. Top-level configuration
property `matched` is reserved: an explicit value must be an array or null.
JavaScript allows other top-level entry types and iterable strings in `matched`;
these are deliberately rejected rather than emulated.

The constructor copies input containers. Matching neither edits caller data
nor shares output containers between calls. There is no per-host cache.

## Matching and merge order

The reference is the actual local
`port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/`
tree, specifically `@blue-frontier/mitmproxy/src/utils/util.match.js`
(`domainMapRegexply`, `matchHostnameAll`, and its local `merge`) and
`@blue-frontier/dev-sidecar/src/merge.js` (`deleteNullItems`).

1. A leading dot becomes `*.`. An existing non-null explicit `*.` entry
   suppresses its leading-dot alias. When the explicit entry is null,
   insertion order determines which normalized value survives.
2. Keys containing `*` or beginning with `^` become patterns. Wildcards replace
   dots with `\.` and stars with `.*`, then add `^` and `$`. Other regex
   metacharacters remain active, as in upstream. Other keys are exact keys.
3. Every matching pattern merges in insertion order. Identical compiled
   expression strings replace the earlier value without moving its position.
4. Three overrides merge afterward: `*hostname`, then `*.hostname`, then
   exact `hostname`. This makes a `*.example.com` entry apply to the apex
   as well as descendants. A null entry performs no merge.
5. If the merged object has no keys, return null. Otherwise remove null and
   exact `"[delete]"` values recursively, then return the result. Deletion
   can therefore produce `{}`. Empty objects do not clear inherited objects.

Matching is case sensitive and performs no DNS, IDNA, whitespace, or trailing
dot normalization. Patterns use regex search; raw `^api\.` can match a prefix.
Arrays and primitives in nested configurations are not treated as rules.

Objects merge recursively. An existing list is replaced wholesale by the next
value, including another list, object, scalar, or null. Objects normally become
lists when overlaid by a list. There is an observable lodash exception: an
object with an integer numeric `length` and indexed keys is array-like. Lodash
copies its indexed elements before merging an incoming list by index, retaining
an inherited tail and recursively merging object-valued elements. The Kotlin
module preserves that behavior, including null placeholders for absent slots.

`ConfigMerge` is not reused: its ordinary array replacement would lose this
array-like tail, and its default sanitizer compacts deleted list entries.
This matcher always retains array length and deleted positions as null, matching
JSON serialization of upstream sparse arrays. It does not expose actual
JavaScript holes or the distinction between a hole and an explicit null.

Root and nested JSON object keys use JavaScript's own-property enumeration
order: canonical array-index keys from `0` through `4294967294` sort numerically,
then other keys retain insertion order. Input maps must preserve insertion order
if the caller needs deterministic precedence among patterns.

## Captures and metadata

When a pattern has capturing groups, its first capture array contains the full
match followed by the captured groups. Later capture matches append only their
subgroups. A configured `matched` list participates as an existing array, even
when empty, so the next match appends subgroups without adding the full match.
Unmatched optional capture slots serialize as null.

The first capture array has `index`, `input`, and any named `groups`. When
upstream concatenates capture arrays with spread syntax, it loses the old
array's metadata. Its subsequent assignment attaches only the newest match's
named groups; it does not preserve older names. An unnamed subsequent match
clears groups. The Kotlin metadata API preserves these observed behaviors:
concatenated captures have null index/input and only the newest named groups.
An exact override of `matched` replaces/deletes its metadata too.

Named groups whose value is JavaScript `undefined` are omitted from the Kotlin
groups map, matching JSON serialization of the groups object. `groups == null`
means the current capture array has no named-group metadata; an empty map means
it has named groups but none participated.

JSON serialization of a JavaScript array serializes indexed elements only. It
loses the array's `.groups`, `.index`, and `.input` properties. Consequently
`match` returns plain JSON values, and callers needing those properties must use
`matchWithMetadata`. Fixtures optionally record the upstream metadata separately
as `expectedGroups` and `expectedCaptureIndex`. Absent metadata fields represent
undefined upstream properties, not an expectation that index is zero.

## Explicit compatibility boundary

This is not an arbitrary JavaScript RegExp implementation. It delegates to the
target's Kotlin regex engine and supports the common intersection used by the
fixtures: ordinary capturing and noncapturing groups, ordinary named groups,
character classes, alternatives, quantifiers, escapes, anchors, and prefix
searches. Named-group metadata is discovered with a small escaped-character
and character-class-aware scanner, not a full regex grammar.

Engine-specific constructs, flags embedded in strings, duplicate or unusual
group names, Unicode/property escapes, lookbehind edge cases, backreferences,
inline regex modes, Java-specific quoting/classes, and non-ASCII case/character
semantics are not guaranteed equivalent across JVM and JS. Flags cannot be
passed separately. Some patterns accepted on one target may be rejected or
behave differently on the other. In particular, neither Kotlin/JVM acceptance
nor these fixtures establishes arbitrary JS compatibility.

Invalid patterns are ignored at construction, allowing other rules to run.
Upstream can instead throw during `hostname.match`. Only own map keys are used;
prototype inheritance and prototype-pollution behavior are not reproduced.
`__proto__` properties are discarded recursively, consistent with the
JSON-visible lodash safe-get boundary rather than JavaScript object prototypes.
Functions, getters, symbols, undefined configuration values, custom prototypes,
non-JSON objects, and cyclic data are outside the input contract.

Construction bounds entries to 1024, key length to 4096 UTF-16 code units,
JSON depth to 64, and input JSON value nodes to 100000. Matching bounds the
hostname to 8192 UTF-16 code units. Array-like conversion rejects a length above
100000 before allocation. Violations throw `IllegalArgumentException`; these
are intentional limits absent from upstream. Cycles terminate at the depth
limit. These are structural bounds, not a byte budget or a regex execution
timeout: pathological backtracking remains possible, so patterns must be trusted.

## Fixtures and reproduction

`port/tests/fixtures/wave2/hostname-all.json` contains 37 fixtures. Every
`expected`, root `expectedKeys`, and optional metadata field was obtained by
executing the local upstream with Node v24.15.0 on November 1, 2026. No expected
value was generated from the Kotlin implementation. Cases cover precedence,
duplicate compiled expressions, leading dots and null aliases, recursive
deletion, array replacement and holes, array-like conversion, captures and
metadata replacement, empty results, and misses.

Source SHA-256:

- `util.match.js`: `59450826eea8f4f73ffe56e021a577c5a5faa663d6b446482190fa3c27ca4d6a`
- `merge.js`: `2ea04ddbb229f7bfeab0856ecf42a8dae6826765f9b3335023d4d98dee8d6207`

To regenerate expectations, run this JavaScript with Node from the repository
root. It loads the actual local module and stubs only its logger, avoiding
logger initialization and any active runtime installation. Each case gets
fresh entries because upstream can mutate array-valued source configurations.

```javascript
const fs = require('fs')
const path = require('path')
const fixturePath = 'port/tests/fixtures/wave2/hostname-all.json'
const target = path.resolve(
  'port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/' +
  '@blue-frontier/mitmproxy/src/utils/util.match.js'
)
const logger = require.resolve('./util.log.server', {
  paths: [path.dirname(target)]
})
require.cache[logger] = {
  id: logger, filename: logger, loaded: true,
  exports: new Proxy({}, { get: () => () => {} })
}
const api = require(target)
const fixtures = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
for (const fixture of fixtures) {
  const entries = JSON.parse(JSON.stringify(fixture.entries))
  const result = api.matchHostnameAll(
    api.domainMapRegexply(entries), fixture.hostname, 'wave2-fixture'
  )
  fixture.expected = result == null ? null : JSON.parse(JSON.stringify(result))
  delete fixture.expectedKeys
  delete fixture.expectedGroups
  delete fixture.expectedCaptureIndex
  if (result != null) fixture.expectedKeys = Object.keys(result)
  if (result && result.matched && result.matched.groups !== undefined) {
    fixture.expectedGroups = JSON.parse(JSON.stringify(result.matched.groups))
  }
  if (result && result.matched && result.matched.index !== undefined) {
    fixture.expectedCaptureIndex = result.matched.index
  }
}
fs.writeFileSync(fixturePath, JSON.stringify(fixtures, null, 2) + '\n')
```

The JUnit 4 tests exercise merge precedence, alias normalization, delayed
deletion, sparse-array JSON transport, lodash array-like conversion, capture
metadata, key order, isolation, invalid-pattern handling, and input bounds.
The integrated Gradle suite and opt-in Kotlin/JS export are run by
`scripts/test-migration-wave2.ps1`.

Local verification used the cached Kotlin 2.1.20 compiler directly with JDK 21,
without running Gradle: all 14 JUnit tests passed. A temporary generated Kotlin
harness also compared all 37 upstream fixtures against the JVM module,
including root key order, named groups, capture index, and repeat-call equality;
all passed. No harness or compiled artifact was added to the repository.
Final cross-module counts and runtime boundaries are recorded in
`docs/MIGRATION-WAVE2.md`.
