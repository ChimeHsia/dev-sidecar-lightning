// SPDX-License-Identifier: MPL-2.0
'use strict'

// DSHA_KOTLIN_HELPERS: compiled CommonJS Kotlin/JS root module.
// DSHA_WAVE2_UPSTREAM: extracted project directory containing core/.
// No sockets, Gradle processes, or devices are used by this suite.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const Module = require('node:module')
const path = require('node:path')
const vm = require('node:vm')
const { test } = require('node:test')
const { install } = require('../node/kotlin-match-runtime')
const { install: installFirstWave } = require('../node/kotlin-helpers')

const helpers = process.env.DSHA_KOTLIN_HELPERS
  ? require(path.resolve(process.env.DSHA_KOTLIN_HELPERS))
  : null
const coreRoot = process.env.DSHA_WAVE2_UPSTREAM
  ? path.resolve(process.env.DSHA_WAVE2_UPSTREAM, 'core')
  : null
const kotlinOptions = { skip: !helpers && 'Set DSHA_KOTLIN_HELPERS to the compiled module.' }
const sourceOptions = { skip: !coreRoot && 'Set DSHA_WAVE2_UPSTREAM to the extracted project containing core/.' }
const runtimeOptions = {
  skip: (!helpers || !coreRoot) && 'Supply both DSHA_KOTLIN_HELPERS and DSHA_WAVE2_UPSTREAM.',
}
const fixtureRoot = path.join(__dirname, 'fixtures/wave2')
const fixtures = name => JSON.parse(fs.readFileSync(path.join(fixtureRoot, `${name}.json`), 'utf8'))
const clone = value => value == null ? null : JSON.parse(JSON.stringify(value))
const logger = Object.fromEntries(['info', 'debug', 'warn', 'error'].map(key => [key, () => {}]))
const fakeCore = path.join(__dirname, 'match-runtime-fixture-core')
const matchFile = root => path.join(root, 'node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js')
const mergeFile = root => path.join(root, 'node_modules/@blue-frontier/dev-sidecar/src/merge.js')
const forbidden = () => { throw new Error('Original business implementation was invoked') }

// Isolate module loading, not business logic. Every original oracle and options
// factory below executes the extracted source without changing that source.
function loadSource (file, overrides = {}) {
  const module = { exports: {} }
  const nativeRequire = Module.createRequire(file)
  const localRequire = id => {
    if (Object.hasOwn(overrides, id)) return overrides[id]
    if (id.includes('util.log')) return logger
    return nativeRequire(id)
  }
  vm.runInThisContext(`(function(require, module, exports) {\n${fs.readFileSync(file, 'utf8')}\n})`,
    { filename: file })(localRequire, module, module.exports)
  return module.exports
}

function withModules (root, matchApi, mergeApi, action) {
  const load = Module._load
  Module._load = function (id) {
    if (id === matchFile(root)) return matchApi
    if (id === mergeFile(root)) return mergeApi
    return load.apply(this, arguments)
  }
  try {
    return action()
  } finally {
    Module._load = load
  }
}

function stubFixture (overrides = {}, context) {
  const api = {
    domainMapRegexply: entries => ({ origin: entries || {} }),
    matchHostname: forbidden,
    matchHostnameAll: forbidden,
    isMatched: forbidden,
  }
  const kotlin = {
    createHostnameAllMatcher: forbidden,
    matchPathRule: forbidden,
    ...overrides,
  }
  withModules(fakeCore, api, {}, () => install(fakeCore, kotlin, context))
  return api
}

function sourceCore () {
  const merge = loadSource(mergeFile(coreRoot))
  const match = loadSource(matchFile(coreRoot), { '@blue-frontier/dev-sidecar/src/merge': merge })
  return { merge, match }
}

