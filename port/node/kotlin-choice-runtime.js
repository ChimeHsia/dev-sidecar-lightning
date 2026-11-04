// SPDX-License-Identifier: MPL-2.0
'use strict'

const path = require('node:path')

const installations = new WeakMap()
const methods = ['setBackupList', 'countStart', 'doCount', 'doRank', 'resetChoice']

/**
 * Install before creating choices. coreRoot contains node_modules; helpers is
 * the compiled Kotlin/JS root export with a stateful createDynamicChoice(key).
 * onUse() runs once, after a successful Kotlin operation has been published.
 * Reinstalling with the same helpers is harmless and retains the first hook.
 */
function install (coreRoot, helpers, { onUse } = {}) {
  if (!helpers || typeof helpers.createDynamicChoice !== 'function') {
    throw new TypeError('Missing Kotlin helper: createDynamicChoice')
  }
  if (onUse !== undefined && typeof onUse !== 'function') {
    throw new TypeError('onUse must be a function')
  }
  const api = require(path.join(
    path.resolve(coreRoot), 'node_modules/@blue-frontier/mitmproxy/src/lib/choice/index.js',
  ))
  const prototype = api.DynamicChoice.prototype
  const previous = installations.get(prototype)
  if (previous) {
    if (previous.helpers !== helpers) {
      throw new Error('DynamicChoice runtime is already installed with different Kotlin helpers')
    }
    return api
  }
  for (const method of methods) {
    if (typeof prototype[method] !== 'function') {
      throw new TypeError(`Unsupported upstream DynamicChoice: ${method}`)
    }
  }

  const states = new WeakMap()
  let used = false

  function stateFor (choice) {
    let state = states.get(choice)
    if (!state) {
      // Adopting historical JS counters would silently lose feedback. Startup
      // installation also covers empty RequestCounter singletons loaded early.
      if (choice.value !== null || choice.backupList.length || Object.keys(choice.countMap).length) {
        throw new Error('Install Kotlin DynamicChoice before initializing choices')
      }
      state = helpers.createDynamicChoice(choice.key == null ? '' : String(choice.key))
      for (const method of [...methods, 'snapshot']) {
        if (!state || typeof state[method] !== 'function') {
          throw new TypeError(`Invalid Kotlin DynamicChoice runtime: ${method}`)
        }
      }
      states.set(choice, state)
    }
    return state
  }

  function publish (choice, state, replaceBackups) {
    const snapshot = state.snapshot()
    choice.value = snapshot.value
    if (replaceBackups) {
      choice.backupList = [...snapshot.backupList]
    } else {
      choice.backupList.length = 0
      for (const candidate of snapshot.backupList) choice.backupList.push(candidate)
    }
    // Preserve the original plain countMap and retained counter references.
    for (const candidate of Object.keys(choice.countMap)) {
      if (!Object.hasOwn(snapshot.countMap, candidate)) delete choice.countMap[candidate]
    }
    for (const candidate of Object.keys(snapshot.countMap)) {
      const record = choice.countMap[candidate] || (choice.countMap[candidate] = {})
      Object.assign(record, snapshot.countMap[candidate])
    }
    if (!used) {
      used = true
      if (onUse) onUse()
    }
  }

  // Both ChoiceCache.getOrCreate's lexical constructor and already-loaded DNS
  // IpCache subclasses use this prototype. Replacing exports misses the former.
  prototype.setBackupList = function (candidates) {
    const state = stateFor(this)
    state.setBackupList(candidates)
    publish(this, state, true)
  }
  prototype.countStart = function (candidate) {
    const state = stateFor(this)
    state.countStart(candidate)
    publish(this, state, false)
  }
  prototype.doCount = function (candidate, isError) {
    const state = stateFor(this)
    state.doCount(candidate, Boolean(isError))
    publish(this, state, false)
  }
  prototype.doRank = function () {
    const state = stateFor(this)
    state.doRank()
    publish(this, state, true)
  }
  prototype.resetChoice = function (hostname) {
    const state = stateFor(this)
    const reset = state.resetChoice(hostname)
    publish(this, state, reset)
    return reset
  }
  installations.set(prototype, { helpers })
  return api
}

module.exports = { install }
