package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext}
import ai.datris.util.{APIKeyValidator, UserStore}
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
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, applyOnBehalfOf(request, resolved))
        } catch {
            case e: Throwable =>
                logger.debug("Could not resolve x-api-key into ResolvedKey: {}", e.getMessage)
        }

        true
    }

    /** The in-platform Assistant reaches REST through the MCP server with the
      * reserved `ui` key, on behalf of the human whose chat it is. The MCP
      * server forwards that user as `X-Datris-On-Behalf-Of`. When the header
      * is present AND the carrying key is one we trust to vouch for a user
      * (see AuditActor.trustsOnBehalfOf) AND the user exists, the ResolvedKey
      * is relabeled `session:<user>` — so version history, owner tagging and
      * the audit log all attribute the action to the person, exactly as if
      * they had clicked in the UI. Capabilities are NOT changed: the key's
      * bundle still governs what the call may do.
      *
      * Any other key sending the header is ignored — and the attempt is
      * itself recorded as a security event. */
    private def applyOnBehalfOf(request: HttpServletRequest, resolved: ResolvedKey): ResolvedKey = {
        val header = Option(request.getHeader(AuditActor.HeaderOnBehalfOf)).map(_.trim).filter(_.nonEmpty)
        if (header.isEmpty) return resolved
        val env = DatrisEnvironment.values
        if (!AuditActor.trustsOnBehalfOf(resolved.label, env.useApiKeys)) {
            logger.warn("Ignoring " + AuditActor.HeaderOnBehalfOf + " from key '" + resolved.label + "' — only the ui key may act on behalf of a user")
            val md = new com.google.gson.JsonObject()
            md.addProperty("claimedUser", header.get.take(64))
            // Attach the key first so the security entry names the offending
            // key rather than resolving to "anonymous".
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, resolved)
            AuditLog.record(request, "security", "spoofed-on-behalf-of", "key", resolved.label,
                outcome = "denied", httpStatus = 200, metadata = md)
            // Let the request proceed under the key's own identity; the
            // capability check still applies. Clear the mark so the audit
            // interceptor also records the underlying action normally.
            request.removeAttribute(AuditLog.RecordedAttr)
            return resolved
        }
        if (!env.useUserAuth) return resolved
        try {
            UserStore.find(header.get) match {
                case Some(user) =>
                    request.setAttribute(AuditActor.OnBehalfOfAttr, user)
                    request.setAttribute(AuditActor.CarrierKeyLabelAttr, resolved.label)
                    resolved.copy(label = "session:" + user.username)
                case None =>
                    logger.debug(AuditActor.HeaderOnBehalfOf + " names unknown user '" + header.get + "'; keeping key identity")
                    resolved
            }
        } catch {
            case e: Exception =>
                logger.debug("On-behalf-of lookup failed: " + e.getMessage)
                resolved
        }
    }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        TenantContext.clear()
    }
}
