package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, User}
import ai.datris.policy.PolicyReplay
import ai.datris.util.{APIKeyValidator, LogSafe, RevokedKeyException, UserStore}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.AsyncHandlerInterceptor

object TenantInterceptor {

    /** Request attribute name under which the ResolvedKey is stored.
      * Controllers and the CapabilityInterceptor read it via
      * `request.getAttribute(TenantInterceptor.ResolvedKeyAttr)`. */
    val ResolvedKeyAttr: String = "ai.datris.resolvedKey"

    /** Request attribute set (to the failure reason) when the request
      * PRESENTED an x-api-key that could not be resolved — revoked, unknown,
      * or malformed. CapabilityInterceptor denies such requests instead of
      * treating them like a request that carried no key at all. */
    val ApiKeyRejectedAttr: String = "datris.apiKeyRejected"

    /** Request attribute set (to the key's LABEL, never its value) when the
      * rejected key was a known-but-revoked key. Unknown values leave it unset
      * so the audit log never echoes a presented value. */
    val ApiKeyRejectedLabelAttr: String = "datris.apiKeyRejectedLabel"

    /** True when the header carries a non-blank value. A blank header is
      * treated exactly like no header (pre-existing behaviour). */
    def presented(apiKey: String): Boolean = apiKey != null && apiKey.trim.nonEmpty

    /** Request attribute holding the carrier's own ResolvedKey (before the
      * on-behalf-of narrowing or relabel), set together with
      * `AuditActor.OnBehalfOfAttr`. CapabilityInterceptor restores it when the
      * request turns out to carry its own sign-in session (the header is
      * ignored on such a request). */
    val OnBehalfOfCarrierAttr: String = "ai.datris.onBehalfOfCarrier"

    /** Body of the 401 a trusted carrier gets when the on-behalf-of user is
      * unknown or was deleted. Names neither the user nor the carrier, and is
      * not the capability-denied body: the identity, not a permission, failed. */
    val UnverifiedUserBody: String =
        "{\"error\":\"The user this request acts for could not be verified\",\"errorKind\":\"acting_user_unverified\"}"

    /** Body of the 503 a trusted carrier gets when the user store could not
      * be read to resolve the on-behalf-of user. */
    val UserStoreUnavailableBody: String =
        "{\"error\":\"The user this request acts for could not be checked: the user store is unavailable\",\"errorKind\":\"user_store_unavailable\"}"

    /** The identity a trusted carrier (`ui`, or the keys-off identity) gets
      * acting on behalf of `user`. Pure: reads no thread-local and no store.
      *   - an admin (`user.role == User.RoleAdmin`, exactly), or an approval
      *     replay for a user who is not restricted: the carrier's own bundle,
      *     relabelled `session:<user>` (attribution only);
      *   - anyone else: the user's session bundle
      *     (`APIKeyValidator.resolveFromSession`: the role bundle, or the
      *     catalog-grant bundle for a restricted user), kept only where the
      *     carrier itself grants it, with `restrictedSession` as the bundle
      *     has it. This only ever narrows. */
    def onBehalfOfIdentity(carrier: ResolvedKey, user: User, approvalReplay: Boolean): ResolvedKey =
        if (user.role == User.RoleAdmin || (approvalReplay && !user.isRestricted))
            carrier.copy(label = "session:" + user.username)
        else {
            val bundle = APIKeyValidator.resolveFromSession(user)
            // Narrow only: a capability the carrier itself could not
            // exercise under the same scope is dropped.
            val narrowed =
                if (carrier.isLegacyFullAccess) bundle.capabilities
                else bundle.capabilities.filter(c => carrier.grants(c.resource, c.action, c.scope))
            carrier.copy(
                label = bundle.label,
                capabilities = narrowed,
                isLegacyFullAccess = false,
                restrictedSession = bundle.restrictedSession,
                // The session bundle holds only applied scopes; the carrier
                // key's inert ones are not its own.
                inertCapabilities = Nil
            )
        }

