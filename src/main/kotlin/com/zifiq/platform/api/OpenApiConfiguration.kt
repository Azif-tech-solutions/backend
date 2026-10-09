package com.zifiq.platform.api

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server

@OpenAPIDefinition(
    info = Info(
        title = "ZifIQ API",
        version = "v1",
        description = "Multi-tenant business operations API",
    ),
    // Relative, so Swagger UI calls the address it was loaded from. Behind CloudFront the app sees
    // plain HTTP and would otherwise advertise http://, which an HTTPS page can't call.
    servers = [Server(url = "/", description = "This server")],
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfiguration
