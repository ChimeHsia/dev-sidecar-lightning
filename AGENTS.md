# Repository Guidelines

## Project Structure

- `lightning-browser/` is the primary Android application and a separate Gradle build. Kotlin sources, Android resources, and JVM tests are under `app/src/main/` and `app/src/test/`.
- Root-level `app/` and `netcore/` are the earlier reference shell and Kotlin networking implementation; `netcore/src/test/` contains networking regression tests.
- `port/` builds and synchronizes the embedded Node payload. `patches/` contains comparison patches. See `LIGHTNING.md` and `PORT.md` for integration details.

## Build, Test, and Development

Use JDK 21 and the Android SDK. Check the machine-specific JDK path in `gradle.properties` and SDK configuration in `local.properties`.

- `python port/build-payload.py --resources <dev-sidecar-resources>` builds the untracked Node payload from an installed dev-sidecar.
- `./port/sync-payload.sh` copies that payload into the browser. Run this before building a functional browser APK.
- `cd lightning-browser && ./gradlew assembleLightningPlusRelease` builds the arm64 release APK.
- `cd lightning-browser && ./gradlew testLightningPlusDebugUnitTest` runs browser JVM tests.
- `./build.sh :netcore:test` runs root-project networking tests; set `GRADLE_BIN` if the script's default Gradle 8.14 path is unavailable. Add `-Pdsha.live=1` for opt-in network smoke tests.

The shell commands above assume Bash. Windows users can run `.\scripts\test-baseline.ps1 -Suite All` in PowerShell; see `docs/REGRESSION.md` for suite boundaries and device acceptance.

## Style and Testing

Follow the surrounding Kotlin and Gradle Kotlin DSL style: four-space indentation, `UpperCamelCase` types, and `lowerCamelCase` functions and properties. Keep Android resources in their existing directories and use lowercase underscore-separated names. Place tests alongside the relevant module, mirror production packages, and name test classes `*Test.kt`. JUnit 4 is used in both builds; browser tests also use Robolectric and AssertJ. Add regression tests for behavior changes, particularly TLS and proxy handling. No numeric coverage threshold is configured.

## Commits and Pull Requests

Recent commits use short prefixed subjects such as `chore: ...`, `port: ...`, and `baseline: ...`; describe the change concisely. Open pull requests against `main`, summarize behavior and verification, link relevant issues, and include screenshots for visible UI changes.

## Security and Configuration

Generated Node binaries, payload archives, local configuration, and CA key material must not be committed. The browser uses a local MITM proxy on `127.0.0.1:31181`; stop competing test packages before device testing. Release builds currently use the debug signing configuration and are intended for local use.
