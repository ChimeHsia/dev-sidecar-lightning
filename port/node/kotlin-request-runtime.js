// SPDX-License-Identifier: MPL-2.0
// Runtime bridge for RequestTransform and @blue-frontier/mitmproxy common/util.
'use strict'

const path = require('node:path')
const { sensitiveHeaders } = require('node:http2')
const installed = new WeakMap()
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key)
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value)
class InputValidationError extends TypeError {}

// Packaging must apply every pair exactly once (or recognize the exact already
// patched text), after the independent TLS/close fix. Never patch files at boot.
const UPSTREAM_PATCHES = require('./request-runtime-patches.json')

function copyHeaders(headers, ErrorType = TypeError, http2Metadata = false) {
  if (!isObject(headers) ||
      ![null, Object.prototype].includes(Object.getPrototypeOf(headers)) ||
      Object.getOwnPropertySymbols(headers).some(symbol => !http2Metadata || symbol !== sensitiveHeaders)) {
    throw new ErrorType('Request headers must be a plain string-keyed object')
  }
  const entries = Object.keys(headers).map(name => {
    const descriptor = Object.getOwnPropertyDescriptor(headers, name)
    if (!own(descriptor, 'value')) throw new ErrorType('Header accessors are unsupported')
    const value = descriptor.value
    if (typeof value === 'string') return [name, value]
    if (!Array.isArray(value)) throw new ErrorType('Header values must be strings or string lists')
    const copied = []
    for (let index = 0; index < value.length; index++) {
      const item = Object.getOwnPropertyDescriptor(value, String(index))
      if (!item || !own(item, 'value') || typeof item.value !== 'string') {
        throw new ErrorType('Header lists must contain only strings, without holes or accessors')
      }
      copied.push(item.value)
    }
    return [name, copied]
  })
  return Object.fromEntries(entries)
}

function inputFor(req, ssl) {
  if (!isObject(req) || typeof req.url !== 'string' ||
      typeof req.method !== 'string' || typeof ssl !== 'boolean' ||
      !isObject(req.socket)) {
    throw new InputValidationError('Expected request url, method, boolean ssl, headers and socket')
  }
  // Node's headers map loses duplicate Host lines. Reject ambiguity while the
  // IncomingMessage still exposes rawHeaders; rewritten headers remain valid.
  if (req.rawHeaders !== undefined) {
    if (!Array.isArray(req.rawHeaders) || req.rawHeaders.length % 2) {
      throw new InputValidationError('Invalid rawHeaders')
    }
    for (let index = 0; index < req.rawHeaders.length; index++) {
      if (typeof req.rawHeaders[index] !== 'string') throw new InputValidationError('Invalid rawHeaders')
    }
    let hosts = 0
    for (let index = 0; index < req.rawHeaders.length; index += 2) {
      if (req.rawHeaders[index].toLowerCase() === 'host') hosts++
    }
    if (hosts > 1) throw new InputValidationError('Duplicate Host headers are unsupported')
  }
  // Check representability before JSON.stringify can drop undefined/functions,
  // turn sparse slots into null, or invoke an input toJSON method.
  // Node attaches sensitivity metadata to HTTP/2 headers using a symbol. It is
  // not a wire header and upstream Object.entries deliberately ignores it.
  return {
    url: req.url, ssl, method: req.method,
    headers: copyHeaders(req.headers, InputValidationError, req.httpVersionMajor === 2),
  }
}

function decodeResult(encoded) {
  if (typeof encoded !== 'string') throw new TypeError('Kotlin request transform must return JSON text')
  const result = JSON.parse(encoded)
  if (!isObject(result) || Object.keys(result).length !== 5 ||
      !['http:', 'https:'].includes(result.protocol) ||
      typeof result.hostname !== 'string' || !result.hostname ||
      !Number.isInteger(result.port) || result.port < 1 || result.port > 65535 ||
      typeof result.path !== 'string' || !result.path) {
    throw new TypeError('Invalid Kotlin request transform result')
  }
  result.headers = copyHeaders(result.headers)
  return result
}

function invalidRequest(cause) {
  const error = new TypeError('Invalid request', { cause })
  error.code = 'DSHA_INVALID_REQUEST'
  return error
}

/**
 * Install before starting the proxy. coreRoot contains node_modules; helpers is
 * the compiled Kotlin/JS module. A packaging hook is mandatory: no JS fallback
 * is permitted for direct requests. Both HTTP and upgrade callers use this util.
 *
 * A truthy externalProxy config explicitly retains the entire upstream branch,
 * including callbacks returning null/throwing, Connection headers, tunnel agent
 * caching and HTTP proxy hostname/port/path overrides. Kotlin validates those
 * inputs but its direct-request result is not used for that branch. Upstream
 * interceptors may subsequently rewrite options; they are outside this bridge.
 *
 * onUse('request-transform') runs after successful Kotlin invocation and result
 * validation, before transport/socket effects. It signals invocation, including
 * validation for external proxies, rather than a completed network request.
 * Input validation errors carry DSHA_INVALID_REQUEST for the patched HTTP and
 * upgrade handlers. Helper crashes, result decoding and onUse failures propagate
 * without that tag so programming errors cannot be disguised as client errors.
 */
function install(coreRoot, helpers, { onUse } = {}) {
  const transform = helpers && (helpers.requestTransform || helpers.wave2RequestTransform)
  if (typeof transform !== 'function') throw new TypeError('Missing compiled Kotlin request transform')
  if (onUse !== undefined && typeof onUse !== 'function') throw new TypeError('onUse must be a function')
  const util = require(path.join(path.resolve(coreRoot),
    'node_modules/@blue-frontier/mitmproxy/src/lib/proxy/common/util.js'))
  const previous = installed.get(util)
  if (previous) {
    if (previous.helpers !== helpers || previous.onUse !== onUse ||
        util.getOptionsFromRequest !== previous.wrapper) {
      throw new Error('Kotlin request runtime already installed with a different binding')
    }
    return
  }
  const original = util.getOptionsFromRequest
  if (typeof original !== 'function' || original.kotlinRequestRuntimeVersion !== 1) {
    throw new Error('Missing Kotlin request runtime packaging hook version 1')
  }
  function wrapper(req, ssl, externalProxy = null, serverSetting, compatibleConfig = null) {
    let input
    try {
      input = inputFor(req, ssl)
    } catch (error) {
      if (error instanceof InputValidationError) throw invalidRequest(error)
      throw error
    }
    const encodedInput = JSON.stringify(input)
    let encodedResult
    try {
      encodedResult = transform.call(helpers, encodedInput)
    } catch (error) {
      if (error && error.name === 'IllegalArgumentException') throw invalidRequest(error)
      throw error
    }
    const transformed = decodeResult(encodedResult)
    if (onUse) onUse('request-transform')
    // Keep the actual req (absolute URL, method, socket identity) for upstream.
    // Never overlay fields after upstream, where proxy-specific overrides exist.
    return original.call(this, req, ssl, externalProxy, serverSetting, compatibleConfig,
      externalProxy ? null : transformed)
  }
  wrapper.kotlinRequestRuntimeVersion = 1
  util.getOptionsFromRequest = wrapper
  installed.set(util, { helpers, onUse, wrapper })
}

module.exports = { install, UPSTREAM_PATCHES }
