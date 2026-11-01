// SPDX-License-Identifier: MPL-2.0
// Generates and replays only valid, intentionally supported upstream cases.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

const args = process.argv.slice(2)
const upstreamIndex = args.indexOf('--upstream')
const generate = args.includes('--generate')
const verify = args.includes('--verify')
if (generate === verify || upstreamIndex < 0 || !args[upstreamIndex + 1] ||
    args.length !== 3) {
  throw new Error('Usage: node svcb.cjs (--generate|--verify) --upstream /absolute/path/to/util.svcb.js')
}
const source = path.resolve(args[upstreamIndex + 1])
const sha256 = crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex')
const pinned = '8f2fc47ae1ea9e9df4e020b581810d97f3192e026031952464fee7ee960ee6b4'
if (sha256 !== pinned) throw new Error(`Unexpected upstream SHA-256: ${sha256}`)
const svc = require(source)
const fixture = path.resolve(__dirname, '../../fixtures/wave3/svcb.json')
const hex = bytes => Buffer.from(bytes).toString('hex')
const buf = value => Buffer.from(value, 'hex')

// Projection: wire bytes are lowercase hex strings; decoded values use JSON
// primitives/arrays, and opaque bytes use {hex:"..."}. Maps are deliberately
// omitted: ordered params let the consumer reconstruct first-occurrence maps.
function value (v) {
  return Buffer.isBuffer(v) ? { hex: hex(v) } : v
}
function param (p, raw) {
  const result = { key: p.key, name: p.name, value: value(p.value) }
  if (raw !== undefined) result.raw = hex(raw)
  return result
}
function service (r, raw, start) {
  let cursor = svc.decodeName(raw, start + 2).offset
  return {
    priority: r.priority,
    target: r.target,
    params: r.params.map(p => {
      const length = raw.readUInt16BE(cursor + 2)
      const bytes = raw.subarray(cursor + 4, cursor + 4 + length)
      cursor += 4 + length
      return param(p, bytes)
    }),
  }
}
function response (b) {
  const r = svc.parseResponse(b)
  let pos = 12
  for (const q of r.questions) pos = svc.decodeName(b, pos).offset + 4
  const answerCount = b.readUInt16BE(6)
  const authorityCount = b.readUInt16BE(8)
  return {
    id: r.id,
    flags: r.flags,
    rcode: r.rcode,
    truncated: r.truncated,
    questions: r.questions.map(q => ({ name: q.name, type: q.type, typeCode: q.typeCode, klass: q.class })),
    answers: r.answers.map((rr, i) => {
      pos = svc.decodeName(b, pos).offset
      const length = b.readUInt16BE(pos + 8)
      pos += 10
      const start = pos
      pos += length
      const projected = {
        name: rr.name, type: rr.type, typeCode: rr.typeCode, klass: rr.class, ttl: rr.ttl,
        section: i < answerCount ? 'ANSWER' : i < answerCount + authorityCount ? 'AUTHORITY' : 'ADDITIONAL',
        raw: hex(b.subarray(start, pos)),
      }
      if (rr.typeCode === 64 || rr.typeCode === 65) projected.service = service(rr.data, b, start)
      if (rr.typeCode === 5) projected.cname = rr.data
      return projected
    }),
  }
}
function evaluate (input) {
  switch (input.op) {
    case 'type':
      return { name: svc.typeName(input.code), isSvc: svc.isSvcType(input.type), code: svc.typeCode(input.type) }
    case 'encodeName': return hex(svc.encodeName(input.text))
    case 'query': return hex(svc.encodeQuery(input.name, input.options))
    case 'decodeName': {
      const r = svc.decodeName(buf(input.wire), input.offset)
      return { name: r.name, offset: r.offset }
    }
    case 'param': return param(svc.decodeSvcParam(input.key, buf(input.wire)), buf(input.wire))
    case 'record': {
      const bytes = buf(input.wire)
      return service(svc.decodeSvcRecord(bytes, input.offset, input.length), bytes, input.offset)
    }
    case 'response': return response(buf(input.wire))
    case 'tokenize': return svc.tokenize(input.text)
    case 'presentation': {
      const r = svc.parsePresentation(input.text)
      return { priority: r.priority, target: r.target, params: r.params.map(p => param(p)) }
    }
    default: throw new Error(`unknown operation ${input.op}`)
  }
}
function u16 (n) { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b }
function u32 (n) { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b }
function p (key, h) { const b = buf(h); return Buffer.concat([u16(key), u16(b.length), b]) }
const rdata = Buffer.concat([
  u16(1), buf('c00c'),
  p(0, '000100030005'), p(1, '02683208687474702f312e31'), p(2, ''),
  p(3, '01bb'), p(4, 'c0000201cb007107'), p(5, '0004fe0d0102'),
  p(6, '20010db8000000000000000000000001'), p(7, '2f646e732d71756572797b3f646e737d'),
  p(8, ''), p(65400, '00ff8081'), p(3, '2053'),
])
const question = Buffer.concat([svc.encodeName('example.test'), u16(65), u16(1)])
function rr (name, type, klass, ttl, data) {
  return Buffer.concat([buf(name), u16(type), u16(klass), u32(ttl), u16(data.length), data])
}
const packet = Buffer.concat([
  buf('beef83830001000200010001'), question,
  rr('c00c', 65, 1, 0xffffffff, rdata),
  rr('c00c', 5, 1, 300, buf('03777777c00c')),
  rr('c00c', 65000, 3, 42, buf('deadbeef')),
  rr('00', 41, 1232, 32768, buf('')),
])
const cases = [
  ['type https', { op: 'type', code: 65, type: 'HTTPS' }],
  ['type svcb numeric', { op: 'type', code: 64, type: 64 }],
  ['type fallback', { op: 'type', code: 65000, type: 'OTHER' }],
  ['type cname', { op: 'type', code: 5, type: 'SVCB' }],
  ['root name', { op: 'encodeName', text: '.' }],
  ['utf8 name', { op: 'encodeName', text: 'caf\u00e9.example.' }],
  ['empty labels', { op: 'encodeName', text: 'a..b.' }],
  ['https query', { op: 'query', name: 'example.test', options: { id: 65535 } }],
  ['svcb query dnssec', { op: 'query', name: 'www.example.test.', options: { id: 4660, type: 64, udpPayloadSize: 4096, dnssecOk: true } }],
  ['unknown query type', { op: 'query', name: '.', options: { id: 0, type: 65000, udpPayloadSize: 512 } }],
  ['compressed name offset', { op: 'decodeName', wire: '03666f6f076578616d706c6504746573740003777777c004ff', offset: 18 }],
  ['pointer chain', { op: 'decodeName', wire: '016100c000c003ff', offset: 5 }],
  ['mandatory', { op: 'param', key: 0, wire: '0001000300ff' }],
  ['alpn', { op: 'param', key: 1, wire: '02683208687474702f312e31' }],
  ['no default alpn', { op: 'param', key: 2, wire: '' }],
  ['port', { op: 'param', key: 3, wire: '01bb' }],
  ['ipv4 hints', { op: 'param', key: 4, wire: 'c0000201cb007107' }],
  ['ech opaque', { op: 'param', key: 5, wire: '0004fe0d0102' }],
  ['ipv6 hints', { op: 'param', key: 6, wire: '20010db800000000000000000000000100000000000000000000000000000001' }],
  ['dohpath', { op: 'param', key: 7, wire: '2f646e732d71756572797b3f646e737d' }],
  ['ohttp', { op: 'param', key: 8, wire: '' }],
  ['unknown parameter', { op: 'param', key: 65400, wire: '00ff8081' }],
  ['service record', { op: 'record', wire: hex(packet), offset: 42, length: rdata.length }],
  ['response all sections', { op: 'response', wire: hex(packet) }],
  ['tokens quotes escapes', { op: 'tokenize', text: '1 . alpn="h2,h3" dohpath="/dns query\\"v1" ohttp' }],
  ['presentation complete', { op: 'presentation', text: '1 . alpn="h2,h3" port=443 ipv4hint="192.0.2.1,203.0.113.7" ech="AAT+DQEC" ipv6hint="2001:db8::1" mandatory="alpn,port" no-default-alpn dohpath="/dns query" ohttp key123="opaque" port=8443' }],
  ['presentation root default and flag', { op: 'presentation', text: '0 example.test unknown' }],
]
if (generate) {
  const actual = cases.map(([name, input]) => ({ name, input, expected: evaluate(input) }))
  fs.mkdirSync(path.dirname(fixture), { recursive: true })
  fs.writeFileSync(fixture, JSON.stringify(actual, null, 2) + '\n')
  console.log(`Generated ${actual.length} cases: ${fixture}`)
} else {
  const existing = fs.readFileSync(fixture, 'utf8')
  const cases = JSON.parse(existing)
  const assert = require('node:assert/strict')
  for (const { name, input, expected } of cases) {
    assert.deepStrictEqual(evaluate(input), expected, name)
  }
  console.log(`Verified ${cases.length} cases against ${pinned}`)
}
