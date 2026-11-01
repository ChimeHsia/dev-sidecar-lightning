plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    js(IR) {
        nodejs()
        useCommonJs()
        binaries.library()
    }

    sourceSets {
        val jsMain by getting {
            kotlin.srcDir("../netcore/src/main/kotlin")
            kotlin.include(
                "com/dsha/sidecar/net/config/ConfigMerge.kt",
                "com/dsha/sidecar/net/dns/IpAddress.kt",
                "com/dsha/sidecar/net/dns/Nat64Address.kt",
                "com/dsha/sidecar/net/rules/HostnameMatcher.kt",
                "com/dsha/sidecar/net/rules/HostnameAllMatcher.kt",
                "com/dsha/sidecar/net/rules/PathRuleMatcher.kt",
                "com/dsha/sidecar/net/dns/DynamicChoice.kt",
                "com/dsha/sidecar/net/http/RequestTransform.kt",
                "Exports.kt",
                "MigrationExports.kt",
                "MatchRuntimeExports.kt",
                "ChoiceRuntimeExports.kt",
            )
        }
    }
}
