'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const http = require('node:http')
const net = require('node:net')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { test } = require('node:test')
const { install, UPSTREAM_PATCHES } = require('../node/kotlin-request-runtime')

const helpers = process.env.DSHA_KOTLIN_HELPERS
  ? require(path.resolve(process.env.DSHA_KOTLIN_HELPERS))
  : null
const project = process.env.DSHA_WAVE2_UPSTREAM && path.resolve(process.env.DSHA_WAVE2_UPSTREAM)
const enabled = {
  skip: (!helpers || !project) &&
    'Set DSHA_KOTLIN_HELPERS to compiled Kotlin/JS and DSHA_WAVE2_UPSTREAM to the project containing core.',
}
const clone = value => structuredClone(value)
const fields = options => Object.fromEntries(
  ['protocol', 'hostname', 'port', 'path', 'headers'].map(key => [key, options[key]]),
)
const fixtures = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/wave2/request-transform.json'), 'utf8'))

function patch(source) {
  source = source.replace(/\r\n/g, '\n')
  for (const { old, new: replacement } of UPSTREAM_PATCHES) {
    // Same replace-once/idempotence contract as Python packaging.
    if (source.includes(replacement)) {
      assert.equal(source.split(replacement).length - 1, 1)
      assert.equal(source.replace(replacement, '').includes(old), false)
    } else {
      assert.equal(source.split(old).length - 1, 1, `Changed upstream patch target: ${old}`)
      source = source.replace(old, replacement)
    }
  }
  return source
}

function unpatch(source) {
  source = source.replace(/\r\n/g, '\n')
  for (const { old, new: replacement } of [...UPSTREAM_PATCHES].reverse()) {
    if (source.includes(replacement)) {
      assert.equal(source.split(replacement).length - 1, 1)
      source = source.replace(replacement, old)
    }
  }
  return source
}

// Evaluate the real utility with the packaging edits in memory; leave the
// shared extracted project untouched. Only logging/bootstrap is isolated:
// URL parser, matching, tunnel-agent and HTTP(S) agent implementations are real.
function runtime(t, { patched = true } = {}) {
  const coreRoot = path.join(project, 'core')
  const root = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src')
  const file = path.join(root, 'lib/proxy/common/util.js')
  const loaded = new Map()
  const calls = { parse: 0, policy: 0, logs: [], uses: [], inputs: [] }
  const agents = new Set()
  const logger = Object.fromEntries(['debug', 'info', 'error', 'warn']
    .map(name => [name, (...args) => calls.logs.push([name, ...args])]))
  function load(filename) {
    if (loaded.has(filename)) return loaded.get(filename)
    const localModule = { exports: {} }
    loaded.set(filename, localModule.exports)
    const nativeRequire = createRequire(filename)
    const localRequire = id => {
      if (id.includes('util.log')) return logger
      if (id === '../../../utils/util.match') return load(path.join(root, 'utils/util.match.js'))
      if (id === 'node:url') {
        return {
          ...require('node:url'),
          parse(...args) {
            calls.parse++
            if (calls.forbidParse) throw new Error('Direct requests must not use Node URL.parse')
            return require('node:url').parse(...args)
          },
        }
      }
      return nativeRequire(id)
    }
    const raw = fs.readFileSync(filename, 'utf8')
    const source = filename === file ? (patched ? patch(raw) : unpatch(raw)) : raw
    vm.runInThisContext(`(function(require, module, exports) {\n${source}\n})`, {
      filename,
    })(localRequire, localModule, localModule.exports)
    loaded.set(filename, localModule.exports)
    return localModule.exports
  }
  const util = load(file)
  const match = load(path.join(root, 'utils/util.match.js'))
  const matchHostname = match.matchHostname
  match.matchHostname = function (...args) {
    calls.policy++
    return matchHostname.apply(this, args)
  }
  const cacheKey = require.resolve(file)
  const saved = require.cache[cacheKey]
  require.cache[cacheKey] = { id: cacheKey, filename: cacheKey, loaded: true, exports: util }
  t.after(() => {
    for (const agent of agents) agent.destroy?.()
    if (saved) require.cache[cacheKey] = saved
    else delete require.cache[cacheKey]
  })
  const original = util.getOptionsFromRequest
  const onUse = name => calls.uses.push(name)
  function bind(customHelpers = {
    wave2RequestTransform(input) {
      calls.inputs.push(JSON.parse(input))
      return helpers.wave2RequestTransform(input)
    },
  }, callback = onUse) {
    install(coreRoot, customHelpers, { onUse: callback })
    return customHelpers
  }
  function settings(extra = {}) {
    return {
      timeoutMapping: match.domainMapRegexply({}),
      tlsVersionMapping: {},
      defaultTimeout: 4321,
      defaultKeepAliveTimeout: 1234,
      verifySsl: true,
      ...extra,
    }
  }
  function get(req, ssl = false, external = null, config = settings(), compatible = null) {
    const result = util.getOptionsFromRequest(req, ssl, external, config, compatible)
    if (result.agent) {
      agents.add(result.agent)
      if (result.agent.unVerifySslAgent) agents.add(result.agent.unVerifySslAgent)
    }
    return result
  }
  return { coreRoot, file, util, match, original, bind, settings, get, calls }
}

