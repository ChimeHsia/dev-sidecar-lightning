# Wave 2: pure path rules

`com.dsha.sidecar.net.rules.PathRuleMatcher` contains reusable Kotlin only, with
no `java.*`, runtime adapters, interceptor execution, or hostname merging.
The source of behavior is the local upstream snapshot:

- `port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js`: `isMatched`.
- The same package's `src/options.js`: `getExclusionArray` and the selection loop
  in `createIntercepts`.

## API for the JavaScript export

```kotlin
PathRuleMatcher.isMatched(path: String, pattern: Any?): List<String?>?
PathRuleMatcher.getExclusionArray(exclusions: Any?): List<Any?>?
PathRuleMatcher.isExcluded(path: String, exclusions: Any?): Boolean
```

Match arrays contain the full match at index zero and numbered captures after it.
Unmatched optional captures are null, matching JavaScript's undefined array slots
after JSON serialization. Named captures appear in their numbered position; the
JavaScript match array's additional `groups`, `index`, and `input` properties are
not exported.

The caller supplies the full original path including its query. There is no URL
decoding, query stripping, case folding, or implicit anchoring. Boolean `true` and
the exact strings `"true"`, `"*"`, and `".*"` return the entire path, even if it
contains newlines. Other string patterns use the first regex search result.
A pattern beginning with `*`, `?`, or `+` receives one leading dot, exactly as
upstream does; this is not general shell glob conversion. Invalid regexes return
null without logging.

Exclusions accept a list of patterns or a string-keyed map. Map **keys** are
patterns, irrespective of their values; false/null values still exclude a
matching path. Empty lists/maps and other exclusion container types normalize
to null. Normalization returns a fresh list. `isExcluded` evaluates exclusions
independently of the primary pattern, allowing an export to report both results
without conflating them.

## Selection

```kotlin
PathRuleMatcher.Implementation(
    name: String,
    priority: Int,
    enabledConfigKeys: List<String>,
)
PathRuleMatcher.select(
    path: String,
    rules: Map<String, Any?>,
    implementations: List<PathRuleMatcher.Implementation>,
): List<PathRuleMatcher.Selection>
```

A selection contains `name`, `priority`, `key` (the original path pattern),
`order` (Double), and `matched` (the capture array). The caller can use `key` to
retrieve its original config. Selections do not expose or retain config maps.
The caller supplies already merged hostname rules in enumeration order and
descriptors in upstream implementation order:

1. Skip the reserved rule key `matched`, invalid/nonmatching regexes, non-map
   rule values, and rules whose exclusions match.
2. A descriptor is enabled when any of its supplied config keys has a truthy
   JSON value. Empty lists/maps are truthy, like JavaScript objects; false, null,
   zero, empty strings, and NaN are false.
3. Select one candidate per implementation name. Higher numeric `order` replaces
   lower order; equal order retains the first candidate. Missing/null order is
   zero. Negative and fractional orders retain their numeric meaning.
4. A replacement retains the name's original insertion position. Sort the
   final selections stably by ascending priority.

This is the selection subset of `createIntercepts`. It adds no replacement,
redirect, or merge policy. It neither annotates config with `key` nor creates
`_pathCache`; repeated calls compute fresh results. Inputs are never edited.
There is no persistent matcher cache or retained caller-owned state.

Descriptor key truthiness deliberately covers simple implementation predicates
such as `abort` or `redirect`. It cannot express every upstream `impl.is`:
SNI accepts an empty string and depends on proxy config; cache predicates use
max-age calculations; SSL predicates compare exact booleans. The caller must
resolve such predicates separately. ECH filtering, hostname captures, script
preprocessing, and actual interceptor execution remain outside this module.

## Supported inputs and regex limits

Patterns are strings or Boolean `true`. Null, Boolean `false`, numbers, lists,
maps, native RegExp objects, and other runtime objects are unsupported and
return null. Upstream sometimes coerces these into regexes (for example false
and numbers); this API deliberately does not emulate that coercion. Native
arrays must be converted to Kotlin lists by the adapter.

Config values are acyclic JSON-like string-keyed maps/lists/scalars. Non-string
exclusion map keys are ignored; JavaScript prototype properties and inherited
enumerable keys are not modeled. Caller iteration order controls rule order;
the adapter must preserve JavaScript object enumeration order, including its
special handling of integer-like keys. Order values must be finite numbers or
missing/null. Other orders throw `IllegalArgumentException` instead of inventing
JavaScript coercion behavior. Priorities are caller-supplied Kotlin integers.

Kotlin Regex uses Java regexes on JVM and JavaScript regexes on JS. Common
patterns in the fixtures are portable, but full syntax/character semantics are
not guaranteed across engines. Known differences include inline flags,
lookbehind and named-group support by runtime version, Java-only constructs
such as atomic groups/possessive quantifiers, JS identity escapes, Unicode
character classes/case behavior, and line-terminator/anchor behavior. The
module does not rewrite or validate an ECMAScript-only regex subset. A pattern
valid on one engine may fail or match differently on the other. Matching is
synchronous, with no regex execution timeout.

## Fixtures and verification

`port/tests/fixtures/wave2/path-rules.json` is an array of cases with `name`,
`path`, `pattern`, `expected` (capture array or null), optional `exclusions`,
and `expectedExcluded`. Expectations were generated locally by invoking the
actual upstream `isMatched` and `getExclusionArray` functions. Only the logger
was mocked; no upstream matching logic was rewritten. `getExclusionArray` was
extracted verbatim from `options.js` and evaluated with the installed lodash.
Capture arrays use normal JSON serialization, turning undefined captures into
null. Fixture replay and optional Kotlin/JS exports are provided by
`scripts/test-migration-wave2.ps1`.

The JUnit tests cover query/capture behavior, prefix quantifiers, invalid and
unsupported patterns, exclusion keys with false/null values, reserved metadata,
order/tie/priority behavior, descriptor truthiness, and input immutability.
They run as part of the serialized `:netcore:test` suite. No
Gradle configuration, export, adapter, device, or networking changes are needed
for this module.
