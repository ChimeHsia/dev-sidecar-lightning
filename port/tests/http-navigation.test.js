'use strict'

const assert = require('node:assert/strict')
const http = require('node:http')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { once } = require('node:events')
const { test } = require('node:test')
const {
  install, compileRules, createRuleStore, upgradeTarget, wrapHandler, wrapConnectInterceptor,
} = require('../node/http-navigation')

function request (url, headers = {}, method = 'GET') {
  return { url, method, headers: { host: new URL(url).host, accept: 'text/html', ...headers } }
}

test('Google navigation upgrades without changing path, escaped query or fragment', () => {
  for (const host of ['google.com', 'www.google.com', 'google.com.hk', 'www.google.com.hk']) {
    const targetHost = host.startsWith('www.') ? host : `www.${host}`
    const suffix = '/search?q=a%2Fb%20c&next=http%3A%2F%2Fexample.com%2F#section'
    assert.equal(upgradeTarget(request(`http://${host}${suffix}`)), `https://${targetHost}${suffix}`)
    assert.equal(upgradeTarget(request(`http://${host}:80/`, {}, 'HEAD')), `https://${targetHost}/`)
  }
  assert.equal(upgradeTarget(request('HTTP://WWW.GOOGLE.COM.HK/')), 'https://www.google.com.hk/')
})

test('ordinary HTTP, HTTPS, custom ports, credentials and lookalike hosts stay unchanged', () => {
  for (const url of [
    'http://example.com/', 'http://github.com/', 'http://127.0.0.1/',
    'http://www.google.com.hk:8080/', 'http://google.com:443/',
    'http://google.com.attacker.test/', 'http://notgoogle.com/',
    'http://google.com@attacker.test/', 'http://user@google.com/',
    'https://google.com.hk/', 'https://www.google.com/', 'ftp://google.com/',
  ]) assert.equal(upgradeTarget(request(url)), null, url)
  assert.equal(upgradeTarget({ url: '/search', method: 'GET', headers: {} }), null)
  assert.equal(upgradeTarget(request('http://google.com/', { host: 'attacker.test' })), null)
})

test('only document GET and HEAD without a body are upgraded', () => {
  const url = 'http://google.com.hk/search?q=test'
  for (const method of ['POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS']) {
    assert.equal(upgradeTarget(request(url, {}, method)), null)
  }
  for (const headers of [
    { accept: 'application/json' }, { accept: '*/*' },
    { accept: 'text/html', 'sec-fetch-dest': 'script' },
    { accept: 'text/html', 'sec-fetch-dest': 'empty' },
    { 'content-length': '1' }, { 'transfer-encoding': 'chunked' },
  ]) assert.equal(upgradeTarget(request(url, headers)), null)
  assert.equal(upgradeTarget(request(url, { accept: '*/*', 'sec-fetch-dest': 'document' })),
    'https://www.google.com.hk/search?q=test')
  assert.equal(upgradeTarget(request(url, { accept: '*/*', 'sec-fetch-dest': 'iframe' })),
    'https://www.google.com.hk/search?q=test')
})

test('installation replaces the factory consumed by the core and is idempotent', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-http-'))
  const modulePath = path.join(root,
    'node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/createRequestHandler.js')
  fs.mkdirSync(path.dirname(modulePath), { recursive: true })
  fs.writeFileSync(modulePath, 'module.exports = callback => callback\n')
  const connectPath = path.join(path.dirname(modulePath), 'createConnectHandler.js')
  fs.writeFileSync(connectPath, 'module.exports = interceptor => interceptor\n')
  const configPath = path.join(root, 'navigation-rules.json')
  try {
    install(root, { configPath })
    const factory = require(modulePath)
    install(root)
    assert.equal(require(modulePath), factory)
    const handler = factory(() => assert.fail('must not enter upstream handler'))
    let status
    let headers
    let ended = false
    handler(request('http://google.com.hk/'), {
      writeHead (code, value) { status = code; headers = value },
      end () { ended = true },
    }, false)
    assert.equal(status, 307)
    assert.equal(headers.Location, 'https://www.google.com.hk/')
    assert.equal(ended, true)
    assert.equal(require(connectPath)(() => null)({ url: 'google.cn:443' }), true)
    fs.writeFileSync(configPath, JSON.stringify({ version: 1, rules: [] }))
    assert.equal(require(connectPath)(() => null)({ url: 'google.cn:443' }), null)
  } finally {
    delete require.cache[modulePath]
    delete require.cache[connectPath]
    fs.rmSync(root, { recursive: true })
  }
})

