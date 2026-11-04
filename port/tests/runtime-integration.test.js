'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const project = path.resolve(process.env.DSHA_TEST_PROJECT || 'port/.run/runtime')
const core = path.join(project, 'core')
process.env.HOME = path.join(project, 'home')
process.env.USERPROFILE = process.env.HOME
process.env.DEV_SIDECAR_LOG_DISABLED = 'true'
process.env.DSHA_KOTLIN_HELPERS = path.join(project, 'kotlin/dev-sidecar-lightning-netcore-js.js')
fs.mkdirSync(process.env.HOME, { recursive: true })

const helpers = require(process.env.DSHA_KOTLIN_HELPERS)
const mergePath = path.join(core, 'node_modules/@blue-frontier/dev-sidecar/src/merge.js')
const matchPath = path.join(core, 'node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js')
const nat64Path = path.join(core, 'node_modules/@blue-frontier/mitmproxy/src/lib/dns/nat64.js')
const merge = require(mergePath)
const originalMerge = merge.doMerge
const originalCleanup = merge.deleteNullItems
const counts = { merges: 0, cleans: 0, matches: 0, nat64: 0, all: 0, paths: 0, choices: 0, requests: 0 }
const tracked = {
  ...helpers,
  mergeConfig (...args) { counts.merges++; return helpers.mergeConfig(...args) },
  sanitizeConfig (...args) { counts.cleans++; return helpers.sanitizeConfig(...args) },
  createHostnameMatcher (...args) {
    const compiled = helpers.createHostnameMatcher(...args)
    return { match (host) { counts.matches++; return compiled.match(host) } }
  },
  createHostnameAllMatcher (...args) {
    const compiled = helpers.createHostnameAllMatcher(...args)
    return { match (host) { counts.all++; return compiled.match(host) } }
  },
  matchPathRule (...args) { counts.paths++; return helpers.matchPathRule(...args) },
  createDynamicChoice (...args) { counts.choices++; return helpers.createDynamicChoice(...args) },
  wave2RequestTransform (...args) { counts.requests++; return helpers.wave2RequestTransform(...args) },
}
const context = require(path.join(project, 'kotlin-helpers.js')).install(core, tracked)
require(path.join(project, 'kotlin-wave2.js')).install(core, tracked, context)
const initialization = { ...counts }

test('initial core configuration loading already delegates to Kotlin', () => {
  assert.ok(initialization.merges > 0)
  assert.ok(initialization.cleans > 0)
})

test('real core default configuration and layered loader use Kotlin merge and cleanup', () => {
  const { defaultConfig } = require(path.join(core, 'node_modules/@blue-frontier/dev-sidecar/src/config'))
  const overlay = {
    server: {
      port: 32181,
      preSetIpList: { 'example.test': ['192.0.2.1'] },
      intercepts: { 'example.test': { '^/path': { sni: 'front.test', enabled: false } } },
    },
    app: { keepLogFileCount: 0 },
  }
  const base = JSON.parse(JSON.stringify(defaultConfig))
  const expected = originalMerge(JSON.parse(JSON.stringify(base)), overlay)
  originalCleanup(expected)
  const actual = merge.doMerge(base, overlay)
  merge.deleteNullItems(actual)
  assert.deepEqual(actual, expected)
  assert.deepEqual(Object.keys(actual), Object.keys(expected))
  assert.ok(counts.merges > 0)
  assert.ok(counts.cleans > 0)
})

test('real domain utility returns original values and keeps matchHostnameAll working', () => {
  const util = require(matchPath)
  const exact = { sni: 'front.test', nested: { value: 1 } }
  const wildcard = { nested: { inherited: 2 }, enabled: false }
  const map = util.domainMapRegexply({ '*.example.test': wildcard, 'api.example.test': exact })
  assert.equal(util.matchHostname(map, 'api.example.test'), exact)
  assert.equal(util.matchHostname(map, 'www.example.test'), wildcard)
  assert.deepEqual(
    util.matchHostnameAll(map, 'api.example.test'),
    { nested: { inherited: 2, value: 1 }, enabled: false, sni: 'front.test' },
  )
  assert.ok(counts.matches >= 2)
  assert.ok(counts.all > 0)
})

test('real path, request options and cached candidate calls dispatch into Kotlin', () => {
  const base = path.join(core, 'node_modules/@blue-frontier/mitmproxy/src')
  const util = require(path.join(base, 'utils/util.match.js'))
  assert.deepEqual(Array.from(util.isMatched('/api?q=1', 'q=(\\d+)')), ['q=1', '1'])
  const { ChoiceCache } = require(path.join(base, 'lib/choice/index.js'))
  const cache = new ChoiceCache()
  const choice = cache.getOrCreate('integration.test', ['192.0.2.1', '192.0.2.2'])
  assert.equal(choice.value, '192.0.2.1')
  choice.doCount(choice.value, true)
  assert.equal(choice.value, '192.0.2.2')
  assert.equal(cache.getOrCreate('integration.test', ['192.0.2.3']), choice)
  const requestUtil = require(path.join(base, 'lib/proxy/common/util.js'))
  const options = requestUtil.getOptionsFromRequest({
    url: '/path%2f?q=a+b', method: 'GET', socket: {},
    headers: { host: 'example.test', connection: 'close', 'proxy-connection': 'keep-alive' },
  }, true, null, { timeoutMapping: util.domainMapRegexply({}), tlsVersionMapping: {}, verifySsl: true })
  assert.equal(options.protocol, 'https:')
  assert.equal(options.path, '/path%2f?q=a+b')
  assert.equal(options.headers.connection, 'close')
  assert.equal(options.headers['proxy-connection'], undefined)
  assert.equal(options.agent.options.minVersion, 'TLSv1.3')
  options.agent.destroy()
  for (const name of ['all', 'paths', 'choices', 'requests']) assert.ok(counts[name] > 0, name)
})

test('real NAT64 lookup internal closure delegates to compiled Kotlin without external DNS', async () => {
  const original = helpers.toNat64Address
  helpers.toNat64Address = (...args) => { counts.nat64++; return original(...args) }
  try {
    const Nat64 = require(nat64Path)
    const resolver = new Nat64({ prefix: '64:ff9b::/96' }, {})
    resolver._queryIpv4 = async () => ['192.0.2.33', '256.1.2.3']
    assert.deepEqual(await resolver._lookup('example.test'), ['64:ff9b:0:0:0:0:c000:221'])
    assert.equal(counts.nat64, 2)
    assert.equal(Nat64.toNat64Address('::', '192.0.2.33'), null)
  } finally {
    helpers.toNat64Address = original
  }
})
