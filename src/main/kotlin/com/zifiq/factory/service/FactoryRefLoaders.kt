package com.zifiq.factory.service

import com.zifiq.factory.model.CategoriesTable
import com.zifiq.factory.model.ManufacturersTable
import com.zifiq.factory.model.RecipesTable
import com.zifiq.factory.model.SpecialProductionClientsTable
import com.zifiq.factory.model.SuppliersTable
import com.zifiq.factory.model.UnitsOfMeasureTable
import com.zifiq.platform.directory.RefKind
import com.zifiq.platform.directory.TableRefLoader
import org.springframework.stereotype.Component

/** How references to the factory's records appear in responses (docs/03 §5.1.2). */
class FactoryRefLoaders {
    @Component
    class Units : TableRefLoader(RefKind.UNIT, UnitsOfMeasureTable, "code" to UnitsOfMeasureTable.code, "name" to UnitsOfMeasureTable.name, "symbol" to UnitsOfMeasureTable.symbol)

    @Component
    class Categories : TableRefLoader(RefKind.CATEGORY, CategoriesTable, "name" to CategoriesTable.name, "type" to CategoriesTable.type)

    @Component
    class Suppliers : TableRefLoader(RefKind.SUPPLIER, SuppliersTable, "code" to SuppliersTable.code, "name" to SuppliersTable.companyName)

    @Component
    class Manufacturers : TableRefLoader(RefKind.MANUFACTURER, ManufacturersTable, "code" to ManufacturersTable.code, "name" to ManufacturersTable.name)

    @Component
    class Recipes : TableRefLoader(RefKind.RECIPE, RecipesTable, "code" to RecipesTable.code, "name" to RecipesTable.name)

    @Component
    class SpecialProductionClients : TableRefLoader(RefKind.SPECIAL_PRODUCTION_CLIENT, SpecialProductionClientsTable, "name" to SpecialProductionClientsTable.name)
}
