package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, User}
import ai.datris.policy.{PendingAction, ReplayAuthority}
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
      *   - an admin (`user.role == User.RoleAdmin`, exactly): the carrier's
      *     own bundle, relabelled `session:<user>` (attribution only);
      *   - anyone else: the user's session bundle
      *     (`APIKeyValidator.resolveFromSession`: the role bundle, or the
      *     catalog-grant bundle for a restricted user), kept only where the
      *     carrier itself grants it, with `restrictedSession` as the bundle
      *     has it. This only ever narrows.
      * `approvalReplay` no longer changes the result (kept for existing
      * callers): a verified replay gets [[replayIdentity]] instead. */
    def onBehalfOfIdentity(carrier: ResolvedKey, user: User, approvalReplay: Boolean = false): ResolvedKey =
        if (user.role == User.RoleAdmin) carrier.copy(label = "session:" + user.username)
        else narrowTo(carrier, APIKeyValidator.resolveFromSession(user), bundleLabel = true)

    /** The identity an approval replay ran with before requester access
      * applied, kept where it still applies (API keys off, or the platform's
      * own key as the requester): with no approver named (user sign-in off)
      * the carrier itself; an admin or an approver who is not restricted
      * the carrier's bundle relabelled `session:<approver>`; a restricted
      * approver their own narrowed bundle. */
    private[config] def approverIdentity(carrier: ResolvedKey, approver: Option[User]): ResolvedKey =
        approver match {
            case None => carrier
            case Some(u) if u.role == User.RoleAdmin || !u.isRestricted => carrier.copy(label = "session:" + u.username)
            case Some(u) => onBehalfOfIdentity(carrier, u)
        }

    /** `rk` held to `bundle`: the bundle's capabilities, kept only where `rk`
      * itself grants them under the same scope. Only ever narrows. With
      * `bundleLabel` the result carries the bundle's label (a chat acting
      * for the bundle's user), else `rk`'s own (the owner tag of what it
      * creates stays the requester's). */
    private def narrowTo(rk: ResolvedKey, bundle: ResolvedKey, bundleLabel: Boolean): ResolvedKey = {
        val narrowed =
            if (rk.isLegacyFullAccess) bundle.capabilities
            else bundle.capabilities.filter(c => rk.grants(c.resource, c.action, c.scope))
        rk.copy(
            label = if (bundleLabel) bundle.label else rk.label,
            capabilities = narrowed,
            isLegacyFullAccess = false,
            restrictedSession = bundle.restrictedSession || (!bundleLabel && rk.restrictedSession),
            // The session bundle holds only applied scopes; the key's inert
            // ones are not the bundle's.
            inertCapabilities = Nil
        )
    }

    /** Body of the 401 a verified approval replay gets when the key that
      * requested the action was revoked (or re-issued under its label) since. */
    val RequesterKeyInvalidBody: String =
        "{\"error\":\"The key that requested this action is no longer valid, so the approved action was not carried out\",\"errorKind\":\"requester_key_invalid\"}"

    /** Body of the 401 a verified approval replay gets when the user whose
      * chat requested the action no longer exists. */
    val RequesterUserGoneBody: String =
        "{\"error\":\"The user whose chat requested this action no longer exists, so the approved action was not carried out\",\"errorKind\":\"requester_user_gone\"}"

    /** Body of the 503 a verified approval replay gets when the key store or
      * the user store could not be read to check its requester. */
    val RequesterUncheckedBody: String =
        "{\"error\":\"The requester of this action could not be checked right now, so the approved action was not carried out. " +
            "Try again later: the agent can request the action again and it can then be approved\",\"errorKind\":\"requester_unchecked\"}"

    /** True when both sides hold a key id and they differ: the label now
      * names a different key. A side with no id (queued before key ids were
      * recorded, or a key issued before they existed) matches on the label. */
    private[config] def otherKey(queued: Option[String], current: Option[String]): Boolean =
        (queued, current) match {
            case (Some(q), Some(c)) => q != c
            case _ => false
        }

    /** The identity the verified replay of `pa` runs with: the requester's
      * current access, never more.
      *   - API keys off: [[approverIdentity]], as before (the API is open);
      *   - a chat (actor `assistant`, or a `user`): that user's on-behalf-of
      *     identity through the presenting carrier ([[onBehalfOfIdentity]]);
      *     a user that no longer exists refuses 401;
      *   - the platform's own `ui` key as the requester: [[approverIdentity]],
      *     as before;
      *   - any other key: the key now issued under the label
      *     (`APIKeyValidator.resolveByLabel`), refused 401 when it is gone,
      *     revoked or a different key ([[otherKey]]);
      *   - a store that cannot be read refuses 503: nothing runs.
      * `approver` is None when no person is named (user sign-in off). For a
      * chat or a key, a restricted approver's own grants narrow the result
      * further and the label stays the requester's, so what the replay
      * creates is theirs. */
    private[config] def replayIdentity(
        pa: PendingAction,
        carrier: ResolvedKey,
        approver: Option[User],
        useApiKeys: Boolean,
        lookupKey: String => Option[ResolvedKey],
        lookupUser: String => Option[User]
    ): Either[(Int, String, String), ResolvedKey] = {
        if (!useApiKeys) return Right(approverIdentity(carrier, approver))
        val actor = pa.actor
        val chat = actor.actorType == "assistant" || actor.actorType == "user"
        val label = actor.keyLabel.getOrElse(actor.label)
        if (!chat && label == AuditActor.UiKeyLabel) return Right(approverIdentity(carrier, approver))
        val requester: Either[(Int, String, String), ResolvedKey] =
            try
                if (chat)
                    actor.username.flatMap(lookupUser) match {
                        case Some(u) => Right(onBehalfOfIdentity(carrier, u))
                        case None => Left((HttpServletResponse.SC_UNAUTHORIZED, RequesterUserGoneBody, "approval requester user no longer exists"))
                    }
                else
                    lookupKey(label) match {
                        case Some(rk) if !otherKey(actor.keyId, rk.keyId) => Right(rk)
                        case _ => Left((HttpServletResponse.SC_UNAUTHORIZED, RequesterKeyInvalidBody, "approval requester key is no longer valid"))
                    }
            catch {
                case e: Exception =>
                    Left((
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        RequesterUncheckedBody,
                        "approval requester could not be checked: " + LogSafe.text(String.valueOf(e.getMessage)).take(200)
                    ))
            }
        requester.map(rk =>
            approver match {
                case Some(u) if u.isRestricted => narrowTo(rk, APIKeyValidator.resolveFromSession(u), bundleLabel = false)
                case _ => rk
            }
        )
    }

    /** Request attribute TenantInterceptor sets on a request it verified as
      * the replay of an approved action ([[ReplayAuthority]]) and gave the
      * requester's identity: a [[VerifiedReplay]]. PolicyInterceptor uses
      * the one-time token only on a request that holds this record for that
      * approval, so the check that set the identity and the use of the token
      * cannot disagree. */
    val ReplayVerifiedAttr: String = "ai.datris.policy.replayVerified"

    /** The approval a request was verified as, the key that presented it,
      * and the person who approved it (None when user sign-in is off). */
    final case class VerifiedReplay(approvalId: String, carrier: ResolvedKey, approver: Option[User])

    def verifiedReplay(request: HttpServletRequest): Option[VerifiedReplay] =
        if (request == null) None else Option(request.getAttribute(ReplayVerifiedAttr)).collect { case v: VerifiedReplay => v }

    /** Must PolicyInterceptor find a [[VerifiedReplay]] before it uses an
      * approval token on this request? Yes for a request the platform's own
      * carrier presented: the `ui` key with API keys on, or, with keys off, a
      * request naming a user to act for. Those are the requests whose
      * identity depends on the verification. (With keys off and no user
      * named, the request already has the open API's full access.) */
    def replayNeedsVerification(request: HttpServletRequest): Boolean = {
        val env = DatrisEnvironment.values
        val keysOn = env != null && env.useApiKeys
        val presenting = verifiedReplay(request).map(_.carrier.label)
            .orElse(Option(request.getAttribute(AuditActor.CarrierKeyLabelAttr)).collect { case l: String => l })
            .orElse(ai.datris.auth.ResolvedKeyAccess.fromRequest(request).map(_.label))
        if (keysOn) presenting.contains(AuditActor.UiKeyLabel)
        else Option(request.getHeader(AuditActor.HeaderOnBehalfOf)).exists(_.trim.nonEmpty)
    }

    /** What applyOnBehalfOf decided: the identity to attach, or a refusal
      * (status, body, audit reason) answered before any handler runs. */
    private[config] sealed trait OnBehalf
    private[config] final case class Act(identity: ResolvedKey) extends OnBehalf

    /** Writes a Decision 7 refusal (no identity is attached and no handler
      * runs) and records it, then answers false. The tenant preHandle may
      * have set is cleared in a `finally`: afterCompletion is not called for
      * an interceptor whose preHandle answered false or threw, and a write
      * to a dropped connection (or the audit call) can throw. Without it the
      * tenant would stay on the servlet thread for its next request, and
      * `/minio-events` (outside this interceptor) would run under it. */
    private[config] def answerRefusal(request: HttpServletRequest, response: HttpServletResponse, r: RefuseOnBehalf): Boolean =
        try {
            response.setStatus(r.status)
            response.setContentType("application/json")
            response.getWriter.write(r.body)
            response.getWriter.flush()
            AuditLog.denied(request, r.reason, r.status, names = r.auditNames)
            false
        } finally TenantContext.clear()

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
                        noteIgnoredIncident(request)
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
            case Some(r) => TenantInterceptor.answerRefusal(request, response, r)
            case None => true
        }
    }

    /** An incident header from a key the platform does not trust with it
      * (AuditActor.trustsIncident) is ignored by every reader
      * (AuditActor.incidentOf); the attempt is recorded once, as a
      * `security` / `spoofed-incident` event naming the presenting key. The
      * claimed id is kept as `claimedIncident`, never as `incidentId`, so the
      * event is not part of that incident's ledger. The request itself goes
      * on and gets its normal audit row. */
    private def noteIgnoredIncident(request: HttpServletRequest): Unit =
        AuditActor.ignoredIncident(request).foreach { claimed =>
            val label = ai.datris.auth.ResolvedKeyAccess.keyLabel(request).getOrElse(AuditActor.Anonymous)
            logger.warn("Ignoring " + AuditActor.HeaderIncident + " from key '" + LogSafe.text(label) + "': only the platform's own callers may send it")
            val md = new com.google.gson.JsonObject()
            md.addProperty("claimedIncident", claimed)
            val alreadyRecorded = request.getAttribute(AuditLog.RecordedAttr) != null
            AuditLog.record(request, "security", "spoofed-incident", "key", label, outcome = "denied", httpStatus = 200, metadata = md)
            if (!alreadyRecorded) request.removeAttribute(AuditLog.RecordedAttr)
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
      *   - the replay of an approved action (PolicyReplay: the header names
      *     the approver) that [[ReplayAuthority]] verifies runs with the
      *     requester's current access ([[TenantInterceptor.replayIdentity]]),
      *     narrowed by a restricted approver; a requester that no longer
      *     exists is refused 401 and an unreadable key, user or approval
      *     store 503 before anything runs or the token is used. The same
      *     holds for a replay that names no approver (user sign-in off, or
      *     no person behind the approve call), whether or not the header is
      *     sent. An approval header that does not verify grants nothing: the
      *     request is handled as if it carried none;
      *   - otherwise the identity is [[TenantInterceptor.onBehalfOfIdentity]]:
      *     an admin keeps the carrier's bundle relabelled `session:<user>`;
      *     every other user is narrowed to their own session bundle (role
      *     bundle, or catalog grants for a restricted user), so a chat acting
      *     for them can do what their role allows and no more.
      * The audit log attributes the action to the person (for a replay, the
      * approver, with the requester as `originalActor`); owner tagging uses
      * the identity's label (for a replay, the requester's).
      *
      * Any other key sending the header is ignored — and the attempt is
      * itself recorded as a security event. */
    private def applyOnBehalfOf(request: HttpServletRequest, resolved: ResolvedKey): TenantInterceptor.OnBehalf = {
        val header = Option(request.getHeader(AuditActor.HeaderOnBehalfOf)).map(_.trim).filter(_.nonEmpty)
        val env = DatrisEnvironment.values
        val trusted = AuditActor.trustsOnBehalfOf(resolved.label, env.useApiKeys)
        if (header.isEmpty || (trusted && !env.useUserAuth)) {
            // No person is named (none sent, or user sign-in is off and the
            // name is not used). The platform's own carrier may still be
            // presenting the replay of an approved action: it then runs with
            // the requester's access, as when a person is named.
            if (trusted) replayOf(request, resolved, None, env).foreach(return _)
            return TenantInterceptor.Act(resolved)
        }
        if (!trusted) {
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
        if (carriesOwnSession(request)) {
            logger.warn("Ignoring " + AuditActor.HeaderOnBehalfOf + " on a request that carries its own sign-in session")
            return TenantInterceptor.Act(resolved)
        }
        // The audit row for a refusal carries the carrier's label and the
        // claimed name (truncated); the response body names neither.
        val names = Seq("carrierKey" -> resolved.label, "claimedUser" -> header.get.take(64))
        try {
            UserStore.find(header.get) match {
                case Some(user) =>
                    replayOf(request, resolved, Some(user), env).getOrElse {
                        request.setAttribute(AuditActor.OnBehalfOfAttr, user)
                        request.setAttribute(AuditActor.CarrierKeyLabelAttr, resolved.label)
                        request.setAttribute(TenantInterceptor.OnBehalfOfCarrierAttr, resolved)
                        TenantInterceptor.Act(TenantInterceptor.onBehalfOfIdentity(resolved, user))
                    }
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

    /** The platform's own carrier `resolved` presenting a request for
      * `approver` (None: no person named): Some(identity or refusal) when it
      * is the verified replay of an approved action, or when the approval
      * store could not be read to tell (503, nothing runs); None when it is
      * not a replay (the request is handled as if it carried no approval).
      * A verified replay is recorded on the request
      * ([[TenantInterceptor.ReplayVerifiedAttr]]). */
    private def replayOf(
        request: HttpServletRequest,
        resolved: ResolvedKey,
        approver: Option[User],
        env: DatrisEnvironment
    ): Option[TenantInterceptor.OnBehalf] = {
        val names = Seq("carrierKey" -> resolved.label) ++ approver.map(u => "claimedUser" -> u.username.take(64))
        ReplayAuthority.verify(request, PolicyInterceptor.store) match {
            case ReplayAuthority.NotReplay => None
            case ReplayAuthority.Unchecked =>
                Some(TenantInterceptor.RefuseOnBehalf(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    TenantInterceptor.RequesterUncheckedBody,
                    "approval could not be checked: the approval store is unavailable",
                    names
                ))
            case ReplayAuthority.Replay(pa) =>
                val decided = TenantInterceptor.replayIdentity(pa, resolved, approver, env.useApiKeys, APIKeyValidator.resolveByLabel, UserStore.find)
                Some(decided match {
                    case Right(rk) =>
                        approver.foreach { u =>
                            request.setAttribute(AuditActor.OnBehalfOfAttr, u)
                            request.setAttribute(AuditActor.CarrierKeyLabelAttr, resolved.label)
                            request.setAttribute(TenantInterceptor.OnBehalfOfCarrierAttr, resolved)
                        }
                        request.setAttribute(TenantInterceptor.ReplayVerifiedAttr, TenantInterceptor.VerifiedReplay(pa.id, resolved, approver))
                        TenantInterceptor.Act(rk)
                    case Left((status, body, reason)) =>
                        logger.info("Approval " + pa.id + " replay refused: " + reason)
                        TenantInterceptor.RefuseOnBehalf(status, body, reason, names ++ Seq("approvalId" -> pa.id, "originalActor" -> pa.actor.label))
                })
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
