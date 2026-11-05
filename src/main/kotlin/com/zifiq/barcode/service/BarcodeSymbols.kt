package com.zifiq.barcode.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.zifiq.common.errors.ApiErrors
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.springframework.stereotype.Component

/**
 * Renders a code to a scannable symbol with ZXing (docs/11 §4.5). 1D symbols (EAN/UPC/Code 128) are
 * wide; QR is square. The symbol is drawn at a high pixel density so it stays crisp when placed on a
 * label PDF at print size.
 */
@Component
class BarcodeSymbols {
    /** A PNG of the symbol, base64 data URI, for embedding in a label PDF. */
    fun dataUri(code: String, symbology: String): String =
        "data:image/png;base64," + Base64.getEncoder().encodeToString(png(code, symbology))

    fun png(code: String, symbology: String): ByteArray {
        val image = image(code, symbology)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", out)
        return out.toByteArray()
    }

    fun image(code: String, symbology: String): BufferedImage {
        val (format, w, h) = when (symbology) {
            "EAN_13" -> Triple(BarcodeFormat.EAN_13, 600, 300)
            "EAN_8" -> Triple(BarcodeFormat.EAN_8, 400, 300)
            "UPC_A" -> Triple(BarcodeFormat.UPC_A, 600, 300)
            "CODE_128" -> Triple(BarcodeFormat.CODE_128, 700, 250)
            "QR" -> Triple(BarcodeFormat.QR_CODE, 400, 400)
            else -> throw ApiErrors.invalidCodeFormat("cannot render $symbology")
        }
        val hints = mapOf(EncodeHintType.MARGIN to if (format == BarcodeFormat.QR_CODE) 1 else 6)
        val matrix = runCatching { MultiFormatWriter().encode(code, format, w, h, hints) }
            .getOrElse { throw ApiErrors.invalidCodeFormat("$code is not a valid $symbology symbol") }
        return MatrixToImageWriter.toBufferedImage(matrix)
    }
}
