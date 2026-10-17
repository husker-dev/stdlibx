package com.huskerdev.stdlibx

actual fun printError(message: Any?) {
    System.err.print(message)
}

actual fun printlnError(message: Any?) {
    System.err.println(message)
}

actual fun printlnError() {
    System.err.println()
}