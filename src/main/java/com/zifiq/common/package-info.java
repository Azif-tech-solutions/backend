/**
 * The single catalog of every error the API can throw (ErrorCode + ApiErrors). Every module
 * references these by name instead of constructing exceptions inline, so this is deliberately
 * OPEN rather than carved into per-package named interfaces.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.zifiq.common;
