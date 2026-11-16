@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.font

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.js.Promise

private fun supportsLocalFontsAccess(): Boolean =
    js("typeof window !== 'undefined' && typeof window.queryLocalFonts === 'function'")

private fun queryLocalFonts(): Promise<JsArray<FontData>> =
    js("window.queryLocalFonts()")

private external interface FontData: JsAny {
    fun blob(): Promise<Blob>
}

private external interface Blob: JsAny {
    fun arrayBuffer(): Promise<ArrayBuffer>
}

private external interface ArrayBuffer: JsAny

private external interface UInt8Array: JsAny {
    val length: Int
}

private fun toUInt8Array(buffer: ArrayBuffer): UInt8Array =
    js("new Uint8Array(buffer)")

private fun byteAt(array: UInt8Array, index: Int): Int =
    js("array[index]")

private fun UInt8Array.toByteArray(): ByteArray =
    ByteArray(length) { byteAt(this, it).toByte() }

internal actual fun supportsSystemFontFacesImpl(): Boolean =
    supportsLocalFontsAccess()

internal actual suspend fun requestFontAccessImpl(): Boolean {
    if(!supportsLocalFontsAccess())
        return false
    return try {
        queryLocalFonts().await()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        false
    }
}

internal actual fun systemFontFacesImpl(): Flow<FontFace> {
    if(!supportsLocalFontsAccess())
        return emptyFlow()

    return flow {
        val localFonts = try {
            queryLocalFonts().await().toArray()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return@flow
        }

        for (fontData in localFonts) {
            val face = try {
                val blob = fontData.blob().await()
                val buffer = blob.arrayBuffer().await()
                val bytes = toUInt8Array(buffer).toByteArray()
                currentCoroutineContext().ensureActive()
                FontFace(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                continue
            }
            emit(face)
        }
    }
}