function runtimeCore (kotlin = helpers, context) {
  const core = sourceCore()
  // Poison the replaced methods so a fallback would fail even when it happened
  // to return the same fixture result as Kotlin.
  core.match.matchHostnameAll = forbidden
  core.match.isMatched = forbidden
  withModules(coreRoot, core.match, core.merge, () => {
    const firstWave = installFirstWave(coreRoot, kotlin)
    install(coreRoot, kotlin, context === undefined ? firstWave : context)
  })
  return core
}

test('required exports and context are validated before module installation', () => {
  assert.throws(() => install(fakeCore, {}), /Missing Kotlin helper: createHostnameAllMatcher/)
  assert.throws(() => install(fakeCore, { createHostnameAllMatcher () {} }),
    /Missing Kotlin helper: matchPathRule/)
  assert.throws(() => stubFixture({}, { bindings: new Map() }), /must be a WeakMap/)
  assert.throws(() => stubFixture({}, { onUse: true }), /must be a function/)
})

test('All compilation is lazy and first-wave scalar, array and circular maps remain usable', () => {
  let compiles = 0
  let queries = 0
  const events = []
  const api = stubFixture({
    createHostnameAllMatcher (json) {
      compiles++
      assert.deepEqual(JSON.parse(json), { '*.test': { audit: true } })
      return { match (host) { queries++; return host === 'api.test' ? { audit: true } : null } }
    },
    matchPathRule: () => null,
  }, { onUse: name => events.push(name) })
  const circular = {}
  circular.self = circular
  for (const entries of [{ x: true }, { x: ['1.2.3.4'] }, { x: circular }, ['a', 'b']]) {
    assert.ok(api.domainMapRegexply(entries))
  }
  assert.equal(compiles, 0)
  const entries = { '*.test': { audit: true } }
  const map = api.domainMapRegexply(entries)
  assert.equal(compiles, 0)
  assert.deepEqual(api.matchHostnameAll(map, 'api.test'), { audit: true })
  entries['*.test'].audit = false
  assert.deepEqual(api.matchHostnameAll(map, 'api.test'), { audit: true })
  assert.equal(api.matchHostnameAll(map, 'other'), undefined)
  assert.equal(compiles, 1)
  assert.equal(queries, 3)
  assert.deepEqual(events, ['hostname-all', 'hostname-all', 'hostname-all'])
  assert.equal(api.isMatched('/missing', '['), null)
  assert.equal(events.at(-1), 'path-rules')
})

test('preexisting maps use context.bindings.entries and preserve original entry spelling', () => {
  const bindings = new WeakMap()
  const map = { '^.*\\.test$': {}, origin: {} }
  const entries = { '.test': { audit: true }, '*.test': null }
  bindings.set(map, { matcher: {}, values: [], entries })
  let compiles = 0
  const api = stubFixture({
    createHostnameAllMatcher (json) {
      compiles++
      assert.deepEqual(JSON.parse(json), entries)
      return { match: () => ({ audit: true }) }
    },
  }, { bindings })
  assert.deepEqual(api.matchHostnameAll(map, 'api.test'), { audit: true })
  assert.deepEqual(api.matchHostnameAll(map, 'test'), { audit: true })
  assert.equal(compiles, 1)
  assert.deepEqual(entries, { '.test': { audit: true }, '*.test': null })
})

test('absent map sources fail explicitly; disabled and null tables keep upstream miss behavior', () => {
  const api = stubFixture({ createHostnameAllMatcher: () => ({ match: () => null }) })
  assert.equal(api.matchHostnameAll(null, 'x'), null)
  assert.equal(api.matchHostnameAll(undefined, 'x'), null)
  assert.equal(api.matchHostnameAll({}, 'x'), null)
  assert.equal(api.matchHostnameAll({ origin: null }, 'x'), null)
  assert.equal(api.matchHostnameAll({ origin: {} }, 'x'), undefined)
  assert.equal(api.matchHostnameAll(api.domainMapRegexply(null), 'x'), undefined)
  assert.throws(() => api.matchHostnameAll({ origin: { x: {} } }, 'x'), /no raw entries/)
  assert.throws(() => api.matchHostnameAll([], 'x'), /compiled hostname map/)
})

