# Native configuration repository candidate

Based on `05d3ae33edba11d70c95e8d18d4b011e9c0da533`. This adds only new
`netcore/config/native` files, tests, synthetic oracle fixtures and these docs.
It does not modify browser source, Node payloads, shared ConfigMerge, Gradle,
main, or sibling worktrees. No device, Gradle invocation or production transport
is used. Both UI worktrees remain available for recovery.

## Usable boundary

`NativeConfigRepository` loads actual UTF-8 JSON5-subset documents from strings
or caller-owned local files. It publishes a `DecodedServerSnapshot` containing:

- `configuration`: complete merged decoded root, deeply read-only;
- `server`, `dns`, `intercepts`, `setting`: directly consumable map views;
- `revision`: monotonically increasing successful-update epoch;
- `provenance`: source-layer SHA-256/byte counts and first-run seed indicator;
- `remoteAddresses`: validated future HTTPS requests and whether HTTP was fixed.

Example at the native integration boundary:

```kotlin
val repository = NativeConfigRepository(
    limits = ConfigLimits(),
    remoteFetchDefaults = callerSuppliedBootstrapDefaults,
    validate = { candidate -> NativeRuntimeConfig.decode(candidate.configuration) },
)
when (val result = repository.reloadFiles(defaultDocument, privateConfigDirectory)) {
    is ConfigUpdate.Applied -> {
        val decoded = result.snapshot
        val planner = RulePlanner(listOf(decoded.configuration))
        // Or pass decoded.server directly to RulePlanner, and decoded.dns
        // to DnsProviderConfig.normalize(providers, options).
        val runtimeConfiguration = NativeRuntimeConfig.decode(decoded.configuration)
    }
    is ConfigUpdate.Rejected -> {
        // result.previous is the exact last usable snapshot, or null at first load.
        // error/offset do not contain input text, filenames, URLs or credentials.
    }
}
```

The directory parameter is `java.io.File`. The caller creates/owns the private
directory; this module never writes, migrates, creates, deletes or rewrites user
configuration. `reload(ConfigDocuments(...))` handles string layers without files.
The consumer validator is important: the repository validates decoded structure
and resource budgets; RulePlanner/runtime support for a specific rule is still
checked by the real native consumer, before a new epoch is published.

The repository does not atomically replace an already running proxy engine.
The runtime owner must activate a successful configuration epoch explicitly.

## Exact layer and file behavior

The actual upstream local-config-loader.js was executed in the independent
oracle. The six merge steps are:

1. Copy user, then merge personal.
2. Merge shared.
3. Merge default.
4. Merge shared again.
5. Merge personal again.
6. Merge user again, then remove null / exact `[delete]`.

This preserves user/personal/shared/default key positions while values resolve
user > personal > shared > default. Nested maps retain this ordering too.
Array-index property names follow ECMAScript numeric-first enumeration.
The accepted shared `ConfigMerge` is reused unchanged; cleanup preserves deleted
array slots as null, corresponding to upstream sparse holes after JSON transport.

Local filenames are fixed:

- config.json takes priority; config.json5 is used only if the former is absent.
- remote_config_personal.json5 is the personal cached layer.
- remote_config.json5 is the shared cached layer.
- Defaults are an explicitly supplied string.

Absent optional files are empty layers. Unlike upstream's permissive file loader,
a present invalid/unreadable file rejects the whole update and retains the old
snapshot. A corrupt config.json never silently falls back to a stale config.json5.
No user file is read from an implicit home directory.

When no user layer exists, the in-memory seed matches the Android entrypoint:
closeStrategy 2, system proxy disabled, node/git/pip/share/free_eye/overwall
disabled, local server enabled on 31181. Every final update forces root
proxy.enabled false and each declared desktop plugin's enabled false, including
unknown plugin names. Remote/default layers cannot re-enable those host actions.
No plugin or system proxy implementation is included.

## Explicit bounded JSON5 subset

