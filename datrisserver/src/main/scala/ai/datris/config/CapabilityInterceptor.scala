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

object CapabilityInterceptor {

    /** Body returned when a presented x-api-key failed resolution. */
    val RejectedKeyBody: String = "{\"error\":\"API key is revoked or invalid\"}"

    /** Body returned when the key could not be checked because the key
      * metadata secret was unreadable (secret-store outage). */
    val MetadataUnavailableBody: String = "{\"error\":\"" + APIKeyValidator.MetadataUnavailableMessage + "\"}"

    /** Status and body for a rejected key: 503 when the rejection is a
      * metadata-store outage (a transient server condition), 401 otherwise. */
    def rejectionResponse(reason: String): (Int, String) =
        if (APIKeyValidator.isStoreOutage(reason))
            (HttpServletResponse.SC_SERVICE_UNAVAILABLE, "{\"error\":\"" + reason + "\"}")
        else (HttpServletResponse.SC_UNAUTHORIZED, RejectedKeyBody)

    /** Pure decision for the no-ResolvedKey case. Deny only when the request
      * presented a key AND TenantInterceptor recorded that it could not be
      * resolved (revoked/unknown/malformed) AND no other identity (session)
      * was found. No key at all → pass through unchanged; a resolved key
      * (legacy full access or scoped) → normal capability check. */
    def denyPresentedButUnresolved(presentedKey: Boolean, resolved: Boolean, rejection: Option[String]): Boolean =
        presentedKey && !resolved && rejection.isDefined
}

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

    private def presentedButRejected(request: HttpServletRequest): Boolean =
        CapabilityInterceptor.denyPresentedButUnresolved(
            TenantInterceptor.presented(request.getHeader("x-api-key")),
            resolved = readResolvedKey(request).isDefined,
            Option(request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr)).map(_.toString)
        )

    /** Writes the 401/503 rejection, audits it as security:denied, and
      * returns false so the controller never runs. */
    private def rejectPresentedKey(
        request: HttpServletRequest,
        response: HttpServletResponse,
        method: String,
        path: String,
        required: Option[String]
    ): Boolean = {
        logger.info(
            "capability check: route={} {} required={} outcome=rejected-key",
            Array[AnyRef](method, path, required.getOrElse("-")): _*
        )
        val (status, body) = CapabilityInterceptor.rejectionResponse(
            String.valueOf(request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr))
        )
        response.setStatus(status)
        response.setContentType("application/json")
        response.getWriter.write(body)
        response.getWriter.flush()
        AuditLog.denied(
            request,
            if (status == HttpServletResponse.SC_SERVICE_UNAVAILABLE)
                String.valueOf(request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr))
            else "API key is revoked or invalid",
            status,
            required = required
        )
        false
    }

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        val method = request.getMethod
        val path = request.getRequestURI

        CapabilityRoutes.lookup(method, path) match {
            case RouteCheck.Skip =>
                true

            case RouteCheck.Unmapped if presentedButRejected(request) =>
                // A revoked or unknown key on a route the table does not
                // classify yet. Authentication failure regardless of mapping:
                // without this the controller's own validate() threw and the
                // caller got a 500 with a stack trace instead of a 401.
                rejectPresentedKey(request, response, method, path, required = None)

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
                    case None if presentedButRejected(request) =>
                        // A key WAS presented but could not be resolved
                        // (revoked, unknown, malformed). Authentication
                        // failure, not a capability question — denied in both
                        // enforce and log-only modes. Without this the request
                        // fell into the no-key branch below and skipped the
                        // capability check entirely.
                        rejectPresentedKey(request, response, method, path, required = Some(resource + ":" + action))

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
