package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.policy._
import com.google.gson.JsonObject
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

import java.time.Instant
import java.time.temporal.ChronoUnit

/** The agent policy gate. Runs after CapabilityInterceptor (a key that may
  * not do something at all is refused there, as today) and before the role
  * and audit interceptors.
  *
  * For an agent-initiated request whose action the policy marks:
  *   - `auto`    → continue, nothing changes.
  *   - `deny`    → 403 with a parseable body; recorded as a security denial.
  *   - `approve` → the request is NOT executed. It is stored as a
  *                 [[PendingAction]] and the caller gets 202 with an
  *                 `approvalId`. A human approves or rejects it later; on
  *                 approval the stored request is replayed through this same
  *                 chain carrying `X-Datris-Approval`, which this interceptor
  *                 recognizes and consumes (single use) instead of gating.
  *
  * Humans are never gated (see [[PolicyActor]]). Two rules are not policy
  * but hard-wired: an agent can never change the policy, and can never
  * decide an approval. Inert while `useAgentPolicy` is off. */
@Component
class PolicyInterceptor extends HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(getClass)

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (!PolicyIO.enabled) return true
        // Only a request the dispatcher routes to a controller method is an
        // action: anything else (the not-found answer, static resources) is
        // never gated, queued or replayed, and gets the dispatcher's answer.
        if (!handler.isInstanceOf[HandlerMethod]) return true

        val approvalHeader = Option(request.getHeader(PolicyReplay.HeaderApproval)).map(_.trim).filter(_.nonEmpty)
        if (approvalHeader.isDefined) handleReplay(request, response, approvalHeader.get).foreach(answer => return answer)
        // A valid approval for a different request falls through: it is gated
        // as if it carried no approval.

        if (!PolicyActor.isAgent(request)) return true

        // The route as the dispatcher matched it (RequestPaths), so the
        // policy decides on the same route the capability gate did. The
        // stored approval keeps the raw request URI: that is what a replay
        // sends again.
        val method = ai.datris.auth.RequestPaths.method(request)
        val path = ai.datris.auth.RequestPaths.path(request)
        val actionKey = PolicyRoutes.actionKey(method, path).getOrElse(return true)

        // Hard rules, independent of the policy document.
        if (actionKey == "policy:update" || actionKey == "approval:decide")
            return deny(request, response, actionKey, "agents may not change the agent policy or decide approvals", hardRule = true)

        if (!PolicyRoutes.isGateable(actionKey)) return true

        val resourceType = PolicyRoutes.resourceType(actionKey)
        val resourceName = PolicyGate.resourceName(request)
        val policy = PolicyIO.current
        val matrixMode = policy.decide(actionKey, Some(resourceType), resourceName)
        // Recovery-agent calls (tagged with an incident id) additionally obey
        // the recovery mode: `propose` parks EVERY mutation for approval
        // regardless of the action matrix; `off` refuses them outright — the
        // runner should not be acting at all. `autopilot` follows the matrix.
        val incidentTagged = Option(request.getHeader(AuditActor.HeaderIncident)).exists(_.trim.nonEmpty)
        val effectiveMode =
            if (!incidentTagged) matrixMode
            else policy.effectiveRecoveryMode(resourceType, resourceName.getOrElse("")) match {
                case ai.datris.policy.RecoverySettings.Off => PolicyMode.Deny
                case ai.datris.policy.RecoverySettings.Propose => PolicyMode.stricter(matrixMode, PolicyMode.Approve)
                case _ => matrixMode
            }
        effectiveMode match {
            case PolicyMode.Auto => true
            case PolicyMode.Deny => deny(request, response, actionKey, "denied by agent policy", hardRule = false)
            case PolicyMode.Approve => queue(request, response, policy, actionKey, resourceType, resourceName)
        }
    }

    // ------------------------------------------------------------------

    private def deny(request: HttpServletRequest, response: HttpServletResponse, actionKey: String, reason: String, hardRule: Boolean): Boolean = {
        val actor = AuditActor.resolve(request)
        logger.info(
            "agent policy: {} {} action={} actor={} outcome=deny",
            Array[AnyRef](
                ai.datris.util.LogSafe.text(request.getMethod),
                ai.datris.util.LogSafe.text(request.getRequestURI),
                actionKey,
                ai.datris.util.LogSafe.text(actor.label)
            ): _*
        )
        val body = new JsonObject()
        body.addProperty("error", reason)
        body.addProperty("errorKind", "policy_denied")
        body.addProperty("action", actionKey)
        body.addProperty(
            "message",
            "The agent policy on this Datris instance does not allow agents to perform '" + actionKey + "'. " +
                (if (hardRule) "This rule cannot be changed by policy: only a person can do this. "
                 else "An administrator can change this under Configuration → Agent Policy. ") +
                "Tell the user; do not retry."
        )
        PolicyGate.writeJson(response, HttpServletResponse.SC_FORBIDDEN, body)
        AuditLog.denied(request, reason + " (" + actionKey + ")", HttpServletResponse.SC_FORBIDDEN, required = Some(actionKey))
        false
    }

    private def queue(
        request: HttpServletRequest,
        response: HttpServletResponse,
        policy: AgentPolicy,
        actionKey: String,
        resourceType: String,
        resourceName: Option[String]
    ): Boolean = {
        val actor = AuditActor.resolve(request)
        if (!PolicyGate.isQueueable(request)) {
            val body = new JsonObject()
            body.addProperty("status", "not_queueable")
            body.addProperty("action", actionKey)
            body.addProperty(
                "message",
                "This action requires human approval under the agent policy, but its request body cannot be stored for later replay " +
                    "(file uploads and very large bodies are not queueable). Ask the user to perform it in the Datris UI."
            )
            PolicyGate.writeJson(response, HttpServletResponse.SC_CONFLICT, body)
            AuditLog.record(
                request,
                "policy",
                "not-queueable",
                resourceType,
                resourceName.orNull,
                outcome = "denied",
                httpStatus = HttpServletResponse.SC_CONFLICT,
                metadata = md(actionKey, None, request)
            )
            return false
        }

        val bodyStr = PolicyGate.bodyString(request)
        val query = Option(request.getQueryString).filter(_.nonEmpty)
        val hash = PolicyInterceptor.dedupHash(actor.label, request, query, bodyStr)
        val store = PolicyInterceptor.store

        try {
            val existing = PolicyInterceptor.pendingDuplicate(store, hash, actor.label)
            val pa = existing.getOrElse {
                if (store.countPending(actor.label) >= policy.limits.maxPendingPerActor) {
                    val body = new JsonObject()
                    body.addProperty("error", "too many pending approvals for this agent")
                    body.addProperty("errorKind", "policy_queue_full")
                    body.addProperty("message", "Wait for a person to decide the existing pending approvals before requesting more.")
                    PolicyGate.writeJson(response, 429, body)
                    return false
                }
                val now = Instant.now()
                val created = PendingAction(
                    id = PendingAction.newId(),
                    action = actionKey,
                    resourceType = Some(resourceType),
                    resourceName = resourceName,
                    resourceVersion =
                        if (PolicyRoutes.targetsCatalog(actionKey)) None
                        else resourceName.flatMap(n => store.currentVersion(resourceType, n)),
                    actor = actor,
                    reason = reasonOf(request),
                    agentSession = Option(request.getHeader(AuditActor.HeaderAgentSession)).map(_.trim).filter(_.nonEmpty).map(_.take(64)),
                    incidentId = Option(request.getHeader(AuditActor.HeaderIncident)).map(_.trim).filter(_.nonEmpty).map(_.take(64)),
                    method = request.getMethod,
                    path = request.getRequestURI,
                    query = query,
                    contentType = Option(request.getContentType).filter(_.nonEmpty),
                    body = bodyStr,
                    bodyHash = hash,
                    createdAt = now,
                    expiresAt = now.plus(policy.limits.pendingTtlHours.toLong, ChronoUnit.HOURS),
                    state = PendingAction.Pending
                )
                store.insert(created)
                logger.info(
                    "agent policy: {} {} action={} actor={} outcome=queued id={}",
                    Array[AnyRef](
                        ai.datris.util.LogSafe.text(request.getMethod),
                        ai.datris.util.LogSafe.text(request.getRequestURI),
                        actionKey,
                        ai.datris.util.LogSafe.text(actor.label),
                        created.id
                    ): _*
                )
                AuditLog.record(
                    request,
                    "policy",
                    "queued",
                    resourceType,
                    resourceName.orNull,
                    httpStatus = HttpServletResponse.SC_ACCEPTED,
                    metadata = md(actionKey, Some(created.id), request)
                )
                created
            }

            val body = new JsonObject()
            body.addProperty("status", "pending_approval")
            body.addProperty("approvalId", pa.id)
            body.addProperty("action", actionKey)
            body.addProperty("resourceType", resourceType)
            resourceName.foreach(body.addProperty("resource", _))
            body.addProperty("expiresAt", pa.expiresAt.toString)
            body.addProperty("duplicate", existing.isDefined)
            body.addProperty(
                "message",
                "This action was NOT performed. The agent policy requires a person to approve '" + actionKey + "' first; it is queued as approval " + pa.id + ". " +
                    "Tell the user it is waiting for approval in Datris (Activity → Approvals). " +
                    "To wait for the decision, poll get_approval with this id, pausing with wait_seconds between polls; " +
                    "do not re-issue the action — a retry returns this same id."
            )
            PolicyGate.writeJson(response, HttpServletResponse.SC_ACCEPTED, body)
            false
        } catch {
            case e: Exception =>
                logger.error("agent policy: could not queue " + actionKey + " for approval: " + e.getMessage, e)
                val body = new JsonObject()
                body.addProperty("error", "could not queue the action for approval: " + e.getMessage)
                body.addProperty("errorKind", "policy_queue_error")
                PolicyGate.writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, body)
                false
        }
    }

    /** `X-Datris-Approval: <id>.<token>` on a replay of an approved action.
      * Some(true): the approved request itself, its single-use token consumed;
      * the policy gate is skipped and the audit row carries the approval.
      * Some(false): an invalid, used or unapproved token (403, as before).
      * None: a valid token presented on a request other than the approved
      * one ([[PolicyInterceptor.replayMatches]]): nothing is consumed or
      * stamped, and the request is gated as if it carried no token. */
    private def handleReplay(request: HttpServletRequest, response: HttpServletResponse, header: String): Option[Boolean] = {
        val dot = header.indexOf('.')
        val (id, token) = if (dot > 0) (header.substring(0, dot), header.substring(dot + 1)) else (header, "")
        val store = PolicyInterceptor.store
        val stored =
            try store.get(id)
            catch { case e: Exception => logger.warn("approval replay lookup failed: " + e.getMessage); None }
        stored match {
            case Some(pa) if PolicyInterceptor.replayable(pa, token) && !PolicyInterceptor.replayMatches(request, pa) =>
                logger.info("agent policy: approval {} presented on a request other than the approved one; gated as usual", pa.id)
                return None
            case _ =>
        }
        val ok =
            try store.consumeReplay(id, token)
            catch { case e: Exception => logger.warn("approval replay lookup failed: " + e.getMessage); None }
        ok match {
            case Some(pa) =>
                request.setAttribute(AuditActor.ApprovalReplayAttr, pa.id)
                val m = new JsonObject()
                m.addProperty("approvalId", pa.id)
                m.addProperty("originalActor", pa.actor.label)
                m.addProperty("originalActorType", pa.actor.actorType)
                pa.decidedBy.foreach(m.addProperty("approvedBy", _))
                pa.reason.foreach(m.addProperty("reason", _))
                request.setAttribute(AuditLog.MetadataAttr, m)
                Some(true)
            case None =>
                val body = new JsonObject()
                body.addProperty("error", "invalid, already used, or unapproved approval token")
                body.addProperty("errorKind", "policy_replay_rejected")
                PolicyGate.writeJson(response, HttpServletResponse.SC_FORBIDDEN, body)
                AuditLog.denied(request, "approval replay rejected for " + ai.datris.util.LogSafe.text(id), HttpServletResponse.SC_FORBIDDEN)
                Some(false)
        }
    }

    private def reasonOf(request: HttpServletRequest): Option[String] =
        Option(request.getHeader(AuditActor.HeaderReason)).map(_.trim).filter(_.nonEmpty).map(_.take(500))

    private def md(actionKey: String, approvalId: Option[String], request: HttpServletRequest): JsonObject = {
        val m = new JsonObject()
        m.addProperty("policyAction", actionKey)
        approvalId.foreach(m.addProperty("approvalId", _))
        reasonOf(request).foreach(m.addProperty("reason", _))
        Option(request.getHeader(AuditActor.HeaderIncident)).map(_.trim).filter(_.nonEmpty).foreach(i => m.addProperty("incidentId", i.take(64)))
        m
    }
}

