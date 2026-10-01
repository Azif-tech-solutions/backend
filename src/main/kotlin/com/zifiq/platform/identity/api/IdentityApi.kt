package com.zifiq.platform.identity.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.security.CurrentActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Current Actor", description = "The authenticated user and their active tenant")
interface IdentityApi {
    @Operation(summary = "Get the authenticated user and active tenant")
    @SecurityRequirement(name = "bearerAuth")
    fun currentActor(): ApiEnvelope<CurrentActor>
}
