package com.zifiq.cashier.service

import com.zifiq.cashier.model.CustomerWaybillsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How a sale and a customer waybill appear when a response references them (docs/03 §5.1.2). */
class CashierRefLoaders {
    @Component
    class Sales : TableRefLoader(RefKind.SALE, SalesTable, "ref" to SalesTable.ref, "status" to SalesTable.status, "customer" to SalesTable.customerName)

    @Component
    class CustomerWaybills : TableRefLoader(RefKind.CUSTOMER_WAYBILL, CustomerWaybillsTable, "ref" to CustomerWaybillsTable.ref, "status" to CustomerWaybillsTable.status)
}
