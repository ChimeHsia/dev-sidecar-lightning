# Native HPKE / TLS crypto prerequisite candidate

Validated November 2, 2026, in managed worktree `native-ech-hpke`, based on
`754ff423b59c743cf94e68b3417c1fde4a6f42b7`.
This is an isolated primitive candidate for a later native ECH engine.
There is no production wiring, TLS handshake, socket, certificate verifier,
ECHConfig parser, ClientHello encoding, or record layer here. Configured-domain
Android ECH behavior still depends on the existing runtime. These files do not
complete Node removal or authorize switching that behavior off.

## API and implementation

Sources are under `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/`.

| API | Contract |
| --- | --- |
| `Hpke.setupBaseSender(pkR, info)` | Fresh BC X25519 ephemeral key generated using `SecureRandom`; returns a unidirectional `Sender`. |
| `Sender.enc` | Detached 32-byte encapsulated public key. No private material is exposed. |
| `Sender.seal(aad, plaintext)` | AES-128-GCM ciphertext followed by a 16-byte tag; synchronized with sealing and closing on the same context. |
| `Hpke.setupBaseRecipient(enc, skR, info)` | Companion recipient for testing and later byte-oriented use; caller supplies a raw 32-byte private key. |
| `Recipient.open(aad, ciphertext)` | Authenticates before returning plaintext. Authentication errors do not advance sequence. |
| `Sender/Recipient.sequenceNumber` | Read-only next sequence number. |
| `Sender/Recipient.close()` | Idempotent; rejects further operations and clears retained key/nonce arrays. |
| `Hpke.labeledExtract/labeledExpand` | RFC 9180 fixed-suite labels, with separate `Domain.KEM` and `Domain.HPKE`. |
| `NativeTlsCrypto.hkdfExtract/hkdfExpand` | RFC 5869; SHA-256 or SHA-384. |
| `hkdfExpandLabel` | RFC 8446 label encoding; checks ASCII and uint8/uint16 lengths. |
| `deriveSecret` | Hashes the supplied encoded handshake messages, then expands. |
| `deriveSecretFromHash` | Explicit prehashed variant matching upstream `deriveSecret`; checks digest length. |
| `aeadSeal/aeadOpen` | Stateless AES-128-GCM, AES-256-GCM or ChaCha20-Poly1305; 12-byte nonce and full 16-byte tag only. |
| `buildNonce` | Detached base IV XOR left-zero-padded nonnegative `Long` sequence. |

The only HPKE profile is mode 0, DHKEM(X25519, HKDF-SHA256) `0x0020`,
HKDF-SHA256 `0x0001`, AES-128-GCM `0x0001`. There is no algorithm negotiation,
PSK, authenticated HPKE, other KEM/KDF/AEAD profile, exporter, or public
deterministic-key API. The internal `setupBaseSenderForTesting` hook accepts the
published ephemeral private key and an initial sequence for boundary tests;
Kotlin `internal` is module visibility, not a JVM security boundary.
Key derivation from HPKE IKM seeds is not implemented: fixtures use published
`skEm` and `skRm` directly.

`bcprov-jdk18on:1.79` is the only added dependency, in `netcore/build.gradle.kts`.
BC lightweight X25519, HKDFBytesGenerator, SHA256Digest/SHA384Digest,
GCMBlockCipher/AESEngine, and ChaCha20Poly1305 perform the cryptographic
primitives. No hand-written curve, HMAC, HKDF block loop, AES, Poly1305 or
ChaCha implementation is present. Provider registration and device JCA
algorithm discovery are not required.

32-byte X25519 public-key length is checked before use. BC performs RFC 7748
decoding, including high-bit masking and noncanonical coordinates; its
`calculateAgreement` rejects the all-zero shared secret. Sender and recipient
both reject that result. Key and setup inputs are copied; outputs do not share
the context's retained arrays. Inputs must not be concurrently mutated during a
call. No keys, plaintexts, or generated private material are logged. Clearing
retained/temporary arrays is best effort: JVM/BC internal copies and garbage
collection prevent a complete memory-erasure guarantee.

