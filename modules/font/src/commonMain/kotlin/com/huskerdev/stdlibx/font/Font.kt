package com.huskerdev.stdlibx.font

import kotlinx.coroutines.flow.Flow

const val FONT_WEIGHT_THIN        = 100f
const val FONT_WEIGHT_EXTRA_LIGHT = 200f
const val FONT_WEIGHT_LIGHT       = 300f
const val FONT_WEIGHT_NORMAL      = 400f
const val FONT_WEIGHT_MEDIUM      = 500f
const val FONT_WEIGHT_SEMI_BOLD   = 600f
const val FONT_WEIGHT_BOLD        = 700f
const val FONT_WEIGHT_EXTRA_BOLD  = 800f
const val FONT_WEIGHT_BLACK       = 900f

const val FONT_WIDTH_ULTRA_CONDENSED = 0.5f
const val FONT_WIDTH_EXTRA_CONDENSED = 0.625f
const val FONT_WIDTH_CONDENSED       = 0.75f
const val FONT_WIDTH_SEMI_CONDENSED  = 0.875f
const val FONT_WIDTH_NORMAL          = 1f
const val FONT_WIDTH_SEMI_EXPANDED   = 1.125f
const val FONT_WIDTH_EXPANDED        = 1.25f
const val FONT_WIDTH_EXTRA_EXPANDED  = 1.5f
const val FONT_WIDTH_ULTRA_EXPANDED  = 2.0f

const val FONT_VAR_WIDTH = "wdth"
const val FONT_VAR_WEIGHT = "wght"
const val FONT_VAR_SLANT = "slnt"
const val FONT_VAR_ITALIC = "ital"
const val FONT_VAR_OPTICAL_SIZING = "opsz"

internal expect fun supportsSystemFontFacesImpl(): Boolean
internal expect suspend fun requestFontAccessImpl(): Boolean
internal expect fun systemFontFacesImpl(): Flow<FontFace>

class FontFace internal constructor(
    internal val handle: NativeFontFace
): AutoCloseable {
    companion object {
        fun supportsSystemFontFaces(): Boolean =
            supportsSystemFontFacesImpl()

        suspend fun requestFontAccess(): Boolean =
            requestFontAccessImpl()

        val systemFontFaces: Flow<FontFace>
            get() = systemFontFacesImpl()

        suspend fun init() {
            if(isLibStdlibxfontLoaded)
                return
            loadLibStdlibxfont()
            initFonts()
        }

        fun faceCountOf(bytes: ByteArray): Int =
            facesCountFromBytes(bytes)

        fun faceCountOf(path: String): Int =
            facesCountFromFile(path)
    }

    private val description by lazy { handle.description() }

    val faceIndex: Int
        get() = description.faceIndex

    val family: String
        get() = description.family

    val postScriptName: String
        get() = description.postScriptName

    val styleName: String
        get() = description.styleName

    val format: FontFormat
        get() = description.format

    val width: Float
        get() = description.width

    val italic: Boolean
        get() = description.italic

    val bold: Boolean
        get() = description.weight == FONT_WEIGHT_BOLD

    val weight: Float
        get() = description.weight

    val kerning: Boolean
        get() = description.kerning

    val slant: Float
        get() = description.slant

    val opticalSize: Float
        get() = description.opticalSize

    val features: Set<String> by lazy {
        description.features.map(::tagToString).toSet()
    }

    val variations: List<VariationAxis> by lazy {
        description.variations?.map {
            VariationAxis(
                key = tagToString(it.key),
                default = it.def,
                min = it.min,
                max = it.max
            )
        } ?: emptyList()
    }

    val isVariable: Boolean
        get() = description.hasVariations

    constructor(path: String, faceIndex: Int = 0): this(
        handle = createFontFaceFromFile(path, faceIndex)
            ?: throw NullPointerException("Could not create font: ${getLastError()}")) {
    }

    constructor(bytes: ByteArray, faceIndex: Int = 0): this(
        handle = createFontFaceFromBytes(bytes, faceIndex)
            ?: throw NullPointerException("Could not create font: ${getLastError()}")
    )

    fun deriveFont(
        size: Float,
        variationAxes: Map<String, Float> = emptyMap()
    ) = Font(this, size, variationAxes)

    override fun close() = handle.close()
}

class Font: AutoCloseable {
    val face: FontFace
    val size: Float
    internal val handle: NativeFont

    val variationsAxes: Map<String, Float>

    constructor(
        face: FontFace,
        size: Float,
        variationAxes: Map<String, Float>
    ) {
        this.handle = face.handle.createFont(
            size = size,
            variationKeys = variationAxes.map { stringToTag(it.key) }.toIntArray(),
            variationValues = variationAxes.map { it.value.toDouble() }.toDoubleArray()
        )
        this.face = face
        this.size = size
        this.variationsAxes = variationAxes
    }

    fun deriveFont(
        size: Float,
        variationAxes: Map<String, Float> = this.variationsAxes
    ) = Font(face, size, variationAxes)

    override fun close() = handle.close()
}

