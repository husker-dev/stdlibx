@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx

private val END_LINE = if(OS.current == OS.WINDOWS) "\r\n" else "\n"

@Suppress("unused")
internal fun printErrorImpl(error: String?): Unit =
    js("typeof write !== 'undefined' ? process.stderr.write(error) : console.error(error)")

@Suppress("unused")
internal fun printlnErrorImpl(error: String?, endLine: String): Unit =
    js("typeof write !== 'undefined' ? process.stderr.write(error + endLine) : console.error(error)")

actual fun printError(message: Any?) =
    printErrorImpl(message?.toString())

actual fun printlnError(message: Any?) =
    printlnErrorImpl(message?.toString(), END_LINE)

actual fun printlnError() =
    printlnErrorImpl("", END_LINE)