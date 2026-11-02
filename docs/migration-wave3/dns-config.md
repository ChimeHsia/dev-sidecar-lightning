# Pure DNS configuration migration

This slice is based on commit `754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
It adds inert constructor descriptions and hostname selection only. It does not
change exports, adapters, payloads, application builds, browser code, or runtime
DNS behavior. No device, network, or Gradle action is part of verification here.

## APIs and identity

All production types live in `com.dsha.sidecar.net.dns`, use Kotlin common
facilities only, and depend only on committed `IpAddress` and `HostnameMatcher`.

- `DnsProviderConfig.normalize(providers, options): DnsProviderCatalog` mirrors
  `initDNS` up to the constructor boundary. `descriptors` records construction
  order, including subsequently shadowed objects. `bindings` records the final
  `dnsMap` name-to-identity associations.
- `DnsProviderDescriptor` records `transport`, `server`, raw `port`, raw `family`,
  raw `cacheSize`, `sni`, ECH initialization options, and NAT64 constructor options.
  Transport strings are `https`, `tls`, `tcp`, `udp`, `preset`, and `nat64`.
- Identity `provider:<name>` denotes the object constructed for that configured
  provider. `builtin:PreSet` and `builtin:Nat64` denote the synthetic objects.
  Bindings such as `ForSNI`, `PreSet`, `Nat64`, and ordinary configured names point
  to those identities. They are not live objects or registry references.
  A configured `PreSet` can remain the `ForSNI` identity even after the `PreSet`
  binding is replaced by the builtin. The descriptor name for NAT64 is `NAT64`,
  while its lookup binding is `Nat64`.
- `DnsProviderConfig.toDohList(doh): List<String>` mirrors NAT64 `toDohList`.
- `DnsProviderConfig.resolveDohList(config, providers): List<DnsProviderDohEntry>`
  mirrors NAT64 `resolveDohList`, including exact URL deduplication, first SNI,
  and the three built-in fallback URLs in upstream order.
- `DnsProviderSelection(catalog, mapping, preSetIpList, ech, nat64Domains,
  echDomains, echPreSetIpDomains)` snapshots decoded configuration. Its domain
  inputs are raw arrays/maps, before upstream `domainMapRegexply`. `echDomains`
  and `echPreSetIpDomains` default to the corresponding `ech` fields; explicit
  null can represent an absent prepared map.
- `select(hostname): DnsProviderChoice?` mirrors `getDNSAndFamily`. The companion
  queries `isNat64Domain`, `isEchDomain`, `isEchIgnorePreSetIp`, and
  `getEchProviderKey` expose the same decisions without a resolver.

`DnsProviderChoice.family` is the mapping's raw family override. A null means no
override; it does not mean IPv4 and does not inherit the provider's constructor
family. That input remains on its descriptor. NAT64, preset, and ECH early
returns carry no mapping family. Transport/BaseDNS parsing, port defaults,
request family parsing, cache construction, and live lookup fallback are outside
this boundary. Do not pass descriptions directly into a runtime without a
separately reviewed adapter.

## Supported input and preserved behavior

Inputs are acyclic decoded JSON values: string-keyed maps, lists, strings, finite
numbers, booleans, and null. Top-level provider/options/config/mapping containers
are objects. Provider values are objects; missing server/host skips a provider.
Text fields that are consumed (`server`, `host`, `type`, `sni`, `servername`,
`dns`, `dnsName`) are strings or null. Port, family, and cacheSize values are
numbers, strings, or null. ECH/NAT64 options are objects or null; a DNS mapping
entry is an object with `dnsName` and optional `family`.

Every JSON input tree is copied and validated before field selection, including
both objects supplied to `resolveDohList`, the direct `toDohList` argument, and
all selection domain arguments. The root is depth zero; maximum depth is 64,
and maximum node count is 100,000 per tree. Every container and scalar/null
value counts as one node; object keys do not count as extra nodes. Shared
acyclic subtrees are copied and charged once per occurrence. Cyclic containers,
greater depth, and excessive nodes raise `IllegalArgumentException`, with
identity-based ancestor checks that do not recursively compare containers.
List copying grows only within the node budget and does not preallocate the
source list's potentially excessive size. These bounds apply to JSON tree
structure, not string length or regex execution cost.

Provider aliases and protocols follow the actual upstream branches, including
HTTP/HTTPS inference, TLS/DoT, TCP, UDP, and explicit
`dns-over-https`/`dns-over-tls`. An unknown explicit type becomes UDP. An unknown
inferred scheme throws. Only the first whitespace run is removed by ordinary
provider normalization, whereas NAT64 removes every whitespace run. Leading and
trailing whitespace can therefore leave an ordinary ` DoH ` type unrecognized.
This is intentionally not silently repaired.

Bracketed IPv6 with an embedded port is split only when `conf.port` is null.
With an explicit port, brackets are removed but the embedded port stays in the
server string, as upstream does. Bare IPv6 remains intact, including the
upstream bracket shortcut and its narrower link-local scope syntax. Valid
unscoped IPv6 uses committed `IpAddress`. Malformed IPv6 acceptance is not a
validation guarantee of this API. A server containing any slash is left alone
for DoH: `http://dns.test` gets no default path, and `dns.test/query` gets no
scheme. Descriptors deliberately preserve such strings.

JavaScript array-index provider keys sort numerically before other keys; other
providers retain insertion order. The last truthy `forSNI`/`forSni` wins, then
`ForSNI` defaults to the builtin preset when no binding exists. ECH enablement
uses strict false tests; NAT64 construction uses strict true.

