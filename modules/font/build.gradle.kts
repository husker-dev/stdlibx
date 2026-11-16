@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    id("com.huskerdev.native-kt") version "4.0.8"
}

group = "com.huskerdev"
version = projectDir.parentFile.parentFile.resolve("VERSION").readText().trim()

kotlin {
    jvmToolchain(24)

    jvm {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                jvmTarget = JvmTarget.JVM_11
            }
        }
    }

    wasmJs {
        browser()
        nodejs()
    }
    js {
        browser()
        nodejs()
    }

    android {
        namespace = "$group.stdlibx.font"
        minSdk = 5
        compileSdk {
            version = release(29)
        }
    }

    mingwX64()

    linuxX64()
    linuxArm64()

    if(Os.isFamily(Os.FAMILY_MAC)) {
        macosArm64()

        iosX64()
        iosArm64()
        iosSimulatorArm64()

        watchosArm32()
        watchosArm64()
        watchosDeviceArm64()
        watchosSimulatorArm64()

        tvosArm64()
        tvosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

natives {
    add("stdlibxfont")
}

tasks.register("generateEmojiSplitter") {
    description = "Generates Emoji splitter source file"
    val target = file("src/commonMain/kotlin/com/huskerdev/stdlibx/font/EmojiSplitter.kt").toPath()

    doFirst {
        generateEmojiSplitter(target, "com.huskerdev.stdlibx.font")
    }
}

mavenPublishing {
    publishToMavenCentral()

    if(project.hasProperty("sign"))
        signAllPublications()

    coordinates(group.toString(), "stdlibx-font", version.toString())

    pom {
        name = "stdlibx-font"
        description = "Extended standard library for Kotlin Multiplatform"
        applyDefaultPomInfo()
    }
}