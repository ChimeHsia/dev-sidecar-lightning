// SPDX-License-Identifier: MPL-2.0
'use strict'

function install (coreRoot, helpers, matchContext = {}) {
  const invoked = new Set()
  const onUse = name => {
    if (invoked.has(name)) return
    invoked.add(name)
    // One event per module proves dispatch without logging hosts or request data.
    console.log(`[android] Kotlin runtime invoked: ${name}`)
  }
  require('./kotlin-match-runtime').install(coreRoot, helpers, { ...matchContext, onUse })
  require('./kotlin-choice-runtime').install(coreRoot, helpers, {
    onUse: () => onUse('dynamic-choice'),
  })
  require('./kotlin-request-runtime').install(coreRoot, helpers, {
    onUse: () => onUse('request-transform'),
  })
}

module.exports = { install }
