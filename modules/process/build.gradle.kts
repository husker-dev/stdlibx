@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

group = "com.huskerdev"
version = projectDir.parentFile.parentFile.resolve("VERSION").readText().trim()

kotlin {
    applyDefaultHierarchyTemplate {
        common {
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
    jvmToolchain(24)
    jvm {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                jvmTarget = JvmTarget.JVM_11
            }
        }
    }
    mingwX64()
    linuxX64()
    linuxArm64()
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
            api(project(":modules:fs"))
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (project.hasProperty("sign"))
        signAllPublications()
    coordinates(group.toString(), "stdlibx-process", version.toString())
    pom {
        name = "stdlibx-process"
        description = "Child processes for Kotlin Multiplatform desktop targets"
        applyDefaultPomInfo()
    }
}
