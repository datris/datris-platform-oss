package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.{DatrisEnvironment, ResolvedKey, User, UserContext}
import jakarta.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}
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
  *  3. Public endpoints — the auth controller's credential-free methods
  *     (login/logout/me/change-password) and /api/v1/version — are exempt,
  *     but ONLY when they carry no @RequiresRole. The auth controller also
  *     hosts admin user-management methods that ARE role-gated; those must
  *     not ride the class exemption.
  *  4. No UserContext (programmatic path):
  *       - role-gated endpoint → require a *validated* full-access API key
  *         (mere x-api-key presence, or the anonymous full-access identity in
  *         useApiKeys=false mode, is NOT sufficient for an admin gate);
  *       - otherwise → x-api-key validated per-controller, or legacy no-auth.
  *  5. Method or class has @RequiresRole → restrict to those roles.
  *  6. Default rule: GET allowed for all logged-in roles; non-GET requires admin or editor.
  *
  * Step 6 keeps the diff small — we don't have to annotate every write endpoint. */
@Component
class RoleEnforcementInterceptor extends HandlerInterceptor {
    private val logger: Logger = LoggerFactory.getLogger(classOf[RoleEnforcementInterceptor])

    private val WriteRoles: Set[String] = Set(User.RoleAdmin, User.RoleEditor)

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (!DatrisEnvironment.values.useUserAuth) return true
        if (!handler.isInstanceOf[HandlerMethod]) return true
        val method = handler.asInstanceOf[HandlerMethod]

        // @RequiresRole may sit on the method or the controller class. Resolve
        // it up front: it decides whether an endpoint is public or role-gated,
        // independent of which controller hosts it. This is what stops the
        // admin user-management methods in AuthAPIController from inheriting
        // the controller's public exemption.
        val ann = Option(method.getMethodAnnotation(classOf[RequiresRole]))
            .orElse(Option(method.getBeanType.getAnnotation(classOf[RequiresRole])))

        val classFqn = method.getBeanType.getName
        // The auth controller's credential-free endpoints (login/logout/me/
        // change-password) must be reachable without a session — but only the
        // ones with no role annotation. A role-gated method here falls through.
        if (ann.isEmpty && classFqn == "ai.datris.api.AuthAPIController") return true

        val uri = request.getRequestURI
        if (ann.isEmpty && uri != null && uri.startsWith("/api/v1/version")) return true

        val userOpt = UserContext.get()
        if (userOpt.isEmpty) {
            // A stale session cookie means this is a browser whose session
            // expired or was revoked — NOT a programmatic caller (those never
            // send the cookie). Hard 401 and clear the cookie so the UI's
            // auth-error interceptor returns the user to the login screen on
            // the next request instead of the page silently degrading to the
            // anonymous paths below. Applies even alongside an x-api-key: a
            // dead session must force re-login, not fall back to a key.
            if (request.getAttribute(SessionAuthenticator.StaleSessionAttribute) != null) {
                response.addCookie(expiredSessionCookie())
                return reject(request, response, HttpStatus.UNAUTHORIZED, """{"error":"Session expired"}""")
            }
            ann match {
                case Some(_) =>
                    // Role-gated endpoint (e.g. key minting, user management)
                    // reached without a user session. A bare x-api-key header
                    // is NOT enough: the old code accepted any non-empty value,
                    // which let a forged or read-only key mint a `*:*` master
                    // key and create admin users. Require a *validated* key
                    // that resolves to full ('*:*') access — which exists only
                    // when useApiKeys is on and the key is real. The anonymous
                    // full-access identity present in useApiKeys=false mode is
                    // deliberately excluded so an admin gate is never satisfied
                    // by an unauthenticated caller.
                    //
                    // A scoped key on a CAPABILITY-MAPPED route is the other
                    // legitimate programmatic identity: the capability check
                    // (which already ran — WebMvcConfig ordering) granted the
                    // route, and controllers still enforce scope predicates.
                    // Role gates govern UI sessions; for server-to-server
                    // callers the capability bundle is the permission model.
                    // Routes OUTSIDE the capability table (key minting, user
                    // management — skip-listed there) still require `*:*`, so
                    // an admin gate is never satisfied by a scoped key.
                    if (
                        DatrisEnvironment.values.useApiKeys &&
                        resolvedKey(request).exists(rk =>
                            RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(
                                rk,
                                request.getMethod,
                                request.getRequestURI
                            )
                        )
                    )
                        return true
                    return reject(request, response, HttpStatus.FORBIDDEN, """{"error":"Insufficient role"}""")

                case None =>
                    // No session — programmatic / service-to-service path (CLI, MCP server, etc).
                    // x-api-key is validated by APIKeyValidator inside each controller; we just
                    // let the request through here.
                    val apiKey = request.getHeader("x-api-key")
                    if (apiKey != null && !apiKey.isEmpty) return true
                    // If api keys are not required either, this is the legacy "no API auth" mode
                    // (the OSS default). useUserAuth governs UI auth; it does not retroactively
                    // require server-to-server callers to authenticate.
                    if (!DatrisEnvironment.values.useApiKeys) return true
                    return reject(request, response, HttpStatus.UNAUTHORIZED, """{"error":"Authentication required"}""")
            }
        }
        // Session present — role check applies even if a stale x-api-key is also being sent.
        // Otherwise a viewer with an old admin api key in localStorage could bypass roles.
        val user = userOpt.get

