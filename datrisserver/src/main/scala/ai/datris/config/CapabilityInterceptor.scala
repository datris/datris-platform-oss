package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import ai.datris.model.{ResolvedKey, UserContext}
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** Checks every request against the capability declared for its route in
  * [[CapabilityRoutes]]. Reads the [[ResolvedKey]] attached by
  * [[TenantInterceptor]] and verifies the key holds the required capability.
  *
  * Two operating modes, selected by the `CAPABILITY_ENFORCEMENT` env var:
  *
  *   - `log-only` (default in Phase 1) — never denies. Emits a structured
  *     log line for every check: `grant`, `would-deny`, `unmapped`, or
  *     `no-key`. The point is to surface what *would* be denied without
  *     breaking traffic, so we can fix mappings before flipping to enforce.
  *
  *   - `enforce` (Phase 2) — denies disallowed requests with HTTP 403.
  *     Existing keys carry the legacy `*:*` capability bundle so nothing
  *     breaks; only keys explicitly issued with scoped capabilities are
  *     constrained.
  *
  * Registered in [[WebMvcConfig]] after [[TenantInterceptor]] so the
  * `ResolvedKey` is available on the request. */
@Component
class CapabilityInterceptor extends HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(getClass)

    // Default to enforce — once an operator has gone to the trouble of issuing
    // a scoped key, they want the scope to actually constrain. Log-only is
    // available as an opt-in trial mode for iterating on a new scope policy
    // without rejecting traffic: set CAPABILITY_ENFORCEMENT=log-only in .env.
    // Legacy `*:*` keys are unaffected either way — they pass everything.
    private val enforce: Boolean =
        !sys.env.getOrElse("CAPABILITY_ENFORCEMENT", "enforce").equalsIgnoreCase("log-only")

    logger.info(
        "CapabilityInterceptor active: mode={}",
        if (enforce) "enforce" else "log-only"
    )

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        val method = request.getMethod
        val path = request.getRequestURI

        CapabilityRoutes.lookup(method, path) match {
            case RouteCheck.Skip =>
                true

            case RouteCheck.Unmapped =>
                // No mapping for this route. In log-only mode this is just
                // informational telemetry — it tells us which routes still
                // need classification. In enforce mode we still let it
                // through; fail-closed for unmapped routes is a Phase 2+
                // tightening once the mapping is comprehensive.
                // INFO during Phase 1 so gaps in the route table are visible
                // under default Spring log levels; dial to DEBUG once stable.
                logger.info("capability check: route={} {} outcome=unmapped", method.asInstanceOf[Any], path.asInstanceOf[Any])
                true

            case RouteCheck.Require(resource, action) =>
                val resolvedOpt = readResolvedKey(request)
                resolvedOpt match {
                    case None =>
                        // No ResolvedKey on the request. This happens for
                        // routes that don't require a key, or when auth is
                        // disabled. We don't enforce here; the existing
                        // per-controller `validate()` calls handle "key
                        // required but missing". Kept at DEBUG — public/
                        // unauthenticated probes are high-volume noise.
                        logger.debug(
                            "capability check: route={} {} required={}:{} outcome=no-key",
                            Array[AnyRef](method, path, resource, action): _*
                        )
                        true

                    case Some(rk) =>
                        // Pre-action gate is scope-agnostic — we don't have
                        // the loaded resource yet (the controller hasn't run),
                        // so we only check that the key holds SOME capability
                        // for this resource+action. Scope predicates like
                        // `owner=self` or `_type=tap` are evaluated later by
                        // the controller via `CapabilityCheck.assertScope`
                        // once the resource is in hand. Without this split,
                        // any scoped capability would false-deny here because
                        // scope.forall on a non-empty scope with an empty
                        // context always fails.
                        val granted = rk.matchesResourceAction(resource, action)
                        if (granted) {
                            // INFO during Phase 1 so the rollout is visible.
                            // After enforce mode is on and the route map is
                            // stable, dial this back to DEBUG to cut volume.
                            logger.info(
                                "capability check: route={} {} required={}:{} key={} legacy={} outcome=grant",
                                Array[AnyRef](method, path, resource, action, rk.label, java.lang.Boolean.valueOf(rk.isLegacyFullAccess)): _*
                            )
                            true
                        } else if (enforce) {
                            logger.info(
                                "capability check: route={} {} required={}:{} key={} outcome=deny",
                                Array[AnyRef](method, path, resource, action, rk.label): _*
                            )
                            // Emit a clean JSON body so agents can parse the
                            // denial. sendError() produces Tomcat's default
                            // HTML page, which agents tend to misread (e.g.
                            // "must be an AI provider auth issue") instead
                            // of recognizing it as a capability denial.
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
                            response.setContentType("application/json")
                            val safeLabel = rk.label.replace("\"", "\\\"")
                            val body =
                                "{\"error\":\"capability denied\"," +
                                    "\"errorKind\":\"capability_denied\"," +
                                    "\"key\":\"" + safeLabel + "\"," +
                                    "\"required\":\"" + resource + ":" + action + "\"," +
                                    "\"route\":\"" + method + " " + path + "\"," +
                                    "\"message\":\"API key '" + safeLabel + "' does not hold capability '" + resource + ":" + action + "'. " +
                                    "This is a permission boundary on the API key, not a problem with the upstream service. " +
                                    "The key would need '" + resource + ":" + action + "' added to its capability bundle, " +
                                    "or the operator must use a different key with the required capability.\"}"
                            response.getWriter.write(body)
                            response.getWriter.flush()
                            AuditLog.denied(
                                request,
                                "capability denied for key '" + rk.label + "'",
                                HttpServletResponse.SC_FORBIDDEN,
                                required = Some(resource + ":" + action)
                            )
                            false
                        } else {
                            logger.warn(
                                "capability check: route={} {} required={}:{} key={} outcome=would-deny (log-only)",
                                Array[AnyRef](method, path, resource, action, rk.label): _*
                            )
                            true
                        }
                }
        }
    }

    private def readResolvedKey(request: HttpServletRequest): Option[ResolvedKey] = {
        val attr = request.getAttribute(TenantInterceptor.ResolvedKeyAttr)
        attr match {
            case rk: ResolvedKey => Some(rk)
            case _ =>
                // No key-derived ResolvedKey on the request — fall back to
                // a session-derived one if the user is logged in. This is
                // what makes browser flows first-class to the capability
                // framework: an authenticated session counts as identity,
                // even without an x-api-key. SessionAuthenticator runs
                // before this interceptor (per WebMvcConfig ordering), so
                // UserContext is populated by the time we read it.
                UserContext.get().map { user =>
                    val resolved = APIKeyValidator.resolveFromSession(user)
                    request.setAttribute(TenantInterceptor.ResolvedKeyAttr, resolved)
                    resolved
                }
        }
    }
}
