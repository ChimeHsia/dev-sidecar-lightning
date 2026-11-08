# 工作交接

## 端口冲突、下载与工作区核查（2026-11-08）

- 用户询问端口自增、能否回收工作区、Node 是否完全移除、下载是否闪退。
- 已实现 31181–31200 绑定冲突递增；WebView、隐身 Binder 和远程配置使用实际端口；规则热更新保持监听。
- 下载协程增加异常提示并保留取消语义；HEAD 失败回退、长度安全解析、响应关闭、损坏 data 图片拒绝与 bitmap 释放。
- 原生 981 项：979 通过、2 跳过、0 失败；浏览器 223 项全通过。最终 Release/lint 通过，APK 22,216,681 字节且无 Node，证据 `port/.run/port-download-fix/`；本轮 versionCode 104。
- 14 个附加 worktree HEAD 全部已进入 main，但 11 个仍有未提交/未跟踪内容，3 个干净；未删除任何工作区。
- 主目录含忽略的 CA 私钥/本机配置，不能只凭 GitHub 已推送就丢弃。测试 Go 工具链也来自旧 worktree。
- 产品 APK 无 Node；旧参考壳/port 工具和 Node 基线仍在仓库，设备旧 Node 目录仅迁移配置后保留。
- 系统 DownloadManager 文件下载不随 WebView 的代理覆盖设置走本地核心；站点下载可能失败，尚无设备验证。


## 当前任务：Node 到 Android Kotlin 完整接线（2026-11-08）

- 用户要求：先提交并推 GitHub，再完成原生 Kotlin 迁移；实现结束后集中测试，允许成熟库。
- 已完成：现有全部改动提交为 `7be44cb`，已推送 `origin/main`。
- 当前目标：接通原生重试/备用目标、脚本响应、DNS→ECH、Android 生命周期与纯原生打包。
- 已有基础：`netcore` 原生 HTTP/CONNECT MITM、DNS、证书、规则、脚本与调度组件；
  `android-native-check` 已有 Android 依赖打包方案，Lightning 尚未接入。
- 待办：实现完整链路、保存无机器路径的默认数据、补回归、最后统一 JVM/Android/APK 检查。
- 注意：不能通过关闭默认 ECH/脚本/backup 绕过迁移；Node 归档和 ELF 不得继续打进产品。
- `@RTK.md` 在当前目录与父目录均未找到，继续遵守已提供的 AGENTS.md。

### 实现阶段交接

- 已改：`NativeProxyServer` 增加响应提交前重试与显式授权的小请求体缓冲；
  runtime 接通候选排除、backup 重组、HTML 注入、Brotli、自动 ECH/NAT64、后台 TCP 观测。
- Android：新增 `lightning-browser/native-core` 模块；`DshaAccel` 改用原生 runtime，
  增加绑定服务及配置继承；默认规则数据由 `port/build-native-data.py` 导出。
- 打包：排除旧 Node jniLibs/两份 zip；新增 APK 检查脚本；versionCode 103。
- 已补：真实候选切换、显式 POST 重放、备用目标、脚本注入、默认配置加载、
  Android 配置继承回归；历史拒绝测试按新行为调整。
- 当前：原生 JVM 979 项，977 通过、2 跳过、0 失败；浏览器 JVM 220 项通过；APK 初检 21.2 MiB，Node 0 项。
- 远程配置已补运行时 epoch 热切换和在途回归，额外 DNS→ECH→Go HTTP 与远程配置→本地 MITM 贯通通过。
- 隐身就绪改为 Binder 查询主进程服务归属，重建及两项归属回归通过；lint 本机 SDK 路径已转义，lintRelease 通过（0 error、5 warnings）。
- 最终 Android Release、220 项浏览器 JVM、native-core lintRelease 同轮 BUILD SUCCESSFUL；Python 7 项、APK 复核和 diff 检查通过。
- 后续：提交迁移；adb 无设备，真机 D01–D08 待验收。
- 当前证据及边界见 `docs/NATIVE-KOTLIN-MIGRATION-2026-11-08.md`。

## 已完成历史任务：海外网站连接超时（2026-11-05）

- 用户要求分析并修复 GitHub 等站点连接超时，最初 Node.js 版本可用。
- 当前浏览器仍使用 Node payload 11，Kotlin 原生 runtime 未接管浏览器。
- 在独立解压的实际 payload 上复现 GitHub 重试后失败；同轮 API 返回 200。
- 分别探测三个预设 IP，观察到一个返回 200、两个超时；稍后网络状态发生变化。
- 已修复默认只重试一次、测速可重新选中失败 IP 的恢复缺口：
  请求内排除失败地址，按候选数量进行有界切换，默认最多重试 3 次。
- 诊断时曾先于适配器加载测速模块，诱发旧 hostname map 未绑定异常；
  修正诊断加载顺序后该异常消失，不能作为真实启动缺陷结论。
- 复现目录：忽略的 `port/.run/timeout-fix/`；不包含提交材料。
- Node 702 项、Python 7 项、浏览器 JVM 215 项通过，Release 构建通过。
- 两份实际 zip 已更新；浏览器解压标记升级为 12。
- 本机 GitHub/Trending/API/Raw/资产及 Google HK 为 200；
  受控前两个地址失败后真实 GitHub 首页第三候选返回 200，DS-Retry=2。
- 已在 Android 16 真机移动网络上抽样：GitHub 首页和 Trending 首次
  42.22/43.95 秒、各重试一次后返回 200，复测 1.99/3.17 秒；
  其他四个目标均为 200。长等待原因和 WebView 渲染仍待确认；代码未提交。
- 详细证据和已改文件见 `docs/GITHUB-TIMEOUT-FIX-2026-11-05.md`。
- 原有 `.gitignore`、`AGENTS.md` 修改继续保留。

## 目标

完成各来源 worktree 向本地 `main` 的整合，保留可恢复的草稿并验证结果。

## 已完成

- 产品代码已在 `7c59126` 及之前的整合提交进入 `main`。
- 重新核对 13 个来源 worktree：全部 HEAD 已进入 `main` 的可达历史。
- 10 个草稿快照全部可从 `main` 恢复；258 项源码改动与快照一致，无新增遗漏。
- `.devtest/` 的 7 个本地测试产物保留在来源目录，不作为产品源码提交。
- 修正 `LIGHTNING.md` 中浏览器主题文件路径，补充合仓复核记录。

## 本轮已改文件

- `LIGHTNING.md`：指向实际使用的 `browser_themes.xml`。
- `docs/WORKTREE-CONSOLIDATION-2026-11-04.md`：补充 11 月 5 日复核结果。
- `HANDOFF.md`：本交接记录。

## 验证与待办

- Release 构建及浏览器 JVM 任务成功；浏览器测试使用 Gradle 缓存结果，215 项，零失败、零跳过。
- Netcore 在主工作区重新执行通过：970 项，968 通过、2 项 live 测试按配置跳过，零失败。
- 产品源码没有待合入项。原始 worktree 保留，未删除或重置。
- 主工作区 `.gitignore`、`AGENTS.md` 原有修改保持未提交。
- 未推送远端，未做本轮设备验收。

## 注意

- 各旧分支含早期实现；不要再把旧树整体覆盖到当前主线。
- 归档 merge 连接历史，草稿内容需要从清单所列快照恢复。
- 快照清单：`docs/WORKTREE-CONSOLIDATION-2026-11-04.json`。
- `@RTK.md` 在当前仓库未找到；已遵循 `AGENTS.md` 的输出限制。
