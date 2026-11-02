# payload 8 启动与 TLS 修复真机验收

2026-11-02（Asia/Singapore），荣耀 PPG-AN00，Android 16 / API 36，
Google WebView 138.0.7204.179。包名 `com.dsha.browser`，
LightningPlus release / arm64-v8a，versionCode 102。

## 结论与范围

本轮针对 [离线修复](OFFLINE-FIXES-2026-11-01.md) 验证：
正常监听后才发出 READY；端口占用时发出 FAILED 并结束 Node，没有误报成功；
移除占用后，下一次导航在同一 Android 主进程内重启核心并恢复页面。

之前反复失败的 Python Google HK `Connection: close` 请求本轮两次均返回 200，
keep-alive 请求也返回 200。页面、证书、导航、前后台、冷启和隐身冒烟通过。
GitHub 仍有连接超时重试，最终成功；没有据此认定所有线路超时已消除。

第二波四个模块没有接入或参与本次负载。设备重启、干净安装、长时间后台和压力场景未测。

## 可复查的版本

- 基础提交 `754ff42`，加工作区未提交的启动/TLS 修复，payload 标记 8。
- APK：`port/.run/device-v8-20261102/lightning-startup-tls-v8.apk`
- APK SHA-256：`ea3726c95d37289493daf3dad86f063d7b576a83a96f3827fda270d8c5c68672`
- 内嵌项目 zip SHA-256：`d7a1c8d256272831698a03192997e9e15b1823a688d136f345f51986aacba981`
- 旧安装包 SHA-256：`75a3a7dddbabb0a79dff977c5c66690b3f619c3752c179eb3d25f690879b279d`，已备份。

测试通过独立 assets 目录构建，没有覆盖根 `app/` 或 Lightning 的原 zip。
Kotlin 库使用第一波已验证编译产物，确认没有 `wave2HostnameAll`。
已核对项目 zip 中入口、监听辅助、导航及适配脚本与当前源码一致，
包含 TLS 补丁；APK 内 zip 逐字节一致，DEX 含 `.v8.done`，手机安装包哈希一致。

新旧签名一致，CA/key/cacert 字节及 App 信任证书 DER 一致。
使用 `adb install -r` 保留数据，没有卸载、清数据、更改导航规则或系统代理。

## 执行结果

| 项目 | 观察 | 判定 |
|---|---|---|
| 升级后首次启动 | 两个 zip 解压；监听日志、精确 READY、WebView 回调依次出现，无 FAILED | 通过；非干净安装 |
| GitHub Trending 页面 | 标题、仓库列表、star 控件和安全锁可见，有对应核心 200 请求 | 页面通过，仍有连接超时重试 |
| Google CN 搜索 | `https://google.cn/search?q=kotlin` 最终显示 HK 站 Kotlin 结果 | 通过 |
| Python HTTPS/close | GitHub 200；Google HK close / keep-alive / close 均 200；全部校验 CA/主机名 | 通过本轮样本 |
| HTTPS 导航 | google.cn 和 www.google.cn 文档请求返回 307，保留路径和 `%20` 查询 | 通过 |
| 受控端口冲突 | shell 临时监听占用 31181；观察 EADDRINUSE、FAILED、Node 退出，没有 READY 或核心监听成功 | 通过修复目标 |
| 释放端口后重试 | 不强停应用，不重装，仅再次导航；主进程 PID 不变，新 Node 监听、READY、代理回调和页面恢复 | 通过导航触发恢复 |
| Home 后返回 | 主进程和 Node PID 保持，搜索页面内容正常，有新 200 请求 | 通过 |
| 强停及普通冷启 | 主/隐身/Node 与 31181 释放；再次启动成功，同版本未重复解压 | 通过 |
| 隐身 | 独立隐身进程显示 HK 首页并设置代理；全程只有主进程的一个 Node | 通过已有主进程场景 |
| 导航配置 | 与安装前逐字节一致，哈希保持 | 通过 |

冲突时仍可能看到提前设置的 WebView 代理回调，那只代表代理地址配置，并非核心就绪。
本轮判据是无 READY、失败进程退出，以及之后同主进程重试成功，未把该回调当成错误的就绪信号。
没有测试“移除冲突后无任何用户导航也自动恢复”，目前实现按下一次普通网络导航重试。

Python 请求均信任显式 App CA，无 `-k` 或关闭校验：

| 目标 | Connection | 状态 | 用时 |
|---|---|---:|---:|
| GitHub Trending | close | 200 | 15.344 s |
| Google HK | close | 200 | 2.188 s |
| Google HK | keep-alive | 200 | 1.718 s |
| Google HK | close | 200 | 2.219 s |
| google.cn 搜索 | close | 307 | 0.141 s |
| www.google.cn 搜索 | close | 307 | 0.031 s |

这些是短时样本，未做同时间旧版/新版随机对照；不能据此排除网络波动，
也没有对上游实际协商的所有 TLS 参数做运行时追踪。

## 环境、清理与留存

开始时 `top.panming.dsbrowser` 的 Node 与目标浏览器同时存在。
强停目标浏览器后 31181 仍监听，临时停止该竞争包后端口释放；
因此本轮将该包保持停止以免抢占端口，没有卸载或修改其数据。

测试监听器已精确结束，本次创建的 ADB 转发和菜单 XML 已清理。
最后强停一次确认全部本包进程及端口释放，再启动普通浏览器并保留 payload 8。
没有更改默认浏览器或全局网络配置；测试增加的公开网页标签保留，
未批量关闭原有标签；最终强停已结束测试隐身进程。

导航配置 SHA-256：`3f74e0956008f2c7ad31fc1bec95c2e061ea45404e3f06ce6a862fe8bdeca254`。
原 assets zip 仍为旧负载，下次普通构建前仍需生成/同步，不能只依赖源码标记。
测试 APK、原包、JSON 和截图保存在被忽略的 `port/.run/device-v8-20261102/`；
不提交负载、私钥或设备原始材料。
