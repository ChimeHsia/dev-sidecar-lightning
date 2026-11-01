# dev-sidecar → Android 全量移植方案

> 目标：把 dev-sidecar 本体（不是重写、不是精简）搬到手机上，先保证功能不丢，再谈体积。
> 结论日期：2026-11-01（真机 + 本机双向验证过）

---

## 一、为什么这条路比"用 Kotlin 重写"靠谱

原方案（`HANDOFF.md`）是用 Kotlin 重写核心，结果是：DNS 优选、规则引擎、隧道都做了，
但**一碰到需要改 TLS 语义的能力（Google、镜像重定向、拦截）就全废**——
因为 dev-sidecar 的核心价值恰恰在 MITM 层，重写等于重做一遍它十年的规则积累。

改用"原版核心 + 换壳"后：

- 规则、DNS 优选、预设 IP、MITM、油猴脚本、PAC、插件，**全部原样继承**；
- 上游更新可以直接同步；
- 我们要写的只有"壳"：Node 运行时 + UI 桥 + 系统集成。

## 二、已确认的事实（都验证过，不是推测）

| 事实 | 证据 |
|---|---|
| 核心是**纯 JS**，不含原生模块 | `@blue-frontier/dev-sidecar/src` 全目录 `grep` 无 `require('electron')` |
| Electron 只在 GUI 层 | 核心入口 `src/index.js` → `expose.js`，不 import electron |
| 原生 `.node` 只有 5 个，且都是 Windows/凭据相关 | `koffi`、`sysproxy`、`shutdown-handler`（win32）、`@parcel/watcher`、`keytar` |
| Windows 相关代码隔离在 `src/shell/` | `set-system-proxy`、`set-system-env`、`sysproxy.exe` |
| **核心能在纯 Node 下启动** | 本机 `node run2.js`：`代理服务已启动：127.0.0.1:31281, ssl: true`，CA 自动生成 |
| **移植后 Google 能打开** | 经该代理 `curl https://www.google.com.hk` → **200，1.7s**；github → 200 |
| asar 总体积 222MB，其中绝大多数是界面 | `ant-design-vue` 58MB、`ace-builds` 42MB、`jsoneditor` 5.8MB |
| UI 是 Electron `loadFile(dist/index.html)` + IPC 桥 | `src/background.js:541`，走 `src/bridge/front.js` 的模块 |

> Google 能过，是因为原版逻辑本来就含 `sni 伪装(www.google.cn) + unVerifySsl + preSetIpList`，
> 见 `HANDOFF.md` 3.9 节。这是我们自己实现永远达不到的部分。

## 三、目标架构

```
Android App（单 APK）
├─ Node 运行时（nodejs-mobile，libnode.so，每 ABI 约 25MB）
│   └─ 原版核心（src + node_modules，assets 内）   ← 不改业务代码
│       ├─ proxy server (127.0.0.1:31181)  MITM / 规则 / DNS优选 / 预设IP
│       └─ HTTP 桥 (替代 Electron IPC)     ← 我们要写的
├─ WebView A：浏览器本体 → ProxyController 指向 127.0.0.1:31181
└─ WebView B：原版管理界面（dist/ + HTTP 桥）
```

## 四、必须改的地方（8 处，含原因）

| # | 位置 | 现状 | 改法 |
|---|---|---|---|
| 1 | `modules/server/index.js:174` | `fork(mitmproxyPath, [running.json])` | Android 无 `child_process.fork` → 同进程 `require` 并 `server.start(config)`（已验证可行） |
| 2 | `src/shell/`（`set-system-proxy`、`set-system-env`、`sysproxy.exe`、`koffi`、`winreg`） | 改 Windows 注册表 / 环境变量 | 换成空实现；系统代理改由 App 侧 `ProxyController.setProxyOverride` 完成 |
| 3 | `config-api` / `local-config-loader` 的根目录 | `os.homedir()/.dev-sidecar` | 指向 App 私有目录（`context.filesDir`），否则 Android 上无写权限 |
| 4 | `mitmproxy/src/lib/interceptor/impl/res/script.js:139` | `pathConfig.script` 未判空，**null 规则会崩** | 启动前剔除 `intercepts` 里的 null 项（dev-sidecar 2.3.1 自身 bug，见下） |
| 5 | `src/bridge/`（api / fileSelector / update / autoStart / onClose / tongji） | Electron IPC | 换成 HTTP + fetch 垫片；update / autoStart 直接置空 |
| 6 | 证书安装 | Windows 调用系统 API 装根证书 | Android 只能导出 PEM 让用户手动装（设置→安全→安装证书），或用 `network-security-config` 只让本 App 信任 |
| 7 | `keytar`（凭据存储） | 系统钥匙串 | 不用，删掉 |
| 8 | `log4js` 输出路径 | `~/.dev-sidecar/logs` | 改 App 私有目录 + 限制单文件大小（手机上别写 1GB） |

### 顺带发现的 dev-sidecar 自身 bug

`~/dev-sidecar/running.json` 里存在 `intercepts["github.com"]["^(/[\\w-.]+){2,}/?(\\?.*)?$"] = null`
（远程配置用 null 表示"停用该条"），而 `script.js:139` 直接读 `pathConfig.script`：

```js
if (typeof pathConfig.script === 'object' && pathConfig.script.length > 0)  // pathConfig 为 null → TypeError
```

**后果：代理服务只要重启就会崩**，桌面上表现为"开了 sidecar 但代理不工作"。
现在没炸只是因为进程一直没重启过。移植时必须在启动前 sanitize。

