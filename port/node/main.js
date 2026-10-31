/**
 * Android 入口：在 nodejs-mobile 里启动 dev-sidecar 原版核心。
 *
 * 目录布局（运行时）：
 *   <filesDir>/nodejs-project/
 *     ├─ main.js          ← 本文件
 *     ├─ core/            ← 解压出来的原版核心（等价于 app.asar 解包结果）
 *     └─ home/.dev-sidecar ← 配置与证书（替代 ~/.dev-sidecar）
 *
 * 与桌面版的差异只有 4 处，全部在这里打补丁，不改核心源码。
 */
const path = require('path')
const fs = require('fs')

const ROOT = __dirname
const CORE = path.join(ROOT, 'core')
const HOME = path.join(ROOT, 'home')
const BASE = path.join(HOME, '.dev-sidecar')
// 端口可由宿主 App 通过环境变量指定，避免与其它版本的同名 App 抢占同一端口
const PORT = parseInt(process.env.DSHA_PORT || '31181', 10)

fs.mkdirSync(BASE, { recursive: true })

// ① 核心用 os.homedir() 推导 ~/.dev-sidecar，必须先改环境变量再 require
process.env.HOME = HOME
process.env.USERPROFILE = HOME
process.env.DS_EXTRA_PATH = path.join(CORE, 'extra')

// ② 最小配置：关掉系统代理与所有插件（Android 上要么无意义，要么由 App 侧接管）
if (!fs.existsSync(path.join(BASE, 'config.json'))) {
  fs.writeFileSync(path.join(BASE, 'config.json'), JSON.stringify({
    app: { closeStrategy: 2 },
    proxy: { enabled: false },
    plugin: {
      node: { enabled: false },
      git: { enabled: false },
      pip: { enabled: false },
      share: { enabled: false },
      free_eye: { enabled: false },
      overwall: { enabled: false },
    },
    server: { enabled: true, port: PORT },
  }, null, 2))
}

/** 远程配置用 null 表示"停用该条规则"，但 mitmproxy 的 script.js 未判空，会崩 */
function stripNulls (intercepts) {
  let n = 0
  for (const host of Object.keys(intercepts || {})) {
    const paths = intercepts[host]
    if (paths == null) { delete intercepts[host]; n++; continue }
    for (const p of Object.keys(paths)) {
      if (paths[p] == null) { delete paths[p]; n++ }
    }
  }
  return n
}

// ③ 用同进程启动替代 child_process.fork（Android 上没有 fork 能力）
const cp = require('child_process')
cp.fork = function (modulePath, args) {
  const server = require(path.join(CORE, 'node_modules/@blue-frontier/mitmproxy'))
  const jsonApi = require(path.join(CORE, 'node_modules/@blue-frontier/mitmproxy/src/json.js'))
  const config = jsonApi.parse(fs.readFileSync(args[0]).toString())
  config.setting.rootDir = path.join(CORE, 'src')
  config.port = PORT
  console.log('[android] 剔除 null 规则', stripNulls(config.intercepts), '条')
  server.start(config).catch((e) => console.error('[android] mitmproxy 启动失败', e && e.stack || e))
  return {
    pid: process.pid, connected: false, killed: false, exitCode: null,
    on () {}, once () {}, removeListener () {}, send () {},
  }
}

const DevSidecar = require(path.join(CORE, 'node_modules/@blue-frontier/dev-sidecar'))

// ④ 系统代理：Android 上由 App 的 ProxyController 负责，这里置空
const shell = require(path.join(CORE, 'node_modules/@blue-frontier/dev-sidecar/src/shell'))
shell.setSystemProxy = async () => true
shell.closeSystemProxy = async () => true

process.on('uncaughtException', (e) => console.error('[android] 未捕获异常', e && e.stack || e))

console.log('[android] 核心', require(path.join(CORE, 'node_modules/@blue-frontier/dev-sidecar/package.json')).version,
  '启动中，端口', PORT)

DevSidecar.api.startup({ mitmproxyPath: 'in-process', setting: { userBasePath: BASE, overwall: false } })
  .then(() => console.log('[android] 就绪 →', `127.0.0.1:${PORT}`))
  .catch((e) => console.error('[android] startup 失败', e && e.stack || e))
