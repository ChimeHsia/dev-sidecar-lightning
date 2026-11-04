# 工作区整合记录（2026-11-04）

基线主线：`ae39b8f3b2602bf2782c2453a5819fa392f9ff3f`。
检查了主工作区和 13 个附加 Git worktree。整合在独立工作区执行，验证后快进本地 `main`。
主工作区原有 `.gitignore`、`AGENTS.md` 修改保持未提交状态。

## 实际启用的改动

- 合入 `worktree-swift-beaming-shell` 尚未进入主线的 Material Symbols 图标、
  设置列表图标、菜单和对话框细节。保留主线的宽色域设置、标签生命周期和现有主题回归保护。
- 浏览器主题由 `browser_themes.xml` 统一定义；清除旧主题重复定义，
  并将标签生命周期测试切换到实际运行使用的 Material 浏览器主题。
- 从 `native-android-packaging` 工作目录补入 Node 代理恢复补丁：
  DNS 错误/迟到回调保护、客户端取消、超时清理、失败只计数一次、
  已发送 POST 不重放、流式响应不追加错误页。
- 重新生成本机 Node project zip，浏览器 payload 标记从 10 升至 11。
  生成的 zip、Node 二进制和 CA 密钥不纳入提交。
- 从 `native-core-review` 草稿提取 required-ECH 策略保护，
  将运行时路由要求传到 TLS policy。普通连接器和组合连接器都拒绝降级，补入两项回归测试。

## 历史与草稿处理

`native-core-ech-v2`、DNS 编排和 Android 打包提交在主线上已有等价补丁。
原生 runtime 冻结版本及其并发清理修复也已被主线后续实现覆盖。
`native-ech-android` 的浏览器整合/主题修复已由 `05d3ae3` 及后续提交接入；
新合入 UI 分支补足其中没有覆盖的设置和图标。

因此对已整合分支使用保留当前树的历史归并，避免旧树覆盖更晚的响应体处理、
取消/重试、CONNECT 字节保留、Android 正则和应用 ID 修复。
这类 merge 只连接历史，不表示将旧树逐文件覆盖到当前源码。

所有附加工作区的未提交源码均另存为 Git 快照，使用独立 index，
没有改动来源工作区的文件或暂存区。快照提交通过归档 merge 纳入可达历史，
不将其中所有旧实现重新启用。下面的快照既包含被选入主线的修复，也包含保留的草稿。

| 来源工作区 | 快照提交 | 文件数 |
|---|---|---:|
| `native-android-packaging` | `31b1690e73ed` | 4 |
| `native-core-review` | `d6a6286d4b13` | 6 |
| `native-dns-orchestration` | `1817b6562a40` | 47 |
| `native-ech-android` | `68f9136f7b7f` | 55 |
| `native-ech-conscrypt` | `7acfc25e35df` | 13 |
| `native-ech-hpke` | `1a94874c2bfe` | 12 |
| `native-proxy-engine` | `378fa8a9fb01` | 18 |
| `native-rule-planner` | `d8b4d3b81943` | 55 |
| `runtime-wave2-review` | `33edc52c1e79` | 47 |
| `ui-ba03219-device` | `e4e44c0726df` | 1 |

完整来源 HEAD、原分支和快照引用见
[机器可读清单](WORKTREE-CONSOLIDATION-2026-11-04.json)。
引用前缀为 `codex/archive-consolidation-20261104/`，可用例如
`git show <快照提交>:<相对文件路径>` 检查或恢复草稿。

以下内容保留在快照中，未直接启用：

- `NativeRuntimeEchBridge` 异步预检草稿：完整生命周期、所有权和线程绑定仍需独立验证。
- 早期 `android-ech-probe` 应用、诊断脚本和历史证据；现主线保留更新的
  `android-native-check` 与对应设备记录。快照可追溯早期诊断来源。
- 各工作区较旧的 HTTP reader、响应实体、重试调度和正则实现；
  主线已有修复及回归测试。机器绝对路径和早期依赖配置也不覆盖主线。
- UI 设备工作区较旧的 `DshaAccel` 启动实现。

`.devtest/` 是本地截图/测试输出，保留在来源工作区和本机备份中，不作为产品源码合入。
原始工作区保持原样，未删除或重置。

## 验证

- Python 打包测试：7 项通过。
- 真实更新后 Node 载荷：12 个测试文件、691 项通过，零失败、零跳过。
- Netcore：970 项，968 通过、2 项 live 测试按配置跳过。
  使用 JDK 21、官方 Conscrypt 2.8.0，以及从当前 fixture 源码编译的独立 Go ECH/HTTP peer。
- Browser Release 构建通过；完整 JVM 测试 215 项全部通过，零失败、零跳过。

使用临时 init script 限制测试堆和 worker，并将独立 ECH peer 的路径与证据输出传给
测试进程；机器路径未写入项目构建配置。详细构建日志留在本机临时目录。
没有进行本轮真机安装或页面验收；本地通过不能替代设备验证。
