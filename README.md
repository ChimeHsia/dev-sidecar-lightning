# dev-sidecar-lightning

把 [docmirror/dev-sidecar](https://github.com/docmirror/dev-sidecar) 的加速能力塞进 Android 浏览器：
WebView 流量走本地 MITM 核心（127.0.0.1:31181），由核心做 DNS 优选、SNI 伪装与证书签发。

外壳用 [Lightning Browser](https://github.com/anthonycr/Lightning-Browser)（MPL-2.0），核心是
dev-sidecar 原版 Node 代码 + Termux 的 aarch64 Node 运行时，跑在 App 自己的进程里。

## 状态

✅ **可用**。荣耀 PPG-AN00 / Android 16 实测：

- 核心 `127.0.0.1:31181` 正常监听，WebView 代理生效
- `github.com/trending` 完整渲染，15 秒内核心收到 207 个请求
- `www.google.com.hk` 走 MITM + SNI 伪装返回 200
- APK 58MB（release，仅 arm64）

## 仓库结构

```
lightning-browser/   Lightning fork + DshaAccel 集成 —— 主产物（包名 com.dsha.browser）
app/ netcore/        早期自研壳与 netcore（纯 Kotlin 转发器），现作为参考实现与单测载体
port/                dev-sidecar 移植工具链：asar 提取 / payload 打包 / payload 同步
patches/             EinkBro 对照实验的改动补丁（对应源码未入库）
```

文档：

| 文件 | 内容 |
|---|---|
| `LIGHTNING.md` | **主线文档**：集成细节、三个致命坑、改动清单 |
| `PORT.md` | 核心移植：Node 运行时怎么来、8 处必改点、真机验证 |
| `EINKBRO.md` | 换壳对照实验记录（EinkBro 版，可用但未采用） |

## 从零构建

payload（`libnodebin.so` 46MB + node libs 30MB）不进版本库，必须先重建：

```bash
# 1) 生成 Node 运行时 + dev-sidecar 核心（需要本机装有 dev-sidecar）
python port/build-payload.py --resources "D:/App/Net/dev-sidecar/resources"

# 2) 同步进壳
./port/sync-payload.sh

# 3) 构建
cd lightning-browser
JAVA_HOME=../.jdk/jdk-21.0.2 ./gradlew assembleLightningPlusRelease
```

`build-payload.py` 里硬编码了 Termux 清华镜像的 deb 版本（node 24.22.1 + 7 个依赖），升级时改那几个常量。

## 为什么和最初设想不一样

项目起步时定过四条约束，后来全部推翻了。原因记在这里，避免重走一遍：

| 最初的决定 | 现在的做法 | 为什么改 |
|---|---|---|
| 不做 MITM、不装根证书 | 做 MITM，CA 打包进 APK 只让本 App 信任 | Google 只给 `CN=invalid.invalid` 诱饵证书，纯字节转发必然被拒 |
| 不用 Node | 内嵌 Termux aarch64 Node 24 | 官方 nodejs-mobile 停在 Node 12，跑不了现代代码；先求功能完整再谈瘦身 |
| 不二开现成浏览器 | fork Lightning Browser | 自研壳缺多标签/书签/历史，没有大量用户测不出来的问题太多 |
| 与上游只有规则语义交集 | 直接移植整个核心 | 规则引擎只是冰山一角，SNI 伪装、IP 优选、ECH、证书管理都在核心里 |

一条仍然成立的结论：**不能在链路上改写 TLS 字节**。SNI 参与握手 transcript，改字节会让 Finished 校验失败。
回归测试见 `netcore/src/test/.../TlsClientHelloTest.kt`。

## 已知问题

- **代理端口 31181 会被抢占**：多个测试包（`com.dsha.sidecar` / `com.dsha.browser` / EinkBro 版）同时装时，
  先启动的占住端口，后启动的核心报 `EADDRINUSE` 秒退。测试前 `adb shell am force-stop` 其它包。
- **release 看不到核心日志**：`proguard-project.txt` 里有 `-assumenosideeffects`，`Log.i/d/v` 全被裁。
  排查时关掉混淆或改用 `Log.e`。
- **`www.google.com` 会 302 到 `google.cn` 再 404**：这是 Google 按出口 IP 的地域重定向，不是代理问题。
  用 `www.google.com.hk`。
- **APK 58MB**：46MB 是 Node 运行时。瘦身需要把核心重写成 Kotlin——那是下一步的事。

## 环境注意

- **`gradle.properties` 里的 `org.gradle.java.home` 是全仓库唯一写死的绝对路径**，移动/重命名工作区后必须同步改。
- 本机 git 走 dev-sidecar 的 HTTPS 代理（`127.0.0.1:31182`），且需要
  `git config --global http.schannelCheckRevoke false`——MITM 证书没法查吊销，schannel 会直接拒绝握手。
- 仓库地址：<https://github.com/ChimeHsia/dsha-sidecar>（private）
