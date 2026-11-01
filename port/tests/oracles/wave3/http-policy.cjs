// SPDX-License-Identifier: MPL-2.0
'use strict'

// --generate writes the fixture; --verify computes it in memory and compares.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')

const args = process.argv.slice(2)
const mode = args.includes('--generate') ? '--generate' : args.includes('--verify') ? '--verify' : null
const upstreamIndex = args.indexOf('--upstream')
if (!mode || (args.includes('--generate') && args.includes('--verify')) || upstreamIndex < 0 || !args[upstreamIndex + 1]) {
  throw new Error('Usage: node http-policy.cjs (--generate|--verify) --upstream <mitmproxy/src>')
}
const root = path.resolve(args[upstreamIndex + 1])
const fixture = path.resolve(__dirname, '../../fixtures/wave3/http-policy.json')
const read = name => fs.readFileSync(path.join(root, name), 'utf8')
const clone = value => JSON.parse(JSON.stringify(value))
const hashes = {
  'lib/proxy/mitmproxy/createRequestHandler.js': '64d7c082f796311ffae7a3f84b51a7f3c902134ecc88094266bc1178e1f6b690',
  'lib/interceptor/impl/req/retry.js': '6d64453ecd15c78391629e14eefc333439645c216d0dc49ceb4e80790841372f',
  'lib/interceptor/impl/req/requestReplace.js': 'd38ab57e0d0d670a7b2aca890c8b1b5dd27bce551316209fbc98e8474cffcb9f',
  'lib/interceptor/impl/res/responseReplace.js': '3b5a7dc465049e19980a955d88c391c269ee0cd65fb85b4d910a94aa5788d10c',
  'lib/interceptor/impl/res/AfterOPTIONSHeaders.js': 'd455246bffa630ea7e34f86e71bb58de0a9d5f82c0c372ee2f9961e47ac4ae72',
}
for (const [name, hash] of Object.entries(hashes)) {
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, name))).digest('hex'),
    hash, `Pinned upstream source changed: ${name}`)
}

function load (source, requireStub) {
  const module = { exports: {} }
  vm.runInNewContext(source, { module, exports: module.exports, require: requireStub }, { timeout: 1000 })
  return module.exports
}

const requestSource = read('lib/interceptor/impl/req/requestReplace.js')
const request = load(requestSource + '\nmodule.exports._replaceRequestHeaders = replaceRequestHeaders\n', () => {
  throw new Error('Unexpected request dependency')
})
const responseSource = read('lib/interceptor/impl/res/responseReplace.js')
const lodash = require(require.resolve('lodash', { paths: [root] }))
const response = load(responseSource, name => {
  if (name === 'lodash') return lodash
  if (name === '../req/cacheRequest') return {}
  throw new Error(`Unexpected response dependency: ${name}`)
})
const cors = load(read('lib/interceptor/impl/res/AfterOPTIONSHeaders.js'), name => {
  if (name === './responseReplace') return response
  throw new Error(`Unexpected CORS dependency: ${name}`)
})
const retrySource = read('lib/proxy/mitmproxy/createRequestHandler.js')
const start = retrySource.indexOf('function canRetryWithBody (')
const end = retrySource.indexOf('\n}\n', start)
assert(start >= 0 && end > start, 'retry function not found')
// The extracted original function captures this sandbox binding per call.
function retryWithThreshold (input) {
  const module = { exports: {} }
  vm.runInNewContext(`
    const MAX_RETRY_BODY_SIZE = threshold
    const BODY_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
    ${retrySource.slice(start, end + 2)}
    module.exports = canRetryWithBody
  `, { module, threshold: input.threshold }, { timeout: 1000 })
  return module.exports({ methods: input.methods }, { method: input.method, headers: input.headers })
}

