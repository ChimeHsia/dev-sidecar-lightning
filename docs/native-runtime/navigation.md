# Native navigation rules

The JVM runtime can now receive a navigation rule supplier through the optional
`navigationRules` parameter of `NativeRuntime` or `NativeRuntime.fromDecoded`.
Use `NativeNavigationRuleStore(defaults, configFile)::getRules` to seed a missing
file once and reload it on subsequent requests. The caller supplies the default
JSON; `port/node/navigation-rules.json` is the existing product ruleset.
No Android application entry point enables this runtime yet.

The store reads at most 64 KiB, accepts strict JSON, validates exact source hosts
and HTTPS target origins, and rejects duplicate sources and redirect cycles.
Malformed, oversized or missing files retain the last successfully compiled
rules. Existing files are never replaced during initialization.

Matching GET and HEAD document requests without request bodies produce a 307
response with `Location`, `Cache-Control: no-store`, `Content-Length: 0` and
`DS-Interceptor: https-navigation`. Rules preserve escaped path and query bytes
while normalizing URL dot segments. Nondefault ports, ambiguous paths and other
request types continue through the runtime's existing rule policy.

The proxy validates authority and framing before the policy runs. Navigation
responses precede upstream DNS selection. For decrypted HTTPS, CONNECT still
passes through the existing resolver and certificate policy before the inner
request can be inspected; this component does not override CONNECT whitelist
decisions.

`NativeNavigationTest` covers the repository default rules, invalid configurations,
hot reload and last-valid retention, a real loopback proxy response before DNS,
and two live rule updates through the assembled JVM runtime. These checks require
neither a device nor public network access.
