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
    var capabilityInterceptor: CapabilityInterceptor = _

    @Autowired
    var sessionAuthenticator: SessionAuthenticator = _

    @Autowired
    var roleEnforcementInterceptor: RoleEnforcementInterceptor = _

    @Autowired
    var auditInterceptor: AuditInterceptor = _

    @Value("${cors.allowedOrigins:*}")
    var allowedOrigins: String = _

    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        // Order matters:
        //   1. TenantInterceptor — sets multi-tenant env from x-api-key, and
        //      attaches a key-derived ResolvedKey when x-api-key is present.
        //   2. SessionAuthenticator — reads the session cookie and populates
        //      UserContext. Runs after TenantInterceptor so multi-tenant
        //      session storage (which is per-tenant) is queried with the
        //      right tenant context.
        //   3. CapabilityInterceptor — reads the ResolvedKey for the
        //      capability check. If none was attached by TenantInterceptor
        //      (no x-api-key), falls back to deriving one from UserContext
        //      so logged-in browser flows are first-class identities too.
        //   4. RoleEnforcementInterceptor — gates @RequiresRole methods using
        //      UserContext.
        //   5. AuditInterceptor — MUST stay last. afterCompletion runs in
        //      reverse order, so last-registered runs first and still sees
        //      UserContext / TenantContext / the ResolvedKey before the
        //      earlier interceptors clear them. Denials upstream never reach
        //      it; those interceptors call AuditLog.denied themselves.
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(sessionAuthenticator)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(capabilityInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(roleEnforcementInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
        registry.addInterceptor(auditInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
    }

    override def addCorsMappings(registry: CorsRegistry): Unit = {
        val origins = allowedOrigins.split(",").map(_.trim).filter(_.nonEmpty)
        val mapping = registry.addMapping("/api/**")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
        if (origins.contains("*")) {
            // SECURITY: never pair a wildcard origin with credentials — that
            // reflects ANY site's Origin back with Access-Control-Allow-
            // Credentials, letting any page make credentialed cross-origin
            // calls. Wildcard stays allowed but WITHOUT credentials. To enable
            // credentialed cross-origin requests, set cors.allowedOrigins to an
            // explicit allowlist (the else branch).
            mapping.allowedOriginPatterns("*").allowCredentials(false)
        } else {
            mapping.allowedOrigins(origins: _*).allowCredentials(true)
        }
    }
}
