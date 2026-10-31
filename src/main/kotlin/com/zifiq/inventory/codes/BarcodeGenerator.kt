package com.zifiq.inventory.codes

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.settings.ModuleSettings
import org.springframework.stereotype.Service

/**
 * Generates product codes (docs/11 §4.1). EAN-13 numbers come from the tenant's GS1 company prefix
 * when set, otherwise the restricted-circulation range (20–29) so they never collide with a real
 * manufacturer's product. QR/Code 128 payloads carry no URL or price, so a printed label stays valid
 * when prices or domains change. Runs in the caller's transaction (sequences are tenant-scoped).
 */
@Service
class BarcodeGenerator(
    private val moduleSettings: ModuleSettings,
    private val sequences: DocumentSequenceService,
) {
    data class GeneratedCode(val code: String, val symbology: String)

    /** The symbology generated for an item kind when the caller doesn't ask for a specific one. */
    fun defaultSymbology(itemType: String): String =
        if (itemType == "IN_HOUSE_RECIPE") moduleSettings.string(BarcodeSettings.RECIPE_SYMBOLOGY)
        else moduleSettings.string(BarcodeSettings.DEFAULT_GOODS_SYMBOLOGY)

    fun generate(itemCode: String, symbology: String): GeneratedCode = when (symbology) {
        "EAN_13" -> GeneratedCode(generateEan13(), "EAN_13")
        "QR" -> GeneratedCode(generateQr(itemCode), "QR")
        "CODE_128" -> GeneratedCode(("ZIQ$itemCode").take(48), "CODE_128")
        else -> throw ApiErrors.invalidCodeFormat("$symbology cannot be generated")
    }

    private fun generateEan13(): String {
        val prefix = moduleSettings.string(BarcodeSettings.GS1_COMPANY_PREFIX).trim()
            .ifEmpty { moduleSettings.string(BarcodeSettings.RESTRICTED_PREFIX).trim() }
        val width = 12 - prefix.length
        if (width < 1) throw ApiErrors.invalidCodeFormat("GS1 prefix is too long to leave room for an item reference")
        val reference = sequences.next("EAN").substringAfterLast('-')
        if (reference.length > width) throw ApiErrors.codeSpaceExhausted()
        return CheckDigits.withCheckDigit(prefix + reference.padStart(width, '0'))
    }

    private fun generateQr(itemCode: String): String {
        val seq = sequences.next("QR").substringAfterLast('-').padStart(4, '0')
        return "ZIQ-$itemCode-$seq"
    }
}
