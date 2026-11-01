/**
 * Android 入口：在 nodejs-mobile 里启动 dev-sidecar 原版核心。
 *
 * 目录布局（运行时）：
 *   <filesDir>/nodejs-project/
 *     ├─ main.js          ← 本文件
 *     ├─ core/            ← 解压出来的原版核心（等价于 app.asar 解包结果）
 *     └─ home/.dev-sidecar ← 配置与证书（替代 ~/.dev-sidecar）
 *
 * 宿主适配在此安装；纯逻辑调用共享 Kotlin/JS。
 * NAT64 的内部闭包由 payload 打包工具接到同一编译产物。
 */
const path = require('path')
const fs = require('fs')

const ROOT = __dirname
const CORE = path.join(ROOT, 'core')
const HOME = path.join(ROOT, 'home')
const BASE = path.join(HOME, '.dev-sidecar')
// 端口可由宿主 App 通过环境变量指定，避免与其它版本的同名 App 抢占同一端口
const PORT = parseInt(process.env.DSHA_PORT || '31181', 10)
let failed = false
function fail (error) {
  if (failed) return
  failed = true
  console.error('[android] FAILED', error && error.stack || error)
  // The in-process core owns timers and sockets: logging alone leaves a dead
  // proxy process alive and prevents the Android host from observing failure.
  process.exit(1)
}
let proxyReady
let proxyFailed
const proxyStartup = new Promise((resolve, reject) => {
  proxyReady = resolve
  proxyFailed = reject
})

fs.mkdirSync(BASE, { recursive: true })

// ① 核心用 os.homedir() 推导 ~/.dev-sidecar，必须先改环境变量再 require
process.env.HOME = HOME
process.env.USERPROFILE = HOME
process.env.DS_EXTRA_PATH = path.join(CORE, 'extra')
process.env.DSHA_KOTLIN_HELPERS = path.join(ROOT, 'kotlin', 'dev-sidecar-lightning-netcore-js.js')
const kotlinHelpers = require(process.env.DSHA_KOTLIN_HELPERS)

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

const matchContext = require('./kotlin-helpers').install(CORE, kotlinHelpers)
require('./kotlin-wave2').install(CORE, kotlinHelpers, matchContext)
console.log('[android] Kotlin helpers active: hostname, config, NAT64, hostname-all, path-rules, dynamic-choice, request-transform')

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
  require('./http-navigation').install(CORE)
  const jsonApi = require(path.join(CORE, 'node_modules/@blue-frontier/mitmproxy/src/json.js'))
  const config = jsonApi.parse(fs.readFileSync(args[0]).toString())
  config.setting.rootDir = path.join(CORE, 'src')
  config.port = PORT
  console.log('[android] 剔除 null 规则', stripNulls(config.intercepts), '条')
  require('./proxy-runtime').start(CORE, config).then((servers) => {
    for (const server of servers) {
      server.on('error', fail)
      server.once('close', () => fail(new Error('Proxy listener closed')))
    }
    proxyReady()
  }, proxyFailed)
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

process.on('uncaughtException', fail)

console.log('[android] 核心', require(path.join(CORE, 'node_modules/@blue-frontier/dev-sidecar/package.json')).version,
  '启动中，端口', PORT)

const startupTimeout = setTimeout(() => proxyFailed(new Error('Core startup timed out')), 15000)
Promise.all([
  Promise.resolve().then(() =>
    DevSidecar.api.startup({ mitmproxyPath: 'in-process', setting: { userBasePath: BASE, overwall: false } })),
  proxyStartup,
]).then(() => {
  clearTimeout(startupTimeout)
  if (!failed) console.log(`[android] READY 127.0.0.1:${PORT}`)
}, (error) => {
  clearTimeout(startupTimeout)
  fail(error)
})
