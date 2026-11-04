import java.security.MessageDigest

plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsha.nativeechtest"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dsha.nativeechtest"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "0.2-diagnostic"
        testInstrumentationRunner = "com.dsha.nativeechtest.EchProbeInstrumentation"
        ndk.abiFilters.add("arm64-v8a")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").apply {
        java.directories.add(layout.buildDirectory.dir("generated/frozen-core").get().asFile.absolutePath)
        kotlin.directories.add(layout.buildDirectory.dir("generated/frozen-core").get().asFile.absolutePath)
    }
    buildFeatures {
        buildConfig = false
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../../netcore/consumer-rules.pro",
                "proguard-rules.pro"
            )
        }
    }
}

val officialAar = rootProject.file("../.tools/conscrypt/conscrypt-android-2.8.0.aar")
dependencies {
    implementation(files(officialAar))
}
val checkPinnedAar = tasks.register("checkPinnedAar") {
    inputs.file(officialAar)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256").digest(officialAar.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == "752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1")
    }
}
val stageFrozenCore = tasks.register<Sync>("stageFrozenCore") {
    from(rootProject.file("../netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/ConscryptEchConnector.kt")) {
        into("com/dsha/sidecar/net/proxy/ech")
    }
    from(rootProject.file("../netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/EchDiagnostics.kt")) {
        into("com/dsha/sidecar/net/proxy/ech")
    }
    from(rootProject.file("../netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt")) {
        into("com/dsha/sidecar/net/dns")
    }
    from(rootProject.file("../netcore/src/main/java/org/conscrypt/NativeEchPolicyAdapter.java")) {
        into("org/conscrypt")
    }
    into(layout.buildDirectory.dir("generated/frozen-core"))
}
tasks.named("preBuild") { dependsOn(checkPinnedAar, stageFrozenCore) }
