@file:Suppress("unused")

package com.huskerdev.stdlibx

import kotlin.jvm.JvmStatic

internal expect fun currentOS(): OS
internal expect fun currentArch(): Arch

enum class OS(
    val dylibExtension: String = "",
    val staticLibExtension: String = "",
    val isUnixLike: Boolean = false
) {
    WINDOWS(dylibExtension = "dll", staticLibExtension = "lib"),
    LINUX(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    MACOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),

    IOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),
    WATCHOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),
    TVOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),

    ANDROID(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    WEAROS(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    ANDROID_TV(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),

    UNKNOWN
    ;
    companion object {
        @JvmStatic val current by lazy { currentOS() }

        val isWindows: Boolean
            get() = current == WINDOWS

        val isLinux: Boolean
            get() = current == LINUX

        val isMacOS: Boolean
            get() = current == MACOS

        val isAndroid: Boolean
            get() = current == ANDROID

        val isWearOS: Boolean
            get() = current == WEAROS

        val isAndroidTV: Boolean
            get() = current == ANDROID_TV

        val isIOS: Boolean
            get() = current == IOS

        val isWatchOS: Boolean
            get() = current == WATCHOS

        val isTVOS: Boolean
            get() = current == TVOS
    }
}

enum class Arch(
    val is64ptr: Boolean
) {
    X86(false),
    X64(true),
    ARM32(false),
    ARM64(true),
    RISCV32(false),
    RISCV64(true),
    UNKNOWN(false)
    ;
    companion object {
        @JvmStatic val current by lazy { currentArch() }
    }
}