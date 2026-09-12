@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

group = "com.huskerdev"
version = "1.0.0"

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
        namespace = "$group.osutils"
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
}


mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "os-utils", version.toString())

    pom {
        name = "os-utils"
        description = "OS utilities library for Kotlin Multiplatform"
        url = "https://github.com/husker-dev/os-utils"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "husker-dev"
                name = "Nikita Shtengauer"
                email = "redfancoestar@gmail.com"
            }
        }
        scm {
            connection = "https://github.com/husker-dev/os-utils.git"
            developerConnection = "https://github.com/husker-dev/os-utils.git"
            url = "https://github.com/husker-dev/os-utils"
        }
    }
}