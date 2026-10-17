@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx

private fun nodePlatform(): String = js("process.getBuiltinModule(\"os\").platform()")
private fun nodeArch(): String = js("process.getBuiltinModule(\"os\").arch()")

internal actual fun currentOS(): OS = when (EnvironmentKind.current) {
    EnvironmentKind.WEB -> OS.UNKNOWN
    EnvironmentKind.NODEJS -> when(nodePlatform()) {
        "win32" -> OS.WINDOWS
        "darwin" -> OS.MACOS
        "linux" -> OS.LINUX
        else -> OS.UNKNOWN
    }
    else -> throw UnsupportedOperationException()
}

internal actual fun currentArch(): Arch = when (EnvironmentKind.current) {
    EnvironmentKind.WEB -> Arch.UNKNOWN
    EnvironmentKind.NODEJS -> when(nodeArch()) {
        "x64" -> Arch.X64
        "arm64" -> Arch.ARM64
        "ia32" -> Arch.X86
        "arm" -> Arch.ARM32
        "riscv64" -> Arch.RISCV64
        else -> Arch.UNKNOWN
    }
    else -> throw UnsupportedOperationException()
}