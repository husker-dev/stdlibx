package com.huskerdev.stdlibx.font

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Paths
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.io.path.absolutePathString
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test

class WindowTest {

    @Test
    fun test() {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        JFrame().apply {
            runBlocking { FontFace.init() }

            val systemFonts = runBlocking {
                FontFace.systemFontFaces.toList()
                    .sortedBy { it.postScriptName }
                    .toTypedArray()
            }

            var fontFace = FontFace(Paths.get("InterVariable.woff2").absolutePathString())
            val emojiFace = systemFonts.first { it.family.lowercase().contains("emoji") }
            val arabicFace = systemFonts.first { it.family.lowercase().contains("geeza pro") }
            val chineseFace = systemFonts.first { it.family.lowercase().contains("heiti tc") }

            var fontScale = 1.0
            var fontText = "\uD83E\uDD81 Sample AVß; 1/3, 0, 1.5"
            var fontSize = 28f
            val variations = fontFace.variations.associate { it.key to it.default }.toMutableMap()
            val features = hashMapOf<String, Boolean>()

            var fontCollection = FontCollection(
                fonts = listOf(
                    fontFace.deriveFont(fontSize),
                    arabicFace.deriveFont(fontSize),
                    chineseFace.deriveFont(fontSize)
                ),
                emojiFonts = listOf(
                    emojiFace.deriveFont(fontSize),
                )
            )
            var renderedText: BufferedImage? = null

            val updateLabelText = {
                val scaledFontSize = (fontSize * fontScale).toFloat()
                fontCollection = FontCollection(
                    fonts = fontCollection.fonts.map { font ->
                        font.deriveFont(
                            size = scaledFontSize,
                            variationAxes = variations
                        ).also { font.close() }
                    },
                    emojiFonts = fontCollection.emojiFonts.map { font ->
                        font.deriveFont(scaledFontSize).also { font.close() }
                    }
                )

                renderedText = renderText(fontCollection, features, fontText)
                repaint()
            }
            updateLabelText()

            add(object: JPanel() {
                override fun paintComponent(g: Graphics?) {
                    super.paintComponent(g)
                    if(renderedText == null) return
                    g as Graphics2D

                    val dpi = g.transform.scaleX
                    if(fontScale != dpi) {
                        fontScale = dpi
                        updateLabelText()
                    }
                    val scaledWidth = width * dpi
                    val scaledHeight = height * dpi
                    g.scale(1 / dpi, 1 / dpi)
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

                    g.drawImage(
                        renderedText,
                        AffineTransform.getTranslateInstance(
                            (scaledWidth - renderedText.width) / 2,
                            (scaledHeight - renderedText.height) / 2
                        ),
                        null
                    )
                    g.scale(dpi, dpi)
                }
            })
            add(JScrollPane(JPanel().apply {
                layout = BorderLayout()
                border = BorderFactory.createMatteBorder(0, 1, 0, 0, Color.lightGray)

                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.PAGE_AXIS)

                    fun option(name: String, component: JComponent) {
                        add(JPanel().apply {
                            layout = FlowLayout(FlowLayout.CENTER, 5, 0)
                            add(JLabel("$name:").apply {
                                preferredSize = Dimension(55, 25)
                            })
                            add(component.apply {
                                preferredSize = Dimension(220, 25)
                            })
                        })
                    }
                    fun slider(min: Float, max: Float, value: Float, callback: (Float) -> Unit) = JSlider(
                        (min * 1000f).toInt(),
                        (max * 1000f).toInt(),
                        (value * 1000f).toInt()
                    ).apply {
                        addChangeListener {
                            callback(this.value / 1000f)
                            updateLabelText()
                        }
                    }
                    fun textField(text: String, callback: (String) -> Unit) = JTextField(text).also { field ->
                        field.document.addDocumentListener(object: DocumentListener {
                            private fun update() { callback(field.text); updateLabelText() }
                            override fun insertUpdate(e: DocumentEvent?) { update() }
                            override fun removeUpdate(e: DocumentEvent?) { update() }
                            override fun changedUpdate(e: DocumentEvent?) { update() }
                        })
                    }
                    fun multiButton(
                        elements: List<String>,
                        selected: Int,
                        callback: (Int) -> Unit
                    ) = JPanel().also { panel ->
                        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
                        elements.forEachIndexed { i, element ->
                            panel.add(JButton(element).also { btn ->
                                btn.isEnabled = selected != i
                                btn.isFocusable = false
                                btn.addActionListener {
                                    panel.components.forEachIndexed { index, component ->
                                        component.isEnabled = index != i
                                    }
                                    callback(i)
                                    updateLabelText()
                                }
                            })
                        }
                    }
                    fun <T> combobox(
                        items: Array<T>,
                        name: (T) -> String,
                        selected: T,
                        callback: (T) -> Unit
                    ) = JComboBox(items.map(name).toTypedArray()).also { combobox ->
                        if(selected in items)
                            combobox.selectedIndex = items.indexOf(selected)
                        combobox.addItemListener {
                            callback(items[combobox.selectedIndex])
                            updateLabelText()
                        }
                    }
                    fun updateControls() {
                        removeAll()
                        option("text", textField(fontText) { fontText = it })
                        option("face", combobox(
                            items = systemFonts,
                            name = { it.postScriptName },
                            selected = fontFace
                        ) {
                            fontFace = it
                            fontCollection = FontCollection(
                                fonts = fontCollection.fonts.toMutableList().apply { set(0, fontFace.deriveFont(fontSize)) },
                                emojiFonts = fontCollection.emojiFonts
                            )
                            SwingUtilities.invokeLater { updateControls() }
                        })
                        option("format", JLabel(fontFace.format.name))
                        option("style", JLabel(fontFace.styleName))
                        option("weight", JLabel(
                            "${fontFace.weight} (${when {
                                fontFace.weight <= FONT_WEIGHT_THIN -> "thin"
                                fontFace.weight <= FONT_WEIGHT_EXTRA_LIGHT -> "extra-light"
                                fontFace.weight <= FONT_WEIGHT_LIGHT -> "light"
                                fontFace.weight <= FONT_WEIGHT_NORMAL -> "normal"
                                fontFace.weight <= FONT_WEIGHT_MEDIUM -> "medium"
                                fontFace.weight <= FONT_WEIGHT_SEMI_BOLD -> "semi-bold"
                                fontFace.weight <= FONT_WEIGHT_BOLD -> "bold"
                                fontFace.weight <= FONT_WEIGHT_EXTRA_BOLD -> "extra-bold"
                                else -> "black"
                            }})"
                        ))
                        option("width", JLabel(
                            "${fontFace.width} (${when {
                                fontFace.width <= FONT_WIDTH_ULTRA_CONDENSED -> "ultra-condensed"
                                fontFace.width <= FONT_WIDTH_EXTRA_CONDENSED -> "extra-condensed"
                                fontFace.width <= FONT_WIDTH_CONDENSED -> "condensed"
                                fontFace.width <= FONT_WIDTH_SEMI_CONDENSED -> "semi-condensed"
                                fontFace.width <= FONT_WIDTH_NORMAL -> "normal"
                                fontFace.width <= FONT_WIDTH_SEMI_EXPANDED -> "semi-expanded"
                                fontFace.width <= FONT_WIDTH_EXPANDED -> "expanded"
                                fontFace.width <= FONT_WIDTH_EXTRA_EXPANDED -> "extra-expanded"
                                else -> "ultra-expanded"
                            }})"
                        ))
                        option("kerning", JLabel(if(fontFace.kerning) "yes" else "no"))
                        option("italic", JLabel(if(fontFace.italic) "yes" else "no"))
                        option("slant", JLabel(fontFace.slant.toString()))
                        option("optical", JLabel(fontFace.opticalSize.toString()))
                        option("size", slider(1f, 200f, fontSize) { fontSize = it })

                        fontFace.variations.forEach { variation ->
                            option(variation.key, slider(
                                min = variation.min,
                                max = variation.max,
                                value = variation.default
                            ) { variations[variation.key] = it })
                        }
                        fontFace.features.forEach { feature ->
                            option(feature, multiButton(
                                elements = listOf("off", "auto", "on"),
                                selected = 1,
                            ) {
                                when (it) {
                                    0 -> features[feature] = false
                                    1 -> features.remove(feature)
                                    2 -> features[feature] = true
                                }
                            })
                        }

                        revalidate()
                        repaint()
                    }

                    updateControls()
                }, BorderLayout.NORTH)
            }).also {
                it.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
                it.verticalScrollBar.unitIncrement = 10
                it.border = null
            }, BorderLayout.EAST)

            size = Dimension(800, 500)
            setLocationRelativeTo(null)
            isVisible = true

            val lock = Object()
            addWindowListener(object: WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) {
                    synchronized (lock) { lock.notifyAll() }
                }
            })
            synchronized(lock) { lock.wait() }
        }
    }

    private fun renderText(
        fontCollection: FontCollection,
        features: Map<String, Boolean>,
        text: String,
    ): BufferedImage {
        // Native offset/length count UTF-8 bytes, not Kotlin UTF-16 code units.
        val layout = fontCollection.layoutInfo(text, features = features)
        val glyphs = layout.map { it.glyph }.distinct()
        val renderedGlyphs = fontCollection.renderGlyphs(glyphs, RenderMode.NORMAL)!!

        val images = glyphs.indices.associate { i ->
            val renderedGlyph = renderedGlyphs[i]
            val image = if (renderedGlyph == null || renderedGlyph.width == 0 || renderedGlyph.height == 0) null else {
                BufferedImage(renderedGlyph.width, renderedGlyph.height, BufferedImage.TYPE_INT_ARGB_PRE).apply {
                    val pixels = (raster.dataBuffer as DataBufferInt).data
                    when (renderedGlyph.pixelMode) {
                        PixelMode.GRAY -> {
                            for (i in pixels.indices)
                                pixels[i] = (renderedGlyph.bytes[i].toInt() and 0xFF) shl 24
                        }

                        PixelMode.BGRA -> {
                            // FreeType BGRA is already premultiplied: write packed pixels
                            // directly rather than using setRGB, which expects straight alpha.
                            for (i in pixels.indices) {
                                val offset = i * 4
                                val b = renderedGlyph.bytes[offset].toInt() and 0xFF
                                val g = renderedGlyph.bytes[offset + 1].toInt() and 0xFF
                                val r = renderedGlyph.bytes[offset + 2].toInt() and 0xFF
                                val a = renderedGlyph.bytes[offset + 3].toInt() and 0xFF
                                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }

                        PixelMode.LCD, PixelMode.LCD_V -> error("Unexpected LCD bitmap in NORMAL mode")
                    }
                }
            }
            glyphs[i] to (image to renderedGlyph)
        }

        // Use actual raster bounds (including bearings), not just advance or outline bounds.
        // Include pen movement to preserve leading/trailing spaces and the baseline origin.
        var left = 0.0
        var top = 0.0
        var right = 0.0
        var bottom = 0.0
        val positioned = layout.mapNotNull { info ->
            val (image, renderedGlyph) = images[info.glyph]!!
            val x = info.x + (renderedGlyph?.offsetX?.toDouble() ?: 0.0)
            val y = info.y + (renderedGlyph?.offsetY?.toDouble() ?: 0.0)
            val x2 = x + info.xAdvance
            val y2 = y + info.yAdvance
            left = minOf(left, x2)
            right = maxOf(right, x2)
            top = minOf(top, y2)
            bottom = maxOf(bottom, y2)
            if (image == null) null else {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + image.width)
                bottom = maxOf(bottom, y + image.height)
                Triple(image, x, y)
            }
        }
        val originX = floor(left)
        val originY = floor(top)
        val result = BufferedImage(
            (ceil(right) - originX).toInt().coerceAtLeast(1),
            (ceil(bottom) - originY).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB_PRE
        )
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for ((image, x, y) in positioned) {
                graphics.drawImage(image, AffineTransform.getTranslateInstance(x - originX, y - originY), null)
            }
        } finally {
            graphics.dispose()
        }
        return result
    }
}