        ann match {
            case Some(a) =>
                val allowed = a.value().toSet
                if (allowed.contains(user.role)) true
                else reject(request, response, HttpStatus.FORBIDDEN, """{"error":"Insufficient role"}""")
            case None =>
                // Default rule: viewers can GET; writes need admin or editor.
                val httpMethod = request.getMethod
                if (httpMethod == "GET" || httpMethod == "HEAD" || httpMethod == "OPTIONS") true
                else if (WriteRoles.contains(user.role)) true
                else reject(request, response, HttpStatus.FORBIDDEN, """{"error":"Read-only role"}""")
        }
    }

    // The ResolvedKey attached by TenantInterceptor from a validated x-api-key.
    // Absent when no valid key was presented (in useApiKeys=true mode a bad key
    // resolves to nothing); present as anonymous full-access in useApiKeys=false
    // mode — which is why the admin-gate check above additionally requires
    // useApiKeys to be on before trusting a full-access resolution.
    private def resolvedKey(request: HttpServletRequest): Option[ResolvedKey] = {
        request.getAttribute(TenantInterceptor.ResolvedKeyAttr) match {
            case rk: ResolvedKey => Some(rk)
            case _ => None
        }
    }

    // Mirrors AuthAPIController.buildSessionCookie's attributes with maxAge=0.
    private def expiredSessionCookie(): Cookie = {
        val cookie = new Cookie("datris-session", "")
        cookie.setHttpOnly(true)
        cookie.setSecure(SessionAuthenticator.cookieSecure)
        cookie.setPath("/")
        cookie.setMaxAge(0)
        cookie.setAttribute("SameSite", "Strict")
        cookie
    }

    private def reject(request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, body: String): Boolean = {
        response.setStatus(status.value())
        response.setContentType("application/json")
        response.getWriter.write(body)
        // Reason is the JSON error text without the envelope, e.g. "Insufficient role".
        val reason = body.replaceAll("^\\{\"error\":\"|\"\\}$", "")
        AuditLog.denied(request, reason, status.value())
        false
    }
}

object RoleEnforcementInterceptor {

    /** Does this validated key satisfy a role-gated endpoint reached without
      * a user session? Full access (`*:*`) always does. A scoped key does
      * only when the route is capability-mapped and the key holds the
      * required capability — the same grant the CapabilityInterceptor
      * already enforced earlier in the chain. Skip-listed and unmapped
      * routes (key minting, user management) never accept a scoped key. */
    private[config] def programmaticKeySatisfiesRoleGate(
        rk: ResolvedKey,
        method: String,
        path: String
    ): Boolean = {
        if (rk.matchesResourceAction("*", "*")) return true
        ai.datris.auth.CapabilityRoutes.lookup(method, path) match {
            case ai.datris.auth.RouteCheck.Require(resource, action) =>
                rk.matchesResourceAction(resource, action)
            case _ => false
        }
    }
}
