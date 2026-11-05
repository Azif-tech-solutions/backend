package com.zifiq.communication.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** A monthly commission allocation run (docs/12 §2.2). */
object CommissionRunsTable : UUIDTable("commission_runs") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val beneficiaryType = varchar("beneficiary_type", 14)
    val periodMonth = date("period_month")
    val locationId = uuid("location_id").nullable()
    val status = varchar("status", 12)
    val totalCommission = decimal("total_commission", 14, 2)
    val beneficiaryCount = integer("beneficiary_count")
    val eligibleLines = integer("eligible_lines")
    val ineligibleLines = integer("ineligible_lines")
    val pendingConfirmationLines = integer("pending_confirmation_lines")
    val calculatedAt = timestamp("calculated_at")
    val runByUserId = reference("run_by_user_id", AppUsersTable)
    val approvedByUserId = reference("approved_by_user_id", AppUsersTable).nullable()
    val approvedAt = timestamp("approved_at").nullable()
    val cancelledReason = varchar("cancelled_reason", 300).nullable()
    val createdAt = timestamp("created_at")
}

/** One (beneficiary × product) line of a run. */
object CommissionRunLinesTable : UUIDTable("commission_run_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val runId = uuid("run_id")
    val beneficiaryId = uuid("beneficiary_id")
    val beneficiaryCode = varchar("beneficiary_code", 20)
    val beneficiaryName = varchar("beneficiary_name", 200)
    val region = varchar("region", 60).nullable()
    val stockItemId = uuid("stock_item_id")
    val itemCode = varchar("item_code", 40)
    val itemName = varchar("item_name", 200)
    val qtySold = decimal("qty_sold", 14, 3)
    val qtyReturned = decimal("qty_returned", 14, 3)
    val netQty = decimal("net_qty", 14, 3)
    val salesValue = decimal("sales_value", 14, 2)
    val rateId = uuid("rate_id").nullable()
    val rateType = varchar("rate_type", 10).nullable()
    val rate = decimal("rate", 14, 4).nullable()
    val unitCommission = decimal("unit_commission", 14, 4)
    val commission = decimal("commission", 14, 2)
    val eligible = bool("eligible")
    val ineligibleReason = varchar("ineligible_reason", 30).nullable()
    val lateFromPeriod = date("late_from_period").nullable()
}

/** Per-beneficiary totals of a run (what is payable). */
object CommissionRunBeneficiariesTable : UUIDTable("commission_run_beneficiaries") {
    val tenantId = reference("tenant_id", TenantsTable)
    val runId = uuid("run_id")
    val beneficiaryId = uuid("beneficiary_id")
    val beneficiaryCode = varchar("beneficiary_code", 20)
    val beneficiaryName = varchar("beneficiary_name", 200)
    val region = varchar("region", 60).nullable()
    val grossCommission = decimal("gross_commission", 14, 2)
    val carriedIn = decimal("carried_in", 14, 2)
    val earned = decimal("earned", 14, 2)
    val carriedOut = decimal("carried_out", 14, 2)
}

/** Once-only allocation guard: a sale/return line earns once per beneficiary type (docs/12 §2.2). */
object CommissionAllocatedLinesTable : Table("commission_allocated_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val beneficiaryType = varchar("beneficiary_type", 14)
    val sourceKind = varchar("source", 10)
    val saleLineId = uuid("sale_line_id")
    val runId = uuid("run_id")
    override val primaryKey = PrimaryKey(tenantId, beneficiaryType, sourceKind, saleLineId)
}
