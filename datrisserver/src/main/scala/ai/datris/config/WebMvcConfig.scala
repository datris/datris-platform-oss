package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.{InterceptorRegistry, WebMvcConfigurer}

@Configuration
class WebMvcConfig extends WebMvcConfigurer {

    @Autowired
    var tenantInterceptor: TenantInterceptor = _

    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/minio-events")
    }
}
