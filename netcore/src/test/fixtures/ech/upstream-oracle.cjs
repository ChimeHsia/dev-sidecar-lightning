// Offline fixture generator. Never invoked by JVM tests or production code.
// node upstream-oracle.cjs <original-ech-directory> <pinned-cfrg-json> <output-directory>
// Only published CFRG private test keys and fixed, public inputs are used.
'use strict'
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const [reference, vectorFile, output] = process.argv.slice(2)
const referenceHashes = {
  'hpke.js': '8bc1288aea0be20934b36bd6686cae8df13ef0bd0bbb26714e11df94ea1f0c7e',
  'ech.js': 'b58d1392a182ff6f7d497fa4aeb999942313e541c2096930c3fa29f6a628f234',
  'tls13.js': '5bb2f76dcdffa4024f7a72e980b905fd6718adaecb5b8b03cb3a99d7f3391035',
}
for (const [file, expected] of Object.entries(referenceHashes)) {
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(reference, file))).digest('hex'), expected, file)
}
const hpke = require(path.join(reference, 'hpke.js'))
const ech = require(path.join(reference, 'ech.js'))
const raw = fs.readFileSync(vectorFile)
const sourceHash = crypto.createHash('sha256').update(raw).digest('hex')
assert.equal(sourceHash, '61fc662f01996cd06d713dacf5e133167bd309a1f329442d53f1e21a47b3ede6')
const vector = JSON.parse(raw).find(v => v.mode === 0 && v.kem_id === 32 && v.kdf_id === 1 && v.aead_id === 1)
const b = hex => Buffer.from(hex, 'hex')
const context = hpke.setupBaseS(b(vector.pkRm), b(vector.info), {
  privateKey: hpke.x25519PrivateKeyFromRaw(b(vector.skEm)),
  publicKeyRaw: b(vector.pkEm),
})
for (const [field, value] of Object.entries({
  enc: context.enc,
  shared_secret: context.sharedSecret,
  key_schedule_context: context.keyScheduleContext,
  key: context.key,
  base_nonce: context.baseNonce,
})) assert.equal(value.toString('hex'), vector[field], field)
const official = {
  source_sha256: sourceHash,
  mode: 0, kem_id: 32, kdf_id: 1, aead_id: 1,
}
for (const field of ['info', 'ikmR', 'ikmE', 'skRm', 'skEm', 'pkRm', 'pkEm', 'enc',
  'shared_secret', 'key_schedule_context', 'secret', 'key', 'base_nonce']) official[field] = vector[field]
vector.encryptions.forEach((v, i) => {
  assert.equal(context.seal(b(v.aad), b(v.pt)).toString('hex'), v.ct, `seal ${i}`)
  assert.equal(hpke.buildNonce(context.baseNonce, i).toString('hex'), v.nonce, `nonce ${i}`)
  for (const field of ['aad', 'pt', 'ct', 'nonce']) official[`encryption.${i}.${field}`] = v[field]
})
official.encryption_count = vector.encryptions.length
const oracle = {
  hpke_js_sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(reference, 'hpke.js'))).digest('hex'),
  ech_js_sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(reference, 'ech.js'))).digest('hex'),
  tls13_js_sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(reference, 'tls13.js'))).digest('hex'),
  node_version: process.version,
  openssl_version: process.versions.openssl,
  sequence: 0x0102030405,
  aad: Buffer.from('native-ech AAD 2026-11-02').toString('hex'),
  plaintext: Buffer.from('Offline primitive compatibility; no TLS handshake.').toString('hex'),
}
for (const hash of ['sha256', 'sha384']) {
  const size = hpke.hashLength(hash)
  const secret = Buffer.from(Array.from({ length: size }, (_, i) => i))
  const salt = Buffer.from('public test salt')
  const transcript = Buffer.from('public encoded transcript fixture')
  const digest = crypto.createHash(hash).update(transcript).digest()
  const key = hpke.hkdfExpandLabel(hash, secret, 'key', Buffer.alloc(0), hash === 'sha256' ? 16 : 32)
  const iv = hpke.hkdfExpandLabel(hash, secret, 'iv', Buffer.alloc(0), 12)
  Object.assign(oracle, {
    [`${hash}.secret`]: secret.toString('hex'),
    [`${hash}.salt`]: salt.toString('hex'),
    [`${hash}.transcript`]: transcript.toString('hex'),
    [`${hash}.digest`]: digest.toString('hex'),
    [`${hash}.extract`]: hpke.hkdfExtract(hash, salt, secret).toString('hex'),
    [`${hash}.expand`]: hpke.hkdfExpand(hash, secret, transcript, size + 17).toString('hex'),
    [`${hash}.derive`]: hpke.deriveSecret(hash, secret, 'c hs traffic', digest).toString('hex'),
    [`${hash}.key`]: key.toString('hex'),
    [`${hash}.iv`]: iv.toString('hex'),
    [`${hash}.accept_confirmation`]: ech.computeAcceptConfirmation({
      hashName: hash, innerRandom: Buffer.alloc(32, 0x42), transcriptHash: digest,
    }).toString('hex'),
  })
}
for (const [cipher, size] of [['aes-128-gcm', 16], ['aes-256-gcm', 32], ['chacha20-poly1305', 32]]) {
  const key = Buffer.from(Array.from({ length: size }, (_, i) => i))
  const iv = Buffer.from(Array.from({ length: 12 }, (_, i) => 0x80 + i))
  const parameters = { cipher, key, iv, seq: oracle.sequence, aad: b(oracle.aad), plaintext: b(oracle.plaintext) }
  const ct = hpke.aeadSeal(parameters)
  assert.deepEqual(hpke.aeadOpen({ ...parameters, ciphertext: ct }), parameters.plaintext)
  oracle[`${cipher}.key`] = key.toString('hex')
  oracle[`${cipher}.iv`] = iv.toString('hex')
  oracle[`${cipher}.nonce`] = hpke.buildNonce(iv, oracle.sequence).toString('hex')
  oracle[`${cipher}.ct`] = ct.toString('hex')
}
fs.mkdirSync(output, { recursive: true })
for (const [name, values] of [['cfrg-base-x25519-aes128.properties', official], ['upstream.properties', oracle]]) {
  fs.writeFileSync(path.join(output, name), '# Public test inputs; see docs/native-proxy/hpke.md\n' +
    Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n')
}
console.log(`Verified ${vector.encryptions.length} official HPKE encryptions against unchanged upstream; wrote public fixtures.`)
