package com.huskerdev.stdlibx


internal actual fun currentOS(): OS {
    return ContextProvider.applicationContext?.let { context ->
        when {
            context.packageManager.hasSystemFeature("android.hardware.type.watch") -> OS.WEAROS
            context.packageManager.hasSystemFeature("android.hardware.type.television") -> OS.ANDROID_TV
            else -> OS.ANDROID
        }
    } ?: OS.ANDROID
}

internal actual fun currentArch(): Arch {
    return System.getProperty("os.arch").lowercase().run {
        when {
            matches("^(x8632|x86|i[3-6]86|ia32|x32)$".toRegex()) -> Arch.X86
            matches("^(x8664|amd64|ia32e|em64t|x64)$".toRegex()) -> Arch.X64
            matches("^(arm|arm32)$".toRegex()) -> Arch.ARM32
            equals("aarch64") -> Arch.ARM64
            matches("^(riscv|riscv32)$".toRegex()) -> Arch.RISCV32
            equals("riscv64") -> Arch.RISCV64
            else -> Arch.UNKNOWN
        }
    }
}