## Sequence and lifecycle limits

The supported HPKE sequence range is `[0, Long.MAX_VALUE)`, a conservative
subset of RFC 9180's 96-bit nonce space. Sealing at `Long.MAX_VALUE` fails before
emitting ciphertext; counters never wrap or reset. The sender reserves sequence
before entering AEAD, and closes on any encryption failure to prevent retrying
that nonce. Its monitor also covers close, so concurrent callers cannot seal
with the same sequence in a context. A failed recipient authentication returns
no plaintext and retains the sequence, matching RFC 9180.

The context has no clone, serialization, restore, rewind, or rekey operation.
Callers must preserve ciphertext order themselves if they call seal concurrently.
The signed-Long bound is an overflow guard, not an AEAD security budget. A future
engine must impose protocol/application message and byte limits, TLS record
limits, authentication-failure limits, and key-update policy. Stateless AEAD
helpers cannot prevent a caller from reusing a key/nonce. They must not be treated
as a complete TLS record writer.

## Primary specifications and exact fixtures

The implementation was checked against:

- RFC 9180 sections 4, 5.1.1, 5.2, 7.1.1, 7.1.4 and appendix A.1:
  <https://www.rfc-editor.org/rfc/rfc9180.html>.
- RFC 5869 sections 2.2/2.3 and appendix A.1-A.3:
  <https://www.rfc-editor.org/rfc/rfc5869.html>.
- RFC 8446 sections 5.3, 7.1 and 7.3:
  <https://www.rfc-editor.org/rfc/rfc8446.html>.
- RFC 8448 section 3 client handshake traffic-secret derivation, key and IV:
  <https://www.rfc-editor.org/rfc/rfc8448.html>.
- RFC 8439 section 2.8.2 ChaCha20-Poly1305 AEAD known answer:
  <https://www.rfc-editor.org/rfc/rfc8439.html>.
- BC 1.79 implementation, for example
  <https://github.com/bcgit/bc-java/blob/r1rv79/core/src/main/java/org/bouncycastle/crypto/generators/HKDFBytesGenerator.java>.

CFRG official published vectors are pinned to the exact revision linked by
RFC 9180's `TestVectors` reference:

```text
https://raw.githubusercontent.com/cfrg/draft-irtf-cfrg-hpke/5f503c564da00b0687b3de75f1dfbdfc4079ad31/test-vectors.json
SHA256 61fc662f01996cd06d713dacf5e133167bd309a1f329442d53f1e21a47b3ede6
selector mode=0, kem_id=32, kdf_id=1, aead_id=1
info 4f6465206f6e2061204772656369616e2055726e
pkRm 3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d
enc 37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431
```

The exact selected inputs, including the **public test-vector private keys**,
schedule outputs, and every `aad/pt/ct/nonce` for sequences 0 through 256, are
retained in `netcore/src/test/resources/ech/cfrg-base-x25519-aes128.properties`.
The whole downloaded multi-profile file is not needed at test runtime.

| Stable fixture | SHA-256 |
| --- | --- |
| `cfrg-base-x25519-aes128.properties` | `61ec8a1baaf0f527995fa6dfac83f6792d3c5d0abffcf60c171e69c9009647c0` |
| `upstream.properties` | `19060d646f98c9b3758b823048e1e169d3c53de4a8804452fa6e814489ebe511` |
| Cached `bcprov-jdk18on-1.79.jar` | `0d81ecc3124536b539bce9aa3fe9621b7f84c9cee371b635a5b31c78b79ab1da` |

The original checkout's read-only reference directory was:

```text
D:\File\User\code\dev-sidecar-lightning\port\.run\offline-proxy-fixes-20261101\runtime\core\node_modules\@blue-frontier\mitmproxy\src\lib\proxy\common\ech
hpke.js SHA256 8bc1288aea0be20934b36bd6686cae8df13ef0bd0bbb26714e11df94ea1f0c7e
ech.js  SHA256 b58d1392a182ff6f7d497fa4aeb999942313e541c2096930c3fa29f6a628f234
tls13.js SHA256 5bb2f76dcdffa4024f7a72e980b905fd6718adaecb5b8b03cb3a99d7f3391035
```

