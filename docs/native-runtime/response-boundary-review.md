# Native response boundary review

The proxy can hand a final upstream response to a trusted policy before writing
its final downstream head. A policy may leave the entity unread for normal
streaming, or read a bounded, complete entity and return a prepared response.
Prepared responses use a validated content length and close the connection.
Incomplete, oversized or trailer-bearing entities cannot be prepared.

The policy boundary alone does not enable response scripts, HTML injection,
Brotli transformation, retries or backup origins. The interrupted script
integration remains unverified in the `native-response-followup` worktree.
Informational responses can already have been sent before the final response
hook; this boundary is not evidence that retries after any downstream write
are safe.

The standalone change passed 961 tests in its worktree, with 959 passed and two
external-network assumptions skipped. After integration with the upstream
ownership change, the native aggregate passed 965 tests: 963 passed, two
external-network assumptions skipped, and none failed. Tests include response
framing and quotas, an explicit trailer-rejection reason and delegation of the
new policy callback through the navigation wrapper. They do not establish
Android or device behavior. Both runs held `Local\DshaNativeRescueBuild`.

Local ignored reports are under `port/.run/response-boundary-preserved/`.
