// Synthetic inputs. Real original cache functions, DynamicChoice, and extracted original retry loop.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { createRequire } = require('node:module')
const [mode, flag, source, ...extra] = process.argv.slice(2)
if (!['--generate', '--verify'].includes(mode) || flag !== '--upstream' || !source || extra.length) {
  throw Error('Usage: node schedule-oracle.cjs (--generate | --verify) --upstream <mitmproxy/src>')
}
const upstream = path.resolve(source)
const root = path.resolve(__dirname, '../../../..')
const oraclePath = path.join(__dirname, 'oracle.json')
const pinsPath = path.join(__dirname, 'source-hashes.json')
const kotlinPath = path.join(root, 'netcore/src/test/kotlin/com/dsha/sidecar/net/http/native/schedule/UpstreamScheduleOracle.kt')
const saved = mode === '--verify' ? JSON.parse(fs.readFileSync(oraclePath)) : null
const pins = mode === '--verify' ? JSON.parse(fs.readFileSync(pinsPath)) : null
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
if (mode === '--verify') {
  assert(pins && typeof pins === 'object' && Object.keys(pins).length, 'Missing source pins')
  for (const [name, value] of Object.entries(pins)) assert.equal(hash(path.resolve(upstream, name)), value, `Source changed: ${name}`)
}
const hashes = {}
const epoch = 1793577600000
let now = epoch
class TestDate extends Date {
  constructor(...args) { super(...(args.length ? args : [now])) }
  static now() { return now }
}
const logger = new Proxy({}, { get: () => () => {} })
const modules = new Map()
function read(name) {
  const file = path.resolve(upstream, name)
  const key = path.relative(upstream, file).replaceAll('\\', '/')
  hashes[key] = hash(file)
  if (mode === '--verify') {
    assert(Object.hasOwn(pins, key), `Missing source pin: ${key}`)
    assert.equal(hashes[key], pins[key], `Source changed: ${key}`)
  }
  return fs.readFileSync(file, 'utf8')
}
function load(file) {
  file = require.resolve(file)
  if (modules.has(file)) return modules.get(file).exports
  const text = read(path.relative(upstream, file))
  const module = { exports: {} }; modules.set(file, module)
  const local = spec => spec.includes('util.log.server') ? logger
    : spec.startsWith('.') ? load(createRequire(file).resolve(spec)) : createRequire(file)(spec)
  vm.runInNewContext('(function(require,module,exports){' + text + '\n})',
    { Date: TestDate, Buffer, console }, { filename: file })(local, module, module.exports)
  return module.exports
}
const cacheReq = load(path.join(upstream, 'lib/interceptor/impl/req/cacheRequest.js'))
const cacheRes = load(path.join(upstream, 'lib/interceptor/impl/res/cacheResponse.js'))
const { DynamicChoice } = load(path.join(upstream, 'lib/choice/index.js'))
const retryImpl = load(path.join(upstream, 'lib/interceptor/impl/req/retry.js'))
const handler = read('lib/proxy/mitmproxy/createRequestHandler.js')
read('lib/interceptor/impl/req/proxy.js')
const eligibilityStart = handler.indexOf('function canRetryWithBody (')
const eligibilityEnd = handler.indexOf('// \u7f13\u5b58\u8bf7\u6c42\u4f53', eligibilityStart)
assert(eligibilityStart >= 0 && eligibilityEnd > eligibilityStart, 'Eligibility source boundary missing')
const canRetry = vm.runInNewContext(
  `const BODY_METHODS = new Set(['POST','PUT','PATCH','DELETE']); const MAX_RETRY_BODY_SIZE=1048576;\n` +
  handler.slice(eligibilityStart, eligibilityEnd) + '\ncanRetryWithBody', {})
const loopStart = handler.indexOf('      let retryCount = 0')
const loopEnd = handler.indexOf("      // proxyRes.on('data'", loopStart)
assert(loopStart >= 0 && loopEnd > loopStart, 'Retry loop source boundary missing')
const originalLoop = vm.runInNewContext(
  '(async function(context,rOptions,res,proxyRequestPromise,log,url){\n' +
  handler.slice(loopStart, loopEnd) + '\nreturn proxyRes;\n})', {})
