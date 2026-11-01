// SPDX-License-Identifier: MPL-2.0
// Adapts @blue-frontier/dev-sidecar/src/merge.js and
// @blue-frontier/mitmproxy/src/utils/util.match.js to Kotlin/JS helpers.
'use strict'

const path = require('node:path')
const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key)
const isContainer = value => value !== null && typeof value === 'object'
const isObject = value => isContainer(value) && !Array.isArray(value)

function setOwn (target, key, value) {
  // JSON keys such as "__proto__" must remain data, not invoke inherited setters.
  Object.defineProperty(target, key, {
    value, enumerable: true, configurable: true, writable: true,
  })
}

// Kotlin decides all merge/deletion semantics. This only reconciles its JSON
// result with JS references and, during cleanup, restores sparse array slots.
function applyResult (target, result, sparse) {
  for (const key of Object.keys(target)) {
    if (!own(result, key)) delete target[key]
  }
  const array = Array.isArray(result)
  if (array) target.length = result.length
  for (const key of Object.keys(result)) {
    const value = result[key]
    if (sparse && array && value === null) {
      delete target[key]
      continue
    }
    if (isContainer(value)) {
      const previous = own(target, key) ? target[key] : undefined
      const reuse = isContainer(previous) &&
        Array.isArray(previous) === Array.isArray(value) &&
        (sparse || !Array.isArray(value))
      const child = reuse ? previous : (Array.isArray(value) ? [] : {})
      applyResult(child, value, sparse)
      if (!reuse) setOwn(target, key, child)
    } else {
      setOwn(target, key, value)
    }
  }
}

/**
 * Install before requiring DevSidecar. coreRoot is the extracted application
 * directory containing node_modules. helpers is the Kotlin/JS root export.
 * Configuration calls use the helpers' JSON boundary; hostname queries do not.
 * Merged arrays are copied from Kotlin output; overlay array identity is not
 * retained. Cleanup preserves existing container identities and sparse slots.
 */
function install (coreRoot, helpers) {
  for (const name of ['createHostnameMatcher', 'mergeConfig', 'sanitizeConfig']) {
    if (!helpers || typeof helpers[name] !== 'function') {
      throw new TypeError(`Missing Kotlin helper: ${name}`)
    }
  }
  const root = path.resolve(coreRoot)
  const mergeApi = require(path.join(root, 'node_modules/@blue-frontier/dev-sidecar/src/merge.js'))

  mergeApi.doMerge = function (base, overlay) {
    if (!isObject(base) || (overlay != null && !isObject(overlay))) {
      throw new TypeError('doMerge expects JSON objects')
    }
    const result = JSON.parse(helpers.mergeConfig(
      JSON.stringify(base), JSON.stringify(overlay == null ? {} : overlay),
    ))
    if (!isObject(result)) throw new TypeError('mergeConfig must return a JSON object')
    applyResult(base, result, false)
    return base
  }

  mergeApi.deleteNullItems = function (target) {
    if (!isContainer(target)) return
    // The exported Kotlin sanitizer accepts object roots; wrap a root array.
    const array = Array.isArray(target)
    const result = JSON.parse(helpers.sanitizeConfig(
      JSON.stringify(array ? { value: target } : target),
    ))
    const cleaned = array && isObject(result) ? result.value : result
    if (!isContainer(cleaned) || Array.isArray(cleaned) !== array) {
      throw new TypeError('sanitizeConfig must preserve the root container type')
    }
    applyResult(target, cleaned, true)
  }

  // Loading the matcher also loads logger -> config/index. Patch both merge
  // exports first so that initial layered configuration already uses Kotlin.
  const matchApi = require(path.join(root, 'node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js'))
  const originalDomainMapRegexply = matchApi.domainMapRegexply
  const bindings = new WeakMap()

  matchApi.domainMapRegexply = function (hostMap) {
    // Keep the upstream compiled representation for matchHostnameAll/captures.
    const compiled = originalDomainMapRegexply.apply(this, arguments)
    const keys = Object.keys(hostMap == null ? {} : hostMap)
    const values = keys.map(key => hostMap[key])
    const matcher = helpers.createHostnameMatcher(keys, values.map(value => value != null))
    if (!isObject(compiled) || !matcher || typeof matcher.match !== 'function') {
      throw new TypeError('Invalid hostname compiler result')
    }
    bindings.set(compiled, { matcher, values, entries: hostMap })
    return compiled
  }

  matchApi.matchHostname = function (hostMap, hostname) {
    if (hostMap == null) return null
    const binding = bindings.get(hostMap)
    if (!binding) {
      // Upstream options.js passes {} when a compatibility map is disabled.
      if (isObject(hostMap) && Object.keys(hostMap).every(key =>
        key === 'origin' && isObject(hostMap.origin) && Object.keys(hostMap.origin).length === 0,
      )) return undefined
      throw new TypeError('Hostname map must be created by the installed domainMapRegexply')
    }
    const index = binding.matcher.match(hostname)
    if (index === null) return undefined
    if (!Number.isInteger(index) || index < 0 || index >= binding.values.length) {
      throw new TypeError('Kotlin hostname matcher returned an invalid index')
    }
    return binding.values[index]
  }

  return { bindings }
}

module.exports = { install }
