package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, TenantContext}
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class TenantInterceptor extends HandlerInterceptor {

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (DatrisEnvironment.values.multiTenant) {
            val apiKey = request.getHeader("x-api-key")
            APIKeyValidator.validateAndResolve(apiKey) match {
                case Some(envName) =>
                    val tenantEnv = DatrisEnvironment.forEnvironment(envName)
                    TenantContext.set(tenantEnv)
                case None => // global environment used
            }
        }
        true
    }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        TenantContext.clear()
    }
}
