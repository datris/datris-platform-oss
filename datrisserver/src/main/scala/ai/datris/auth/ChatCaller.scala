package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.ApiErrors
import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.config.{CapabilityInterceptor, TenantInterceptor}
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.util.{APIKeyValidator, SecretsUtil}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.springframework.http.{HttpHeaders, HttpStatus, MediaType, ResponseEntity}

/** What a chat endpoint decided about its caller, on the servlet thread. */
sealed trait ChatDecision

/** Refuse the request before any stream opens: write `status` with the JSON `body`. */
case class Refuse(status: Int, body: String) extends ChatDecision

/** Serve the chat as `identity`. */
case class Allow(identity: ChatIdentity) extends ChatDecision

/** Who a chat acts as, decided once on the servlet thread and handed to the
  * SSE worker as a value (never re-read from thread-locals on the worker).
  *
  * @param user   the acting user: the session user, or the user the `ui` key
  *               is acting on behalf of. Set as the worker's `UserContext`, so
  *               `MCPClient` forwards it as `X-Datris-On-Behalf-Of`.
  * @param key    the request's resolved key, when there is one.
  * @param ownKey true when the agent's tools must run with the caller's own
  *               presented key instead of the platform's `ui` key. */
case class ChatIdentity(user: Option[User], key: Option[ResolvedKey], ownKey: Boolean)

/** The one caller decision every in-platform chat endpoint makes (Ops,
  * Assistant init/chat/attachment, Catalog, Search, Configuration). The chat
  * never gives a caller more than its own credential allows:
  *   1. a tap-run token is refused (403), in every mode;
  *   2. a session user, or the `ui` key acting on behalf of a trusted user,
  *      chats as that user with the `ui` key. The chat's tool calls carry
  *      `X-Datris-On-Behalf-Of`, and TenantInterceptor narrows them to that
  *      user's role bundle (catalog grants for a restricted user); an admin
  *      keeps the `ui` key's full access;
  *   3. a full-access identity (legacy key, the `ui` key, keys-off anonymous,
  *      a multi-tenant key, an unscoped `*:*` key) uses the `ui` key, as before;
  *   4. any other key has its own presented key used for the agent's tools,
  *      so the REST layer enforces exactly that key's capabilities and scopes;
  *   5. no identity is 401, a rejected key 401, a key-store outage 503. */
object ChatCaller {

    /** Body of the 403 a tap-run token gets. Names nothing about the platform. */
    val TapTokenRefusedBody: String = "{\"error\":\"Chat is not available to a tap run\"}"

    /** Body of the 403 the Configuration chat gives a key that is not full
      * access when user auth is off. */
    val ConfigureRefusedBody: String = "{\"error\":\"Configuration chat requires a full-access API key\"}"

    /** Pure decision, rules 1-5 in order. `resolved` is the key
      * TenantInterceptor attached (relabelled / narrowed for on-behalf-of);
      * `rejection` its reason when a presented key could not be resolved. */
    def decide(
        sessionUser: Option[User],
        onBehalfOf: Option[User],
        resolved: Option[ResolvedKey],
        rejection: Option[String]
    ): ChatDecision =
        if (resolved.exists(rk => TapRunTokens.isTapLabel(rk.label)))
            Refuse(HttpServletResponse.SC_FORBIDDEN, TapTokenRefusedBody)
        else
            sessionUser.orElse(onBehalfOf) match {
                case Some(u) => Allow(ChatIdentity(Some(u), resolved, ownKey = false))
                case None =>
                    resolved match {
                        case Some(rk) => Allow(ChatIdentity(None, Some(rk), ownKey = !isFullAccess(rk)))
                        case None =>
                            rejection match {
                                case Some(reason) =>
                                    val (status, body) = CapabilityInterceptor.rejectionResponse(reason)
                                    Refuse(status, body)
                                case None => Refuse(HttpServletResponse.SC_UNAUTHORIZED, ApiErrors.AuthenticationRequiredBody)
                            }
                    }
            }

    /** [[decide]] plus the label the request's key carried BEFORE
      * TenantInterceptor relabelled it for on-behalf-of
      * (`AuditActor.CarrierKeyLabelAttr`). With API keys off any key may
      * vouch for a user, so a tap-run token sending `X-Datris-On-Behalf-Of`
      * arrives relabelled `session:<user>`; rule 1 must still see the tap. */
    def decide(
        sessionUser: Option[User],
        onBehalfOf: Option[User],
        resolved: Option[ResolvedKey],
        rejection: Option[String],
        carrierKeyLabel: Option[String]
    ): ChatDecision =
        if (carrierKeyLabel.exists(TapRunTokens.isTapLabel))
            Refuse(HttpServletResponse.SC_FORBIDDEN, TapTokenRefusedBody)
        else decide(sessionUser, onBehalfOf, resolved, rejection)

    /** The carrier label `from` passes to [[decide]]: the attribute
      * TenantInterceptor set when it relabelled the key, else the label of a
      * live tap-run token presented as `x-api-key` (whatever relabelling
      * happened afterwards). */
    private[auth] def carrierLabel(carrierAttr: Option[String], presentedKey: String): Option[String] =
        carrierAttr.filter(TapRunTokens.isTapLabel)
            .orElse(TapRunTokens.resolve(presentedKey).map(_.label))
            .orElse(carrierAttr)

