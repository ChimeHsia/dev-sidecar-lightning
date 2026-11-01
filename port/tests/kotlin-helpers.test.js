// SPDX-License-Identifier: MPL-2.0
'use strict'

// Stub contracts: node --test port/tests/kotlin-helpers.test.js
// Real Kotlin: set DSHA_KOTLIN_HELPERS to the absolute compiled CommonJS file.
// Alternatively: node port/tests/kotlin-helpers.test.js --helpers=/absolute/file.js
// An explicitly supplied module must load and expose the real root API.
const assert = require('node:assert/strict')
const Module = require('node:module')
const path = require('node:path')
const { test } = require('node:test')
const { install } = require('../node/kotlin-helpers')

const coreRoot = path.resolve(__dirname, 'fixture-core')
const mergePath = path.join(coreRoot, 'node_modules/@blue-frontier/dev-sidecar/src/merge.js')
const matchPath = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js')

function forbidden () {
  throw new Error('The adapter must not call the original business implementation')
}

// Mock only module loading; the adapter sees the same absolute require paths
// used with an extracted core. No fixture files or installed packages needed.
function fixture (helpers, compile = () => ({ origin: {} }), onMatchLoad = () => {}) {
  const doDiff = function () { return 'original diff' }
  const matchHostnameAll = function (map) { return map.origin }
  const merge = { doMerge: forbidden, deleteNullItems: forbidden, doDiff }
  const match = { domainMapRegexply: compile, matchHostname: forbidden, matchHostnameAll }
  const load = Module._load
  const loaded = []
  Module._load = function (name) {
    if (name === mergePath || name === matchPath) {
      loaded.push(name)
      if (name === matchPath) onMatchLoad(merge)
      return name === mergePath ? merge : match
    }
    return load.apply(this, arguments)
  }
  try {
    install(coreRoot, helpers)
  } finally {
    Module._load = load
  }
  return { merge, match, doDiff, matchHostnameAll, loaded }
}

function stubHelpers (overrides = {}) {
  return {
    createHostnameMatcher: forbidden,
    mergeConfig: forbidden,
    sanitizeConfig: forbidden,
    toNat64Address: forbidden,
    ...overrides,
  }
}

test('install loads absolute modules and leaves doDiff and matchHostnameAll untouched', () => {
  const f = fixture(stubHelpers())
  assert.deepEqual(f.loaded, [mergePath, matchPath])
  assert.equal(f.merge.doDiff, f.doDiff)
  assert.equal(f.match.matchHostnameAll, f.matchHostnameAll)
  assert.equal(f.merge.doDiff(), 'original diff')
})

test('initial config loaded by the matcher module uses both patched Kotlin merge APIs', () => {
  const initialConfig = { nested: { keep: 1, remove: 'old' } }
  const overlay = { nested: { remove: null, enabled: false } }
  const calls = []
  const f = fixture(stubHelpers({
    mergeConfig (baseJson, overlayJson) {
      calls.push('merge')
      assert.deepEqual(JSON.parse(baseJson), { nested: { keep: 1, remove: 'old' } })
      assert.deepEqual(JSON.parse(overlayJson), overlay)
      return '{"nested":{"keep":1,"remove":null,"enabled":false}}'
    },
    sanitizeConfig (json) {
      calls.push('sanitize')
      assert.deepEqual(JSON.parse(json), { nested: { keep: 1, remove: null, enabled: false } })
      return '{"nested":{"keep":1,"enabled":false}}'
    },
  }), undefined, mergeApi => {
    // Simulate util.match -> logger -> config/index during require(matchPath).
    assert.equal(mergeApi.doMerge(initialConfig, overlay), initialConfig)
    assert.equal(mergeApi.deleteNullItems(initialConfig), undefined)
  })
  assert.deepEqual(f.loaded, [mergePath, matchPath])
  assert.deepEqual(calls, ['merge', 'sanitize'])
  assert.deepEqual(initialConfig, { nested: { keep: 1, enabled: false } })
})

test('missing helper methods fail explicitly', () => {
  assert.throws(() => install(coreRoot, {}), /Missing Kotlin helper: createHostnameMatcher/)
})

