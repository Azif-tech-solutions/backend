/**
 * Shared platform infrastructure (auth, tenancy, RBAC, notifications, numbering, OTP, the response
 * envelope, error types) that every business module depends on, analogous to a framework layer --
 * never the other way around. Declared OPEN rather than carved into per-package named interfaces
 * because that is its whole purpose: to be used freely by every other module.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.zifiq.platform;
