package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.config.TenantInterceptor
import ai.datris.model.{DatrisException, ResolvedKey}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

/** Thrown by [[CapabilityCheck.assertScope]] on a scope denial. A distinct
  * type so controller catch blocks can map it to a clean 403 (matching the
  * CapabilityInterceptor's enforce-mode denials) instead of the generic
  * 500-with-stacktrace path.
  *
  * `getMessage` is what the caller may see (it never names the target's
  * catalog, owner or `_type`). `detail`, when set, is the full text with the
  * target's scope, for the server log and the audit log only: it must never
  * be put into a response. */
class CapabilityDeniedException(msg: String, val detail: String = null) extends DatrisException(msg)

/** Thrown by [[CapabilityCheck.assertVisible]] when a restricted user's
  * session targets a resource outside its catalog grants. Controllers map it
  * to their existing "not found" 404 body so the resource's existence never
  * leaks. Deliberately NOT a [[CapabilityDeniedException]]: a catch block for
  * the 403 must not swallow it. */
class ResourceHiddenException(msg: String) extends DatrisException(msg)

/** In-action scope check helper, called by controllers AFTER loading the
  * target resource. The CapabilityInterceptor's pre-action gate is scope-
  * agnostic: it confirms the request's key holds SOME capability for the
  * route's resource+action, but cannot evaluate scope predicates (owner,
  * _type, catalog) without the actual resource in hand.
  *
  * Controllers complete the check here:
  *   - load the target resource
  *   - build a context map from its scope fields
  *   - call assertScope(...) — throws [[CapabilityDeniedException]]
  *     if no capability the key holds satisfies the scope
  *
  * The exception bubbles up to the controller's catch block, which converts
  * it to a 403 response — same shape as a CapabilityInterceptor enforce-mode
  * deny. The message names the key, the capability it lacks and the key's
  * own capabilities for that resource, never the target's scope; the target's
  * scope goes to the server log and (via [[AuditLog.noteScopeDenial]]) the
  * audit log. */
object CapabilityCheck {

    private val logger = LoggerFactory.getLogger(getClass)

    /** How many of the key's own capabilities a refusal lists. */
    private val MaxListedCapabilities = 8

    /** Throw a [[CapabilityDeniedException]] if the resolved key on the
      * request does not hold a capability that grants (resource, action)
      * under the given context. No-op if the request has no ResolvedKey
      * (unauthenticated probe paths) — the CapabilityInterceptor's no-key
      * branch already decided whether to let those through.
      *
      * On a denial the full text with the target's scope is logged at INFO
      * and noted on the request for the audit log; the exception's message
      * carries only the key's side ([[scopeDenialMessage]]).
      *
      * On the verified replay of an approved action with a second limit
      * ([[replaySecondLimit]]: the requester's identity beside a restricted
      * approver's), that limit must grant it too; it is checked after the
      * request's identity, with the same wording naming that identity.
      *
      * Returns normally on success, so callers can chain:
      *   CapabilityCheck.assertScope(request, "secret", "write", Map("_type" -> existingType))
      *   SecretsUtil.writeSecret(...) */
    def assertScope(
        request: HttpServletRequest,
        resource: String,
        action: String,
        context: Map[String, String]
    ): Unit = checkedIdentities(request).foreach(denyUnlessGranted(request, _, resource, action, context))

    /** The identities a check applies to: the request's identity, then the
      * second limit of a verified replay ([[replaySecondLimit]]), if any. */
    private def checkedIdentities(request: HttpServletRequest): Seq[ResolvedKey] =
        readResolvedKey(request).toSeq ++ replaySecondLimit(request).toSeq

    /** [[assertScope]] for one identity. */
    private def denyUnlessGranted(request: HttpServletRequest, rk: ResolvedKey, resource: String, action: String, context: Map[String, String]): Unit =
        if (!rk.grants(resource, action, context)) {
            val detail = scopeDenialDetail(rk, resource, action, context)
            logger.info("capability scope denial: " + detail)
            AuditLog.noteScopeDenial(request, resource + ":" + action, detail)
            throw new CapabilityDeniedException(scopeDenialMessage(rk, resource, action), detail)
        }

