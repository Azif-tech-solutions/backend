package com.zifiq.platform.accesscontrol

/**
 * Per docs/implemented/01-user-tenant-access-control.md §2: the eight PRD-defined roles seeded for every new
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
            "customers.read",
            "customers.reclassify",
            "customers.correspondents.manage",
            "customers.ledger.read",
            "platform.documents.read",
            "inventory.pricing.read",
            "inventory.drivers.read",
            "showroom.returns.read",
            "accounting.running-costs.approve",
        ),
        ACCOUNTS_PERSONNEL to setOf(
            "accounting.read",
            "accounting.vat.manage",
            "accounting.bank.manage",
            "accounting.cash-accounts.manage",
            "accounting.expense-categories.manage",
            "accounting.fx.manage",
            "accounting.petty-cash.fund",
            "accounting.petty-cash.pay",
            "accounting.petty-cash.respond",
            "accounting.running-costs.submit",
            "accounting.funds.send",
            "accounting.funds.confirm",
            "accounting.lodgements.manage",
            "accounting.lodgements.confirm",
            "cashier.sales.read",
            "customers.read",
            "customers.manage",
            "customers.dealers.manage",
            "customers.ledger.read",
            "platform.documents.read",
            "inventory.pricing.read",
            "showroom.stock.read",
            "showroom.returns.read",
        ),
        STOCK_KEEPER to setOf(
            "inventory.stock.read",
            "inventory.stock.manage",
            "inventory.adjustments.manage",
            "inventory.transfers.manage",
            "showroom.stock.read",
            "showroom.stock.manage",
            "platform.documents.read",
            "inventory.pricing.read",
            "inventory.drivers.read",
            "inventory.drivers.manage",
            "showroom.intake.manage",
            "showroom.returns.manage",
            "showroom.returns.read",
        ),
        QUALITY_CONTROL to setOf(
            "inventory.stock.read",
            "factory.production.manage",
            "factory.recipes.manage",
        ),
        CASHIER to setOf(
            "accounting.lodgements.manage",
            "cashier.sales.read",
            "cashier.sales.manage",
            "customers.read",
            "customers.manage",
            "platform.documents.read",
            "inventory.pricing.read",
            "inventory.drivers.read",
            "showroom.stock.read",
            "showroom.returns.manage",
            "showroom.returns.read",
        ),
        // Correspondents see only their own customers; dealers only their own account (PRD §7).
        CORRESPONDENT to setOf("customers.read.own"),
        DEALER to setOf("customers.self.read"),
    )
}