test('Kotlin failures and invalid results never fall back or report successful use', () => {
  const error = new Error('Kotlin query failed')
  const events = []
  for (const overrides of [
    { createHostnameAllMatcher: () => { throw error } },
    { createHostnameAllMatcher: () => ({ match: () => { throw error } }) },
  ]) {
    const api = stubFixture(overrides, { onUse: name => events.push(name) })
    assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({ x: {} }), 'x'),
      candidate => candidate === error)
  }
  let api = stubFixture({
    createHostnameAllMatcher: () => ({}),
    matchPathRule: () => { throw error },
  }, { onUse: name => events.push(name) })
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({}), 'x'), /compiler result/)
  assert.throws(() => api.isMatched('/x', '*'), candidate => candidate === error)
  api = stubFixture({
    createHostnameAllMatcher: () => ({ match: () => [] }),
    matchPathRule: () => ({}),
  }, { onUse: name => events.push(name) })
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({}), 'x'), /object or null/)
  assert.throws(() => api.isMatched('/x', '*'), /array or null/)
  const circular = {}
  circular.self = circular
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({ x: circular }), 'x'), /circular/i)
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply([]), 'x'), /JSON objects/)
  assert.deepEqual(events, [])
})

test('real compiled module exposes the production native-value exports', kotlinOptions, () => {
  for (const name of ['createHostnameAllMatcher', 'matchPathRule']) {
    assert.equal(typeof helpers[name], 'function', name)
  }
  const matcher = helpers.createHostnameAllMatcher('{"x":{"audit":true}}')
  assert.equal(typeof matcher.match, 'function')
  assert.deepEqual(matcher.match('x'), { audit: true })
  assert.equal(matcher.match('missing'), null)
  assert.deepEqual(helpers.matchPathRule('/x', '*'), ['/x'])
})

for (const item of fixtures('hostname-all')) {
  test(`original hostname fixture: ${item.name}`, sourceOptions, () => {
    const { match } = sourceCore()
    const actual = match.matchHostnameAll(match.domainMapRegexply(clone(item.entries)), item.hostname)
    assert.deepEqual(clone(actual), item.expected)
    assert.deepEqual(Object.keys(actual || {}), item.expectedKeys || [])
  })
  test(`production Kotlin hostname fixture: ${item.name}`, runtimeOptions, () => {
    const original = sourceCore().match
    const api = runtimeCore().match
    const actual = api.matchHostnameAll(api.domainMapRegexply(clone(item.entries)), item.hostname)
    const expected = original.matchHostnameAll(original.domainMapRegexply(clone(item.entries)), item.hostname)
    assert.deepEqual(clone(actual), item.expected)
    assert.deepEqual(actual, expected, 'including sparse slots and native capture metadata')
    assert.deepEqual(Object.keys(actual || {}), item.expectedKeys || [])
  })
}

for (const item of fixtures('path-rules')) {
  test(`original path fixture: ${item.name}`, sourceOptions, () => {
    assert.deepEqual(clone(sourceCore().match.isMatched(item.path, item.pattern)), item.expected)
  })
  test(`production Kotlin path fixture: ${item.name}`, runtimeOptions, () => {
    const original = sourceCore().match.isMatched(item.path, item.pattern)
    const actual = runtimeCore().match.isMatched(item.path, item.pattern)
    assert.deepEqual(clone(actual), item.expected)
    assert.deepEqual(actual, original, 'including present undefined slots and RegExp array metadata')
  })
}

