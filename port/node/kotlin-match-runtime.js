// SPDX-License-Identifier: MPL-2.0
'use strict'

const path = require('node:path')
const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key)
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value)

/**
 * Install after kotlin-helpers.install and before requiring DevSidecar.
 *
 * helpers must export createHostnameAllMatcher(entriesJson), returning an object
 * with match(hostname), and matchPathRule(path, pattern). Both return native JS
 * values, including capture array metadata. No JSON output transport is used.
 *
 * context.bindings optionally supplies the first-wave WeakMap; a binding's
 * entries field is the raw hostMap passed to domainMapRegexply. This is needed
 * only for maps created before this installation. Subsequent maps are recorded
 * here too. The All compiler is lazy because DNS/whitelist maps can contain
 * scalar, array, or even circular values that never reach matchHostnameAll.
 *
 * context.onUse(name), when supplied, runs after each successful Kotlin query
 * (including misses), with 'hostname-all' or 'path-rules'.
 */
function install (coreRoot, helpers, context = {}) {
  for (const name of ['createHostnameAllMatcher', 'matchPathRule']) {
    if (!helpers || typeof helpers[name] !== 'function') {
      throw new TypeError(`Missing Kotlin helper: ${name}`)
    }
  }
  const { bindings, onUse } = context
  if (bindings !== undefined && !(bindings instanceof WeakMap)) {
    throw new TypeError('Match runtime bindings must be a WeakMap')
  }
  if (onUse !== undefined && typeof onUse !== 'function') {
    throw new TypeError('Match runtime onUse must be a function')
  }
  const matchApi = require(path.join(path.resolve(coreRoot),
    'node_modules/@blue-frontier/mitmproxy/src/utils/util.match.js'))
  if (typeof matchApi.domainMapRegexply !== 'function') {
    throw new TypeError('Missing upstream domainMapRegexply')
  }
  const compileMap = matchApi.domainMapRegexply
  const entriesByMap = new WeakMap()
  const allMatchers = new WeakMap()

  matchApi.domainMapRegexply = function (entries) {
    const compiled = compileMap.apply(this, arguments)
    if (!isObject(compiled)) throw new TypeError('Invalid hostname compiler result')
    entriesByMap.set(compiled, entries)
    return compiled
  }

  matchApi.matchHostnameAll = function (hostMap, hostname) {
    if (hostMap == null) return null
    if (!isObject(hostMap)) throw new TypeError('Expected a compiled hostname map')
    if (hostMap.origin == null) return null
    let matcher = allMatchers.get(hostMap)
    if (!matcher) {
      let entries
      if (entriesByMap.has(hostMap)) {
        entries = entriesByMap.get(hostMap)
      } else {
        const binding = bindings && bindings.get(hostMap)
        if (binding && own(binding, 'entries')) {
          entries = binding.entries
        } else if (Object.keys(hostMap).length === 1 && own(hostMap, 'origin') &&
          isObject(hostMap.origin) && Object.keys(hostMap.origin).length === 0) {
          // An empty upstream compiler result needs no raw configuration.
          entries = {}
        } else {
          throw new TypeError('Hostname map has no raw entries; pass context.bindings with entries')
        }
      }
      if (entries != null && !isObject(entries)) {
        throw new TypeError('Hostname All entries must be JSON objects')
      }
      matcher = helpers.createHostnameAllMatcher(JSON.stringify(entries == null ? {} : entries))
      if (!matcher || typeof matcher.match !== 'function') {
        throw new TypeError('Invalid Kotlin hostname All compiler result')
      }
      allMatchers.set(hostMap, matcher)
    }
    const result = matcher.match(hostname)
    if (result !== null && !isObject(result)) {
      throw new TypeError('Kotlin hostname All matcher must return an object or null')
    }
    if (onUse) onUse('hostname-all')
    // Upstream distinguishes a null input map from a well-formed table miss.
    return result === null ? undefined : result
  }

  matchApi.isMatched = function (requestPath, pattern) {
    const result = helpers.matchPathRule(requestPath, pattern)
    if (result !== null && !Array.isArray(result)) {
      throw new TypeError('Kotlin path matcher must return an array or null')
    }
    if (onUse) onUse('path-rules')
    return result
  }
}

module.exports = { install }