This is an original bounded parser, **not full JSON5 and not Gson lenient JSON**.

Supported and independently compared with JSON5 2.2.3:

- Objects with ASCII unquoted IdentifierNames, including escaped ASCII `\uXXXX`
  names, dollar/underscore names and keyword names.
- Single/double-quoted strings, quoted Unicode names/text, valid surrogate pairs.
- Standard escapes, `\xHH`, `\uHHHH`, `\v`, `\0`, allowed non-escape characters,
  and LF/CRLF/U+2028/U+2029 backslash continuations.
- Line/block comments, JSON5 whitespace/BOM and trailing object/array commas.
- Boolean/null values, signed finite decimal/exponent/hexadecimal numbers,
  leading/trailing decimal points and negative zero.

Rejected explicitly:

- Non-ASCII unquoted IdentifierNames (use quoted Unicode keys).
- Infinity/NaN/overflow, duplicate decoded keys, prototype-sensitive keys
  `__proto__`, `constructor`, `prototype`, and unpaired surrogates.
- Unescaped ASCII control characters in strings, numeric separators/octal/binary
  literals, undefined, expressions, functions, RegExp literals and array elisions.
- Non-object roots, malformed input or any syntax outside the supported grammar.

Production has no JS eval, Node, third-party parser binary or fallback.
UTF-8 byte decoding and string encoding both report malformed input instead of
silently replacing bytes/surrogates.

The mature dependency candidate `de.marhali:json5-java:3.0.0` was checked against
its official repository README and exact Maven Central POM/source archive.
The primary artifacts/hashes and declared Java11+ baseline are recorded in
`netcore/src/test/fixtures/native-config/library-audit.json`. Nothing was globally
installed or added to Gradle. That Java baseline is not treated as a verified
min24/budget guarantee; this task uses the permitted bounded-subset option.
The actual installed upstream parser, JSON5 2.2.3, remains the test-only oracle.

## Budgets, snapshot safety and file constraints

Default limits:

| Resource | Default |
| --- | --- |
| One document | 1 MiB UTF-8 |
| All four input layers including seed | 4 MiB |
| Nesting | 48 from root zero, hard configurable ceiling 128 |
| Aggregate input keys | 20,000 |
| Aggregate input nodes | 50,000 |
| String/key characters | 262,144 |
| Number token characters | 128 |

The parser enforces limits while scanning, before unbounded recursive allocation.
Byte limits are checked before decoding; files are read incrementally with a cap,
not readAllBytes. Aggregate input budgets are checked before six-pass merging,
and a fresh tree budget rechecks the final policy-expanded result.

Maps/lists are recursively copied into Java unmodifiable containers. Caller-owned
bootstrap collections and mutable Number instances cannot change stored state.
Cycles or unsupported caller policy values reject. Writers serialize; readers
observe one AtomicReference epoch. Validation, all layer loads and fetch responses
finish before publication. Failed syntax, size/depth budgets, URL/file/fetch
errors or consumer validation retain the exact previous object/revision.
Reentrant updates cannot publish a nested epoch during validation.

The production API does not link `java.nio.file.Path/Files`; it uses File,
canonical root/direct-child checks, restrictive filename grammar and bounded
FileInputStream reads. File aliases and non-files are rejected. Windows File
canonicalization does not resolve symbolic file aliases, so platforms exposing
NIO get an optional reflection-only link check. API24 does not statically link
API26 classes. The test suite hides all java.nio.file classes and actually reads
and reloads through the fallback API.

This is **not** an openat/O_NOFOLLOW sandbox. The private root must be controlled
by the application, with no hostile concurrent directory/file replacement.
Size/mtime/canonical-path rechecks detect ordinary changes, not adversarial
same-size/mtime replacement. Actual Android min24 execution is still untested.

## Remote data policy, no real transport

remote-config-url.js was compared directly. Only a leading-whitespace,
case-insensitive HTTP prefix is rewritten once to HTTPS; its path/query escapes
and explicit port remain intact. Existing HTTPS spelling is not rewritten.

