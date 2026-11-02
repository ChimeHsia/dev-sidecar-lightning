'use strict'

// Executes the supplied upstream source, never copies or modifies it.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createHash } = require('node:crypto')

const args = process.argv.slice(2)
const upstreamIndex = args.indexOf('--upstream')
if (upstreamIndex < 0 || !args[upstreamIndex + 1] ||
    args.includes('--generate') === args.includes('--verify')) {
  throw new Error('Usage: node ip-policy.cjs (--generate|--verify) --upstream <src>')
}
const upstream = path.resolve(args[upstreamIndex + 1])
const fixturePath = path.resolve(__dirname, '../../fixtures/wave3/ip-policy.json')
const sourceHashes = {
  'lib/dns/util.ip.js': '0db0c7a14304b17403aea1753e9517aa43a340d76af12a53d978d527f2315f44',
  'lib/dns/base.js': '60d1cbe1c24d4a6d44391bf4d4dd630b739d3bcf84f7ec0c4b5e39b3e1ec7284',
  'lib/speed/SpeedTester.js': 'f4ff4dcb53f4d4a9ae8486f95bbc596860f021fa984bd3b1ed3dac711341159f',
}
for (const [relative, expected] of Object.entries(sourceHashes)) {
  const actual = createHash('sha256').update(fs.readFileSync(path.join(upstream, relative))).digest('hex')
  if (actual !== expected) throw new Error(`Upstream source hash mismatch: ${relative}`)
}
const util = require(path.join(upstream, 'lib/dns/util.ip.js'))

function base (available) {
  const source = fs.readFileSync(path.join(upstream, 'lib/dns/base.js'), 'utf8')
  class Cache {
    get () { return null }
    set (key, value) {
      value.setBackupList = list => { this.candidates = [...list] }
    }
  }
  class Choice {
    constructor (hostname) { this.value = hostname }
    setBackupList () {}
  }
  const log = { info () {}, warn () {}, debug () {}, error () {} }
  const dependencies = {
    'lru-cache': { LRUCache: Cache },
    '../../utils/util.log.server': log,
    '../../utils/util.match': { matchHostname: map => map.matched },
    './util.ip': util,
    './util.svcb': {},
    '../choice/index': { DynamicChoice: Choice },
    'node:os': {
      networkInterfaces: () => available
        ? { fixture: [{ internal: false, family: 'IPv6', address: '2001:db8::1' }] }
        : {},
    },
  }
  const module = { exports: {} }
  const context = {
    module, exports: module.exports, console,
    require: name => {
      if (!(name in dependencies)) throw new Error(`Unexpected dependency: ${name}`)
      return dependencies[name]
    },
  }
  vm.runInNewContext(
    source + '\nmodule.exports.__oracle = { reportIPv6Error, state: () => ({ available: !ipv6Unavailable, errorCount: ipv6ErrCount }) }\n',
    context,
    { filename: path.join(upstream, 'lib/dns/base.js') },
  )
  return module.exports
}

function speedTester () {
  const module = { exports: {} }
  const dependencies = {
    'node:net': require('node:net'),
    lodash: {},
    '../../utils/util.log.server': { info () {} },
    './config.js': {},
    '../../utils/util.match.js': { domainMapRegexply: () => ({}) },
    '../cloudflareRoute': { rewriteIp: ip => ip },
    '../dns/util.ip': util,
    '@blue-frontier/dev-sidecar/src/config/index.js': { configFromFiles: { server: { dns: {} } } },
  }
  vm.runInNewContext(fs.readFileSync(path.join(upstream, 'lib/speed/SpeedTester.js'), 'utf8'), {
    module, exports: module.exports,
    require: name => {
      if (!(name in dependencies)) throw new Error(`Unexpected dependency: ${name}`)
      return dependencies[name]
    },
  })
  return module.exports
}

async function evaluate (input) {
  switch (input.operation) {
    case 'zero': return util.isZeroIp(input.value)
    case 'ipv6': return util.isIPv6(input.value)
    case 'speed': {
      // Avoid the constructor, which starts asynchronous speed tests.
      const tester = Object.create(speedTester().prototype)
      tester.hostname = 'fixture.test'
      tester.getFromOneDns = async () => [...input.candidates]
      return (await tester.getIpListFromDns({ fixture: {} }, 4)).map(item => item.host)
    }
    case 'preset': {
      const Base = base(true)
      const dns = new Base(null, null, 'fixture', 'fixture', 1, { matched: input.preset })
      dns._lookup = async () => null
      const result = await dns._lookupWithPreSetIpList('fixture.test', { ignorePreSetIpList: input.ignore })
      return result == null ? null : { addresses: Array.from(result), isPreset: result.isPreSet === true }
    }
    case 'dns': {
      const Base = base(input.available)
      const dns = new Base(null, null, 'fixture', 'fixture', 1, null)
      dns._lookupWithPreSetIpList = async () => input.candidates == null ? null : [...input.candidates]
      await dns.lookup(input.hostname)
      return dns.cache.candidates
    }
    case 'availability': {
      const Base = base(input.available)
      const states = [Base.__oracle.state()]
      for (const address of input.reports) {
        Base.__oracle.reportIPv6Error(address)
        states.push(Base.__oracle.state())
      }
      return states
    }
    default: throw new Error(`Unknown operation ${input.operation}`)
  }
}

