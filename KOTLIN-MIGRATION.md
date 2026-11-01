# Kotlin 替代范围与首批小模块迁移

核对日期：2026-11-01。按模块边界和可测试性拆分，不按业务重要性排序。

## 对照基线

- 仓库中的 `netcore/`、参考壳 `app/`、Lightning 的 `DshaAccel` 和 `port/node/main.js`。
- 本机安装包中的 `@blue-frontier/dev-sidecar` 和 `@blue-frontier/mitmproxy`，两个包的 `package.json` 均为 **2.3.1**。
- 原始资源目录为 `D:/App/Net/dev-sidecar/resources`；源码同时来自 `app.asar` 和 `app.asar.unpacked`。
- `app.asar` SHA-256：`0587d99fba40e0276408c8e7c66da0b1537c18d03cc96276180c1c64f37c65b1`。此校验值仅标识归档，不包含 unpacked 文件。
- 本次核对使用的源码副本位于被忽略的 `port/.run/upstream/node_modules/@blue-frontier/`，不提交第三方源码、Node 负载或证书私钥。版本号相同不代表其他机器上的安装包内容完全相同。

下文用 `K` 表示 `netcore/src/main/kotlin/com/dsha/sidecar/net`，`M` 表示上游 `mitmproxy/src`，`D` 表示上游 `dev-sidecar/src`。上游路径均相对上述提取目录。

## 当前实际调用链

Lightning 是独立 Gradle 构建，`lightning-browser/settings.gradle.kts` 只包含其 `app`，尚未依赖根项目的 `netcore`。实际路径为：

```text
BrowserApp -> DshaAccel -> Node 进程 -> port/node/main.js -> 原版 mitmproxy
WebView -> ProxyController -> 127.0.0.1:31181
```

根项目的 `app/` 才依赖 JVM 版 `netcore`，它同时包含 Kotlin 参考转发器和 Node 启动逻辑。本轮另加 `netcore-js`，直接复用四个纯逻辑文件编译成 CommonJS，由 Node 同进程调用。Lightning 无需增加 JVM `netcore` 依赖即可使用这些 Kotlin 实现。

`TlsClientHello.rewriteSni` 的调用方仅见测试。`K/proxy/LocalProxyServer.kt` 的 CONNECT 实际保持 ClientHello 和 SNI 不变，直接转发 TLS 字节。

## 能力差距