function request(extra = {}) {
  return { url: '/', method: 'GET', headers: { host: 'example.test' }, socket: {}, ...extra }
}

test('patch data has the shared plain JSON schema', () => {
  assert.deepEqual(UPSTREAM_PATCHES,
    JSON.parse(fs.readFileSync(path.join(__dirname, '../node/request-runtime-patches.json'), 'utf8')))
  assert.equal(UPSTREAM_PATCHES.length, 6)
  for (const pair of UPSTREAM_PATCHES) {
    assert.deepEqual(Object.keys(pair), ['old', 'new'])
    assert.equal(typeof pair.old, 'string')
    assert.equal(typeof pair.new, 'string')
    assert.notEqual(pair.old, pair.new)
  }
})

test('install requires a compiled export and never silently accepts JS-only mode', () => {
  for (const value of [null, {}, { wave2RequestTransform: 'wrong' }]) {
    assert.throws(() => install('unused', value), /Missing compiled Kotlin/)
  }
})

test('missing packaging hook fails before binding the real original utility', enabled, t => {
  const r = runtime(t, { patched: false })
  assert.throws(() => r.bind(), /packaging hook version 1/)
  assert.equal(r.util.getOptionsFromRequest, r.original)
  assert.equal(r.calls.policy, 0)
})

test('strict packaging patches match real TLS-fixed upstream and are idempotent', enabled, t => {
  const r = runtime(t)
  const raw = unpatch(fs.readFileSync(r.file, 'utf8'))
  assert.equal(patch(patch(raw)), patch(raw))
  assert.throws(() => patch(raw.replace('  const urlObj = URL.parse(req.url)', '  const urlObj = changedParser(req.url)')),
    /Changed upstream patch target/)
})

for (const fixture of fixtures) {
  test(`real getOptionsFromRequest consumes Kotlin: ${fixture.name}`, enabled, t => {
    const r = runtime(t)
    r.bind()
    r.calls.forbidParse = true
    r.util.parseHostnameAndPort = () => { throw new Error('Direct requests must use Kotlin authority') }
    const req = request(clone(fixture.input))
    const before = clone(req)
    const actual = r.get(req, fixture.input.ssl)
    assert.deepEqual(fields(actual), fixture.expected)
    assert.equal(actual.method, req.method)
    assert.equal(actual.url, req.url)
    assert.equal(actual.maxHeaderSize, 65536)
    if (req.headers.authorization) {
      before.socket.customSocketId = actual.customSocketId
      assert.equal(req.socket.customSocketId, actual.customSocketId)
    }
    assert.deepEqual(req, before)
    assert.equal(r.calls.inputs.length, 1)
    assert.deepEqual(r.calls.uses, ['request-transform'])
    assert.equal(r.calls.parse, 0)
    assert.notEqual(actual.headers, req.headers)
    for (const [name, value] of Object.entries(actual.headers)) {
      if (Array.isArray(value)) assert.notEqual(value, req.headers[name])
    }
  })
}