test('country-domain rules apply to HTTP and decrypted HTTPS, preserving escaped paths and queries', () => {
  const suffix = '/search/a%2Fb?q=a%2Fb%20c&next=https%3A%2F%2Fexample.com%2F'
  for (const host of ['google.cn', 'www.google.cn']) {
    assert.equal(upgradeTarget(request(`http://${host}${suffix}`), false),
      `https://www.google.com.hk${suffix}`)
    assert.equal(upgradeTarget(request(`https://${host}${suffix}`), true),
      `https://www.google.com.hk${suffix}`)
    assert.equal(upgradeTarget({
      url: suffix, method: 'GET', headers: { host: `${host}:443`, 'sec-fetch-dest': 'document' },
    }, true), `https://www.google.com.hk${suffix}`)
  }
  assert.equal(upgradeTarget(request('https://www.google.com.hk/'), true), null)
  assert.equal(upgradeTarget(request('https://google.cn/'), false), null)
  for (const url of ['//attacker.test/', '/\\attacker.test/', '/search\nbad']) {
    assert.equal(upgradeTarget({
      url, method: 'GET', headers: { host: 'google.cn', accept: 'text/html' },
    }, true), null)
  }
})

function customConfig (rules = {}) {
  return {
    version: 1,
    rules: [{
      enabled: true,
      sourceHosts: ['entry.example.test'],
      sourceSchemes: ['http', 'https'],
      targetOrigin: 'https://destination.example.test',
      ...rules,
    }],
  }
}

test('hostnames and destinations come from configuration, without built-in matching assumptions', () => {
  const rules = compileRules(customConfig())
  assert.equal(upgradeTarget(request('https://entry.example.test/path?x=%2F'), true, rules),
    'https://destination.example.test/path?x=%2F')
  assert.equal(upgradeTarget(request('http://google.cn/'), false, rules), null)
  assert.equal(upgradeTarget(request('http://sub.entry.example.test/'), false, rules), null)
  assert.equal(compileRules(customConfig({ enabled: false })).size, 0)
  assert.equal(compileRules({ version: 1, rules: [] }).size, 0)
})

test('configuration rejects ambiguous rules, unsafe targets and loops', () => {
  for (const config of [
    null, {}, { version: 2, rules: [] }, { version: 1, rules: [null] },
    customConfig({ enabled: 'false' }),
    customConfig({ sourceSchemes: ['ftp'] }),
    customConfig({ sourceHosts: ['*.example.test'] }),
    customConfig({ sourceHosts: ['example.test:443'] }),
    customConfig({ sourceHosts: ['user@example.test'] }),
    customConfig({ sourceHosts: ['127.0.0.1'] }),
    customConfig({ targetOrigin: 'http://destination.example.test' }),
    customConfig({ targetOrigin: 'https://user@destination.example.test' }),
    customConfig({ targetOrigin: 'https://destination.example.test:8443' }),
    customConfig({ targetOrigin: 'https://destination.example.test/path' }),
    customConfig({ targetOrigin: 'https://destination.example.test/?q=test' }),
    customConfig({ targetOrigin: 'https://destination.example.test/#fragment' }),
    customConfig({ targetOrigin: 'https://entry.example.test' }),
  ]) assert.throws(() => compileRules(config), JSON.stringify(config))
  const duplicate = customConfig()
  duplicate.rules.push({ ...duplicate.rules[0] })
  assert.throws(() => compileRules(duplicate), /Duplicate/)
  const cycle = customConfig()
  cycle.rules.push({
    sourceHosts: ['destination.example.test'], sourceSchemes: ['https'],
    targetOrigin: 'https://entry.example.test',
  })
  assert.throws(() => compileRules(cycle), /loop/)
  cycle.rules[1].targetOrigin = 'https://final.example.test'
  assert.equal(compileRules(cycle).size, 3)
})