function run (input) {
  input = clone(input)
  if (input.operation === 'retry') return retryWithThreshold(input)
  if (input.operation === 'request') {
    const rOptions = { headers: input.headers }
    request._replaceRequestHeaders(rOptions, input.replacements, { debug () {} })
    return rOptions.headers
  }
  const rawHeaders = input.rawHeaders
  const setHeaders = {}
  const res = { setHeader (name, value) { setHeaders[name] = value } }
  const proxyRes = { rawHeaders }
  if (input.operation === 'response') {
    const replacements = input.replacements
    const previousHeaders = response.replaceResponseHeaders(replacements, res, proxyRes)
    return { rawHeaders, setHeaders, previousHeaders, remainingReplacements: replacements }
  }
  if (input.operation === 'cors') {
    let remainingReplacements = {}
    let previousHeaders = null
    const api = response.replaceResponseHeaders
    response.replaceResponseHeaders = function (replacements, out, proxy) {
      previousHeaders = api(replacements, out, proxy)
      remainingReplacements = replacements
      return previousHeaders
    }
    try {
      cors.responseIntercept(
        { rOptions: { method: input.method, headers: input.requestHeaders }, log: { info () {} } },
        { optionsCrossPolicy: input.optionsCrossPolicy }, {}, res, {}, proxyRes
      )
    } finally {
      response.replaceResponseHeaders = api
    }
    return { rawHeaders, setHeaders, previousHeaders, remainingReplacements }
  }
  throw new Error(`Unknown operation: ${input.operation}`)
}

const cases = []
function add (name, input) { cases.push({ name, input, expected: run(input) }) }
const retryCase = (name, method, headers, methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], threshold = 10) =>
  add(name, { operation: 'retry', method, headers, methods, threshold })
retryCase('default method', null, {})
retryCase('empty method', '', {})
retryCase('method uppercase', 'post', { 'content-length': '10' })
retryCase('configured method exact case', 'get', {}, ['get'])
retryCase('unknown length', 'POST', {})
retryCase('negative length accepted', 'POST', { 'content-length': '-1' })
retryCase('length prefix accepted', 'POST', { 'content-length': '  +10junk' })
retryCase('hex prefix decimal parsing', 'POST', { 'content-length': '0x99' })
retryCase('over threshold', 'PUT', { 'content-length': '11' })
retryCase('enormous length', 'PATCH', { 'content-length': '9'.repeat(400) })
retryCase('array parsed by js', 'DELETE', { 'content-length': ['9', '100'] })
retryCase('length ignored for get', 'GET', { 'content-length': 'nonsense' })
retryCase('transfer encoding empty', 'GET', { 'transfer-encoding': '' })
retryCase('transfer encoding empty array truthy', 'GET', { 'transfer-encoding': [] })
retryCase('mixed case transfer encoding ignored', 'GET', { 'Transfer-Encoding': 'chunked' })
retryCase('expect case insensitive', 'GET', { expect: 'prefix 100-ConTinue suffix' })
retryCase('expect absent string', 'GET', { expect: '' })
retryCase('numeric decimal prefix', 'POST', { 'content-length': 9.9 })
const requestCase = (name, headers, replacements) => add(name, { operation: 'request', headers, replacements })
requestCase('request mutation and truthiness',
  { x: 'old', empty: '', zero: 0, flag: false, Upper: 'retained', remove: 'present' },
  { X: 'new', Empty: null, Zero: false, Flag: '[remove]', UPPER: 'lower', REMOVE: '', Added: [], Nil: null })
requestCase('request collisions', { a: 'old' }, { A: 'first', a: 'second' })
requestCase('request empty objects truthy', { x: 0 }, { X: {}, y: 0, z: false })
const responseCase = (name, rawHeaders, replacements) => add(name, { operation: 'response', rawHeaders, replacements })
responseCase('response duplicates and additions',
  ['X-Foo', 'one', 'x-foo', 'two', 'Vary', 'Accept', 'X-Keep', 'yes'],
  { 'X-FOO': 'new', Vary: '[remove]', Added: ['a', 'b'], Skip: null, Absent: '[remove]' })
