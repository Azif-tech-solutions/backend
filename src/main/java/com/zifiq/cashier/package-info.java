/**
 * Sales &amp; POS (docs/09). A consumer module: it depends on the OPEN platform, common and inventory
 * modules and on the published spi of Customers (buyers, wallets, correspondents), Settings (the
 * receipt header), Finance &amp; Accounting (tills, receipts, VAT) and Electronic Invoicing (invoices).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform", "common", "inventory", "customers :: spi", "settings :: spi", "account :: spi", "invoicing :: spi"})
package com.zifiq.cashier;
