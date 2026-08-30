package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditLog}
import ai.datris.config.RequiresRole
import ai.datris.model.UserContext
import ai.datris.policy._
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.time.Instant

/** Pending agent actions parked by the policy, and the human decisions on
  * them. Agents see only what they queued; people see everything. Deciding
  * is for admins and editors — and never for an agent, whatever key it
  * holds. */
@RestController
@RequestMapping(Array("/api/v1/approvals"))
class ApprovalsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ApprovalsAPIController])
    private val gson = new Gson()
    private val ResultBodyClip = 4000

    @GetMapping(produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def list(
        @RequestParam(name = "state", required = false) state: String,
        @RequestParam(name = "actor", required = false) actor: String,
        @RequestParam(name = "limit", required = false) limit: Integer,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val out = new JsonObject()
            out.addProperty("enabled", PolicyIO.enabled)
            val arr = new JsonArray()
            if (PolicyIO.enabled) {
                val st = Option(state).map(_.trim).filter(_.nonEmpty)
                if (st.exists(s => !PendingAction.States.contains(s)))
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        errorJson("state must be one of " + PendingAction.States.toSeq.sorted.mkString(", "))
                    )
                val actorFilter =
                    if (PolicyActor.isAgent(request)) Some(PolicyActor.label(request))
                    else Option(actor).map(_.trim).filter(_.nonEmpty)
                val lim = Option(limit).map(_.intValue()).getOrElse(100)
                PendingActionIO.list(st, actorFilter, lim).foreach(pa => arr.add(pa.toPublicJson))
            }
            out.add("approvals", arr)
            new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error listing approvals: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    @GetMapping(path = Array("/{id}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def get(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!PolicyIO.enabled) return disabled()
            visible(id, request) match {
                case Some(pa) => new ResponseEntity[String](gson.toJson(pa.toPublicJson), HttpStatus.OK)
                case None => notFound(id)
            }
        } catch {
            case e: Exception =>
                logger.error("Error reading approval: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    @PostMapping(path = Array("/{id}/approve"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin", "editor"))
    def approve(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!PolicyIO.enabled) return disabled()
            if (PolicyActor.isAgent(request))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](errorJson("agents may not decide approvals"))
            val pa = PendingActionIO.get(id).getOrElse(return notFound(id))
            if (pa.isExpired())
                return ResponseEntity.status(HttpStatus.GONE).body[String](stateJson(
                    pa.copy(state = PendingAction.Expired),
                    "this approval expired before a decision was made"
                ))
            if (!pa.isPending)
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](stateJson(pa, "this approval was already decided"))

            // Approve what was proposed, not what the agent would propose now.
            val currentVersion = for { t <- pa.resourceType; n <- pa.resourceName; v <- PolicyGate.currentVersion(t, n) } yield v
            (pa.resourceVersion, currentVersion) match {
                case (Some(queued), Some(live)) if queued != live =>
                    val out = pa.toPublicJson
                    out.addProperty("error", "stale")
                    out.addProperty("errorKind", "approval_stale")
                    out.addProperty("liveVersion", live)
                    out.addProperty(
                        "message",
                        "The " + pa.resourceType.getOrElse(
                            "resource"
                        ) + " changed (version " + queued + " → " + live + ") since this action was queued. Reject it and have the agent propose again."
                    )
                    return ResponseEntity.status(HttpStatus.CONFLICT).body[String](gson.toJson(out))
                case _ =>
            }

            val approver = approverLabel(request)
            val token = PendingAction.newToken()
            val now = Instant.now()
            val moved = PendingActionIO.transition(
                pa.id,
                PendingAction.Pending,
                PendingAction.Approved,
                "decidedBy" -> approver,
                "decidedAt" -> now.toString,
                "replayToken" -> token
            )
            if (!moved)
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson("this approval was decided concurrently"))
            AuditLog.record(request, "approval", "approve", "approval", pa.id, metadata = decisionMd(pa))

            val result =
                try PolicyReplay.execute(pa, token, UserContext.get().map(_.username))
                catch {
                    case e: Exception =>
                        logger.error("Approval " + pa.id + " replay failed: " + e.getMessage, e)
                        PolicyReplay.Result(0, "replay failed: " + e.getMessage)
                }
            val ok = result.status >= 200 && result.status < 300
            PendingActionIO.transition(
                pa.id,
                PendingAction.Approved,
                if (ok) PendingAction.Executed else PendingAction.Failed,
                "executedAt" -> Instant.now().toString,
                "resultStatus" -> (result.status: java.lang.Integer),
                "resultBody" -> Option(result.body).getOrElse("").take(ResultBodyClip)
            )
            logger.info("Approval " + pa.id + " (" + pa.action + ") approved by " + approver + " → HTTP " + result.status)
            val updated = PendingActionIO.get(pa.id).getOrElse(pa)
            new ResponseEntity[String](gson.toJson(updated.toPublicJson), if (ok) HttpStatus.OK else HttpStatus.BAD_GATEWAY)
        } catch {
            case e: Exception =>
                logger.error("Error approving " + id + ": " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    @PostMapping(path = Array("/{id}/reject"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin", "editor"))
    def reject(@PathVariable("id") id: String, @RequestBody(required = false) body: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!PolicyIO.enabled) return disabled()
            if (PolicyActor.isAgent(request))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](errorJson("agents may not decide approvals"))
            val pa = PendingActionIO.get(id).getOrElse(return notFound(id))
            if (!pa.isPending || pa.isExpired())
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](stateJson(pa, "this approval is no longer pending"))
            val note = Option(body).flatMap { b =>
                try {
                    val el = JsonParser.parseString(b)
                    if (el.isJsonObject && el.getAsJsonObject.has("note")) Some(el.getAsJsonObject.get("note").getAsString.take(500)) else None
                } catch { case _: Exception => None }
            }
            val approver = approverLabel(request)
            val sets = Seq[(String, Any)]("decidedBy" -> approver, "decidedAt" -> Instant.now().toString) ++ note.map(n => "decisionNote" -> (n: Any))
            val moved = PendingActionIO.transition(pa.id, PendingAction.Pending, PendingAction.Rejected, sets: _*)
            if (!moved)
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson("this approval was decided concurrently"))
            AuditLog.record(request, "approval", "reject", "approval", pa.id, metadata = decisionMd(pa))
            logger.info("Approval " + pa.id + " (" + pa.action + ") rejected by " + approver)
            val updated = PendingActionIO.get(pa.id).getOrElse(pa)
            new ResponseEntity[String](gson.toJson(updated.toPublicJson), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error rejecting " + id + ": " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------

    private def visible(id: String, request: HttpServletRequest): Option[PendingAction] =
        PendingActionIO.get(id).filter(pa => !PolicyActor.isAgent(request) || pa.actor.label == PolicyActor.label(request))

    private def approverLabel(request: HttpServletRequest): String =
        UserContext.get().map(_.username).getOrElse(AuditActor.resolve(request).label)

    private def decisionMd(pa: PendingAction): JsonObject = {
        val m = new JsonObject()
        m.addProperty("policyAction", pa.action)
        pa.resourceType.foreach(m.addProperty("resourceType", _))
        pa.resourceName.foreach(m.addProperty("resource", _))
        m.addProperty("originalActor", pa.actor.label)
        m.addProperty("originalActorType", pa.actor.actorType)
        pa.reason.foreach(m.addProperty("reason", _))
        m
    }

    private def stateJson(pa: PendingAction, message: String): String = {
        val out = pa.toPublicJson
        out.addProperty("error", message)
        gson.toJson(out)
    }

    private def disabled(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\":\"agent policy is disabled\",\"enabled\":false}")

    private def notFound(id: String): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no approval " + id))

    private def errorJson(msg: String): String =
        "{\"error\":\"" + Option(msg).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
}
