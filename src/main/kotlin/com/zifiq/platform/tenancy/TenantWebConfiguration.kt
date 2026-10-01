package com.zifiq.platform.tenancy

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class TenantWebConfiguration(private val tenantContextInterceptor: TenantContextInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(tenantContextInterceptor).addPathPatterns("/api/**")
    }
}