responseCase('response same then different duplicate', ['X', 'same', 'x', 'old'], { x: 'same' })
responseCase('response duplicate previous is last changed', ['X', 'first', 'x', 'second'], { x: 'new' })
responseCase('response null does not replace', ['X', 'old'], { X: null })
responseCase('response false zero empty replace and add', ['X', 'old', 'Y', 'old', 'Z', 'old'], { X: false, Y: 0, Z: '', Added: false })
responseCase('response collision uppercase wins', ['X', 'old'], { x: 'lower', X: 'upper' })
responseCase('response collision later mixed wins', ['X', 'old'], { X: 'first', x: 'lower', xX: 'other', XX: 'last' })
responseCase('response no changes', ['X', 'same'], { x: 'same' })
responseCase('response empty', ['X', 'same'], {})
const corsCase = (name, method, requestHeaders, rawHeaders, optionsCrossPolicy = null) =>
  add(name, { operation: 'cors', method, requestHeaders, rawHeaders, optionsCrossPolicy })
corsCase('cors skipped options', 'OPTIONS', { origin: 'https://example.test' }, [])
corsCase('cors method exact', 'options', { origin: 'https://example.test' }, [])
corsCase('cors skipped missing origin', 'GET', {}, [])
corsCase('cors empty origin', 'GET', { origin: '' }, [])
corsCase('cors adds headers', 'GET', { origin: 'https://example.test' }, [])
corsCase('cors vary append and duplicate', 'GET', { origin: 'https://example.test' }, ['Vary', 'Accept-Encoding', 'vary', 'Origin'])
corsCase('cors existing origin exact', 'GET', { origin: 'https://example.test' }, ['Vary', 'Accept, Origin'])
corsCase('cors existing lowercase origin', 'GET', { origin: 'https://example.test' }, ['Vary', 'origin'])
corsCase('cors empty vary', 'GET', { origin: 'https://example.test' }, ['Vary', ''])
corsCase('cors null vary', 'GET', { origin: 'https://example.test' }, ['Vary', null])
corsCase('cors policy truthy', 'GET', { origin: ['one', 'two'] }, [], 'same-site')
corsCase('cors policy falsy', 'GET', { origin: 'site' }, [], false)
corsCase('cors unchanged diagnostic', 'GET', { origin: 'site' },
  ['Access-Control-Allow-Credentials', 'true', 'Access-Control-Allow-Origin', 'site',
    'Cross-Origin-Resource-Policy', 'cross-origin', 'Vary', 'Origin'])
for (const [name, value] of [
  ['null', null], ['false', false], ['zero', 0], ['empty-string', ''],
  ['empty-list', []], ['empty-object', {}], ['remove', '[remove]'],
]) {
  requestCase(`request value ${name}`, { x: 'old', kept: value }, { X: value, Added: value })
  responseCase(`response value ${name}`, ['X', 'old', 'x', 'other'], { X: value, Added: value })
  corsCase(`cors policy ${name}`, 'GET', { origin: 'site' }, [], value)
}
for (const vary of ['*', 'Origin ', ' Origin', 'Accept,\tOrigin', 'Accept, Origin ', 'Origin, Accept', 'Accept,,Origin', false, 0]) {
  corsCase(`cors vary ${JSON.stringify(vary)}`, 'GET', { origin: 'site' }, ['Vary', vary])
}
for (const length of ['', ' \uFEFF+10stuff', '1e9', '10.9', true, null, [], {}, [['9']], ['bad', '9'], -0.5]) {
  retryCase(`retry length ${JSON.stringify(length)}`, 'POST', { 'content-length': length })
}
responseCase('response numeric keys enumerate first', [], { Z: 'z', 10: 'ten', 2: 'two', A: 'a' })
requestCase('request numeric keys enumerate first', { 10: 'ten', 2: 'two', z: 'z' }, { B: 'b', 1: 'one' })

const output = JSON.stringify(cases, null, 2).replace(/[\u007f-\uffff]/g,
  char => `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`) + '\n'
if (mode === '--generate') {
  fs.mkdirSync(path.dirname(fixture), { recursive: true })
  fs.writeFileSync(fixture, output)
  console.log(`Wrote ${cases.length} upstream cases to ${fixture}`)
} else {
  assert.equal(fs.readFileSync(fixture, 'utf8'), output, 'Fixture differs from upstream oracle')
  console.log(`Verified ${cases.length} upstream cases`)
}
