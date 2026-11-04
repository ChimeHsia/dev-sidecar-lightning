'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const http = require('node:http')
const https = require('node:https')
const net = require('node:net')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { test } = require('node:test')

const project = process.env.DSHA_TEST_PROJECT
const enabled = { skip: !project && 'Set DSHA_TEST_PROJECT to the extracted, patched payload' }
const core = project && path.resolve(project, 'core/node_modules/@blue-frontier/mitmproxy/src')
if (project) {
  process.env.HOME = path.resolve(project, 'home')
  process.env.USERPROFILE = process.env.HOME
  process.env.DEV_SIDECAR_LOG_DISABLED = 'true'
  fs.mkdirSync(process.env.HOME, { recursive: true })
  const helpers = require(path.resolve(project, 'kotlin/dev-sidecar-lightning-netcore-js.js'))
  const context = require(path.resolve(project, 'kotlin-helpers.js')).install(path.resolve(project, 'core'), helpers)
  require(path.resolve(project, 'kotlin-wave2.js')).install(path.resolve(project, 'core'), helpers, context)
}

function settings(match, extra = {}) {
  return {
    timeoutMapping: match.domainMapRegexply({}),
    tlsVersionMapping: {},
    defaultTimeout: 100,
    defaultKeepAliveTimeout: 100,
    allowTls12: false,
    verifySsl: true,
    script: { enabled: false },
    ...extra,
  }
}

async function listen(server, t) {
  const sockets = new Set()
  server.on('connection', socket => {
    sockets.add(socket)
    socket.once('close', () => sockets.delete(socket))
  })
  t.after(() => {
    for (const socket of sockets) socket.destroy()
    server.close()
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return server.address().port
}

function tlsFixture(requireCore) {
  const forge = requireCore('node-forge')
  const pfx = fs.readFileSync(path.resolve(__dirname, '../../netcore/src/test/resources/tls/test.p12'))
  const parsed = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(pfx.toString('binary')), 'changeit')
  const cert = parsed.getBags({ bagType: forge.pki.oids.certBag })[forge.pki.oids.certBag][0].cert
  return { pfx, ca: forge.pki.certificateToPem(cert) }
}

function loopbackLookup(_host, options, done) {
  if (options.all) done(null, [{ address: '127.0.0.1', family: 4 }])
  else done(null, '127.0.0.1', 4)
}

function request(options) {
  return new Promise((resolve, reject) => {
    const client = (options.protocol === 'https:' ? https : http).request(options, response => {
      const chunks = []
      response.on('data', chunk => chunks.push(chunk))
      response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString() }))
      response.on('error', reject)
    })
    client.on('error', reject)
    client.setTimeout(1500, () => client.destroy(new Error('Local regression request timed out')))
    client.end()
  })
}

function handlerFactory() {
  const modulePath = path.join(core, 'lib/proxy/mitmproxy/createRequestHandler.js')
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(modulePath, 'utf8'), {
    require: createRequire(modulePath), module, exports: module.exports, Buffer, process, console,
    clearTimeout,
    // Preserve production phases while making deterministic local failures fast.
    setTimeout: (fn, delay, ...args) => setTimeout(fn, delay === 7000 ? 30 : delay === 60000 ? 300 : delay, ...args),
  }, { filename: modulePath })
  return module.exports
}

test('Connection close preserves the same TLS minimum as keep-alive', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const util = requireCore('./lib/proxy/common/util')
  const match = requireCore('./utils/util.match')
  const { pfx, ca } = tlsFixture(requireCore)
  const server = https.createServer({
    pfx, passphrase: 'changeit', minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2',
  }, (_req, res) => res.end('TLS 1.2 must not be accepted'))
  server.on('tlsClientError', () => {})
  const port = await listen(server, t)
  for (const connection of ['keep-alive', 'close', 'Close, upgrade']) {
    const options = util.getOptionsFromRequest({
      method: 'GET', url: '/', socket: {}, headers: { host: `localhost:${port}`, connection },
    }, true, null, settings(match))
    Object.assign(options, {
      ca,
      lookup: loopbackLookup,
    })
    try {
      await assert.rejects(request(options), /protocol|version|EPROTO/i, connection)
    } finally {
      if (options.agent) options.agent.destroy()
    }
  }
})

test('Connection close still completes verified TLS and does not reuse the socket', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const util = requireCore('./lib/proxy/common/util')
  const match = requireCore('./utils/util.match')
  const { pfx, ca } = tlsFixture(requireCore)
  let connections = 0
  const server = https.createServer({ pfx, passphrase: 'changeit', minVersion: 'TLSv1.3' },
    (req, res) => {
      assert.equal(req.headers.connection.toLowerCase(), 'close')
      res.end('verified')
    })
  server.on('secureConnection', () => { connections++ })
  server.on('tlsClientError', () => {})
  const port = await listen(server, t)
  const create = host => ({
    ...util.getOptionsFromRequest({
      method: 'GET', url: '/', socket: {}, headers: { host: `${host}:${port}`, connection: 'close' },
    }, true, null, settings(match)),
    ca, lookup: loopbackLookup,
  })
  const options = create('localhost')
  try {
    for (let i = 0; i < 2; i++) {
      assert.deepEqual(await request(options), { status: 200, body: 'verified' })
    }
    assert.equal(connections, 2)
    await assert.rejects(request(create('wrong.example')), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' })
  } finally {
    options.agent.destroy()
  }
})

test('a TCP-connected upstream that never finishes TLS gets a connection timeout', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const blackhole = net.createServer(() => {})
  const upstreamPort = await listen(blackhole, t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, true)), t)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, method: 'GET', path: '/',
    headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close' },
  })
  assert.equal(response.status, 500)
  assert.match(response.body, /连接超时/)
  assert.doesNotMatch(response.body, /no response from upstream/)
})

test('a completed TLS handshake clears the connect deadline before a slow response body', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const { pfx, ca } = tlsFixture(requireCore)
  const server = https.createServer({ pfx, passphrase: 'changeit', minVersion: 'TLSv1.3' },
    (_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.write('first-')
      setTimeout(() => res.end('second'), 80)
    })
  server.on('tlsClientError', () => {})
  const upstreamPort = await listen(server, t)
  const factory = handlerFactory()
  const handler = factory(context => {
    context.rOptions.ca = ca
    return []
  }, [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, true)), t)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, method: 'GET', path: '/',
    headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close' },
  })
  assert.deepEqual(response, { status: 200, body: 'first-second' })
})