object PolicyInterceptor {

    /** Where pending actions live. [[MongoStore]] in the server; specs swap it. */
    trait Store {
        def get(id: String): Option[PendingAction]
        def consumeReplay(id: String, token: String): Option[PendingAction]
        def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction]
        def countPending(actorLabel: String): Long
        def listPending(actorLabel: String): List[PendingAction]
        def insert(pa: PendingAction): Unit
        def currentVersion(resourceType: String, name: String): Option[Int]
    }

    object MongoStore extends Store {
        def get(id: String): Option[PendingAction] = PendingActionIO.get(id)
        def consumeReplay(id: String, token: String): Option[PendingAction] = PendingActionIO.consumeReplay(id, token)
        def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction] = PendingActionIO.findPendingDuplicate(hash, actorLabel)
        def countPending(actorLabel: String): Long = PendingActionIO.countPending(actorLabel)
        def listPending(actorLabel: String): List[PendingAction] = PendingActionIO.list(Some(PendingAction.Pending), Some(actorLabel), Int.MaxValue)
        def insert(pa: PendingAction): Unit = PendingActionIO.insert(pa)
        def currentVersion(resourceType: String, name: String): Option[Int] = PolicyGate.currentVersion(resourceType, name)
    }

    @volatile private[datris] var store: Store = MongoStore

    /** An approved action whose replay token is `token` and not used yet. */
    def replayable(pa: PendingAction, token: String): Boolean =
        pa != null && pa.state == PendingAction.Approved && token != null && token.nonEmpty &&
            pa.replayToken.contains(token) && pa.replayConsumedAt.isEmpty

    /** Is `request` the approved action `pa`: the same canonical method and
      * path ([[ai.datris.auth.RequestPaths]], the stored raw path read the
      * same way) and the same resource name? The replay re-sends the stored
      * raw path, so every spelling that could have been queued matches. */
    def replayMatches(request: HttpServletRequest, pa: PendingAction): Boolean = {
        val path = ai.datris.auth.RequestPaths.path(request)
        path != null &&
        ai.datris.auth.RequestPaths.method(request) == ai.datris.auth.RequestPaths.method(pa.method) &&
        path == ai.datris.auth.RequestPaths.canonicalOf(pa.path) &&
        PolicyGate.resourceName(request) == pa.resourceName
    }

    /** The dedup hash of a request: over its canonical method and path
      * ([[ai.datris.auth.RequestPaths]]), so two spellings of one route are
      * one action. */
    def dedupHash(actorLabel: String, request: HttpServletRequest, query: Option[String], body: Option[String]): String =
        PendingAction.hashOf(actorLabel, ai.datris.auth.RequestPaths.method(request), ai.datris.auth.RequestPaths.path(request), query, body)

    /** [[dedupHash]] of a stored pending action, from its stored method and
      * raw path read the same way. */
    def dedupHashOf(pa: PendingAction): String =
        PendingAction.hashOf(pa.actor.label, ai.datris.auth.RequestPaths.method(pa.method), ai.datris.auth.RequestPaths.canonicalOf(pa.path), pa.query, pa.body)

    /** The pending action that is this request: by its stored hash, else (an
      * action queued before the hash was canonical, whose stored hash is over
      * the raw method and URI) by recomputing the canonical hash of each of
      * the actor's pending actions. Stored documents are never rewritten. */
    def pendingDuplicate(store: Store, hash: String, actorLabel: String): Option[PendingAction] =
        store.findPendingDuplicate(hash, actorLabel).orElse(store.listPending(actorLabel).find(pa => dedupHashOf(pa) == hash))
}