test('close retains real verified TLS agents, policy versions and compatibility fields', enabled, t => {
  const r = runtime(t)
  r.bind()
  const compatible = { keep: 'identity' }
  for (const connection of ['keep-alive', 'close', 'Close, upgrade', ['keep-alive', 'CLOSE']]) {
    for (const extra of [
      {},
      { allowTls12: true },
      { tlsVersionMapping: { 'example.test': '1.2' } },
      { tlsVersionMapping: { 'example.test': { enabled: true, version: 'TLSv1.3' } } },
      { verifySsl: false },
    ]) {
      const config = r.settings(extra)
      const req = request({ headers: { host: 'example.test', connection } })
      const expected = r.original(clone(req), true, null, config, compatible)
      const actual = r.get(req, true, null, config, compatible)
      assert.deepEqual(fields(actual), fields(expected))
      assert.equal(actual.agent, expected.agent)
      assert.equal(actual.compatibleConfig, compatible)
      assert.equal(actual.minVersion, expected.minVersion)
      assert.equal(actual.maxVersion, expected.maxVersion)
      assert.equal(actual.agent.options.rejectUnauthorized, extra.verifySsl !== false)
      assert.equal(actual.agent.options.minVersion,
        extra.tlsVersionMapping?.['example.test'] === '1.2' || extra.allowTls12 ? 'TLSv1.2' : 'TLSv1.3')
      assert.equal(actual.agent.options.keepAlive, true)
      assert.equal(actual.agent.options.timeout, 4321)
      assert.equal(actual.agent.options.freeSocketTimeout, 1234)
      assert.equal(own(actual.agent.options, 'checkServerIdentity'), false)
    }
  }
  function own(object, name) { return Object.prototype.hasOwnProperty.call(object, name) }
})

test('hostname timeout and enabled/disabled TLS mappings remain upstream decisions', enabled, t => {
  const r = runtime(t)
  r.bind()
  const config = r.settings({
    timeoutMapping: r.match.domainMapRegexply({ 'example.test': { timeout: 9876, keepAliveTimeout: 3456 } }),
    tlsVersionMapping: { 'example.test': { enabled: false, version: '1.2' } },
  })
  const actual = r.get(request({ headers: { host: 'example.test', connection: 'close' } }), true, null, config)
  assert.equal(actual.agent.options.timeout, 9876)
  assert.equal(actual.agent.options.freeSocketTimeout, 3456)
  assert.equal(actual.agent.options.minVersion, 'TLSv1.3')
  assert.equal(actual.minVersion, undefined)
})

test('auth uses and mutates the actual incoming socket, preserving upstream zero-ID behavior', enabled, t => {
  const r = runtime(t)
  r.bind()
  const socket = {}
  const authenticated = () => request({
    socket, headers: { host: 'example.test', authorization: 'NTLM token', 'proxy-authorization': 'Basic proxy' },
  })
  assert.equal(r.get(authenticated()).customSocketId, 0)
  assert.equal(socket.customSocketId, 0)
  assert.equal(r.get(authenticated()).customSocketId, 1)
  assert.equal(socket.customSocketId, 1)
  assert.equal(r.get(request({ socket })).customSocketId, 1)
  assert.equal(r.get(authenticated()).headers['proxy-authorization'], 'Basic proxy')
})

test('real IncomingMessage, null-prototype headers, lists and own __proto__ survive the bridge', enabled, t => {
  const r = runtime(t)
  r.bind()
  const socket = new net.Socket()
  t.after(() => socket.destroy())
  const req = new http.IncomingMessage(socket)
  req.method = 'POST'
  req.url = 'http://url.test/a%2fb/../c?q=%26+z'
  req.rawHeaders = ['Host', '[2001:db8::1]:8080', 'Authorization', 'token']
  req.headers = Object.assign(Object.create(null), {
    host: '[2001:db8::1]:8080', authorization: 'token', connection: ['Upgrade', 'close'],
  })
  Object.defineProperty(req.headers, '__proto__', { value: ['retained'], enumerable: true })
  const actual = r.get(req, true)
  assert.equal(actual.protocol, 'http:')
  assert.equal(actual.hostname, '[2001:db8::1]')
  assert.equal(actual.port, 8080)
  assert.equal(actual.path, '/a%2fb/../c?q=%26+z')
  assert.equal(actual.url, req.url)
  assert.equal(actual.method, 'POST')
  assert.deepEqual(actual.headers.__proto__, ['retained'])
  assert.deepEqual(actual.headers.connection, ['Upgrade', 'close'])
  assert.equal(actual.customSocketId, req.socket.customSocketId)
  actual.headers.__proto__.push('independent')
  assert.deepEqual(req.headers.__proto__, ['retained'])
})

