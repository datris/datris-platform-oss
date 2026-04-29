package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.springframework.beans.factory.annotation.{Autowired, Value}
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.{CorsRegistry, InterceptorRegistry, WebMvcConfigurer}

@Configuration
class WebMvcConfig extends WebMvcConfigurer {

    @Autowired
    var tenantInterceptor: TenantInterceptor = _

    @Autowired
    var sessionAuthenticator: SessionAuthenticator = _

    @Autowired
    var roleEnforcementInterceptor: RoleEnforcementInterceptor = _

    @Value("${cors.allowedOrigins:*}")
    var allowedOrigins: String = _

    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        // Order matters: TenantInterceptor sets multi-tenant env first, SessionAuthenticator
        // populates UserContext from the cookie, then RoleEnforcementInterceptor checks
        // @RequiresRole using that context.
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(sessionAuthenticator)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(roleEnforcementInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
    }

    override def addCorsMappings(registry: CorsRegistry): Unit = {
        val origins = allowedOrigins.split(",").map(_.trim).filter(_.nonEmpty)
        val mapping = registry.addMapping("/api/**")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
        // allowedOriginPatterns supports "*" with credentials; allowedOrigins does not
        if (origins.contains("*")) mapping.allowedOriginPatterns("*")
        else mapping.allowedOrigins(origins: _*)
    }
}
