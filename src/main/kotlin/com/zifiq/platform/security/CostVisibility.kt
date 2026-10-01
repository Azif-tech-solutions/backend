package com.zifiq.platform.security

import org.springframework.stereotype.Component

/**
 * Explicit cost/value-field masking helper, called at DTO-construction time in service methods
 * for fields the PRD says must "display as zero" (not be omitted) for unauthorized roles —
 * e.g. landing/unit cost in the Comprehensive Stock Report. Deliberately a plain function, not a
 * Jackson annotation/serializer, so every masked field is visible at its call site. See
 * docs/00-platform-foundation.md §6.3.
 */
@Component
class CostVisibility {
    fun <T> maskIfUnauthorized(value: T, visible: Boolean, zero: T): T = if (visible) value else zero
}
