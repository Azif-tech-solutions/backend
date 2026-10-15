/**
 * Electronic Invoicing (docs/08). A substrate module: it depends on the OPEN platform, common and
 * inventory modules, on Customers', Settings' and Finance & Accounting's published spi (buyer
 * details, the issuer company header, VAT rates and the money ledger); other modules use it only
 * through its own "spi" named interface.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform", "common", "inventory", "customers :: spi", "settings :: spi", "account :: spi"})
package com.zifiq.invoicing;