The native boundary is intentionally stricter afterward: require ASCII HTTPS
with a host, no credentials, fragment, backslash or ambiguous whitespace, and a
valid port. Invalid/unsupported URL values reject, including when remote fetching
is disabled. Nothing is persisted back to config files.

remote-config-rule.js and the server module's application logic were reviewed:

- Explicit DNS provider override wins; otherwise select the first configured
  provider whose server contains the caller-supplied bootstrap host match.
- If absent, add the caller-supplied provider/HTTPS DoH definition.
- SNI remains unchanged by default; a supported explicit override creates a
  missing intercept.
- Preserve existing intercepts and preset-IP entries.
- DNS mappings overwrite an existing host mapping. This follows actual upstream
  code, despite the source comment describing it as only filling missing values.

No real config service URL or production preset-IP list is embedded.
RemoteFetchDefaults is explicit caller-owned public bootstrap data.
Unsupported SNI/DNS override types reject rather than JS coercing arbitrary data.

`reloadWithFetcher` is opt-in and synchronous. The interface receives only
validated HTTPS addresses and a byte cap; returned bodies are independently
rechecked and parsed. If either response fails or changes the transaction's
endpoint set, no snapshot is published. There is no built-in HTTPS client,
redirect policy, TLS trust policy, timeout scheduler, cache writer or remote
refresh loop; future transport owns those details.

## Validation and independent oracle

`docs/native-config/run-tests.ps1` uses cached Kotlin2.0.21 compiler/JUnit jars
with explicit JDK21 and `-Xmx256m`, SerialGC, two active processors. No Gradle.

The independent test-only Node oracle uses the real JSON5 2.2.3 implementation,
actual local loader/merge functions and remote URL/fetch-rule functions. It stubs
only file reads/logging and replaces two bootstrap data literals with synthetic
`.test` values; algorithms are unchanged. All preset addresses use documentation
192.0.2.0/24. No personal configuration, key, CA or live endpoint is read.

The checked-in corpus has 55 valid JSON5 parses, 17 deliberately unsupported or
invalid cases and 40 layered snapshots. Comparison checks decoded values and
every nested object's own-key sequence, not just JSON string equality.
Original source hashes for all three loader/policy files, merge.js and parser
are in `upstream-provenance.json`.

Reproduce with the extracted upstream core path:

```powershell
pwsh -NoProfile -File docs/native-config/run-tests.ps1
pwsh -NoProfile -File docs/native-config/run-oracle.ps1 -UpstreamCore '<core>'
pwsh -NoProfile -File docs/native-config/run-consumers.ps1
```

The oracle runner uses the checked-in independent fixtures by default and checks
upstream source hashes. `-Regenerate` generates a fresh corpus into ignored build
output only; it never replaces checked-in expected values.

The real-consumer check compiles the sibling RulePlanner, DnsProviderConfig and
NativeRuntimeConfig source at recorded content hashes. Stable copies are pinned
in ignored build evidence; no peer file is edited or imported into tracked
production source. Later peer changes are reported separately. It proves a synthetic rule creates a 302 response, a configured
DNS provider produces the expected catalog, native runtime configuration decodes
the snapshot, and a real consumer rejection preserves the prior epoch.
It starts no DNS resolver, socket, proxy, certificate authority or Node runtime.

See verification.json for exact final test totals, source/tree identities and
artifact hashes. Ignored build/native-config retains compile/JUnit/oracle/consumer
logs, jars and argument files.

## Remaining integration

This is a native capability candidate, not activated Android behavior. Required
follow-up: full Android min24 execution, application-private directory ownership,
runtime owner activation/rollback across engine epochs, actual default asset
selection, and a separately reviewed HTTPS refresh transport. No actual remote
fetch or DNS/HTTP engine traffic has been tested here. Unsupported JSON5/rule
features must remain explicit rejections; do not silently interpret them through
Node or broaden the subset without new independent oracle cases.
