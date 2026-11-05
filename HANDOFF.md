# Worktree 合仓交接（2026-11-05）

## 当前任务：海外网站连接超时（2026-11-05）

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
