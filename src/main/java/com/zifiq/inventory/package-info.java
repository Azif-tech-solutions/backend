/**
 * The shared stock substrate (stock items, balances, the ledger-posting primitive) that Factory
 * and Showroom both depend on, analogous to how every module depends on platform. Declared OPEN
 * rather than carved into per-package named interfaces because that is its whole purpose.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.zifiq.inventory;
