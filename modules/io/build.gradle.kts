@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
@file:Suppress("UnstableApiUsage")

import com.android.build.api.withAndroid
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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
            group("commonJvm") {
                withJvm()
                withAndroid()
            }
            group("desktop") {
                group("nativeDesktop") {
                    group("unix") {
                        group("linux") { withLinux() }
                        group("macos") { withMacos() }
                    }
                    group("mingw") { withMingw() }
                }
                group("jvm") { withJvm() }
            }
        }
    }
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(24)

    jvm {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                jvmTarget = JvmTarget.JVM_11
            }
        }
    }

    listOf(
        wasmJs(), js()
    ).forEach {
        it.browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        it.nodejs()
    }

    android {
        namespace = "$group.stdlibx.io"
        minSdk = 21
        compileSdk {
            version = release(35)
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "com.huskerdev.stdlibx.io.IoTestRunner"
        }
    }

    mingwX64()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core"))
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    if(project.hasProperty("sign"))
        signAllPublications()

    coordinates(group.toString(), "stdlibx-io", version.toString())

    pom {
        name = "stdlibx-io"
        description = "Extended standard IO library for Kotlin Multiplatform"
        applyDefaultPomInfo()
    }
}