| 能力 | 现有 Kotlin | 上游能力及尚缺范围 | 代码依据 |
| --- | --- | --- | --- |
| DNS 查询与 provider 选择 | UDP、截断后 TCP、固定 DoH、系统兜底；多源 A 记录收集 | 自定义 UDP/TCP/DoT/DoH provider、按域名选 provider/family、DoH 的 SNI 配置；当前多源收集是串行且有数量阈值 | `K/dns/DnsClient.kt`；`M/lib/dns/index.js:19`、`:208` |
| DNS/IP 优选 | 并发 TCP 握手测速，缓存单个赢家，连接失败后部分重试 | 每候选成功/失败状态、真实请求反馈、跨请求切换、存活池、后台复测、按域名与端口隔离 | `K/dns/IpBenchmark.kt:14`；`M/lib/choice/index.js:139`；`M/lib/speed/SpeedTester.js:44`、`:345`；`M/lib/speed/index.js:10` |
| 缓存 | 单 IP、TTL、可选 TSV 持久化，无容量淘汰 | 普通 IP 缓存按 provider 使用有界 LRU，保存动态候选；所读上游普通缓存不按 DNS TTL 过期，不能当作现有缓存的等价替换 | `K/dns/DnsCache.kt`；`M/lib/dns/base.js:59`、`:82`、`:323` |
| 预设 IP | 静态后缀种子池、精确域名单 IP pin | 运行时预设表、逐 IP 启停、数组/对象配置、域名通配与正则；上游有效预设直接替代查询，当前先查 DNS，种子失败才测 DNS 候选 | `K/dns/IpSeed.kt`、`K/dns/HostResolver.kt:54`；`M/lib/dns/base.js:36`、`:399` |
| IPv6、NAT64、扩展 DNS | 报文层能解 AAAA；普通解析编排仍只取 IPv4；本轮补地址工具 | family 路由、接口能力与不可达降级、完整 NAT64 DoH/bootstrap/缓存、HTTPS/SVCB/ECH 元数据、Cloudflare 地址改写 | `K/dns/DnsPacket.kt:52`、`K/dns/DnsClient.kt:111`；`M/lib/dns/nat64.js`、`util.svcb.js`、`base.js:149` |
| SNI 伪装 | 有独立编解码工具与测试，运行代理不改 SNI | MITM 终止下游 TLS 后新建上游 TLS；独立表达 servername、verifyHost 和上游证书校验策略 | `K/proxy/TlsClientHello.kt`、`LocalProxyServer.kt:252`；`M/lib/interceptor/impl/req/sni.js:19`；`M/lib/proxy/mitmproxy/createRequestHandler.js:246`、`:375` |
| MITM | CONNECT 为原始 Socket 隧道，不解密 HTTPS | CONNECT 白名单、规则/ECH 触发 MITM、本地 TLS 服务器、下游 HTTP/1 与 HTTP/2、独立上游连接及错误处理 | `M/options.js:178`；`M/lib/proxy/mitmproxy/createConnectHandler.js:43`；`M/lib/proxy/tls/FakeServersCenter.js:123` |
| 证书生成与缓存 | `netcore` 未实现；Android 壳复制固定 CA/私钥并配置 App 信任 | CA、动态叶证书、SAN/AKI、签名、按名称去重生成、假服务器缓存与淘汰关闭。上游所读缓存没有到期检查和自动轮换；这些不能写成已具备能力 | `M/lib/proxy/tls/tlsUtils.js:36`、`:127`；`CertAndKeyContainer.js:32`；`FakeServersCenter.js:45` |
| 证书信任管理 | `DshaAccel` 复制打包的 CA 和私钥；`network_security_config.xml` 仅让本 App 信任该 CA | Android CA 生命周期、导入导出、私钥存储与轮换；桌面系统安装脚本无法直接复用。固定打包私钥持有者能签发此 App 信任的证书，这是当前实现的实际信任边界 | `lightning-browser/app/src/main/java/acr/browser/lightning/accel/DshaAccel.kt:172`；`D/shell/scripts/setup-ca.js:100` |
| 规则与配置 | host 正则、首条命中；DIRECT/ABORT/ACCELERATE/PROXY 四种动作 | host→path、exclusions、捕获组、单结果匹配与多结果合并的区别、拦截器 order/priority、用户/个人远程/共享远程/默认配置的值和键顺序 | `K/rules/RuleEngine.kt`；`M/utils/util.match.js`；`M/options.js:232`、`:283`、`:320`；`D/config/local-config-loader.js:90` |
| HTTP 请求/响应变换 | 首条普通 HTTP 请求转为 origin-form 后整流转发；不能按 HTTPS 路径处理 | URL/Host/路径重写、请求与响应头修改、重定向、备用目标、重试、HTTP 缓存、脚本注入、gzip/deflate/br 解压重压 | `K/proxy/LocalProxyServer.kt:268`；`M/lib/interceptor/index.js:24`；`M/lib/proxy/middleware/InsertScriptMiddleware.js:9` |
| PAC、插件、系统集成 | Kotlin 未实现完整插件/PAC；Android 采用固定 WebView 代理 | 上游插件会合并 DNS/intercepts 并覆盖配置，PAC 可参与分流；当前 Android 首次配置关闭插件，系统代理操作置空，不能把包内存在的能力都算作 Android 已启用 | `D/modules/server/index.js:110`；`M/lib/proxy/middleware/overwall.js:164`；`port/node/main.js:29`、`:77` |

两边的 TCP 测速都不能证明目标 IP 的 TLS 证书或 HTTP 内容正确。

### TLS 的替代边界

透明转发时，浏览器已经生成 ClientHello。中途改写它会造成两端握手转录不同，不能作为有效的通用 SNI 伪装方案。已有 `TlsThroughProxyTest` 使用本地 TLS 服务验证了改写后的失败，但没有锁定 TLS 1.3 或具体失败阶段。

上游 MITM 分别完成“浏览器↔本地代理”和“本地代理↔目标”的握手，上游 ClientHello 从生成时就使用所选 SNI。需要重建这条链路，才能替代其 HTTPS 路径规则和 SNI 功能。

