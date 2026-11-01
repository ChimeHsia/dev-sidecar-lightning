# 第二波纯逻辑迁移

四个模块已完成 JVM、Kotlin/JS 和原版 Node 对照验证，并通过
`port/node/kotlin-wave2.js` 接入 Node 负载。当前浏览器负载标记为 9。
这是共享 Kotlin 源码编译为 JS 后在 Node 内执行；Android/JVM HostResolver
及完整代理核心没有随之替换。最初纯模块交付的数据保留在下文。

## 运行时接线

| 模块 | 实际调用点 | 保留的上游职责 |
|---|---|---|
| HostnameAllMatcher | `util.match.matchHostnameAll`，首次查询惰性编译；恢复稀疏数组及捕获组元数据 | 单结果匹配仍使用第一波适配 |
| PathRuleMatcher | `util.match.isMatched`，包括路径与 exclusions 查询 | `createIntercepts` 的实现判定、order/priority、SNI/ECH 和缓存启用条件 |
| DynamicChoice | 原类 prototype，覆盖 ChoiceCache 的闭包构造和 DNS IpCache 子类；同步公开计数状态 | LRU、时间、DNS、测速和网络反馈入口 |
| RequestTransform | 直接请求的协议、Host、端口、路径、头；打包时插入严格校验的字段入口 | Agent/TLS、套接字、外部代理分支及后续拦截器重写 |

入口在核心启动前安装全部适配器。缺少编译导出、打包钩子或不支持的输入会明确失败，
不静默回退原 JS 业务逻辑。每个模块首次实际使用记录一个不含请求数据的
`[android] Kotlin runtime invoked: ...` 事件；它证明派发，不表示网络请求成功。

## 交付范围

| 模块 | 已实现 | 边界说明 |
|---|---|---|
| `HostnameAllMatcher` | 多规则按顺序合并、根域/精确覆盖、最终删除、数组位置、捕获组与命名元数据 | JSON 对象配置；不保证任意 JavaScript 正则都与 JVM 等价 |
| `PathRuleMatcher` | 原始路径与查询串匹配、排除条件、简单拦截器描述符的 order/priority 选择 | 不执行拦截器；SNI、缓存等专用启用条件仍由调用方判断 |
| `DynamicChoice` | 候选顺序、首次失败切换、历史反馈、成功率排序、重置与状态快照 | 没有 DNS、测速、存活池、时钟或缓存；空队列不复刻 JS 的虚假 undefined 计数 |
| `RequestTransform` | Host/协议/端口/路径构造、转义保留、伪头与代理头处理、Connection token | 仅直接请求的纯字段；未实现 proxy 规则重写、响应变换、TLS 或真实连接 |

代码分别位于 `netcore/src/main/kotlin/com/dsha/sidecar/net/{rules,dns,http}/`。
每个模块的 API、来源哈希、原版行为、严格校验差异和数据再生成方法，见
[域名合并](migration-wave2/hostname-all.md)、
[路径规则](migration-wave2/path-rules.md)、
[候选选择](migration-wave2/dynamic-choice.md)、
[请求变换](migration-wave2/request-transform.md)。

## 测试结果

### 2026-11-02 隔离接线复查

复查目录 `runtime-wave2-review` 基于 `754ff42`，复制第二波源码及已有启动/TLS
修复，不含并发开发的标签页或样式变更。负载候选以已验收的 payload 8 项目 zip
为输入，嵌入当前第二波编译库；还没有构建、安装或验收新的浏览器 APK。

- Python 打包回归 6 项通过；重复更新不产生重复条目，脚本与归档内容一致。
- Node、原版对照、真实核心、HTTP/TLS 和拒绝边界最初 679 项通过；
  HTTP/2 元数据修复后为 681 项通过，无失败或跳过。
- 实际项目 zip 包含 HTTP 和 upgrade 的 `DSHA_INVALID_REQUEST` 处理；
  真实监听器返回受控 400，正常后续请求仍可用；测试禁止临时补丁掩盖漏打包。
- 两个打包入口共用 `patch_core_file`，在上游源码变化时明确失败。
- 样式构建释放构建槽后，已在本隔离目录重新执行 JVM 测试及 Kotlin/JS
  production library 构建：146 项中 144 通过、2 项外网冒烟按设计跳过；
  使用该构建产物重新打包并复跑上述 679 项检查。这里没有运行浏览器构建或设备测试。

### HTTP/2 与回退复查

候选 9 真机浏览器打开 GitHub 显示 Bad Request，但同目标 Python/HTTP1
可得到 200。本地真实 `node:http2` server/client 复现请求适配器拒绝
`Http2ServerRequest.headers` 自带的 `Symbol(sensitiveHeaders)`，在调用 Kotlin 前
返回固定 400。新增回归在旧适配器上失败、修复后通过；只在 HTTP2 请求中
忽略 Node 导出的这个元数据 Symbol，其他 Symbol 及 HTTP1 输入仍拒绝。
包内适配器参与完整 681 项复跑；HTTP2 校验不再仅依赖伪头 JSON 数据。

