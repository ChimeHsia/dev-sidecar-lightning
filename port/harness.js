#!/usr/bin/env node
/**
 * P0 验证脚本：让 dev-sidecar 核心脱离 Electron 在纯 Node 下运行。
 *
 * 用途：Android 移植前先证明"核心不依赖 Electron"，并暴露所有隐藏依赖。
 * 用法：node port/harness.js <解包后的 app 目录> [端口]
 *
 * 已被验证的结果（2026-11-01）：
 *   代理服务已启动 127.0.0.1:31281 ssl=true
 *   curl -x 127.0.0.1:31281 https://www.google.com.hk → 200（1.7s）
 */
const path = require('path')
const fs = require('fs')

const APP = path.resolve(process.argv[2] || '')
const PORT = Number(process.argv[3] || 31281)
if (!fs.existsSync(path.join(APP, 'node_modules/@blue-frontier/dev-sidecar'))) {
  console.error('用法: node port/harness.js <解包后的 app 目录> [端口]')
  process.exit(1)
}

// 核心用 os.homedir() 推导 ~/.dev-sidecar，必须在 require 之前改
const HOME = path.join(__dirname, '.run')
fs.mkdirSync(HOME, { recursive: true })
process.env.USERPROFILE = HOME
process.env.HOME = HOME
const BASE = path.join(HOME, '.dev-sidecar')
fs.mkdirSync(BASE, { recursive: true })

// 最小配置：关掉系统代理（Android 上没有这回事），端口可指定
fs.writeFileSync(path.join(BASE, 'config.json'), JSON.stringify({
  plugin: { node: { enabled: false }, git: { enabled: false } },
  proxy: { enabled: false },
  server: { enabled: true, port: PORT },
}, null, 2))

// ① 替代 child_process.fork：同进程启动 mitmproxy
const cp = require('child_process')
cp.fork = function (modulePath, args) {
  const server = require(path.join(APP, 'node_modules/@blue-frontier/mitmproxy'))
  const jsonApi = require(path.join(APP, 'node_modules/@blue-frontier/mitmproxy/src/json.js'))
  const config = jsonApi.parse(fs.readFileSync(args[0]).toString())
  config.setting.rootDir = path.join(APP, 'src')
  config.port = PORT
  const removed = stripNulls(config.intercepts)
  console.log(`[harness] fork → 同进程；剔除 null 规则 ${removed} 条`)
  server.start(config).catch((e) => console.error('[harness] 启动失败:', e && e.stack || e))
  return { pid: process.pid, connected: false, killed: false, exitCode: null, on() {}, once() {}, removeListener() {}, send() {} }
}

// ② 替代系统代理：Android 上由 ProxyController 负责
const DevSidecar = require(path.join(APP, 'node_modules/@blue-frontier/dev-sidecar'))
const shell = require(path.join(APP, 'node_modules/@blue-frontier/dev-sidecar/src/shell'))
shell.setSystemProxy = async (opt) => {
  console.log('[harness] 跳过系统代理设置', JSON.stringify(opt))
  return true
}

/** 远程配置用 null 表示"停用该条规则"，但 script.js 没判空会崩，启动前先剔掉 */
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

DevSidecar.api.startup({ mitmproxyPath: 'in-process', setting: { userBasePath: BASE, overwall: false } })
  .then(() => console.log(`[harness] 就绪 → 代理 127.0.0.1:${PORT}`))
  .catch((e) => console.error('[harness] startup 失败:', e && e.stack || e))