test('external proxy strings retain HTTP overrides, HTTPS tunnels and original Connection semantics', enabled, t => {
  const r = runtime(t)
  r.bind()
  for (const ssl of [false, true]) {
    for (const proxy of ['http://user:pass@proxy.test:3128/proxy?q=1', 'https://proxy.test:8443/proxy']) {
      for (const connection of [undefined, 'Upgrade', 'close', ['close', 'Upgrade']]) {
        const headers = { host: 'origin.test:8080', ':path': '/pseudo', 'proxy-connection': 'discard' }
        if (connection !== undefined) headers.connection = connection
        const req = request({ url: 'http://absolute.test/a%2F?x=1', headers })
        const config = r.settings()
        const expected = r.original(clone(req), ssl, proxy, config)
        const actual = r.get(req, ssl, proxy, config)
        assert.deepEqual(fields(actual), fields(expected))
        assert.equal(actual.agent, expected.agent)
        assert.equal(actual.url, req.url)
        assert.equal(actual.headers.connection, headers.connection)
      }
    }
  }
  // Exercise HTTPS destination separately; an absolute HTTP URL forces HTTP.
  const actual = r.get(request(), true, 'http://proxy.test:3128')
  assert.equal(actual.protocol, 'https:')
  assert.ok(actual.agent)
  assert.equal(actual.hostname, 'example.test')
})

test('external proxy callbacks see the original request and retain null/error/mutation behavior', enabled, t => {
  const r = runtime(t)
  r.bind()
  for (const mode of ['proxy', 'null', 'throw', 'mutate']) {
    const req = request({ url: 'https://absolute.test/original?q=1', headers: { host: 'example.test', connection: 'Upgrade' } })
    let calls = 0
    const callback = (incoming, ssl) => {
      calls++
      assert.equal(incoming, req)
      assert.equal(incoming.socket, req.socket)
      assert.equal(incoming.url, 'https://absolute.test/original?q=1')
      assert.equal(incoming.method, 'GET')
      assert.equal(ssl, true)
      if (mode === 'throw') throw new Error('upstream callback error')
      if (mode === 'mutate') incoming.headers.host = 'rewritten.test:9443'
      return mode === 'proxy' ? 'http://proxy.test:3128/hop' : null
    }
    const actual = r.get(req, true, callback)
    assert.equal(calls, 1)
    assert.equal(actual.hostname, mode === 'mutate' ? 'rewritten.test' : 'example.test')
    assert.equal(actual.port, mode === 'mutate' ? 9443 : 443)
    assert.equal(actual.path, '/original?q=1')
    assert.equal(actual.headers.host, 'example.test')
    assert.equal(actual.headers.connection, mode === 'proxy' ? 'Upgrade' : 'keep-alive')
  }
  assert.ok(r.calls.logs.some(entry => entry[0] === 'error' && entry[1] === 'externalProxy error:'))
})

test('upstream proxy interceptors can rewrite Kotlin options without losing original URL or TLS policy', enabled, t => {
  const r = runtime(t)
  r.bind()
  const actual = r.get(request({ url: 'https://original.test/a%2Fb?q=1' }), true)
  const agent = actual.agent
  const proxy = require(path.join(r.coreRoot,
    'node_modules/@blue-frontier/mitmproxy/src/lib/interceptor/impl/req/proxy.js'))
  const target = proxy.doProxy('mirror.test:9443', actual, request(), {}, null, null)
  assert.equal(target, 'https://mirror.test:9443/a%2Fb?q=1')
  assert.equal(actual.hostname, 'mirror.test')
  assert.equal(actual.headers.host, 'mirror.test:9443')
  assert.equal(actual.port, 9443)
  assert.equal(actual.path, '/a%2Fb?q=1')
  assert.equal(actual.url, 'https://original.test/a%2Fb?q=1')
  assert.equal(actual.original.hostname, 'example.test')
  assert.equal(actual.agent, agent)
})

