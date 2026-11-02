import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

val conscryptAar = providers.gradleProperty("nativeConscryptAar").map { file(it) }
    .orElse(rootProject.file("../port/.run/android-native-check/conscrypt-android-2.8.0.aar"))

android {
    namespace = "com.dsha.nativecorecheck"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dsha.nativecorecheck"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "com.dsha.nativecorecheck.NativeCoreInstrumentation"
        ndk.abiFilters.add("arm64-v8a")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    sourceSets.getByName("main").apply {
        java.directories.add(rootProject.file("../netcore/src/main/java").absolutePath)
        kotlin.directories.add(rootProject.file("../netcore/src/main/kotlin").absolutePath)
    }
    buildFeatures {
        buildConfig = false
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files(conscryptAar))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}

val checkConscryptAar = tasks.register("checkConscryptAar") {
    inputs.file(conscryptAar)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(conscryptAar.get().readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == "752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1") {
            "Expected the pinned Conscrypt Android 2.8.0 AAR"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(checkConscryptAar)
}
