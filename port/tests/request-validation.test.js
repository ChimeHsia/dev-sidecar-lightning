'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const http = require('node:http')
const net = require('node:net')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')
const { createRequire } = require('node:module')
const { test } = require('node:test')
const patches = require('../node/request-validation-patches.json')
const { install } = require('../node/kotlin-request-runtime')

const project = process.env.DSHA_TEST_PROJECT && path.resolve(process.env.DSHA_TEST_PROJECT)
const enabled = { skip: !project && 'Set DSHA_TEST_PROJECT to the full extracted v9 runtime with the request options hook.' }
const httpPath = 'core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/createRequestHandler.js'
const upgradePath = 'core/node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/createUpgradeHandler.js'
let context

function patched(source, pairs) {
  source = source.replace(/\r\n/g, '\n')
  for (const { old, new: replacement } of pairs) {
    if (source.includes(replacement)) {
      assert.equal(source.split(replacement).length - 1, 1)
      assert.equal(source.replace(replacement, '').includes(old), false)
    } else {
      assert.equal(source.split(old).length - 1, 1, `Changed validation patch target: ${old}`)
      source = source.replace(old, replacement)
    }
  }
  return source
}

function runtime() {
  if (context) return context
  // Exercise the local adapter with actual packaged dependencies and compiled
  // helpers. Do not install the older request adapter bundled in a v9 archive.
  process.env.HOME = path.join(project, 'home')
  process.env.USERPROFILE = process.env.HOME
  process.env.DEV_SIDECAR_LOG_DISABLED = 'true'
  const coreRoot = path.join(project, 'core')
  const helpers = require(process.env.DSHA_KOTLIN_HELPERS
    ? path.resolve(process.env.DSHA_KOTLIN_HELPERS)
    : path.join(project, 'kotlin/dev-sidecar-lightning-netcore-js.js'))
  require(path.join(project, 'kotlin-helpers.js')).install(coreRoot, helpers)
  install(coreRoot, helpers)
  const root = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src')
  const util = require(path.join(root, 'lib/proxy/common/util.js'))
  const match = require(path.join(root, 'utils/util.match.js'))
  context = { util, match }
  return context
}

function factories(t) {
  const { util, match } = runtime()
  const stats = { guards: new Set(), fired: 0, intercepts: 0, upgrades: 0, requests: 0 }
  const agents = new Set()
  function load(relative) {
    const file = path.join(project, relative)
    const raw = fs.readFileSync(file, 'utf8')
    const source = patched(raw, patches[relative])
    if (process.env.DSHA_EXPECT_PACKAGED_VALIDATION === '1') {
      assert.equal(source, raw.replace(/\r\n/g, '\n'), 'Packaged runtime lacks the rejection guard')
    }
    assert.equal(patched(source, patches[relative]), source)
    const module = { exports: {} }
    const timed = (fn, delay, ...args) => {
      if (delay !== 60000) return setTimeout(fn, delay, ...args)
      const timer = setTimeout(() => {
        stats.guards.delete(timer)
        stats.fired++
        fn(...args)
      }, 80)
      stats.guards.add(timer)
      return timer
    }
    const cleared = timer => {
      stats.guards.delete(timer)
      clearTimeout(timer)
    }
    // Real source and imports; only shorten/instrument the existing guard to
    // prove it cannot later fire with an uninitialized request URL.
    vm.runInThisContext(`(function(require, module, exports, setTimeout, clearTimeout) {\n${source}\n})`, {
      filename: file,
    })(createRequire(file), module, module.exports, timed, cleared)
    return module.exports
  }
  const settings = {
    timeoutMapping: match.domainMapRegexply({}),
    tlsVersionMapping: {},
    defaultTimeout: 1500,
    defaultKeepAliveTimeout: 100,
    verifySsl: true,
    script: { enabled: false },
  }
  const handler = load(httpPath)(context => {
    stats.intercepts++
    if (context.rOptions.agent) agents.add(context.rOptions.agent)
    return []
  }, [], null, {}, settings, null)
  const upgrade = load(upgradePath)(settings)
  t.after(() => {
    for (const timer of stats.guards) clearTimeout(timer)
    for (const agent of agents) {
      agent.destroy()
      agent.unVerifySslAgent?.destroy()
    }
  })
  return { handler, upgrade, stats, util }
}

async function listen(t, server) {
  const sockets = new Set()
  server.on('connection', socket => {
    sockets.add(socket)
    socket.on('error', () => {})
    socket.once('close', () => sockets.delete(socket))
  })
  t.after(() => {
    for (const socket of sockets) socket.destroy()
    server.close()
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return server.address().port
}

function rawRequest(port, text) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let data = ''
    socket.setTimeout(1500, () => socket.destroy(new Error('Validation response timed out')))
    socket.once('connect', () => socket.write(text))
    socket.on('data', chunk => {
      data += chunk.toString('latin1')
      if (data.length > 8192) socket.destroy(new Error('Unbounded validation response'))
    })
    socket.on('error', reject)
    socket.once('end', () => {
      socket.destroy()
      resolve(data)
    })
  })
}

