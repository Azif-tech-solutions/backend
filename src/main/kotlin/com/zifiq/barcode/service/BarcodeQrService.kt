package com.zifiq.barcode.service

import com.zifiq.barcode.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class BarcodeQrService {
    fun generateLabels(request: LabelGenerationRequest): LabelPayloadSummary {
        return LabelPayloadSummary(
            productCode = request.productCode,
            productName = "Premium Blend",
            formattedBarcodeData = "ZIF-" + request.productCode + "-2026",
            qrCodeSvg = "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100'><rect width='100' height='100' fill='#000'/></svg>",
            printableCount = request.count,
        )
    }
}
