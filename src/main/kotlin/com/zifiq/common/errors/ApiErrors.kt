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
}
