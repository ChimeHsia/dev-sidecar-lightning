'use strict'

// Test-only upstream oracle. Never loads personal files or starts a Node proxy.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')

const [mode, coreInput, outputInput] = process.argv.slice(2)
if (!['generate', 'verify'].includes(mode) || !coreInput || !outputInput) {
  throw new Error('Usage: node oracle.cjs generate|verify <upstream-core> <evidence-directory>')
}
const core = path.resolve(coreInput)
const output = path.resolve(outputInput)
const configRoot = path.join(core, 'node_modules/@blue-frontier/dev-sidecar/src/config')
const JSON5 = require(path.join(core, 'node_modules/json5'))
assert.equal(require(path.join(core, 'node_modules/json5/package.json')).version, '2.2.3')
const lodash = require(path.join(core, 'node_modules/lodash'))
const merge = require(path.join(configRoot, '../merge.js'))
const silent = { info () {}, error () {}, warn () {}, debug () {} }
const sha = data => crypto.createHash('sha256').update(data).digest('hex')

function moduleFromSource (filename, substitutions, transform = text => text) {
  const source = transform(fs.readFileSync(filename, 'utf8'))
  const module = { exports: {} }
  const factory = vm.runInNewContext(
    `(function(require,module,exports,process){${source}\n})`,
    { URL, console: silent }, { filename, timeout: 1000 })
  factory(name => {
    if (Object.hasOwn(substitutions, name)) return substitutions[name]
    throw new Error(`Unapproved oracle import: ${name}`)
  }, module, module.exports, { env: { HOME: '/synthetic-config-oracle' } })
  return module.exports
}
const urls = moduleFromSource(path.join(configRoot, 'remote-config-url.js'), {
  '../utils/util.log-or-console': silent,
})
const defaults = {
  dohUrl: 'https://resolver.example.test/dns-query',
  providerName: 'cf-DoH',
  providerHostMatch: 'resolver.example.test',
  presetIpLists: { 'rules.example.test': { '192.0.2.8': true } },
}
// Substitute bootstrap data literals only; preserve the actual upstream algorithm.
// No production service URL, preset address or personal config enters fixtures.
const rules = moduleFromSource(path.join(configRoot, 'remote-config-rule.js'), {}, source => {
  assert.equal((source.match(/^const CLOUDFLARE_DOH_SERVER = .+$/gm) || []).length, 1)
  assert.equal((source.match(/conf\.server\.includes\('[^']+'\)/g) || []).length, 1)
  return source.replace(/^const CLOUDFLARE_DOH_SERVER = .+$/m,
    `const CLOUDFLARE_DOH_SERVER = ${JSON.stringify(defaults.dohUrl)}`)
    .replace(/conf\.server\.includes\('[^']+'\)/,
      `conf.server.includes(${JSON.stringify(defaults.providerHostMatch)})`)
})
const serverModule = fs.readFileSync(path.join(configRoot, '../modules/server/index.js'), 'utf8')
const applyStart = serverModule.indexOf('function applyRemoteConfigFetchRules (')
const applyEnd = serverModule.indexOf('\nconst serverApi =', applyStart)
assert.ok(applyStart >= 0 && applyEnd > applyStart)
const applyFetchRules = vm.runInNewContext(
  serverModule.slice(applyStart, applyEnd) + '\napplyRemoteConfigFetchRules',
  { buildRemoteConfigFetchRules: rules.buildRemoteConfigFetchRules, log: silent },
  { timeout: 1000 })

const seed = {
  app: { closeStrategy: 2 }, proxy: { enabled: false },
  plugin: Object.fromEntries(['node', 'git', 'pip', 'share', 'free_eye', 'overwall']
    .map(name => [name, { enabled: false }])),
  server: { enabled: true, port: 31181 },
}

function ownOrders (value, pointer = '$', rows = []) {
  if (value && typeof value === 'object') {
    if (!Array.isArray(value)) rows.push([pointer, Object.keys(value)])
    Object.keys(value).forEach(key => ownOrders(value[key], `${pointer}/${key}`, rows))
  }
  return rows
}

function layered (input) {
  const user = input.user == null ? lodash.cloneDeep(seed) : JSON5.parse(input.user)
  const personal = input.personal == null ? {} : JSON5.parse(input.personal)
  const shared = input.shared == null ? {} : JSON5.parse(input.shared)
  const loader = moduleFromSource(path.join(configRoot, 'local-config-loader.js'), {
    'node:fs': {
      existsSync: () => true,
      mkdirSync: () => { throw new Error('Unexpected write in oracle') },
      readFileSync: filename => JSON.stringify(filename.includes('_personal') ? personal : shared),
    },
    'node:path': path,
    lodash, '@blue-frontier/mitmproxy/src/json': { parse: text => JSON5.parse(String(text)) },
    '../merge': merge, '../utils/util.log-or-console': silent,
  })
  const all = loader.getConfigFromFiles(user, JSON5.parse(input.defaults))
  all.proxy ||= {}
  all.proxy.enabled = false
  all.plugin ||= {}
  Object.values(all.plugin).forEach(plugin => { plugin.enabled = false })
  urls.applyRemoteConfigUrlHttps(all)
  if (all.app?.remoteConfig?.enabled === true) {
    const server = all.server ||= {}
    const dns = server.dns ||= {}
    dns.providers ||= {}
    // Synthetic caller bootstrap replaces the production preset defaults only.
    const remote = all.app.remoteConfig
    const hadFetchRule = Object.hasOwn(remote, 'fetchRule')
    const hadPresets = Object.hasOwn(remote.fetchRule || {}, 'preSetIpList')
    remote.fetchRule ||= {}
    if (!Object.hasOwn(remote.fetchRule, 'preSetIpList')) {
      remote.fetchRule.preSetIpList = defaults.presetIpLists
    }
    server.intercepts ||= {}
    applyFetchRules(all, server)
    // Default bootstrap data is caller policy, not a new field in persisted configuration.
    if (!hadPresets) delete remote.fetchRule.preSetIpList
    if (!hadFetchRule) delete remote.fetchRule
  }
  return all
}

if (mode === 'generate') {
  const parseSources = [
    '{}', '{unquoted:true}', "{single:'text',double:\"text\",}",
    '/* a */ {a:1,// b\n b:2,}', '{n:+42,minus:-2,hex:0xff,neghex:-0X2a}',
    '{a:.5,b:5.,c:+.25,d:1e-3,e:-0}', "{line:'a\\\nb',cr:'a\\\r\nb'}",
    "{escape:'\\b\\f\\n\\r\\t\\v\\0\\x41\\u4e2d\\q'}",
    '{"quoted Unicode":"\\uD83D\\uDE00"}', '{\\u0061:1,$key:2,_key:3}',
    '\ufeff{\u00a0a:true,\u2028b:null}', "{nested:{z:1,'10':2,'2':3},list:[1,{a:2},],}",
    "{'4294967294':1,'4294967295':2,'00':3,'0':4,z:5}",
    "{null:null,true:true,false:false}", "{x:'\"quoted\"',y:\"'quoted'\"}",
  ]
  for (let index = 0; index < 40; index++) {
    const value = {
      name: `sample-${index}`,
      number: index % 2 ? -index / 8 : index * 1e3,
      enabled: index % 3 === 0,
      text: index % 2 ? 'line\nquoted\'\u4e2d' : '\\path\tvalue',
      nested: { first: [index, null, false], second: { keep: true } },
    }
    parseSources.push(`/* generated ${index} */\n${JSON5.stringify(value, null, 2)}\n// end`)
  }
  const valid = parseSources.map((source, index) => {
    const value = JSON5.parse(source)
    return { id: `json5-${index}`, source, expected: value, orders: ownOrders(value) }
  })
  const rejected = [
    ['{a:Infinity}', true], ['{a:NaN}', true], ['{a:1e999}', true],
    ['{a:1,a:2}', true], ['{\u4e2d:1}', true], ["{x:'\\uD800'}", true],
    ['{constructor:1}', true], ['{__proto__:{x:1}}', true],
    ['{a:undefined}', false], ['{a:function(){}}', false], ['{a:(1+1)}', false],
    ['{a:01}', false], ['{a:0x}', false], ['{a:[,1]}', false],
    ["{a:'\\09'}", false], ['{a:0b10}', false], ['{a:1} garbage', false],
  ].map(([source, upstreamAccepts], index) => {
    let accepted = false
    try { JSON5.parse(source); accepted = true } catch {}
    assert.equal(accepted, upstreamAccepts)
    return { id: `reject-${index}`, source, upstreamAccepts }
  })
  const layerInputs = [
    { defaults: '{server:{port:1}}', user: '{}' },
    { defaults: '{server:{port:1}}' },
    { defaults: "{server:{intercepts:{d:{'.*':{sni:'d.test'}},both:{'.*':{sni:'d.test'}}}}}",
      user: "{server:{intercepts:{u:{'.*':{sni:'u.test'}},both:{'.*':{sni:'u.test'}}}}}",
      personal: "{server:{intercepts:{p:{'.*':{sni:'p.test'}}}}}",
      shared: "{server:{intercepts:{s:{'.*':{sni:'s.test'}}}}}" },
    { defaults: "{server:{gone:{a:1},array:[1,2]}}",
      user: "{server:{gone:null,array:[null,'[delete]',{x:null,y:1}]}}" },
    { defaults: "{'10':1,'2':2,z:3,server:{}}", user: "{'1':1,a:1}", personal: "{p:1}", shared: "{s:1}" },
    { defaults: "{proxy:{enabled:true},plugin:{custom:{enabled:true}},server:{}}", user: '{}' },
    { defaults: "{app:{remoteConfig:{enabled:false,url:' \\tHTTP://rules.example.test:80/a%2Fb?q=%26 '}}}", user: '{}' },
    { defaults: "{app:{remoteConfig:{enabled:true,url:'https://rules.example.test/a'}}}", user: '{}' },
    { defaults: "{app:{remoteConfig:{enabled:true,url:'http://rules.example.test/a',fetchRule:{sni:'edge.example.test'}}},server:{dns:{providers:{existing:{server:'https://resolver.example.test/custom'}},mapping:{'rules.example.test':'old'}},intercepts:{'rules.example.test':{'.*':{sni:'keep.example.test'}}},preSetIpList:{'rules.example.test':{'192.0.2.99':true}}}}", user: '{}' },
    { defaults: "{app:{remoteConfig:{enabled:true,url:'https://rules.example.test',personalUrl:'https://personal.example.test',fetchRule:{dns:'custom',sni:'edge.example.test',preSetIpList:null}}}}", user: '{}' },
  ]
  for (let index = 0; index < 30; index++) {
    layerInputs.push({
      defaults: JSON.stringify({ server: { order: { d: index, both: 0 }, list: [0, 1] } }),
      shared: JSON.stringify({ server: { order: { s: index + 1, both: 1 } } }),
      personal: JSON.stringify({ server: { order: { p: index + 2, both: 2 } } }),
      user: JSON.stringify({ server: { order: { u: index + 3, both: index % 2 ? null : 3 }, list: [index] } }),
    })
  }
  const layers = layerInputs.map((documents, index) => {
    const expected = layered(documents)
    return { id: `layer-${index}`, documents, expected, orders: ownOrders(expected) }
  })
  const sources = [
    'node_modules/@blue-frontier/dev-sidecar/src/config/local-config-loader.js',
    'node_modules/@blue-frontier/dev-sidecar/src/config/remote-config-rule.js',
    'node_modules/@blue-frontier/dev-sidecar/src/config/remote-config-url.js',
    'node_modules/@blue-frontier/dev-sidecar/src/merge.js',
    'node_modules/@blue-frontier/dev-sidecar/src/modules/server/index.js',
    'node_modules/@blue-frontier/mitmproxy/src/json.js',
    'node_modules/json5/lib/parse.js',
    'node_modules/json5/lib/index.js',
    'node_modules/json5/lib/stringify.js',
    'node_modules/json5/lib/util.js',
    'node_modules/json5/lib/unicode.js',
    'node_modules/json5/package.json',
    'node_modules/lodash/lodash.js',
    'node_modules/lodash/package.json',
  ]
  fs.mkdirSync(output, { recursive: true })
  const data = { bootstrap: defaults, parse: valid, reject: rejected, layers }
  fs.writeFileSync(path.join(output, 'oracle-cases.json'), JSON.stringify(data, null, 2) + '\n')
  fs.writeFileSync(path.join(output, 'upstream-provenance.json'), JSON.stringify({
    json5Version: '2.2.3',
    packageVersion: require(path.join(core, 'node_modules/@blue-frontier/dev-sidecar/package.json')).version,
    sourceSha256: Object.fromEntries(sources.map(name => [name, sha(fs.readFileSync(path.join(core, name)))])),
    generatedCases: { parse: valid.length, rejectedSubsetOrInvalid: rejected.length, layers: layers.length },
    personalConfigurationRead: false, productionNodeRequired: false,
    oracleSyntheticSubstitutions: [
      'CLOUDFLARE_DOH_SERVER literal -> synthetic .test HTTPS endpoint',
      'provider server.includes hostname literal -> synthetic .test hostname',
      'preset policy -> caller-supplied 192.0.2.0/24 fixture',
    ],
  }, null, 2) + '\n')
  console.log(`Generated ${valid.length} JSON5 + ${rejected.length} rejection + ${layers.length} real layer oracle cases.`)
} else {
  const provenance = JSON.parse(fs.readFileSync(path.join(output, 'upstream-provenance.json')))
  for (const [name, expectedHash] of Object.entries(provenance.sourceSha256)) {
    assert.equal(sha(fs.readFileSync(path.join(core, name))), expectedHash, `Upstream source drift: ${name}`)
  }
  const expected = JSON.parse(fs.readFileSync(path.join(output, 'oracle-cases.json')))
  const actual = JSON.parse(fs.readFileSync(path.join(output, 'kotlin-oracle-results.json')))
  for (const kind of ['parse', 'layers']) {
    assert.equal(actual[kind].length, expected[kind].length)
    actual[kind].forEach((item, index) => {
      assert.equal(item.id, expected[kind][index].id)
      assert.deepEqual(item.value, expected[kind][index].expected, `${kind} value ${item.id}`)
      assert.deepEqual(item.orders, expected[kind][index].orders, `${kind} key order ${item.id}`)
    })
  }
  assert.deepEqual(actual.reject.map(item => item.id), expected.reject.map(item => item.id))
  actual.reject.forEach(item => assert.equal(item.rejected, true, item.id))
  console.log(`Independent upstream JSON5/layer/key-order oracle passed: ${actual.parse.length + actual.layers.length + actual.reject.length} cases.`)
}
