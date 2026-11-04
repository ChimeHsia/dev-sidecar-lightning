# Payload 10 Device Acceptance

Date: 2026-11-02. Android 16, SDK 36. This record covers the second-wave
integration candidate, not a Node-free browser.

## Build

- Browser source: `9441ba45e89dc0b3965306ac436b2e88bf56df5e`.
- APK SHA-256: `3f30cc12cc28009861d6ddc9ae871af61e93f36218b69039a09596337addde33`.
- Project ZIP SHA-256: `8e337c9e27b796cd2847efe43a0b131822f4fa41814b51a8baa10bbd8ab95f62`.
- Browser JVM: 205 passed. Packaged Node runtime: 681 passed, no skips.
  Python packaging: 6 passed. Release APK built after retrying a failed
  512 MiB heap build with a 1024 MiB heap.
- The package, signer, 16 KiB alignment, packaged payload and compiled
  payload-version 10 extractor call were independently checked.

## Device

- First start extracted both payload ZIPs, a Node child ran, port 31181 was
  listening, and the application reported READY. A same-version cold start reported
  READY without logging another extraction.
- GitHub Trending rendered its title and repository content; Google HK
  rendered with the certificate indicator. A malformed request returned 400
  and a later normal request succeeded without stopping the main process.
- CA-verified HTTPS tests returned 200 for GitHub and Google HK, including
  close and keep-alive cases. Google China redirects preserved an encoded
  query. All four migrated adapters logged real request dispatch.
- Normal confirm accepted and prompt canceled; incognito confirm canceled and
  prompt returned entered text. The earlier style acceptance covers the
  broader dialog and tab-style matrix; host-destruction callback behavior was
  not tested on device.
- The guarded 10 to 8 to 10 round trip re-extracted both archives at each
  version change. The old version rendered GitHub without observed wave-two
  dispatch; after re-upgrade, GitHub and all four dispatches worked. This is
  observed behavior, not direct inspection of private marker files.

The device page did not expose verifiable ALPN or HTTP/2 negotiation evidence.
HTTP/2 request metadata is independently exercised against a local real Node
HTTP/2 server/client and origin. Port conflict and warm-return scenarios were
tested on the earlier candidate, not repeated on payload 10.

All installs targeted only `com.dsha.browser` in user 0 with a verified signer,
APK hash and backup; no app data was cleared. Navigation may remain in history
or tabs. The separately documented earlier accidental update to
`top.panming.dsbrowser` was not part of this acceptance and was not reversed.
