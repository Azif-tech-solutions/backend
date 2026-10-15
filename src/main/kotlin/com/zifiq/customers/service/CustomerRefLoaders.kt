package com.zifiq.customers.service

import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.model.DealerCategoriesTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How references to customers' records appear in responses (docs/03 §5.1.2). */
class CustomerRefLoaders {
    @Component
    class Customers : TableRefLoader(RefKind.CUSTOMER, CustomersTable, "code" to CustomersTable.code, "name" to CustomersTable.name, "type" to CustomersTable.type)

    @Component
    class Correspondents : TableRefLoader(RefKind.CORRESPONDENT, CorrespondentsTable, "code" to CorrespondentsTable.code, "name" to CorrespondentsTable.name)

    @Component
    class DealerCategories : TableRefLoader(RefKind.DEALER_CATEGORY, DealerCategoriesTable, "name" to DealerCategoriesTable.name)
}
