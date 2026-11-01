# Lightning + sidecar 真机回归基线

主产物是 `lightning-browser/` 的 **LightningPlus release / arm64-v8a**，包名 `com.dsha.browser`，链路为 WebView → `127.0.0.1:31181` → 内嵌 Node/dev-sidecar。根目录 `app/`、`netcore/` 是早期壳和纯 Kotlin 参考实现；其测试通过不能证明当前 Node payload、Lightning 代理或页面正常。

本文依据当前 `DshaAccel.kt`、`BrowserApp.kt`、清单和 `port/node/main.js` 制定检查步骤。当前已运行本地测试和真机只读采集，尚未重新执行下列页面与生命周期用例；`README.md` / `LIGHTNING.md` 记录的历史成功不等于当前 APK 已复测。

## 状态与判定

| 范围 | 实际状态（2026-11-01） | 待补证据 |
|---|---|---|
| 历史真机 | 原文记录荣耀 PPG-AN00 / Android 16：GitHub Trending 完整渲染、核心收到 207 个请求；README 记录 Google HK 返回 200 | 对当前 commit、APK、payload 重跑；HTTP 200 本身不证明 Google 页面完整渲染 |
| Node 本地测试 | 17/17 通过 | 假核心契约测试不证明真机监听 |
| Browser 上游 JVM 测试 | 最终重跑 58/58 通过；测试用 `PreferenceDataStoreRule` 使用 OkioStorage 真实序列化 | 单元测试不代替真机 WebView 验证 |
| Netcore | Gradle 45 项：43 通过，2 项有意跳过的 live 测试；包含 17 个新增用例 | live 未验证；不能代替主产物验证 |
| 只读采集器 | 荣耀 PPG-AN00 / Android 16 真机采集成功；当前用户 0 安装 browser 102 与旧 sidecar 1，未列出 EinkBro；快照未观察到 31181 监听 | `ApplicationVerdict=not-assessed`；此次没有启动浏览器，不能据此判断主链路是否工作 |
| 下列设备用例 | 全部待测 | 每项独立填写结果与证据 |

用例结果只写：**通过 / 失败 / 受阻 / 待测**。采集成功只表示读到了证据，不是应用通过。页面用例通过至少要求：对应时段的实际监听、归属可核对的核心请求证据、人工可见页面内容；缺项写受阻或待测，不拿进程存在、代理回调、就绪日志、HTTP 状态码替代。

**就绪标记不是健康检查。** 当前 `port/node/main.js` 在 `DevSidecar.api.startup` resolve 后打印“就绪”，但被替换的 `cp.fork` 内异步 `server.start(...).catch(...)` 可能已记录 `mitmproxy 启动失败`。即使有就绪日志，也要检查该错误、`startup 失败`、`EADDRINUSE`、Node 退出，并核对监听和请求/页面。被其它包占住的 31181 也不是本包核心启动成功。

## 准备与证据

1. 使用 JDK 21、Android SDK、支持 arm64 的实机；记录 Android / WebView 版本、网络环境、设备别名、commit、APK SHA-256、versionCode 和本地 payload 版本/来源。不要将完整序列号、账号、请求令牌、CA 私钥写入仓库。
2. 依 `README.md` 生成并同步未跟踪 payload，再在 `lightning-browser/` 构建 `assembleLightningPlusRelease`。release 当前禁用混淆/资源压缩且使用 debug 签名，仅供本地；不要用 debug 包的 LeakCanary 启动行为代替 release 基线。
3. 检查安装的 `com.dsha.browser`、旧壳 `com.dsha.sidecar`、对照包 `info.plateaukao.einkbro` 和其它 `com.dsha.*`。Lite 的 `acr.browser.barebones` 也可能存在。安装并不代表运行或占端口；先只读采集，再由测试者手动停止确定竞争的包。首次安装/清数据须用专用测试环境，避免丢失日常数据。
4. 每个步骤前后分别采集，保留电脑时间、动作时间、PID、监听、必要请求摘要以及人工截图/观察。截图显示地址栏、实际正文、错误提示；测试使用无账号页面。证据默认保存在临时目录，先移至本地持久存储，再脱敏摘要入表，不把原始设备日志或密钥提交。

