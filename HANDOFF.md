# Dsha Sidecar（安卓 GitHub 加速浏览器）— 任务交接文档

> 用途：把当前进度交接给另一个 AI 继续开发。
> 工作区：`d:/File/User/code/dsha-sidecar`
> 最近更新：2026-10-31（构建已通、netcore 三大模块完成、加速开关接入 WebView）

---

## 1. 项目目标

把 [docmirror/dev-sidecar](https://github.com/docmirror/dev-sidecar)（PC 端 GitHub 加速工具）的加速能力移植到 Android，但**不接管系统和其它 App 的流量**，而是做成一个**自带加速能力的极简浏览器**（对标 Via 的体积，3–6MB）。

### 核心决策（已确认，不要推翻）

1. **不做 MITM、不装根证书、不用 VpnService、不需要 root。**
   注意：这意味着**不能在链路上改写 TLS 字节**（改写 SNI 会破坏握手，见第 4 节）。加速靠"连更快的 IP、SNI 与证书保持原域名"。
2. **不用 Electron / Node（nodejs-mobile）。** `libnode.so` 会让 APK 增大 30MB+。核心逻辑用 Kotlin 重写。
3. **不二开现成开源浏览器**（GPL 传染 + 包体 8–15MB + 他人 6 万行代码）。
4. **与上游 dev-sidecar 的唯一交集是"规则语义"**：`redirect / proxy / abort` 这类 JSON 正则规则，用 Kotlin 重新实现匹配器，不搬代码。

### 目标架构（已落地）

```
WebView（渲染，复用系统 Chromium，不打内核）
   ↓ ProxyController.setProxyOverride("127.0.0.1:8118")
本地转发器 LocalProxyServer（Kotlin，netcore 模块，纯字节转发、不解密）
   ├─ DNS 优选：自建 UDP 解析（TCP 截断回退 / 系统解析兜底）
   ├─ IP 测速：并发 TCP 443 握手，取最快
   ├─ CONNECT 目标改写：host:443 → 优选 IP（SNI 原样保留）
   └─ 规则引擎：DIRECT / ACCELERATE / ABORT / PROXY
```

---

## 2. 环境事实（照抄即可）

| 项 | 值 |
|---|---|
| OS / Shell | Windows 11，**shell 是 Git Bash**（MINGW64）。不要用 PowerShell 语法 |
| 工作区 | `d:/File/User/code/dsha-sidecar`（bash 下 `/d/File/User/code/dsha-sidecar`） |
| JAVA_HOME（系统） | `D:\App\Dev\graalvm`（GraalVM 21.0.11，**不能直接构建 Android**） |
| 构建用 JDK | **工作区 `.jdk/jdk-21.0.2`**（标准 OpenJDK 21，已由 `gradle.properties` 的 `org.gradle.java.home` 指定） |
| ANDROID_HOME | `D:\App\Dev\Android` |
| SDK 组件 | platforms: android-34/35/36；build-tools: 35.0.0 / 36.0.0；ndk、cmake 已装；**无模拟器、无 system image、无真机** |
| adb | 37.0.0（platform-tools 已在 PATH） |
| Gradle | 不在 PATH。用 `~/.gradle/wrapper/dists/gradle-8.14-all/c2qonpi39x1mddn7hk5gh9iqj/gradle-8.14/bin/gradle` |
| 工程配置 | AGP 8.7.3 + Kotlin 2.0.21 + compileSdk 35 + minSdk 24 + targetSdk 35 + viewBinding |

构建与测试命令：

```bash
cd /d/File/User/code/dsha-sidecar
./build.sh assembleDebug                 # 或 ./build.sh assembleRelease
GR=~/.gradle/wrapper/dists/gradle-8.14-all/c2qonpi39x1mddn7hk5gh9iqj/gradle-8.14/bin/gradle
"$GR" :netcore:test                      # 28 个用例，2 个联网用例默认 skip
"$GR" :netcore:test -Pdsha.live=1 -Pdsha.liveHost=raw.githubusercontent.com   # 联网冒烟
```

**产物**：`app/build/outputs/apk/debug/app-debug.apk`（3.3MB）、`app/build/outputs/apk/release/app-release-unsigned.apk`（**558KB**，R8 已开）。

---

## 3. 已完成

### 3.1 构建链（原阻塞已解除）

原失败点：`:app:compileDebugJavaWithJavac` → `JdkImageTransform` 调 GraalVM 的 `jlink` 处理 `core-for-system-modules.jar` 失败。

修复：下载标准 OpenJDK 21.0.2 解压到 `.jdk/`（已 gitignore），`gradle.properties` 增加：

```properties
org.gradle.java.home=D:\\File\\User\\code\\dsha-sidecar\\.jdk\\jdk-21.0.2
```

`android.experimental.disableJdkImageTransform` 在 AGP 8.7.3 已无效，保留为注释备查。

### 3.2 netcore：DNS 优选（`netcore/src/main/kotlin/com/dsha/sidecar/net/dns/`）

| 文件 | 说明 |
|---|---|
| `DnsPacket.kt` | 手写查询报文（A/AAAA + EDNS0 1232）、应答解析（名称压缩指针、CNAME 链跟随） |
| `DnsClient.kt` | 多上游轮询（223.5.5.5 / 119.29.29.29 / 1.1.1.1 / 8.8.8.8），TC=1 走 TCP 重试，**全部失败退回系统解析**（Captive Portal / 内网 DNS 场景） |
| `IpBenchmark.kt` | 候选 IP 并发 TCP 443 握手测速，取最快 N 个 |
| `DnsCache.kt` | TTL 感知内存缓存 + TSV 持久化（冷启动复用上次测速结果） |
| `IpSeed.kt` | 内置种子 IP 池（GitHub 关键域）。国内 DNS 常只给一个地址，那台挂了就全挂；种子只作**额外候选**，仍要测速 |
| `HostResolver.kt` | 编排：缓存 → 多源解析 → 测速 → 优选；同域名探测串行化；缓存 IP 连不上时 `invalidate` 后重测一次 |

实测（本机）：`raw.githubusercontent.com` 拿到 4 个候选 IP，最快 76ms。

### 3.3 netcore：规则引擎（`.../net/rules/`）

- `Rule(hostPattern 正则, action, target, cacheDays, note)` + `Policy`
- 动作：`ACCELERATE`（优选 IP）/ `DIRECT`（直连，普通站点默认）/ `ABORT`（拦截）/ `PROXY`（改连其它 host:port）
- `BuiltinRules.DEFAULT`：GitHub 主域 + `*.githubusercontent.com` + `*.githubassets.com` + `github.io` + `ghcr.io` 走加速；**其它站点一律直连**，避免给普通网页增加 DNS 探测延迟

### 3.4 netcore：本地转发器（`.../net/proxy/`）

| 文件 | 说明 |
|---|---|
| `LocalProxyServer.kt` | 绑定 127.0.0.1:8118；解析 `CONNECT host:port` 与绝对 URI 的普通 HTTP；规则 → 优选 IP → 连接（失败按候选逐个重试，最后回退系统解析）；统计与回调 |
| `Tunnel.kt` | 双向转发，正常 EOF 走半关闭（`shutdownOutput`），异常关闭双方解除阻塞 |
| `TlsClientHello.kt` | ClientHello 解析 + SNI 改写（含全部长度字段回填）。**仅诊断/测试用，链路上禁止调用**，原因见第 4 节 |

### 3.5 app 层

- `accel/AccelManager.kt`：转发器生命周期 + `ProxyController.setProxyOverride`（`WebViewFeature.PROXY_OVERRIDE` 探测，不支持时提示并回退直连）+ 状态快照 + 启动自检（`diagnose()` 打 DNS/解析结果到 logcat）
- `MainActivity`：加速开关（状态栏一行可点，菜单项同效）、状态栏显示端口/请求数/加速数/失败数/最近 host→IP→延迟（每秒刷新）、开关状态持久化、加速就绪后再加载首屏、**自己处理 window insets**（targetSdk 35 强制 edge-to-edge）
- `res/xml/network_security_config.xml`：允许明文（浏览器语义 + 本地 127.0.0.1 代理）
- 日志 tag：`dsha-accel`（DNS/转发器）、`dsha-ui`（WebView 加载）。排查真机问题直接
  `adb logcat -s dsha-accel:* dsha-ui:*`

### 3.6 测试（`netcore/src/test/kotlin/`，28 个用例）

- `DnsPacketTest`：报文编码/解析、CNAME 链、TC/rcode、非法标签
- `DnsCacheTest`：TTL 上下限、过期、持久化、大小写
- `TlsClientHelloTest`：SNI 解析、变长改写后各层长度自洽、截断/非 TLS 安全返回
- `RuleEngineTest`：子域匹配、非 GitHub 直连、ABORT/PROXY 优先级、大小写
- `LocalProxyServerTest`：普通 HTTP 请求行改写 + CONNECT 隧道（本地回环，无外网）
- `TlsThroughProxyTest`：**本地真实 TLS 栈闭环**——JSSE 客户端 → 代理 → 嗅探中继 → 自签名 TLS 服务端；验证握手成功、SNI 原样，以及"改写 SNI 必然握手失败"的回归护栏
- `LiveAccelSmokeTest`：联网冒烟（默认 skip，`-Pdsha.live=1` 开启）

### 3.7 真机验证（荣耀 PPG-AN00 / Android 16，校园网 NNU-WiFi6）

结论：**加速链路端到端可用**。

- 现象：不开加速时 github.com 直接 `net::ERR_NAME_NOT_RESOLVED`（该系统/网络下 `mscw.wifipro.dns_fail_count=19`，系统 DNS 基本不可用）。
- 根因 1：该网络 **UDP 53 被拦截**，四个公共 DNS 全部超时 → 已加 **DoH 兜底**（`https://223.5.5.5/dns-query` 走 443，零新依赖，用 `HttpURLConnection`），并记住上次成功的通道（`DnsClient.preferDoh`）。
- 根因 2：`TCP 443` 到 GitHub IP 是通的（实测 76–110ms），所以只要解析出 IP 就能加速。
- 结果：开加速后 GitHub 首页完整渲染（含图片/CSS），状态栏可见
  `请求 7 · 加速 5 · 最近 api.github.com → 20.205.243.168（176ms）`；
  用 `adb forward` + 独立 TLS 客户端（Python ssl）验证隧道：CONNECT 200 → TLS 1.3 握手 → 证书 CN=github.com → HTTP 200，全程 0.7s。
- 该网络还对部分站点做 TLS 中间人（example.com 证书链校验失败），属环境特性，不是本项目的 bug。

### 3.8 dev-sidecar 能力对照（按其 README 逐条核对过）

| dev-sidecar 能力 | 它的实现方式 | 我们的对应 |
|---|---|---|
| **dns 优选**（头号能力） | 自建 DNS 拿 IP 列表 + 测速，**任意域名都能加**；README 明说"被 \*\*\* 封杀的无效" | 已有：DoH + 多候选测速；`HostResolver.resolve` / `resolveQuick` |
| GitHub 直连加速 | README："通过修改 sni 实现"，且**默认模式需安装证书**（即 MITM，由它自己发起上游 TLS） | 我们用"只换 IP、不改字节"，效果等价且不需要证书/MITM |
| release / source / zip / clone 加速 | 镜像 + 油猴脚本改 URL | 未做（见 5.2 / 5.5） |
| Stack Overflow 加速 | 把 `ajax.google.com` 代理到加速 CDN | 可用 `PROXY` 规则表达，尚未配置 |
| 拦截 / 成功模拟 | MITM 之后改响应 | 不做（本项目无 MITM） |
| 系统代理 | Windows 系统设置 | Android 用 `ProxyController`（仅本 App 的 WebView） |

### 3.9 dev-sidecar 为什么能上 Google（已刨到根，2026-11-01）

**先纠正上一版结论：之前写的"SNI 阻断 / 只能中转"是错的。** 真实机制是从 dev-sidecar 源码与日志里挖出来的。

#### dev-sidecar 的实际做法（`node_modules/@blue-frontier/mitmproxy/src/lib/proxy/middleware/overwall.js` + 运行日志）

```
sni intercept: sni replace servername: www.google.com.hk ➜ www.google.cn, unVerifySsl
发起代理请求: GET ➜ https://www.google.com.hk , sni: www.google.cn
use alive ip from dns '预设IP': 142.251.189.206   → 200
```

即 **MITM + SNI 伪装 + unVerifySsl + 预设 IP** 四件套：
1. 用自己的 CA 终结浏览器 TLS（所以它**必须让用户装证书**，README 也写了）；
2. 上游 TLS 的 SNI 换成没被封的 `www.google.cn`；
3. 不校验上游证书（因为证书本来就不匹配）；
4. 连 dev-sidecar 维护的"预设 IP"（`preSetIpList`，`142.251.189.206` 标注"自动地区官方"）。

`overwall` 那条 `ow-prod.docmirror.top` 中转在本机不可达，也没被使用——**这里的 Google 不是靠中转**。

#### 为什么我们的"纯字节转发"做不到

1. 这台网络的 DNS 对 `www.google.com.hk` 投毒：`31.13.68.169`（Facebook 段）、`2001::1`（保留地址）。
2. 换干净 IP（`142.251.189.206`）后 TCP 通、TLS 也能握手，但 Google 回的是**诱饵证书**：
   `subject=CN=invalid.invalid, OU=This cert should never been seen. Contact security@google.com.`
   → WebView 报 `net_error -202`（`ERR_CERT_AUTHORITY_INVALID`，真机日志已确认）。
3. 换 `142.250.4.94` / `172.217.163.36` / `www.google.com` / `www.youtube.com` → 直接 RST 或超时。

**所以核心障碍不是 DNS、不是 SNI、不是 IP，而是"拿不到能让浏览器接受的证书"。**
纯转发必然把证书原样交给浏览器，因此绕不过去。

#### 三条可行路线（都需要产品决策，尚未实现）

| 路线 | 原理 | 代价 |
|---|---|---|
| **A. App 内自建 MITM** | 复刻 dev-sidecar：自己签发证书 + SNI 伪装 + 不校验上游 | 最大工作量；CA 只被本 App 信任（APK 内置 `res/raw` + `network-security-config` 的 `trust-anchors`，**无需装系统证书、无需 root**，Android 8+ WebView 会读该配置） |
| **B. WebView 拦截层代取** | `shouldInterceptRequest` 里用自建客户端带 SNI 伪装取回字节再交给 WebView | 不碰 TLS、不用证书；但拿不到 POST body、不支持 WebSocket，只适合 GET 类资源 |
| **C. 上游中转** | 用户自备 HTTP CONNECT / SOCKS5 节点 | 改动最小、最安全；但用户得有节点 |

`BuiltinRules.GOOGLE_DOMAINS` 保持**默认不启用**，`IpSeed` 里保留了 Google 预设 IP 供上述方案复用。

---

## 4. 已确认的技术结论（别重复踩）

1. **不能在链路上改写 ClientHello 的 SNI。**
   SNI 参与 TLS 握手转录（transcript）。中间人改字节 → 客户端与服务端 transcript 不一致 → TLS 1.3 在 Finished 校验时报 `tag mismatch`（TLS 1.2 同样过不了 Finished 校验）。
   一句话版本：双方各自把"看到的所有握手字节"做一次指纹，最后的 Finished 就是核对这个指纹；中间人改了其中一句话，两份指纹就对不上了。**和"改得等长"无关**，是哈希对不上。
   已用真实 JSSE 服务端复现（`TlsThroughProxyTest.链路上改写 SNI 会导致 TLS 握手失败`）。
   **加速 = 连更快的 IP，SNI 与证书保持原域名**，链路必须是纯字节转发。
   `TlsClientHello.rewriteSni` 保留只为验证解析逻辑与将来"自己发起 TLS"的场景（如自建下载器），不得用于 CONNECT 透传。
   补充：dev-sidecar 需要改 TLS 内容的场景靠的是 MITM + 装根证书（重新发一次 TLS），不是"原地改字节"——那正是本项目明确排除的东西。
2. `Rule.PROXY`（改连别的 host）只有在目标能出示**原域名证书**时才可用，否则 WebView 直接证书报错。
3. JSSE 及部分客户端不会为单标签主机名（`localhost`）或 IP 字面量发送 SNI，写测试要用带点域名。
4. 普通站点不要做**测速**：每个新域名多 1.5–3s 首包延迟。但**要**用自建 DNS 解析一次
   （`HostResolver.resolveQuick`，不测速、只取第一个 IP）——系统 DNS 坏掉的网络（校园网、
   企业网、被劫持的宽带）里，否则普通网页根本打不开。SNI 不变，TLS 不受影响。
5. `ThreadPoolExecutor(corePoolSize = 0, 无界队列)` **永远只创建 1 个线程**（经典坑）。
   浏览器的多条 keep-alive 隧道会串行排队，表现为"页面永远加载不完、子资源 0 字节"。
   转发器已改成 core = max（64）并 `allowCoreThreadTimeOut(true)`。
6. targetSdk 35 强制 edge-to-edge：不自己 `setOnApplyWindowInsetsListener` 让出系统栏，
   工具栏会被状态栏压住（点地址栏其实点到系统状态栏）。
7. WebView 会为子资源发 **preconnect**（连上不发数据就关），日志里出现 `上行 0B 下行 0B`
   属正常现象，不要当 bug 追。

---

## 5. 尚未开始（下一步，按价值排序）

### 5.1 真机验证（已完成，见 3.7）

已在荣耀 PPG-AN00（Android 16）上跑通：GitHub 首页完整渲染、请求/加速计数正常、隧道 TLS 1.3 正常。
后续换机型时重点看：`dsha-accel` 里是否出现 `req <host>:443 -> ACCELERATE`、状态栏"请求/加速"是否同时增长。

### 5.2 自己的下载器（release 加速的关键）

当前 `setDownloadListener` 仍用系统 `DownloadManager`，**不走本地代理**，GitHub release 资源拿不到加速：

- `netcore`：`DownloadTask`（用 `HostResolver` 选 IP、Range 断点续传、进度回调、写 `Download/`）
- `app`：下载列表页（RecyclerView）、通知栏进度、完成后打开

### 5.3 多标签

`WebView` 池 + 标签切换列表；注意内存与 `saveState/restoreState`。

### 5.4 书签 / 历史

原生 SQLite 两张表（`bookmark`、`history`）+ RecyclerView，不要引入 Room。

### 5.5 页面级加速

`evaluateJavascript` 注入：把 GitHub 页面里的 raw/release 链接改写成镜像域名。这是**不改 TLS、却能绕过资源域限速**的那一层。

### 5.6 体验补齐

- 主页/新标签页、长按链接菜单、分享、网页查找
- 加速面板：当前优选 IP 列表、测速结果、命中规则数、手动重新测速
- 用户自定义规则（导入/导出 JSON，语义对齐 dev-sidecar）

---

## 6. 约束与红线

1. **体积**：release 目标 ≤ 6MB（当前 558KB，余量充足，但仍别乱加依赖）。
2. **构建工具链**：Gradle 8.14 + AGP 8.7.3 + JDK 21 标准版（`.jdk/`）。
3. **不引入 MITM/证书/VpnService 相关代码**。
4. **链路上不改写 TLS 字节**（见第 4 节第 1 条）。
5. 保持 `minSdk 24`；`ProxyController` 不支持时必须有降级路径。
6. 交互语言：中文；注释精简，关键处只写"为什么"。

---

## 7. 可直接粘给下一个 AI 的开场话术

> 这是一个 Android 极简浏览器项目（Kotlin，路径 `d:/File/User/code/dsha-sidecar`），把 dev-sidecar 的 GitHub 加速能力做进浏览器：WebView 通过 `ProxyController` 指向本地转发器（127.0.0.1:8118），转发器做自建 DNS + 多 IP 并发测速，CONNECT 时连优选 IP，**但绝不改写 TLS 字节**（改写 SNI 会破坏 TLS 1.3 Finished 校验，已有回归测试证明）。构建已通（debug 3.3MB / release 558KB），netcore 的 DNS、规则、转发器三个模块和 28 个单测都完成了，app 层的加速开关与状态栏也接好了。当前环境没有真机/模拟器，运行时行为未验证。请先按 `HANDOFF.md` 5.1 做真机验证，再做 5.2 自建下载器（DownloadManager 不走代理，release 资源拿不到加速）。环境：Windows + Git Bash，JDK 用工作区 `.jdk/jdk-21.0.2`（已写进 gradle.properties），ANDROID_HOME=D:\App\Dev\Android，Gradle 8.14（见 build.sh），AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 24。
