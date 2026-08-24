package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model.{DatrisException, ResolvedKey}
import jakarta.servlet.http.HttpServletRequest

/** Thrown by [[CapabilityCheck.assertScope]] on a scope denial. A distinct
  * type so controller catch blocks can map it to a clean 403 (matching the
  * CapabilityInterceptor's enforce-mode denials) instead of the generic
  * 500-with-stacktrace path. */
class CapabilityDeniedException(msg: String) extends DatrisException(msg)

/** In-action scope check helper, called by controllers AFTER loading the
  * target resource. The CapabilityInterceptor's pre-action gate is scope-
  * agnostic: it confirms the request's key holds SOME capability for the
  * route's resource+action, but cannot evaluate scope predicates (owner,
  * _type, catalog) without the actual resource in hand.
  *
  * Controllers complete the check here:
  *   - load the target resource
  *   - build a context map from its scope fields
  *   - call assertScope(...) — throws DatrisException("capability denied")
  *     if no capability the key holds satisfies the scope
  *
  * The exception bubbles up to the controller's catch block, which converts
  * it to a 403 response — same shape as a CapabilityInterceptor enforce-mode
  * deny. */
object CapabilityCheck {

    /** Throw a `DatrisException` if the resolved key on the request does
      * not hold a capability that grants (resource, action) under the
      * given context. No-op if the request has no ResolvedKey (unauth-
      * enticated probe paths) — the CapabilityInterceptor's no-key branch
      * already decided whether to let those through.
      *
      * Returns normally on success, so callers can chain:
      *   CapabilityCheck.assertScope(request, "secret", "write", Map("_type" -> existingType))
      *   SecretsUtil.writeSecret(...) */
    def assertScope(
        request: HttpServletRequest,
        resource: String,
        action: String,
        context: Map[String, String]
    ): Unit = {
        val resolved = readResolvedKey(request)
        resolved.foreach { rk =>
            if (!rk.grants(resource, action, context)) {
                throw new CapabilityDeniedException(
                    "capability denied: key '" + rk.label + "' does not hold capability '" +
                        resource + ":" + action + "' for the targeted resource " +
                        "(scope: " + scopeStr(context) + ")"
                )
            }
        }
    }

    /** Convenience overload for the most common scope check: a single
      * `owner` field on a loaded resource against `owner=self` capabilities. */
    def assertOwnerScope(
        request: HttpServletRequest,
        resource: String,
        action: String,
        resourceOwner: String
    ): Unit = {
        val ctx = if (resourceOwner == null) Map.empty[String, String] else Map("owner" -> resourceOwner)
        assertScope(request, resource, action, ctx)
    }

    /** Same as assertScope but returns a boolean instead of throwing. Used
      * by list endpoints to filter result sets, where "denied" means
      * "exclude from the list" rather than "fail the whole request". */
    def grants(
        request: HttpServletRequest,
        resource: String,
        action: String,
        context: Map[String, String]
    ): Boolean = {
        readResolvedKey(request) match {
            case Some(rk) => rk.grants(resource, action, context)
            case None => true
        }
    }

    /** Returns true if the resolved key has any capability for the
      * resource+action *that includes an `owner=self` scope*. Used by list
      * endpoints to decide whether to filter results to owned items only.
      * (If the key also holds an unscoped capability for the same action,
      * that broader grant means no filtering is needed.) */
    def hasOnlyOwnerSelfScope(
        request: HttpServletRequest,
        resource: String,
        action: String
    ): Boolean = {
        readResolvedKey(request) match {
            case Some(rk) if !rk.isLegacyFullAccess =>
                val matching = rk.capabilities.filter(_.matchesResourceAction(resource, action))
                if (matching.isEmpty) return false
                // True iff EVERY matching capability has owner=self scope.
                matching.forall(c => c.scope.get("owner").contains("self"))
            case _ => false
        }
    }

    private def readResolvedKey(request: HttpServletRequest): Option[ResolvedKey] = {
        if (request == null) return None
        request.getAttribute(TenantInterceptor.ResolvedKeyAttr) match {
            case rk: ResolvedKey => Some(rk)
            case _ => None
        }
    }

    private def scopeStr(context: Map[String, String]): String = {
        if (context.isEmpty) "—"
        else context.toSeq.sortBy(_._1).map { case (k, v) => k + "=" + v }.mkString(", ")
    }
}
