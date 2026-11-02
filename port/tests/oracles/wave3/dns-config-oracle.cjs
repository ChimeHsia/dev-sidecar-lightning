// SPDX-License-Identifier: MPL-2.0
// Runs the actual upstream functions; only DNS constructors and logging are stubbed.
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')

const args = process.argv.slice(2)
const flag = args.indexOf('--upstream')
if (flag < 0 || !args[flag + 1] || !args.includes('--generate') && !args.includes('--verify') ||
    args.includes('--generate') && args.includes('--verify')) {
  throw new Error('Usage: node dns-config-oracle.cjs --upstream <mitmproxy/src> --generate|--verify')
}
const upstream = path.resolve(args[flag + 1])
const fixture = path.resolve(__dirname, '../../fixtures/wave3/dns-config.json')
const fixtures = JSON.parse(fs.readFileSync(fixture, 'utf8'))
const orig = Module._load
let descriptors = []
const logger = { info () {}, warn () {}, error () {}, debug () {} }
class StubBase {}
function constructor (kind) {
  return class {
    constructor (...args) {
      if (kind === 'preset') {
        Object.assign(this, { key: 'builtin:PreSet', name: 'PreSet', transport: kind })
      } else if (kind === 'nat64') {
        Object.assign(this, { key: 'builtin:Nat64', name: 'NAT64', transport: kind, nat64Options: args[0], prefix: '', dohList: [] })
      } else {
        const [name, cacheSize, , server] = args
        const https = kind === 'https'
        Object.assign(this, {
          key: `provider:${name}`, name, transport: kind, server,
          port: https ? null : args[4] ?? null,
          family: (https ? args[4] : args[5]) ?? null,
          cacheSize: cacheSize ?? null,
          sni: (https ? args[5] : kind === 'tls' ? args[6] : null) ?? null,
        })
      }
      descriptors.push(this)
    }
    initEch (options) { this.echOptions = options }
  }
}
const stubs = {
  './base': StubBase,
  './preset.js': constructor('preset'),
  './nat64.js': constructor('nat64'),
  './https.js': constructor('https'),
  './tls.js': constructor('tls'),
  './tcp.js': constructor('tcp'),
  './udp.js': constructor('udp'),
}
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename.startsWith(upstream)) {
    if (request.endsWith('util.log.server')) return logger
    if (parent.filename === path.join(upstream, 'lib/dns/index.js') && request in stubs) return stubs[request]
    if (parent.filename === path.join(upstream, 'lib/dns/nat64.js') && request === './base') return StubBase
    if (parent.filename === path.join(upstream, 'lib/dns/nat64.js') && request === 'node:https') {
      return { Agent: class {} }
    }
  }
  return orig.apply(this, arguments)
}
const nat64 = require(path.join(upstream, 'lib/dns/nat64.js'))
const dns = require(path.join(upstream, 'lib/dns/index.js'))
const match = require(path.join(upstream, 'utils/util.match.js'))
const domain = require(path.join(upstream, 'lib/proxy/common/ech/domain.js'))

function run (input) {
  if (input.operation === 'toDohList') return nat64.toDohList(input.doh)
  if (input.operation === 'resolveDohList') return nat64.resolveDohList(input.config, input.providers)
  descriptors = []
  let dnsMap
  try {
    dnsMap = dns.initDNS(input.providers || {}, {}, input.options || {})
  } catch (e) {
    return { error: e.message }
  }
  if (input.operation === 'normalize') {
    return {
      descriptors: descriptors.map(d => ({
        key: d.key, name: d.name, transport: d.transport, server: d.server ?? null,
        port: d.port ?? null, family: d.family ?? null, cacheSize: d.cacheSize ?? null,
        sni: d.sni ?? null, echOptions: d.echOptions ?? null, nat64Options: d.nat64Options ?? null,
      })),
      bindings: Object.fromEntries(Object.keys(dnsMap).map(k => [k, dnsMap[k].key])),
    }
  }
  if (input.operation === 'select') {
    const ech = input.ech ?? null
    const domainMap = value => domain.createEchDomainMap({ domains: value })
    const config = {
      dnsMap,
      mapping: match.domainMapRegexply(input.mapping || {}),
      preSetIpList: match.domainMapRegexply(input.preSetIpList || {}),
      ech,
      nat64Domains: domainMap(input.nat64Domains),
      echDomains: domainMap(input.echDomains === undefined ? ech?.domains : input.echDomains),
      echPreSetIpDomains: domainMap(input.echPreSetIpDomains === undefined ? ech?.preSetIpDomains : input.echPreSetIpDomains),
    }
    const result = dns.getDNSAndFamily(config, input.hostname)
    return {
      choice: result == null ? null : { providerKey: result.dns.key, family: result.family ?? null },
      isNat64: dns.isNat64Domain(config, input.hostname),
      isEch: dns.isEchDomain(config, input.hostname),
      ignorePreSet: dns.isEchIgnorePreSetIp(config, input.hostname),
      echProviderKey: dns.getEchDNS(config)?.key ?? null,
    }
  }
  throw new Error(`Unknown operation: ${input.operation}`)
}

for (const item of fixtures) {
  const actual = run(item.input)
  if (args.includes('--generate')) item.expected = actual
  else assert.deepStrictEqual(actual, item.expected, item.name)
}
if (args.includes('--generate')) fs.writeFileSync(fixture, JSON.stringify(fixtures, null, 2) + '\n')
console.log(`${args.includes('--generate') ? 'Generated' : 'Verified'} ${fixtures.length} upstream DNS fixtures`)
