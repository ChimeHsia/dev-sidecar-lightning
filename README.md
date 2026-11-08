# dev-sidecar-lightning

把 [docmirror/dev-sidecar](https://github.com/docmirror/dev-sidecar) 的加速能力接入 Android 浏览器。WebView 通过本地代理（127.0.0.1:31181）访问网络，由 Kotlin 核心处理 DNS、MITM、SNI、ECH 和规则。

浏览器外壳基于 [Lightning Browser](https://github.com/anthonycr/Lightning-Browser)（MPL-2.0）。当前产品直接运行原生 Kotlin/JVM 核心；APK 不再携带或启动 Node.js。

## 当前状态（2026-11-08）

- 已接通原生核心、普通/隐身进程服务绑定、配置继承和远程规则热更新。
- 默认 56 组域名规则保留 ECH、脚本、备用目标；请求失败支持有界候选切换。
- Release versionCode 103，APK 约 21.2 MiB，已检查不含 Node 负载及运行时。
- 本轮主机测试和构建记录见 [原生迁移记录](docs/NATIVE-KOTLIN-MIGRATION-2026-11-08.md)。当前没有连接 Android 设备，原生包尚未完成 WebView/生命周期真机验收；旧 Node 包的设备结果只作为历史记录。

## 仓库结构

| 目录 | 用途 |
|---|---|
| `lightning-browser/` | 主产品，包名 top.panming.dsbrowser；native-core 模块直接编译共享网络源码 |
| `netcore/` | 原生代理、DNS、TLS/ECH、证书、规则、配置和 JVM 回归 |
| `app/、netcore-js/` | 早期参考壳与历史 Kotlin/JS 迁移路径 |
| `port/` | 默认数据导出、历史 Node 构建工具与测试基线 |
| `patches/` | 早期浏览器对照补丁 |

## 构建

使用 JDK 21 和 Android SDK，配置本机 `gradle.properties` / `local.properties`。原生构建使用已提交的规则数据，无需生成或同步 Node payload。


```bash
cd lightning-browser
./gradlew assembleLightningPlusRelease
./gradlew testLightningPlusDebugUnitTest :native-core:lintRelease
```

APK：`lightning-browser/app/build/outputs/apk/lightningPlus/release/app-lightningPlus-release.apk`。

本地 CA 文件沿用现有开发配置且不入库；首次克隆须配置与 App 信任资源匹配的 CA。生成的 Node 二进制、归档、配置与 CA 私钥均不得提交。Release 当前使用 debug 签名，仅供本地使用。

## 测试

完整原生 JVM 回归使用带独立 Go ECH 对端的入口：


```bash
python scripts/test-native-core.py --java-home <jdk21> --conscrypt-jar <conscrypt-openjdk-uber-2.8.0.jar> --go-exe <go>
python scripts/verify-native-browser-apk.py --apk lightning-browser/app/build/outputs/apk/lightningPlus/release/app-lightningPlus-release.apk
```

依赖需预先下载到 Gradle 缓存，Go 需支持 TLS ECH。浏览器 JVM 测试和 APK 检查不能代替真机页面、证书与服务生命周期验证，步骤见 [回归指南](docs/REGRESSION.md)。

## 文档与边界

- [当前原生迁移记录](docs/NATIVE-KOTLIN-MIGRATION-2026-11-08.md)：接入范围、测试、产物与限制。
- [Lightning 集成记录](LIGHTNING.md)：当前入口及早期浏览器问题。
- [历史差距分析](KOTLIN-MIGRATION.md)、[历史 Node 移植](PORT.md)：早期实现背景。
- 桌面 PAC、系统代理安装、插件运行时和 cloudflareRoute 未启用；启用未实现选项时配置会明确拒绝。
- 缓存规则保持上游 304/响应头语义，不提供内存实体缓存。ECH 失败保持拒绝；浏览器对旧 SNI 规则显式保留原有上游 TLS 兼容策略，独立核心默认严格校验。
- 本地 31181 端口被其他测试包占用时核心启动失败；设备测试前检查并停止冲突包。
