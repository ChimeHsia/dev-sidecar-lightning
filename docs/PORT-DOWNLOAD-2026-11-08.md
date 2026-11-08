# 端口与下载核查（2026-11-08）

## 端口

浏览器优先绑定 127.0.0.1:31181，BindException 时依次尝试至 31200；整个范围失败才报告启动失败。
普通 WebView、隐身进程 Binder 和远程规则下载使用实际绑定端口。规则更新保持监听端口。
原生核心默认不启用递增，浏览器明确配置 19 次后续尝试；没有先探测再关闭端口的竞争窗口。

回归覆盖首选端口被占用后在下一端口启动、更新规则不改监听、禁用兜底时冲突失败、Binder 返回实际端口。

## 下载

下载从 AppCoroutineScope 的 launch 进入，原先未处理网络、存储和参数异常。现统一记录异常并显示下载失败提示；取消异常仍向上抛出以保留协程取消。
HEAD 元数据读取失败时继续使用 WebView 给出的原始信息，Content-Length 使用安全解析，响应 use 关闭。
data 图片损坏时明确失败，保存失败不登记成功，bitmap 在 finally 释放。

回归覆盖 IOException、SecurityException、NumberFormatException、IllegalArgumentException 不外溢，以及取消语义。
本轮未连接 Android 设备，不能把上述回归当作实际手机不会闪退的证明；大 data 图片内存等边界仍需设备验证。

系统 DownloadManager 文件传输不继承 WebView 的 ProxyController 配置，受限站点仍可能下载失败。本轮未替换系统下载器。

## Node 范围

主产品的运行时和 APK 已移除 Node；仓库的 port/node、Node 构建工具、差分测试和根 app 旧参考壳仍保留。
设备已有的 nodejs-project 目录为配置继承保留，尚未删除。当前原生构建不读取旧 Node 可执行文件或负载归档。

## 工作区

14 个附加 worktree HEAD 都已进入 main，但核查时 11 个存在未提交/未跟踪内容，仅 3 个干净。
没有删除或移动工作区。主目录还含被忽略的本机 CA 私钥、配置和工具链；GitHub 已推送不等于这些文件已备份。
核查摘要位于忽略的 port/.run/port-download-fix/worktree-audit.json，不能据此直接递归删除目录。

## 验证

- 原生核心：981 项，979 通过，2 项 opt-in live 跳过，0 失败。
- 浏览器 JVM：223 项通过，0 失败、0 跳过，包含下载异常与服务端口回归。
- Release 与 native-core lintRelease：最终 BUILD SUCCESSFUL；lint 0 errors、5 warnings。
- versionCode：104，APK 22,216,681 字节，内容检查 Node 0 项，保留 56 组规则和默认 ECH。
- APK SHA-256：`5141db2fbf3dc4c0e49a4c082f4d74d71d9b68c5bfd56e0fe32ad0dceaa678f4`。
- Python 工具测试 7 项通过，git diff --check 通过。
