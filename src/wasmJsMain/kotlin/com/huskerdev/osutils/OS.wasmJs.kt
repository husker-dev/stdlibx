package com.huskerdev.osutils

@OptIn(ExperimentalWasmJsInterop::class)
private val isNodeJs: Boolean =
    js("typeof window === 'undefined' && typeof process !== 'undefined'")

internal actual fun currentOS() = if(isNodeJs) OS.NODE_WASM else OS.WEB_WASM
internal actual fun currentArch() = Arch.UNKNOWN