设备还发现：重装旧 APK 不会可靠回退负载，历史 `.done` 标记可能导致跳过解压。
因此候选 9 没有验收通过。后续候选使用 payload 10，并包含浏览器解压标记修复；
其 JVM/设备结果单独记录，不能沿用 payload 8 的结论。

### 2026-11-02 payload 10 验收

- 浏览器 JVM 205 项、完整 Node 运行时 681 项、Python 打包测试 6 项通过。
- Android 16 真机上 GitHub Trending 和 Google HK 页面可见，畸形请求返回
  400 后进程仍可处理正常请求；四个第二波适配器均有实际调用记录。
- 普通及无痕窗口各验证了 confirm、prompt 的部分成功或取消结果。完整弹窗、
  标签样式及图标结果见此前浏览器样式验收；销毁期间回调次数仍未真机验证。
- 实际执行 10 → 8 → 10 安装：每次版本变化均重新解压并 READY，回退期间
  未观察到第二波调用，再升级后四个适配器均有调用。未直接读取私有标记。
- GitHub 页面没有捕获到 HTTP/2 协商证据；HTTP/2 适配器由独立的本地真实
  Node HTTP/2 服务端与客户端回归验证，不能将页面显示视为协议证明。
- 构建和设备详情见 [payload 10 真机结果](DEVICE-RESULTS-PAYLOAD10-2026-11-02.md)。

完整本地运行链检查：

```powershell
.\scripts\test-node-runtime.ps1 -Project '<extracted-updated-payload>' `
    -UpstreamProject '<original-project-containing-core>'
```

不传 `-UpstreamProject` 时，原版回放明确跳过。已有数据比较仍运行，
不能把带跳过的结果写成原版回放已执行。此 runner 不安装应用、不操作手机。

### 纯模块交付时的结果

根项目 Gradle 8.14 / Kotlin 2.0.21、JDK 21；Node 使用本机 v24.15.0。

| 检查 | 结果 |
|---|---|
| `netcore` 完整 JVM 套件 | 146 项：144 通过，2 项外网冒烟按设计跳过 |
| 本轮新增 JVM 用例 | 58 项：域名 14、路径 12、候选 17、请求 15，全部通过 |
| Kotlin/JS production library | 构建成功 |
| 原版生成的数据 | 154 组：域名 37、路径 52、候选 31、请求 34 |
| Node 差分与原版回放 | 311 项通过，无失败或跳过：154 次 Kotlin 比较、154 次原版回放、3 项集成/边界检查 |
| 第一波 Kotlin 适配回归 | 19 项通过，使用包含新导出的编译产物 |

数据预期来自本机提取的 dev-sidecar/mitmproxy 2.3.1 原文件。回放只执行其中的纯逻辑，
替换日志和 Agent 构造，不启用桌面运行时或网络；预期值没有由新 Kotlin 实现生成。
JVM 和 JS 都通过只代表已声明的输入范围，不代表任意语言特性或完整代理行为等价。

## 统一入口

仓库根目录 PowerShell：

```powershell
.\scripts\test-migration-wave2.ps1 -Offline
# 有真实核心副本时，额外重放原版 Node；路径下应包含 core/node_modules：
.\scripts\test-migration-wave2.ps1 -Offline -UpstreamProject '<extracted-project>'
```

可用 `-JavaHome` / `-GradleBin` 指定 JDK 21 和 Gradle 8.14。
未传上游目录时，原版回放明确跳过，仍对照已提交的数据检查编译后的 Kotlin/JS。
脚本串行运行 JVM 测试和 JS 编译，恢复调用前环境变量，不修改系统配置。
本机原版回放日志位于被忽略的 `port/.run/migration-wave2/integrated-results.txt`。

`MigrationExports.kt` 保留 JSON 差分接口；运行时匹配与候选状态另由
`MatchRuntimeExports.kt` / `ChoiceRuntimeExports.kt` 返回原生对象。
请求适配器使用 `wave2RequestTransform` 的 JSON 边界。
统一脚本同时运行第一波适配及三组第二波运行时测试；有真实核心目录时才运行相关集成。

## 运行边界与后续验收

- 正则引擎在 JVM/JS 上存在差异；应针对实际要迁移的规则审计语法，再扩充对应数据。
- 多域名匹配的运行时导出恢复稀疏数组、groups/index/input；JSON 测试接口仍使用 null 和独立元数据。
- PathRuleMatcher 的通用描述符不等价于全部上游 `impl.is`，不能直接替换整个 `createIntercepts`。
- 动态候选已连接原版缓存和请求反馈；并发探测及存活池仍由上游承担，未迁移为 Kotlin。
- 请求变换有意拒绝上游宽松接受的畸形/非 ASCII 输入，接入前需确认产品对这些输入的处理。
- 启动/TLS 修复的 payload 8 真机记录是接入前对照，不能代替其他版本的验收。