所读 `sni.js` 对普通非空 SNI 规则会尝试切到不验证证书的 Agent，并非只有显式 `unVerifySsl` 才影响校验。后续 Kotlin 实现需明确建模这几个独立参数及兼容行为。

### 静态核对发现的现有衔接问题

本轮记录这些问题，尚未改动对应优选或转发行为：

1. `HostResolver.Result.ip` 是测速赢家，但 `candidates` 仍按种子加 DNS 的原始顺序。CONNECT 遍历 `candidates`，第一次实际连接的 IP 可能与显示及缓存的赢家不同。
2. `resolveQuick` 和 `resolve` 共用缓存。快速解析写入的未测速地址可能使后续加速解析直接命中；测速默认 443，缓存也未按目标端口隔离。
3. 全部候选测速失败时仍缓存第一个候选；缺少上游的逐候选请求反馈。
4. 普通 HTTP 分支没有使用 PROXY 的目标地址；后续请求整体透传，不能逐请求重新选路。
5. `Rule.cacheDays` 尚未接入处理。上游同名字段表示 HTTP 缓存时长，不能当作 DNS TTL。

## 首批迁移

这些模块无 Android、外网或 Node 运行时依赖，使用现有 Kotlin/JVM 和 JUnit 4。

| 模块 | 本轮实现范围 | 接线状态与兼容边界 |
| --- | --- | --- |
| `dns/IpAddress.kt` | 严格 IPv4/IPv6 字面量解析及全零地址识别，不触发 DNS | `HostResolver.isIpLiteral` 委派给新校验；尚未把零 IP 排除策略接入完整候选收集/测速 |
| `dns/Nat64Address.kt` | 显式 `/96` 前缀与 IPv4 的纯地址转换 | 已替换 Node NAT64 内部函数及其导出，真实 DNS `_lookup` 与 DoH bootstrap 调用同一 Kotlin 转换；无默认网关；拒绝非法/全零前缀及非 `/96` |
| `rules/HostnameMatcher.kt` | 单结果 `matchHostname`：精确优先、根域快捷匹配、前导点别名、通配符/正则顺序、null/false 行为 | 已替换 Node 单结果匹配入口，初始化时编译、查询时直接返回原 JS 值引用；`matchHostnameAll` 与路径匹配保留上游实现；尚未替换 JVM `RuleEngine` |
| `config/ConfigMerge.kt` | JSON 对象树递归合并、数组整体替换、最终清理 null/`"[delete]"`、结果深拷贝 | 已替换 Node `doMerge`/`deleteNullItems`，包括初始配置加载和后续调用；适配层保持根和嵌套对象引用及数组删除空位；JVM API 默认仍压实数组。`doDiff`、配置源加载及其键顺序预置保留上游 |

地址校验有意比上游宽松的辅助函数严格。尤其不再把“包含冒号”直接当作有效 IP。域名匹配使用 Kotlin Map，不复刻上游普通 JS 对象对 `constructor`、`__proto__` 等继承属性的误命中。各工具的具体输入契约以 KDoc 和测试为准。

新实现不等于以上能力整体已迁移：NAT64 转换不等于 NAT64 DNS，域名匹配不等于完整规则引擎，配置深合并不等于远程配置系统。

### 实际替换方式

`netcore-js/build.gradle.kts` 从 JVM 源目录选取纯逻辑文件，编译为 CommonJS。`Exports.kt` 仅负责 JS/Map/List 边界。域名匹配以原始键和存在标志预编译成索引匹配器，查询时不做 JSON 转换，返回的是原 JS 配置对象。

`port/node/kotlin-helpers.js` 在核心初始化前替换单结果域名匹配和配置函数；原有多层配置加载顺序继续使用原版加载器。合并与清理不是每个请求的域名匹配路径：它们通过 JSON 传入 Kotlin，再由适配层把结果同步回原 JS 容器。Node 清理启用保留数组槽位模式，适配层将 null 占位还原为稀疏空位。

`port/kotlin_payload.py` 替换 NAT64 文件内部的转换函数体，使其内部闭包调用也执行 Kotlin，不只替换末尾导出。遇到无法识别的上游源码布局会打包失败。编译产物缺失也会明确失败，不静默回退 JS 业务逻辑。

