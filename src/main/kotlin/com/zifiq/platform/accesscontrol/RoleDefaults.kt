package com.zifiq.platform.accesscontrol

/**
 * Per docs/01-user-tenant-access-control.md §2: the eight PRD-defined roles seeded for every new
 * tenant (root, via AuthService.registerTenant, and child, via TenantService.createChild). Every
 * module that adds permission codes to permission_catalog extends this map's relevant entries as
 * part of its own migration/doc — this file is never redesigned, only extended.
 */
object RoleDefaults {
    const val ADMINISTRATOR = "Administrator"
    const val MANAGEMENT = "Management"
    const val ACCOUNTS_PERSONNEL = "Accounts Personnel"
    const val STOCK_KEEPER = "Stock Keeper"
    const val QUALITY_CONTROL = "Quality Control"
    const val CASHIER = "Cashier"
    const val CORRESPONDENT = "Correspondent"
    const val DEALER = "Dealer"

    /** Sentinel meaning "every code currently in permission_catalog," resolved at seed time. */
    const val ALL_PERMISSIONS = "*"

    val ROLE_DEFAULTS: Map<String, Set<String>> = mapOf(
        ADMINISTRATOR to setOf(ALL_PERMISSIONS),
        MANAGEMENT to setOf(
            "inventory.stock.read",
            "factory.costing.read",
            "factory.costing.approve",
            "showroom.stock.read",
            "accounting.read",
            "cashier.sales.read",
            "settings.views.share",
            "platform.activity.read",
        ),
        ACCOUNTS_PERSONNEL to setOf(
            "accounting.read",
            "accounting.manage",
            "accounting.vat.manage",
            "cashier.sales.read",
        ),
        STOCK_KEEPER to setOf(
            "inventory.stock.read",
            "inventory.stock.manage",
            "inventory.adjustments.manage",
            "inventory.transfers.manage",
            "showroom.stock.read",
            "showroom.stock.manage",
        ),
        QUALITY_CONTROL to setOf(
            "inventory.stock.read",
            "factory.production.manage",
            "factory.recipes.manage",
        ),
        CASHIER to setOf(
            "cashier.sales.read",
            "cashier.sales.manage",
        ),
        // Correspondent and Dealer default to no platform-level permission codes yet — their
        // read-only, self-service capabilities land with the Customers module.
        CORRESPONDENT to emptySet(),
        DEALER to emptySet(),
    )
}
