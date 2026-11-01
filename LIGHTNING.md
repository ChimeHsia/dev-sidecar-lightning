# Lightning 版（top.panming.dsbrowser）集成记录

> 目标：用成熟浏览器外壳（Lightning Browser，MPL-2.0，2451★）+ 内嵌 dev-sidecar 核心。

## 当前状态：✅ 可用（2026-11-01 04:12 真机实测）

| 测试 | 结果 |
|---|---|
| 核心启动 | ✅ `代理服务已启动：127.0.0.1:31181, ssl: true` |
| WebView 代理 | ✅ 核心收到 **207 个请求**（不再是 0） |
| Google | ✅ `www.google.com` 走代理返回 302（被 Google 按地域重定向到 google.cn，属正常） |
| GitHub | ✅ `github.com/trending` 完整渲染（仓库列表、star 按钮） |
| APK | 58MB（release，不混淆，仅 arm64） |

## 之前为什么一个请求都到不了核心

三个问题叠在一起，逐个都是致命的：

### 1. debug 包被 LeakCanary 抢了启动入口（**主因**）

`debugRuntimeOnly("com.squareup.leakcanary:leakcanary-android")` 会注册自己的
LAUNCHER Activity，点图标先弹满屏 `0 Distinct Leaks`，浏览器 Activity 根本没起来，
WebView 自然从未创建 —— 表现就是"代理设置回调成功，核心 0 请求"。

这个依赖被写进了 Gradle 的 dependency lock，删掉会让依赖解析直接失败：
```
Did not resolve 'com.squareup.leakcanary:leakcanary-android:2.14'
which is part of the dependency lock state
```
→ **调试和交付都改用 release 变体**（release 不含 LeakCanary）。

（此前"Compose `AndroidView` 里的 WebView 导致代理失效"的猜测是错的，已推翻。）

### 2. 启动瞬间就开始加载，代理还没就绪

Lightning 启动会恢复上次标签，页面立刻发请求，那时核心端口还没 LISTEN。
Chromium 会把"代理连不上"这个结论缓存一段时间，之后端口开了也不重试。

修法（`DshaAccel.refreshProxy`）：核心就绪后 **先 `clearProxyOverride` 再 `setProxyOverride`**，
强制 Chromium 丢掉旧结论重新解析。

### 3. release 开 R8 会启动即崩

```
IllegalStateException: Unsupported type class fn4
  at DefaultBrowserActivity
```
R8 把 kotlinx.serialization / Room 的类型信息裁坏了（上游靠 CI 的 keep 规则撑着）。
本地自用直接把 `isMinifyEnabled` / `isShrinkResources` 设为 `false`，
顺带保住了 `Log.i`（`proguard-project.txt` 里有 `-assumenosideeffects`，否则日志全没了）。

## 改动清单（相对上游）

| 文件 | 改动 |
|---|---|
| `accel/DshaAccel.kt`（新增） | 解压 assets → exec `libnodebin.so` → 等"就绪"→ 设代理；`refreshProxy()` 就绪后强制重设 |
| `BrowserApp.kt` | 主进程 `DshaAccel.start(this)`；incognito 进程 `attachProxyOnly` |
| `BrowserActivity.onResume/onPostResume` | 补设代理 |
| `WebViewFactory.createWebView` | 每个 WebView 补设代理 |
| `TabWebViewClient.shouldBlockRequest` | 修上游 bug：规则未就绪时不要 `runBlocking`，否则堵死请求线程 |
| `UserPreferencesDataStore.openAvailableAppsEnabled` | 默认 true→false，否则点链接弹系统选择器 |
| `res/xml/network_security_config.xml` | 加 `@raw/dsha_ca`（只本 App 信任自签 CA） |
| `AndroidManifest.xml` | `tools:node="remove"` 去 LeakLauncherActivity |
| `app/build.gradle.kts` | `jniLibs.useLegacyPackaging`、`noCompress(zip,pem)`、release 关混淆 + debug 签名 |
| `gradle.properties` | `ksp.incremental=false`（跨盘符增量编译必崩） |
| `res/values/styles.xml`、`theme_colors.xml` | XML 主题迁到 Material3，颜色与 `compose/Color.kt` 一致，`ThemeColorsTest` 校验 |
| `res/drawable/ic_*.xml`（`viewportWidth="960"` 的那些） | 取自 [Material Symbols](https://github.com/google/material-design-icons)（Outlined，wght 400，npm `@material-symbols/svg-400` 0.48.2，Apache-2.0） |

## 关键文件

```
lightning-browser/
├─ app/src/main/java/acr/browser/lightning/accel/DshaAccel.kt   ← 加速模块
├─ app/src/main/java/acr/browser/lightning/BrowserApp.kt        ← 启动入口
├─ app/src/main/java/acr/browser/lightning/browser/BrowserActivity.kt
├─ app/src/main/java/acr/browser/lightning/browser/tab/WebViewFactory.kt
├─ app/src/main/assets/{node-libs.zip,nodejs-project.zip,cacert.pem,dsha_ca.*}
├─ app/src/main/jniLibs/arm64-v8a/libnodebin.so
└─ app/src/main/res/{raw/dsha_ca.pem,xml/network_security_config.xml}
```

## 从零构建（克隆仓库后）

payload（`libnodebin.so` 46MB + node libs 30MB）不进版本库，必须先重建：

```bash
# 1) 生成 Node 运行时 + dev-sidecar 核心
#    需要本机装有 dev-sidecar（取它的 app.asar 作为核心源码）
python port/build-payload.py --resources "D:/App/Net/dev-sidecar/resources"

# 2) 同步进壳
./port/sync-payload.sh                 # 默认 lightning-browser，可传目录名

# 3) 构建
cd lightning-browser
JAVA_HOME=../.jdk/jdk-21.0.2 ./gradlew assembleLightningPlusRelease
# → app/build/outputs/apk/lightningPlus/release/app-lightningPlus-release.apk（58MB）
```

`port/build-payload.py` 里硬编码了 Termux 清华镜像的 deb 版本（node 24.22.1 及 7 个依赖），
升级时改那几个常量即可。

## 构建（payload 已就位时）

```bash
cd lightning-browser
JAVA_HOME=../.jdk/jdk-21.0.2 ./gradlew assembleLightningPlusRelease
```

## 已知问题

- **Google 访问 `www.google.com` 会被 302 到 `google.cn` 然后 404** —— 这是 Google 按出口 IP 的
  地域重定向，不是代理问题。用 `www.google.com.hk` 就正常。
- 从别的 App 点链接会弹系统"打开方式"选择器（音乐/WPS/浏览器都能收 VIEW），
  在系统设置里把「边车浏览器」设为默认浏览器即可。
- 端口 31181 会被旧壳（`com.dsha.sidecar`）/ EinkBro 版抢占，现象是 `EADDRINUSE` + 核心秒退。
  装多个测试包时先 force-stop 其它。
- APK 58MB 主要是 Node 运行时（`libnodebin.so` 46MB + assets 30MB）。
- debug 变体目前不可用（LeakCanary 抢入口 + 依赖锁），要调 UI 请用 release 变体。