这属于同一份 Kotlin 源码的 JVM/JS 双目标迁移，实际执行位置仍是 Node 进程；它减少 JS 业务实现，尚未减少 Node 运行时体积。纯 Android/JVM 替代需要继续迁移调用它们的 DNS、规则与代理链路。

Node 适配仅接受 JSON 对象配置，不保留 overlay 数组本身的引用；替换后的数组来自 Kotlin 结果。原根对象、保留的嵌套对象及清理时的数组引用保持不变。它不是任意 JS 对象的 lodash 替代，函数、循环引用或数组与对象混用的非 JSON 配置不在此边界内。

## 离线验证

```bash
./build.sh :netcore:test --offline --rerun-tasks
./build.sh :netcore-js:jsNodeProductionLibraryDistribution --offline
python -m unittest discover -s port/tests -p 'test_*.py'
```

Windows 可直接使用已有 Gradle 8.14 的 `gradle.bat` 执行相同参数，设置 JDK 21。JVM 测试中外网冒烟需显式传 `-Pdsha.live=1`，本轮不启用。

Node 适配测试可指定真实编译模块：

```bash
DSHA_KOTLIN_HELPERS="$PWD/netcore-js/build/dist/js/productionLibrary/dev-sidecar-lightning-netcore-js.js" \
  node --test port/tests/kotlin-helpers.test.js
```

完整核心集成测试需将更新后的 `nodejs-project.zip` 解压至 `port/.run/runtime/`，或以 `DSHA_TEST_PROJECT` 指定解压路径，然后执行 `node --test port/tests/runtime-integration.test.js`。它使用真实核心与 Kotlin 编译产物，验证默认配置合并、保留的多结果匹配，以及 NAT64 `_lookup` 内部确实调用 Kotlin；仅将真实 DNS 查询替换为固定 A 记录。

新增测试覆盖：地址合法/非法边界、IPv6 压缩与嵌入 IPv4、全零地址、NAT64 转换及拒绝条件、域名匹配优先级和别名、配置覆盖/删除/数组/不可变输入。旧 DNS、隧道和本地 TLS 回归也需一起运行。

本轮实际结果：JVM 共 71 项，69 通过、2 项外网冒烟跳过；真实 Kotlin 编译产物参与的 Node 适配测试 19 项通过；完整核心集成测试 4 项通过；payload 工具测试 3 项通过。Lightning `assembleLightningPlusRelease --offline` 成功，并核对 APK 内嵌 zip 与更新后的 payload 一致，包含 Kotlin 模块、适配脚本与 NAT64 闭包替换。尚未做本次 APK 的真机安装或网页访问验收。

## 后续按可测试性拆分

以下是拆分顺序，不代表业务重要性排名：

| 边界 | 下一批模块 | 不依赖真实外网的验证方式 |
| --- | --- | --- |
| 纯状态与决策 | 动态候选 Choice、探测占用/存活池、按 host:port 的缓存与失败反馈 | 固定候选、假时钟、注入探测结果，验证赢家优先、失败切换、耗尽与重置 |
| 纯规则与数据 | `matchHostnameAll`、配置源值优先级与键顺序、路径 exclusions/order/priority | 固定规则树；将 JS 参考结果保存为夹具；分别验证值、键顺序及捕获组 |
| 纯请求变换 | URL/Host/路径目标构造器、请求/响应头变换、重试与缓存策略 | 固定 URL、头、多值字段、方法、状态、次数、时钟和不可重放请求体 |
| 二进制协议 | HTTPS/SVCB 编解码与 ECH 配置选择 | 固定字节夹具、压缩名、长度边界和畸形输入；不把解析成功当作 TLS ECH 支持 |
| 生命周期 | 证书缓存键、并发生成去重、失效与 CA 轮换策略 | 注入证书工厂和时钟；测试并发、生成失败后重试、淘汰关闭。生成/签名实现另行验证 |
| 本地集成 | DNS/provider 编排、独立上游 TLS、MITM 服务端、HTTP 逐请求处理 | 本机 DNS/HTTP/TLS 假服务及临时 CA，验证 SNI/verifyHost、链信任、连接复用和失败行为；随后再做设备测试 |

MITM、证书和独立上游 TLS 需要单独的集成验收。本轮保留现有 Lightning Node 路径。
