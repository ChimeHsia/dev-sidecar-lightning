# Repository Guidelines

## Project Structure

- `lightning-browser/` is the primary Android application and a separate Gradle build. Kotlin sources, Android resources, and JVM tests are under `app/src/main/` and `app/src/test/`.
- Root-level `app/` is the earlier reference shell. `netcore/` contains the shared native network core, compiled into the browser by `lightning-browser/native-core/`; `netcore/src/test/` contains networking regression tests.
- `port/` exports native default data and retains the historical Node payload tools. `patches/` contains comparison patches. See `docs/NATIVE-KOTLIN-MIGRATION-2026-11-08.md` for current integration; `PORT.md` describes the historical Node path.

## Build, Test, and Development

Use JDK 21 and the Android SDK. Check the machine-specific JDK path in `gradle.properties` and SDK configuration in `local.properties`.

- Native builds use the committed `assets/native/` data; no Node payload build/sync is needed. Local CA assets must match the App trust resource and remain untracked.
- `python scripts/test-native-core.py --java-home <jdk21> --conscrypt-jar <conscrypt-openjdk-uber-2.8.0.jar> --go-exe <go>` runs the full native suite with independent ECH peers.
- `python scripts/verify-native-browser-apk.py --apk <apk>` verifies native packaging and absence of Node.
- `cd lightning-browser && ./gradlew assembleLightningPlusRelease` builds the arm64 release APK.
- `cd lightning-browser && ./gradlew testLightningPlusDebugUnitTest` runs browser JVM tests.
- `./build.sh :netcore:test` runs root-project networking tests; set `GRADLE_BIN` if the script's default Gradle 8.14 path is unavailable. Add `-Pdsha.live=1` for opt-in network smoke tests.

The shell commands above assume Bash. Windows users can run `.\scripts\test-baseline.ps1 -Suite All` in PowerShell; see `docs/REGRESSION.md` for suite boundaries and device acceptance.

## Style and Testing

Follow the surrounding Kotlin and Gradle Kotlin DSL style: four-space indentation, `UpperCamelCase` types, and `lowerCamelCase` functions and properties. Keep Android resources in their existing directories and use lowercase underscore-separated names. Place tests alongside the relevant module, mirror production packages, and name test classes `*Test.kt`. JUnit 4 is used in both builds; browser tests also use Robolectric and AssertJ. Add regression tests for behavior changes, particularly TLS and proxy handling. No numeric coverage threshold is configured.

## Commits and Pull Requests

Recent commits use short prefixed subjects such as `chore: ...`, `port: ...`, and `baseline: ...`; describe the change concisely. Open pull requests against `main`, summarize behavior and verification, link relevant issues, and include screenshots for visible UI changes.

## Agent Tool Output Constraints

This workspace holds multi-megabyte generated assets, and linked worktrees in `.claude/worktrees/` are full repository copies. Unbounded tool output is the main cause of agent sessions stalling with "too large" or timeout errors, so these limits are mandatory rather than advisory.

- Never read a file in full unless it is under roughly 200 lines. Locate the region first, then read it with `offset`/`limit` or `sed -n 'A,Bp'`.
- The following are excluded through the shared `.git/info/exclude` and must never be read whole: `app/src/main/assets/hosts.txt` and `lightning-browser/app/src/main/assets/hosts.txt` (1.7 MB each), `build/outputs/mapping/**`, `build/intermediates/r8_art_profile/**`, plus all `*.zip`, `*.so`, `*.dex`, `*.apk` assets.
- Bound every search: use `-m 20` or pipe through `head -n 40`. Never search from the repository root without a path or `--glob` narrowing.
- Never dump a full build log. Filter it, for example `./gradlew <task> 2>&1 | grep -E "error:|FAILED|BUILD" | tail -n 30`.
- Prefer one targeted read over reading a directory of files.
- When delegating to a subagent, ask for findings plus short evidence (paths and line numbers), not full file contents.
- To inspect an excluded file on purpose, override explicitly with `rg --no-ignore <pattern> <path>` and keep the output bounded.

`.git/info/exclude` sits in the common git directory, so every linked worktree shares it and checkout, merge, and rebase never overwrite it. Do not rewrite it from inside a worktree; extend it only from the primary checkout.

## Security and Configuration

Generated Node binaries, payload archives, local configuration, and CA key material must not be committed. The browser prefers a local MITM proxy on `127.0.0.1:31181`, trying successive ports through 31200 on bind conflicts. Record the actual service-owned port during device testing. Release builds currently use the debug signing configuration and are intended for local use.
