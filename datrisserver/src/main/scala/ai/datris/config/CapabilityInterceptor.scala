package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CallerVisibility, CapabilityRoutes, CapabilityScopes, RequestPaths, RouteCheck}
import ai.datris.model.{Capability, ResolvedKey, UserContext}
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/** See [[CapabilityInterceptor.inertOutcome]]. */
sealed trait InertOutcome
object InertOutcome {
    final case class Refused(capabilities: Seq[Capability]) extends InertOutcome
    final case class UsedUnderOptOut(capabilities: Seq[Capability]) extends InertOutcome
}

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

    /** The capability-denied 403 body (keys `error`, `errorKind`, `key`,
      * `required`, `route`, `message`), JSON-escaped throughout: a label or
      * capability string with a quote, backslash or control character still
      * yields valid JSON. `message` None = the generic "does not hold" text. */
    def deniedBody(label: String, resource: String, action: String, method: String, path: String, message: Option[String]): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty("error", "capability denied")
        o.addProperty("errorKind", "capability_denied")
        o.addProperty("key", label)
        o.addProperty("required", resource + ":" + action)
        o.addProperty("route", method + " " + path)
        o.addProperty(
            "message",
            message.getOrElse(
                "API key '" + label + "' does not hold capability '" + resource + ":" + action + "'. " +
                    "This is a permission boundary on the API key, not a problem with the upstream service. " +
                    "The key would need '" + resource + ":" + action + "' added to its capability bundle, " +
                    "or the operator must use a different key with the required capability."
            )
        )
        o.toString
    }

    /** What a key's inert capabilities ([[ResolvedKey.inertCapabilities]])
      * mean for a route requiring (resource, action): None when they play no
      * part (no inert capability matches, or an applied one does);
      * `Refused` when only an inert capability matches and the platform fails
      * closed (the default); `UsedUnderOptOut` when it matches under
      * `DATRIS_KEY_INERT_SCOPES=allow`. */
    def inertOutcome(rk: ResolvedKey, resource: String, action: String): Option[InertOutcome] = {
        val caps = rk.inertMatching(resource, action)
        if (caps.isEmpty) None
        else if (CapabilityScopes.refuseInert) Some(InertOutcome.Refused(caps))
        else Some(InertOutcome.UsedUnderOptOut(caps))
    }

    /** `message` of the 403 a restricted user's session gets on a table or
      * collection listing ([[CapabilityRoutes.UnfilterableForRestricted]]).
      * The other body fields match the capability-denied body. */
    def restrictedListingMessage(label: String, required: String): String =
        "'" + label + "' holds " + required + ", but this listing is refused for a catalog-scoped user: " +
            "table and collection listings cannot be limited to the user's catalogs. " +
            "Use find_data or get_pipeline to locate data in those catalogs instead."
}

