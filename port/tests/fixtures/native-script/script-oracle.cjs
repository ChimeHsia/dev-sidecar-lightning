// Synthetic scripts/configuration only. Original tag, route, userscript-wrapper and byte pipeline code.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const zlib = require('node:zlib')
const { Readable, Writable } = require('node:stream')
const { createRequire } = require('node:module')
const [mode, flag, source, ...extra] = process.argv.slice(2)
if (!['--generate', '--verify'].includes(mode) || flag !== '--upstream' || !source || extra.length) {
  throw Error('Usage: node script-oracle.cjs (--generate | --verify) --upstream <mitmproxy/src>')
}
const upstream = path.resolve(source)
const root = path.resolve(__dirname, '../../../..')
const oraclePath = path.join(__dirname, 'oracle.json')
const pinsPath = path.join(__dirname, 'source-hashes.json')
const kotlinPath = path.join(root, 'netcore/src/test/kotlin/com/dsha/sidecar/net/http/native/script/UpstreamScriptOracle.kt')
const saved = mode === '--verify' ? JSON.parse(fs.readFileSync(oraclePath)) : null
const pins = mode === '--verify' ? JSON.parse(fs.readFileSync(pinsPath)) : null
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
if (mode === '--verify') {
  assert(pins && typeof pins === 'object' && Object.keys(pins).length, 'Missing source pins')
  for (const [name, value] of Object.entries(pins)) assert.equal(hash(path.resolve(upstream, name)), value, `Source changed: ${name}`)
}
const hashes = {}
const cache = new Map()
const logger = new Proxy({}, { get: () => () => {} })
const epoch = 1793577600000
let assets = {}
let failCatalog = false
class FixedDate extends Date {
  constructor(...args) { super(...(args.length ? args : [epoch])) }
  static now() { return epoch }
}
function read(file) {
  const name = path.relative(upstream, file).replaceAll('\\', '/')
  hashes[name] = hash(file)
  if (mode === '--verify') {
    assert(Object.hasOwn(pins, name), `Missing source pin: ${name}`)
    assert.equal(hashes[name], pins[name], `Source changed: ${name}`)
  }
  return fs.readFileSync(file, 'utf8')
}
function load(file) {
  file = require.resolve(file)
  if (cache.has(file)) return cache.get(file).exports
  const text = read(file)
  const module = { exports: {} }; cache.set(file, module)
  const local = spec => {
    if (spec.includes('util.log.server')) return logger
    if (spec.endsWith('/monkey')) return { get: () => { if (failCatalog) throw Error('synthetic catalog failure'); return assets } }
    if (spec === 'node:fs') return new Proxy({}, { get: () => () => { throw Error('Filesystem script loading is forbidden in oracle') } })
    if (spec.startsWith('.')) return load(createRequire(file).resolve(spec))
    return createRequire(file)(spec)
  }
  vm.runInNewContext('(function(require,module,exports){' + text + '\n})',
    { Date: FixedDate, Buffer, console }, { filename: file })(local, module, module.exports)
  return module.exports
}
const script = load(path.join(upstream, 'lib/interceptor/impl/res/script.js'))
const middleware = load(path.join(upstream, 'lib/proxy/middleware/InsertScriptMiddleware.js'))
const monkey = load(path.join(upstream, 'lib/monkey/index.js'))
read(path.join(upstream, 'lib/proxy/mitmproxy/createRequestHandler.js'))
read(path.join(upstream, 'options.js'))
const catalog = { alpha: '/* synthetic alpha */', beta: '/* synthetic beta */', tampermonkey: '/* synthetic bootstrap */' }
const html = '<html><head><title>caf\u00e9 \u4e2d\ud83d\ude80</title></head><body>hello</body></html>'
const userSource = '// ==UserScript==\n// @name Synthetic\n// @version 1\n// @grant GM_getValue\n// @grant GM_setValue\n// ==/UserScript==\nwindow.synthetic = true;'
const cases = [
  { id: 'builtin-order', type: 'inject', script: ['alpha', 'beta'] },
  { id: 'builtin-string', type: 'inject', script: 'alpha' },
  { id: 'missing-optional', type: 'inject', script: ['missing', 'alpha'] },
  { id: 'all-missing', type: 'inject', script: ['missing'] },
  { id: 'reserved-only', type: 'inject', script: ['global', 'tampermonkey'] },
  { id: 'relative-script-custom-bootstrap', type: 'inject', script: ['/scripts/alpha.js'], tampermonkeyScript: '/bootstrap.js' },
  { id: 'external-script', type: 'inject', script: ['https://scripts.test/alpha.js'] },
  { id: 'csp-nonce', type: 'inject', script: ['alpha'], csp: "script-src 'nonce-AbCd0123=' 'strict-dynamic'" },
  { id: 'report-only-nonce', type: 'inject', script: ['alpha'], reportCsp: "script-src 'nonce-AbCd0123='" },
  { id: 'csp-self-repair', type: 'inject', script: ['alpha'], csp: "default-src 'none'; script-src https:" },
  { id: 'github-tags', type: 'inject', host: 'github.com', script: ['alpha'] },
  { id: 'github-turbo-skip', type: 'inject', host: 'github.com', turbo: 'repo-content-turbo-frame', script: ['alpha'] },
  { id: 'non-html-skip', type: 'inject', contentType: 'application/json', script: ['alpha'] },
  { id: 'non200-still-injects', type: 'inject', status: 404, script: ['alpha'] },
  { id: 'uppercase-head', type: 'inject', text: '<HTML><HEAD></HEAD><BODY>text</BODY></HTML>', script: ['alpha'] },
  { id: 'gzip', type: 'inject', encoding: 'gzip', script: ['alpha'] },
  { id: 'deflate', type: 'inject', encoding: 'deflate', script: ['alpha'] },
  { id: 'brotli-evidence', type: 'inject', encoding: 'br', script: ['alpha'] },
  { id: 'catalog-error', type: 'inject', failCatalog: true, script: ['alpha'] },
  { id: 'missing-bootstrap', type: 'inject', noBootstrap: true, script: ['alpha'] },
  { id: 'route-asset', type: 'route', path: '/____ds_script____/alpha' },
  { id: 'route-global-alias', type: 'route', path: '/____ds_script____/global' },
  { id: 'route-missing', type: 'route', path: '/____ds_script____/missing' },
  { id: 'route-other', type: 'route', path: '/other' },
  { id: 'userscript-wrapper', type: 'compile', source: userSource, key: 'synthetic' },
  { id: 'userscript-window-load', type: 'compile', source: userSource.replace('@version 1', '@version 2\n// @run-at document-idle'), key: 'synthetic' },
]
function compress(bytes, encoding) {
  if (encoding === 'gzip') return zlib.gzipSync(bytes)
  if (encoding === 'deflate') return zlib.deflateSync(bytes)
  if (encoding === 'br') return zlib.brotliCompressSync(bytes)
  return bytes
}
function decode(bytes, encoding) {
  if (encoding === 'gzip') return zlib.gunzipSync(bytes)
  if (encoding === 'deflate') return zlib.inflateSync(bytes)
  if (encoding === 'br') return zlib.brotliDecompressSync(bytes)
  return bytes
}
async function oracle(test) {
  assets = Object.fromEntries(Object.entries(catalog).filter(([key]) => !test.noBootstrap || key !== 'tampermonkey').map(([key, text]) => [key, { script: text }]))
  failCatalog = !!test.failCatalog
  if (test.type === 'compile') return { script: monkey.loadScript(test.source, test.key).script }
  const context = { rOptions: { hostname: test.host || 'example.test', path: test.path || '/', headers: test.turbo ? { 'turbo-frame': test.turbo } : {}, protocol: 'https:', port: 443 },
    log: logger, setting: { script: { defaultDir: '/synthetic', dirAbsolutePath: '/synthetic' } } }
  const set = {}; let status = null; let routeBody = ''
  if (test.type === 'route') {
    const res = { writeHead: (code, headers) => { status = code; Object.assign(set, headers) }, write: text => { routeBody += text }, end: text => { routeBody += text || '' } }
    const handled = middleware.requestIntercept(context, {}, res)
    return { handled: !!handled, status, headers: set, body: routeBody }
  }
  const input = compress(Buffer.from(test.text || html), test.encoding)
  const headerList = [['content-type', test.contentType || 'text/html; charset=utf-8'], ['content-length', String(input.length)]]
  if (test.encoding) headerList.push(['content-encoding', test.encoding])
  if (test.csp) headerList.push(['content-security-policy', test.csp])
  if (test.reportCsp) headerList.push(['content-security-policy-report-only', test.reportCsp])
  const proxyRes = Readable.from([input])
  proxyRes.statusCode = test.status || 200
  proxyRes.headers = Object.fromEntries(headerList)
  const chunks = []
  const res = new Writable({ write(chunk, enc, next) { chunks.push(Buffer.from(chunk)); next() } })
  res.setHeader = (name, value) => { set[name.toLowerCase()] = value }
  res.writeHead = code => { status = code }
  const append = script.responseIntercept(context, { script: test.script, tampermonkeyScript: test.tampermonkeyScript }, {}, res, {}, proxyRes)
  if (append) {
    const done = new Promise((resolve, reject) => { res.on('finish', resolve); res.on('error', reject); proxyRes.on('error', reject) })
    middleware.responseInterceptor({}, res, {}, proxyRes, true, () => {}, append)
    await done
  }
  const wire = append ? Buffer.concat(chunks) : input
  return { tags: append?.head || null, diagnostics: set['ds-script-interceptor'] || null,
    inputHeaders: headerList, inputBase64: input.toString('base64'), status: proxyRes.statusCode,
    outputHeaders: set, decodedBase64: decode(wire, test.encoding).toString('base64') }
}
function kotlin(value) {
  if (value == null) return 'null'
  if (typeof value === 'string') return JSON.stringify(value).replaceAll('$', '\\$').replace(/[\u0080-\uffff]/g, char => '\\u' + char.charCodeAt(0).toString(16).padStart(4, '0'))
  if (typeof value === 'boolean' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return 'listOf<Any?>(' + value.map(kotlin).join(', ') + ')'
  return 'linkedMapOf<String, Any?>(' + Object.entries(value).map(([key, item]) => kotlin(key) + ' to ' + kotlin(item)).join(', ') + ')'
}
async function main() {
  if (mode === '--verify') assert.deepEqual(saved.map(({ expected, ...test }) => test), cases, 'Synthetic inputs changed')
  const results = []
  for (const test of cases) results.push({ ...test, expected: await oracle(test) })
  const generated = '// Generated by script-oracle.cjs from original JavaScript; no real script assets.\n' +
    'package com.dsha.sidecar.net.http.native.script\n\ninternal object UpstreamScriptOracle {\n' +
    '    val cases get() = listOf(' + results.map((_, i) => `case${i}()`).join(', ') + ')\n' +
    results.map((test, i) => `    private fun case${i}(): Map<String, Any?> = ${kotlin(test)}`).join('\n') + '\n}\n'
  if (mode === '--verify') {
    assert.deepEqual(JSON.parse(JSON.stringify(results)), saved, 'Original source output changed')
    assert.deepEqual(hashes, pins, 'Source inventory changed')
    assert.equal(fs.readFileSync(kotlinPath, 'utf8'), generated, 'Kotlin fixture changed')
    console.log(`Verified ${results.length} script cases and ${Object.keys(hashes).length} source pins; no writes.`)
  } else {
    fs.mkdirSync(path.dirname(kotlinPath), { recursive: true })
    fs.writeFileSync(oraclePath, JSON.stringify(results, null, 2) + '\n')
    fs.writeFileSync(pinsPath, JSON.stringify(hashes, null, 2) + '\n')
    fs.writeFileSync(kotlinPath, generated)
    console.log(`Generated ${results.length} original-function script fixtures.`)
  }
}
main().catch(failure => { console.error(failure); process.exitCode = 1 })