test('doMerge delegates JSON once, mutates the old root and preserves deletion markers', () => {
  const nested = { inherited: 1, remove: 'old' }
  const oldItems = ['old', 'tail']
  const base = { nested, items: oldItems, enabled: true }
  const overlay = { nested: { remove: null }, items: [false, 0, ''], enabled: '[delete]' }
  let calls = 0
  const f = fixture(stubHelpers({
    mergeConfig (baseJson, overlayJson) {
      calls++
      assert.deepEqual(JSON.parse(baseJson), {
        nested: { inherited: 1, remove: 'old' }, items: ['old', 'tail'], enabled: true,
      })
      assert.deepEqual(JSON.parse(overlayJson), overlay)
      return '{"nested":{"inherited":1,"remove":null},"items":[false,0,""],"enabled":"[delete]"}'
    },
  }))
  assert.equal(f.merge.doMerge(base, overlay), base)
  assert.equal(calls, 1)
  assert.equal(base.nested, nested)
  assert.deepEqual(base, {
    nested: { inherited: 1, remove: null }, items: [false, 0, ''], enabled: '[delete]',
  })
  assert.notEqual(base.items, oldItems)
  assert.notEqual(base.items, overlay.items)
  assert.deepEqual(oldItems, ['old', 'tail'])
  assert.deepEqual(overlay, {
    nested: { remove: null }, items: [false, 0, ''], enabled: '[delete]',
  })
})

test('doMerge normalizes a null overlay and rejects non-object inputs', () => {
  const f = fixture(stubHelpers({
    mergeConfig (base, overlay) {
      assert.equal(base, '{"kept":1}')
      assert.equal(overlay, '{}')
      return base
    },
  }))
  const base = { kept: 1 }
  assert.equal(f.merge.doMerge(base, null), base)
  assert.equal(f.merge.doMerge(base, undefined), base)
  assert.throws(() => f.merge.doMerge([], {}), /expects JSON objects/)
  assert.throws(() => f.merge.doMerge({}, []), /expects JSON objects/)
})

test('sanitize keeps nested identities, length and existing holes while deleting new slots', () => {
  const leaf = { remove: '[delete]', keep: false }
  const inner = [null, 0, '[delete]', '']
  const items = [null, leaf, '[delete]', inner, , null]
  const nested = { items, remove: null }
  const root = { nested, remove: '[delete]' }
  let calls = 0
  const f = fixture(stubHelpers({
    sanitizeConfig (json) {
      calls++
      assert.deepEqual(JSON.parse(json), {
        nested: {
          items: [null, { remove: '[delete]', keep: false }, '[delete]', [null, 0, '[delete]', ''], null, null],
          remove: null,
        },
        remove: '[delete]',
      })
      return '{"nested":{"items":[null,{"keep":false},null,[null,0,null,""],null,null]}}'
    },
  }))
  assert.equal(f.merge.deleteNullItems(root), undefined)
  assert.equal(calls, 1)
  assert.equal(root.nested, nested)
  assert.equal(nested.items, items)
  assert.equal(items[1], leaf)
  assert.equal(items[3], inner)
  assert.deepEqual(leaf, { keep: false })
  assert.equal(items.length, 6)
  assert.equal(inner.length, 4)
  for (const index of [0, 2, 4, 5]) assert.equal(index in items, false)
  for (const index of [0, 2]) assert.equal(index in inner, false)
  assert.equal(inner[1], 0)
  assert.equal(inner[3], '')
  assert.equal('remove' in root, false)
  assert.equal('remove' in nested, false)
})

test('sanitize wraps root arrays for the object-only Kotlin API', () => {
  const child = { keep: 1, remove: null }
  const target = [null, child, '[delete]', , false]
  const f = fixture(stubHelpers({
    sanitizeConfig (json) {
      assert.deepEqual(JSON.parse(json), {
        value: [null, { keep: 1, remove: null }, '[delete]', null, false],
      })
      return '{"value":[null,{"keep":1},null,null,false]}'
    },
  }))
  assert.equal(f.merge.deleteNullItems(target), undefined)
  assert.equal(target.length, 5)
  assert.equal(target[1], child)
  assert.deepEqual(child, { keep: 1 })
  assert.equal(target[4], false)
  for (const index of [0, 2, 3]) assert.equal(index in target, false)
  assert.equal(f.merge.deleteNullItems(null), undefined)
  assert.equal(f.merge.deleteNullItems(undefined), undefined)
})