test('hostname holes and optional named groups differ correctly from path undefined slots', runtimeOptions, () => {
  const api = runtimeCore().match
  const original = sourceCore().match
  const entries = {
    '^(?<sub>api)(?<absent>z)?\\.': { list: [null, '[delete]', { gone: null, keep: false }] },
  }
  const actual = api.matchHostnameAll(api.domainMapRegexply(entries), 'api.test')
  const expected = original.matchHostnameAll(original.domainMapRegexply(clone(entries)), 'api.test')
  assert.deepEqual(actual, expected)
  assert.equal(2 in actual.matched, false)
  assert.equal(0 in actual.list, false)
  assert.equal(1 in actual.list, false)
  assert.equal(actual.list.length, 3)
  assert.equal(Object.hasOwn(actual.matched.groups, 'absent'), true)
  assert.equal(actual.matched.groups.absent, undefined)
  assert.equal(Object.getPrototypeOf(actual.matched.groups), null)

  const pattern = '(?<sub>api)(?<absent>z)?'
  const captured = api.isMatched('/api?x=1', pattern)
  assert.deepEqual(captured, original.isMatched('/api?x=1', pattern))
  assert.equal(2 in captured, true)
  assert.equal(captured[2], undefined)
  assert.equal(captured.index, 1)
  assert.equal(captured.input, '/api?x=1')
  assert.equal(Object.hasOwn(captured.groups, 'absent'), true)
  assert.equal(captured.groups.absent, undefined)
  assert.equal(Object.getPrototypeOf(captured.groups), null)
})

test('native metadata scanner respects escaping, character classes, lookbehind and concatenation', runtimeOptions, () => {
  const api = runtimeCore().match
  const original = sourceCore().match
  for (const [requestPath, pattern] of [
    ['/(api?a=7', '\\((?<word>[a-z]+)\\?a=(?<n>\\d+)(?<missing>x)?'],
    ['/api', '[(](x)|(?<word>api)'],
    ['/api', '(?<=/)(?<word>api)(?<missing>x)?'],
    ['/api', '(?:a)(p)(?<word>i)'],
  ]) {
    assert.deepEqual(api.isMatched(requestPath, pattern), original.isMatched(requestPath, pattern))
  }
  for (const entries of [
    { '^(?<first>api)\\.': {}, '^api(?<absent>z)?\\.': {} },
    { '^(?<first>api)\\.': {}, '^(api)\\.': {} },
    { '^(?<first>api)\\.': {}, 'api.test': { matched: ['configured'] } },
    { '^(?<first>api)\\.': {}, 'api.test': { matched: null } },
  ]) {
    assert.deepEqual(api.matchHostnameAll(api.domainMapRegexply(entries), 'api.test'),
      original.matchHostnameAll(original.domainMapRegexply(clone(entries)), 'api.test'))
  }
})

test('real first-wave scalar maps avoid the All JSON boundary; errors remain explicit on All use', runtimeOptions, () => {
  let compiles = 0
  const kotlin = {
    ...helpers,
    createHostnameAllMatcher (json) { compiles++; return helpers.createHostnameAllMatcher(json) },
  }
  const api = runtimeCore(kotlin).match
  const circular = {}
  circular.self = circular
  for (const value of [true, ['1.2.3.4'], circular]) {
    const map = api.domainMapRegexply({ 'x.test': value })
    assert.equal(api.matchHostname(map, 'x.test'), value)
  }
  assert.equal(compiles, 0)
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({ 'x.test': circular }), 'x.test'),
    /circular/i)
  assert.throws(() => api.matchHostnameAll(api.domainMapRegexply({ 'x.test': true }), 'x.test'))
})

