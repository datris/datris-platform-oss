package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, TenantContext}
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

object TenantInterceptor {
    /** Request attribute name under which the ResolvedKey is stored.
      * Controllers and the CapabilityInterceptor read it via
      * `request.getAttribute(TenantInterceptor.ResolvedKeyAttr)`. */
    val ResolvedKeyAttr: String = "ai.datris.resolvedKey"
}

@Component
class TenantInterceptor extends HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(getClass)

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        val apiKey = request.getHeader("x-api-key")

        if (DatrisEnvironment.values.multiTenant) {
            APIKeyValidator.validateAndResolve(apiKey) match {
                case Some(envName) =>
                    val tenantEnv = DatrisEnvironment.forEnvironment(envName)
                    TenantContext.set(tenantEnv)
                case None => // global environment used
            }
        }

        // Attach the ResolvedKey (capabilities + label) to the request so the
        // CapabilityInterceptor and controllers can read it for owner tagging
        // and scope checks. resolveKey handles the no-key case for anonymous
        // mode (`useApiKeys=false`) — we always get a ResolvedKey there even
        // without a header. Failures only happen in modes where a key IS
        // required; those are swallowed so the existing per-controller
        // `validate()` calls remain the source of truth for missing-key errors.
        try {
            val resolved = APIKeyValidator.resolveKey(apiKey)
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, resolved)
        } catch {
            case e: Throwable =>
                logger.debug("Could not resolve x-api-key into ResolvedKey: {}", e.getMessage)
        }

        true
    }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        TenantContext.clear()
    }
}
