@file:Suppress("OPT_IN_USAGE", "unused")

package com.huskerdev.stdlibx.fs.nodejs

import kotlin.js.Promise

internal external interface NodeOs : JsAny {
    fun homedir(): String
    fun tmpdir(): String
    fun platform(): String
}

internal external interface NodeFs : JsAny {
    val constants: NodeFsConstants
    val promises: NodeFsPromises
    fun statSync(path: String, options: NodeStatOptions): NodeStats
    fun lstatSync(path: String, options: NodeStatOptions): NodeStats
    fun fstatSync(fd: Int, options: NodeStatOptions): NodeStats
    fun statfsSync(path: String, options: NodeStatOptions): NodeStatFs
    fun readFileSync(path: String, encoding: String): String
    fun readFileSync(path: String): NodeBuffer
    fun writeFileSync(path: String, data: NodeBuffer)
    fun writeFileSync(path: String, data: NodeBuffer, options: NodeWriteOptions)
    fun readdirSync(path: String): JsArray<JsString>
    fun realpathSync(path: String): String
    fun readlinkSync(path: String): String
    fun symlinkSync(target: String, path: String, type: String)
    fun mkdirSync(path: String, options: NodeMkdirOptions)
    fun unlinkSync(path: String)
    fun rmdirSync(path: String)
    fun renameSync(source: String, target: String)
    fun copyFileSync(source: String, target: String, flags: Int)
    fun chmodSync(path: String, mode: Int)
    fun accessSync(path: String, mode: Int)
    fun utimesSync(path: String, access: Double, modification: Double)
    fun lutimesSync(path: String, access: Double, modification: Double)
    fun openSync(path: String, flags: String): Int
    fun openSync(path: String, flags: Int): Int
    fun openSync(path: String, flags: String, mode: Int): Int
    fun closeSync(fd: Int)
    fun readSync(fd: Int, buffer: NodeBuffer, offset: Int, length: Int, position: Double?): Int
    fun writeSync(fd: Int, buffer: NodeBuffer, offset: Int, length: Int, position: Double?): Int
}

internal external interface NodePath : JsAny {
    fun resolve(path: String): String
}

internal external interface NodeFsConstants {
    val O_WRONLY: Int
    val O_TRUNC: Int
    val O_APPEND: Int
    val O_CREAT: Int
    val O_EXCL: Int
    val COPYFILE_EXCL: Int
    val R_OK: Int
    val W_OK: Int
    val X_OK: Int
}

internal external interface NodeStatOptions {
    val bigint: Boolean
}

internal external interface NodeMkdirOptions {
    val mode: Int
}

/** fs.BigIntStats: all integer fields retain their full precision across JS and Wasm. */
internal external interface NodeStats {
    val mode: NodeBigInt
    val size: NodeBigInt
    val dev: NodeBigInt
    val ino: NodeBigInt
    val atimeNs: NodeBigInt
    val mtimeNs: NodeBigInt
    val birthtimeNs: NodeBigInt
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun isSymbolicLink(): Boolean
}

internal external interface NodeStatFs {
    val type: NodeBigInt
    val bsize: NodeBigInt
    val blocks: NodeBigInt
    val bavail: NodeBigInt
    val bfree: NodeBigInt
}

internal external interface NodeBigInt {
    fun toString(radix: Int): String
}

internal fun NodeBigInt.toLong(): Long = toString(10).toLong()

internal external interface NodeBufferModule : JsAny {
    val Buffer: NodeBufferConstructor
}

internal external interface NodeBufferConstructor {
    fun alloc(size: Int): NodeBuffer
}

internal external interface NodeBuffer : JsAny {
    val length: Int
    fun readUInt8(offset: Int): Int
    fun writeUInt8(value: Int, offset: Int): Int
}

internal external interface NodeProcess {
    fun <T : JsAny> getBuiltinModule(name: String): T
}

internal external interface NodeError {
    val code: String?
    val message: String?
}

internal external interface NodeFsPromises {
    fun readFile(path: String, options: NodeFileOptions): Promise<NodeBuffer>
    fun writeFile(path: String, data: NodeBuffer, options: NodeFileOptions): Promise<JsAny?>
}

internal external interface NodeFileOptions {
    val signal: JsAny
    val flag: Int
}

internal external interface NodeWriteOptions {
    val flag: Int
}

internal external interface NodeAbortController {
    val signal: JsAny
    fun abort()
}

internal fun nodeProcess(): NodeProcess = js("process")
internal fun nodeOs(): NodeOs = nodeProcess().getBuiltinModule("os")
internal fun nodeFs(): NodeFs = nodeProcess().getBuiltinModule("fs")
internal fun nodePath(): NodePath = nodeProcess().getBuiltinModule("path")
internal fun nodeBufferModule(): NodeBufferModule = nodeProcess().getBuiltinModule("buffer")
internal fun nodeBuffer(): NodeBufferConstructor = nodeBufferModule().Buffer
internal fun statOptions(): NodeStatOptions = js("({bigint: true})")
internal fun mkdirOptions(mode: Int): NodeMkdirOptions = js("({mode})")
internal fun multiplyBigInts(left: NodeBigInt, right: NodeBigInt): NodeBigInt = js("left * right")
internal fun nodeError(value: JsAny?): NodeError? = js("value && typeof value === 'object' ? value : null")
internal fun nodeAbortController(): NodeAbortController = js("new AbortController()")
internal fun nodeFileOptions(signal: JsAny, flag: Int = 0): NodeFileOptions = js("({signal, flag})")
internal fun nodeWriteOptions(flag: Int): NodeWriteOptions = js("({flag})")