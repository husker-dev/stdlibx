@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
@file:Suppress("UnstableApiUsage")

import com.android.build.api.withAndroid
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

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

    // Skip Web tests on Node.js and vice versa
    listOf(
        wasmJs(), js()
    ).forEach {
        it.browser {
            testTask {
                useKarma { useChromeHeadless() }
                filter.excludeTestsMatching("com.huskerdev.stdlibx.io.fs.nodejs.*")
                filter.excludeTestsMatching("com.huskerdev.stdlibx.io.fs.WholeFileTest")
            }
        }
        it.nodejs {
            testTask {
                filter.excludeTestsMatching("com.huskerdev.stdlibx.io.fs.opfs.*")
                filter.excludeTestsMatching("com.huskerdev.stdlibx.io.fs.indexeddb.*")
            }
        }
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

    listOf(mingwX64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("windowsOle") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/windowsOle.def"))
        }
    }
    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("unixFileSystemLinux") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/unixFileSystemLinux.def"))
        }
    }
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.LINUX || konanTarget.family == Family.OSX) {
            compilations.getByName("main").cinterops.create("processUnix") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/processUnix.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
    }

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
