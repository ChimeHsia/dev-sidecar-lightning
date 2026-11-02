# 代理启动与 TLS 问题的离线修复

后续于 2026-11-02 完成 payload 8 的定向真机验收：端口冲突不再误报 READY、同主进程导航重试恢复、Python close 请求及页面通过。范围与剩余限制见 [真机记录](DEVICE-RESULTS-2026-11-02.md)。以下保留修复开发阶段的离线范围。

本轮在 `754ff42` 后的工作区修改上修复问题。未调用 ADB、操作手机、访问外网站点、覆盖现有 assets 或安装 APK。
此前真机版本为 payload 7；当前源码使用 payload 8，参考壳的独立解压标记升为 5。设备结果仍以旧记录为准。

## 启动状态

上游 `server.start()` 返回时，真正的 `server.listen()` 可能尚未完成。旧入口仅等待核心初始化，端口占用时仍会打印就绪。

- 新增 `port/node/proxy-runtime.js`，捕获本轮创建的服务器，等待实际 `listening` 或 `error/close`，并设置绑定超时。失败清理本轮监听及上游资源，不关闭竞争者。
- 只有核心初始化与监听都成功后，入口才输出精确协议行 `[android] READY 127.0.0.1:31181`。
- 初始化失败、监听错误、监听关闭或未捕获异常输出 `[android] FAILED` 并非零退出；避免失效进程只写日志却仍驻留。
- Android 不再匹配任意“就绪”字样。失败或退出时复位状态、释放等待者；下一次普通进程网络导航可以发起新的启动。
- 启动代次隔离过期的代理回调。旧进程结束、旧 clear/set 回调及旧等待者不能把新启动标成成功。显式 stop 禁止自动重启；隐身进程不启动 Node。
- 资源准备串行执行，避免停止与重试期间两个启动线程同时解压。

本地测试使用真实回环端口复现 `EADDRINUSE`，并覆盖延迟监听、初始化失败后清理、无监听、监听超时、就绪后错误、失败退出及过期回调。
Android 原生进程和 WebView 的整体恢复行为尚未重新验证，不能只凭状态门测试声称真机自动恢复已通过。

## 可复现的 TLS 缺陷

1. **Connection: close 改变 TLS 策略。** 上游为该请求设置 `agent=false`，丢失配置代理上的 TLS 最低版本及相关策略。旧代码在同一台本地 TLS 1.2 服务上，keep-alive 拒绝连接，close 却可成功；修复后两者一致拒绝。
2. **连接超时没有覆盖 TLS 握手。** TCP connect 后就移除计时器，而 TLS 可能仍未完成，最终落到“上游无响应”兜底。测试使用只接受 TCP、不完成 TLS 的本地服务复现；修复后握手阶段仍有连接超时，secureConnect 后才结束计时。

修改由 `port/kotlin_payload.py` 在负载生成/更新时精确应用于上游请求选项及处理器；`build-payload.py` 和 `update_project()` 均使用同一实现。
补丁可重复应用，遇到不支持的上游布局直接失败，不静默保留旧代码。未修改被提取的原版核心副本。

close 请求仍发送关闭语义，回归确认两次请求建立两个 TLS 连接；可信证书成功，错误主机名仍被拒绝。
正常 TLS 握手完成后的慢响应体不会被连接计时器截断。测试只缩短本地测试中的计时器，生产超时数值不变。

这些是已复现并修复的具体缺陷；尚未证明它们解释全部 Google Python 客户端超时或 GitHub 偶发无响应。
DNS/IP 候选反馈、真实线路变化及其他客户端差异仍可能影响访问，后续需受控对照及经授权的设备复测。

## 验证与复现

- Node 共 63 项通过：入口/监听 26、导航 10、Kotlin/JS 适配 19、真实核心集成 4、传输层 4。
- Python 工具测试 4 项通过，覆盖传输补丁、幂等性及不兼容上游拒绝。
- 浏览器完整 JVM 套件 73 项通过，含新增 3 项启动状态回归；参考壳 `:app:compileDebugKotlin` 成功。
- 真实核心、Kotlin/JS 与新入口打成隔离负载并解压，证据位于被忽略的 `port/.run/offline-proxy-fixes-20261101/`。

从仓库根目录运行：

```powershell
node --test --unhandled-rejections=strict port/tests/node-entrypoint.test.cjs port/tests/proxy-runtime.test.cjs
python -m unittest discover -s port/tests -p 'test_*.py' -v
$env:DSHA_TEST_PROJECT = '<extracted-fixed-payload>'
$env:DSHA_KOTLIN_HELPERS = "$env:DSHA_TEST_PROJECT/kotlin/dev-sidecar-lightning-netcore-js.js"
node --test --unhandled-rejections=strict port/tests/proxy-transport.test.js port/tests/runtime-integration.test.js port/tests/kotlin-helpers.test.js port/tests/http-navigation.test.js
```

`proxy-transport.test.js` 没有真实核心目录时会明确跳过；没有编译产物时，Kotlin 适配测试中的真实实现用例也会跳过，不能把跳过算作修复验证。
统一脚本的 Node 分支仍只自动收集 `.test.cjs`，运行 `-Suite All` 不包含全部上述外部夹具测试。浏览器本轮沿用临时低内存 init script；未更改系统内存或 Gradle 全局配置。

之后打包须重新生成/同步 payload，确认包含 `proxy-runtime.js`、新入口协议与上游补丁。仅更新 Kotlin 源码标记或重建 Android APK，不会自动刷新被忽略的 zip。