test('config writeback treats prototype-like keys as data', () => {
  const base = {}
  const f = fixture(stubHelpers({
    mergeConfig: () => '{"__proto__":{"adapterPolluted":true},"constructor":{"prototype":{"safe":1}}}',
    sanitizeConfig: () => '{"__proto__":{},"constructor":{"prototype":{"safe":1}}}',
  }))
  f.merge.doMerge(base, {})
  const ownPrototype = base.__proto__
  assert.equal(Object.getPrototypeOf(base), Object.prototype)
  assert.equal({}.adapterPolluted, undefined)
  assert.equal(Object.hasOwn(base, '__proto__'), true)
  f.merge.deleteNullItems(base)
  assert.equal(base.__proto__, ownPrototype)
  assert.deepEqual(ownPrototype, {})
  assert.equal({}.adapterPolluted, undefined)
})

test('helper exceptions and invalid configuration results never fall back or mutate the root', () => {
  const error = new Error('Kotlin failed')
  const f = fixture(stubHelpers({
    mergeConfig: () => { throw error },
    sanitizeConfig: () => { throw error },
  }))
  const root = { keep: 1 }
  assert.throws(() => f.merge.doMerge(root, {}), value => value === error)
  assert.throws(() => f.merge.deleteNullItems(root), value => value === error)
  assert.deepEqual(root, { keep: 1 })
  const invalid = fixture(stubHelpers({
    mergeConfig: () => '[]',
    sanitizeConfig: () => '[]',
  }))
  assert.throws(() => invalid.merge.doMerge(root, {}), /must return a JSON object/)
  assert.throws(() => invalid.merge.deleteNullItems(root), /root container type/)
  assert.deepEqual(root, { keep: 1 })
})

test('hostname wrapper keeps the original shape and binds original values including false and zero', () => {
  const reference = { token: 1 }
  reference.circular = reference
  const source = {
    'reference.example': reference,
    'false.example': false,
    'zero.example': 0,
    'null.example': null,
    'undefined.example': undefined,
  }
  const compiled = { '^.*$': reference, origin: { 'reference.example': reference } }
  let compiles = 0
  let originals = 0
  const f = fixture(stubHelpers({
    createHostnameMatcher (keys, present) {
      compiles++
      assert.deepEqual(keys, Object.keys(source))
      assert.deepEqual(present, [true, true, true, false, false])
      return {
        match (hostname) {
          const index = keys.indexOf(hostname)
          return index < 0 ? null : index
        },
      }
    },
  }), function (map) {
    originals++
    assert.equal(map, source)
    return compiled
  })
  assert.equal(f.match.domainMapRegexply(source), compiled)
  assert.equal(f.match.matchHostnameAll(compiled), compiled.origin)
  assert.equal(f.match.matchHostname(compiled, 'reference.example'), reference)
  assert.equal(f.match.matchHostname(compiled, 'false.example'), false)
  assert.equal(f.match.matchHostname(compiled, 'zero.example'), 0)
  assert.equal(f.match.matchHostname(compiled, 'null.example'), null)
  assert.equal(f.match.matchHostname(compiled, 'undefined.example'), undefined)
  assert.equal(f.match.matchHostname(compiled, 'missing.example'), undefined)
  assert.equal(compiles, 1)
  assert.equal(originals, 1)
})

test('hostname queries do not serialize or recompile and bindings stay independent', () => {
  let compiles = 0
  let queries = 0
  const f = fixture(stubHelpers({
    createHostnameMatcher () {
      compiles++
      return { match: () => { queries++; return 0 } }
    },
  }))
  const firstValue = { value: 1 }
  const secondValue = { value: 2 }
  const first = f.match.domainMapRegexply({ first: firstValue })
  const second = f.match.domainMapRegexply({ second: secondValue })
  const stringify = JSON.stringify
  const parse = JSON.parse
  JSON.stringify = forbidden
  JSON.parse = forbidden
  try {
    for (let i = 0; i < 100; i++) {
      assert.equal(f.match.matchHostname(first, 'first'), firstValue)
      assert.equal(f.match.matchHostname(second, 'second'), secondValue)
    }
  } finally {
    JSON.stringify = stringify
    JSON.parse = parse
  }
  assert.equal(compiles, 2)
  assert.equal(queries, 200)
})

test('unwrapped empty maps are misses and nonempty maps fail explicitly', () => {
  const f = fixture(stubHelpers())
  assert.equal(f.match.matchHostname({}, 'host'), undefined)
  assert.equal(f.match.matchHostname({ origin: {} }, 'host'), undefined)
  assert.equal(f.match.matchHostname(null, 'host'), null)
  for (const map of [
    { origin: { host: false } },
    { origin: {}, '^host$': 0 },
    { host: true },
  ]) {
    assert.throws(() => f.match.matchHostname(map, 'host'), /installed domainMapRegexply/)
  }
})