/** Checks every request against the capability declared for its route in
  * [[CapabilityRoutes]]. Reads the [[ResolvedKey]] attached by
  * [[TenantInterceptor]] and verifies the key holds the required capability.
  *
  * Two operating modes, selected by the `CAPABILITY_ENFORCEMENT` env var:
  *
  *   - `log-only` — never denies. Emits a structured
  *     log line for every check: `grant`, `would-deny`, `unmapped`, or
  *     `no-key`. The point is to surface what *would* be denied without
  *     breaking traffic, so we can fix mappings before flipping to enforce.
  *
  *   - `enforce` (the default) — denies disallowed requests with HTTP 403.
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

    /** Keys-off mode (`useApiKeys=false`) attaches the anonymous full-access
      * identity to every request, browser sessions included, so role bundles
      * never apply there. A restricted user's catalog grants must still hold:
      * when the request carries that anonymous identity and the logged-in
      * session user is restricted, the identity is replaced with the user's
      * session bundle. Only ever narrows; unrestricted users are unchanged. */
    private[config] def narrowForRestrictedSession(request: HttpServletRequest): Unit =
        request.getAttribute(TenantInterceptor.ResolvedKeyAttr) match {
            case rk: ResolvedKey if rk.label == "anonymous" && rk.isLegacyFullAccess && rk.tenantEnvironment.isEmpty =>
                attachRestrictedSession(request)
            case _: ResolvedKey =>
            case _ =>
                // No key identity (keys on, cookie-only browser request): attach
                // a restricted user's session bundle now, before the route
                // lookup, so controller-side visibility checks apply on every
                // route (unmapped ones included), not only on mapped ones.
                // Unrestricted users keep the lazy fallback in readResolvedKey.
                // A presented-but-rejected key is never replaced by the session.
                if (request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr) == null) attachRestrictedSession(request)
        }

    private def attachRestrictedSession(request: HttpServletRequest): Unit =
        UserContext.get().filter(_.isRestricted).foreach { user =>
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, APIKeyValidator.resolveFromSession(user))
        }

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        // The route as the dispatcher matched it (decoded, path parameters
        // removed; HEAD decided as GET): the same string every access
        // decision reads (RequestPaths).
        val method = RequestPaths.method(request)
        val path = RequestPaths.path(request)
        narrowForRestrictedSession(request)

        CapabilityRoutes.lookup(method, path) match {
            case RouteCheck.Skip =>
                true

            case RouteCheck.Unmapped if presentedButRejected(request) =>
                // A revoked or unknown key on a route the table does not
                // classify yet. Authentication failure regardless of mapping:
                // without this the controller's own validate() threw and the
                // caller got a 500 with a stack trace instead of a 401.
                rejectPresentedKey(request, response, method, path, required = None)

            case RouteCheck.Unmapped if handler.isInstanceOf[HandlerMethod] =>
                // A controller method with no row and no skip pattern. Every
                // handler mapping has a decision (HandlerMappingDecisionSpec),
                // so this is a route added without one: only full access
                // passes (as for `*:*`), and no identity is left to the
                // controller's own key check, as on mapped routes. Anyone
                // else is refused in enforce mode and logged as would-deny
                // in log-only.
                readResolvedKey(request) match {
                    case Some(rk) if !rk.isFullAccess =>
                        if (enforce) {
                            logger.warn(
                                "capability check: route={} {} key={} outcome=deny (no capability mapping for this handler)",
                                Array[AnyRef](method, path, rk.label): _*
                            )
                            denyCapability(request, response, rk, "*", "*", method, path)
                            false
                        } else {
                            logger.warn(
                                "capability check: route={} {} key={} outcome=would-deny (log-only; no capability mapping for this handler)",
                                Array[AnyRef](method, path, rk.label): _*
                            )
                            true
                        }
                    case _ =>
                        logger.info("capability check: route={} {} outcome=unmapped", method.asInstanceOf[Any], path.asInstanceOf[Any])
                        true
                }

            case RouteCheck.Unmapped =>
                // Not a controller method (static resources, the not-found
                // answer): nothing to protect, the request answers as today.
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

                    case Some(rk) if CapabilityRoutes.scopeRefusal(rk, method, path).contains(CapabilityRoutes.RestrictedListing) =>
                        // A restricted user's session holds metadata:read, but
                        // raw table/collection listings cannot be narrowed to
                        // its catalogs: refused with the capability-denied body
                        // in both modes (the grants are a hard boundary).
                        logger.info(
                            "capability check: route={} {} required={}:{} key={} outcome=deny (restricted session, unfilterable listing)",
                            Array[AnyRef](method, path, resource, action, rk.label): _*
                        )
                        denyCapability(
                            request,
                            response,
                            rk,
                            resource,
                            action,
                            method,
                            path,
                            Some(CapabilityInterceptor.restrictedListingMessage(rk.label, resource + ":" + action))
                        )
                        false

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
                        val inert = CapabilityInterceptor.inertOutcome(rk, resource, action)
                        if (granted && CapabilityRoutes.scopeRefusal(rk, method, path).contains(CapabilityRoutes.InstallWide)) {
                            // A caller limited to a catalog or to what it
                            // created holds the capability, but the route
                            // covers the whole installation and has nothing
                            // to filter (InstallWideForLimited, Decision D3):
                            // refused in both modes, before the handler runs,
                            // as the restricted-session listing refusal is.
                            // Only a caller that holds the capability reaches
                            // here: one that does not keeps the does-not-hold
                            // or inert-scope 403 below.
                            val message = CallerVisibility.InstallWideRequestMessage
                            logger.info(
                                "capability check: route={} {} required={}:{} key={} outcome=deny (scope-limited caller, install-wide route)",
                                Array[AnyRef](method, path, resource, action, rk.label): _*
                            )
                            denyCapability(request, response, rk, resource, action, method, path, Some(message), Some(message + " (key '" + rk.label + "')"))
                            false
                        } else if (granted) {
                            // INFO during Phase 1 so the rollout is visible.
                            // After enforce mode is on and the route map is
                            // stable, dial this back to DEBUG to cut volume.
                            logger.info(
                                "capability check: route={} {} required={}:{} key={} legacy={} outcome=grant",
                                Array[AnyRef](method, path, resource, action, rk.label, java.lang.Boolean.valueOf(rk.isLegacyFullAccess)): _*
                            )
                            inert match {
                                case Some(InertOutcome.UsedUnderOptOut(caps)) =>
                                    // DATRIS_KEY_INERT_SCOPES=allow: passed only
                                    // through a capability whose scope is not applied.
                                    val required = resource + ":" + action
                                    val reason = CapabilityScopes.inertUsedMessage(rk.label, required, caps)
                                    logger.warn(
                                        "capability check: route={} {} required={} key={} outcome=inert-scope-used ({}=allow): {}",
                                        Array[AnyRef](method, path, required, rk.label, CapabilityScopes.InertScopesEnv, caps.map(_.raw).mkString(", ")): _*
                                    )
                                    AuditLog.inertScopeUsed(request, required, caps.map(_.raw), reason)
                                case _ =>
                            }
                            true
                        } else if (enforce) {
                            inert match {
                                case Some(InertOutcome.Refused(caps)) =>
                                    val required = resource + ":" + action
                                    val message = CapabilityScopes.inertDenialMessage(rk.label, required, caps)
                                    logger.info(
                                        "capability check: route={} {} required={} key={} outcome=deny (inert scope: {})",
                                        Array[AnyRef](method, path, required, rk.label, caps.map(_.raw).mkString(", ")): _*
                                    )
                                    denyCapability(request, response, rk, resource, action, method, path, Some(message), Some(message))
                                case _ =>
                                    logger.info(
                                        "capability check: route={} {} required={}:{} key={} outcome=deny",
                                        Array[AnyRef](method, path, resource, action, rk.label): _*
                                    )
                                    denyCapability(request, response, rk, resource, action, method, path)
                            }
                            false
                        } else if (CapabilityRoutes.scopeRefusal(rk, method, path).contains(CapabilityRoutes.InstallWide)) {
                            // Log-only never opens an install-wide route to a
                            // caller limited to a catalog or to what it
                            // created (a restricted session included): the
                            // refusal above holds whether or not the caller
                            // holds the capability. Enforce mode keeps the
                            // does-not-hold / inert-scope answers above.
                            val message = CallerVisibility.InstallWideRequestMessage
                            logger.info(
                                "capability check: route={} {} required={}:{} key={} outcome=deny (log-only; scope-limited caller, install-wide route)",
                                Array[AnyRef](method, path, resource, action, rk.label): _*
                            )
                            denyCapability(request, response, rk, resource, action, method, path, Some(message), Some(message + " (key '" + rk.label + "')"))
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

    /** The 403 capability-denied JSON body and its audit row. Emit a clean
      * JSON body so agents can parse the denial. sendError() produces Tomcat's
      * default HTML page, which agents tend to misread (e.g. "must be an AI
      * provider auth issue") instead of recognizing it as a capability denial. */
    private def denyCapability(
        request: HttpServletRequest,
        response: HttpServletResponse,
        rk: ResolvedKey,
        resource: String,
        action: String,
        method: String,
        path: String,
        message: Option[String] = None,
        auditReason: Option[String] = None
    ): Unit = {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN)
        response.setContentType("application/json")
        response.getWriter.write(CapabilityInterceptor.deniedBody(rk.label, resource, action, method, path, message))
        response.getWriter.flush()
        AuditLog.denied(
            request,
            auditReason.getOrElse("capability denied for key '" + rk.label + "'"),
            HttpServletResponse.SC_FORBIDDEN,
            required = Some(resource + ":" + action)
        )
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
