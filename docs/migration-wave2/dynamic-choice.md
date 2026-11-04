# DynamicChoice: second-wave candidate selection

`netcore/src/main/kotlin/com/dsha/sidecar/net/dns/DynamicChoice.kt` ports the
state transitions of `DynamicChoice` from this installed source:

```text
port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/
  @blue-frontier/mitmproxy/src/lib/choice/index.js
```

The installed package reports version 2.3.1. The SHA-256 of `index.js` used to
generate the fixtures is
`64626417b13025dcd7e93e1d3046c7483428883376d94f699668de3ff3e15d96`.
The relevant caller is `src/lib/dns/base.js`: `IpCache` extends `DynamicChoice`,
DNS results append the original hostname as the final fallback, connection
feedback calls `doCount`, and a hostname fallback may trigger `resetChoice`.

This module is pure Kotlin, using the standard library without `java.*`, DNS,
probes, timers, logging, or platform adapters. Upstream's diagnostic `key` is
retained. Its constructor's unused `createTime = new Date()` is omitted.
There is no `ChoiceCache`, resolver integration, or liveness policy here.

## API for the offline wrapper

```kotlin
val choice = DynamicChoice(key = "example.test") // key defaults to ""
choice.setBackupList(listOf("192.0.2.1", "2001:db8::1", "example.test"))
choice.countStart("192.0.2.1")
choice.doCount("192.0.2.1", true)
choice.doRank()
val didReset: Boolean = choice.resetChoice("example.test")
val snapshot: DynamicChoice.Snapshot = choice.snapshot()
```

- `setBackupList(List<String>)`, `countStart(String)`, `doCount(String, Boolean)`,
  and `doRank()` return `Unit`.
- `resetChoice(String)` returns whether at least one remembered candidate
  differs from the supplied hostname.
- `value: String?` and `backupList: List<String>` are readable properties.
  `value` has no public setter; `backupList` returns a detached copy.
- `snapshot()` returns `Snapshot(value, backupList, counts)`.
  `counts` is `Map<String, DynamicChoice.Count>`, with fields `total`, `error`,
  `keepErrorCount`, and `successRate`. There is no redundant `value` field in a
  counter. All four numbers are `Double`, matching JavaScript Number arithmetic.
- Snapshot fields are read-only, and all containers and counter records are
  detached from internal state. Even a caller that casts a returned collection
  to a mutable collection cannot alter the selector. Old snapshots do not
  change when more feedback arrives.

Candidate strings are compared exactly, without parsing, trimming, resolving,
or case folding. They can be IPv4 literals, IPv6 literals, hostnames, numeric
strings, or ordinary opaque labels. Duplicates are valid and remain in the
backup queue until ranking or resetting builds a list from distinct counters.
Feedback for a previously unseen candidate is valid and creates a counter.

## Preserved transitions

| Action | Observable effect |
| --- | --- |
| Construct | `value = null`, empty queue and counts. |
| Set a list | Copy the list. Seed each unseen candidate with descending totals starting at the full list length, zero errors and consecutive errors, and rate `0.5`. Decrement the seed only for an unseen candidate, including when the list contains duplicates or existing candidates. Keep all old counters. Shift the first item into `value` and apply one non-error count to it. |
| Count an unseen candidate | Seed total `5`, zero errors and consecutive errors, and rate `1`, then apply this count. It need not be in the current queue. |
| Count a non-error | Clear consecutive errors, increment total, and recompute rate as `1 - error / total`. `countStart` does exactly this; it is not merely an in-flight marker. |
| Count an error | Increment total, error and consecutive errors, then recompute rate. If this candidate is current, switch immediately on its first failure. |
| Switch after failure | Clear the failed candidate's total, error and consecutive errors, retaining the just-computed success rate. Shift the next backup without counting it, or select `null` if exhausted. A duplicate can select the same string again. |
| Late feedback | Update the historical candidate's counter. An error for a string different from current cannot switch current. A success does not revive an exhausted selection. |
| Rank | Enumerate every remembered counter, stably sort by descending stored rate, and pass the resulting distinct list to `setBackupList`. This may revive removed or failed candidates and applies another non-error count to the selected item. Stored rates are not recomputed before sorting, even when totals were cleared. |
| Reset | Reset every counter to total/error/consecutive errors `0` and rate `1`. Build a queue from every remembered key except the exact supplied hostname, preserving key enumeration order. If nonempty, select its first item and apply one non-error count. |
| Reset with no alternatives | Return `false` after resetting counters; preserve the existing current value and backup queue. |

JavaScript object enumeration matters for snapshot order, reset order, and
stable ranking ties. Canonical array-index strings (`"0"` through
`"4294967294"`) enumerate first in ascending numeric order. Other strings
enumerate in their original insertion order. For example, `"01"`, `"-0"`, and
`"4294967295"` belong to the second group. Replacing the backup queue does not
change that order. Kotlin implements this enumeration explicitly rather than
relying solely on insertion order.

