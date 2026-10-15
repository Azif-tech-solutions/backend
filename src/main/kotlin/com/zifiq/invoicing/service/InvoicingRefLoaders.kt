package com.zifiq.invoicing.service

import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How references to invoices appear in responses (docs/03 §5.1.2). */
class InvoicingRefLoaders {
    @Component
    class Invoices : TableRefLoader(RefKind.INVOICE, InvoicesTable, "number" to InvoicesTable.number, "status" to InvoicesTable.status)
}