    /** The second limit a verified replay is also held to
      * (`TenantInterceptor.replaySecondLimit`: the requester's identity when
      * a restricted approver's identity runs the replay); None on every
      * other request. */
    def replaySecondLimit(request: HttpServletRequest): Option[ResolvedKey] =
        if (request == null) None else TenantInterceptor.replaySecondLimit(request)

    /** The `collection=` check on `search:vector` and `document:upload`
      * (plans/stories/api-key-inert-scopes.md, Fix 2). Each entry of
      * `collections` is a container the request reaches, as
      * [[CapabilityScopes.collectionNameOpt]] names it (None: no scope value
      * names it, so only an unscoped capability covers it); an empty list
      * (no vector container) is likewise covered only unscoped.
      *
      * Applied only when one of the key's applied capabilities matches
      * (resource, action). Otherwise the route gate already decided: the key
      * passed only through an inert capability under
      * `DATRIS_KEY_INERT_SCOPES=allow`, or under
      * `CAPABILITY_ENFORCEMENT=log-only` as would-deny, and both keep their
      * v1.60.0 answer. No-op for no identity and legacy keys. Decided for
      * the request's identity and then, the same way, for a verified
      * replay's second limit ([[replaySecondLimit]]). */
    def assertCollectionScope(
        request: HttpServletRequest,
        resource: String,
        action: String,
        collections: Seq[Option[String]]
    ): Unit =
        checkedIdentities(request).foreach { rk =>
            if (!rk.isLegacyFullAccess && rk.capabilities.exists(_.matchesResourceAction(resource, action))) {
                if (collections.isEmpty) denyUnlessGranted(request, rk, resource, action, Map.empty)
                else
                    collections.foreach {
                        case Some(c) => denyUnlessGranted(request, rk, resource, action, Map("collection" -> c))
                        case None => denyUnlessGranted(request, rk, resource, action, Map.empty)
                    }
            }
        }

    /** The refusal text a caller sees: the key, the capability it lacks and
      * the key's own capabilities for `resource` (or `*`), distinct, sorted,
      * at most [[MaxListedCapabilities]]. Nothing about the target. */
    def scopeDenialMessage(rk: ResolvedKey, resource: String, action: String): String = {
        val own = rk.effectiveCapabilities.filter(c => c.resource == resource || c.resource == "*").map(_.raw).distinct.sorted
        val prefix = deniedPrefix(rk, resource, action)
        if (own.isEmpty) prefix + ". The key holds no " + resource + " capability."
        else {
            val listed = own.take(MaxListedCapabilities).mkString(", ") + (if (own.size > MaxListedCapabilities) ", ..." else "")
            prefix + ". The key's " + resource + " capabilities are: " + listed + ". The target is outside that scope."
        }
    }

    /** The full refusal text with the target's scope context. Server log and
      * audit log only; never sent to a client. */
    def scopeDenialDetail(rk: ResolvedKey, resource: String, action: String, context: Map[String, String]): String =
        deniedPrefix(rk, resource, action) + " (scope: " + scopeStr(context) + ")"

    private def deniedPrefix(rk: ResolvedKey, resource: String, action: String): String =
        "capability denied: key '" + rk.label + "' does not hold capability '" + resource + ":" + action + "' for the targeted resource"

    /** Same as [[assertScope]], but a restricted user's session
      * (`ResolvedKey.restrictedSession`) gets a [[ResourceHiddenException]]
      * instead, so the controller answers 404 as if the resource did not
      * exist. API keys keep the 403 (`CapabilityDeniedException`). Routes
      * that must not refuse a key holding the action nowhere (the route gate
      * decided) use [[assertKeyScope]] for the key side instead. A verified
      * replay's second limit ([[replaySecondLimit]]) is checked the same
      * way after the request's identity, with its own ordinary refusal
      * (404 for a restricted user's session, the 403 for a key). */
    def assertVisible(
        request: HttpServletRequest,
        resource: String,
        action: String,
        context: Map[String, String]
    ): Unit =
        checkedIdentities(request).foreach { rk =>
            if (rk.restrictedSession && !rk.grants(resource, action, context))
                throw new ResourceHiddenException("not found")
            denyUnlessGranted(request, rk, resource, action, context)
        }