## Intentional boundaries

Candidates and the hostname argument must be nonempty strings and must not
equal any of these Object.prototype property names:

```text
__proto__ constructor __defineGetter__ __defineSetter__ hasOwnProperty
__lookupGetter__ __lookupSetter__ isPrototypeOf propertyIsEnumerable
toString valueOf toLocaleString
```

Upstream stores counts in `{}` and tests inherited properties as though they
were counters; those names can mutate inherited objects or otherwise produce
nonsensical counts. They are outside this string-candidate contract. Empty
strings are excluded explicitly as well. Violations throw
`IllegalArgumentException` before changing state, including when an invalid
item appears late in a replacement list. This is a deliberate restriction,
not an attempt to reproduce JavaScript prototype behavior. The literal string
`"undefined"` is permitted as an actual candidate.

Upstream `setBackupList([])` shifts JavaScript `undefined` into `value`, then
calls `doCount(undefined, false)`. This accidentally creates a counter at object
key `"undefined"` with total `6` and rate `1`, whose own `value` field is
undefined. Ranking a freshly constructed upstream instance reaches the same
behavior. Such state cannot faithfully fit a `String?` selection with
string-only candidate counters.

Kotlin instead makes `setBackupList(emptyList())` set `value = null`, empty the
queue, and preserve existing counters without adding a synthetic counter.
Ranking when no counters exist also yields that empty state. These two
operations are covered by Kotlin boundary tests, outside the JS parity
fixtures. A real candidate spelled `"undefined"` remains a normal counter.

## Fixtures and replay

`port/tests/fixtures/wave2/dynamic-choice.json` contains 31 cases. Every expected
counter and reset return was generated by Node v24.15.0 executing the actual
upstream module with its logger replaced by a local no-op mock. The generator
does not import `dns/base.js` or perform DNS, network requests, or probes.

Each case has `name`, `candidates`, `operations` (`{method, args}`), and
`expected`. Expected snapshots have `value`, `backupList`, `counts`, and
`resetResults`, an array of boolean return values in reset-operation order;
it is empty for cases without resets. Count records have just the four numeric
fields listed in the API. Assert queue ordering and, when checking counter key
order, use `Object.keys(expected.counts)` rather than an order-insensitive
object equality assertion. Numeric-key rank and reset cases also expose
enumeration order through the selected value and queue.

Replay creates a fresh instance and calls `setBackupList(candidates)` only
when the initial list is nonempty. An empty initial `candidates` means the
untouched upstream constructor; it does not instruct upstream to call the
empty-list setter. The Kotlin wrapper may call its empty-list setter in that
case because its resulting state equals a fresh constructor. Then apply
operations in order. Fixtures never call `setBackupList([])` or rank without
any counters. They do cover empty construction, empty reset, exhaustion,
first failure, late feedback, duplicate queues, replacement seeding,
ranking, hostname-only reset, failed reset returns, numeric key order, and
the real candidate `"undefined"`.

To regenerate expected values from the stored case inputs, run this JavaScript
from the repository root with the installed source present:

```javascript
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const entry = path.resolve(
  'port/.run/merged-regression-20261101-1328eb6e/runtime/core/node_modules/' +
  '@blue-frontier/mitmproxy/src/lib/choice/index.js'
)
const localRequire = createRequire(entry)
const logger = localRequire.resolve('../../utils/util.log.server')
require.cache[logger] = {
  id: logger, filename: logger, loaded: true,
  exports: { info() {}, warn() {}, error() {}, debug() {} }
}
const { DynamicChoice } = localRequire(entry)
const target = 'port/tests/fixtures/wave2/dynamic-choice.json'
const cases = JSON.parse(fs.readFileSync(target, 'utf8'))
for (const test of cases) {
  const choice = new DynamicChoice('offline')
  if (test.candidates.length) choice.setBackupList(test.candidates)
  const resetResults = []
  for (const { method, args } of test.operations) {
    const result = choice[method](...args)
    if (method === 'resetChoice') resetResults.push(result)
  }
  const counts = {}
  for (const key of Object.keys(choice.countMap)) {
    const { total, error, keepErrorCount, successRate } = choice.countMap[key]
    counts[key] = { total, error, keepErrorCount, successRate }
  }
  test.expected = {
    value: choice.value, backupList: [...choice.backupList], counts, resetResults
  }
}
fs.writeFileSync(target, JSON.stringify(cases, null, 2) + '\n')
```

## Verification

The focused `DynamicChoiceTest` suite has 17 tests covering meaningful
transitions, exact numeric results, detached snapshots, and the intentional
input/empty-list boundaries. It passed using the already cached Kotlin 2.0.21
compiler and JUnit 4.13.2 directly. No Gradle invocation or dependency install
was needed for that standalone check. The integrated build and Kotlin/JS
differential results are recorded in `docs/MIGRATION-WAVE2.md`.
