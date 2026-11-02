'use strict'

// Only metadata functions are invoked. No DNS, TLS, or HPKE operation is run.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')

const args = process.argv.slice(2)
const upstreamAt = args.indexOf('--upstream')
const generate = args.includes('--generate')
const verify = args.includes('--verify')
if (upstreamAt < 0 || !args[upstreamAt + 1] || generate === verify ||
    args.some((arg, index) => index !== upstreamAt + 1 && arg !== '--upstream' && arg !== '--generate' && arg !== '--verify')) {
  throw new Error('Usage: node ech-config.cjs --upstream <mitmproxy/src> (--generate | --verify)')
}
const upstream = path.resolve(args[upstreamAt + 1])
const repo = path.resolve(__dirname, '../../../..')
const outputDirectory = path.join(repo, 'port/tests/fixtures/wave3')
const sources = [
  'lib/dns/util.svcb.js',
  'lib/dns/base.js',
  'lib/proxy/common/ech/ech.js',
  'lib/proxy/common/ech/hpke.js',
  'lib/proxy/common/ech/EchHttpsAgent.js',
  'lib/proxy/common/ech/tls13.js',
  'lib/proxy/common/ech/tlsSocket.js',
]
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const sourceHashes = Object.fromEntries(sources.map(relative => [
  relative, sha256(fs.readFileSync(path.join(upstream, relative))),
]))
const dns = require(path.join(upstream, 'lib/dns/util.svcb.js'))
const ech = require(path.join(upstream, 'lib/proxy/common/ech/ech.js'))
const u16 = value => {
  const bytes = Buffer.alloc(2)
  bytes.writeUInt16BE(value)
  return bytes
}
const vector16 = value => Buffer.concat([u16(value.length), value])
const extension = (type, data = Buffer.alloc(0)) => Buffer.concat([u16(type), vector16(data)])
const config = (id, options = {}) => {
  const kem = options.kem ?? 0x20
  const keyLength = ({ 16: 65, 17: 97, 18: 133, 32: 32, 33: 56 })[kem] ?? 7
  // Deterministic arbitrary octets, NOT validated cryptographic public keys.
  const key = Buffer.from(Array.from({ length: keyLength }, (_, index) => (id + index) & 255))
  const name = Buffer.from(options.name ?? 'public.example', 'ascii')
  const suites = Buffer.concat((options.suites ?? [[1, 1]]).flatMap(pair => pair.map(u16)))
  const contents = Buffer.concat([
    Buffer.from([id]), u16(kem), vector16(key), vector16(suites),
    Buffer.from([options.maximumNameLength ?? 64, name.length]), name,
    vector16(Buffer.concat(options.extensions ?? [])),
  ])
  return Buffer.concat([u16(0xfe0d), vector16(contents)])
}
const definitions = [
  ['single-x25519', [config(7)]],
  ['p256-only-fallback', [config(8, { kem: 0x10 })]],
  ['prefer-x25519-after-p256', [config(9, { kem: 0x10 }), config(10)]],
  ['first-x25519-is-stable', [config(11), config(12), config(13, { kem: 0x10 })]],
  ['unknown-kem-falls-back-in-wire-order', [config(14, { kem: 0xbeef }), config(15, { kem: 0x21 })]],
  ['unknown-kdf-aead-retained', [config(16, { suites: [[0xbeef, 0xcafe]] })]],
  ['suite-pairs-must-not-cross-boundaries', [config(17, { suites: [[2, 1], [1, 2]] })]],
  ['supported-pair-later-in-vector', [config(18, { suites: [[3, 3], [1, 1], [0xbeef, 0xcafe]] })]],
  ['picker-and-profile-filter-differ', [config(19, { suites: [[2, 2]] }), config(20)]],
  ['optional-extensions-and-empty-data', [config(21, {
    extensions: [extension(0x1234, Buffer.from('00ff1080', 'hex')), extension(0x2345)],
  })]],
  ['zero-maximum-name-length', [config(22, { maximumNameLength: 0, name: 'Public.Example' })]],
  ['maximum-name-length-is-not-public-name-limit', [config(23, {
    maximumNameLength: 255, name: 'a'.repeat(63) + '.' + 'b'.repeat(63) + '.' + 'c'.repeat(63) + '.' + 'd'.repeat(61),
  })]],
  ['all-known-kem-encoding-lengths', [
    config(24, { kem: 0x10 }), config(25, { kem: 0x11 }), config(26, { kem: 0x12 }),
    config(27, { kem: 0x21 }), config(28),
  ]],
]

