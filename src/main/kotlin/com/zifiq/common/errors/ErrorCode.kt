package com.zifiq.common.errors

/**
 * Every error code the API can return, as a closed, typed vocabulary — not a raw string anywhere
 * else in the codebase. `com.zifiq.common.errors.ApiErrors` pairs each code with its HTTP status
 * and message (and `com.zifiq.platform.api.GlobalExceptionHandler` for the handful of
 * framework-level codes it maps directly). Jackson serializes an enum as its constant name by
 * default, so the wire format (`{"error":{"code":"USER_EXISTS",...}}`) is unchanged by this being
 * an enum instead of a `String`.
 */
enum class ErrorCode {
    // ---- Framework-level (GlobalExceptionHandler maps these directly, no ApiErrors entry) ----
    VALIDATION_FAILED,
    FORBIDDEN,
    INTERNAL_ERROR,
    NOT_FOUND,
    UNAUTHORIZED,

    // ---- Authentication ----
    USER_EXISTS,
    INVALID_CREDENTIALS,
    NO_TENANT_MEMBERSHIP,
    INVALID_TENANT,
    TENANT_SWITCH_FORBIDDEN,
    USER_NOT_FOUND,
    INVALID_REFRESH_TOKEN,
    EXPIRED_REFRESH_TOKEN,
    INVALID_CURRENT_PASSWORD,
    INVALID_RESET_TOKEN,
    EXPIRED_RESET_TOKEN,
    INVALID_AUTHENTICATION,
    INVALID_USER,
    INVALID_TOKEN_SIGNATURE,
    TOKEN_EXPIRED,
    INVALID_TOKEN,

    // ---- Tenancy ----
    TENANT_CREATION_FORBIDDEN,
    TENANT_REQUIRED,

    // ---- Members & Roles ----
    ROLE_ASSIGNMENT_FORBIDDEN,
    UNKNOWN_PERMISSIONS,
    USER_ALREADY_MEMBER,
    ROLE_NOT_FOUND,
    MEMBER_NOT_FOUND,

    // ---- OTP ----
    CHALLENGE_NOT_FOUND,
    CHALLENGE_NOT_AWAITING,
    NOT_CHALLENGE_OWNER,
    OTP_EXPIRED,
    INVALID_OTP_CODE,
    OTP_REQUIRED,

    // ---- Factory Setup ----
    FACTORY_NOT_FOUND,

    // ---- Factory Trading ----
    TRADE_OFFER_NOT_FOUND,
    TRADE_ALREADY_DECIDED,

    // ---- Factory Production ----
    RECIPE_NOT_FOUND,
    MATERIALS_REQUIRED,
    COSTING_NOT_FOUND,
    ALREADY_DECIDED,
    COSTING_NOT_APPROVED,
    SPECIAL_RUN_NOT_FOUND,

    // ---- Inventory ----
    STOCK_ITEM_NOT_FOUND,
    ITEM_SUSPENDED,
    RECEIPT_NOT_FOUND,
    INSUFFICIENT_STOCK,
    TRANSFER_NOT_FOUND,
    TRANSFER_LINE_NOT_FOUND,

    // ---- Tax Classification / VAT Control ----
    UNKNOWN_VAT_CATEGORY,
    VAT_CATEGORY_NOT_ACTIVE,
    UNKNOWN_HSN_SECTION,
    UNKNOWN_ISIC_SECTION,

    // ---- Location Access ----
    LOCATION_ACCESS_DENIED,

    // ---- Search, filters, paging, saved views (docs/03 §4–§5) ----
    INVALID_FILTER,
    FILTER_TOO_COMPLEX,
    INVALID_SORT_FIELD,
    PAGE_SIZE_TOO_LARGE,
    SEARCH_TIMEOUT,
    VIEW_NOT_FOUND,
}
