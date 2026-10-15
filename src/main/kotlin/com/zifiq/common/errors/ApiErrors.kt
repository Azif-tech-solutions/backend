package com.zifiq.common.errors

import com.zifiq.platform.api.ApiException
import java.util.UUID
import org.springframework.http.HttpStatus

/**
 * Single catalog of every error the API can throw — paired with [ErrorCode], the closed enum of
 * every code. Controllers and services never construct `ApiException` inline and never use
 * try/catch — they throw a named error from here, and
 * `com.zifiq.platform.api.GlobalExceptionHandler` (a `@RestControllerAdvice`) is the only place
 * that turns a thrown exception into an HTTP response. Adding a new error means adding one
 * function here (and, if it's a genuinely new error, one constant to [ErrorCode]) — never a new
 * catch block anywhere else.
 */
object ApiErrors {
    // ---- Framework-level (referenced only by GlobalExceptionHandler) ----
    fun validationFailed(details: Map<String, String> = emptyMap()) =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", details)
    fun accessDenied() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You do not have permission to perform this action")
    fun unauthenticated() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "A valid access token is required")
    fun internalError() = ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred")
    fun noRouteFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "No route matches this request")
    fun methodNotAllowed(method: String, supported: Collection<String>) = ApiException(
        HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED, "$method is not supported here", mapOf("supported" to supported.sorted().joinToString(", ")),
    )
    fun unsupportedMediaType(sent: String?, supported: Collection<String>) = ApiException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "This endpoint takes ${supported.joinToString(" or ")}" + (sent?.let { ", not $it" } ?: ""), mapOf("supported" to supported.joinToString(", ")),
    )
    fun notMultipart() = validationFailed(mapOf("body" to "Expected a multipart/form-data body with the file in it"))

    // ---- Authentication ----
    fun userExists() = ApiException(HttpStatus.CONFLICT, ErrorCode.USER_EXISTS, "A user with this email address already exists")
    fun invalidCredentials() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid email or password")
    fun noTenantMembership() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.NO_TENANT_MEMBERSHIP, "User is not associated with any active tenant")
    fun notMemberOfRequestedTenant() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.INVALID_TENANT, "User is not a member of the requested tenant")
    fun tenantSwitchForbidden() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.TENANT_SWITCH_FORBIDDEN, "You are not a member of the requested target tenant")
    fun userNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, "User record not found")
    fun invalidRefreshToken() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid")
    fun expiredRefreshToken() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.EXPIRED_REFRESH_TOKEN, "Refresh token has expired or been revoked")
    fun invalidCurrentPassword() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CURRENT_PASSWORD, "The current password provided is incorrect")
    fun invalidResetToken() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_RESET_TOKEN, "Password reset token is invalid")
    fun expiredResetToken() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.EXPIRED_RESET_TOKEN, "Password reset token has expired or already been used")
    fun invalidAuthentication() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_AUTHENTICATION, "A verified access token is required")
    fun invalidUserInToken() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_USER, "The access token subject is invalid")
    fun invalidTokenSignature() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN_SIGNATURE, "JWT token signature validation failed")
    fun tokenExpired() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED, "JWT token has expired")
    fun invalidToken() = ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN, "Malformed or invalid access token")

    // ---- Tenancy ----
    fun tenantCreationForbidden() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.TENANT_CREATION_FORBIDDEN, "You cannot create a child tenant")
    fun tenantRequired() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.TENANT_REQUIRED, "The access token has no active tenant")
    fun malformedTenantClaim() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.INVALID_TENANT, "The active tenant identifier is invalid")

    // ---- Members & Roles ----
    fun roleAssignmentForbidden() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.ROLE_ASSIGNMENT_FORBIDDEN, "You cannot assign roles to this user in the active tenant")
    fun unknownPermissions(unknown: Set<String>) = ApiException(
        HttpStatus.BAD_REQUEST,
        ErrorCode.UNKNOWN_PERMISSIONS,
        "One or more permission codes are not recognized",
        mapOf("permissions" to unknown.sorted().joinToString(",")),
    )
    fun userAlreadyMember() = ApiException(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_MEMBER, "This user is already a member of the active tenant")
    fun roleNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.ROLE_NOT_FOUND, "Role not found in the active tenant")
    fun memberNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND, "Member not found in the active tenant")

    // ---- OTP ----
    fun otpChallengeNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CHALLENGE_NOT_FOUND, "OTP challenge not found")
    fun otpChallengeNotAwaiting() = ApiException(HttpStatus.CONFLICT, ErrorCode.CHALLENGE_NOT_AWAITING, "This OTP challenge has already been issued, used, or expired")
    fun notOtpChallengeOwner() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_CHALLENGE_OWNER, "You did not request this OTP challenge")
    fun otpExpired() = ApiException(HttpStatus.GONE, ErrorCode.OTP_EXPIRED, "This OTP challenge has expired or was never issued")
    fun invalidOtpCode() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_OTP_CODE, "The OTP code provided is incorrect")
    fun otpRequiredForAdjustment() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.OTP_REQUIRED, "This adjustment exceeds the threshold and requires a verified OTP challenge")
    fun otpNotVerified() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.OTP_REQUIRED, "The provided OTP challenge has not been verified for this action")

    // ---- Factory Setup ----
    fun factoryNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.FACTORY_NOT_FOUND, "Factory not found")

    // ---- Factory Trading ----
    fun tradeOfferNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.TRADE_OFFER_NOT_FOUND, "Trade offer not found")
    fun tradeAlreadyDecided() = ApiException(HttpStatus.CONFLICT, ErrorCode.TRADE_ALREADY_DECIDED, "This trade offer has already been responded to or has expired")
    fun insufficientStockForTrade() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_STOCK, "Source factory no longer has sufficient stock for this trade")

    // ---- Factory Production ----
    fun recipeNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RECIPE_NOT_FOUND, "Recipe not found")
    fun materialsRequired() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MATERIALS_REQUIRED, "Provide either materials or a recipeId")
    fun materialNotFound(stockItemId: UUID) = ApiException(HttpStatus.NOT_FOUND, ErrorCode.STOCK_ITEM_NOT_FOUND, "Material $stockItemId not found")
    fun costingNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.COSTING_NOT_FOUND, "Production costing not found")
    fun costingAlreadyDecided() = ApiException(HttpStatus.CONFLICT, ErrorCode.ALREADY_DECIDED, "This costing has already been approved or rejected")
    fun costingNotApproved() = ApiException(HttpStatus.CONFLICT, ErrorCode.COSTING_NOT_APPROVED, "This costing has not been approved")
    fun specialProductionRunNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.SPECIAL_RUN_NOT_FOUND, "Special production run not found")
    fun insufficientStockForProduction(stockItemId: UUID) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock of material $stockItemId to complete this production run")

    // ---- Inventory Stock ----
    fun stockItemNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.STOCK_ITEM_NOT_FOUND, "Stock item not found")
    fun stockItemSuspended() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.ITEM_SUSPENDED, "This stock item is suspended and cannot be posted against")
    fun stockReceiptNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RECEIPT_NOT_FOUND, "Stock receipt not found")

    // ---- Inventory Operations ----
    fun insufficientStockForTransfer(stockItemId: UUID) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for item $stockItemId")
    fun transferNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.TRANSFER_NOT_FOUND, "Stock transfer not found")
    fun transferLineNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.TRANSFER_LINE_NOT_FOUND, "Transfer line not found")
    fun adjustmentExceedsBalance() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_STOCK, "Adjustment quantity exceeds current balance")

    // ---- Tax Classification / VAT Control ----
    fun unknownVatCategories(unknown: Set<String>) = ApiException(
        HttpStatus.BAD_REQUEST,
        ErrorCode.UNKNOWN_VAT_CATEGORY,
        "One or more VAT category codes are not recognized",
        mapOf("codes" to unknown.sorted().joinToString(",")),
    )
    fun vatCategoryNotActive() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VAT_CATEGORY_NOT_ACTIVE, "This VAT category has not been activated for the active tenant (see VAT Control)")
    fun unknownHsnSection() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_HSN_SECTION, "Unknown HSN section")
    fun unknownIsicSection() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_ISIC_SECTION, "Unknown ISIC section")

    // ---- Location Access ----
    fun locationAccessDenied() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.LOCATION_ACCESS_DENIED, "You are not assigned to this location")

    // ---- Search, filters, paging, saved views ----
    fun invalidFilter(field: String, reason: String, allowed: Collection<String> = emptyList()) = ApiException(
        HttpStatus.BAD_REQUEST,
        ErrorCode.INVALID_FILTER,
        "Invalid filter on '$field': $reason",
        buildMap {
            put("field", field)
            put("reason", reason)
            if (allowed.isNotEmpty()) put("allowed", allowed.sorted().joinToString(","))
        },
    )
    fun filterTooComplex(limit: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FILTER_TOO_COMPLEX, "The filter is too complex ($limit)")
    fun invalidSortField(field: String, allowed: Collection<String>) = ApiException(
        HttpStatus.BAD_REQUEST,
        ErrorCode.INVALID_SORT_FIELD,
        "Cannot sort by '$field'",
        mapOf("field" to field, "allowed" to allowed.sorted().joinToString(",")),
    )
    fun pageSizeTooLarge(max: Int) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PAGE_SIZE_TOO_LARGE, "Page size must be between 1 and $max")
    fun searchTimeout() = ApiException(HttpStatus.REQUEST_TIMEOUT, ErrorCode.SEARCH_TIMEOUT, "The search took too long — narrow the filters or date range and try again")
    fun viewNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.VIEW_NOT_FOUND, "Saved view not found")
    fun documentNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.DOCUMENT_NOT_FOUND, "Document not found")
    fun storageNotConfigured() = ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.STORAGE_NOT_CONFIGURED, "File storage is not configured on this server")
    fun fileNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.FILE_NOT_FOUND, "File not found")
    fun fileTooLarge(maxBytes: Long) = ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.FILE_TOO_LARGE, "The file is larger than ${maxBytes / (1024 * 1024)} MB", mapOf("maxBytes" to maxBytes.toString()))
    fun fileTypeNotAllowed(detected: String, allowed: Collection<String>) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FILE_TYPE_NOT_ALLOWED, "This kind of file ($detected) can't be uploaded here", mapOf("detected" to detected, "allowed" to allowed.joinToString(",")))
    fun unknownFilePurpose(purpose: String, allowed: Collection<String>) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_FILE_PURPOSE, "Unknown file purpose '$purpose'", mapOf("allowed" to allowed.joinToString(",")))

    // ---- Showroom + inventory extensions (docs/05 §7) ----
    fun receiptExceedsSent(line: Int, outstanding: java.math.BigDecimal, attempted: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RECEIPT_EXCEEDS_SENT,
        "Line $line: accepted plus rejected (${attempted.stripTrailingZeros().toPlainString()}) exceeds the ${outstanding.stripTrailingZeros().toPlainString()} still outstanding",
        mapOf("line" to line.toString(), "outstanding" to outstanding.toPlainString(), "attempted" to attempted.toPlainString()))
    fun receiptNotReconciled(line: Int, outstanding: java.math.BigDecimal, attempted: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RECEIPT_NOT_RECONCILED,
        "Line $line: ${(outstanding - attempted).stripTrailingZeros().toPlainString()} of the ${outstanding.stripTrailingZeros().toPlainString()} dispatched is unaccounted for",
        mapOf("line" to line.toString(), "outstanding" to outstanding.toPlainString(), "attempted" to attempted.toPlainString()))
    fun sameLocation(reason: String = "Source and destination are the same location; move stock between its areas with an area move") =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCode.SAME_LOCATION, reason)
    fun notSameLocation() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_SAME_LOCATION, "Both areas must belong to the same showroom or factory")
    fun locationNotTrading(name: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.LOCATION_NOT_TRADING, "$name is pending opening: it can receive stock but cannot trade, dispatch or take returns yet")
    fun locationSuspended(name: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.LOCATION_SUSPENDED, "$name is suspended: no stock can move in or out")
    fun areaHasStock(units: java.math.BigDecimal) = ApiException(HttpStatus.CONFLICT, ErrorCode.AREA_HAS_STOCK, "This area still holds ${units.stripTrailingZeros().toPlainString()} units; move them first", mapOf("units" to units.toPlainString()))
    fun itemLocationRestricted() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.ITEM_LOCATION_RESTRICTED, "This direct-entry item is held only at the showroom that entered it")
    fun itemNotOutsourced() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.ITEM_NOT_OUTSOURCED, "Only registered outsourced products can be received here; for third-party goods not in the register use Stock Entry (Direct)")
    fun outsourceCostNotSet() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OUTSOURCE_COST_NOT_SET, "Set this outsourced product's landing cost before receiving it")
    fun invalidExpiry() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_EXPIRY, "The expiry date must be after the received date")
    fun noAdjustmentNeeded() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.NO_ADJUSTMENT_NEEDED, "The counted quantity equals the quantity on hand; nothing to adjust")
    fun notADealer() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_A_DEALER, "A dealer return needs an Authorised Dealer")
    fun useDealerReturn() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.USE_DEALER_RETURN, "This customer is an Authorised Dealer; record a dealer return instead")
    fun priceNotSet(item: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PRICE_NOT_SET, "$item has no selling price on that date; publish one in the price list first", mapOf("item" to item))
    fun returnNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RETURN_NOT_FOUND, "Return not found")
    fun returnAlreadyReversed() = ApiException(HttpStatus.CONFLICT, ErrorCode.RETURN_ALREADY_REVERSED, "This return has already been reversed")
    fun groupMixedUnits(units: Collection<String>) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.GROUP_MIXED_UNITS, "The group's items use different units (${units.joinToString()}), so their quantities can't be added together", mapOf("units" to units.joinToString(",")))
    fun dealerPriceAboveRetail(item: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.DEALER_PRICE_ABOVE_RETAIL, "$item: the dealer price can't be above the retail price", mapOf("item" to item))
    fun priceAlreadySetForDate(item: String, date: java.time.LocalDate) = ApiException(HttpStatus.CONFLICT, ErrorCode.PRICE_ALREADY_SET_FOR_DATE, "$item already has a price effective $date", mapOf("item" to item, "effectiveFrom" to date.toString()))
    fun priceAlreadyEffective() = ApiException(HttpStatus.CONFLICT, ErrorCode.PRICE_ALREADY_EFFECTIVE, "This price is already in effect and can't be changed; publish a new price instead")
    fun showroomNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.SHOWROOM_NOT_FOUND, "Showroom not found")
    fun driverNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.DRIVER_NOT_FOUND, "Driver not found")
    fun stockEntryNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.STOCK_ENTRY_NOT_FOUND, "Stock entry not found")
    fun duplicateViewName() = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_VIEW_NAME, "You already have a view with this name for this list")

    // ---- Module settings, custom fields, notifications ----
    fun settingKeyUnknown(key: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.SETTING_KEY_UNKNOWN, "Unknown setting '$key'", mapOf("key" to key))
    fun settingValueInvalid(key: String, reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.SETTING_VALUE_INVALID, "Invalid value for '$key': $reason", mapOf("key" to key, "reason" to reason))
    fun invalidCustomField(field: String, reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CUSTOM_FIELD, "Custom field '$field': $reason", mapOf("field" to field, "reason" to reason))
    fun notificationNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found")

    // ---- Generic, reused by later modules ----
    fun duplicateName(name: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_NAME, "'$name' is already in use", mapOf("name" to name))
    fun duplicateCode(code: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_CODE, "The code '$code' is already in use", mapOf("code" to code))
    fun duplicateRecord() = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_RECORD, "A record with these details already exists")
    fun valueNotAllowed() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", mapOf("body" to "A value is too long or not allowed"))
    fun referencedRecordMissing() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", mapOf("body" to "A referenced record does not exist"))
    fun invalidStatusTransition(from: String, to: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION, "Cannot change status from $from to $to", mapOf("from" to from, "to" to to))
    fun notConvertible(status: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION, "Only an active temporary walk-in customer can be registered this way; this one is $status", mapOf("from" to status, "to" to "REGISTERED"))
    fun invalidLocation(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_LOCATION, reason)

    // ---- Customers & Distributors ----
    fun customerNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found")
    fun customerNotActive(status: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.CUSTOMER_NOT_ACTIVE, "This customer is $status and cannot be transacted with", mapOf("status" to status))
    fun customerIsTemporary() = ApiException(HttpStatus.CONFLICT, ErrorCode.CUSTOMER_IS_TEMPORARY, "Register this temporary customer properly before reclassifying")
    fun insufficientWalletBalance(available: java.math.BigDecimal, requested: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_WALLET_BALANCE, "The wallet balance is not enough", mapOf("available" to available.toPlainString(), "requested" to requested.toPlainString()))
    fun insufficientDepositBalance(available: java.math.BigDecimal, requested: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_DEPOSIT_BALANCE, "The deposit balance is not enough", mapOf("available" to available.toPlainString(), "requested" to requested.toPlainString()))
    fun balanceTypeNotAllowed(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BALANCE_TYPE_NOT_ALLOWED, reason)
    fun categoryRequiredForDealer() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CATEGORY_REQUIRED_FOR_DEALER, "Authorised dealers need a dealer category (and only dealers may have one)")
    fun creditLimitNotAllowed() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CREDIT_LIMIT_NOT_ALLOWED, "No-credit customers cannot have a credit limit")
    fun dealerCategoryNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.DEALER_CATEGORY_NOT_FOUND, "Dealer category not found or inactive")
    fun categoryInUse(count: Long) = ApiException(HttpStatus.CONFLICT, ErrorCode.CATEGORY_IN_USE, "$count active dealer(s) use this category", mapOf("dealers" to count.toString()))
    fun reclassificationSameType() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RECLASSIFICATION_SAME_TYPE, "The customer already has this type")
    fun reclassificationPending() = ApiException(HttpStatus.CONFLICT, ErrorCode.RECLASSIFICATION_PENDING, "A reclassification is already scheduled for this customer")
    fun reclassificationNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RECLASSIFICATION_NOT_FOUND, "Scheduled reclassification not found")
    fun depositMustBeSettled() = ApiException(HttpStatus.CONFLICT, ErrorCode.DEPOSIT_MUST_BE_SETTLED, "Draw or refund the deposit before moving this customer away from Special Customer")
    fun correspondentNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CORRESPONDENT_NOT_FOUND, "Correspondent not found")
    fun correspondentNotAssigned() = ApiException(HttpStatus.CONFLICT, ErrorCode.CORRESPONDENT_NOT_ASSIGNED, "Only an assigned correspondent can be linked to customers")
    fun invalidRegion(allowed: List<String>) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REGION, "Unknown region", mapOf("allowed" to allowed.joinToString(",")))
    fun userAlreadyLinked() = ApiException(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_LINKED, "This user is already linked to another record")
    fun invalidUser(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_USER, reason)
    fun invalidStateCode() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_STATE_CODE, "Unknown state code")
    fun invalidLgaCode() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_LGA_CODE, "Unknown LGA code, or the LGA is not in the selected state")
    fun alreadyReversed() = ApiException(HttpStatus.CONFLICT, ErrorCode.ALREADY_REVERSED, "This entry has already been reversed")
    fun reverseInSourceModule(module: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.REVERSE_IN_SOURCE_MODULE, "Reverse this entry by voiding its source document in $module", mapOf("module" to module))
    fun phoneRequired() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PHONE_REQUIRED, "A phone number is required")
    fun ledgerEntryNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.LEDGER_ENTRY_NOT_FOUND, "Ledger entry not found")

    // ---- Settings (docs/06 §7) ----
    fun invalidLogo(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_LOGO, reason)
    fun logoNotSet() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.LOGO_NOT_SET, "No company logo has been uploaded")
    fun onboardingIncomplete(missing: List<String>) = ApiException(HttpStatus.CONFLICT, ErrorCode.ONBOARDING_INCOMPLETE, "Complete the company details before finishing onboarding", mapOf("missing" to missing.joinToString(",")))
    fun optionInUse(option: String, count: Long) = ApiException(HttpStatus.CONFLICT, ErrorCode.OPTION_IN_USE, "The option '$option' is used by $count record(s) and can't be removed", mapOf("option" to option, "count" to count.toString()))
    fun fieldHasBlanks(count: Long) = ApiException(HttpStatus.CONFLICT, ErrorCode.FIELD_HAS_BLANKS, "$count record(s) have no value for this field, so it can't be made required yet", mapOf("count" to count.toString()))
    /** DUPLICATE_NAME with the existing field's id, so "Add custom field" can reuse it (docs/06 §4.5). */
    fun duplicateCustomField(label: String, existingId: java.util.UUID) = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_NAME, "A custom field called '$label' already exists", mapOf("name" to label, "existingId" to existingId.toString()))
    fun customFieldNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CUSTOM_FIELD_NOT_FOUND, "Custom field not found")

    // ---- Finance & Accounting (docs/07 §7) ----
    fun duplicateAccountNumber(bank: String, number: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_ACCOUNT_NUMBER, "Account $number at $bank is already registered", mapOf("bankName" to bank))
    fun accountNotActive(code: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_NOT_ACTIVE, "Account $code is not active and can't take new transactions", mapOf("account" to code))
    fun accountHasBalance(code: String, balance: java.math.BigDecimal) = ApiException(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_HAS_BALANCE, "Account $code still holds ${balance.toPlainString()}; empty it before deactivating", mapOf("account" to code, "balance" to balance.toPlainString()))
    fun insufficientCash(code: String, available: java.math.BigDecimal, requested: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INSUFFICIENT_CASH, "Not enough cash in $code: ${available.toPlainString()} available, ${requested.toPlainString()} requested", mapOf("account" to code, "available" to available.toPlainString(), "requested" to requested.toPlainString()))
    fun expenseCategoryInactive(name: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.EXPENSE_CATEGORY_INACTIVE, "The expense category '$name' is inactive", mapOf("category" to name))
    fun currencyNotTracked(code: String, tracked: Collection<String>) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CURRENCY_NOT_TRACKED, "$code is not a tracked currency", mapOf("currency" to code, "tracked" to tracked.joinToString(",")))
    fun invalidRate(code: String, reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_RATE, "$code: $reason", mapOf("currency" to code, "reason" to reason))
    fun bankAccountNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.BANK_ACCOUNT_NOT_FOUND, "Bank account not found")
    fun cashAccountNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CASH_ACCOUNT_NOT_FOUND, "Cash account not found")
    fun expenseCategoryNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.EXPENSE_CATEGORY_NOT_FOUND, "Expense category not found")
    fun floatLimitExceeded(code: String, limit: java.math.BigDecimal, after: java.math.BigDecimal) = ApiException(HttpStatus.CONFLICT, ErrorCode.FLOAT_LIMIT_EXCEEDED, "This would take $code to ${after.toPlainString()}, above its float limit of ${limit.toPlainString()}", mapOf("account" to code, "floatLimit" to limit.toPlainString(), "balanceAfter" to after.toPlainString()))
    fun categoryNotPayableFromPettyCash(name: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH, "'$name' can't be paid from petty cash; settle it by bank transfer or cheque instead", mapOf("category" to name))
    fun monthlyCapExceeded(name: String, cap: java.math.BigDecimal, spent: java.math.BigDecimal) = ApiException(HttpStatus.CONFLICT, ErrorCode.MONTHLY_CAP_EXCEEDED, "This would take '$name' past its monthly cap of ${cap.toPlainString()} (${spent.toPlainString()} spent so far)", mapOf("category" to name, "cap" to cap.toPlainString(), "spent" to spent.toPlainString()))
    fun invalidPayingAccount(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PAYING_ACCOUNT, reason)
    fun notEditable(status: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.NOT_EDITABLE, "A $status entry can't be changed", mapOf("status" to status))
    fun selfApprovalNotAllowed() = ApiException(HttpStatus.CONFLICT, ErrorCode.SELF_APPROVAL_NOT_ALLOWED, "Someone other than the submitter must approve this")
    fun alreadyDecided(what: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.ALREADY_DECIDED, "This $what has already been decided")
    fun receivedExceedsSent() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RECEIVED_EXCEEDS_SENT, "The amount received can't exceed the amount sent")
    fun shortfallReasonRequired() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.SHORTFALL_REASON_REQUIRED, "Explain why less was received than was sent")
    fun invalidDestinationAccount(reason: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_DESTINATION_ACCOUNT, reason)
    fun bankedExceedsTakings() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BANKED_EXCEEDS_TAKINGS, "The amount banked can't exceed the takings")
    fun pettyCashFundingNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.PETTY_CASH_FUNDING_NOT_FOUND, "Petty cash funding not found")
    fun pettyCashPaymentNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.PETTY_CASH_PAYMENT_NOT_FOUND, "Petty cash payment not found")
    fun allocationNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.ALLOCATION_NOT_FOUND, "Petty cash allocation not found")
    fun runningCostNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RUNNING_COST_NOT_FOUND, "Running cost not found")
    fun disbursementNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.DISBURSEMENT_NOT_FOUND, "Fund disbursement not found")
    fun lodgementNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.LODGEMENT_NOT_FOUND, "Cash lodgement not found")
    fun refundChannelNotSupported(channel: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.REFUND_CHANNEL_NOT_SUPPORTED, "A refund can't be paid by $channel; use cash, a fund transfer or the wallet", mapOf("channel" to channel))
    fun useInvoicePayment() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.USE_INVOICE_PAYMENT, "Settling an invoice from the wallet is an invoice payment (method WALLET), not a withdrawal")
    fun otpMismatch(reason: String) = ApiException(HttpStatus.FORBIDDEN, ErrorCode.OTP_MISMATCH, "This OTP was issued for a different withdrawal: $reason")
    fun otpAlreadyUsed() = ApiException(HttpStatus.FORBIDDEN, ErrorCode.OTP_REQUIRED, "This OTP has already authorised a withdrawal; request a new one")
    fun vatSettingExistsForDate(date: java.time.LocalDate) = ApiException(HttpStatus.CONFLICT, ErrorCode.VAT_SETTING_EXISTS_FOR_DATE, "A VAT setting already takes effect on $date for this location", mapOf("effectiveFrom" to date.toString()))
    fun receiptNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.RECEIPT_NOT_FOUND, "Receipt not found")
    fun walletFundingNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.WALLET_FUNDING_NOT_FOUND, "Wallet funding not found")
    fun withdrawalNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.WITHDRAWAL_NOT_FOUND, "Wallet withdrawal not found")
    fun invoiceNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.INVOICE_NOT_FOUND, "Invoice not found")
    fun invoiceNotDraft() = ApiException(HttpStatus.CONFLICT, ErrorCode.INVOICE_NOT_DRAFT, "This invoice is already issued; only a draft can be changed or issued")
    fun invoiceNotIssued() = ApiException(HttpStatus.CONFLICT, ErrorCode.INVOICE_NOT_ISSUED, "This invoice is not issued")
    fun invoiceExistsForSource(ref: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.INVOICE_EXISTS_FOR_SOURCE, "A live invoice already exists for $ref", mapOf("sourceRef" to ref))
    fun invoiceHasPayments() = ApiException(HttpStatus.CONFLICT, ErrorCode.INVOICE_HAS_PAYMENTS, "This invoice has payments or credit notes: refund or credit it first, or void and reissue")
    fun reissueBelowPaid() = ApiException(HttpStatus.CONFLICT, ErrorCode.REISSUE_BELOW_PAID, "The replacement's total is less than what has already been paid; issue a credit note instead")
    fun discountExceedsLine() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.DISCOUNT_EXCEEDS_LINE, "A line discount can't exceed the line value")
    fun hsnSectionMismatch() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.HSN_SECTION_MISMATCH, "The HS/ISIC code's section must match the item's classification section")
    fun invoiceAlreadyPaid() = ApiException(HttpStatus.CONFLICT, ErrorCode.INVOICE_ALREADY_PAID, "This invoice is already paid in full")
    fun overpayment(balance: java.math.BigDecimal) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OVERPAYMENT, "The payment is more than the balance of ${balance.toPlainString()}", mapOf("balance" to balance.toPlainString()))
    fun creditExceedsLine(lineNo: Int) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.CREDIT_EXCEEDS_LINE, "Line $lineNo can't be credited for more than was invoiced on it", mapOf("lineNo" to lineNo.toString()))
    fun creditExceedsBalance(reason: String) = ApiException(HttpStatus.CONFLICT, ErrorCode.CREDIT_EXCEEDS_BALANCE, reason)
    fun noEmailOnFile() = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.NO_EMAIL_ON_FILE, "There is no e-mail address for this buyer; give recipientEmail")
    fun exportTooLarge(max: Int, found: Long) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.EXPORT_TOO_LARGE, "$found invoices match; narrow the range to at most $max", mapOf("max" to max.toString(), "found" to found.toString()))
    fun creditNoteNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.CREDIT_NOTE_NOT_FOUND, "Credit note not found")
    fun invoicePaymentNotFound() = ApiException(HttpStatus.NOT_FOUND, ErrorCode.INVOICE_PAYMENT_NOT_FOUND, "Invoice payment not found")
    fun pageTooDeep(maxRows: Long) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PAGE_TOO_DEEP,
        "Pages are available for the first $maxRows rows; narrow the filters or the date range to see older ones", mapOf("maxRows" to maxRows.toString()))
}
