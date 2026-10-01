package com.zifiq.platform.api

import com.zifiq.common.errors.ErrorCode
import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val code: ErrorCode,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)
