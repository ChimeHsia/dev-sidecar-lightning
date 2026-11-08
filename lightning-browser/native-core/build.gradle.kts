import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.dsha.sidecar.net"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    sourceSets.getByName("main").apply {
        java.directories.add(layout.projectDirectory.dir("../../netcore/src/main/java").asFile.absolutePath)
        kotlin.directories.add(layout.projectDirectory.dir("../../netcore/src/main/kotlin").asFile.absolutePath)
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("org.brotli:dec:0.1.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
