# Android packaging checkpoint

The main branch contains the CONNECT handoff compatibility fix (`a4b21b3`).
The native JVM aggregate after that fix executed 966 tests: 964 passed,
two external-network assumptions skipped, and none failed. This includes the
coalesced CONNECT and ClientHello regression.

An independent Android build in the `native-android-packaging` worktree
compiled all netcore production sources and assembled an arm64 debug APK on
November 2, 2026. Its standalone build is committed there as `0ba39ad`, but
is not integrated into main. The APK contains the pinned Conscrypt Android
2.8.0 JNI library, BC, native core and desugared time/NIO classes.

This is not yet a finished test harness. Its declared instrumentation runner
has not been implemented, and its auxiliary build and APK inspection scripts
are uncommitted drafts. Android lint also reported two API 26 named-regex
group access errors against the declared minimum API 24. Do not treat the
successful build as proof of API 24/25 compatibility or browser integration.
The current worktree is the app's primary checkout and cannot be archived
with the managed worktree tool.

The ignored `port/.run/android-packaging-checkpoint/` directory in the main
checkout preserves hash-verified copies of the draft scripts and the local
JVM, APK and lint reports. The independent ECH device proof is recorded in
`android-ech-device-followup.md`; it did not exercise this full-core APK.
The product continues to use Node.
