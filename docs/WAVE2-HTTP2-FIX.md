# HTTP/2 request metadata regression

Payload 9's device acceptance exposed a local 400 on GitHub. A real Node
HTTP/2 server/client reproduced the adapter failure without external network:
`Http2ServerRequest.headers` includes Node's `http2.sensitiveHeaders` symbol.
The new request boundary rejected every symbol before calling Kotlin.

The original proxy converts `req.authority` to `headers.host` and filters
wire headers through `Object.entries`, which excludes this protocol metadata.
The fix allows only that known symbol, and only when `httpVersionMajor === 2`.
Its value is neither serialized nor forwarded. Other symbols and HTTP/1
symbol-bearing input remain rejected.

## Verification

- The real HTTP/2 forwarding regression fails against the old adapter with
  fixed `400 / Bad Request\n`, then passes with the fix and a real local HTTP
  origin. A rejected unknown-symbol request does not break the next request.
- The packaged-runtime suite passes 681 tests, with zero failures or skips.
- The Kotlin library is unchanged; this is a Node-boundary compatibility repair.
- Payload marker increases from 9 to 10 to force extraction of the changed zip.
- The device rollback check also exposed stale historical completion markers.
  The extractor now retains only the completed version of each archive and
  re-extracts when another version's marker appears. An old APK therefore
  cannot skip rollback extraction based on an obsolete marker. Failures leave
  no valid completion marker, and unlisted user configuration remains intact.
  Independent JVM tests cover upgrade, old-APK rollback and return, retry,
  invalid archives, traversal and forged markers.

This candidate retains the accepted Material theme repair and all second-wave
bridges. It does not include third-wave/native runtime application.
Browser build and device acceptance are recorded separately; the failed
payload 9 candidate must not be presented as accepted.
