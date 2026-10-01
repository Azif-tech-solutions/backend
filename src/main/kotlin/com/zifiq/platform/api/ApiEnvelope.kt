package com.zifiq.platform.api

import com.zifiq.common.errors.ErrorCode

data class ApiEnvelope<T>(val data: T)

data class ApiErrorEnvelope(
    val data: Nothing? = null,
    val error: ApiError,
)

data class ApiError(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)
