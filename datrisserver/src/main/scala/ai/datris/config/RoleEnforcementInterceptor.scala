package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, User, UserContext}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/** Enforces role-based access on /api endpoints when useUserAuth is on.
  *
  * Decision order:
  *  1. useUserAuth=false → noop (existing deploys unchanged).
  *  2. Non-controller / static handler → noop.
  *  3. AuthAPIController, /api/v1/version → public (auth controller handles its own gating).
  *  4. Request carries an x-api-key → programmatic path, already validated upstream → allow.
  *  5. No UserContext → 401.
  *  6. Method or class has @RequiresRole → restrict to those roles.
  *  7. Default rule: GET allowed for all logged-in roles; non-GET requires admin or editor.
  *
  * Step 7 keeps the diff small — we don't have to annotate every write endpoint. */
@Component
class RoleEnforcementInterceptor extends HandlerInterceptor {
    private val logger: Logger = LoggerFactory.getLogger(classOf[RoleEnforcementInterceptor])

    private val WriteRoles: Set[String] = Set(User.RoleAdmin, User.RoleEditor)

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (!DatrisEnvironment.values.useUserAuth) return true
        if (!handler.isInstanceOf[HandlerMethod]) return true
        val method = handler.asInstanceOf[HandlerMethod]

        val classFqn = method.getBeanType.getName
        if (classFqn == "ai.datris.api.AuthAPIController") return true

        val uri = request.getRequestURI
        if (uri != null && uri.startsWith("/api/v1/version")) return true

        val userOpt = UserContext.get()
        if (userOpt.isEmpty) {
            // No session — programmatic / service-to-service path (CLI, MCP server, etc).
            // x-api-key is validated by APIKeyValidator inside each controller; we just
            // let the request through here.
            val apiKey = request.getHeader("x-api-key")
            if (apiKey != null && !apiKey.isEmpty) return true
            // If api keys are not required either, this is the legacy "no API auth" mode
            // (the OSS default). useUserAuth governs UI auth; it does not retroactively
            // require server-to-server callers to authenticate.
            if (!DatrisEnvironment.values.useApiKeys) return true
            return reject(response, HttpStatus.UNAUTHORIZED, """{"error":"Authentication required"}""")
        }
        // Session present — role check applies even if a stale x-api-key is also being sent.
        // Otherwise a viewer with an old admin api key in localStorage could bypass roles.
        val user = userOpt.get

        val ann = Option(method.getMethodAnnotation(classOf[RequiresRole]))
            .orElse(Option(method.getBeanType.getAnnotation(classOf[RequiresRole])))
        ann match {
            case Some(a) =>
                val allowed = a.value().toSet
                if (allowed.contains(user.role)) true
                else reject(response, HttpStatus.FORBIDDEN, """{"error":"Insufficient role"}""")
            case None =>
                // Default rule: viewers can GET; writes need admin or editor.
                val httpMethod = request.getMethod
                if (httpMethod == "GET" || httpMethod == "HEAD" || httpMethod == "OPTIONS") true
                else if (WriteRoles.contains(user.role)) true
                else reject(response, HttpStatus.FORBIDDEN, """{"error":"Read-only role"}""")
        }
    }

    private def reject(response: HttpServletResponse, status: HttpStatus, body: String): Boolean = {
        response.setStatus(status.value())
        response.setContentType("application/json")
        response.getWriter.write(body)
        false
    }
}