test('null and empty input maps compile once and remain Kotlin-backed', () => {
  let compiles = 0
  const f = fixture(stubHelpers({
    createHostnameMatcher (keys, present) {
      compiles++
      assert.deepEqual(keys, [])
      assert.deepEqual(present, [])
      return { match: () => null }
    },
  }))
  const empty = f.match.domainMapRegexply(null)
  assert.equal(f.match.matchHostname(empty, 'host'), undefined)
  assert.equal(f.match.matchHostname(empty, 'host'), undefined)
  assert.equal(compiles, 1)
})

test('invalid matcher results and matcher exceptions fail without JS fallback', () => {
  for (const index of [-1, 1, 0.5, undefined, '0', false]) {
    const f = fixture(stubHelpers({
      createHostnameMatcher: () => ({ match: () => index }),
    }))
    const map = f.match.domainMapRegexply({ host: true })
    assert.throws(() => f.match.matchHostname(map, 'host'), /invalid index/)
  }
  const error = new Error('Kotlin matcher failed')
  const f = fixture(stubHelpers({
    createHostnameMatcher: () => ({ match: () => { throw error } }),
  }))
  const map = f.match.domainMapRegexply({ host: true })
  assert.throws(() => f.match.matchHostname(map, 'host'), value => value === error)
})

const argument = process.argv.find(value => value.startsWith('--helpers='))
const argumentIndex = process.argv.indexOf('--helpers')
const helpersPath = argument
  ? argument.slice('--helpers='.length)
  : (argumentIndex >= 0 ? process.argv[argumentIndex + 1] : process.env.DSHA_KOTLIN_HELPERS)
if ((argument || argumentIndex >= 0) && !helpersPath) {
  throw new Error('--helpers requires a compiled Kotlin/JS module path')
}
const realHelpers = helpersPath ? require(path.resolve(helpersPath)) : null
const realOptions = { skip: !realHelpers && 'Set DSHA_KOTLIN_HELPERS or --helpers to test compiled Kotlin/JS' }

function tracedRealHelpers () {
  const calls = { createHostnameMatcher: 0, match: 0, mergeConfig: 0, sanitizeConfig: 0, toNat64Address: 0 }
  const helpers = {}
  for (const name of Object.keys(calls).filter(name => name !== 'match')) {
    assert.equal(typeof realHelpers[name], 'function', `Missing compiled Kotlin export: ${name}`)
    helpers[name] = function (...args) {
      calls[name]++
      const result = realHelpers[name](...args)
      if (name !== 'createHostnameMatcher') return result
      return { match: hostname => { calls.match++; return result.match(hostname) } }
    }
  }
  return { helpers, calls }
}

test('real Kotlin merge then sanitize deletes inherited values and preserves nested identities', realOptions, () => {
  const { helpers, calls } = tracedRealHelpers()
  const f = fixture(helpers)
  const nested = { kept: 1, remove: { inherited: true } }
  const base = { nested, old: true, items: ['old', 'tail'] }
  const overlay = { nested: { remove: null, added: 0 }, old: '[delete]', items: [false, null, '', '[delete]'] }
  assert.equal(f.merge.doMerge(base, overlay), base)
  assert.equal(base.nested, nested)
  assert.equal(base.nested.remove, null)
  assert.equal(base.old, '[delete]')
  const items = base.items
  assert.equal(f.merge.deleteNullItems(base), undefined)
  assert.equal(base.nested, nested)
  assert.equal(base.items, items)
  assert.deepEqual(nested, { kept: 1, added: 0 })
  assert.equal(Object.hasOwn(base, 'old'), false)
  assert.equal(items.length, 4)
  assert.equal(items[0], false)
  assert.equal(items[2], '')
  assert.equal(1 in items, false)
  assert.equal(3 in items, false)
  assert.deepEqual(overlay, {
    nested: { remove: null, added: 0 }, old: '[delete]', items: [false, null, '', '[delete]'],
  })
  assert.equal(calls.mergeConfig, 1)
  assert.equal(calls.sanitizeConfig, 1)
  assert.equal(calls.toNat64Address, 0)
})

