package com.zifiq.inventory.service

import com.zifiq.inventory.model.DriversTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockTransfersTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How references to inventory's records appear in responses (docs/03 §5.1.2). */
class InventoryRefLoaders {
    @Component
    class StockItems : TableRefLoader(RefKind.STOCK_ITEM, StockItemsTable, "code" to StockItemsTable.code, "name" to StockItemsTable.name, "itemType" to StockItemsTable.itemType)

    @Component
    class Drivers : TableRefLoader(RefKind.DRIVER, DriversTable, "name" to DriversTable.name, "phone" to DriversTable.phone)

    @Component
    class StockTransfers : TableRefLoader(RefKind.STOCK_TRANSFER, StockTransfersTable, "ref" to StockTransfersTable.waybillRef)
}
