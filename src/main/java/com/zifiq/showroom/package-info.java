/**
 * Showroom (docs/05). Depends on the OPEN platform, common and inventory modules and on Customers'
 * published spi (dealer returns credit the customer's wallet). Other modules use it only through its
 * read-only "spi" named interface.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform", "common", "inventory", "customers :: spi"})
package com.zifiq.showroom;