    /** What applyOnBehalfOf decided: the identity to attach, or a refusal
      * (status, body, audit reason) answered before any handler runs. */
    private[config] sealed trait OnBehalf
    private[config] final case class Act(identity: ResolvedKey) extends OnBehalf
    private[config] final case class RefuseOnBehalf(status: Int, body: String, reason: String, auditNames: Seq[(String, String)]) extends OnBehalf
}

@Component
class TenantInterceptor extends AsyncHandlerInterceptor {

    private val logger = LoggerFactory.getLogger(getClass)

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        // Never inherit a tenant from whatever this thread served last (see
        // SessionAuthenticator for why async SSE requests leave it behind).
        TenantContext.clear()
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
        // required. With NO key presented the failure is left to the
        // per-controller `validate()` calls (missing-key errors, session
        // flows). With a key presented, the failure is recorded on the
        // request so CapabilityInterceptor fails closed — a revoked or
        // unknown key must never be treated like an anonymous request.
        val refusal: Option[TenantInterceptor.RefuseOnBehalf] =
            try {
                val resolved = APIKeyValidator.resolveKey(apiKey)
                applyOnBehalfOf(request, resolved) match {
                    case TenantInterceptor.Act(identity) =>
                        request.setAttribute(TenantInterceptor.ResolvedKeyAttr, identity)
                        None
                    case r: TenantInterceptor.RefuseOnBehalf => Some(r)
                }
            } catch {
                case e: Throwable =>
                    if (TenantInterceptor.presented(apiKey)) {
                        val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                        logger.info("Rejected presented x-api-key: {}", reason)
                        request.setAttribute(TenantInterceptor.ApiKeyRejectedAttr, reason)
                        e match {
                            case r: RevokedKeyException => request.setAttribute(TenantInterceptor.ApiKeyRejectedLabelAttr, r.label)
                            case _ =>
                        }
                    } else
                        logger.debug("Could not resolve x-api-key into ResolvedKey: {}", e.getMessage)
                    None
            }