test('actual first-wave context supports maps compiled before the runtime adapter is installed', runtimeOptions, () => {
  const { match, merge } = sourceCore()
  const entries = { '.test': { audit: true }, '*.test': null }
  let compiles = 0
  const kotlin = {
    ...helpers,
    createHostnameAllMatcher (json) {
      compiles++
      assert.deepEqual(JSON.parse(json), entries)
      return helpers.createHostnameAllMatcher(json)
    },
  }
  withModules(coreRoot, match, merge, () => {
    const context = installFirstWave(coreRoot, kotlin)
    const map = match.domainMapRegexply(entries)
    const circular = {}
    circular.self = circular
    const scalarMap = match.domainMapRegexply({ x: circular })
    assert.equal(context.bindings.get(map).entries, entries)
    match.matchHostnameAll = forbidden
    install(coreRoot, kotlin, context)
    assert.equal(match.matchHostname(scalarMap, 'x'), circular)
    assert.equal(compiles, 0)
    assert.deepEqual(match.matchHostnameAll(map, 'api.test'), undefined)
    assert.equal(compiles, 1)
  })
})

test('path runtime keeps the documented unsupported-pattern boundary without JS coercion', runtimeOptions, () => {
  const api = runtimeCore().match
  for (const pattern of [false, null, undefined, 1, [], {}, /api/]) {
    assert.equal(api.isMatched('/api?false=1', pattern), null)
  }
})

function interceptFile (relative) {
  return path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src', relative)
}

function serverConfig (entries, echDomains = []) {
  return {
    intercepts: entries,
    whiteList: {},
    preSetIpList: {},
    setting: {
      rootDir: '/unused',
      script: { defaultDir: 'scripts' },
      timeoutMapping: {},
    },
    plugin: { overwall: {} },
    dns: { mapping: {}, providers: {}, ech: { domains: echDomains } },
  }
}

function requestContext (hostname, requestPath) {
  return {
    rOptions: {
      hostname, path: requestPath, protocol: 'https:', port: 443,
      method: 'GET', headers: {},
    },
    log: logger,
  }
}

function optionsFactory (match, records = []) {
  const implementationFiles = [
    'lib/interceptor/impl/req/proxy.js',
    'lib/interceptor/impl/req/sni.js',
    'lib/interceptor/impl/req/cacheRequest.js',
    'lib/interceptor/impl/req/unVerifySsl.js',
    'lib/interceptor/impl/res/cacheResponse.js',
  ]
  const implementations = implementationFiles.map(file => loadSource(interceptFile(file)))
  implementations.push({
    name: 'audit',
    priority: 250,
    is: config => !!config.audit,
    requestIntercept (context, config, req, res, ssl, next, matched, hostnameMatched) {
      records.push({ config, matched, hostnameMatched })
    },
  }, {
    name: 'resAudit',
    priority: 251,
    is: config => !!config.resAudit,
    responseIntercept (context, config, req, res, proxyReq, proxyRes, ssl, next, matched, hostnameMatched) {
      records.push({ config, matched, hostnameMatched })
    },
  })
  const ech = loadSource(interceptFile('lib/proxy/common/ech/domain.js'),
    { '../../../../utils/util.match': match })
  let scriptPreparations = 0
  const factory = loadSource(interceptFile('options.js'), {
    './utils/util.match': match,
    './lib/dns': { initDNS: () => ({}) },
    './lib/proxy/common/ech/domain': ech,
    './lib/interceptor': implementations,
    './lib/interceptor/impl/res/script': {
      handleScriptInterceptConfig () { scriptPreparations++ },
    },
    './lib/proxy/middleware/overwall': {
      createOverwallMiddleware: () => null,
      getTmpPacFilePath: forbidden,
      downloadPacAsync: forbidden,
    },
  })
  return { factory, preparations: () => scriptPreparations }
}

