'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const dns = require('node:dns')
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
    client.end(options.body)
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

function replaceLookup(t, lookup) {
  const original = dns.lookup
  dns.lookup = lookup
  t.after(() => { dns.lookup = original })
}

function trackLookupCalls(lookup) {
  return (hostname, options, done) => {
    if (hostname === 'recovery.test') lookup(hostname, options, done)
    else loopbackLookup(hostname, options, done)
  }
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

test('DNS failures return an error and the same handler serves a later successful request', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  let failures = 0
  replaceLookup(t, trackLookupCalls((_host, _options, done) => {
    failures++
    process.nextTick(done, Object.assign(new Error('Controlled DNS failure'), { code: 'EAI_AGAIN' }))
  }))
  const upstreamPort = await listen(http.createServer((_req, res) => res.end('recovered')), t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
  const send = host => request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, path: '/',
    headers: { Host: `${host}:${upstreamPort}`, Connection: 'close' },
  })
  const failed = await send('recovery.test')
  assert.equal(failed.status, 500)
  assert.match(failed.body, /EAI_AGAIN/)
  assert.equal(failures, 2)
  assert.deepEqual(await send('127.0.0.1'), { status: 200, body: 'recovered' })
})

test('late DNS callbacks after connect deadlines cannot mutate the completed error response', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const pending = []
  replaceLookup(t, trackLookupCalls((_host, options, done) => pending.push(() => {
    loopbackLookup('recovery.test', options, done)
  })))
  let upstreamRequests = 0
  const upstreamPort = await listen(http.createServer((_req, res) => {
    upstreamRequests++
    res.end('recovered')
  }), t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
  const send = host => request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, path: '/',
    headers: { Host: `${host}:${upstreamPort}`, Connection: 'close' },
  })
  const failed = await send('recovery.test')
  assert.equal(failed.status, 500)
  assert.match(failed.body, /连接超时/)
  assert.equal(pending.length, 2)
  for (const complete of pending) assert.doesNotThrow(complete)
  await new Promise(setImmediate)
  assert.equal(upstreamRequests, 0)
  assert.deepEqual(await send('127.0.0.1'), { status: 200, body: 'recovered' })
})

test('a client closing during DNS lookup cancels the attempt without starting a retry', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  let lookups = 0
  let failures = 0
  let lookupStarted
  const started = new Promise(resolve => { lookupStarted = resolve })
  replaceLookup(t, trackLookupCalls(() => { lookups++; lookupStarted() }))
  const handler = handlerFactory()(context => {
    context.requestCount = { value: 'recovery.test', count: { doCount: () => { failures++ } } }
    return []
  }, [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
  const client = http.get({
    hostname: '127.0.0.1', port: proxyPort, path: '/',
    headers: { Host: 'recovery.test', Connection: 'close' },
  })
  client.on('error', () => {})
  t.after(() => client.destroy())
  await started
  client.destroy()
  await new Promise(resolve => setTimeout(resolve, 120))
  assert.equal(lookups, 1)
  assert.equal(failures, 0)
})

test('the no-response guard does not append an error to an active streaming response', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const upstreamPort = await listen(http.createServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.write('first-')
    setTimeout(() => res.end('second'), 400)
  }), t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, path: '/',
    headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close' },
  })
  assert.deepEqual(response, { status: 200, body: 'first-second' })
})

test('a submitted POST is not replayed after an upstream reset, even with a retry rule', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  for (const configured of [false, true]) {
    const received = []
    const upstreamPort = await listen(http.createServer((req, res) => {
      const chunks = []
      req.on('data', chunk => chunks.push(chunk))
      req.on('end', () => {
        received.push(Buffer.concat(chunks).toString())
        if (received.length === 1) req.socket.destroy()
        else res.end('duplicate')
      })
    }), t)
    const handler = handlerFactory()(context => {
      if (configured) context.retryConfig = { methods: ['POST'], times: 1, statuses: [500] }
      return []
    }, [], null, {}, settings(match), null)
    const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
    const response = await request({
      protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, method: 'POST', path: '/',
      headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close', 'Content-Length': 7 },
      body: 'payment',
    })
    assert.equal(response.status, 500, `configured retry: ${configured}`)
    assert.deepEqual(received, ['payment'], `configured retry: ${configured}`)
  }
})

test('a submitted POST is not replayed for a configured retry status', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  let received = 0
  const upstreamPort = await listen(http.createServer((req, res) => {
    req.resume()
    req.on('end', () => {
      received++
      res.writeHead(503)
      res.end('origin unavailable')
    })
  }), t)
  const handler = handlerFactory()(context => {
    context.retryConfig = { methods: ['POST'], times: 1, statuses: [503] }
    return []
  }, [], null, {}, settings(match), null)
  const proxyPort = await listen(http.createServer((req, res) => handler(req, res, false)), t)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1', port: proxyPort, method: 'POST', path: '/',
    headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close', 'Content-Length': 7 },
    body: 'payment',
  })
  assert.deepEqual(response, { status: 503, body: 'origin unavailable' })
  assert.equal(received, 1)
})

