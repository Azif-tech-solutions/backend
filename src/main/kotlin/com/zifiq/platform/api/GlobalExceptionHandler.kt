package com.zifiq.platform.api

import com.zifiq.common.errors.ApiErrors
import jakarta.validation.ConstraintViolationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * The only place an exception becomes an HTTP response. Every error is defined once in
 * `com.zifiq.common.errors.ApiErrors` (paired with `ErrorCode`) — this class only maps exception
 * *types* to the named error to return; it never constructs a status/code/message itself, and
 * no controller or service anywhere uses try/catch.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorEnvelope> = toResponse(exception)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(exception: NoResourceFoundException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.noRouteFound())

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidRequest(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorEnvelope> {
        val details = exception.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Invalid value") }
        return toResponse(ApiErrors.validationFailed(details))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(exception: ConstraintViolationException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.validationFailed())

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(exception: MissingServletRequestParameterException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.validationFailed(mapOf(exception.parameterName to "This parameter is required")))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(exception: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.validationFailed(mapOf((exception.name) to "This value is not a valid ${exception.requiredType?.simpleName ?: "value"}")))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(exception: HttpMessageNotReadableException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.validationFailed(mapOf("body" to "Request body is missing or malformed")))

    /** The servlet's hard ceiling (spring.servlet.multipart.max-file-size); each purpose's own limit is checked in FileService. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(exception: org.springframework.web.multipart.MaxUploadSizeExceededException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.fileTooLarge(25L * 1024 * 1024))

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException::class)
    fun handleMissingPart(exception: org.springframework.web.multipart.support.MissingServletRequestPartException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.validationFailed(mapOf(exception.requestPartName to "This part is required")))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(exception: AccessDeniedException): ResponseEntity<ApiErrorEnvelope> =
        toResponse(ApiErrors.accessDenied())

    /**
     * SQLSTATE 57014 (query_canceled) is what PostgreSQL raises when a search exceeds the
     * transaction-local statement_timeout set by `Paging.limitStatementTime` — a user-fixable
     * "narrow your filters", not a server fault. Every other database error stays an internal error.
     */
    @ExceptionHandler(ExposedSQLException::class)
    fun handleDatabaseError(exception: ExposedSQLException): ResponseEntity<ApiErrorEnvelope> {
        if (exception.sqlState == QUERY_CANCELED_SQLSTATE) return toResponse(ApiErrors.searchTimeout())
        logger.error("Unhandled database failure", exception)
        return toResponse(ApiErrors.internalError())
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ApiErrorEnvelope> {
        logger.error("Unhandled request failure", exception)
        return toResponse(ApiErrors.internalError())
    }

    private fun toResponse(exception: ApiException): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(exception.status)
            .body(ApiErrorEnvelope(error = ApiError(exception.code, exception.message, exception.details)))
}

private const val QUERY_CANCELED_SQLSTATE = "57014"
