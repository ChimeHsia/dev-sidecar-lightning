# Terra handoff: independent ECH APKs

**Android execution BLOCKED (Terra report, November 2): both version-1 APKs
failed accepted and reject cases in about 8-9 ms with HANDSHAKE; Go observed
EOF and zero application bytes. No Android ECH acceptance has been proved.**
The version-1 hashes below identify historical failed builds, not a usable Android
route. After fresh BUILD_GO, the version-2 diagnostics packet built successfully
and now awaits parent PHONE_GO. No claim of Android repair is made.
See `../docs/native-proxy/ech-android-diagnostics.md`.

**Current diagnostic packet:** `artifacts/native-ech-probe-v2-debug.apk`,
versionCode 2 / `0.2-diagnostic`, 4051807 bytes, SHA-256
`d32ff875e357f1076a113cc5106d8a6baeb9fef98f22ecca3def2d9bdc462da0`.
Package/minSdk/ABI/signer are unchanged. Gradle is finished; no v2 device run.
Expect JSON `schema=2`, `probeRevision=diag2-explicit-tmf`, static `stage`,
and at most eight cause-class entries. Use this distinct filename for handoff.

November 2, 2026. **Both probe Gradle builds have finished. Build slot released
for browser payload10. No further Gradle build is scheduled here.**
Phone testing is NOT authorized by this file: wait for parent PHONE_GO after
candidate10 acceptance. This worker has not invoked ADB or touched a phone.

Workspace:
`C:\Users\HONOR\.codex\worktrees\native-ech-android\dev-sidecar-lightning`
(base `befef499589d20cb1fd6aa7fe293d009524b7240`).

## Verified artifacts

| Variant | APK relative to workspace | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Historical v1 debug | Original build output replaced by v2; identify old packet by hash | 4051795 | `80292aa9c608ee9d27a61233e3269473f497c6a956b39c88afc5ba2b261c7410` |
| R8 release | `android-ech-probe/app/build/outputs/apk/release/app-release.apk` | 3019441 | `d3862b3a8f4f2a8651c252aacb9d55419ae614ab3596b027211e96c140d57b54` |

Both: package `com.dsha.nativeechtest`, versionCode 1,
versionName `0.1-platform-proof`, **minSdk 24**, targetSdk 37, arm64-v8a only.
Actual manifest is test-only with backup disabled; the sole permission is
INTERNET. They never target the browser package or `top.panming`.

APK v2 signature verification passed for both. Signer:
`CN=Android Debug, O=Android, C=US`, RSA 2048, certificate SHA-256:

```text
d8755927514c111bf255341f3f8171d6edc61c89be0ac4f3c45fe7909e36950b
```

Actual ZIP native entry: `lib/arm64-v8a/libconscrypt_jni.so` (2786872 bytes).
There are no JVM DLL/JAR payloads in either APK. Conscrypt runtime is exclusively
the official Android 2.8.0 AAR, SHA-256
`752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1`.
Only the three accepted core source files are staged alongside the tiny probe;
the MITM engine, BC, Go and Node are absent from the APK.

The release APK was actually minified with R8. Its configuration, mapping and
DEX were checked to retain both
`org.conscrypt.NativeEchPolicyAdapter$RequiredTrustManager` and the exact
`getNetworkSecurityPolicy()` method. `netcore/consumer-rules.pro` is explicitly
included. This confirms packaging preservation, not Android runtime execution.

Builds used explicit JDK 21.0.2, cached AGP 9.5.0/Kotlin 2.4.21, offline Gradle
9.8.2, one worker, in-process compilation and **256 MB Gradle heap**. Memory was
not raised. Initial offline-module/source-directory configuration failures were
fixed within this isolated project; final debug and release builds succeeded.

## Host proof completed before APK handoff

**48 JVM tests pass** (28 unchanged accepted Conscrypt tests plus 20 new
composite dialer/proxy tests), compiler 256 MB and JUnit 128 MB.

- Verified browser-like CONNECT client validates the generated browser.test
  MITM leaf, sends HTTPS through NativeProxyServer, and reaches independent Go
  crypto/tls origin through the new composite Conscrypt dialer.
- Go independently reports ECH accepted, observes `inner.example` and the exact
  HTTP request target. The client checks exact status and response body.
- Rejected ECH/no-ECH peer, bad certificate, ciphertext/payload tampering,
  handshake timeout and engine stop produce **zero origin application bytes**.
- Explicit non-ECH CHAIN_ONLY and UNVERIFIED routes delegate unchanged to the
  ordinary dialer; required ECH rejects both modes before any ClientHello.
- A separate host test performs the Android probe's exact HTTP request and
  reads TLS to clean EOF, exercising the Go peer's close_notify.

Evidence:
`docs/native-proxy/ech-dialer-loopback-2026-11-02.txt`.
Public API, input snapshot hashes and remaining integration gates:
`docs/native-proxy/ech-android.md`.

## Device safety guard and installation

**Terra only, after PHONE_GO.** Select the device explicitly. First run this
read-only guard, which also detects retained/uninstalled package records:

```powershell
.\android-ech-probe\terra-install-guard.ps1 -Serial DEVICE_SERIAL
```

The corrected guard checks native process exit status, separate stdout/stderr,
`get-current-user` equal to explicit user 0, a clean empty
`pm list packages --user 0 -u com.dsha.nativeechtest`, and exact global dumpsys
package-not-found output. SecurityException, nonempty stderr, unexpected output,
or a globally existing/retained package all mean STOP. It never installs,
removes or overwrites. Android shell access to other users can be restricted;
an empty/error result from an implicit cross-user query does not prove absence.
If the parent approves a fresh install and absence is confirmed:

```powershell
$adb = 'D:\App\Dev\Android\platform-tools\adb.exe'
& $adb -s DEVICE_SERIAL install --user 0 -t '.\android-ech-probe\app\build\outputs\apk\debug\app-debug.apk'
```

Do not add `-r` or uninstall an unknown existing app. A release replacement
after debug requires explicit approval for that now-known probe installation.
No cleanup/uninstall authorization is implied.

## Fresh local peer, public fixture and running a case

Run from this workspace on the host immediately before the device case:

```powershell
.\android-ech-probe\start-peer-for-terra.ps1 -Scenario accepted
```

This launches only `.tools/go/ech-http-peer.exe`, a source-built Go 1.27.3
standard-library ECH HTTP server on **host 127.0.0.1 with an ephemeral port**.
The launch script prints:

- An explicit device reverse mapping, default device TCP port 31443.
- One same-package instrumentation command OR an alternative Activity command.
  **Choose only one per peer session.**
- Exact session paths:
  `.tools/device-sessions/<id>/public-fixture.json`,
  `terra-commands.txt`, `peer.jsonl`, and `peer-stderr.txt`.

The fixture is small JSON containing only device/host ports, scenario,
expected result, timeout, public ephemeral CA certificate and ECHConfigList.
Schema: `android-ech-probe/PUBLIC-FIXTURE.schema.json`.
No private CA or ECH key is printed, transmitted to Android, or written to disk.
Keys live only in the host peer process memory. All Android inputs come from
Intent/runner extras; no browser key or trust-store changes are required.
The process waits 120 seconds for connect; each connected session has an
eight-second deadline. Each new scenario needs a fresh process/fixture.

The generated commands have this structure (use the actual generated public
base64 values rather than literal placeholders):

```powershell
adb -s DEVICE_SERIAL reverse tcp:31443 tcp:HOST_EPHEMERAL_PORT
adb -s DEVICE_SERIAL shell am start -W -n com.dsha.nativeechtest/.EchProbeActivity `
    --es port 31443 --es expected accepted --es timeout_ms 3000 --es trust anchors `
    --es config_b64 PUBLIC_ECHCONFIGLIST_BASE64 --es ca_b64 PUBLIC_ROOT_CERT_BASE64
adb -s DEVICE_SERIAL logcat -d -s NativeEchProof:I '*:S'
# Remove only the reverse mapping created for this test:
adb -s DEVICE_SERIAL reverse --remove tcp:31443
```

Activity UI shows result JSON. `am instrument -w` returns the same JSON in
`result_json`, with no separate AndroidX runner needed. Logs contain result
classification, SDK/ABI, provider version, response match and unchanged-global
checks; config/cert/private-key material and exception messages are not logged.
`adb logcat -d` above is read-only; do not clear unrelated device logs.

## Cases and required independent evidence

Use `-Scenario` with each value below; launch script supplies the right
expected classification and strict trust inputs.

| Scenario | Expected device result | Independent host Go result |
| --- | --- | --- |
| `accepted` | pass=true, enforced ECH status, TLSv1.3, exact response match | ech_accepted=true, server_name=inner.example, exact GET line below |
| `reject` | ECH_REJECTED, pass=true | application_bytes=0 |
| `noech` | ECH_REJECTED, pass=true | application_bytes=0 |
| `wrong-host`, `expired`, `untrusted` | PEER_AUTHENTICATION, pass=true | application_bytes=0 |
| `tamper`, `record-tamper` | HANDSHAKE failure, pass=true | application_bytes=0 |
| `stall` | TIMEOUT, pass=true | application_bytes=0 |
| `cancel` | CANCELLED, pass=true | application_bytes=0 |

Accepted request line must be:

```text
GET /native/android-ech-proof?q=24 HTTP/1.1
```

Inspect the **host** session's second `peer.jsonl` event, not only the app's
enforced-policy status. Device `pass=true` is insufficient by itself.
Every result must also show `globalDefaultsUnchanged=true`. For certificate/
record failures Go may report ECH processed before the client rejects the
connection; zero application bytes are the required negative evidence.

Collect both device JSON and host result for debug first, then repeat with the
R8 APK after any approved replacement. A modern phone run does not establish
API 24 compatibility; minSdk 24 device/emulator coverage remains a separate gate.
No claim of shipped/browser ECH support or completed Node removal is made.