`netcore/src/test/fixtures/ech/upstream-oracle.cjs` checks all reference hashes
before requiring the unchanged `hpke.js` and `ech.js`. With Node v24.15.0 and
OpenSSL 3.5.5, it verified all 257 CFRG ciphertexts and HPKE intermediate fields.
The script also generated the exact deterministic TLS-helper expectations in
`upstream.properties`: SHA-256/SHA-384 secrets are byte ramps starting at zero,
salt is ASCII `public test salt`, messages are ASCII
`public encoded transcript fixture`, and labels include `key`, `iv`,
`c hs traffic`, and `ech accept confirmation`. The acceptance-confirmation
helper uses a fixed 32-byte `0x42` inner random.

All three AEAD oracle cases use key bytes `00..0f` or `00..1f`, IV bytes
`80..8b`, sequence `4328719365` (`0x0102030405`), AAD ASCII
`native-ech AAD 2026-11-02`, and plaintext ASCII
`Offline primitive compatibility; no TLS handshake.`. Inputs and complete
outputs are retained in the fixture. The oracle exercises the actual exported
crypto helpers shared with `tls13.js`; it does not run `Tls13Session`.
No upstream handshake or certificate behavior is claimed to be ported or
validated. Node is used only during this optional, offline fixture-generation
step; no production primitive or JVM test invokes it.

## Verification and reproduction

On JDK 21.0.2, direct Kotlin compiler 2.0.21 targeting JVM 17:
**23 JUnit tests pass: 11 HPKE tests and 12 TLS primitive tests**.

Coverage includes 257 official seals and 257 official opens, labeled key
schedule outputs, RFC 5869 cases 1-3, RFC 8448 secret/key/IV, RFC 8439 AEAD,
both upstream hash profiles, all three upstream AEAD outputs, low-order
X25519 keys, bad key sizes, ciphertext/tag/AAD/key/nonce tampering, truncation,
replay/out-of-order sequence mismatch, input/output detachment, close behavior,
empty plaintext, HKDF/label bounds, high sequence bits, and overflow refusal.
The 512-call concurrent sealing test compares the entire ciphertext set with
sequences 0..511; a separate 32-call race at exhaustion permits exactly one
last seal.

Run from this worktree with the existing JDK and cached jars:

```powershell
.\netcore\src\test\fixtures\ech\direct-check.ps1 `
    -JavaHome 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2'
```

The script compiles only these two production files and their three Kotlin test
files. It uses compiler `-Xmx256m` and JUnit JVM `-Xmx128m`, writes output beneath
this worktree's ignored `netcore/build/native-ech-hpke-check/`, and reads only
cached dependencies. Those are heap caps, not total process RSS caps.
The JVM tests have no `java.net`, external network, Node, or subprocess
dependency. No Gradle build, Android build, or ADB command was run.

For offline oracle regeneration, supply the already downloaded pinned CFRG file:

```powershell
node .\netcore\src\test\fixtures\ech\upstream-oracle.cjs `
    '<original reference ech directory>' `
    '<local pinned test-vectors.json>' `
    '.\netcore\src\test\resources\ech'
```

## Android acceptance still required

Both Android builds at this base declare minSdk 24. These primitives avoid
platform XDH/X25519 APIs introduced on later Android versions, JDK HPKE, and
platform ChaCha20 provider availability by calling BC lightweight operations
directly. The dependency is BC's Java-8-and-later artifact; netcore retains its
existing JVM 17 target. This is a compatibility design choice, not a device
compatibility result.

No APK packaging, D8/R8/desugaring, Android class loading, provider/package
coexistence, device entropy, runtime performance, or API 24 device execution
has been tested. Later integration must check those on supported devices,
including existing BC dependencies, shrinking rules and app size. A passing
JDK 21 suite is not Android or ECH handshake proof. Any future migration must
retain configured-domain ECH until a separately validated engine implements
the handshake and its verification/retry/acceptance behavior.
