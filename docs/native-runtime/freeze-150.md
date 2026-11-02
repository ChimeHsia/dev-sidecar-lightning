# Accepted 150-test assembly freeze

This snapshot precedes the authorized engine retry/capture-interface work.
The full suite passed 150 tests in 12.307 seconds: 41 runtime integration,
52 engine, and 57 scheduling tests including 20 upstream-oracle cases.
Compiler and JUnit used 256 MiB heap caps. No Gradle, device, main checkout,
Node process, payload, persisted CA/key or generated binary is included.

All 59 core and 15 schedule copied files retain their exact byte hashes.
The core snapshot matches Git content at
`de9532717eb6fe925cddef3bb77f0a01859ffb5c` after Git checkout newline
normalization. The canonical working copy of SocketDnsTransport has advanced;
this snapshot deliberately retains the recorded core version.

| File | SHA-256 of frozen working-tree bytes |
| --- | --- |
| `proxy/native/NativeProxyServer.kt` | `5BEE92603048FFFE0EA46912EBBFC7BBDE82137A91BC3C904A392DC8BED86C55` |
| `proxy/native/NativeTlsConnector.kt` | `406B8AE345D8A05BBD5CD4367FA2E0203EE5B6B0F4C393892654096D82F70D54` |
| `proxy/native/NativeProxyRoute.kt` | `6004834AE05EC92605F4C923C3997761FD1AFAC1E75F2CBA2ACADAA4F0217A65` |
| `rules/native/RulePlanner.kt` | `84205BA09D7B61C7847F2C5A137E7B78B464E26CC618156D8FD46AD7A55F929E` |
| `http/native/RuleActionExecutor.kt` | `3009F2651E7997B377FBBA119854AF14C7BB762ADA5315AD6C2F695056009BC9` |
| `dns/native/SocketDnsTransport.kt` | `232E58F91048E8DF67441D97E315F5DDD9C0D4D6C60C7CD20322735578B0660C` |
| `docs/native-runtime/prerequisites.json` | `69E53CAB2CD783DBB6CBB51ED730912A97B655BA9654DF1ED66AB795BD6E61E3` |
| `docs/native-runtime/schedule-prerequisites.json` | `27392A9DA02286440CA25A85B764E57E2A9E9E9DFA1BADEC6488E80EA19BC61D` |

Source paths in the first six rows are relative to
`netcore/src/main/kotlin/com/dsha/sidecar/net/`.
The two manifests contain the complete copied-file hash lists.
