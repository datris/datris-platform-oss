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

        val approvalHeader = Option(request.getHeader(PolicyReplay.HeaderApproval)).map(_.trim).filter(_.nonEmpty)
        if (approvalHeader.isDefined) return handleReplay(request, response, approvalHeader.get)

        if (!PolicyActor.isAgent(request)) return true

        val method = request.getMethod
        val path = request.getRequestURI
        val actionKey = PolicyRoutes.actionKey(method, path).getOrElse(return true)

        // Hard rules, independent of the policy document.
        if (actionKey == "policy:update" || actionKey == "approval:decide")
            return deny(request, response, actionKey, "agents may not change the agent policy or decide approvals", hardRule = true)

        if (!PolicyRoutes.isGateable(actionKey)) return true

        val resourceType = PolicyRoutes.resourceType(actionKey)
        val resourceName = PolicyGate.resourceName(request)
        val policy = PolicyIO.current
        policy.decide(actionKey, Some(resourceType), resourceName) match {
            case PolicyMode.Auto => true
            case PolicyMode.Deny => deny(request, response, actionKey, "denied by agent policy", hardRule = false)
            case PolicyMode.Approve => queue(request, response, policy, actionKey, resourceType, resourceName)
        }
    }

    // ------------------------------------------------------------------

    private def deny(request: HttpServletRequest, response: HttpServletResponse, actionKey: String, reason: String, hardRule: Boolean): Boolean = {
        val actor = AuditActor.resolve(request)
        logger.info("agent policy: {} {} action={} actor={} outcome=deny", Array[AnyRef](request.getMethod, request.getRequestURI, actionKey, actor.label): _*)
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
        val hash = PendingAction.hashOf(actor.label, request.getMethod, request.getRequestURI, query, bodyStr)

        try {
            val existing = PendingActionIO.findPendingDuplicate(hash, actor.label)
            val pa = existing.getOrElse {
                if (PendingActionIO.countPending(actor.label) >= policy.limits.maxPendingPerActor) {
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
                    resourceVersion = resourceName.flatMap(n => PolicyGate.currentVersion(resourceType, n)),
                    actor = actor,
                    reason = reasonOf(request),
                    agentSession = Option(request.getHeader(AuditActor.HeaderAgentSession)).map(_.trim).filter(_.nonEmpty).map(_.take(64)),
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
                PendingActionIO.insert(created)
                logger.info(
                    "agent policy: {} {} action={} actor={} outcome=queued id={}",
                    Array[AnyRef](request.getMethod, request.getRequestURI, actionKey, actor.label, created.id): _*
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

    /** `X-Datris-Approval: <id>.<token>` on a replay of an approved action. */
    private def handleReplay(request: HttpServletRequest, response: HttpServletResponse, header: String): Boolean = {
        val dot = header.indexOf('.')
        val (id, token) = if (dot > 0) (header.substring(0, dot), header.substring(dot + 1)) else (header, "")
        val ok =
            try PendingActionIO.consumeReplay(id, token)
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
                true
            case None =>
                val body = new JsonObject()
                body.addProperty("error", "invalid, already used, or unapproved approval token")
                body.addProperty("errorKind", "policy_replay_rejected")
                PolicyGate.writeJson(response, HttpServletResponse.SC_FORBIDDEN, body)
                AuditLog.denied(request, "approval replay rejected for " + id, HttpServletResponse.SC_FORBIDDEN)
                false
        }
    }

    private def reasonOf(request: HttpServletRequest): Option[String] =
        Option(request.getHeader(AuditActor.HeaderReason)).map(_.trim).filter(_.nonEmpty).map(_.take(500))

    private def md(actionKey: String, approvalId: Option[String], request: HttpServletRequest): JsonObject = {
        val m = new JsonObject()
        m.addProperty("policyAction", actionKey)
        approvalId.foreach(m.addProperty("approvalId", _))
        reasonOf(request).foreach(m.addProperty("reason", _))
        m
    }
}
