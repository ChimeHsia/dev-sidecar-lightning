# Native retry rescue review — November 2, 2026

The interrupted draft compiles, but does not implement runtime retries.
`Forward.attempts` was populated by the adapter and never consumed by the
engine. `NativePreparedUpstreamResponse` was never constructed. Adding a
replay InputStream parameter did not implement complete framing validation,
capture admission, aggregate accounting or ownership. The draft's diagnostics
claimed capabilities that were absent.

The unused scaffolding has been removed from compiled sources. Its exact
diff and new source are preserved locally under ignored
`port/.run/native-core-review/rescued-unfinished-retry-draft.patch` and
`port/.run/native-core-review/rescued-NativeAttemptPolicy.kt.draft`.
The runtime and cache compatibility guards reject retry/backup as before.
NativeRuntime.kt and the ECH bridge were not edited.

## Independently reviewable changes

1. The standalone scheduler refuses status retries when a response explicitly
   reports downstream commitment. It retains ownership of the returned final
   response; it does not release that response as if it were discarded.
2. The engine reports typed TCP success/failure after the actual connect.
   Runtime DNS tickets consume each report once. Failure affects subsequent
   exchanges, without an implicit second attempt on the current exchange.
   Registration/stop failures and TLS/HTTP failures do not count as connect
   failures. Observer exceptions do not alter network forwarding.

Local origin tests cover TCP success, refused first candidate, selection of
the next candidate on a separate request, duplicate feedback suppression, and
absence of connect feedback when DNS resolution fails.

Changed source and regression paths:

- `netcore/src/main/kotlin/com/dsha/sidecar/net/http/native/schedule/RequestAttemptScheduler.kt`
- `netcore/src/main/kotlin/com/dsha/sidecar/net/http/native/schedule/ScheduleModels.kt`
- `netcore/src/test/kotlin/com/dsha/sidecar/net/http/native/schedule/RequestAttemptSchedulerTest.kt`
- `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/native/NativeProxyRoute.kt`
- `netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/native/NativeProxyServer.kt`
- `netcore/src/main/kotlin/com/dsha/sidecar/net/runtime/NativeRuntimeRouteResolver.kt`
- `netcore/src/main/kotlin/com/dsha/sidecar/net/runtime/NativeRuntimeConfig.kt`
- `netcore/src/test/kotlin/com/dsha/sidecar/net/runtime/NativeRuntimeIntegrationTest.kt`

## Still required before opening retry/backup gates

- A real attempt must return an owned head and retained body reader before
  downstream commitment. Discard must close its sockets/timers exactly once.
- Close empty GET/HEAD first-500/then-200 exchanges through the actual engine,
  then admit only completely validated bounded replay entities. Enforce
  aggregate capacity before capture; failed/partial captures are terminal.
- Keep POST side effects single-shot after possible delivery, and preserve
  chunked, oversized, Expect, early responses and WebSocket behavior.
- Rebuild candidate URL/path/captures, Host, port, SNI, verifyHost, STRICT trust,
  ECH requirement and DNS ticket. The abandoned candidate helper did not
  demonstrate preservation of standalone TLS overrides.
- Test discard without draining, partial downstream commit, close, timeout,
  stop/cancellation, replay admission exhaustion, malformed capture, and two
  independently verified TLS backup origins.
- Feed actual final TLS/HTTP/slow outcomes separately into BackupSelector.

For the body-hook integration, select the owned final response before invoking
response headers/body processors. Rejected retry responses must not trigger
those processors. This is a proposed integration contract, not an implemented
engine hook in these commits.

## Verification

The provided `scripts/test-native-core.py` ran with JDK 21.0.2, cached Gradle
dependencies, the provided Conscrypt 2.8.0 JAR and Go executable. All compile
and test execution held `Local\DshaNativeRescueBuild`, acquired through repeated
`WaitOne(1000)` and released in `finally`.

The reviewed draft compiled and the first aggregate completed 885 tests:
883 passed, 2 external-network assumptions skipped, zero failures.
After removing the unused scaffolding, compilation succeeded again and the
aggregate completed 885 tests: 882 passed, 2 assumptions skipped, one failure.
The failure was `ConscryptNativeTlsDialerTest.handshakeTimeoutCannotDeliverRequest`:
the 200 ms stalled ECH handshake returned 502 where the test expected 504.
That unchanged test passed in the first run. The failure is recorded rather
than attributed conclusively to timing or changed as part of this focused
review. All scheduler, engine compatibility and runtime suites passed in both
runs. The final aggregate is therefore not fully green.

Both runs used:

```powershell
python scripts/test-native-core.py --java-home 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2' --conscrypt-jar 'C:\Users\HONOR\.codex\worktrees\native-core-ech-v2\dev-sidecar-lightning\.tools\conscrypt\conscrypt-openjdk-uber-2.8.0.jar' --go-exe 'C:\Users\HONOR\.codex\worktrees\native-ech-conscrypt\dev-sidecar-lightning\.tools\go\go\bin\go.exe'
```

## Follow-up ownership step — November 2, 2026

The runtime now uses `NativeUpstreamAttempt` in the
actual engine's existing single-attempt forwarding path. It owns the buffered
response reader, head-wait timer and raw-socket release callback through body
transfer. Closing the owner cancels the head wait and releases the socket
once, without draining an unselected body. Failures before ownership transfer
retain the engine's existing fallback cleanup.

This is a prerequisite refactor, not a runtime retry implementation. No retry
field was added to `Forward`, no second connection is scheduled, and neither
runtime configuration nor cache compatibility gates were relaxed. Response
policy invocation order, upload handling, POST single-shot behavior and the
existing whole-exchange deadline remain as before.

Two new loopback tests in `NativeUpstreamAttemptTest` verify that the selected
body remains available through the retained reader, repeated close invokes
release once, reads after close fail, and a discarded 500 with an absent
million-byte body closes without waiting for that body.

The command above completed in this worktree under JDK 21.0.2, holding
`Local\DshaNativeRescueBuild` for compilation and execution. The lock was
acquired with repeated `WaitOne(1000)` and released in `finally`.
The current aggregate reports 69 suites, 960 executed tests, 958 passed,
2 external-network assumptions skipped, zero failures and zero ignored tests.
The existing engine compatibility, runtime POST single-shot, committed-body
failure, timeout and shutdown suites were included. The unchanged Conscrypt
handshake-timeout test passed in this run; that does not resolve the earlier
intermittent failure conclusively. Machine-readable output and ECH proof files
were copied from the follow-up worktree and verified by SHA-256; they are local
and ignored at `port/.run/retry-followup-preserved/`.

The uncompiled empty-request status-retry sketch is retained locally at
`port/.run/retry-followup-preserved/native-status-retry.kt.draft`. The earlier
`rescued-*` files in the main checkout were read only.

Still unclosed for the next independently verified step:

- Connect an explicit bounded same-route status retry loop for completely
  validated empty GET/HEAD, including first-500/then-200 engine tests.
- Select the owned final head before invoking response processors. Discarded
  heads must never reach those processors.
- Prohibit retries after any attempted downstream head write, including
  informational or partial heads. Exercise deadline races, interruption,
  stop/cancellation, and timer/socket cleanup across multiple attempts.
- Preserve single-shot POST, chunked or nonempty uploads, Expect, early
  responses and WebSocket behavior with retry configured at the engine boundary.
- Keep general runtime retry/backup gates closed until their declared scope
  is independently verified. Body capture/admission/accounting, backup target
  recomposition, independent TLS verification and final outcome feedback remain
  outside this ownership-only step.

No device, Node payload, certificate material or ECH bridge was changed.