    /** Install-wide full access. `grants` with no scope context, so a scoped
      * `*:*:catalog=x` does not count. */
    def isFullAccess(rk: ResolvedKey): Boolean = rk.isFullAccess

    /** May the Ops chat context carry open incidents for this caller? A user
      * is judged by their own session bundle
      * (`APIKeyValidator.resolveFromSession`: full access or unscoped
      * `incident:read`, today an admin only), whatever key comes with the
      * request; a caller with no user by its key (full access, or unscoped
      * `incident:read`). */
    def mayReadIncidents(id: ChatIdentity): Boolean =
        id.user match {
            case Some(u) =>
                val bundle = APIKeyValidator.resolveFromSession(u)
                isFullAccess(bundle) || bundle.grants("incident", "read")
            case None => id.key.exists(k => isFullAccess(k) || k.grants("incident", "read"))
        }

    /** The ceiling on a chat turn's agent iterations: the server's default.
      * A request may ask for fewer, never more. */
    val MaxIterations: Int = 50

    /** `maxIterations` from a chat request body, clamped to [[MaxIterations]];
      * the default when absent or null. */
    def maxIterations(req: com.google.gson.JsonObject): Int =
        if (req != null && req.has("maxIterations") && !req.get("maxIterations").isJsonNull)
            math.min(req.get("maxIterations").getAsInt, MaxIterations)
        else MaxIterations

    /** The Configuration chat with user auth off: only a full-access key. A
      * session user is never accepted here; with user auth on the class-level
      * role gate owns that case. */
    def mayConfigure(id: ChatIdentity): Boolean =
        id.user.isEmpty && id.key.exists(isFullAccess)

    /** The key the agent's tools run with: the caller's own presented key for
      * an own-key caller (the `ui` secret is not read), the `ui` key otherwise. */
    def toolKey(id: ChatIdentity, presented: String, uiKey: () => String): String =
        if (id.ownKey) presented else uiKey()

    /** The decision for this request, from what the interceptors attached.
      * Call on the servlet thread only. */
    def from(request: HttpServletRequest): ChatDecision =
        decide(
            UserContext.get(),
            Option(request.getAttribute(AuditActor.OnBehalfOfAttr)).collect { case u: User => u },
            ResolvedKeyAccess.fromRequest(request),
            Option(request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr)).map(_.toString),
            carrierLabel(
                Option(request.getAttribute(AuditActor.CarrierKeyLabelAttr)).map(_.toString),
                request.getHeader("x-api-key")
            )
        )

    /** The platform's `ui` key for MCP-bound calls. Null with API keys off
      * (no header is sent); the `{env}/ui-api-key` secret's `apiKey` with
      * keys on, falling back to the presented key when the secret is missing
      * or empty (installs upgraded before the secret existed). */
    def uiApiKey(presented: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _ => presented
        }
    }

    private def deniedReason(r: Refuse): String =
        if (r.status == HttpServletResponse.SC_FORBIDDEN) "chat refused for this credential"
        else if (r.status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) "API key store unavailable"
        else "authentication required"

    /** Writes the refusal as JSON and flushes, and records it in the audit
      * log. An SSE controller then returns null (the request is handled). */
    def refuse(request: HttpServletRequest, response: HttpServletResponse, r: Refuse): Unit = {
        response.setStatus(r.status)
        response.setContentType("application/json")
        response.getWriter.write(r.body)
        response.flushBuffer()
        AuditLog.denied(request, deniedReason(r), r.status)
    }

    /** The refusal as a JSON `ResponseEntity` (non-streaming endpoints), recorded
      * in the audit log. */
    def refusal(request: HttpServletRequest, r: Refuse): ResponseEntity[String] = {
        AuditLog.denied(request, deniedReason(r), r.status)
        val headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        new ResponseEntity[String](r.body, headers, HttpStatus.valueOf(r.status))
    }

    /** Runs `body` on an SSE worker thread as `identity`: clears the thread's
      * UserContext / TenantContext, sets only `identity.user` and the tenant
      * captured on the servlet thread, and clears both again afterwards. */
    def onWorker(identity: ChatIdentity, tenant: Option[DatrisEnvironment])(body: => Unit): Unit = {
        UserContext.clear()
        TenantContext.clear()
        identity.user.foreach(UserContext.set)
        tenant.foreach(TenantContext.set)
        try body
        finally {
            UserContext.clear()
            TenantContext.clear()
        }
    }

    /** Runs `body` on the servlet thread with `identity.user` as UserContext
      * (on-behalf-of callers have none there), restoring the previous value. */
    def asUser[T](identity: ChatIdentity)(body: => T): T = {
        val previous = UserContext.get()
        identity.user.foreach(UserContext.set)
        try body
        finally previous match {
                case Some(u) => UserContext.set(u)
                case None => UserContext.clear()
            }
    }
}
