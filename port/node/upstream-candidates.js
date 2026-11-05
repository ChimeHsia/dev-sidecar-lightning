'use strict'

const net = require('node:net')

// One instance per client request. TCP speed tests may complete after a real
// TLS/request failure, so their results must not erase this request's history.
class UpstreamCandidates {
  constructor () {
    this.failed = new Set()
    this.retryLimit = 1
  }

  allows (ip) {
    return !this.failed.has(ip)
  }

  fail ({ ip, hostname, dns, tester }, error) {
    if (!net.isIP(ip || '')) return
    this.failed.add(ip)
    if (error) this.lastError = error
    const cached = dns && dns.cache && dns.cache.get(hostname)
    const addresses = new Set([
      ...this.failed,
      cached && cached.value,
      ...((cached && cached.backupList) || []),
      ...((tester && tester.backupList) || []).map(item => item.host),
    ].filter(address => typeof address === 'string' && net.isIP(address)))
    // Cover small preset pools (GitHub has three addresses) without making
    // unavailable sites retry an unbounded list of DNS answers.
    this.retryLimit = Math.max(this.retryLimit, Math.min(3, addresses.size - 1))
  }

  pickAlive (tester) {
    const preferred = tester.pickFastAliveIpObj()
    return [preferred, ...(tester.alive || [])].find(item =>
      item && item.status !== 'failed' && this.allows(item.host))
  }

  pickProbe (tester) {
    const preferred = tester.pickNextForProbing()
    const selected = [preferred, ...(tester.backupList || [])].find(item =>
      item && item.status !== 'failed' && this.allows(item.host))
    if (selected) selected._probing = true
    return selected
  }

  wrapLookup (callback) {
    return (error, address, family) => {
      if (error) return callback(error)
      if (Array.isArray(address)) {
        const remaining = address.filter(item => this.allows(item.address))
        if (remaining.length) return callback(null, remaining)
      } else if (this.allows(address)) {
        return callback(null, address, family)
      }
      // A DNS implementation or an asynchronous retest may still return the
      // previous address. Fail locally rather than opening that socket again.
      const exhausted = new Error(
        this.lastError ? this.lastError.message : 'No untried upstream address remains',
        { cause: this.lastError },
      )
      exhausted.code = this.lastError && this.lastError.code || 'EHOSTUNREACH'
      exhausted.status = this.lastError && this.lastError.status
      exhausted.upstreamCandidatesExhausted = true
      callback(exhausted)
    }
  }
}

module.exports = UpstreamCandidates