test('real Kotlin sanitizer preserves holes, empty containers and root array identity', realOptions, () => {
  const { helpers, calls } = tracedRealHelpers()
  const f = fixture(helpers)
  const emptyObject = { gone: '[delete]' }
  const inner = [null, 0, '', false, '[delete]']
  const root = [null, emptyObject, inner, , '[delete]']
  f.merge.deleteNullItems(root)
  assert.equal(root.length, 5)
  assert.equal(root[1], emptyObject)
  assert.equal(root[2], inner)
  assert.deepEqual(emptyObject, {})
  assert.equal(inner.length, 5)
  for (const index of [0, 3, 4]) assert.equal(index in root, false)
  for (const index of [0, 4]) assert.equal(index in inner, false)
  assert.deepEqual(inner.slice(1, 4), [0, '', false])
  f.merge.deleteNullItems(root)
  assert.equal(root[2], inner)
  assert.equal(3 in root, false)
  assert.equal(calls.sanitizeConfig, 2)
  assert.deepEqual(f.merge.doMerge({}, {}), {})
})

test('real Kotlin hostname matching preserves precedence and original references without JSON', realOptions, () => {
  const { helpers, calls } = tracedRealHelpers()
  const f = fixture(helpers)
  const wildcard = { rule: 'wildcard' }
  const alias = { rule: 'alias' }
  const regex = { rule: 'regex' }
  wildcard.circular = wildcard
  const source = {
    'exact.example': false,
    'zero.example': 0,
    'empty.example': '',
    'null.example': null,
    '*.example': wildcard,
    '.alias.test': alias,
    '^api[0-9]+\\.test$': regex,
  }
  const compiled = f.match.domainMapRegexply(source)
  const stringify = JSON.stringify
  const parse = JSON.parse
  JSON.stringify = forbidden
  JSON.parse = forbidden
  try {
    for (let i = 0; i < 20; i++) {
      assert.equal(f.match.matchHostname(compiled, 'exact.example'), false)
      assert.equal(f.match.matchHostname(compiled, 'zero.example'), 0)
      assert.equal(f.match.matchHostname(compiled, 'empty.example'), '')
      assert.equal(f.match.matchHostname(compiled, 'null.example'), wildcard)
      assert.equal(f.match.matchHostname(compiled, 'example'), wildcard)
      assert.equal(f.match.matchHostname(compiled, 'sub.example'), wildcard)
      assert.equal(f.match.matchHostname(compiled, 'alias.test'), alias)
      assert.equal(f.match.matchHostname(compiled, 'sub.alias.test'), alias)
      assert.equal(f.match.matchHostname(compiled, 'api12.test'), regex)
      assert.equal(f.match.matchHostname(compiled, 'missing.test'), undefined)
    }
  } finally {
    JSON.stringify = stringify
    JSON.parse = parse
  }
  assert.equal(calls.createHostnameMatcher, 1)
  assert.equal(calls.match, 200)
  assert.equal(calls.mergeConfig, 0)
  assert.equal(calls.sanitizeConfig, 0)
})

test('real Kotlin handles null and alias priority and skips invalid patterns before wildcard fallback', realOptions, () => {
  const { helpers, calls } = tracedRealHelpers()
  const f = fixture(helpers)
  const map = f.match.domainMapRegexply({
    'null.test': null,
    '.alias.test': 'alias',
    '*.alias.test': false,
  })
  assert.equal(f.match.matchHostname(map, 'null.test'), undefined)
  assert.equal(f.match.matchHostname(map, 'alias.test'), false)
  assert.equal(f.match.matchHostname(map, 'sub.alias.test'), false)
  assert.equal(f.match.matchHostname(map, 'missing.test'), undefined)
  assert.equal(calls.createHostnameMatcher, 1)
  const fallback = { rule: 'fallback' }
  const withInvalidPattern = f.match.domainMapRegexply({
    '^[': { rule: 'invalid' },
    '*': fallback,
  })
  assert.equal(f.match.matchHostname(withInvalidPattern, 'anything.test'), fallback)
  assert.equal(f.match.matchHostname(withInvalidPattern, 'another.test'), fallback)
  assert.equal(calls.createHostnameMatcher, 2)
})

test('real Kotlin NAT64 export is callable directly and install leaves it untouched', realOptions, () => {
  const { helpers, calls } = tracedRealHelpers()
  const nat64 = helpers.toNat64Address
  fixture(helpers)
  assert.equal(helpers.toNat64Address, nat64)
  assert.equal(nat64('64:ff9b::/96', '192.0.2.33'), '64:ff9b:0:0:0:0:c000:221')
  assert.equal(nat64('64:ff9b::/96', '999.0.2.33'), null)
  assert.equal(nat64('invalid', '192.0.2.33'), null)
  assert.equal(calls.toNat64Address, 3)
})
