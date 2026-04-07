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

    @Value("${cors.allowedOrigins:*}")
    var allowedOrigins: String = _

    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
    }

    override def addCorsMappings(registry: CorsRegistry): Unit = {
        val origins = allowedOrigins.split(",").map(_.trim).filter(_.nonEmpty)
        val mapping = registry.addMapping("/api/**")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
        // allowedOriginPatterns supports "*" with credentials; allowedOrigins does not
        if (origins.contains("*")) mapping.allowedOriginPatterns("*")
        else mapping.allowedOrigins(origins: _*)
    }
}