data class FontCollection(
    val fonts: List<Font>,
    val emojiFonts: List<Font> = emptyList()
) {
    private val flatten = fonts + emojiFonts
    private val flattenNative = flatten.map { it.handle }.toTypedArray()

    constructor(vararg fonts: Font): this(fonts.toList(), emptyList())

    private fun textLayout(text: String): IntArray {
        val parts = EmojiTextSplitter.split(text)
        val result = IntArray(parts.size * 3)
        for (i in parts.indices) {
            val part = parts[i]
            result[i*3] = part.utf8Offset
            result[i*3 + 1] = part.utf8Length
            result[i*3 + 2] = if(part.isEmoji) 1 else 0
        }
        return result
    }

    fun layoutInfo(
        text: String,
        features: Map<String, Boolean> = emptyMap(),
        textDirection: TextDirection = TextDirection.AUTO,
        language: String? = null
    ): Array<LayoutInfo> {
        val values = layoutInfo(
            fonts = flattenNative,
            defaultFontsCount = fonts.size,
            text = text,
            utf8TextLayout = textLayout(text),
            featureKeys = features.map { stringToTag(it.key) }.toIntArray(),
            featureValues = features.map { it.value }.toBooleanArray(),
            direction = TextDirection.entries[textDirection.ordinal],
            language = language
        )
        return Array(values.size / 6) {
            val rel = it * 6
            LayoutInfo(
                glyph = Glyph(values[rel].toInt(), values[rel + 1].toInt()),
                x = values[rel + 2],
                y = values[rel + 3],
                xAdvance = values[rel + 4],
                yAdvance = values[rel + 5],
            )
        }
    }

    fun measure(
        text: String,
        features: Map<String, Boolean> = emptyMap(),
        textDirection: TextDirection = TextDirection.AUTO,
        language: String? = null
    ): TextMetrics {
        val values = measure(
            fonts = flattenNative,
            defaultFontsCount = fonts.size,
            text = text,
            utf8TextLayout = textLayout(text),
            featureKeys = features.map { stringToTag(it.key) }.toIntArray(),
            featureValues = features.map { it.value }.toBooleanArray(),
            direction = textDirection,
            language = language
        )
        return TextMetrics(
            width = values[0],
            actualBoundingBoxLeft = values[1],
            actualBoundingBoxRight = values[2],
            actualBoundingBoxAscent = values[3],
            actualBoundingBoxDescent = values[4],
            fontBoundingBoxAscent = values[5],
            fontBoundingBoxDescent = values[6],
            hangingBaseline = values[7],
            alphabeticBaseline = 0.0,
            ideographicBaseline = values[8]
        )
    }

    fun renderGlyphs(glyphs: List<Glyph>, mode: RenderMode): Array<RenderedGlyph?>? {
        val glyphIds = IntArray(glyphs.size * 2)
        for(i in glyphs.indices) {
            val glyph = glyphs[i]
            glyphIds[i * 2] = glyph.fontIndex
            glyphIds[i * 2 + 1] = glyph.id
        }

        val nativeGlyphs = renderGlyphs(
            fonts = flattenNative,
            glyphs = glyphIds,
            mode = mode
        ) ?: return null

        return Array(nativeGlyphs.size) {
            val glyph = nativeGlyphs[it] ?: return@Array null
            RenderedGlyph(
                bytes = glyph.bytes,
                pixelMode = PixelMode.entries[glyph.pixelMode.ordinal],
                width = glyph.width,
                height = glyph.height,
                offsetX = glyph.offsetX,
                offsetY = glyph.offsetY
            )
        }
    }
}

data class VariationAxis(
    val key: String,
    val default: Float,
    val min: Float,
    val max: Float
)

data class Glyph(
    val fontIndex: Int,
    val id: Int
)

data class LayoutInfo(
    val glyph: Glyph,
    val x: Double,
    val y: Double,
    val xAdvance: Double,
    val yAdvance: Double
)

data class TextMetrics(
    val width: Double,
    val actualBoundingBoxLeft: Double,
    val actualBoundingBoxRight: Double,
    val actualBoundingBoxAscent: Double,
    val actualBoundingBoxDescent: Double,
    val fontBoundingBoxAscent: Double,
    val fontBoundingBoxDescent: Double,
    val hangingBaseline: Double,
    val alphabeticBaseline: Double,
    val ideographicBaseline: Double
)

data class RenderedGlyph(
    val bytes: ByteArray,
    val pixelMode: PixelMode,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RenderedGlyph

        if (width != other.width) return false
        if (height != other.height) return false
        if (offsetX != other.offsetX) return false
        if (offsetY != other.offsetY) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (pixelMode != other.pixelMode) return false
        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + offsetX
        result = 31 * result + offsetY
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + pixelMode.hashCode()
        return result
    }
}

private fun tagToString(tag: Int): String = buildString(4) {
    append(((tag ushr 24) and 0xFF).toChar())
    append(((tag ushr 16) and 0xFF).toChar())
    append(((tag ushr 8) and 0xFF).toChar())
    append((tag and 0xFF).toChar())
}

private fun stringToTag(value: String): Int =
    (value[0].code shl 24) or
    (value[1].code shl 16) or
    (value[2].code shl 8) or
    value[3].code