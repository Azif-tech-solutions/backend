package com.zifiq.barcode.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.zifiq.common.errors.ApiErrors
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.springframework.stereotype.Component

/**
 * Decodes the barcode(s) in an uploaded still image with ZXing (docs/11 §4.3) — the camera fallback
 * when live in-browser decoding isn't available. Returns the decoded text(s); resolution then runs
 * exactly as a typed scan. The image is never stored.
 */
@Component
class BarcodeImageDecoder {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE,
        ),
    )

    fun decode(bytes: ByteArray): List<String> {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: throw ApiErrors.unsupportedImageType()
        if (image.width > 4000 || image.height > 4000) throw ApiErrors.imageTooLarge()
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.map { r -> r.text } }
        return runCatching { MultiFormatReader().apply { setHints(hints) }.decodeWithState(bitmap) }.getOrNull()
            ?.let { listOf(it.text) } ?: throw ApiErrors.noCodeInImage()
    }
}