function expectedFor(wire) {
  const parsed = dns.parseEchConfigList(wire)
  if (parsed.some(item => item.parseError)) throw new Error('Oracle input unexpectedly malformed')
  const summary = item => ({
    versionCode: item.versionCode,
    length: item.length,
    rawHex: item.raw.toString('hex'),
    configId: item.configId,
    kemId: item.kemId,
    publicKeyHex: item.publicKey.toString('hex'),
    // The actual upstream API returns flat uint16 IDs; preserve that shape here.
    cipherSuites: item.cipherSuites,
    maximumNameLength: item.maximumNameLength,
    publicName: item.publicName,
    extensionsLength: item.extensionsLength,
  })
  const indexOf = selected => selected == null ? null : parsed.findIndex(item =>
    item.versionCode === selected.versionCode && item.raw.equals(selected.raw))
  return {
    configs: parsed.map(summary),
    pickIndex: indexOf(dns.pickEchConfig(wire)),
    selectIndex: indexOf(ech.selectEchConfig(wire)),
    supportedCipherSuites: parsed.map(ech.supportedCipherSuite),
  }
}

const fixtures = definitions.map(([name, configs]) => {
  const wire = vector16(Buffer.concat(configs))
  return { name, input: { kind: 'wireHex', value: wire.toString('hex') }, expected: expectedFor(wire) }
})
const fixtureText = JSON.stringify(fixtures, null, 2) + '\n'
const provenance = {
  schemaVersion: 1,
  baseCommit: '754ff423b59c743cf94e68b3417c1fde4a6f42b7',
  upstreamPackage: '@blue-frontier/mitmproxy',
  upstreamCapture: 'offline-proxy-fixes-20261101/runtime/core/node_modules/@blue-frontier/mitmproxy/src',
  sourceHashes,
  generator: { path: 'port/tests/oracles/wave3/ech-config.cjs', sha256: sha256(fs.readFileSync(__filename)) },
  fixture: { path: 'port/tests/fixtures/wave3/ech-config.valid.json', sha256: sha256(fixtureText), cases: fixtures.length },
  safetyFixture: {
    path: 'port/tests/fixtures/wave3/ech-config.safety.json',
    sha256: sha256(fs.readFileSync(path.join(outputDirectory, 'ech-config.safety.json'))),
    origin: 'Local strict-policy expectations; not upstream oracle output',
  },
  schema: 'docs/migration-wave3/ech-config.md#fixture-schema',
  note: 'Expected values come from actual upstream functions on well-formed inputs; keys are arbitrary octets, not cryptographically validated.',
}
const outputs = [
  ['ech-config.valid.json', fixtureText],
  ['ech-config.provenance.json', JSON.stringify(provenance, null, 2) + '\n'],
]
if (generate) fs.mkdirSync(outputDirectory, { recursive: true })
if (verify) {
  const saved = JSON.parse(fs.readFileSync(path.join(outputDirectory, 'ech-config.valid.json'), 'utf8'))
  for (const fixture of saved) {
    assert.equal(fixture.input.kind, 'wireHex')
    assert.deepEqual(expectedFor(Buffer.from(fixture.input.value, 'hex')), fixture.expected, fixture.name)
  }
}
for (const [filename, text] of outputs) {
  const target = path.join(outputDirectory, filename)
  if (verify) {
    assert.deepEqual(
      JSON.parse(fs.readFileSync(target, 'utf8')),
      JSON.parse(text),
      `Fixture drift: ${filename}; source/generator/safety hashes are checked as well as expected values`,
    )
  } else {
    fs.writeFileSync(target, text)
  }
}
console.log(`${verify ? 'Verified' : 'Generated'} ${fixtures.length} actual-upstream ECH parser/selection fixtures`)
