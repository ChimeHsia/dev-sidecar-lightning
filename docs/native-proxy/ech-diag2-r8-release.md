# Version-2 R8 release probe handoff

Built November 2, 2026 in the isolated `native-ech-android` worktree with
explicit JDK 21.0.2, offline Gradle 9.8.2, one worker and a 256 MB heap.
The actual `minifyReleaseWithR8` task completed successfully. Gradle has exited.
No ADB, installation or device execution was performed.

Artifact:

```text
android-ech-probe/artifacts/native-ech-probe-v2-r8-release.apk
Bytes: 3035833
SHA-256:
c23945c327c52add1035675d78c9db317f360845df54ab3f4a310383d76218e3
```

Actual manifest inspection:

- Package `com.dsha.nativeechtest`.
- versionCode 2, versionName `0.2-diagnostic`, minSdk 24, targetSdk 37.
- arm64-v8a only, INTERNET only, testOnly=true, allowBackup=false.
- Same-package Activity and instrumentation runner; no browser package.

APK v2 signature verification passed. Signer:
`CN=Android Debug, O=Android, C=US`, RSA 2048.
Signer certificate SHA-256:
`d8755927514c111bf255341f3f8171d6edc61c89be0ac4f3c45fe7909e36950b`.

## Actual 16K package and ELF inspection

`zipalign -c -P 16 -v 4` returned zero and `Verification successful`.
The sole native ZIP entry is
`lib/arm64-v8a/libconscrypt_jni.so`, 2786872 bytes, stored uncompressed.
Its data offset is 229376, exactly 14 * 16384.

`llvm-readelf -lW` was run against that library extracted from this APK,
not against a source-tree or external substitute:

| LOAD file offset | Virtual address | p_align |
| --- | --- | --- |
| `0x000000` | `0x000000` | `0x4000` |
| `0x28dc00` | `0x291c00` | `0x4000` |
| `0x2a58f8` | `0x2ad8f8` | `0x4000` |

All three LOAD segment alignments are 16384, and file offsets/virtual addresses
are congruent modulo 16384. This proves the inspected ZIP/ELF alignment
properties; it does not prove runtime ECH behavior or complete device support.

## Actual R8 DEX checks

Disassembled `classes.dex` from this APK with Android build-tools `dexdump -d`.
Checks went beyond searching the mapping or descriptor references:

- Actual class definitions for NativeEchPolicyAdapter, RequiredTrustManager
  and RequiredTrustManager$1 exist.
- RequiredTrustManager defines the exact public `getNetworkSecurityPolicy`.
  R8 mapping also preserves the original class and method names.
- The policy callback's actual instructions load
  `DomainEncryptionMode.REQUIRED` with `sget-object` and return it.
- Actual preflight instructions invoke `getEchOptions`, `isFailClosed` and
  `getDomainEncryptionMode`.
- Diagnostic const-string instructions retain `diag2-explicit-tmf`,
  `causeClasses`, `causeChainTruncated`, `nativeAvailable`, `configValidated`,
  `TRUST_MANAGER_CREATION` and `REQUIRED_POLICY_PREFLIGHT`.
- There is no embedded JVM DLL/JAR native payload.

Source-level diagnostics remain metadata-only: static stages and at most
eight cause class names, without messages, stack traces, cert/config bytes,
private keys or account URLs. The unchanged source revision previously passed
56 JVM tests and Android SDK/AAR API compilation; this task made no source
change to that revision.

R8 mapping SHA-256:
`1f5e4a6aad13b9ddaa3d37c54f7e222b3b9092d759d036009cb7197d657cd437`.
Effective R8 configuration SHA-256:
`f56a10066ee1732e7f69b0d66cd0c5f98e176633c152cde3f447948c6f71e1f0`.

Separate machine-readable release audit: `ech-diag2-r8-release.json`.
Raw signature, manifest, alignment, mapping/configuration and targeted DEX
excerpts are preserved only under ignored
`.tools/artifact-audits/diag2-r8-release/`.

## Existing debug packet was not rewritten

The stable version-2 debug artifact remains:

```text
android-ech-probe/artifacts/native-ech-probe-v2-debug.apk
d32ff875e357f1076a113cc5106d8a6baeb9fef98f22ecca3def2d9bdc462da0
```

Before and after this build, SHA-256, size and UTC modification timestamps
matched for both debug APK copies and the existing
`android-ech-apks.json` / `ech-diag2-source.sha256` provenance files.
This release uses its own distinct artifact name, audit and handoff document.
No earlier debug provenance was edited.

## Device gate

Terra should first test version-2 debug against the independent Go observer.
Only a real accepted result authorizes proceeding to this corresponding R8
device path under parent scheduling. The TrustManager-service explanation
remains a device-unconfirmed hypothesis; earlier Android attempts failed.
No Android repair, successful device ECH, or completed Node removal is claimed.