test('invalid requests fail before URL parsing, agents, callbacks, socket IDs or onUse', enabled, t => {
  const r = runtime(t)
  r.bind()
  let externalCalls = 0
  const external = () => { externalCalls++; return 'http://proxy.test:3128' }
  const invalid = [
    { url: '//other.test/' }, { url: 'https://user@other.test/' }, { url: '/bad%2' },
    { url: '/bad\\path' }, { url: '/bad\npath' }, { url: ' https://other.test/' },
    { url: 'https://other.test:65536/' }, { url: 'https://[bad]/' }, { url: '/unicode-\u2603' },
    { method: 'CONNECT' }, { method: 'BAD METHOD' }, { method: '' }, { url: '*' },
    { headers: {} }, { headers: { host: ['example.test'] } },
    { headers: { host: 'example.test:0' } }, { headers: { host: 'example.test:80junk' } },
    { headers: { host: '127.1' } }, { headers: { host: '[::1%eth0]' } },
    { headers: { host: 'example.test', Host: 'other.test' } },
    { headers: { host: 'example.test', 'x-test': 'a\r\nb' } },
    { headers: { host: 'example.test', 'proxy-connection': '\0' } },
    { headers: { host: 'example.test', ':path': '\u2603' } },
    { headers: { host: 'example.test', 'x-test': [false] } },
    { headers: { host: 'example.test', 'x-test': [, 'value'] } },
    { headers: { host: 'example.test', 'x-test': undefined } },
    { headers: { host: 'example.test', 'x-test': NaN } },
    { headers: { host: 'example.test', 'x-test': () => 'dropped' } },
    { headers: { host: 'example.test', 'x-test': { toJSON() { throw new Error('must not serialize') } } } },
    { rawHeaders: ['Host', 'example.test', 'HOST', 'second.test'] },
    { rawHeaders: ['Host', ,] },
    { rawHeaders: ['Host'] },
    { socket: null }, { url: null }, { method: null },
  ]
  for (const extra of invalid) {
    for (const proxy of [null, external]) {
      const socket = {}
      const req = request({ socket, ...extra })
      assert.throws(() => r.get(req, true, proxy), error =>
        error.code === 'DSHA_INVALID_REQUEST' && error.cause instanceof Error)
      assert.deepEqual(socket, {})
    }
  }
  for (const ssl of [undefined, null, 'true', 1]) {
    assert.throws(() => r.util.getOptionsFromRequest(request(), ssl, external, r.settings()),
      { code: 'DSHA_INVALID_REQUEST' })
  }
  assert.equal(externalCalls, 0)
  assert.equal(r.calls.parse, 0)
  assert.equal(r.calls.policy, 0)
  assert.deepEqual(r.calls.uses, [])
})

test('helper exceptions and malformed results fail before upstream side effects', enabled, t => {
  const r = runtime(t)
  let next
  r.bind({ wave2RequestTransform() { return next() } })
  for (const invalid of [
    () => { throw new Error('compiled rejection') },
    () => { throw new TypeError('helper programming failure') },
    () => undefined,
    () => '{invalid',
    () => JSON.stringify({ protocol: 'https:', hostname: 'example.test', port: 443, path: '/' }),
    () => JSON.stringify({ protocol: 'ftp:', hostname: 'example.test', port: 443, path: '/', headers: {} }),
    () => JSON.stringify({ protocol: 'https:', hostname: 'example.test', port: 0, path: '/', headers: {} }),
    () => JSON.stringify({ protocol: 'https:', hostname: 'example.test', port: 443, path: '/', headers: { host: null } }),
  ]) {
    next = invalid
    const socket = {}
    assert.throws(() => r.get(request({ socket, headers: { host: 'example.test', authorization: 'NTLM' } }), true),
      error => error.code === undefined)
    assert.deepEqual(socket, {})
  }
  assert.equal(r.calls.policy, 0)
  assert.deepEqual(r.calls.uses, [])
})

test('unexpected request getter failures remain untagged programming errors', enabled, t => {
  const r = runtime(t)
  r.bind()
  const failure = new TypeError('request getter programming failure')
  const req = request()
  Object.defineProperty(req, 'url', { get() { throw failure } })
  assert.throws(() => r.get(req), error => error === failure && error.code === undefined)
  assert.equal(r.calls.policy, 0)
  assert.deepEqual(r.calls.uses, [])
})

test('onUse observes validated invocation before transport, and installation is idempotent', enabled, t => {
  const r = runtime(t)
  let count = 0
  const onUse = name => {
    assert.equal(name, 'request-transform')
    assert.equal(r.calls.policy, 0)
    count++
  }
  const binding = r.bind(helpers, onUse)
  install(r.coreRoot, binding, { onUse })
  r.get(request())
  assert.equal(count, 1)
  assert.throws(() => install(r.coreRoot, { ...helpers }, { onUse }), /different binding/)
})

test('a production requestTransform alias uses the same compiled implementation', enabled, t => {
  const r = runtime(t)
  let calls = 0
  r.bind({
    requestTransform(input) {
      calls++
      return helpers.wave2RequestTransform(input)
    },
    wave2RequestTransform() { throw new Error('Production alias must take precedence') },
  })
  assert.equal(r.get(request({ url: '/compiled%2F?q=%26' })).path, '/compiled%2F?q=%26')
  assert.equal(calls, 1)
})

test('onUse failure propagates before agent construction and auth socket mutation', enabled, t => {
  const r = runtime(t)
  r.bind(helpers, () => { throw new Error('evidence callback failed') })
  const socket = {}
  assert.throws(() => r.get(request({ socket, headers: { host: 'example.test', authorization: 'NTLM' } })),
    /evidence callback failed/)
  assert.deepEqual(socket, {})
  assert.equal(r.calls.policy, 0)
})