const inputs = [
  ...[
    null, false, '', '0.0.0.0', ' 0.0.0.0 ', '[0.0.0.0]', '0.0.0.0]',
    '::', '[::]', ' [::] ', '::]', '[::', '[[::]]', '0000:0:00:000:0:0:0:0000',
    '::0.0.0.0', '::%eth0', '::1', '0::0::0', '00.0.0.0',
    '\uFEFF::\uFEFF', '\u0085::\u0085', '[ ::]', '::ffff:0.0.0.0',
    '[::%eth0]', '0:0:0:0:0:0:0.0.0.0', 0, {}, [],
  ].map((value, i) => ({ name: `zero-${i}`, input: { operation: 'zero', value } })),
  ...[
    '::', '::1', ' 2001:db8::1 ', '[invalid', 'not[an-ip', '::1]', 'bad',
    'fe80::1%eth0', '2001:db8::1%eth0', '::ffff:192.0.2.1', '1:2:3:4:5:6:7:8::',
    '\uFEFF::1', '\u0085::1', 'fe80::1%en_0', 'FE80::1%eth0',
  ].map((value, i) => ({ name: `ipv6-${i}`, input: { operation: 'ipv6', value } })),
  { name: 'speed-zero-filter', input: { operation: 'speed', candidates: ['0.0.0.0', '::', '::0.0.0.0', ' 0.0.0.0 ', '192.0.2.1', '::1'] } },
  { name: 'preset-array', input: { operation: 'preset', preset: ['::', '192.0.2.1', '192.0.2.1'], ignore: false } },
  { name: 'preset-empty', input: { operation: 'preset', preset: [], ignore: false } },
  { name: 'preset-null', input: { operation: 'preset', preset: null, ignore: false } },
  { name: 'preset-ignore', input: { operation: 'preset', preset: ['::1'], ignore: true } },
  { name: 'preset-object', input: { operation: 'preset', preset: { '192.0.2.1': true, '::1': {}, a: [], b: false, c: null, d: 0, e: '', f: 'false', g: '0', h: 'False', i: -1, j: ' ' }, ignore: false } },
  { name: 'preset-object-js-order', input: { operation: 'preset', preset: { '10': true, '2': true, '01': true, '4294967295': true, '0': true, '4294967294': true }, ignore: false } },
  { name: 'preset-no-enabled', input: { operation: 'preset', preset: { a: false, b: null, c: '0' }, ignore: false } },
  { name: 'dns-no-ipv6', input: { operation: 'dns', available: false, hostname: 'example.test', candidates: ['::', '0.0.0.0', ' [broken', '::1]', '192.0.2.1', '192.0.2.1'] } },
  { name: 'dns-available', input: { operation: 'dns', available: true, hostname: 'example.test', candidates: ['::', '0.0.0.0', '::1'] } },
  { name: 'dns-no-answer', input: { operation: 'dns', available: false, hostname: 'example.test', candidates: null } },
  { name: 'availability-errors', input: { operation: 'availability', available: true, reports: ['192.0.2.1', '::1', 'bad', '[broken', '2001:db8::1', '::2'] } },
  { name: 'availability-disabled', input: { operation: 'availability', available: false, reports: ['::1', '::2', '::3'] } },
]

async function main () {
  if (args.includes('--generate')) {
    const fixtures = []
    for (const test of inputs) fixtures.push({ ...test, expected: await evaluate(test.input) })
    fs.mkdirSync(path.dirname(fixturePath), { recursive: true })
    fs.writeFileSync(fixturePath, JSON.stringify(fixtures, null, 2) + '\n')
    console.log(`Generated ${fixtures.length} fixtures: ${fixturePath}`)
    return
  }
  const fixtures = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
  for (const test of fixtures) {
    const actual = await evaluate(test.input)
    if (JSON.stringify(actual) !== JSON.stringify(test.expected)) {
      throw new Error(`${test.name}: expected ${JSON.stringify(test.expected)}, got ${JSON.stringify(actual)}`)
    }
  }
  console.log(`Verified ${fixtures.length} upstream fixtures`)
}
main().catch(error => { console.error(error); process.exitCode = 1 })