// Independent production scenarios supplement the stored pure fixtures. They
// exercise actual options.js and the actual specialized activation predicates.
const activationCases = [
  { name: 'empty SNI is enabled', config: { sni: '' }, expected: ['sni'] },
  { name: 'verifyHost alone enables SNI', config: { verifyHost: 'verify.test' }, expected: ['sni'] },
  { name: 'proxy suppresses separate SNI', config: { proxy: 'https://target.test', sni: '' }, expected: ['proxy'] },
  { name: 'ECH ignores SNI', config: { sni: '', cacheMinutes: 1 }, ech: true, expected: ['cacheRequest', 'cacheResponse'] },
  { name: 'cache minutes', config: { cacheMinutes: 2 }, expected: ['cacheRequest', 'cacheResponse'] },
  { name: 'zero and negative cache do not activate', config: { cache: -1, cacheSeconds: 0 }, expected: [] },
  { name: 'truthy cache object does not activate', config: { cache: {} }, expected: [] },
  { name: 'ssl false activates exact boolean predicate', config: { ssl: false }, expected: ['unVerifySsl'] },
  { name: 'ssl string false does not activate', config: { ssl: 'false', unVerifySsl: 'true' }, expected: [] },
  { name: 'object exclusions use false-valued keys', config: { audit: true, exclusions: { '^/api': false } }, expected: [] },
  { name: 'hostname cleanup removes null-valued exclusion keys', config: { audit: true, exclusions: { '^/api': null } }, expected: ['audit'] },
  { name: 'array exclusions preserve query', config: { audit: true, exclusions: ['q=7'] }, expected: [] },
  { name: 'invalid exclusion can be followed by a match', config: { audit: true, exclusions: ['[', 'q=7'] }, expected: [] },
]

for (const item of activationCases) {
  test(`production createIntercepts: ${item.name}`, runtimeOptions, () => {
    const entries = { 'api.test': { '.*': item.config } }
    const ech = item.ech ? ['api.test'] : []
    for (const match of [sourceCore().match, runtimeCore().match]) {
      const source = optionsFactory(match)
      const options = source.factory(serverConfig(clone(entries), ech))
      const selected = options.createIntercepts(requestContext('api.test', '/api?q=7'))
      assert.deepEqual(selected.map(value => value.name), item.expected)
      assert.equal(source.preparations(), 1)
    }
  })
}

test('production selection preserves order, equal-order ties, priority and reserved hostname captures', runtimeOptions, () => {
  const entries = {
    '^(?<sub>api)\\.': {
      '.*': { audit: 'base', resAudit: true, order: 1 },
      '^/api': { audit: 'winner', order: 2 },
      'q=7': { audit: 'tie-loses', order: 2 },
      '^/': { audit: 'excluded', order: 99, exclusions: { 'q=7': false } },
    },
  }
  for (const match of [sourceCore().match, runtimeCore().match]) {
    const records = []
    const { factory } = optionsFactory(match, records)
    const options = factory(serverConfig(clone(entries)))
    const context = requestContext('api.test', '/api?q=7')
    const selected = options.createIntercepts(context)
    assert.deepEqual(selected.map(value => value.name), ['audit', 'resAudit'])
    selected[0].requestIntercept(context, {}, {}, true, () => {})
    selected[1].responseIntercept(context, {}, {}, {}, {}, true, () => {})
    assert.equal(records[0].config.audit, 'winner')
    assert.equal(records[0].config.key, '^/api')
    assert.equal(records[1].config.key, '.*')
    assert.deepEqual(Array.from(records[0].matched), ['/api'])
    assert.equal(records[0].matched.index, 0)
    assert.equal(records[0].matched.input, '/api?q=7')
    assert.equal(records[0].hostnameMatched.groups.sub, 'api')
    assert.deepEqual(Array.from(records[1].matched), ['/api?q=7'])
  }
})

