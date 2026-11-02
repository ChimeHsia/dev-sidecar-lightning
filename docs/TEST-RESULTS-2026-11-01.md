# 合并后回归测试记录

日期：2026-11-01。测试源码为主工作区 `main` 的 `754ff423b59c743cf94e68b3417c1fde4a6f42b7`。
使用仓库内 JDK 21.0.2、根项目 Gradle 8.14、Lightning wrapper Gradle 9.8.2 和本机 Node。
本次没有安装 APK、操作手机、替换现有 assets 或更改生产代码。

## 最终结果

| 范围 | 通过 | 跳过 | 失败 |
|---|---:|---:|---:|
| Kotlin/JVM `netcore` 全套 | 86 | 2 | 0 |
| Lightning Debug JVM 全套 | 70 | 0 | 0 |
| Node 入口契约 | 17 | 0 | 0 |
| HTTP/HTTPS 导航规则 | 10 | 0 | 0 |
| Kotlin/JS 适配与真实编译产物 | 19 | 0 | 0 |
| 隔离负载的真实核心集成 | 4 | 0 | 0 |
| Python 负载工具 | 3 | 0 | 0 |
| 合计 | 209 | 2 | 0 |

两项跳过均为需显式开启的 `LiveAccelSmokeTest`，未访问外网。
Kotlin/JS production library 构建成功。JVM 数量由最终 JUnit XML 汇总，Node/Python 数量来自各次测试进程。
首轮未提供真实 Kotlin/JS 路径时跳过的五项，已在编译后重新执行并全部通过，不计为最终跳过。

## 命令与执行边界

根目录执行：

```powershell
.\scripts\test-baseline.ps1 -Suite All -Offline
python -m unittest discover -s port/tests -p 'test_*.py' -v
node --test --unhandled-rejections=strict port/tests/http-navigation.test.js
# 使用本机 Gradle 8.14，并配置 JAVA_HOME 为 JDK 21：
gradle :netcore-js:jsNodeProductionLibraryDistribution --offline
$env:DSHA_KOTLIN_HELPERS = "$PWD/netcore-js/build/dist/js/productionLibrary/dev-sidecar-lightning-netcore-js.js"
node --test --unhandled-rejections=strict port/tests/kotlin-helpers.test.js
# 此路径必须包含更新后的适配层、Kotlin 模块和真实 core：
$env:DSHA_TEST_PROJECT = '<isolated-project>'
node --test --unhandled-rejections=strict port/tests/runtime-integration.test.js
```

当前统一脚本的 Node 分支只收集 `*.test.cjs`，不自动包含新增 `.test.js`、Python、Kotlin/JS 编译或真实核心测试。
本轮已额外执行这些步骤；不能仅凭原脚本的 `All` 标签声称覆盖了它们。
Kotlin/JS 产物与真实核心均来自本次主工作区测试准备，没有借用另一 worktree 的编译产物。

## 浏览器内存重试

初次全套测试因 Windows 提交内存不足而中断，属于 JVM 进程失败，不是测试断言失败。
首次降低堆内存的重试仍失败；检查实际 JVM 参数发现 Android 单测配置覆盖了早期追加的 GC 参数。
最终以临时 Gradle init script 在任务图准备后设置参数，完整 70 项通过：

- Gradle：单次 daemon、单 worker、堆上限 512 MB、Serial GC、两个可见处理器。
- 单测：单 fork、堆 32–384 MB、Serial GC、两个可见处理器、code cache 64 MB。
- 保留既有 JVM `--add-opens`；关闭配置缓存和 isolated projects。

项目构建配置和测试断言没有为此次重试修改。既有 `PreferenceDataStoreRule` 的 Windows 修复仍有效，最终未出现此前的 DataStore 文件重命名失败。

最终报告：

- `netcore/build/test-results/test/`：12 份 XML，88 项，2 跳过。
- `lightning-browser/app/build/test-results/testLightningPlusDebugUnitTest/`：24 份 XML，70 项，无跳过。

## 隔离负载校验

将旧 `app/src/main/assets/nodejs-project.zip` 复制到被忽略的测试目录，再通过 `port/kotlin_payload.py` 更新副本。
已核对入口、导航脚本、规则和 Kotlin 编译产物逐字节对应当前文件，无重复 zip 条目，并含 NAT64 内部函数替换。
集成测试验证初始化配置合并、清理、域名匹配原值引用、多结果匹配保留，以及 NAT64 内部调用实际委派给 Kotlin；DNS 查询使用固定本地测试数据。

- 原 zip SHA-256：`b49ae9e6af4d9f1a0c35d8b13970531fbae3e539dd4ef3f745848aa743825cf0`
- 测试 zip SHA-256：`558486a7603a30c85eabdf4224d7e87f0fadf4ba419bd62857f61e28a33f3c43`
- 原 zip 测后哈希不变；Lightning assets 中的旧负载也未替换。
- 本机证据、临时 init script、更新后的 zip 和解压核心位于 `port/.run/merged-regression-20261101-1328eb6e/`，不纳入 Git。

## 尚未验收

后续已执行部分真机验证，结果及未通过项见 [真机记录](DEVICE-RESULTS-2026-11-01.md)。以下描述本地测试结束时的范围。

本轮结论限于主工作区的本地回归、Kotlin/JS 构建和真实核心的隔离集成。
没有构建并安装使用此测试负载的新 APK，也没有重新验证 WebView、证书信任、页面渲染、隐身或生命周期。
交接中的原版核心真机结果不能替代 Kotlin/JS 负载的设备验收；后续打包须核对实际 assets 和 `PAYLOAD_VERSION`。
