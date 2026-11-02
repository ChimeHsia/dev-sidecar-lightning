# Kotlin/JS 负载真机验证

此记录对应 payload 7；之后的启动/TLS 离线修复见 [修复记录](OFFLINE-FIXES-2026-11-01.md)，尚未在手机验证，不能回填为本轮设备通过。

2026-11-01，荣耀 PPG-AN00，Android 16 / API 36，Google WebView 138.0.7204.179。
包名 `com.dsha.browser`，LightningPlus release / arm64-v8a，versionCode 102。
源码为 `754ff42` 加 `DshaAccel.PAYLOAD_VERSION` 从 `6` 到 `7` 的修改。

## 结论

本次确认 Kotlin/JS 新负载在真机启动，并完成 GitHub、Google HK、导航跳转和隐身模式的页面检查。
尚不能判定全项通过：受控端口冲突时出现错误的“就绪”信号；访问过程中也观察到超时与上游无响应。
没有用先前原版核心 APK 的结果替代本次 Kotlin 负载验收。

## 包与负载身份

- 新 APK：`port/.run/kotlin-device-20261101/lightning-kotlin-v7.apk`
- APK SHA-256：`75a3a7dddbabb0a79dff977c5c66690b3f619c3752c179eb3d25f690879b279d`
- 内嵌 `nodejs-project.zip` SHA-256：`558486a7603a30c85eabdf4224d7e87f0fadf4ba419bd62857f61e28a33f3c43`
- 已核对 APK 内负载与本次隔离测试负载逐字节一致，包含 Kotlin CommonJS、适配脚本、导航规则和 NAT64 内部替换；DEX 含 `.v7.done` 解压标记。
- 手机安装后的 `base.apk` 哈希与新 APK 一致；首次启动记录两个 zip 解压、`Kotlin helpers active`、代理监听和 WebView 代理应用。
- Node 进程 PPID 对应浏览器主进程，UID 相同；正常状态只有一个 Node。运行时未逐个导出私有模块文件，身份依据为已安装包哈希、解压事件和启动日志。

原已安装 APK 保存为 `installed-before.apk`，哈希为
`76cfc069776c827637210f9c88cc80467dd310eebb7e7061921b6f53944f8d83`。
新旧 APK 签名一致，CA 证书 DER、打包的 CA/key 和证书包保持一致；使用 `adb install -r` 保留应用数据。
没有清除数据、卸载应用、修改导航规则或系统代理。

## 已执行用例

| 项目 | 实际结果 | 状态 |
|---|---|---|
| 升级后首次启动 | 新标记触发两个 zip 解压；约两秒出现 Kotlin、监听及代理回调，无启动错误 | 通过；不是干净安装测试 |
| GitHub Trending | 首次和后续冷启后的访问均显示 Trending 标题、仓库列表及 star 控件，核心有对应请求 | 页面可用；稳定性仍有失败记录 |
| Google HK | 普通及隐身模式均显示完整首页；地址栏为香港站且无证书错误 | 通过 |
| Google CN 搜索入口 | `https://google.cn/search?q=kotlin` 最终显示香港站 Kotlin 搜索结果 | 通过 |
| HTTP/HTTPS 导航 | 文档请求返回 307，路径和转义查询参数保留；覆盖 google.cn 及 www.google.cn 的 HTTPS，以及 google.cn HTTP | 通过已列场景 |
| HTTPS 客户端校验 | Python 通过 App CA 验证 TLS；最终 curl 对 GitHub / Google HK 返回 200，验证结果为 0 | 通过证书和最终请求；另有失败样本 |
| 后台返回 | Home 后通过 VIEW 返回同一主进程及 Node；该次 GitHub 请求最终显示上游无响应，后续冷启后恢复 | 稳定性未通过 |
| 强停与冷启 | 强停后主/隐身/Node 及 31181 监听释放；重启产生新进程，监听与页面恢复，无重复解压 | 通过 |
| 隐身进程 | 从浏览器菜单新建无痕标签并输入 Google HK；隐身进程有代理回调，Node 仍只有主进程的一个 | 通过已列场景 |
| 受控端口冲突 | shell 测试监听器占住 31181，核心记录启动错误，无“代理服务已启动”，却仍打印“就绪”并设置 WebView 代理 | 未通过健康状态判断 |
| 冲突清理与恢复 | 精确停止测试监听器和失败的浏览器，确认端口释放，再冷启；监听、Google 页面与最终代理请求恢复 | 通过人工恢复；未证明自动恢复 |
| 配置保留 | 外部 navigation-rules.json 与测试前逐字节相同 | 通过 |

curl 使用显式 App CA，且仅对本次命令使用 `--ssl-no-revoke`，绕过 Windows 对本地 CA 缺少吊销信息的报错；没有关闭证书链或主机名验证，没有修改全局设置。
HTTP 非文档请求不应套用导航规则；未附文档请求头的 curl 曾返回 404，不计为文档重定向失败。

## 未解决的问题

1. **启动失败仍误报就绪。** 受控测试中 `server.start` 失败日志先出现，随后入口仍发出就绪标记，Android 继续 clear/set 代理。监听来自测试进程，并非核心。需要让入口等待真实 server 启动结果，并处理 Android 的失败、退出、重试状态；本次未修改这部分业务代码。
2. **客户端差异与访问稳定性。** Python CONNECT 客户端请求 Google HK 多次返回 500，响应说明上游连接约 7 秒超时；同期 WebView 正常、curl 后续返回 200。GitHub 有一次上游无响应及一次 curl 超时，最终重测返回 200（约 13 秒）。未做同时间原版/Kotlin 严格 A/B，不能归因于网络、客户端差异或迁移中的任意一项。

未覆盖：设备重启、干净安装、长期后台与系统回收、无主进程时直接启动隐身、多标签反复切换、真实 NAT64/IPv6 网络、并发压力及电量/性能对比。日志中出现 Kotlin 模块启用不等于真机请求已实际走过所有迁移函数，NAT64 委派另有本地核心集成测试。

## 最终状态与复现

手机保留本次 Kotlin 测试 APK，最后处于普通浏览器 Google HK 页面。
端口冲突测试监听器和本次创建的 ADB 转发已清理；导航配置未改变，书签等应用数据未清除。
测试通过公开 URL 新增了普通标签和一个无痕标签，强停已结束该隐身进程；未批量关闭原有标签。

测试 APK 使用隔离 assets Gradle init script 构建，**主工作区原有两个 assets zip 仍为旧负载**。
再次普通构建前必须按 `LIGHTNING.md` 生成/同步正确负载，并核对解压版本；不要直接把现有源码标记 `7` 与旧 zip 打包当成已验证组合。

本机证据位于被忽略的 `port/.run/kotlin-device-20261101/`：
`build-info.json`、逐次进程/日志事件 JSON、配置哈希、代理响应、原 APK、新 APK 和截图。
截图中可能包含系统临时通知，不应未经检查直接分享。仓库只保存结果摘要，不提交私钥、负载或原始设备材料。
