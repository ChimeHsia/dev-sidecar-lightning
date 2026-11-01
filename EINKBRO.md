# EinkBro 版（info.plateaukao.einkbro）集成记录

> ⚠️ **这一版已弃用，源码也已删除**（2026-11-01）。
> 主产物换成了 Lightning（见 `LIGHTNING.md`）。本文档只保留结论：
> 它证明了"WebView 是裸 `WebView` 子类时代理能生效"，用来对照 Lightning 的失败原因。
> 若要重建：clone EinkBro 16.7.1 后 `git apply patches/einkbro-integration.patch`，
> 再按 LIGHTNING.md 的方式准备 payload（`port/sync-payload.sh einkbro`）。

> 目标：用成熟浏览器外壳（EinkBro，GPL-3.0，6000+★，原生 Activity + WebView）
> + 内嵌 dev-sidecar 核心。**这一版代理真的通了。**

## 当前状态：✅ 可用

真机（荣耀 PPG-AN00 / Android 16）实测：

| 测试 | 结果 |
|---|---|
| 核心启动 | ✅ `代理服务已启动：127.0.0.1:31181, ssl: true` |
| WebView 代理 | ✅ `WebView 代理已生效 → 127.0.0.1:31181`，核心收到全部请求 |
| GitHub | ✅ github.com/trending 完整渲染（仓库列表、star 按钮） |
| Google | ✅ www.google.com.hk 首页 + 搜索结果页正常 |
| 百度 | ✅ 图片/图标全部正常（对照组，证明不是"代理把页面搞坏"） |
| APK 体积 | 48MB（release，R8 + 仅 arm64） |

## 为什么 EinkBro 成了、Lightning 没成

只差一个结构差异：

- **Lightning**：WebView 建在 Compose `AndroidView` 里 → `ProxyController.setProxyOverride`
  回调成功但设置被丢弃，核心收到 0 请求。
- **EinkBro**：`EBWebView : WebView(context)` 自己继承，由 Activity 直接 new →
  与旧壳（已验证可用的 `com.dsha.sidecar`）结构一致，代理正常生效。

**结论：换壳的坑不是"换壳"本身，而是 Compose 包裹 WebView 时 WebView 的创建/attach 时序。**
以后选壳，先看 WebView 是不是裸 `WebView` 子类。

## 改动清单（相对上游 16.7.1）

1. **新增** `app/src/main/java/info/plateaukao/einkbro/accel/DshaAccel.kt`
   解压 assets → exec `libnodebin.so` → 等"就绪"→ 设代理。可重复调用 `ensureProxy()`。
2. `EinkBroApplication.onCreate()` 首行 `DshaAccel.start(this)`（异步，失败不影响浏览器）。
3. `EBWebView.init` 末尾 `DshaAccel.ensureProxy(context)`（每个 WebView 兜底补设）。
4. `res/xml/network_security_config.xml` 增加 `<certificates src="@raw/dsha_ca" />`
   （只让本 App 信任自签 CA，不碰系统证书）。
5. `app/build.gradle.kts`
   - `jniLibs.useLegacyPackaging = true`（`libnodebin.so` 要被 exec）
   - `androidResources.noCompress += ["zip","pem","crt"]`
   - release 用 debug 签名（本地自用，不依赖 `~/.secrets` 里的 Play 密钥）
6. `local.properties` → `sdk.dir=D:/App/Dev/Android`
7. `gradle-wrapper.properties` → 腾讯镜像（gradle 8.14.5）

## 资源（从 Lightning 版复用，同一份）

```
app/src/main/assets/{node-libs.zip, nodejs-project.zip, cacert.pem, dsha_ca.crt, dsha_ca.key.pem}
app/src/main/jniLibs/arm64-v8a/libnodebin.so    ← Termux nodejs-lts 24.22.1，46MB
app/src/main/res/raw/dsha_ca.pem
```

## 构建

```bash
cd einkbro
JAVA_HOME=../.jdk/jdk-21.0.2 ./gradlew assembleRelease -PbuildAbis=arm64-v8a
# → app/build/outputs/apk/release/app-arm64-v8a-release.apk（48MB）
```

`-PbuildAbis=arm64-v8a` 必须带，否则 splits 会为四个 ABI 各出一个包。

## 已知问题

- **debug 变体会被 LeakCanary 抢占启动**（`0 Distinct Leaks` 全屏页）——
  上游 `debugImplementation` 里虽注释了但仍有传递依赖。**用 release 包即可，没有这问题。**
- **release 看不到 dsha 日志**：上游 `proguard-rules.txt` 里
  `-assumenosideeffects class android.util.Log { … v/d/i }` 把 `Log.i` 全裁了，
  只剩 `w/e`。排查时改用 debug 包，或临时把那几条注释掉。
- **从别的 App 点链接会弹系统"打开方式"选择器**：这是系统行为（音乐/WPS/EinkBro 都能收 VIEW），
  在系统设置里把 EinkBro 设为默认浏览器即可，不是 App bug。
- 端口 31181 会被旧壳（`com.dsha.sidecar`）/ Lightning 版（`top.panming.dsbrowser`）抢占，
  现象是 `EADDRINUSE` + 核心秒退。装多个测试包时先 force-stop 其它。
