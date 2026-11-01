// SPDX-License-Identifier: MPL-2.0
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const net = require('node:net')

const installed = Symbol.for('dsha.http-navigation.installed')
const defaultConfigPath = path.join(__dirname, 'navigation-rules.json')

function validHostname (host) {
  return typeof host === 'string' && host.length <= 253 && !net.isIP(host) &&
    host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))
}

function compileRules (config) {
  if (!config || config.version !== 1 || !Array.isArray(config.rules) || config.rules.length > 128) {
    throw new Error('Expected version 1 and a rules array (at most 128 rules)')
  }
  const rules = new Map()
  for (const rule of config.rules) {
    if (!rule || (rule.enabled != null && typeof rule.enabled !== 'boolean')) {
      throw new Error('Invalid rule or enabled flag')
    }
    if (rule.enabled === false) continue
    if (!Array.isArray(rule.sourceHosts) || !rule.sourceHosts.length || rule.sourceHosts.length > 128 ||
        !rule.sourceHosts.every(validHostname) ||
        !Array.isArray(rule.sourceSchemes) || !rule.sourceSchemes.length ||
        !rule.sourceSchemes.every(scheme => scheme === 'http' || scheme === 'https')) {
      throw new Error('Expected exact sourceHosts and http/https sourceSchemes')
    }
    const target = new URL(rule.targetOrigin)
    if (target.protocol !== 'https:' || target.port || target.username || target.password ||
        !validHostname(target.hostname) || target.pathname !== '/' || target.search || target.hash) {
      throw new Error('targetOrigin must be an HTTPS origin without credentials, path or custom port')
    }
    for (const host of rule.sourceHosts) {
      for (const scheme of rule.sourceSchemes) {
        const source = `${scheme}://${host.toLowerCase()}`
        if (rules.has(source)) throw new Error('Duplicate navigation source')
        rules.set(source, target.origin)
      }
    }
  }
  // Every redirect preserves the path and query, so an origin cycle is a URL cycle.
  const checked = new Set()
  for (const source of rules.keys()) {
    const visited = new Set()
    let next = source
    while (rules.has(next) && !checked.has(next)) {
      if (visited.has(next)) throw new Error('Navigation rules contain a redirect loop')
      visited.add(next)
      next = rules.get(next)
    }
    for (const origin of visited) checked.add(origin)
  }
  return rules
}

const defaultRules = compileRules(JSON.parse(fs.readFileSync(defaultConfigPath, 'utf8')))

function createRuleStore (configPath, { log = console.warn, shared = false } = {}) {
  let current = defaultRules
  let lastContent
  let lastError
  try {
    fs.mkdirSync(path.dirname(configPath), { recursive: true })
    fs.copyFileSync(defaultConfigPath, configPath, fs.constants.COPYFILE_EXCL)
  } catch (error) {
    if (error.code !== 'EEXIST') log(`[navigation] Cannot initialize config: ${error.message}`)
  }
  if (shared) {
    try {
      // Android's inherited umask otherwise makes a copied config inaccessible to ADB.
      // The app-specific external directory still enforces Android storage isolation.
      fs.chmodSync(configPath, 0o666)
    } catch (error) {
      log(`[navigation] Cannot make external config editable: ${error.message}`)
    }
  }
  function getRules () {
    try {
      if (fs.statSync(configPath).size > 65536) throw new Error('Configuration exceeds 64 KiB')
      const content = fs.readFileSync(configPath, 'utf8')
      if (content !== lastContent) {
        lastContent = content
        current = compileRules(JSON.parse(content))
        lastError = null
      }
    } catch (error) {
      if (lastError !== error.message) {
        log(`[navigation] Keeping last valid rules: ${error.message}`)
        lastError = error.message
      }
    }
    return current
  }
  getRules()
  return { getRules }
}

function navigationUrl (req, ssl) {
  const headers = req.headers || {}
  const host = String(headers.host || '').toLowerCase()
  if (!host || /[\\/?#@\s]/.test(host)) return null
  try {
    let url
    if (typeof req.url !== 'string' || /[\\\r\n\t]/.test(req.url)) return null
    if (req.url.startsWith('/')) {
      if (req.url.startsWith('//')) return null
      url = new URL(`${ssl ? 'https' : 'http'}://${host}${req.url}`)
    } else {
      url = new URL(req.url)
    }
    if (!['http:', 'https:'].includes(url.protocol) || url.port || url.username || url.password ||
        (ssl != null && (url.protocol === 'https:') !== ssl)) return null
    const authority = new URL(`${url.protocol}//${host}`)
    if (authority.host !== url.host) return null
    return url
  } catch {
    return null
  }
}

function upgradeTarget (req, ssl, rules = defaultRules) {
  if (req.method !== 'GET' && req.method !== 'HEAD') return null
  const headers = req.headers || {}
  if (headers['transfer-encoding'] ||
      (headers['content-length'] != null && String(headers['content-length']) !== '0')) return null

  const destination = String(headers['sec-fetch-dest'] || '').toLowerCase()
  const document = destination === 'document' || destination === 'iframe' ||
    (!destination && /(?:^|,)\s*text\/html(?:\s*;|\s*,|$)/i.test(headers.accept || ''))
  if (!document) return null

  const url = navigationUrl(req, ssl)
  if (!url) return null
  const target = (typeof rules === 'function' ? rules() : rules).get(url.origin)
  return target ? target + url.pathname + url.search + url.hash : null
}

function wrapHandler (handler, getRules = () => defaultRules) {
  return function (req, res, ssl) {
    const target = upgradeTarget(req, ssl, getRules)
    if (!target) return handler.call(this, req, res, ssl)

    // Redirect before DNS/IP selection: an unreachable port 80 must not
    // penalize the same IP subsequently used by HTTPS.
    res.writeHead(307, {
      Location: target,
      'Cache-Control': 'no-store',
      'Content-Length': '0',
      'DS-Interceptor': 'https-navigation',
    })
    res.end()
  }
}

function wrapConnectInterceptor (interceptor, getRules) {
  return function (req, ...args) {
    const original = interceptor ? interceptor.call(this, req, ...args) : null
    // Respect explicit core whitelist decisions.
    if (original != null) return original
    if (!/^[a-z0-9.-]+:443$/i.test(req.url || '')) return original
    const host = req.url.slice(0, -4).toLowerCase()
    return getRules().has(`https://${host}`) ? true : original
  }
}

function install (coreRoot, options = {}) {
  const root = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src/lib/proxy/mitmproxy')
  const requestPath = require.resolve(path.join(root, 'createRequestHandler.js'))
  const factory = require(requestPath)
  if (factory[installed]) return
  const configPath = options.configPath || process.env.DSHA_NAVIGATION_CONFIG ||
    path.join(os.homedir(), '.dev-sidecar', 'navigation-rules.json')
  const { getRules } = createRuleStore(configPath, {
    shared: Boolean(process.env.DSHA_NAVIGATION_CONFIG), ...options,
  })
  const wrapped = function (...args) {
    return wrapHandler(factory.apply(this, args), getRules)
  }
  const connectPath = require.resolve(path.join(root, 'createConnectHandler.js'))
  const connectFactory = require(connectPath)
  require.cache[connectPath].exports = function (interceptor, ...args) {
    return connectFactory.call(this, wrapConnectInterceptor(interceptor, getRules), ...args)
  }
  wrapped[installed] = true
  require.cache[requestPath].exports = wrapped
  console.log(`[navigation] Config: ${configPath}`)
}

module.exports = { install, compileRules, createRuleStore, upgradeTarget, wrapHandler, wrapConnectInterceptor }
