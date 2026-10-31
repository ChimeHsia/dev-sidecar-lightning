plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsha.sidecar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsha.sidecar"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // assets 里放的是已压缩的 zip 和证书，别再压一遍（否则构建慢、安装时还要多解一次）
        noCompress += listOf("zip", "pem")
    }

    packaging {
        jniLibs {
            // node 可执行文件必须被解压到 nativeLibraryDir 才能 exec（Android 10+ 禁止从数据目录执行）
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libnodebin.so")
        }
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation(project(":netcore"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