test('each TLS connect timeout records one candidate failure, not one per emitted event', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const blackhole = net.createServer(() => {})
  const upstreamPort = await listen(blackhole, t)
  let lookups = 0
  let dnsFailures = 0
  let backupFailures = 0
  const provider = {
    dnsName: 'controlled',
    lookup: async () => { lookups++; return '127.0.0.1' },
    count: () => { dnsFailures++ },
  }
  const config = {
    dnsMap: { PreSet: provider },
    preSetIpList: match.domainMapRegexply({ localhost: ['127.0.0.1'] }),
  }
  const handler = handlerFactory()(context => {
    context.requestCount = { value: 'localhost', count: { doCount: () => { backupFailures++ } } }
    return []
  }, [], null, config, settings(match), null)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1',
    port: await listen(http.createServer((req, res) => handler(req, res, true)), t), path: '/',
    headers: { Host: `localhost:${upstreamPort}`, Connection: 'close' },
  })
  assert.equal(response.status, 500)
  assert.match(response.body, /连接超时/)
  assert.equal(lookups, 2)
  assert.equal(dnsFailures, 2)
  assert.equal(backupFailures, 2)
})

test('failed custom DNS candidates rotate and a GET recovers within one retry', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  const { DynamicChoice } = requireCore('./lib/choice')
  const choice = new DynamicChoice('recovery.test')
  choice.setBackupList(['127.0.0.2', '127.0.0.1'])
  const selected = []
  const failed = []
  const provider = {
    dnsName: 'controlled',
    lookup: async () => { selected.push(choice.value); return choice.value },
    count: (_host, address, error) => { failed.push(address); choice.doCount(address, error) },
  }
  const config = {
    dnsMap: { PreSet: provider },
    preSetIpList: match.domainMapRegexply({ 'recovery.test': ['127.0.0.2', '127.0.0.1'] }),
  }
  const upstreamPort = await listen(http.createServer((_req, res) => res.end('recovered')), t)
  const handler = handlerFactory()(() => [], [], null, config, settings(match), null)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1',
    port: await listen(http.createServer((req, res) => handler(req, res, false)), t), path: '/',
    headers: { Host: `recovery.test:${upstreamPort}`, Connection: 'close' },
  })
  assert.deepEqual(response, { status: 200, body: 'recovered' })
  assert.deepEqual(selected, ['127.0.0.2', '127.0.0.1'])
  assert.deepEqual(failed, ['127.0.0.2'])
})

test('a buffered POST can recover from DNS failure before transmission and reaches the origin once', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  let lookups = 0
  replaceLookup(t, trackLookupCalls((host, options, done) => {
    lookups++
    if (lookups === 1) {
      process.nextTick(done, Object.assign(new Error('Controlled DNS failure'), { code: 'EAI_AGAIN' }))
    } else {
      loopbackLookup(host, options, done)
    }
  }))
  const received = []
  const upstreamPort = await listen(http.createServer((req, res) => {
    const chunks = []
    req.on('data', chunk => chunks.push(chunk))
    req.on('end', () => {
      received.push(Buffer.concat(chunks).toString())
      res.end('accepted')
    })
  }), t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1',
    port: await listen(http.createServer((req, res) => handler(req, res, false)), t),
    method: 'POST', path: '/',
    headers: { Host: `recovery.test:${upstreamPort}`, Connection: 'close', 'Content-Length': 7 },
    body: 'payment',
  })
  assert.deepEqual(response, { status: 200, body: 'accepted' })
  assert.equal(lookups, 2)
  assert.deepEqual(received, ['payment'])
})

test('the no-response guard closes the upstream and does not retry after completing 504', enabled, async t => {
  const requireCore = createRequire(path.join(core, 'index.js'))
  const match = requireCore('./utils/util.match')
  let received = 0
  const sockets = new Set()
  const upstream = http.createServer(() => { received++ })
  upstream.on('connection', socket => {
    sockets.add(socket)
    socket.once('close', () => sockets.delete(socket))
  })
  const upstreamPort = await listen(upstream, t)
  const handler = handlerFactory()(() => [], [], null, {}, settings(match), null)
  const response = await request({
    protocol: 'http:', hostname: '127.0.0.1',
    port: await listen(http.createServer((req, res) => handler(req, res, false)), t), path: '/',
    headers: { Host: `127.0.0.1:${upstreamPort}`, Connection: 'close' },
  })
  assert.equal(response.status, 504)
  assert.match(response.body, /no response from upstream/)
  await new Promise(resolve => setTimeout(resolve, 60))
  assert.equal(sockets.size, 0)
  assert.equal(received, 1)
})
