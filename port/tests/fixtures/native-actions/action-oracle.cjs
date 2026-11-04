// Synthetic action inputs only. Executes original response functions and byte/compression middleware.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const zlib = require('node:zlib')
const { Readable, Writable } = require('node:stream')
const { createRequire } = require('node:module')
const args = process.argv.slice(2)
const mode = args[0]
if (!['--generate', '--verify'].includes(mode) || args[1] !== '--upstream' || args.length !== 3) {
  throw Error('Usage: node action-oracle.cjs (--generate | --verify) --upstream <mitmproxy/src>')
}
const upstream = path.resolve(args[2])
const root = path.resolve(__dirname, '../../../..')
const oraclePath = path.join(__dirname, 'oracle.json')
const pinsPath = path.join(__dirname, 'source-hashes.json')
const kotlinPath = path.join(root, 'netcore/src/test/kotlin/com/dsha/sidecar/net/http/native/UpstreamActionOracle.kt')
const saved = mode === '--verify' ? JSON.parse(fs.readFileSync(oraclePath)) : null
const pins = mode === '--verify' ? JSON.parse(fs.readFileSync(pinsPath)) : null
const hashes = {}
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
if (mode === '--verify') {
  assert(pins && typeof pins === 'object' && Object.keys(pins).length > 0, 'Missing source pins')
  for (const [name, value] of Object.entries(pins)) assert.equal(hash(path.resolve(upstream, name)), value, `Source changed: ${name}`)
}
const cache = new Map()
const logger = new Proxy({}, { get: () => () => {} })
function load(file) {
  file = require.resolve(file)
  if (cache.has(file)) return cache.get(file).exports
  const name = path.relative(upstream, file).replaceAll('\\', '/')
  const source = fs.readFileSync(file, 'utf8')
  hashes[name] = hash(file)
  if (mode === '--verify') {
    assert(Object.hasOwn(pins, name), `Missing source pin: ${name}`)
    assert.equal(hashes[name], pins[name], `Source changed: ${name}`)
  }
  const module = { exports: {} }
  cache.set(file, module)
  const req = spec => {
    if (spec.includes('util.log.server')) return logger
    if (spec.endsWith('/monkey')) return { get: () => { throw Error('No script asset loading in action oracle') } }
    if (spec.startsWith('.')) return load(createRequire(file).resolve(spec))
    return createRequire(file)(spec)
  }
  vm.runInNewContext('(function(require,module,exports){' + source + '\n})', { Buffer, console }, { filename: file })(req, module, module.exports)
  return module.exports
}
const replace = load(path.join(upstream, 'lib/interceptor/impl/res/responseReplace.js'))
const cors = load(path.join(upstream, 'lib/interceptor/impl/res/AfterOPTIONSHeaders.js'))
const middleware = load(path.join(upstream, 'lib/proxy/middleware/InsertScriptMiddleware.js'))
for (const name of ['lib/interceptor/impl/res/script.js', 'lib/proxy/mitmproxy/createRequestHandler.js']) {
  hashes[name] = hash(path.join(upstream, name))
  if (mode === '--verify') {
    assert(Object.hasOwn(pins, name), `Missing source pin: ${name}`)
    assert.equal(hashes[name], pins[name], `Source changed: ${name}`)
  }
}
const html = '<html><head><title>caf\u00e9 \u4e2d\ud83d\ude80</title></head><body>hello</body></html>'
const request = { host: 'example.test', path: '/asset/a%20b.zip?token=x', method: 'GET', headers: { origin: 'https://caller.test' } }
const append = { head: '<!-- h \u4e2d -->', body: '<!-- b -->' }
const baseHeaders = [['Content-Type', 'text/html; charset=utf-8'], ['Vary', 'Accept-Encoding'], ['Server', 'origin']]
const cases = [
  { id: 'headers-cors', config: { options: true, responseReplace: { headers: { Server: '[remove]', 'X-Added': 'yes' } } } },
  { id: 'download-naming', config: { responseReplace: { doDownload: true } }, headers: [['Cache-Control', 'old'], ['Last-Modified', 'old'], ['Expires', 'old']] },
  { id: 'non200-skips-replacement', status: 404, config: { responseReplace: { doDownload: true, headers: { Server: 'new' } } } },
  { id: 'gate-disabled', gate: false, config: { options: true, responseReplace: { headers: { Server: 'new' } } } },
  { id: 'identity-head-body-injection', append },
  { id: 'gzip-head-body-injection', append, encoding: 'gzip' },
  { id: 'deflate-head-body-injection', append, encoding: 'deflate' },
  { id: 'gzip-untouched-header-action', encoding: 'gzip', config: { options: true } },
  { id: 'brotli-untouched-header-action', encoding: 'br', config: { responseReplace: { headers: { 'X-Test': 'br' } } } },
  { id: 'non-html-append-skips', append, headers: [['Content-Type', 'application/octet-stream']], text: 'binary \u00e9' },
  { id: 'uppercase-tags', append, text: '<HTML><HEAD></HEAD><BODY>value</BODY></HTML>' },
  { id: 'missing-tags', append, text: '<html>plain</html>' },
  { id: 'csp-self', append, headers: [['Content-Security-Policy', "default-src 'none'; script-src 'nonce-test'; script-src-elem https:"]] },
  { id: 'csp-default-only', append, headers: [['Content-Security-Policy', "default-src 'none'"]] },
]
function compress(bytes, encoding) {
  if (encoding === 'gzip') return zlib.gzipSync(bytes)
  if (encoding === 'deflate') return zlib.deflateSync(bytes)
  if (encoding === 'br') return zlib.brotliCompressSync(bytes)
  return bytes
}
function decompress(bytes, encoding) {
  if (encoding === 'gzip') return zlib.gunzipSync(bytes)
  if (encoding === 'deflate') return zlib.inflateSync(bytes)
  if (encoding === 'br') return zlib.brotliDecompressSync(bytes)
  return bytes
}
async function oracle(test) {
  const input = compress(Buffer.from(test.text ?? html), test.encoding)
  const extra = test.headers || []
  const overridden = new Set(extra.map(([name]) => name.toLowerCase()))
  const headers = baseHeaders.filter(([name]) => !overridden.has(name.toLowerCase())).concat(extra)
  if (test.encoding) headers.push(['Content-Encoding', test.encoding])
  headers.push(['Content-Length', String(input.length)])
  const proxyRes = Readable.from([input])
  proxyRes.statusCode = test.status || 200
  proxyRes.rawHeaders = headers.flat()
  Object.defineProperty(proxyRes, 'headers', { get: () => Object.fromEntries(
    proxyRes.rawHeaders.reduce((pairs, value, i, raw) => i % 2 ? pairs : [...pairs, [value.toLowerCase(), raw[i + 1]]], [])) })
  const set = {}
  const chunks = []
  const output = new Writable({ write(chunk, enc, next) { chunks.push(Buffer.from(chunk)); next() } })
  output.setHeader = (name, value) => { set[name.toLowerCase()] = value }
  output.writeHead = () => {}
  const context = { rOptions: { ...request, hostname: request.host, protocol: 'https:', port: 443 }, log: logger }
  const config = test.config || {}
  if (test.gate !== false) {
    if (cors.is(config)) cors.responseIntercept(context, config, {}, output, {}, proxyRes)
    if (replace.is(config)) replace.responseIntercept(context, config, {}, output, {}, proxyRes)
  }
  let transformed = false
  if (test.append) {
    const done = new Promise((resolve, reject) => { output.on('finish', resolve); output.on('error', reject); proxyRes.on('error', reject) })
    middleware.responseInterceptor({}, output, {}, proxyRes, true, () => {}, test.append)
    transformed = Object.keys(set).includes('content-type')
    if (transformed) await done
  }
  let body
  if (transformed) body = Buffer.concat(chunks)
  else {
    for (const [name, value] of Object.entries(proxyRes.headers)) set[name] = value
    body = input
  }
  return { request, status: proxyRes.statusCode, inputHeaders: headers, inputBase64: input.toString('base64'),
    headers: set, decodedBase64: decompress(body, test.encoding).toString('base64'),
    encodedUnchanged: !transformed, bodyBase64: body.toString('base64') }
}
function kotlin(value) {
  if (value == null) return 'null'
  if (typeof value === 'string') return JSON.stringify(value).replaceAll('$', '\\$').replace(/[\u0080-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
  if (typeof value === 'boolean' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return 'listOf<Any?>(' + value.map(kotlin).join(', ') + ')'
  return 'linkedMapOf<String, Any?>(' + Object.entries(value).map(([key, item]) => kotlin(key) + ' to ' + kotlin(item)).join(', ') + ')'
}
async function main() {
  const results = []
  if (mode === '--verify') assert.deepEqual(saved.map(({ expected, ...test }) => test), cases, 'Synthetic inputs changed')
  for (const test of cases) results.push({ ...test, expected: await oracle(test) })
  const generated = '// Generated from original JS by action-oracle.cjs; do not edit expected data.\n' +
    'package com.dsha.sidecar.net.http.native\n\ninternal object UpstreamActionOracle {\n' +
    '    val cases get() = listOf(' + results.map((_, i) => `case${i}()`).join(', ') + ')\n' +
    results.map((test, i) => `    private fun case${i}(): Map<String, Any?> = ${kotlin(test)}`).join('\n') + '\n}\n'
  if (mode === '--verify') {
    assert.deepEqual(JSON.parse(JSON.stringify(results)), saved, 'Upstream action output changed')
    assert.deepEqual(hashes, pins, 'Source inventory changed')
    assert.equal(fs.readFileSync(kotlinPath, 'utf8'), generated, 'Kotlin fixture changed')
    console.log(`Verified ${results.length} action fixtures, ${Object.keys(hashes).length} pinned sources; no writes.`)
  } else {
    fs.mkdirSync(path.dirname(kotlinPath), { recursive: true })
    fs.writeFileSync(oraclePath, JSON.stringify(results, null, 2) + '\n')
    fs.writeFileSync(pinsPath, JSON.stringify(hashes, null, 2) + '\n')
    fs.writeFileSync(kotlinPath, generated)
    console.log(`Generated ${results.length} original-function action fixtures.`)
  }
}
main().catch(failure => { console.error(failure); process.exitCode = 1 })
