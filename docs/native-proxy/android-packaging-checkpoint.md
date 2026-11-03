# Android packaging checkpoint

The standalone `android-native-check` build compiles the full netcore source,
packages the pinned Conscrypt Android 2.8.0 arm64 library, and desugars Java
time and NIO APIs. The build, instrumentation APK, APK content verification
and Android lint passed on November 4, 2026. The two lint errors for API 26
named-regex group access were fixed; Android's stricter regex syntax also
required escaping a closing brace in the rule target replacement pattern.

The native JVM aggregate after these fixes executed 968 tests: 966 passed,
two external-network assumptions skipped, and none failed.

On one PPG-AN00 device running Android API 36 and arm64-v8a, the standalone
instrumentation checked Conscrypt availability, named capture replacement,
runtime start and stop, a local synthetic HTTP response, and a trusted
CONNECT/MITM handshake and response using an in-memory test CA. The two
dedicated test packages were absent before installation and removed from the
owner user after the test. The browser and its data were not modified.

This evidence does not establish runtime compatibility on API 24 or 25, nor
does it cover browser integration, real upstream traffic, ECH/NAT64 runtime
integration, or product acceptance. The independent ECH device probe is
documented in `android-ech-device-followup.md`. The product still starts Node.
