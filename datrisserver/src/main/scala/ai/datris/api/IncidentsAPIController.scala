package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.config.RequiresRole
import ai.datris.incident.{Incident, IncidentIO}
import ai.datris.model.DatrisEnvironment
import ai.datris.policy.PolicyActor
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Read access to the recovery agent's incidents, plus a human-only
  * abandon. Nothing here opens incidents — the platform does that. */
@RestController
@RequestMapping(Array("/api/v1/incidents"))
class IncidentsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[IncidentsAPIController])
    private val gson = new Gson()

    private def enabled: Boolean =
        DatrisEnvironment.values != null && DatrisEnvironment.values.recoveryAgentEnabled

    @GetMapping(produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def list(
        @RequestParam(name = "state", required = false) state: String,
        @RequestParam(name = "limit", required = false) limit: Integer
    ): ResponseEntity[String] = {
        try {
            val out = new JsonObject()
            out.addProperty("enabled", enabled)
            val arr = new JsonArray()
            if (enabled) {
                val st = Option(state).map(_.trim).filter(_.nonEmpty)
                if (st.exists(s => s != "open" && !Incident.States.contains(s)))
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        errorJson("state must be open or one of " + Incident.States.toSeq.sorted.mkString(", "))
                    )
                IncidentIO.list(st, Option(limit).map(_.intValue()).getOrElse(50)).foreach(i => arr.add(i.toPublicJson))
            }
            out.add("incidents", arr)
            new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error listing incidents: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    @GetMapping(path = Array("/{id}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def get(@PathVariable("id") id: String): ResponseEntity[String] = {
        try {
            if (!enabled) return disabled()
            IncidentIO.get(id) match {
                case Some(i) => new ResponseEntity[String](gson.toJson(i.toPublicJson), HttpStatus.OK)
                case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            }
        } catch {
            case e: Exception =>
                logger.error("Error reading incident: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    /** A person closing an incident by hand: "stop working this". */
    @PostMapping(path = Array("/{id}/abandon"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin", "editor"))
    def abandon(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!enabled) return disabled()
            if (PolicyActor.isAgent(request))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](errorJson("agents may not abandon incidents"))
            val incident = IncidentIO.get(id).getOrElse(return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id)))
            if (!incident.isOpen)
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson("incident is already " + incident.state))
            val by = PolicyActor.label(request)
            IncidentIO.transition(id, Incident.OpenStates, Incident.Abandoned, "outcome" -> ("abandoned by " + by))
            IncidentIO.appendStep(id, ai.datris.incident.IncidentStep(java.time.Instant.now(), "close", "abandoned by " + by))
            AuditLog.record(request, "incident", "abandon", "incident", id)
            val updated = IncidentIO.get(id).getOrElse(incident)
            new ResponseEntity[String](gson.toJson(updated.toPublicJson), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error abandoning incident: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    private def disabled(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\":\"the recovery agent is disabled\",\"enabled\":false}")

    private def errorJson(msg: String): String =
        "{\"error\":\"" + Option(msg).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
}