const cases = [
  { id: 'conditional-fresh', type: 'cache', config: { cacheDays: 1 }, headers: { 'if-modified-since': String(epoch - 1000) } },
  { id: 'conditional-exact-boundary', type: 'cache', config: { cacheSeconds: 2 }, headers: { 'if-modified-since': String(epoch - 2000) } },
  { id: 'conditional-expired', type: 'cache', config: { cacheSeconds: 1 }, headers: { 'if-modified-since': String(epoch - 2000) } },
  { id: 'conditional-no-cache', type: 'cache', config: { cacheDays: 1 }, headers: { 'if-modified-since': String(epoch), 'cache-control': 'no-cache' } },
  { id: 'conditional-invalid', type: 'cache', config: { cacheDays: 1 }, headers: { 'if-modified-since': 'not a date' } },
  { id: 'cache-response-default', type: 'cache', config: { cacheDays: 1 } },
  { id: 'cache-response-longer-upstream', type: 'cache', config: { cacheSeconds: 5 }, responseHeaders: { 'Cache-Control': 'public, max-age=60' } },
  { id: 'cache-response-immutable-skip', type: 'cache', config: { cacheSeconds: 5 }, responseHeaders: { 'Cache-Control': 'public, max-age=60, immutable' } },
  { id: 'cache-response-private', type: 'cache', config: { cacheSeconds: 10, cacheControlType: 'private', cacheImmutable: false } },
  { id: 'cache-response-status-exclude', type: 'cache', config: { cacheDays: 1, cacheExcludeStatusCodeList: { '200': true } } },
  { id: 'cache-response-non-get', type: 'cache', config: { cacheDays: 1 }, method: 'POST' },
  { id: 'choice-first-failure', type: 'choice', candidates: ['a.test', 'b.test', 'c.test'], failures: [true] },
  { id: 'choice-exhaust-rank', type: 'choice', candidates: ['a.test', 'b.test'], failures: [true, true, false] },
  { id: 'choice-duplicates', type: 'choice', candidates: ['a.test', 'a.test', 'b.test'], failures: [true, true] },
  { id: 'retry-statuses', type: 'retry', retry: { times: 2, statuses: [500, 503] }, attempts: [500, 503, 200] },
  { id: 'retry-budget', type: 'retry', retry: 2, attempts: [500, 500, 500, 200] },
  { id: 'retry-error-status-shared', type: 'retry', retry: 2, attempts: ['error', 500, 200] },
  { id: 'retry-implicit-error', type: 'retry', attempts: ['error', 200] },
  { id: 'retry-no-status-default', type: 'retry', attempts: [500, 200] },
  { id: 'retry-method-filter', type: 'retry', method: 'POST', headers: { 'content-length': '0' }, retry: 2, attempts: [500, 200] },
]
async function oracle(test) {
  now = epoch
  if (test.type === 'cache') {
    const rOptions = { method: test.method || 'GET', headers: test.headers || {}, protocol: 'https:', hostname: 'synthetic.test', path: '/', port: 443 }
    const context = { rOptions, log: logger }
    let conditionalStatus = null
    const res = { writeHead: status => { conditionalStatus = status }, end: () => {}, setHeader: () => {} }
    cacheReq.requestIntercept(context, test.config, { url: '/' }, res)
    const raw = Object.entries(test.responseHeaders || {}).flat()
    const additions = {}
    cacheRes.responseIntercept(context, test.config, { url: '/' }, { setHeader: (key, value) => { additions[key.toLowerCase()] = String(value) } }, {}, { statusCode: 200, rawHeaders: raw })
    const final = { ...test.responseHeaders }
    const headers = {}
    for (let i = 0; i < raw.length; i += 2) headers[raw[i].toLowerCase()] = raw[i + 1]
    return { maxAge: cacheReq.getMaxAge(test.config), conditionalStatus, headers: { ...headers, ...additions } }
  }
  if (test.type === 'choice') {
    const choice = new DynamicChoice('synthetic')
    choice.setBackupList(test.candidates)
    const trace = []
    for (const failed of test.failures) {
      if (choice.value == null) choice.doRank()
      const candidate = choice.value
      choice.doCount(candidate, false) // original proxy request start
      if (failed) choice.doCount(candidate, true)
      trace.push({ candidate, value: choice.value, remaining: [...choice.backupList], counts: JSON.parse(JSON.stringify(choice.countMap)) })
    }
    return trace
  }
  const context = { rOptions: { hostname: 'synthetic.test' }, log: logger, retryBody: Buffer.alloc(0) }
  retryImpl.requestIntercept(context, { retry: test.retry })
  const eligible = canRetry(context.retryConfig || { methods: [test.method || 'GET'] }, { method: test.method || 'GET', headers: test.headers || {} })
  if (!eligible) { context.retryConfig = null; context.retryBody = null }
  let calls = 0; let released = 0
  const outcome = await originalLoop(context, { method: test.method || 'GET' }, { setHeader: () => {} }, async () => {
    const value = test.attempts[Math.min(calls++, test.attempts.length - 1)]
    if (value === 'error') { const failure = Error('synthetic'); failure.retryable = true; throw failure }
    return { statusCode: value, on: () => {}, resume: () => { released++ } }
  }, logger, '/')
  return { calls, released, status: outcome.statusCode, eligible }
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
  const generated = '// Generated by original source schedule-oracle.cjs; do not hand-edit expectations.\n' +
    'package com.dsha.sidecar.net.http.native.schedule\n\ninternal object UpstreamScheduleOracle {\n' +
    '    val cases get() = listOf(' + results.map((_, i) => `case${i}()`).join(', ') + ')\n' +
    results.map((test, i) => `    private fun case${i}(): Map<String, Any?> = ${kotlin(test)}`).join('\n') + '\n}\n'
  if (mode === '--verify') {
    assert.deepEqual(JSON.parse(JSON.stringify(results)), saved, 'Original source output changed')
    assert.deepEqual(hashes, pins, 'Source inventory changed')
    assert.equal(fs.readFileSync(kotlinPath, 'utf8'), generated, 'Kotlin fixture changed')
    console.log(`Verified ${results.length} schedule cases and ${Object.keys(hashes).length} source pins; no writes.`)
  } else {
    fs.mkdirSync(path.dirname(kotlinPath), { recursive: true })
    fs.writeFileSync(oraclePath, JSON.stringify(results, null, 2) + '\n')
    fs.writeFileSync(pinsPath, JSON.stringify(hashes, null, 2) + '\n')
    fs.writeFileSync(kotlinPath, generated)
    console.log(`Generated ${results.length} original-function schedule cases.`)
  }
}
main().catch(failure => { console.error(failure); process.exitCode = 1 })
