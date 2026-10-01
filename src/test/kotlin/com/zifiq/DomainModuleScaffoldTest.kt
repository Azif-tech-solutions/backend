package com.zifiq

import com.zifiq.showroom.dto.DirectStockEntryRequest
import com.zifiq.showroom.service.ShowroomInventoryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Factory module scaffold assertions moved to FactoryModuleIntegrationTest — the module now
 * persists via Exposed (docs/02-factory.md) rather than returning hardcoded mock data, so it's
 * exercised as a real Testcontainers integration test instead of a no-arg-constructor unit test.
 */
class DomainModuleScaffoldTest {

    @Test
    fun `showroom inventory service records incoming stock`() {
        val service = ShowroomInventoryService()

        val result = service.recordDirectEntry(
            DirectStockEntryRequest(
                warehouseCode = "SHW-001",
                productCode = "P-1001",
                quantity = 12.0,
                unit = "pcs",
                source = "factory"
            )
        )

        assertEquals("P-1001", result.productCode)
        assertEquals(12.0, result.quantity)
    }
}