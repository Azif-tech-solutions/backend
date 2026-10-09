package com.zifiq.inventory.service

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * The next free ITM-n code. Users may also type their own codes (e.g. "ITM-12"), so a generated
 * code skips any already in use rather than colliding with the unique (tenant, code) index.
 * Runs in the caller's transaction.
 */
@Component
class StockItemCodes(
    private val sequences: DocumentSequenceService,
    private val tenantContext: TenantContext,
) {
    fun next(): String = generateSequence { sequences.next("ITM") }.first { code ->
        StockItemsTable.selectAll().where { (StockItemsTable.tenantId eq tenantContext.requireTenantId()) and (StockItemsTable.code eq code) }.empty()
    }
}