    /** True when the request runs as a restricted user's session. */
    def isRestrictedSession(request: HttpServletRequest): Boolean =
        readResolvedKey(request).exists(_.restrictedSession)

    /** True when the request's identity sees only part of the install
      * ([[CallerVisibility.limited]]): a restricted user's session, or an API
      * key limited by a `catalog=` / `owner=` scope on a pipeline or tap
      * capability (plans/stories/scoped-key-read-routes.md, D1). False with no
      * identity (auth off) and for a null request. */
    def isLimited(request: HttpServletRequest): Boolean =
        readResolvedKey(request).exists(CallerVisibility.limited)

    /** [[isLimited]] for one resource ([[CallerVisibility.limitedFor]]):
      * `pipeline` or `tap`. False with no identity and for a null request. */
    def isLimitedFor(request: HttpServletRequest, resource: String): Boolean =
        readResolvedKey(request).exists(CallerVisibility.limitedFor(_, resource))

    /** For an API key only: throws [[CapabilityDeniedException]] (through
      * [[assertScope]], so the wording and the audit note are the shared ones)
      * when the key's scope refuses (resource, action) on an item with scope
      * `context` ([[CallerVisibility.keyOutOfScope]]). No-op for sessions, no
      * identity, full keys and keys whose scope cannot refuse the action. */
    def assertKeyScope(request: HttpServletRequest, resource: String, action: String, context: Map[String, String]): Unit =
        if (readResolvedKey(request).exists(rk => CallerVisibility.keyOutOfScope(rk, resource, action, context)))
            assertScope(request, resource, action, context)

    /** For an API key whose `action` (not `read`) on an item is already
      * granted: the read half of the stored-item write rule
      * ([[CallerVisibility.storedWriteDenial]] with `action` accepted), so a
      * key may write, delete, rename or run only an item it may also read.
      * Throws [[CapabilityDeniedException]] through [[assertScope]] (shared
      * wording, audit note). No-op for `read`, sessions, no identity and
      * keys the rule does not refuse. */
    def assertWriteReadable(request: HttpServletRequest, resource: String, action: String, context: Map[String, String]): Unit =
        if (action != "read")
            readResolvedKey(request).filter(!_.restrictedSession).foreach { rk =>
                CallerVisibility.storedWriteDenial(rk, resource, context, Seq(action), action).foreach(a => assertScope(request, resource, a, context))
            }

    /** [[assertVisible]] for `action`, then for an API key
      * [[assertWriteReadable]]: the older one-item seam held to the same
      * "may act and may read" rule as the shared write rule. Restricted
      * sessions keep [[assertVisible]]'s 404/403 exactly (their grants imply
      * read for every write). */
    def assertVisibleToWrite(request: HttpServletRequest, resource: String, action: String, context: Map[String, String]): Unit = {
        assertVisible(request, resource, action, context)
        assertWriteReadable(request, resource, action, context)
    }

    /** True when the request is an API key whose scope can refuse (resource,
      * action) on a named item ([[CallerVisibility.keyScopeApplies]]), so the
      * item must be read to decide. */
    def keyScopeApplies(request: HttpServletRequest, resource: String, action: String): Boolean =
        readResolvedKey(request).exists(rk => CallerVisibility.keyScopeApplies(rk, resource, action))

    /** The request's resolved identity, if any. */
    def resolvedKey(request: HttpServletRequest): Option[ResolvedKey] = readResolvedKey(request)

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
      * "exclude from the list" rather than "fail the whole request". A
      * verified replay's second limit ([[replaySecondLimit]]) must grant it
      * too. */
    def grants(
        request: HttpServletRequest,
        resource: String,
        action: String,
        context: Map[String, String]
    ): Boolean = {
        readResolvedKey(request) match {
            case Some(rk) => rk.grants(resource, action, context) && replaySecondLimit(request).forall(_.grants(resource, action, context))
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
                val matching = rk.effectiveCapabilities.filter(_.matchesResourceAction(resource, action))
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