        refusal match {
            case Some(r) =>
                // Decision 7 (fail closed): no identity is attached and no
                // handler runs. The tenant set above is cleared here because
                // afterCompletion is not called for an interceptor whose
                // preHandle answered false.
                response.setStatus(r.status)
                response.setContentType("application/json")
                response.getWriter.write(r.body)
                response.getWriter.flush()
                AuditLog.denied(request, r.reason, r.status, names = r.auditNames)
                TenantContext.clear()
                false
            case None => true
        }
    }

    /** The in-platform chats reach REST through the MCP server with the
      * reserved `ui` key, on behalf of the human whose chat it is; the
      * approval replay (PolicyReplay) and the Configuration chat's loopback
      * calls do the same. They send that user as `X-Datris-On-Behalf-Of`.
      * When the header is present AND the carrying key is one we trust to
      * vouch for a user (see AuditActor.trustsOnBehalfOf: the `ui` key, or
      * any request with API keys off) AND user sign-in is on:
      *   - a request that carries its own sign-in session (one the server
      *     validates, as SessionAuthenticator does) ignores the header and
      *     acts as that session's user (Decision 8). The platform's internal
      *     callers never send a session cookie;
      *   - a user who cannot be resolved (unknown, deleted, or the user store
      *     read fails) refuses the request: 401 [[TenantInterceptor.UnverifiedUserBody]]
      *     or 503 [[TenantInterceptor.UserStoreUnavailableBody]], never the
      *     carrier's own identity (Decision 7);
      *   - otherwise the identity is [[TenantInterceptor.onBehalfOfIdentity]]:
      *     an admin keeps the carrier's bundle relabelled `session:<user>`;
      *     every other user is narrowed to their own session bundle (role
      *     bundle, or catalog grants for a restricted user), so a chat acting
      *     for them can do what their role allows and no more. An approval
      *     replay (`X-Datris-Approval` present) for a user who is not
      *     restricted keeps the carrier's bundle: the agent's key was already
      *     allowed the action and a human approved it.
      * Version history, owner tagging and the audit log attribute the action
      * to the person either way.
      *
      * Any other key sending the header is ignored — and the attempt is
      * itself recorded as a security event. */
    private def applyOnBehalfOf(request: HttpServletRequest, resolved: ResolvedKey): TenantInterceptor.OnBehalf = {
        val header = Option(request.getHeader(AuditActor.HeaderOnBehalfOf)).map(_.trim).filter(_.nonEmpty)
        if (header.isEmpty) return TenantInterceptor.Act(resolved)
        val env = DatrisEnvironment.values
        if (!AuditActor.trustsOnBehalfOf(resolved.label, env.useApiKeys)) {
            logger.warn(
                "Ignoring " + AuditActor.HeaderOnBehalfOf + " from key '" + LogSafe.text(resolved.label) + "' — only the ui key may act on behalf of a user"
            )
            val md = new com.google.gson.JsonObject()
            md.addProperty("claimedUser", header.get.take(64))
            // Attach the key first so the security entry names the offending
            // key rather than resolving to "anonymous".
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, resolved)
            AuditLog.record(request, "security", "spoofed-on-behalf-of", "key", resolved.label, outcome = "denied", httpStatus = 200, metadata = md)
            // Let the request proceed under the key's own identity; the
            // capability check still applies. Clear the mark so the audit
            // interceptor also records the underlying action normally.
            request.removeAttribute(AuditLog.RecordedAttr)
            return TenantInterceptor.Act(resolved)
        }
        if (!env.useUserAuth) return TenantInterceptor.Act(resolved)
        if (carriesOwnSession(request)) {
            logger.warn("Ignoring " + AuditActor.HeaderOnBehalfOf + " on a request that carries its own sign-in session")
            return TenantInterceptor.Act(resolved)
        }
        val approvalReplay = request.getHeader(PolicyReplay.HeaderApproval) != null
        // The audit row for a refusal carries the carrier's label and the
        // claimed name (truncated); the response body names neither.
        val names = Seq("carrierKey" -> resolved.label, "claimedUser" -> header.get.take(64))
        try {
            UserStore.find(header.get) match {
                case Some(user) =>
                    request.setAttribute(AuditActor.OnBehalfOfAttr, user)
                    request.setAttribute(AuditActor.CarrierKeyLabelAttr, resolved.label)
                    request.setAttribute(TenantInterceptor.OnBehalfOfCarrierAttr, resolved)
                    TenantInterceptor.Act(TenantInterceptor.onBehalfOfIdentity(resolved, user, approvalReplay))
                case None =>
                    logger.info(AuditActor.HeaderOnBehalfOf + " names a user that does not exist; request refused")
                    TenantInterceptor.RefuseOnBehalf(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        TenantInterceptor.UnverifiedUserBody,
                        "on-behalf-of user could not be verified",
                        names
                    )
            }
        } catch {
            case e: Exception =>
                logger.warn("On-behalf-of user lookup failed; request refused: " + LogSafe.text(String.valueOf(e.getMessage)))
                TenantInterceptor.RefuseOnBehalf(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    TenantInterceptor.UserStoreUnavailableBody,
                    "user store unavailable; on-behalf-of user could not be checked",
                    names
                )
        }
    }

    /** True when the request carries a sign-in session the server validates
      * (SessionAuthenticator.validatedSessionUser: a live session for an
      * existing user). A stale or invalid cookie is not a session; a lookup
      * that fails counts as none (the header then applies, and
      * CapabilityInterceptor still drops it if SessionAuthenticator later
      * finds the session). */
    private def carriesOwnSession(request: HttpServletRequest): Boolean =
        try SessionAuthenticator.validatedSessionUser(request).isDefined
        catch { case _: Exception => false }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        TenantContext.clear()
    }

    /** Called instead of `afterCompletion` on the servlet thread when the
      * handler started async processing (SseEmitter). Clear here so the
      * servlet thread does not carry a tenant into its next request. */
    override def afterConcurrentHandlingStarted(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Unit = {
        TenantContext.clear()
    }
}
