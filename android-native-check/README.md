# Full native core Android check

This independent Gradle build compiles all production Kotlin and Java sources
from `netcore`, packages the Android Conscrypt provider, and desugars Java time
and NIO APIs for a minimum SDK of 24. It does not change the browser build or
select a product runtime. The empty application has a distinct package name,
`com.dsha.nativecorecheck`, and no launcher activity.

Use JDK 21, Gradle 9.8.2, Android platform 37.2 and build tools 37.0.0.
Supply the official Conscrypt Android 2.8.0 AAR with
`-PnativeConscryptAar=<absolute-path>`. Its SHA-256 is checked before building;
the AAR and generated APKs must not be committed.

From this directory, run Gradle tasks `:app:assembleDebug` and
`:app:lintDebug`. Add `--offline` after dependencies have been cached.
Acquire the shared `Local\DshaNativeRescueBuild` mutex for heavy builds.

An APK build proves compilation, DEX generation and packaging, not execution
of every core path on every supported Android API level. Lint and device
execution provide separate evidence. The product still starts Node.
