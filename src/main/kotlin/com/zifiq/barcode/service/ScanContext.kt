package com.zifiq.barcode.service

/** Kinds a showroom can sell (docs/11 §3.2 — raw materials are not sellable at POS/pro-forma). */
private val SELLABLE = setOf("FINISHED_PRODUCT", "IN_HOUSE_RECIPE", "OUTSOURCED", "DIRECT_ENTRY")

/**
 * Each scan point (docs/11 §3.2): the permission the caller must hold (same as opening the manual
 * screen), the item kinds it may act on, whether the item must be ACTIVE, whether a price is shown
 * and required, and whether it is location-bound (runs LocationAccessService).
 */
enum class ScanContext(
    val permission: String,
    val allowedKinds: Set<String>,
    val activeOnly: Boolean,
    val showsPrice: Boolean,
    val requiresPrice: Boolean,
    val locationBound: Boolean,
) {
    LOOKUP("barcode.lookup", emptySet(), activeOnly = false, showsPrice = true, requiresPrice = false, locationBound = false),
    STOCK_REFILL("inventory.stock.manage", setOf("FINISHED_PRODUCT", "IN_HOUSE_RECIPE"), true, false, false, false),
    RAW_MATERIAL_RECEIPT("inventory.stock.manage", setOf("RAW_MATERIAL"), true, false, false, false),
    STOCK_TRANSFER("inventory.transfers.manage", emptySet(), true, false, false, false),
    QUANTITY_ADJUSTMENT("inventory.adjustments.manage", emptySet(), true, false, false, false),
    RECEIVE_PRODUCT("inventory.transfers.manage", emptySet(), true, false, false, true),
    POS("cashier.sales.manage", SELLABLE, true, true, true, false),
    PRO_FORMA("cashier.pro-forma.manage", SELLABLE, true, true, true, false),
    WAYBILL("cashier.waybill.manage", emptySet(), true, false, false, false),
    DIRECT_ENTRY("showroom.intake.manage", setOf("DIRECT_ENTRY"), true, false, false, true),
    OUTSOURCE_RECEIPT("showroom.intake.manage", setOf("OUTSOURCED"), true, false, false, false),
    CUSTOMER_RETURN("showroom.returns.manage", setOf("FINISHED_PRODUCT", "OUTSOURCED", "DIRECT_ENTRY"), activeOnly = false, false, false, false),
    ;

    /** POS/pro-forma call a wrong kind "not sellable"; everywhere else it is a wrong-kind block. */
    fun wrongKindReason(): String = if (this == POS || this == PRO_FORMA) "NOT_SELLABLE" else "WRONG_KIND"
}