test('rule file is seeded once, reloads without a restart and retains last valid rules on errors', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-navigation-'))
  const configPath = path.join(root, 'nested', 'navigation-rules.json')
  const warnings = []
  const options = { log: message => warnings.push(message) }
  try {
    const store = createRuleStore(configPath, options)
    assert.equal(store.getRules().get('https://google.cn'), 'https://www.google.com.hk')
    const config = customConfig()
    fs.writeFileSync(configPath, JSON.stringify(config))
    assert.equal(store.getRules().get('https://entry.example.test'), 'https://destination.example.test')
    const secondStore = createRuleStore(configPath, options)
    assert.deepEqual(JSON.parse(fs.readFileSync(configPath)), config)
    assert.equal(secondStore.getRules().has('https://entry.example.test'), true)
    fs.writeFileSync(configPath, '{invalid')
    assert.equal(store.getRules().has('https://entry.example.test'), true)
    store.getRules()
    assert.equal(warnings.length, 1)
    fs.writeFileSync(configPath, JSON.stringify(customConfig({ targetOrigin: 'http://bad.test' })))
    assert.equal(store.getRules().has('https://entry.example.test'), true)
    fs.writeFileSync(configPath, ' '.repeat(65537))
    assert.equal(store.getRules().has('https://entry.example.test'), true)
    fs.writeFileSync(configPath, JSON.stringify({ version: 1, rules: [] }))
    assert.equal(store.getRules().size, 0)
    fs.unlinkSync(configPath)
    assert.equal(store.getRules().size, 0)
  } finally {
    fs.rmSync(root, { recursive: true })
  }
})

test('CONNECT interception enables configured HTTPS origins and respects core whitelist decisions', () => {
  let rules = compileRules(customConfig())
  const getRules = () => rules
  const interceptor = wrapConnectInterceptor(() => null, getRules)
  assert.equal(interceptor({ url: 'entry.example.test:443' }), true)
  assert.equal(interceptor({ url: 'ENTRY.EXAMPLE.TEST:443' }), true)
  for (const url of ['entry.example.test:8443', 'entry.example.test:80', 'other.example.test:443',
    'entry.example.test:443/path', 'user@entry.example.test:443']) {
    assert.equal(interceptor({ url }), null)
  }
  for (const original of [false, 'false', true, 'matched']) {
    assert.equal(wrapConnectInterceptor(() => original, getRules)({ url: 'entry.example.test:443' }), original)
  }
  rules = compileRules({ version: 1, rules: [] })
  assert.equal(interceptor({ url: 'entry.example.test:443' }), null)
})

test('real HTTP proxy redirects before upstream handling and delegates other requests', async () => {
  let forwarded = 0
  const owner = {}
  const handler = wrapHandler(function (req, res, ssl) {
    assert.equal(this, owner)
    assert.equal(ssl, false)
    forwarded++
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('ordinary-http-ok')
  })
  const server = http.createServer((req, res) => handler.call(owner, req, res, false))
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  async function fetch (url, headers = {}, method = 'GET') {
    return new Promise((resolve, reject) => {
      const outgoing = http.request({
        hostname: '127.0.0.1', port: server.address().port,
        path: url, method, headers: request(url, headers, method).headers,
      }, res => {
        const chunks = []
        res.on('data', chunk => chunks.push(chunk))
        res.on('end', () => resolve({
          status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString(),
        }))
      })
      outgoing.on('error', reject)
      outgoing.end()
    })
  }
  try {
    const result = await fetch('http://google.com.hk/search?q=kotlin%20test')
    assert.equal(result.status, 307)
    assert.equal(result.headers.location, 'https://www.google.com.hk/search?q=kotlin%20test')
    assert.equal(result.headers['cache-control'], 'no-store')
    assert.equal(result.headers['ds-interceptor'], 'https-navigation')
    assert.equal(result.body, '')
    assert.equal(forwarded, 0)
    for (const [url, headers, method] of [
      ['http://example.com/', {}, 'GET'],
      ['http://google.com.hk:8080/', {}, 'GET'],
      ['http://google.com.hk/', { accept: 'application/json' }, 'GET'],
      ['http://google.com.hk/', {}, 'POST'],
    ]) {
      const response = await fetch(url, headers, method)
      assert.equal(response.status, 200)
      assert.equal(response.body, 'ordinary-http-ok')
    }
    assert.equal(forwarded, 4)
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  }
})
