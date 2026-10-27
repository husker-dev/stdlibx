@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

group = "com.huskerdev"
version = projectDir.parentFile.parentFile.resolve("VERSION").readText().trim()

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("nativeDesktop") {
                group("linux") { withLinux() }
                group("macos") { withMacos() }
                group("mingw") { withMingw() }
            }
        }
    }

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
        namespace = "$group.stdlibx"
        minSdk = 5
        compileSdk {
            version = release(5)
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

    androidNativeX64()
    androidNativeX86()
    androidNativeArm32()
    androidNativeArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

mavenPublishing {
    publishToMavenCentral()

    if(project.hasProperty("sign"))
        signAllPublications()

    coordinates(group.toString(), "stdlibx", version.toString())

    pom {
        name = "stdlibx"
        description = "Extended standard library for Kotlin Multiplatform"
        applyDefaultPomInfo()
    }
}