package com.zifiq.barcode.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val CONTEXTS =
    "LOOKUP|STOCK_REFILL|RAW_MATERIAL_RECEIPT|STOCK_TRANSFER|QUANTITY_ADJUSTMENT|RECEIVE_PRODUCT|POS|PRO_FORMA|WAYBILL|DIRECT_ENTRY|OUTSOURCE_RECEIPT|CUSTOMER_RETURN"

/** Resolve one scanned code in the context of the screen that scanned it (docs/11 §4.2). */
data class ScanRequest(
    @field:NotBlank @field:Size(max = 400) val code: String,
    @field:Pattern(regexp = CONTEXTS) val context: String,
    val locationId: UUID? = null,
    val customerId: UUID? = null,
    @field:Pattern(regexp = "SCANNER|CAMERA|MANUAL") val inputMethod: String = "SCANNER",
    @field:Size(max = 60) val clientRef: String? = null,
)

/** Resolve many codes at once (stocktake-style rapid scanning or an offline queue). */
data class BatchScanRequest(
    @field:Pattern(regexp = CONTEXTS) val context: String,
    val locationId: UUID? = null,
    val customerId: UUID? = null,
    @field:NotEmpty @field:Size(max = 200) val codes: List<@NotBlank String>,
)

/** The resolved item; `stockItem` is a reference (its id is what the screen submits), never a bare id. */
data class ScannedItem(
    val stockItem: Ref,
    val unit: String,
    val status: String,
    val symbology: String,
    val category: String?,
)

data class ScanPrice(val basis: String, val unitPrice: BigDecimal, val retailPrice: BigDecimal)

data class ScanStock(val location: Ref, val onHand: BigDecimal, val available: BigDecimal)

/** One scan's resolution (docs/11 §4.2). `item` is null only for NOT_RECOGNISED / MISREAD. */
data class ScanResolution(
    val outcome: String,
    val blockReason: String?,
    val code: String,
    val item: ScannedItem?,
    val packQuantity: BigDecimal?,
    val packLabel: String?,
    val price: ScanPrice?,
    val stock: ScanStock?,
    /** NOT_RECOGNISED: the caller may register the item (holds inventory.stock.manage). */
    val canRegister: Boolean = false,
    /** NOT_RECOGNISED in a DIRECT_ENTRY context: the item can be registered inline. */
    val canRegisterInline: Boolean = false,
    val message: String,
)

/** One scan-log line (docs/11 §4.4). */
data class ScanLogRow(
    val time: Instant,
    val code: String,
    val result: String,
    /** "CODE · Name" or "—". */
    val resolvedTo: String,
    /** The context label the scan happened in. */
    val scannedIn: String,
    val scannedBy: UserRef?,
    val location: Ref?,
    val note: String?,
)

data class UnrecognisedTop(val code: String, val count: Long)

/** Scan-log summary stats (docs/11 §4.4). */
data class ScanLogStats(
    val scans: Long,
    val matchRate: BigDecimal,
    val notRecognised: Long,
    val blocked: Long,
    val misread: Long,
    val topUnrecognised: List<UnrecognisedTop>,
)

/** A repeatedly-unrecognised code worth registering (docs/11 §9). */
data class UnrecognisedCodeRow(
    val code: String,
    val count: Long,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val contexts: List<String>,
    val canRegister: Boolean,
)
