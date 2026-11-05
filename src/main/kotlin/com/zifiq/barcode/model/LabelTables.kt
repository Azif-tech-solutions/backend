package com.zifiq.barcode.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/** A label print run (docs/11 §2.3). */
object LabelPrintRunsTable : UUIDTable("label_print_runs") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val labelSize = varchar("label_size", 10)
    val outputFormat = varchar("output_format", 4)
    val showPrice = bool("show_price").default(false)
    val priceBasis = varchar("price_basis", 10).nullable()
    val locationId = uuid("location_id").nullable()
    val totalLabels = integer("total_labels")
    val codesGenerated = integer("codes_generated").default(0)
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

/** One line of a print run: the snapshot of what was printed, and which code (docs/11 §2.3). */
object LabelPrintRunItemsTable : UUIDTable("label_print_run_items") {
    val tenantId = reference("tenant_id", TenantsTable)
    val runId = uuid("run_id")
    val stockItemId = uuid("stock_item_id")
    val codeId = uuid("code_id")
    val copies = integer("copies")
    val itemName = varchar("item_name", 200)
    val unitName = varchar("unit_name", 60)
    val price = decimal("price", 14, 2).nullable()
}