## 五、分阶段计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0（已完成）** | 核心脱离 Electron 跑通 | `代理服务已启动`，Google 200 ✅ |
| **P1** | 把核心 + node_modules 打进 Android assets；`nodejs-mobile` 启动 Node | App 启动后 127.0.0.1:31181 可连（adb forward 验证） |
| **P2** | 浏览器 WebView + `ProxyController` 指向 31181 + 证书引导 | 手机上打开 github.com / google.com.hk |
| **P3** | 用 HTTP 桥替代 IPC，WebView 加载原版 `dist/` | 原版配置界面可用（改规则、看状态） |
| **P4** | 收尾：前台服务保活、通知、退出清理、体积优化（可选） | 后台不被杀，能正常退出 |

## 六、风险

1. **nodejs-mobile 的版本**：需 ≥ Node 18（核心用了 `node:` 前缀、可选链等）。要确认它支持 `worker_threads`（若 fork 改 worker 更稳）。
2. **体积**：assets 里 222MB（可去掉 `dist` 外的 UI 依赖、只留一条 ABI，能压到 ~60MB）；与"≤6MB"的原始目标冲突，需明确取舍。
3. **证书**：Android 用户装 CA 的门槛比 Windows 更高，需引导页 + 一键导出。
4. **保活**：Node 跑在 App 进程内，被系统杀死后代理即断，需要前台服务 + 自启。

## 七、本仓库里的验证脚本

`port/harness.js` —— 一键复现"核心脱离 Electron 运行"的脚本（P0 的产物）。

```bash
# 需要一个已解包的 app 目录（extract-asar.py 生成）
node port/harness.js /path/to/app
```

## 八、P1 实施记录（2026-11-01）

### Node 运行时怎么来的

官方 `nodejs-mobile` 最新版（v0.3.3，2021 年）只有 **Node 12.19**，而核心用了
`require('node:...')`、可选链、空值合并——**Node 14+ 才支持**，直接用会语法报错。

改用 **Termux 的 node（Node 24 LTS）**：它是普通 Android aarch64 ELF，可直接 exec。

| 项 | 值 |
|---|---|
| 包 | `nodejs-lts_24.22.1_aarch64.deb`（清华镜像） |
| 二进制 | 45 MB |
| 依赖 | libc++ / openssl / c-ares / libicu / libsqlite / zlib / libffi（共 46 MB） |
| 坑 | Termux 的 RUNPATH 指向 `/data/data/com.termux/files/usr/lib`，靠 `LD_LIBRARY_PATH` 覆盖 |
| 坑 | Termux 的 openssl 默认证书路径不存在，用 `SSL_CERT_FILE` 指向自带的 cacert.pem |
| 坑 | Android 10+ 禁止从数据目录 exec，二进制必须以 `libnodebin.so` 放进 jniLibs 并 `useLegacyPackaging = true` |

### 已完成的接线

- `keyboard` → 无。Android 侧新增：`app/src/main/java/com/dsha/sidecar/node/NodeRuntime.kt`
  （首次启动解压 assets → 起进程 → stdout 打到 `dsha-node` tag）
- `app/build.gradle.kts`：`androidResources.noCompress += zip/pem`、`packaging.jniLibs.useLegacyPackaging = true`
- `MainActivity.onCreate` 里启动 Node（暂不接管 WebView 代理）
- `port/build-payload.py`：重建全部产物；现需先运行 `./build.sh :netcore-js:jsNodeProductionLibraryDistribution` 编译共享 Kotlin 逻辑

### 产物

```
app-debug.apk  49 MB
  lib/arm64-v8a/libnodebin.so   46 MB   ← node 可执行文件
  assets/node-libs.zip          18 MB   ← 依赖 so
  assets/nodejs-project.zip     11 MB   ← main.js + 原版核心（6326 个文件）
  assets/cacert.pem            196 KB
```

### 真机验证结果（荣耀 PPG-AN00 / Android 16）

| 验证项 | 结果 |
|---|---|
| node 能否 exec | ✅ `代理服务已启动：127.0.0.1:31181, ssl: true` |
| LD_LIBRARY_PATH | ✅ 依赖全部加载，`npm` 级模块正常 require |
| SSL_CERT_FILE | ✅ 核心能发起 HTTPS（拉远程配置、测速都正常） |
| 端口外连（adb forward + curl） | ✅ github 200 / **google 200** / **youtube 200** |
| WebView 走核心代理 | ✅ 状态栏显示「端口 31181」，GitHub 完整渲染，无证书报错 |
| **App 内打开 Google** | ✅ **首页完整渲染**（搜索框、登录按钮都在） |

关键细节：核心默认会用**自己生成的 CA**，而 `network-security-config` 只信任我们打包的那张，
所以必须用 assets 里的 `dsha_ca.crt/key.pem` 覆盖到 `nodejs-project/home/.dev-sidecar/`。
覆盖后 WebView 才不再报 `net_error -202`。

### P2 已完成

- 代理切换：核心就绪后 `AccelManager` 自动改用 127.0.0.1:31181，未就绪时回退自研转发器
- 证书信任：`res/raw/dsha_ca.pem` + `network-security-config`，**只对本 App 生效**
- CA 固定：打包时生成一次，避免每次安装换 CA 导致信任失效

### 下载站实测（给以后取大文件用）

同一台机器、同一时段，10 秒内能跑完整个 54MB 的只有三个（≥5 MB/s）：

`https://ghproxy.sakuramoe.dev/`、`https://30006000.xyz/`、`https://gh.padao.fun/`

其余多数是 Cloudflare 前置（104.x / 172.67.x），这个网络到 CF 段不通，表现为 curl 000。
