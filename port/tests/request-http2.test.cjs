'use strict'

const assert = require('node:assert/strict')
const http = require('node:http')
const http2 = require('node:http2')
const path = require('node:path')
const fs = require('node:fs')
const { test } = require('node:test')
const project = process.env.DSHA_TEST_PROJECT && path.resolve(process.env.DSHA_TEST_PROJECT)
const adapter = process.env.DSHA_REQUEST_ADAPTER ||
  (project && process.env.DSHA_EXPECT_PACKAGED_VALIDATION === '1'
    ? path.join(project, 'kotlin-request-runtime.js')
    : path.resolve(__dirname, '../node/kotlin-request-runtime.js'))
const { install } = require(path.resolve(adapter))
const enabled = { skip: !project && 'Supply the extracted updated runtime through DSHA_TEST_PROJECT.' }
let runtime

function core () {
  if (runtime) return runtime
  process.env.HOME = path.join(project, 'home')
  process.env.USERPROFILE = process.env.HOME
  process.env.DEV_SIDECAR_LOG_DISABLED = 'true'
  fs.mkdirSync(process.env.HOME, { recursive: true })
  const root = path.join(project, 'core')
  const helpers = require(path.join(project, 'kotlin/dev-sidecar-lightning-netcore-js.js'))
  require(path.join(project, 'kotlin-helpers.js')).install(root, helpers)
  install(root, helpers)
  const source = path.join(root, 'node_modules/@blue-frontier/mitmproxy/src')
  runtime = {
    util: require(path.join(source, 'lib/proxy/common/util.js')),
    match: require(path.join(source, 'utils/util.match.js')),
  }
  return runtime
}

async function listen (server, t) {
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

function request (client, headers) {
  return new Promise((resolve, reject) => {
    const stream = client.request(headers)
    let status
    const chunks = []
    stream.once('response', response => { status = response[':status'] })
    stream.on('data', chunk => chunks.push(chunk))
    stream.once('error', reject)
    stream.once('end', () => resolve({ status, body: Buffer.concat(chunks).toString() }))
    stream.end()
  })
}

test('actual Node HTTP/2 sensitivity metadata is not treated as a rejected request header', enabled, async t => {
  const { util, match } = core()
  const received = []
  const origin = http.createServer((req, res) => {
    received.push({ url: req.url, authorization: req.headers.authorization })
    res.end('forwarded')
  })
  const originPort = await listen(origin, t)
  const settings = {
    timeoutMapping: match.domainMapRegexply({}), tlsVersionMapping: {}, verifySsl: true,
    defaultTimeout: 1500, defaultKeepAliveTimeout: 100,
  }
  let sawMetadata = false
  const failures = []
  const server = http2.createServer((req, res) => {
    // This is the same authority normalization performed by FakeServersCenter.
    req.headers.host = req.authority
    sawMetadata ||= Object.getOwnPropertySymbols(req.headers).includes(http2.sensitiveHeaders)
    if (req.url === '/unknown-symbol') req.headers[Symbol('unsupported')] = []
    let options
    try {
      options = util.getOptionsFromRequest(req, false, null, settings)
    } catch (error) {
      failures.push(error.code)
      res.writeHead(400)
      res.end('Bad Request\n')
      return
    }
    assert.equal(Object.getOwnPropertySymbols(options.headers).length, 0)
    const upstream = http.request(options, response => {
      res.writeHead(response.statusCode)
      response.pipe(res)
      response.once('end', () => options.agent?.destroy())
    })
    upstream.once('error', error => {
      failures.push(error.code)
      options.agent?.destroy()
      res.writeHead(502)
      res.end()
    })
    req.pipe(upstream)
  })
  const port = await listen(server, t)
  const client = http2.connect(`http://127.0.0.1:${port}`)
  t.after(() => client.destroy())
  const headers = {
    ':authority': `127.0.0.1:${originPort}`,
    authorization: 'fixture-only',
    [http2.sensitiveHeaders]: ['authorization'],
  }
  assert.deepEqual(await request(client, { ...headers, ':path': '/trending?q=a%2Fb' }),
    { status: 200, body: 'forwarded' })
  assert.equal(sawMetadata, true)
  assert.deepEqual(received, [{ url: '/trending?q=a%2Fb', authorization: 'fixture-only' }])
  assert.deepEqual(await request(client, { ...headers, ':path': '/unknown-symbol' }),
    { status: 400, body: 'Bad Request\n' })
  assert.deepEqual(await request(client, { ...headers, ':path': '/after-rejection' }),
    { status: 200, body: 'forwarded' })
  assert.deepEqual(failures, ['DSHA_INVALID_REQUEST'])
  assert.equal(received.length, 2)
})

test('HTTP/1 input cannot use the HTTP/2 metadata exception', enabled, () => {
  const { util, match } = core()
  assert.throws(() => util.getOptionsFromRequest({
    httpVersionMajor: 1, method: 'GET', url: '/', socket: {},
    headers: { host: 'example.test', [http2.sensitiveHeaders]: [] },
  }, false, null, { timeoutMapping: match.domainMapRegexply({}), tlsVersionMapping: {} }),
  { code: 'DSHA_INVALID_REQUEST' })
})
