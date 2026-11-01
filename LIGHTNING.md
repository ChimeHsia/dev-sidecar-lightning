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
# 1) 编译供 Node 直接调用的 Kotlin/JS 小模块
./build.sh :netcore-js:jsNodeProductionLibraryDistribution

# 2) 生成 Node 运行时 + dev-sidecar 核心
#    需要本机装有 dev-sidecar（取它的 app.asar 作为核心源码）
python port/build-payload.py --resources "D:/App/Net/dev-sidecar/resources"

# 3) 同步进壳
./port/sync-payload.sh                 # 默认 lightning-browser，可传目录名

# 4) 构建
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

### 2026-11-01 离线启动与 TLS 修复

- payload 8 等待实际服务器监听后才发出精确 READY 协议；失败退出并复位 Android 等待状态，旧启动回调不能标记新启动成功。
- 负载生成器为上游统一 `Connection: close` 的 TLS 策略，并让连接计时覆盖 TLS 握手。更换上游布局时补丁会明确失败。
- 代码与本地端口/TLS 回归已验证。2026-11-02 又完成 payload 8 的启动、端口冲突与同进程恢复、Python close 请求和页面真机检查，见 [新验收记录](docs/DEVICE-RESULTS-2026-11-02.md)；仍有 GitHub 重试及长期场景限制。
- 主工作区现有 zip 未覆盖。构建前必须重新生成/同步负载，不能只修改版本标记后打包旧 assets。

### 2026-11-01 HTTP 入口与页面暂停修复

- `google.com`、`google.com.hk` 及各自的 `www` HTTP 网页入口，在代理内直接返回
  307 到对应的 `https://www...`，保留路径、查询串；避免先等两轮 7 秒的 80 端口超时。
  该处理位于 DNS/IP 选择前，不会把 HTTP 超时记入 HTTPS 共用的 IP 失败状态。
- 仅处理无请求体的文档 GET/HEAD。普通 HTTP、非标准端口、API 和其他方法仍走原流程。
  实现见 `port/node/http-navigation.js`，在 Node 入口安装，不修改上游核心源码。
- 浏览器前后台切换使用 `BrowserVisibilityController` 取消过期任务。异步保存标签期间
  若已切回前台，旧的后台任务不能再暂停 WebView 的 JavaScript 和布局计时器。
- 网络页面首次加载及标签恢复通过 `ProxyStartupGate` 等待核心就绪和 WebView 代理配置完成；
  最长等待 15 秒，不阻塞界面。停止、切换地址或销毁标签后，不再执行旧的等待中导航。
- 本次对比使用 `port/.run/browser-versions/baseline-before-kotlin.apk` 的原版核心；
  只修改负载入口并增加 HTTP 导航模块。当前本地负载也保持这份基线，Kotlin 迁移源码保留。
  重新生成不同负载时仍须增加 `DshaAccel.PAYLOAD_VERSION`，避免沿用旧解压标记。
- 回归：`node --test port/tests/http-navigation.test.js`、
  `python -m unittest discover -s port/tests -p 'test_*.py'`，
  以及浏览器 JVM 的 `BrowserVisibilityControllerTest`、`ProxyStartupGateTest`。

### 其他已知问题

- Google 可能把 `www.google.com` 重定向到 `google.cn`。现在由下面的导航配置将
  `google.cn`、`www.google.cn` 的 HTTP/HTTPS 网页请求转到香港入口，避免落到 404。
- 从别的 App 点链接会弹系统"打开方式"选择器（音乐/WPS/浏览器都能收 VIEW），
  在系统设置里把「边车浏览器」设为默认浏览器即可。
- 端口 31181 会被旧壳（`com.dsha.sidecar`）/ EinkBro 版抢占，现象是 `EADDRINUSE` + 核心秒退。
  装多个测试包时先 force-stop 其它。
- APK 58MB 主要是 Node 运行时（`libnodebin.so` 46MB + assets 30MB）。
- debug 变体目前不可用（LeakCanary 抢入口 + 依赖锁），要调 UI 请用 release 变体。

## 可配置的域名跳转

默认规则在 `port/node/navigation-rules.json`，随 Node 负载打包，域名不写在跳转引擎里。
首次运行时复制到 App 外部文件目录：

```text
/sdcard/Android/data/top.panming.dsbrowser/files/navigation-rules.json
```

后续升级保留已有文件。可通过 ADB 拉取、编辑、写回，不需要重新构建 APK：

```powershell
adb pull /sdcard/Android/data/top.panming.dsbrowser/files/navigation-rules.json port/.run/navigation-rules-edit.json
# 编辑 port/.run/navigation-rules-edit.json，保留需要的其他规则
adb push port/.run/navigation-rules-edit.json /sdcard/Android/data/top.panming.dsbrowser/files/navigation-rules.json
```

例如仅保留中国域名到香港入口的规则：

```json
{
  "version": 1,
  "rules": [
    {
      "enabled": true,
      "sourceHosts": ["google.cn", "www.google.cn"],
      "sourceSchemes": ["http", "https"],
      "targetOrigin": "https://www.google.com.hk"
    }
  ]
}
```

- `sourceHosts` 精确匹配域名，不包含子域名或通配符；`sourceSchemes` 指定 `http`、`https`。
  `targetOrigin` 必须是 HTTPS 的协议和域名，不带路径、查询串或非标准端口。
- 返回不缓存的 307，保留原路径及查询串。例如
  `https://google.cn/search?q=a%2Fb` → `https://www.google.com.hk/search?q=a%2Fb`。
  只重定向无请求体的文档 GET/HEAD；API、页面资源和其他方法走原处理流程。
- 修改在下一次文档请求或新 CONNECT 时加载。`enabled: false` 关闭一条规则；
  `"rules": []` 关闭全部导航规则。格式错误、重复来源和跳转循环会记录日志，并继续使用
  上一次有效配置；启动时尚无有效配置则使用打包默认值。配置上限为 64 KiB、128 条规则。
- HTTPS 来源按配置加入现有 MITM 流程，才能返回跳转响应；核心明确列入白名单的域名仍跳过
  MITM，不执行 HTTPS 导航规则。浏览器已有 CA 信任配置不变。
- 宿主通过 `DSHA_NAVIGATION_CONFIG` 指定运行时文件；外部文件目录不可用时回退到 App
  内部文件目录，实际位置会输出到 `[navigation] Config:` 日志。
