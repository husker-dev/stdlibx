package com.huskerdev.osutils

private val isNodeJs: Boolean =
    js("typeof window === 'undefined' && typeof process !== 'undefined'")

internal actual fun currentOS() = if(isNodeJs) OS.NODE_JS else OS.WEB_JS
internal actual fun currentArch() = Arch.UNKNOWN