// Synthetic inputs only. Use --generate or --verify, plus --upstream mitmproxy/src.
// Expected selections/transforms come from original JS functions, not a second selector.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { createRequire } = require('node:module')
const usage = 'Usage: node generate-oracle.cjs (--generate | --verify) --upstream <mitmproxy/src>'
let mode
let sourcePath
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i]
  if ((arg === '--generate' || arg === '--verify') && !mode) mode = arg
  else if (arg === '--upstream' && !sourcePath && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')) {
    sourcePath = process.argv[++i]
  } else throw Error(usage)
}
if (!mode || !sourcePath) throw Error(usage)
const upstream = path.resolve(sourcePath)
if (!fs.existsSync(path.join(upstream, 'options.js'))) throw Error('Missing upstream options.js')
const root = path.resolve(__dirname, '../../../..')
const oraclePath = path.join(__dirname, 'oracle.json')
const hashesPath = path.join(__dirname, 'source-hashes.json')
const kotlinPath = path.join(root, 'netcore/src/test/kotlin/com/dsha/sidecar/net/rules/native/UpstreamRuleOracle.kt')
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'))
const saved = mode === '--verify' ? readJson(oraclePath) : null
const pinned = mode === '--verify' ? readJson(hashesPath) : null
function sourceHash(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}
if (mode === '--verify') {
  assert(pinned && typeof pinned === 'object' && !Array.isArray(pinned) && Object.keys(pinned).length > 0,
    'Missing or invalid pinned source hashes')
  // Validate every pin before evaluating any original module, including context-only files.
  for (const [name, hash] of Object.entries(pinned)) {
    assert(typeof hash === 'string' && /^[a-f0-9]{64}$/.test(hash), `Invalid pinned source hash: ${name}`)
    assert.equal(sourceHash(path.resolve(upstream, name)), hash, `Upstream source changed: ${name}`)
  }
}
const cache = new Map()
const hashes = {}
function recordSource(file) {
  const name = path.relative(upstream, file).replaceAll('\\', '/')
  const hash = sourceHash(file)
  if (pinned !== null) {
    assert(Object.hasOwn(pinned, name), `Missing pinned source hash: ${name}`)
    assert.equal(hash, pinned[name], `Upstream source changed: ${name}`)
  }
  hashes[name] = hash
}
const log = new Proxy({}, { get: () => () => {} })
const epoch = 1793577600000
class FixedDate extends Date {
  constructor(...args) { super(...(args.length ? args : [epoch])) }
  static now() { return epoch }
}
function load(file) {
  file = require.resolve(file)
  if (cache.has(file)) return cache.get(file).exports
  const source = fs.readFileSync(file, 'utf8')
  recordSource(file)
  const module = { exports: {} }
  cache.set(file, module)
  const localRequire = spec => {
    if (spec.includes('util.log.server')) return log
    if (spec === './lib/dns') return { initDNS: () => ({}) }
    if (spec.includes('middleware/overwall')) return { createOverwallMiddleware: () => null }
    if (spec === 'baidu-aip-sdk') return { ocr: class {} }
    if (spec.endsWith('/monkey')) return { get: () => ({}) }
    if (spec.startsWith('.') || spec === '@blue-frontier/dev-sidecar/src/merge') {
      return load(createRequire(file).resolve(spec))
    }
    return createRequire(file)(spec)
  }
  vm.runInNewContext('(function(require,module,exports,__filename,__dirname){' + source + '\n})',
    { Date: FixedDate, console, Buffer, URL, setTimeout, clearTimeout }, { filename: file })(
    localRequire, module, module.exports, file, path.dirname(file))
  return module.exports
}
const impls = load(path.join(upstream, 'lib/interceptor/index.js'))
for (const impl of impls) {
  for (const phase of ['requestIntercept', 'responseIntercept']) {
    const original = impl[phase]
    if (!original) continue
    impl[phase] = function (context, opt, ...args) {
      if (context.recordOnly) {
        const offset = phase === 'requestIntercept' ? 4 : 6
        const matched = args[offset]
        const hostMatched = args[offset + 1]
        context.captured = {
          name: impl.name, priority: impl.priority, key: opt.key, order: opt.order || 0,
          matched: Array.from(matched || []),
          named: matched?.groups || null,
          hostMatched: hostMatched ? Array.from(hostMatched) : null,
          hostNamed: hostMatched?.groups || null,
        }
        return
      }
      return original.call(impl, context, opt, ...args)
    }
  }
}
const optionsFactory = load(path.join(upstream, 'options.js'))
for (const name of ['lib/proxy/mitmproxy/createRequestHandler.js', 'lib/proxy/common/util.js']) {
  recordSource(path.join(upstream, name))
}
const merge = load(createRequire(path.join(upstream, 'utils/util.match.js')).resolve('@blue-frontier/dev-sidecar/src/merge'))
const base = {
  intercepts: {}, whiteList: {}, setting: { rootDir: '/synthetic', script: { dirAbsolutePath: '/synthetic/scripts' } },
  dns: { providers: {}, mapping: {}, ech: { enabled: true, domains: [] } },
  plugin: { overwall: {} },
}
const request = (host = 'api.example.test', pathname = '/items/42?q=x', extra = {}) => ({
  host, path: pathname, method: 'GET', scheme: 'https', port: 443,
  headers: { host, origin: 'https://caller.test', cookie: 'secret' }, ...extra,
})
const one = rule => [{ server: { intercepts: { 'api.example.test': { '.*': rule } } } }]
const cases = [
  { id: 'plain', layers: [], request: request() },
  { id: 'captures-and-exact-layer', layers: [
    { server: { intercepts: {
      '^(?<sub>api)\\.(example)\\.test$': { '^/items/(?<id>[0-9]+)': { proxy: 'https://mirror.test/${sub}/${h[2]}/${id}/${p[1]}/${missing}', order: 1 } },
      '^api\\.(?<zone>example)\\.test$': { '.*': { requestReplace: { headers: { Cookie: '[remove]', 'X-Test': 'yes' } } } },
      'api.example.test': { '^/items/(?<id>[0-9]+)': { verifyHost: 'mirror.test' } },
    } } },
    { server: { intercepts: { 'api.example.test': { '.*': { responseReplace: { headers: { Server: '[remove]', 'X-Added': 'ok' } } } } } } },
  ], request: request() },
  { id: 'order-and-ties', layers: [{ server: { intercepts: { 'api.example.test': {
    '^/': { proxy: 'first.test', order: 2 },
    'items': { proxy: 'tie.test', order: 2, retry: 2 },
    '42': { requestReplace: { headers: { 'X-Order': 'higher' } }, order: 5 },
    '.*': { requestReplace: { headers: { 'X-Order': 'lower' } }, order: 0 },
  } } } }], request: request() },
  { id: 'higher-order-replaces', layers: [{ server: { intercepts: { 'api.example.test': {
    '^/': { proxy: 'first.test' }, 'items': { proxy: 'winner.test', order: 3 },
  } } } }], request: request() },
  { id: 'exclusion-object-values-ignored', layers: [{ server: { intercepts: { 'api.example.test': {
    '^/': { abort: true, exclusions: { items: false } }, '.*': { sni: '' },
  } } } }], request: request() },
  { id: 'exclusion-array', layers: one({ abort: true, exclusions: ['items'] }), request: request() },
  { id: 'sni-suppress-keeps-verification', layers: one({ sni: '', verifyHost: 'certificate.test' }), request: request() },
  { id: 'sni-rewrite-compat-unverified', layers: one({ sni: 'front.test', verifyHost: 'certificate.test' }), request: request() },
  { id: 'verify-host-only-compat-unverified', layers: one({ verifyHost: 'certificate.test' }), request: request() },
  { id: 'sni-empty-verify-host', layers: one({ verifyHost: '' }), request: request() },
  { id: 'proxy-stops-other-sni', layers: [{ server: { intercepts: { 'api.example.test': {
    '^/': { proxy: 'mirror.test' }, '.*': { sni: 'front.test', unVerifySsl: true },
  } } } }], request: request() },
  { id: 'proxy-inline-sni', layers: one({ proxy: 'mirror.test', sni: 'front.test', verifyHost: 'certificate.test', backup: ['backup.test'] }), request: request() },
  { id: 'proxy-inline-empty-sni-ignored', layers: one({ proxy: 'mirror.test', sni: '' }), request: request() },
  { id: 'ech-ignores-sni-and-verify-host', layers: [{ server: { dns: { ech: { domains: ['api.example.test'] } }, intercepts: { 'api.example.test': { '.*': { sni: 'front.test', verifyHost: 'certificate.test' } } } } }], request: request() },
  { id: 'ech-inline-proxy-source-exception', layers: [{ server: { dns: { ech: { domains: ['api.example.test'] } }, intercepts: { 'api.example.test': { '.*': { proxy: 'mirror.test', sni: 'front.test' } } } } }], request: request() },
  { id: 'ech-disabled', layers: [{ server: { dns: { ech: { enabled: false, domains: ['api.example.test'] } }, intercepts: { 'api.example.test': { '.*': { sni: '' } } } } }], request: request() },
  { id: 'nat64-forces-ech-when-ech-disabled', layers: [{ server: { dns: { ech: { enabled: false }, nat64: { enabled: true, prefix: '64:ff9b::/96', domains: ['api.example.test'] } }, intercepts: { 'api.example.test': { '.*': { sni: '' } } } } }], request: request() },
  { id: 'unverify-explicit', layers: one({ ssl: false }), request: request() },
  { id: 'setting-verification-disabled', layers: [{ server: { setting: { verifySsl: false } } }], request: request() },
  { id: 'success-wins-abort-and-proxy', layers: one({ success: { status: 201, text: 'created' }, abort: true, proxy: 'mirror.test', cacheDays: 2 }), request: request() },
  { id: 'abort-no-content', layers: one({ abort: { status: 204, text: 'discarded' } }), request: request() },
  { id: 'abort-cors-custom', layers: one({ abort: { status: 451, html: '<p>blocked</p>', headers: { 'Access-Control-Allow-Origin': '*' } } }), request: request() },
  { id: 'redirect-captures', layers: [{ server: { intercepts: { 'api.example.test': { '^/items/([0-9]+)': { redirect: 'https://new.test/${p[1]}', cacheDays: 1 } } } } }], request: request() },
  { id: 'replace-regex-target', layers: one({ proxy: 'https://new.test/$1', replace: '^/items/([0-9]+).*' }), request: request() },
  { id: 'placeholder-first-only', layers: one({ proxy: 'https://new.test/${host}/${host}/${p[0]}/${p[0]}' }), request: request() },
  { id: 'request-download', layers: one({ requestReplace: { doDownload: true, headers: { Cookie: '[remove]', 'X-Empty': '', 'X-Zero': 0 } }, responseReplace: { doDownload: true } }), request: request('api.example.test', '/asset/file.zip?DS_DOWNLOAD=1') },
  { id: 'response-headers-and-cors', layers: one({ options: true, responseReplace: { headers: { SERVER: '[remove]', 'X-Added': 'ok' } } }), request: request() },
  { id: 'preflight', layers: one({ options: true, optionsCredentials: false, optionsMaxAge: 42, success: true }), request: request('api.example.test', '/', { method: 'OPTIONS', headers: { host: 'api.example.test', origin: 'https://caller.test', 'access-control-request-headers': 'x-test' } }) },
  { id: 'options-without-origin-falls-through', layers: one({ options: true, proxy: 'mirror.test' }), request: request('api.example.test', '/', { method: 'OPTIONS', headers: { host: 'api.example.test' } }) },
  { id: 'retry-object-and-cache-descriptors', layers: one({ retry: { times: 3.9, statuses: ['502bad', 503], methods: ['get', 'post'] }, cacheDays: 3, proxy: 'mirror.test' }), request: request() },
  { id: 'cache-unit-precedence', layers: one({ cacheSeconds: -1, cacheMaxAge: 20, cacheDays: 3 }), request: request() },
  { id: 'script-generated-route', layers: one({ script: ['https://scripts.test/tool.js'] }), request: request('api.example.test', '/____ds_script_proxy____/https_scripts_test_tool.js') },
  { id: 'script-selection-only', layers: one({ script: ['custom'], tampermonkeyScript: '/monkey.js' }), request: request() },
  { id: 'unknown-enabled-action', layers: one({ mysteryAction: true }), request: request() },
  { id: 'layer-deletions-and-array-replacement', layers: [ ...one({ proxy: 'old.test', retry: { times: 2, methods: ['GET', 'POST'] } }), { server: { intercepts: { 'api.example.test': { '.*': { proxy: null, retry: { methods: ['HEAD'] }, sni: '' } } } } } ], request: request() },
]
function oracle(test) {
  let merged = {}
  for (const layer of test.layers) merged = merge.doMerge(merged, structuredClone(layer))
  merge.deleteNullItems(merged)
  const server = merge.doMerge(structuredClone(base), merged.server || merged)
  const r = test.request
  const verify = server.setting.verifySsl !== false
  const insecure = { options: { rejectUnauthorized: false } }
  const context = { rOptions: { protocol: r.scheme + ':', hostname: r.host, host: r.host, port: r.port,
    method: r.method, path: r.path, url: r.path, headers: structuredClone(r.headers),
    agent: { options: { rejectUnauthorized: verify }, unVerifySslAgent: insecure } }, log, setting: server.setting }
  const options = optionsFactory(server)
  const selected = options.createIntercepts(context) || []
  const selections = selected.map(item => {
    context.recordOnly = true
    if (item.requestIntercept) item.requestIntercept(context, {}, {}, true, () => {})
    else item.responseIntercept(context, {}, {}, {}, {}, true, () => {})
    return context.captured
  })
  delete context.recordOnly
  const responseHeaders = {}
  let terminal = null
  const res = { setHeader: (name, value) => { responseHeaders[name] = value },
    writeHead: (status, headers) => { terminal = { status, headers, body: '' } },
    write: value => { terminal.body += value }, end: () => {} }
  const executed = []
  for (const item of selected) {
    if (!item.requestIntercept) continue
    executed.push(item.name)
    // A cache lookup is represented but never evaluated against a clock in this planner oracle.
    if (item.name === 'cacheRequest' || item.name === 'baiduOcr') continue
    if (item.requestIntercept(context, { url: r.path }, res, true, () => {})) break
  }
  const proxyRes = { statusCode: 200, rawHeaders: ['Server', 'origin-server', 'Vary', 'Accept-Encoding'], headers: { 'content-type': 'text/plain' } }
  if (!terminal) for (const item of selected) {
    if (!item.responseIntercept || ['script', 'cacheResponse'].includes(item.name)) continue
    item.responseIntercept(context, {}, res, {}, proxyRes, true, () => {})
  }
  // Omit diagnostics and dynamic Date; preserve actual header transformation outputs.
  for (const key of Object.keys(responseHeaders)) if (key.startsWith('DS-')) delete responseHeaders[key]
  if (terminal) delete terminal.headers.Date
  const ro = context.rOptions
  return { selected: selections, executed, route: { scheme: ro.protocol.slice(0, -1), host: ro.hostname, port: ro.port, path: ro.path },
    headers: ro.headers, serverName: ro.servername ?? ro.hostname, verifyHost: ro.verifyHost || ro.hostname,
    verify: ro.agent.options.rejectUnauthorized !== false, terminal,
    retry: context.retryConfig || null, doDownload: ro.doDownload || false,
    responseRaw: proxyRes.rawHeaders, responseSet: responseHeaders }
}
let inputs = cases
if (mode === '--verify') {
  assert(Array.isArray(saved), 'Saved oracle must be an array')
  inputs = saved.map(({ expected, ...input }) => input)
  // Do not permit saved inputs to drift into real/full configurations.
  assert.deepEqual(inputs, cases, 'Saved inputs differ from the synthetic case definitions')
}
const results = inputs.map(test => ({ ...test, expected: oracle(test) }))
function kotlin(value) {
  if (value == null) return 'null'
  if (typeof value === 'string') return JSON.stringify(value).replaceAll('$', '\\$').replace(/[\u0080-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
  if (typeof value === 'boolean' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return 'listOf<Any?>(' + value.map(kotlin).join(', ') + ')'
  return 'linkedMapOf<String, Any?>(' + Object.entries(value).map(([k, v]) => kotlin(k) + ' to ' + kotlin(v)).join(', ') + ')'
}
const generated = '// Generated by generate-oracle.cjs from original JS. Do not hand-edit expected values.\n' +
  'package com.dsha.sidecar.net.rules.native\n\ninternal object UpstreamRuleOracle {\n' +
  '    val cases: List<Map<String, Any?>> get() = listOf(' +
  results.map((_, index) => `case${index}()`).join(', ') + ')\n' +
  results.map((test, index) => `    private fun case${index}(): Map<String, Any?> = ` + kotlin(test)).join('\n') + '\n}\n'
const oracleText = JSON.stringify(results, null, 2) + '\n'
const hashesText = JSON.stringify(hashes, null, 2) + '\n'
if (mode === '--verify') {
  // JSON round-trip removes VM realm/prototype differences without altering JSON values.
  assert.deepEqual(JSON.parse(oracleText), saved, 'Original upstream output differs from saved oracle')
  assert.deepEqual(hashes, pinned, 'Pinned source inventory differs from consulted sources')
  assert.equal(fs.readFileSync(kotlinPath, 'utf8'), generated, 'Generated Kotlin fixture differs from saved oracle')
  console.log(`Verified ${results.length} synthetic fixtures and ${Object.keys(hashes).length} pinned sources; no artifacts written.`)
} else {
  fs.mkdirSync(path.dirname(kotlinPath), { recursive: true })
  fs.writeFileSync(oraclePath, oracleText)
  fs.writeFileSync(hashesPath, hashesText)
  fs.writeFileSync(kotlinPath, generated)
  console.log(`Generated ${results.length} upstream cases; hashed ${Object.keys(hashes).length} original sources.`)
}
