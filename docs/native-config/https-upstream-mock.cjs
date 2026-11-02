'use strict'

// Local-only mock of the request 2.88.2 GET options and redirect defaults used by
// config-api.js. This is not a call to its private requestRemoteConfig function.
const assert = require('node:assert/strict')
const http = require('node:http')
const requestModule = process.argv[2]
if (!requestModule) throw new Error('Pass an absolute path to the upstream request package')
const request = require(requestModule)
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const close = server => new Promise(resolve => server.close(resolve))
const get = url => new Promise((resolve, reject) => request(url, {
  proxy: null,
  timeout: 30000,
  agent: new http.Agent({ keepAlive: false }),
  headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache', 'User-Agent': 'synthetic-test' },
}, (error, response, body) => error ? reject(error) : resolve({ status: response.statusCode, body })))

async function main () {
  let originHits = 0
  let destinationHits = 0
  const destination = http.createServer((req, res) => {
    destinationHits++
    assert.equal(req.headers['cache-control'], 'no-cache')
    res.end('destination')
  })
  await listen(destination)
  const origin = http.createServer((req, res) => {
    originHits++
    if (req.url === '/same') {
      res.writeHead(302, { Location: '/ok' })
      res.end()
    } else if (req.url === '/cross-port') {
      res.writeHead(302, { Location: `http://127.0.0.1:${destination.address().port}/ok` })
      res.end()
    } else if (req.url === '/not-found') {
      res.writeHead(404)
      res.end('synthetic error')
    } else {
      res.end('origin')
    }
  })
  await listen(origin)
  try {
    const base = `http://127.0.0.1:${origin.address().port}`
    assert.deepEqual(await get(`${base}/same`), { status: 200, body: 'origin' })
    assert.deepEqual(await get(`${base}/cross-port`), { status: 200, body: 'destination' })
    assert.deepEqual(await get(`${base}/not-found`), { status: 404, body: 'synthetic error' })
    assert.equal(originHits, 4)
    assert.equal(destinationHits, 1)
    console.log('Synthetic request 2.88.2 oracle: 3/3; same-origin and cross-port redirects followed, HTTP 404 surfaced')
  } finally {
    await close(origin)
    await close(destination)
  }
}

main().catch(error => {
  console.error(error)
  process.exitCode = 1
})
