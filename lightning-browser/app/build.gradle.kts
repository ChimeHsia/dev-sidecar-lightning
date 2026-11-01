import app.cash.licensee.SpdxId

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.12"
    id("com.anthonycr.plugins.mezzanine") version "2.5.0"
    id("com.autonomousapps.dependency-analysis") version "3.20.0"
    id("com.squareup.sort-dependencies") version "0.21.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("com.anthonycr.plugins.mockingbird") version "3.4.0"
    id("app.cash.licensee") version "1.14.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

android {
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        buildToolsVersion = "37.0.0"
        minSdk = 24
        targetSdk = 37
        versionName = "5.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    val isCi = System.getenv("CI") == "true"

    sourceSets {
        create("lightningPlus").apply {
            setRoot("src/LightningPlus")
        }
        create("lightningLite").apply {
            setRoot("src/LightningLite")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    buildTypes {
        named("debug") {
            multiDexEnabled = true
            isMinifyEnabled = false
            isShrinkResources = false
            setProguardFiles(listOf("proguard-project.txt"))
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
        }

        named("release") {
            multiDexEnabled = false
            // 不混淆：R8 会把 kotlinx.serialization / Room 的类型信息裁坏，
            // 启动即崩（IllegalStateException: Unsupported type class fn4）。
            // 上游靠 CI 里的额外 keep 规则撑着，本地自用直接关掉最省事，
            // 顺带保留 Log.i，方便看加速核心的日志。
            isMinifyEnabled = false
            isShrinkResources = false
            setProguardFiles(listOf("proguard-project.txt"))
            // 本地自用：用 debug 签名，省去自建 keystore
            signingConfig = signingConfigs.getByName("debug")
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false

            ndk {
                // 内嵌的 Node 运行时只有 arm64，其它 ABI 打了也跑不了加速
                abiFilters.add("arm64-v8a")
            }
        }
    }

    flavorDimensions.add("capabilities")

    val commonVersionCode = 102

    productFlavors {
        create("lightningPlus") {
            dimension = "capabilities"
            buildConfigField("boolean", "FULL_VERSION", "Boolean.parseBoolean(\"true\")")
            applicationId = "top.panming.dsbrowser"
            versionCode = commonVersionCode
        }
        create("lightningLite") {
            dimension = "capabilities"
            buildConfigField("boolean", "FULL_VERSION", "Boolean.parseBoolean(\"false\")")
            applicationId = "acr.browser.barebones"
            versionCode = commonVersionCode
        }
    }
    packaging {
        resources {
            excludes += listOf(".readme")
        }
        jniLibs {
            // Node 二进制以 libnodebin.so 的形式放在 jniLibs 里，
            // Android 10+ 只允许从 nativeLibraryDir 执行文件，必须真解压到磁盘
            useLegacyPackaging = true
        }
    }

    androidResources {
        // 这两个是运行时才解压的资源，禁止 AAPT 二次压缩
        noCompress += listOf("zip", "pem")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }
    namespace = "acr.browser.lightning"

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs = listOf(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.runtimeConfiguration.resolutionStrategy.activateDependencyLocking()
    }
}

dependencies {
    val robolectric = "4.17"
    val mezzanineVersion = "2.5.0"
    val daggerVersion = "2.60.1"
    val kotlin = "2.4.21"
    val datastore = "1.3.0"
    val coil = "3.6.3"
    val coroutines = "1.11.0"
    val leakcanary = "2.14"
    val lifecycle = "2.11.0"
    val serialization = "1.12.0"

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-geometry")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.datastore:datastore:$datastore")
    implementation("androidx.datastore:datastore-core:$datastore")
    implementation("androidx.datastore:datastore-preferences:$datastore")
    implementation("androidx.datastore:datastore-preferences-core:$datastore")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-common:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    implementation("androidx.palette:palette:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.anthonycr.mezzanine:core:$mezzanineVersion")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.dagger:dagger:$daggerVersion")
    implementation("com.guolindev.permissionx:permissionx:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-compose-core:${coil}")
    implementation("io.coil-kt.coil3:coil-core:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
    implementation("javax.inject:javax.inject:1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
    implementation("org.jsoup:jsoup:1.23.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.leakcanary:leakcanary-android-core:$leakcanary")
    debugImplementation("com.squareup.leakcanary:shark:$leakcanary")

    compileOnly("javax.annotation:jsr250-api:1.0")

    // 注意：这个自安装包会在 debug 包里注册 LAUNCHER 入口，每次启动都把满屏的
    // "0 Distinct Leaks" 盖在浏览器前面。它被写进了 gradle 的 dependency lock，
    // 删掉会让依赖解析失败 —— 所以调试请用 release 变体（release 不含 LeakCanary）。
    debugRuntimeOnly("com.squareup.leakcanary:leakcanary-android:$leakcanary")

    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:${kotlin}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines")
    testImplementation("org.robolectric:annotations:$robolectric")
    testImplementation("org.robolectric:robolectric:$robolectric")

    ksp("com.anthonycr.mezzanine:processor:$mezzanineVersion")
    ksp("com.google.dagger:dagger-compiler:$daggerVersion")
}

mezzanine {
    files = files(
        "src/main/html/list.html",
        "src/main/html/bookmarks.html",
        "src/main/html/homepage.html",
        "src/main/js/InvertPage.js",
        "src/main/js/TextReflow.js",
        "src/main/js/ThemeColor.js"
    )
}

licensee {
    bundleAndroidAsset = true
    androidAssetReportPath = "licensee/artifacts.json"

    allow(SpdxId.Apache_20)
    allow(SpdxId.BSD_3_Clause)
    allowUrl("https://jsoup.org/license")
}

kotlin {
    jvmToolchain(21)
}