Selection priority is NAT64, eligible preset, ECH DNS, then hostname mapping.
NAT64 presence is the `Nat64` binding, even for a user provider with that reserved
name. ECH normally ignores preset IPs, with explicit domain exceptions; NAT64
always ignores them for initial selection. Missing ECH DNS falls through.
`usa` aliases `cloudflare` only in mapping selection, and only when `usa` has no
binding. ECH DNS names are not trimmed or aliased. Missing/unknown mappings
return null; `ForSNI` is not an automatic fallback from `select`.

Preset match values use JavaScript truthiness, so empty arrays/objects match.
ECH domain arrays ignore nonstring and empty elements. ECH domain objects
include every value except false/null (including zero and empty strings).
Case, exact matching, apex shortcuts, leading-dot aliases, and wildcard/regex
insertion order follow committed `HostnameMatcher`.

## Explicit differences and limits

- This is a strict JSON subset, not JavaScript's general coercion surface:
  numeric/object DNS names or SNI, scalar `doh`, and nonstring `doh` array entries
  throw instead of being coerced. NAT64 `doh` accepts null, a comma-separated
  string, or a string list; commas inside list entries are not split.
- All input trees reject non-JSON values, non-finite numbers, cycles, and
  depth/node budget violations, even under unused fields. Upstream does not
  enforce these resource bounds. Field-specific shape validation follows the
  original access branches: an unused field with a valid JSON value is inert,
  while consumed scalar fields and option containers must have supported shapes.
- There is no inherited-property enumeration or prototype lookup. Prototype
  names such as `__proto__`, `constructor`, and `toString` cannot create phantom
  provider/matcher results. Inputs are copied rather than retained by reference.
- `HostnameMatcher` ignores invalid regexes, whereas upstream lookup can throw.
  Regex dialect differences outside its supported common subset remain.
- Legacy `util.ip.isIPv6` accepts some malformed addresses; only valid standard
  IPv6, its bracket shortcut, and its link-local scope branch are in scope.
- Null and absent descriptor fields, and null and absent returned mapping family,
  share the same projection. Warnings/logging are intentionally absent.
- No ECH record lookup, DNS queries, timers, retries, bootstrap resolution,
  preSet fallback after a failed lookup, or runtime defaults are implemented.

## Oracle and verification

`port/tests/fixtures/wave3/dns-config.json` is an array of
`{name,input,expected}` objects, with `input.operation` selecting `normalize`,
`toDohList`, `resolveDohList`, or `select`.

The local oracle script loads the actual upstream modules with their installed
dependencies. It executes `initDNS`, `getDNSAndFamily`, `toDohList`,
`resolveDohList`, the real matcher, and the real ECH domain helper. DNS
constructors are replaced with argument recorders; logging and NAT64's
module-level HTTPS Agent are inert. The normalization projection records the
constructor arguments and `initEch` options, not invented transport internals.
Only projection and identity labeling occur in the recorder.

Run from the repository root, substituting the read-only upstream `src` path:

```powershell
node port/tests/oracles/wave3/dns-config-oracle.cjs --upstream '<mitmproxy/src>' --generate
node port/tests/oracles/wave3/dns-config-oracle.cjs --upstream '<mitmproxy/src>' --verify
```

`--generate` is the only mode that writes expected fixtures. `--verify` only
loads, executes, and compares. The oracle never writes upstream.

`DnsProviderConfigTest` and `DnsProviderSelectionTest` exercise the Kotlin APIs
directly. `DnsProviderOracleTest` independently replays the committed expected
fixtures against those APIs without Node or another dependency.
The test-only common wrapper
`port/tests/wave3/src/jsMain/kotlin/CheckDnsConfig.kt` supplies
`internal fun checkDnsConfig(input: Map<String, Any?>): Any?` for the parent's
aggregate Kotlin/JS fixture harness; it has no export or production wiring.

Local verification on 2026-11-02:

- Independent Node `--verify`: 25/25 fixtures passed.
- Cached Kotlin 2.0.21 compiler, JDK 21, JUnit 4.13.2: 17/17 tests passed after
  the bounded JSON copy patch, including cycle/depth/node boundary tests and
  replay of all 25 fixtures. The common test wrapper also compiled.
- One compiler invocation with a 384 MiB heap, then one JUnit process with a
  192 MiB heap; both exited. No Gradle or compiler daemon was started.
- The parent reported aggregate JVM tests, actual Kotlin/JS execution of all
  25 fixtures, and independent Node oracle verification passed before the
  bounded JSON copy patch. Aggregate verification of the final patch is
  delegated to the parent's single serial rerun.

## Upstream source fingerprints

SHA-256 of the read-only upstream snapshot used by the oracle:

| Source relative to mitmproxy/src | SHA-256 |
| --- | --- |
| `lib/dns/index.js` | `1b869add75eca57ed60c74e5a34dfa15ce78bc2a095afafe24288c484aa25141` |
| `lib/dns/nat64.js` | `7371b61b06187952520ce63c6891cb599df83e223bed780e6f1f4001178be2ab` |
| `lib/dns/util.ip.js` | `0db0c7a14304b17403aea1753e9517aa43a340d76af12a53d978d527f2315f44` |
| `utils/util.match.js` | `59450826eea8f4f73ffe56e021a577c5a5faa663d6b446482190fa3c27ca4d6a` |
| `lib/proxy/common/ech/domain.js` | `b33bff813f3f895c578b3eec0a34c3e3721a3b4de940bf870a15ab77861292d7` |
