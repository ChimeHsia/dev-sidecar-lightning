import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 不使用 toolchain：本机只有 JDK 21，统一用当前 JDK 编译并输出 17 字节码
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("org.conscrypt:conscrypt-openjdk-uber:2.8.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    testImplementation(kotlin("test"))
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.8.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // -Pdsha.live=1 时启用联网冒烟测试
    systemProperty("dsha.live", providers.gradleProperty("dsha.live").getOrElse(""))
    systemProperty("dsha.liveHost", providers.gradleProperty("dsha.liveHost").getOrElse(""))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