async function listeners(t, r) {
  const origin = http.createServer((req, res) => {
    r.stats.requests++
    res.end(`ok:${req.url}`)
  })
  origin.on('upgrade', (_req, socket) => {
    r.stats.upgrades++
    socket.end('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\nready')
  })
  const originPort = await listen(t, origin)
  const proxy = http.createServer((req, res) => {
    // Wire-valid headers are already strings/lists. Inject an invalid map value
    // into a real IncomingMessage to cover the adapter's stricter map boundary.
    if (req.url === '/invalid-header-map') req.headers['x-test'] = ['value', false]
    r.handler(req, res, false)
  })
  proxy.on('upgrade', (req, socket, head) => r.upgrade(req, socket, head, false))
  const proxyPort = await listen(t, proxy)
  const host = `127.0.0.1:${originPort}`
  const valid = () => rawRequest(proxyPort,
    `GET /valid%2F?q=%26 HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\n\r\n`)
  return { originPort, proxyPort, host, valid }
}

test('validation manifest uses payload paths and strict replace-once patches', () => {
  assert.deepEqual(Object.keys(patches), [httpPath, upgradePath])
  for (const pairs of Object.values(patches)) {
    assert.equal(pairs.length, 1)
    for (const pair of pairs) {
      assert.deepEqual(Object.keys(pair), ['old', 'new'])
      assert.equal(patched(pair.old, pairs), pair.new)
      assert.equal(patched(pair.new, pairs), pair.new)
      assert.throws(() => patched('changed upstream', pairs), /Changed validation patch target/)
    }
  }
})

test('real HTTP listener bounds invalid URL/Host/header requests and still forwards the next request', enabled, async t => {
  const r = factories(t)
  const { proxyPort, host, valid } = await listeners(t, r)
  const invalid = [
    // No body is sent: rejection must not wait to buffer/drain this body.
    `GET /bad%escape HTTP/1.1\r\nHost: ${host}\r\nContent-Length: 9999\r\nConnection: keep-alive\r\n\r\n`,
    'GET / HTTP/1.1\r\nHost: example.test:80junk\r\nConnection: close\r\n\r\n',
    'GET / HTTP/1.1\r\nHost: [invalid-ipv6]\r\nConnection: close\r\n\r\n',
    `GET / HTTP/1.1\r\nHost: ${host}\r\nHOST: second.test\r\nConnection: close\r\n\r\n`,
    `GET /invalid-header-map HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\n\r\n`,
  ]
  for (const text of invalid) {
    const before = { requests: r.stats.requests, intercepts: r.stats.intercepts }
    const response = await rawRequest(proxyPort, text)
    assert.match(response, /^HTTP\/1\.1 400 Bad Request\r\n/)
    assert.match(response, /\r\nConnection: close\r\n/i)
    assert.match(response, /\r\nContent-Length: 12\r\n/i)
    assert.equal(response.split('\r\n\r\n')[1], 'Bad Request\n')
    assert.equal(r.stats.requests, before.requests)
    assert.equal(r.stats.intercepts, before.intercepts)
    assert.equal(r.stats.guards.size, 0)
    const next = await valid()
    assert.match(next, /^HTTP\/1\.1 200 OK\r\n/)
    assert.ok(next.endsWith('ok:/valid%2F?q=%26'))
    assert.equal(r.stats.requests, before.requests + 1)
  }
  await new Promise(resolve => setTimeout(resolve, 120))
  assert.equal(r.stats.guards.size, 0)
  assert.equal(r.stats.fired, 0)
})

test('real upgrade listener returns minimal 400 and accepts subsequent HTTP and upgrade requests', enabled, async t => {
  const r = factories(t)
  const { proxyPort, host, valid } = await listeners(t, r)
  for (const [target, authority] of [['/bad%escape', host], ['/', 'example.test:0']]) {
    const response = await rawRequest(proxyPort,
      `GET ${target} HTTP/1.1\r\nHost: ${authority}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n`)
    assert.equal(response, 'HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
    assert.equal(r.stats.upgrades, 0)
    const next = await valid()
    assert.match(next, /^HTTP\/1\.1 200 OK\r\n/)
    assert.ok(next.endsWith('ok:/valid%2F?q=%26'))
  }
  // Upstream normalizes Connection without a close token to keep-alive.
  const upgraded = await rawRequest(proxyPort,
    `GET /valid-upgrade HTTP/1.1\r\nHost: ${host}\r\nConnection: Upgrade, close\r\nUpgrade: websocket\r\n\r\n`)
  assert.match(upgraded, /^HTTP\/1\.1 101 Switching Protocols\r\n/)
  assert.ok(upgraded.endsWith('ready'))
  assert.equal(r.stats.upgrades, 1)
  assert.equal(r.stats.fired, 0)
})

test('both real handlers rethrow unrelated failures without turning them into 400 responses', enabled, t => {
  const r = factories(t)
  const original = r.util.getOptionsFromRequest
  const failure = new Error('programming failure')
  r.util.getOptionsFromRequest = () => { throw failure }
  t.after(() => { r.util.getOptionsFromRequest = original })
  const req = { url: '/', method: 'GET', headers: { host: 'example.test' }, socket: {} }
  const response = new EventEmitter()
  response.writeHead = () => assert.fail('Unrelated failures must not produce a 400')
  response.end = () => assert.fail('Unrelated failures must not be swallowed')
  assert.throws(() => r.handler(req, response, false), error => error === failure)
  assert.equal(r.stats.guards.size, 0)
  const socket = { end() { assert.fail('Unrelated failures must not produce an upgrade 400') } }
  assert.throws(() => r.upgrade(req, socket, Buffer.alloc(0), false), error => error === failure)
})