只读采集（从仓库根目录执行；占位序列号只在本机替换）：

```powershell
.\scripts\collect-device-baseline.ps1 -Serial '<local-device-id>'
# 仅有一台 online/authorized 设备时可省略 -Serial。
# 多台设备必须显式选择；offline/unauthorized 不会被选中。
.\scripts\collect-device-baseline.ps1 -Serial '<local-device-id>' -OutputDirectory "$env:TEMP\lightning-case01-before"
```

输出 `evidence.json`：设备基本信息、当前 Android 用户的已安装测试包及 versionCode、主/隐身与可见 `libnodebin.so` 的 PID/PPID/UID、31181 监听、最近 200 条缓冲记录中 `dsha-accel` 的有限事件分类。包列表明确指定当前用户，避免其它用户/工作资料的权限错误影响采集；不检查其它用户的安装情况。日志只留时间/PID/类别，不保留原始文本和 URL；序列号不入报告。完整请求与渲染关联须人工补证，WebView 版本从设备设置记录。

脚本只调用读取型 adb 命令，不打开页面、不停止/卸载/清数据、不清日志、不转发端口、不读取私有目录/证书；不抓全量 logcat、bugreport 或大范围 dumpsys。首次调用 adb 可能自动启动电脑端 adb server。输出目录必须尚不存在，默认使用电脑临时目录。

退出码：`0` = 各采集命令成功，**应用仍未判定**；`1` = 命令非零或输出无法解析，报告标 `incomplete`；`2` = 设备选择、工具或输出等致命错误，报告可能未生成。`ss`/日志权限不足不能算通过；日志为空或监听/进程缺失也不自动意味着失败或通过。事件可能来自旧启动/其它包；用动作时间与 PID 核对，Node 归属再对 PPID/UID。顺序快照不是原子抓取。

## 手工设备用例（按顺序）

以下是人工执行步骤，采集器不执行它们。每项先写初始状态，末尾填写结果；当前均为 **待测**。

| 编号 | 操作 | 判定与关键证据 |
|---|---|---|
| D01 首次启动 payload | 在专用干净安装环境启动 release，记录启动到可用耗时，然后访问下列站点 | 两个 zip 解压事件、Node 启动、31181、代理重设与请求/页面；无缺 payload、启动错误、证书错误。未具备干净环境则受阻，不能把普通重开当首次启动 |
| D02 温启动 | 正常访问后按 Home，再从桌面返回，刷新同一页面 | 主进程/Node PID 是否保持、监听是否连续、刷新内容是否可见。恢复成功不等于重新启动核心 |
| D03 冷启动/重启复用 | 记录后由测试者在系统设置强行停止本包，确认清理，再启动；另测设备重启后首次进入 | 记录新 PID、监听与恢复标签的首次请求/渲染；相同 payload 版本不应重复解压两个 zip。就绪后 clear→set 代理是否恢复；若需手工刷新才能正常，单独记录首次失败 |
| D04 GitHub | 输入 `https://github.com/trending`，等待完成并滚动 | 地址/标题、仓库列表、star 按钮等内容截图；对应请求时间/数量与错误摘要。旧页面缓存或单个 200 不能单独判通过 |
| D05 Google HK | 输入 `https://www.google.com.hk`，用无敏感词搜索并打开结果 | 首页及结果页可见、最终 URL/重定向、对应请求；若遇验证码/网络限制写受阻。`www.google.com` → google.cn → 404 的历史地域重定向应独立记录，不替代 HK 用例 |
| D06 多标签/隐身 | 普通模式开两个标签，切换/刷新；进入隐身开页面，再返回普通模式；另测主进程未运行时直接进入隐身（有入口时） | 普通主进程与 `com.dsha.browser:incognito` 各自代理、Node PID/数量/归属；隐身应只挂代理而不重复起 Node。主进程未运行的隐身入口可能没有核心，须记录实际结果，不能预设已支持 |
| D07 端口冲突 | 专用环境手动启动已知竞争包占住 31181，再启动本包；保存错误后手动停止竞争包，再冷启本包 | 先记录占用包/PID/端点，检查 `EADDRINUSE`/`mitmproxy 启动失败`；不得将“就绪”或借用其它包代理的页面成功判成本包通过。恢复后重新证明本包监听/请求/页面。竞争包不可用则受阻 |
| D08 退出与清理 | 分别关闭标签/划掉最近任务、只留隐身、系统强行停止本包；每步前后采集 | 记录主/隐身/Node 进程和监听是否残留；强停后预期本包相关进程与自身监听释放。监听仍在时先排除其它包。划掉任务不预设等于强停；留残进程或恢复失败如实列限制 |

