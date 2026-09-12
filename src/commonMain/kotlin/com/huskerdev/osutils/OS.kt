@file:Suppress("unused")

package com.huskerdev.osutils

import kotlin.jvm.JvmStatic

internal expect fun currentOS(): OS
internal expect fun currentArch(): Arch

enum class OS(
    val dylibExtension: String = "",
    val staticLibExtension: String = "",
    val isUnixLike: Boolean = false,
    val isWeb: Boolean = false,
    val isNode: Boolean = false
) {
    MACOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),
    IOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),
    WATCHOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),
    TVOS(dylibExtension = "dylib", staticLibExtension = "a", isUnixLike = true),

    WINDOWS(dylibExtension = "dll", staticLibExtension = "lib"),

    LINUX(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    ANDROID(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    WEAROS(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),
    ANDROID_TV(dylibExtension = "so", staticLibExtension = "a", isUnixLike = true),

    WEB_JS(isWeb = true),
    WEB_WASM(isWeb = true),
    NODE_JS(isNode = true),
    NODE_WASM(isNode = true),

    UNKNOWN
    ;
    companion object {
        @JvmStatic val current by lazy { currentOS() }
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
