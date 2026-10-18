@file:Suppress("UnstableApiUsage")

rootProject.name = "stdlibx"

include("modules:core")
include("modules:io")
include("modules:fs")
include("modules:process")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }

    versionCatalogs {
        create("libs") {
            from(files("libs.versions.toml"))
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

gradle.beforeProject {
    repositories.addAll(dependencyResolutionManagement.repositories)
}
