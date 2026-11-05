'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const UpstreamCandidates = require('../node/upstream-candidates')

test('candidate budgets are distinct-IP based, capped and isolated to one request', () => {
  const candidates = new UpstreamCandidates()
  const otherRequest = new UpstreamCandidates()
  const backupList = ['192.0.2.1', '192.0.2.1', '192.0.2.2', '192.0.2.3', 'origin.test']
  const dns = { cache: new Map([['origin.test', { backupList }]]) }
  candidates.fail({ ip: '192.0.2.1', hostname: 'origin.test', dns })
  assert.equal(candidates.retryLimit, 2)
  assert.equal(candidates.allows('192.0.2.1'), false)
  assert.equal(otherRequest.allows('192.0.2.1'), true)
  backupList.push('192.0.2.4', '192.0.2.5')
  candidates.fail({ ip: '192.0.2.2', hostname: 'origin.test', dns })
  assert.equal(candidates.retryLimit, 3)
  assert.equal(otherRequest.retryLimit, 1)
  candidates.fail({})
  assert.equal(candidates.failed.has(undefined), false)
})

test('stale alive and probing results cannot revive a failed request candidate', () => {
  const candidates = new UpstreamCandidates()
  const first = { host: '192.0.2.1', status: 'success' }
  const next = { host: '192.0.2.2', status: 'success' }
  const tester = {
    alive: [first, next], backupList: [first, next],
    pickFastAliveIpObj: () => first,
    pickNextForProbing: () => first,
  }
  candidates.fail({ ip: first.host, tester })
  assert.equal(candidates.pickAlive(tester), next)
  assert.equal(candidates.pickProbe(tester), next)
  candidates.fail({ ip: next.host, tester })
  assert.equal(candidates.pickAlive(tester), undefined)
  assert.equal(candidates.pickProbe(tester), undefined)
})

test('a real DynamicChoice removes selected IPs from backups without reducing the retry budget', () => {
  const candidates = new UpstreamCandidates()
  const cached = { value: '192.0.2.1', backupList: ['192.0.2.2', '192.0.2.3', 'origin.test'] }
  const dns = { cache: new Map([['origin.test', cached]]) }
  candidates.fail({ ip: cached.value, hostname: 'origin.test', dns })
  assert.equal(candidates.retryLimit, 2)
  cached.value = cached.backupList.shift()
  candidates.fail({ ip: cached.value, hostname: 'origin.test', dns })
  assert.equal(candidates.retryLimit, 2)
  assert.equal(candidates.allows(cached.backupList[0]), true)
})

test('both Node lookup shapes filter failed IPs and preserve errors and families', () => {
  const candidates = new UpstreamCandidates()
  candidates.fail({ ip: '192.0.2.1' })
  const results = []
  const complete = candidates.wrapLookup((...args) => results.push(args))
  complete(null, '192.0.2.2', 4)
  complete(null, [
    { address: '192.0.2.1', family: 4 },
    { address: '2001:db8::1', family: 6 },
  ])
  complete(null, '192.0.2.1', 4)
  complete(null, [{ address: '192.0.2.1', family: 4 }])
  const original = Object.assign(new Error('resolver failed'), { code: 'EAI_AGAIN' })
  complete(original)
  assert.deepEqual(results.slice(0, 2), [
    [null, '192.0.2.2', 4],
    [null, [{ address: '2001:db8::1', family: 6 }]],
  ])
  assert.equal(results[2][0].code, 'EHOSTUNREACH')
  assert.equal(results[3][0].code, 'EHOSTUNREACH')
  assert.equal(results[4][0], original)
})
