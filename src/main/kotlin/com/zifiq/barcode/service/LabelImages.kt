package com.zifiq.barcode.service

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.springframework.stereotype.Component

/**
 * Composes a single-label preview PNG (docs/11 §4.5) the same way the PDF lays one out: product
 * name, the symbol, `code · unit`, and an optional price. An item with no code yet shows the
 * "generated on save" placeholder instead of a symbol.
 */
@Component
class LabelImages {
    fun preview(size: String, name: String, code: String?, symbology: String?, sub: String, priceText: String?, symbol: BufferedImage?): ByteArray {
        val (w, h) = when (size) { "40x25" -> 320 to 200; "100x50" -> 800 to 400; "A4_24" -> 560 to 296; else -> 464 to 320 }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK

        val margin = w / 20
        g.font = Font("SansSerif", Font.BOLD, h / 14)
        drawCentered(g, name.take(40), w, margin + g.fontMetrics.ascent)

        val symTop = h / 6
        val symH = h / 2
        if (symbol != null) {
            val scale = minOf((w - 2 * margin).toDouble() / symbol.width, symH.toDouble() / symbol.height)
            val sw = (symbol.width * scale).toInt()
            val sh = (symbol.height * scale).toInt()
            g.drawImage(symbol, (w - sw) / 2, symTop, sw, sh, null)
        } else {
            g.font = Font("SansSerif", Font.ITALIC, h / 16)
            g.color = Color.GRAY
            drawCentered(g, "code generated on save", w, symTop + symH / 2)
            g.color = Color.BLACK
        }

        g.font = Font("SansSerif", Font.PLAIN, h / 18)
        drawCentered(g, sub, w, symTop + symH + g.fontMetrics.ascent + 4)
        if (priceText != null) {
            g.font = Font("SansSerif", Font.BOLD, h / 12)
            drawCentered(g, priceText, w, h - margin)
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    private fun drawCentered(g: java.awt.Graphics2D, text: String, width: Int, y: Int) {
        val x = (width - g.fontMetrics.stringWidth(text)) / 2
        g.drawString(text, maxOf(x, 2), y)
    }
}
