/**
 * Settings (docs/06): company profile, logo, onboarding, and the screens over platform-owned module
 * settings and custom-field definitions. A substrate module: it depends only on the OPEN platform
 * and common modules, and other modules use it only through its "spi" named interface.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform", "common"})
package com.zifiq.settings;
