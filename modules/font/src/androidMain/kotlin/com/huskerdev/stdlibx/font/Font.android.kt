package com.huskerdev.stdlibx.font

import android.graphics.fonts.SystemFonts
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

internal actual fun supportsSystemFontFacesImpl(): Boolean = true

internal actual suspend fun requestFontAccessImpl(): Boolean = true

internal actual fun systemFontFacesImpl(): Flow<FontFace> = flow {
    if(Build.VERSION.SDK_INT >= 29) {
        val seen = mutableSetOf<Pair<String, Int>>()

        for (font in SystemFonts.getAvailableFonts()) {
            currentCoroutineContext().ensureActive()

            val file = font.file ?: continue
            val key = file.absolutePath to font.ttcIndex
            if (!seen.add(key)) continue

            val face = try {
                FontFace(key.first, key.second)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                continue
            }
            emit(face)
        }
    } else {
        listOf(
            "/system/fonts/",
            "/system/font/",
            "/data/fonts/",
            "/system/product/fonts/"
        ).flatMap {
            File(it).walk().mapNotNull { file ->
                if (file.isDirectory) null
                else file.absolutePath
            }
        }.forEach { path ->
            for (faceIndex in 0 until FontFace.faceCountOf(path)) {
                val fontFace = try {
                    FontFace(path, faceIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
                emit(fontFace)
            }
        }
    }
}

