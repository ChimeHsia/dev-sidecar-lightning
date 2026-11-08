# 原生 Kotlin 浏览器迁移（2026-11-08）

> 后续端口冲突和下载修复、versionCode 104 及最新回归见 [端口与下载核查](PORT-DOWNLOAD-2026-11-08.md)。本文 APK 哈希和 103 版结果属于迁移提交基线。

用户要求先提交并推送现有工作，然后完成 Node.js 到 Kotlin 的迁移，最后集中测试。迁移前基线已提交为 `7be44cb` 并推送 origin/main。

## 当前产品路径

`BrowserApp → DshaAccel → NativeProxyService → NativeRuntime`；WebView 继续指向 127.0.0.1:31181。
`lightning-browser/native-core` 直接编译 `netcore/src/main`，使用 Android Conscrypt、Bouncy Castle 和 Brotli 解码库。
产品不会启动 Node 子进程；旧 Node 工具、差分夹具和测试保留供追溯。

普通浏览器进程持有服务；隐身进程通过非导出绑定服务启动/保留主进程，查询 Binder 就绪状态，避免将其他应用的 31181 监听当作本包就绪。
网络导航继续通过既有 ProxyStartupGate 等待核心监听和 WebView 代理设置。

## 本轮接通

- DNS provider、预设地址、请求内失败候选排除、晚期 TLS/HTTP 失败反馈、后台有界 TCP 探测排序；浏览器显式允许未配置域名的系统 DNS 兜底。
- CONNECT 选择性 MITM/白名单隧道、动态叶证书、SNI 与校验主机分离。
- HTTPS/SVCB 查询取得 ECHConfigList，再交给 Conscrypt 执行 ECH；NAT64 ECH 查询接入原生发现链路，失败不降级为普通 TLS。
- 响应提交前有界重试、显式方法/状态重试与小请求体重放、备用目标重建。已提交响应、流式上传、Expect 和升级请求不自动重放。
- HTML 脚本响应、远程脚本代理路径、gzip/deflate/Brotli 有界解码；Brotli 修改后发送 identity 实体，重建长度和编码头。
- 默认有效配置导出为数据，保留 56 组域名规则、ECH、脚本和备用目标；机器路径及桌面插件/系统代理入口剥离。
- 旧目录只迁移受大小/文件名限制的用户与远程配置，原文件保留；旧 rootCaFile 路径不会替换 App 的固定 CA。
- 远程规则通过自己的本地代理下载、校验后热更新。新请求使用新状态，在途请求持有旧 DNS/TLS/规则状态直到完成，监听端口不变；非法更新保留现有配置。

## 依赖与信任

- Conscrypt 2.8.0：Android TLS 与 ECH；JVM 测试使用同版 openjdk-uber。
- Bouncy Castle bcpkix/bcprov/bcutil 1.79：证书和密码学组件。
- org.brotli:dec 0.1.2：纯 Java Brotli 解码。
- desugar_jdk_libs_nio 2.1.5：minSdk 24 的共享 Java/NIO API 兼容。

独立核心默认严格校验 TLS。浏览器显式开启 legacyTlsCompatibility，保留旧 SNI 伪装规则的上游兼容信任策略；ECH 分支保持严格校验。
App 信任范围沿用打包 CA，只对本 App 生效；私钥属于本地开发资产，不入库。首次克隆需准备匹配的
`assets/dsha_ca.crt`、`assets/dsha_ca.key.pem` 和 `res/raw/dsha_ca.pem`。
发布构建仍用 debug 签名，本产物用于本地验证。

## 验证

| 检查 | 本轮结果 |
|---|---|
| 原生 JVM 全量（独立 Go ECH 对端） | 979 项：977 通过，2 项 opt-in live 跳过，0 失败 |
| 浏览器 JVM | 220 项通过，0 失败、0 跳过，包含两项服务归属回归 |
| Release APK | 最终重建及内容复核通过，versionCode 103 |
| APK 内容审计 | 22,216,021 字节（约 21.2 MiB）；无 Node 归档/ELF/node_modules，包含原生 runtime/service、Conscrypt JNI、56 组规则、默认 ECH |
| native-core lintRelease | 通过：0 error、5 warnings（版本提示与自定义信任管理器提示） |
| Python 历史打包工具 | 7 项通过 |
| 真机原生包验收 | 未执行：adb 无已连接设备 |

新增回归覆盖两个失败候选后第三个成功、POST 显式状态重放、备用目标 Host/path 重建、真实响应 HTML 注入、Brotli 独立夹具、默认有效配置加载、配置文件继承、在途请求期间热更新。
额外贯通验证真实 UDP HTTPS DNS 记录 → runtime ECH 准备 → Conscrypt → 独立 Go TLS/HTTP 服务，确认服务端 ECH accepted 和 inner SNI；远程配置测试确认经原生 MITM 下载，未修改全局信任设置。

命令（仓库根目录）：

```bash
python scripts/test-native-core.py --java-home <jdk21> --conscrypt-jar <conscrypt-openjdk-uber-2.8.0.jar> --go-exe <go>
cd lightning-browser
./gradlew assembleLightningPlusRelease testLightningPlusDebugUnitTest :native-core:lintRelease
cd ..
python scripts/verify-native-browser-apk.py --apk lightning-browser/app/build/outputs/apk/lightningPlus/release/app-lightningPlus-release.apk --report port/.run/native-browser-migration/apk-report.json
```

缓存依赖需预先下载；Go 工具链须支持 ECH。主机证据位于忽略的 `port/.run/native-core-review/` 和 `port/.run/native-browser-migration/`。
SDK/JDK 路径按机器配置，不提交本地配置。

最终 APK SHA-256：`4096c52f7c918605e2f2a32a99ee7c03135b772a6d31683fbf4b707eff5e37ee`。

## 尚未验证和适用边界

- 当前环境无 Android 设备，尚未验证新 APK 在 WebView 的页面渲染、普通/隐身跨进程、后台/冷启动、端口冲突后恢复、升级继承及网络切换。按 [回归指南](REGRESSION.md) 的 D01–D08 补设备验收，旧 Node 包结果不能替代。
- ECH 实际握手由独立 Go 对端验证；Android JNI 已打包，设备端 ABI/API 和真实公网 ECH 仍需实测。
- 桌面 PAC、系统证书安装/代理切换、任意插件、cloudflareRoute 未启用；配置请求开启未绑定能力会被明确拒绝。
- 缓存规则保持上游 304/响应头行为，实体内存缓存仍关闭。HTTP/2 不作为新增能力；MITM 上游使用 HTTP/1.1，隧道继续传输原始字节。
- TCP 后台测速只用于排序，不代表 TLS/HTTP 成功；请求内失败排除优先。
