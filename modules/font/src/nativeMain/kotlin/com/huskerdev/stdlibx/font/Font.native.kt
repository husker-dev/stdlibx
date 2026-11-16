package com.huskerdev.stdlibx.font

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal actual fun supportsSystemFontFacesImpl(): Boolean = true

internal actual suspend fun requestFontAccessImpl(): Boolean = true

internal actual fun systemFontFacesImpl(): Flow<FontFace> =
    flowOf(*getSystemFontFaces().map { FontFace(it) }.toTypedArray())