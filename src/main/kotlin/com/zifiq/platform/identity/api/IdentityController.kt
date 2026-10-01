package com.zifiq.platform.identity.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.security.CurrentActor
import com.zifiq.platform.security.CurrentActorProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me")
class IdentityController(private val currentActorProvider: CurrentActorProvider) : IdentityApi {
    @GetMapping
    override fun currentActor(): ApiEnvelope<CurrentActor> = ApiEnvelope(currentActorProvider.current())
}
