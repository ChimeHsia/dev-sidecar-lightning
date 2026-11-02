# Independent ECH device follow-up

On November 2, 2026 (Asia/Singapore), the existing `com.dsha.nativeechtest`
version 2 diagnostic probe was exercised on one PPG-AN00 device running
Android API 36 with arm64-v8a. The installation guard detected the existing
package, so no installation or replacement was performed.

The installed base APK SHA-256 was
`c23945c327c52add1035675d78c9db317f360845df54ab3f4a310383d76218e3`,
matching the previously audited v2 R8 probe. This probe packages a subset
of the native ECH implementation; it is not the browser or complete netcore.

Two fresh independent Go peers provided these results:

- Acceptance: device reported TLS 1.3 and a matching response. The peer
  confirmed ECH acceptance, inner SNI `inner.example`, and the expected GET
  request, with 87 application bytes.
- Rejection: device reported `ECH_REJECTED`; the peer reported ECH rejection
  and zero application bytes.

The device probe reported unchanged global defaults in both runs.
Temporary reverse mappings were removed. No browser package, application
data, trust store, Node payload or application CA material was changed.

Raw device results, peer output and the device record are retained locally
in the main checkout's ignored `port/.run/terra-device-followup/` directory.
This evidence covers this probe and one device. API 24 compatibility, full
runtime assembly, browser integration and Node replacement remain unproven.