当前生命周期边界（代码检查所得，待真机确认）：

- `started` 在 prepare 前已置位；启动异常/缺二进制/Node 退出不复位、不自动重启，`coreReady` 也不会在退出时清除。移除竞争包后同进程重试不可靠，需完整冷启验证。
- `stop()` 只销毁保存的子进程、清空引用，不重置上述标志，也没有现有生命周期调用点；`BrowserApp.onTerminate()` / Activity 销毁没有核心清理逻辑。不能宣称已有可靠退出、子进程回收或后台保活机制。
- 代理是进程级设置，隐身依赖主进程的核心；Android 9 以下当前 `BrowserApp` 不识别隐身进程，可能重复启动。Android 16 的历史结果不能外推到所有支持版本。
- payload 解压依赖版本 `2` 的标记文件，替换 zip 必须递增版本；仅重开不会检验新内容。CA 文件会复制，禁止为验证去导出密钥。代理不支持、日志缺失与端口监听成功均需各自记录。

每次执行复制一行记录，报告中设备只用别名：

| 时间/设备别名 | commit / APK SHA-256 / payload 来源与版本 | 用例/初始状态/动作 | 实际结果与状态 | 证据路径（本地、脱敏）/缺口 |
|---|---|---|---|---|
| 待填 | 待填 | D01–D08 分项填写 | 待测 | 截图、前后 JSON、人工请求摘要 |

## 本地测试与小步迁移

统一入口 `scripts/test-baseline.ps1` 支持 `-Suite All|Netcore|Node|Browser`、`-Offline`、`-JavaHome`、`-GradleBin`。例如 `.\scripts\test-baseline.ps1 -Suite All -Offline`；离线缺缓存是受阻，不是测试通过。Browser 使用 `--no-configuration-cache` 和单独引用的 `'-Dorg.gradle.unsafe.isolated-projects=false'`（隔离项目要求配置缓存），这些仅是 runner 局部参数，不修改构建配置。执行结果按实际报告补填，不把 runner 成功当成 D01–D08 完成。

Netcore 既有健壮性缺陷（生产代码保持不变，当前通过数不代表已修复或有通过的回归保护）：DNS A 记录声明 RDATA 长度 4、实际仅 1 字节，解析可先通过、随后读取 `addresses` 抛异常；45 字节畸形 TLS record、`sessionIdLength=255` 可使 `sniOf` 抛异常。这是参考 Kotlin 解析层的已知缺口，不据此推断当前 Node 链路同样有缺陷；后续补边界处理与独立回归。

迁移按可独立测试的小模块推进，每步保留 Node 对照链路并重跑受影响的设备用例：

1. 先固定 payload 版本/文件清单/解压决策等纯逻辑，用临时目录覆盖缺失、损坏、升级；Android 文件入口留薄封装。
2. 提取启动状态机和“监听就绪”契约，用假进程/假监听覆盖延迟、假就绪、异步失败、退出与重试；不让日志关键词充当成功信号。
3. 分离代理配置、clear→set 顺序和普通/隐身进程角色判定；用假平台接口测顺序/幂等性，再以 D02/D03/D06 验证 WebView 行为。
4. 将 Node 规则中可纯化部分逐项抽出：配置归一化/null 规则过滤、域名匹配、DNS/IP 选择策略。固定输入/输出与 Node 做差分，再逐步考虑网络 I/O 适配。
5. 最后评估 TLS/MITM、证书与生命周期替换；先建立握手、错误和清理契约，再做小流量对照。参考 netcore 测试可复用思路，不能据此宣称覆盖现用 Node 的 MITM/SNI/ECH 能力，更不能直接整核替换。
