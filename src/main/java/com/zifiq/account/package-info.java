/**
 * Finance & Accounting (docs/07). A substrate module: it depends on the OPEN platform, common and
 * inventory modules, on Customers' published spi (wallet funding and withdrawals) and on Settings'
 * (the company header on printed receipts); other modules use it only through its "spi" named interface.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform", "common", "inventory", "customers :: spi", "settings :: spi"})
package com.zifiq.account;