test('production proxy execution consumes numbered and named host/path captures', runtimeOptions, () => {
  const entries = {
    '^(?<sub>api)(?<unused>z)?\\.': {
      '^/id/(?<id>\\d+)(?<optional>x)?': {
        proxy: 'https://target.test/${sub}/${id}/${h[1]}/${p[1]}?missing=${unused}${optional}',
      },
    },
  }
  for (const match of [sourceCore().match, runtimeCore().match]) {
    const options = optionsFactory(match).factory(serverConfig(clone(entries)))
    const context = requestContext('api.test', '/id/42')
    const selected = options.createIntercepts(context)
    assert.deepEqual(selected.map(value => value.name), ['proxy'])
    const headers = {}
    assert.equal(selected[0].requestIntercept(context, {}, {
      setHeader (key, value) { headers[key] = value },
    }, true, () => {}), true)
    assert.equal(context.rOptions.hostname, 'target.test')
    assert.equal(context.rOptions.path, '/api/42/api/42?missing=')
    assert.equal(headers['DS-Interceptor'], 'proxy: https://target.test/api/42/api/42?missing=')
  }
})

test('production cache stays nonenumerable, bounded and keyed by complete path including query', runtimeOptions, () => {
  const core = runtimeCore()
  const lookup = core.match.matchHostnameAll
  let lastRules
  core.match.matchHostnameAll = function () {
    lastRules = lookup.apply(this, arguments)
    return lastRules
  }
  const records = []
  const options = optionsFactory(core.match, records).factory(serverConfig({
    'api.test': { 'q=(?<id>\\d+)': { audit: true } },
  }))
  const one = requestContext('api.test', '/api?q=1')
  const first = options.createIntercepts(one)
  const cache = lastRules._pathCache
  assert.equal(Object.getOwnPropertyDescriptor(lastRules, '_pathCache').enumerable, false)
  assert.equal(Object.keys(lastRules).includes('_pathCache'), false)
  assert.equal(cache.maxSize, 512)
  assert.equal(cache.get('/api?q=1'), first)
  assert.equal(cache.get('/api?q=2'), undefined)
  first[0].requestIntercept(one, {}, {}, true, () => {})
  const two = requestContext('api.test', '/api?q=2')
  options.createIntercepts(two)[0].requestIntercept(two, {}, {}, true, () => {})
  assert.equal(records[0].matched.groups.id, '1')
  assert.equal(records[1].matched.groups.id, '2')

  // Exercise the existing options.js cache-hit branch with its prior result.
  // HostnameAllMatcher itself intentionally returns fresh results per query.
  const cachedRules = lastRules
  core.match.matchHostnameAll = () => cachedRules
  const pathMatch = core.match.isMatched
  core.match.isMatched = forbidden
  assert.equal(options.createIntercepts(two), cachedRules._pathCache.get('/api?q=2'))
  core.match.isMatched = pathMatch
  for (let index = 0; index < 520; index++) {
    options.createIntercepts(requestContext('api.test', `/api?q=${index}`))
  }
  assert.equal(cachedRules._pathCache.size, 512)
  assert.equal(cachedRules._pathCache.has('/api?q=0'), false)
})

test('production onUse hooks prove Kotlin calls, lazy compilation and query failure propagation', runtimeOptions, () => {
  const events = []
  let compiles = 0
  const error = new Error('Kotlin path query failed')
  const kotlin = {
    ...helpers,
    createHostnameAllMatcher (json) { compiles++; return helpers.createHostnameAllMatcher(json) },
  }
  const { match } = runtimeCore(kotlin, { onUse: name => events.push(name) })
  const options = optionsFactory(match).factory(serverConfig({
    'api.test': { '^/api': { audit: true } },
  }))
  assert.equal(compiles, 0)
  assert.deepEqual(events, [])
  options.createIntercepts(requestContext('api.test', '/api'))
  options.createIntercepts(requestContext('api.test', '/api?q=2'))
  assert.equal(compiles, 1)
  assert.deepEqual(events, ['hostname-all', 'path-rules', 'hostname-all', 'path-rules'])
  kotlin.matchPathRule = () => { throw error }
  assert.throws(() => options.createIntercepts(requestContext('api.test', '/api?q=3')),
    candidate => candidate === error)
  assert.deepEqual(events.slice(-1), ['hostname-all'])
})
