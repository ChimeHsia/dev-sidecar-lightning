'use strict'

const path = require('node:path')

function waitForListening(server, timeoutMillis, signal) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer)
      server.removeListener('listening', ready)
      server.removeListener('error', failed)
      server.removeListener('close', closed)
      signal.removeEventListener('abort', cancelled)
    }
    const ready = () => { cleanup(); resolve() }
    const failed = error => { cleanup(); reject(error) }
    const closed = () => failed(new Error('Proxy closed before listening'))
    const cancelled = () => failed(new Error('Proxy startup cancelled'))
    const timer = setTimeout(() => failed(new Error('Proxy listen timed out')), timeoutMillis)
    server.once('listening', ready)
    server.once('error', failed)
    server.once('close', closed)
    signal.addEventListener('abort', cancelled, { once: true })
    if (server.listening) ready()
  })
}

async function startProxy(serverApi, proxyApi, config, { timeoutMillis = 15000 } = {}) {
  const createProxy = proxyApi.createProxy
  let servers
  let listening
  const abort = new AbortController()
  proxyApi.createProxy = function (...args) {
    if (servers) throw new Error('Multiple proxy creations during startup')
    servers = createProxy.apply(this, args)
    if (!Array.isArray(servers) || servers.length === 0) {
      throw new Error('Core created no proxy listeners')
    }
    listening = Promise.all(servers.map(server => waitForListening(server, timeoutMillis, abort.signal)))
    // The upstream async start resolves before listen; consume early errors
    // until both its initialization and the real bind operation can be awaited.
    listening.catch(() => {})
    return servers
  }
  try {
    await serverApi.start(config)
    if (!listening) throw new Error('Core did not create a proxy listener')
    await listening
    return servers
  } catch (error) {
    abort.abort()
    for (const server of servers || []) server.closeAllConnections?.()
    await Promise.resolve().then(() => serverApi.close()).catch(() => {})
    throw error
  } finally {
    abort.abort()
    proxyApi.createProxy = createProxy
  }
}

function start(coreRoot, config) {
  const root = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy')
  const serverApi = require(root)
  const proxyApi = require(path.join(root, 'src/lib/proxy'))
  return startProxy(serverApi, proxyApi, config)
}

module.exports = { start, startProxy }
