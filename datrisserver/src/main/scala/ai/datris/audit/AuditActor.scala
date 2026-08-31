package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{ResolvedKeyAccess, TapRunTokens}
import ai.datris.model.{ResolvedKey, User, UserContext}
import jakarta.servlet.http.HttpServletRequest

/** Resolves the actor behind a request for the audit log.
  *
  * Agents are identified by their API key, humans by their session. The
  * in-platform Assistant is the hybrid: the reserved `ui` key carries its
  * REST calls, on behalf of the human whose chat it is — propagated as the
  * `X-Datris-On-Behalf-Of` header and honored by TenantInterceptor only when
  * the presented key really is `ui` (see [[trustsOnBehalfOf]]). */
object AuditActor {

    /** Header the MCP server forwards from the Assistant's chat session. */
    val HeaderOnBehalfOf = "X-Datris-On-Behalf-Of"

    /** MCP session id forwarded on the REST hop — joins an audit entry to the
      * Agent Monitor's activity buffer while that buffer still holds it. */
    val HeaderAgentSession = "X-Datris-Agent-Session"

    /** Incident id the recovery agent attaches to every call it makes while
      * working an incident — joins audit entries to the incident record. */
    val HeaderIncident = "X-Datris-Incident"

    /** Free-text intent an agent may attach to a mutating call (the MCP
      * tools' optional `reason` argument). Stored in audit metadata and on
      * pending approvals; never interpreted. */
    val HeaderReason = "X-Datris-Reason"

    /** Request attribute (the approval id) PolicyInterceptor sets on the
      * replay of an approved agent action. The replay carries the ui key on
      * behalf of the approver; with this attribute present the approver is
      * recorded as a plain user — it is their decision being executed. */
    val ApprovalReplayAttr = "ai.datris.policy.approvalReplay"

    /** Sent by TapScriptRunner on outbound HTTP-tap endpoint calls. NOT used
      * for inbound actor resolution — a tap's identity on the platform
      * callback comes from its per-run token (label `tap:<name>`), which
      * cannot be forged by setting a header. */
    val HeaderTap = "X-Datris-Tap"

    /** Request attribute (a [[User]]) TenantInterceptor sets when an
      * on-behalf-of header was trusted and resolved to a real user. */
    val OnBehalfOfAttr = "ai.datris.audit.onBehalfOf"

    /** Request attribute holding the label the request's key had BEFORE it
      * was relabeled to `session:<user>` for on-behalf-of attribution. */
    val CarrierKeyLabelAttr = "ai.datris.audit.carrierKeyLabel"

    /** The reserved label of the key the UI / Assistant use on the MCP → REST hop. */
    val UiKeyLabel = "ui"

    val Anonymous = "anonymous"

    /** May this key vouch for a user via `X-Datris-On-Behalf-Of`?
      *
      * Only the platform's own `ui` key — its value lives in Vault and is
      * never shown in the UI, so possession already implies platform-internal
      * access. With API keys disabled there is no key to check and no
      * programmatic auth at all, so the header is accepted as-is (spoofing
      * is moot in a mode where anyone can do anything). */
    def trustsOnBehalfOf(keyLabel: String, useApiKeys: Boolean): Boolean =
        keyLabel == UiKeyLabel || !useApiKeys

    /** Pure resolution — the matrix the audit log documents. Testable without
      * a servlet request. */
    def from(
        resolved: Option[ResolvedKey],
        sessionUser: Option[User],
        onBehalfOf: Option[User],
        carrierKeyLabel: Option[String]
    ): AuditActorInfo = {
        // A tap-run token resolves to label `tap:<name>` (see TapRunTokens).
        val tapName = resolved.map(_.label).filter(TapRunTokens.isTapLabel).map(TapRunTokens.tapName)
        (sessionUser, onBehalfOf, tapName, resolved) match {
            case (Some(u), _, _, _) =>
                AuditActorInfo(
                    actorType = "user",
                    label = "session:" + u.username,
                    username = Some(u.username),
                    role = Some(u.role),
                    legacyFullAccess = u.role == User.RoleAdmin
                )
            case (None, Some(u), _, rk) =>
                AuditActorInfo(
                    actorType = "assistant",
                    label = "session:" + u.username,
                    keyLabel = carrierKeyLabel.orElse(rk.map(_.label)),
                    keyId = rk.flatMap(_.keyId),
                    username = Some(u.username),
                    role = Some(u.role),
                    legacyFullAccess = rk.exists(_.isLegacyFullAccess)
                )
            case (None, None, Some(tap), _) =>
                AuditActorInfo(actorType = "tap", label = tap)
            case (None, None, None, Some(rk)) =>
                AuditActorInfo(
                    actorType = "api-key",
                    label = rk.label,
                    keyLabel = Some(rk.label),
                    keyId = rk.keyId,
                    legacyFullAccess = rk.isLegacyFullAccess
                )
            case (None, None, None, None) =>
                // Legacy no-auth mode, or a key that failed to resolve: be
                // honest that there is no identity to record.
                AuditActorInfo(actorType = "api-key", label = Anonymous, keyLabel = Some(Anonymous), legacyFullAccess = true)
        }
    }

    def resolve(request: HttpServletRequest): AuditActorInfo = {
        val onBehalfOf = Option(request.getAttribute(OnBehalfOfAttr)).collect { case u: User => u }
        val carrier = Option(request.getAttribute(CarrierKeyLabelAttr)).collect { case s: String => s }
        val replay = request.getAttribute(ApprovalReplayAttr) != null
        val sessionUser = UserContext.get().orElse(if (replay) onBehalfOf else None)
        from(ResolvedKeyAccess.fromRequest(request), sessionUser, if (replay) None else onBehalfOf, carrier)
    }
}
