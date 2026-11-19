package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.config.RequiresRole
import ai.datris.auth.{CallerVisibility, CapabilityCheck, HiddenTapScrub}
import ai.datris.incident.{Incident, IncidentIO, IncidentRunner}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, TapConfig}
import ai.datris.util.{PipelineConfigIO, TapConfigIO}
import ai.datris.policy.PolicyActor
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** Read access to the recovery agent's incidents, plus a human-only
  * abandon and proceed. Nothing here opens incidents — the platform does that. */
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
        @RequestParam(name = "limit", required = false) limit: Integer,
        request: HttpServletRequest
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
                // A limited caller sees only incidents on a pipeline or tap it
                // may read (the limit applies to the stored rows first, as
                // for everyone; the response carries no count).
                val (readPipeline, readTap) = IncidentsAPIController.storeReaders()
                IncidentsAPIController
                    .visibleIncidents(request, IncidentIO.list(st, Option(limit).map(_.intValue()).getOrElse(50)), readPipeline, readTap)
                    .foreach(i => arr.add(i.toPublicJson))
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
    def get(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!enabled) return disabled()
            val (readPipeline, readTap) = IncidentsAPIController.storeReaders()
            val stored = IncidentIO.get(id)
            IncidentsAPIController.incidentRefusal(request, id, stored, readPipeline, readTap).foreach(return _)
            stored.map(i => IncidentsAPIController.shownIncident(request, i, readPipeline, readTap)) match {
                case Some(i) => new ResponseEntity[String](gson.toJson(i.toPublicJson), HttpStatus.OK)
                case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            }
        } catch {
            case e: Exception =>
                logger.error("Error reading incident: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    /** The incident's deterministic evidence (baseline / real profiles,
      * diff, blast radius, thresholds, verdict). 404 when the incident or its
      * evidence is absent (incidents opened before evidence existed). */
    @GetMapping(path = Array("/{id}/evidence"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def evidence(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!enabled) return disabled()
            val (readPipeline, readTap) = IncidentsAPIController.storeReaders()
            val stored = IncidentIO.get(id)
            IncidentsAPIController.incidentRefusal(request, id, stored, readPipeline, readTap).foreach(return _)
            stored.map(i => IncidentsAPIController.shownIncident(request, i, readPipeline, readTap)) match {
                case Some(i) =>
                    i.evidence match {
                        case Some(e) => new ResponseEntity[String](gson.toJson(e), HttpStatus.OK)
                        case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("incident " + id + " has no evidence"))
                    }
                case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            }
        } catch {
            case e: Exception =>
                logger.error("Error reading incident evidence: " + Throwables.getStackTraceAsString(e))
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
            val stored = IncidentIO.get(id)
            // An incident on a resource the caller may not read is the unknown-incident 404, before anything changes.
            val (readPipeline, readTap) = IncidentsAPIController.storeReaders()
            IncidentsAPIController.incidentRefusal(request, id, stored, readPipeline, readTap).foreach(return _)
            val incident = stored.getOrElse(return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id)))
            if (!incident.isOpen)
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson("incident is already " + incident.state))
            val by = PolicyActor.label(request)
            IncidentIO.transition(id, Incident.OpenStates, Incident.Abandoned, "outcome" -> ("abandoned by " + by))
            IncidentIO.appendStep(id, ai.datris.incident.IncidentStep(java.time.Instant.now(), "close", "abandoned by " + by))
            AuditLog.record(request, "incident", "abandon", "incident", id)
            val updated = IncidentsAPIController.shownIncident(request, IncidentIO.get(id).getOrElse(incident), readPipeline, readTap)
            new ResponseEntity[String](gson.toJson(updated.toPublicJson), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error abandoning incident: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    /** A person accepting a needs-human scratch verdict: "run it for real".
      * 409 unless the incident is parked on that verdict. */
    @PostMapping(path = Array("/{id}/proceed"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin", "editor"))
    def proceed(@PathVariable("id") id: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!enabled) return disabled()
            if (PolicyActor.isAgent(request))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](errorJson("agents may not proceed past needs-human evidence"))
            val stored = IncidentIO.get(id)
            // An incident on a resource the caller may not read is the unknown-incident 404, before anything runs.
            val (readPipeline, readTap) = IncidentsAPIController.storeReaders()
            IncidentsAPIController.incidentRefusal(request, id, stored, readPipeline, readTap).foreach(return _)
            val incident = stored.getOrElse(return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id)))
            IncidentRunner.proceed(id, PolicyActor.label(request)) match {
                case Left(msg) => ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson(msg))
                case Right(_) =>
                    AuditLog.record(request, "incident", "proceed", "incident", id)
                    val updated = IncidentsAPIController.shownIncident(request, IncidentIO.get(id).getOrElse(incident), readPipeline, readTap)
                    new ResponseEntity[String](gson.toJson(updated.toPublicJson), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error proceeding incident: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    private def disabled(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\":\"the recovery agent is disabled\",\"enabled\":false}")

    private def errorJson(msg: String): String = IncidentsAPIController.errorJson(msg)
}

/** Who sees which incident (plans/stories/scoped-key-read-routes.md, Part B,
  * lead ruling 4). A limited caller ([[CapabilityCheck.isLimited]]: a
  * restricted user's session, or an API key limited by a `catalog=` /
  * `owner=` scope) sees an incident only when it may read the incident's
  * resource (`resourceType` pipeline or tap, `resourceName`), judged on the
  * STORED item's catalog and owner ([[PipelineAPIController.mayRead]],
  * [[TapAPIController.mayRead]]); a resource no longer stored, or of any
  * other type, is hidden (fail closed). Inside a visible incident, the
  * downstream pipelines of its evidence's blast radius that the caller may
  * not read read `[hidden]` ([[HiddenTapScrub.Placeholder]]). Everyone else
  * gets the incidents unchanged and neither reader is called. */
object IncidentsAPIController {

    /** Readers on the live stores for the decisions below: each store is read
      * at most once, and only when a reader is first called (never for a
      * caller that is not limited). */
    def storeReaders(): (String => PipelineConfig, String => TapConfig) = {
        lazy val pipelines: Map[String, PipelineConfig] =
            PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName).filter(p => p != null && p.name != null).map(p => p.name -> p).toMap
        lazy val taps: Map[String, TapConfig] =
            TapConfigIO.readAll(DatrisEnvironment.current.tapTableName).filter(t => t != null && t.name != null).map(t => t.name -> t).toMap
        (n => pipelines.getOrElse(n, null), n => taps.getOrElse(n, null))
    }

    private def limitedKey(request: HttpServletRequest): Option[ResolvedKey] =
        CapabilityCheck.resolvedKey(request).filter(CallerVisibility.limited)

    /** Memoised by name for one call. */
    private def memo[T](read: String => T): String => T = {
        val m = scala.collection.mutable.Map[String, T]()
        n => m.getOrElseUpdate(n, read(n))
    }

    private def pipelineReadable(rk: ResolvedKey, p: PipelineConfig): Boolean =
        p != null && CallerVisibility.mayReadStored(Some(rk), "pipeline", p.catalog, p.createdByKeyLabel)

    /** Whether `rk` may read the incident's resource (stored; anything else hidden). */
    private def resourceReadable(rk: ResolvedKey, i: Incident, readPipeline: String => PipelineConfig, readTap: String => TapConfig): Boolean =
        i != null && i.resourceName != null && (i.resourceType match {
            case "pipeline" => pipelineReadable(rk, readPipeline(i.resourceName))
            case "tap" =>
                val t = readTap(i.resourceName)
                t != null && CallerVisibility.mayReadStored(Some(rk), "tap", t.catalog, t.createdByKeyLabel)
            case _ => false
        })

    /** GET /incidents: the incidents the caller sees, each as
      * [[shownIncident]]. `incidents` itself for a caller that is not
      * limited, neither reader called. */
    def visibleIncidents(
        request: HttpServletRequest,
        incidents: List[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig
    ): List[Incident] =
        limitedKey(request) match {
            case None => incidents
            case Some(rk) => visibleIncidentsFor(rk, incidents, readPipeline, readTap)
        }

    /** [[visibleIncidents]] for a limited identity `rk` (the Ops chat block,
      * which has no request on its worker thread). */
    def visibleIncidentsFor(
        rk: ResolvedKey,
        incidents: List[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig
    ): List[Incident] =
        if (incidents == null) incidents
        else {
            val rp = memo(readPipeline)
            val rt = memo(readTap)
            incidents.filter(i => resourceReadable(rk, i, rp, rt)).map(i => shownFor(rk, i, rp))
        }

    /** GET /incidents/{id}, /{id}/evidence, POST /{id}/proceed and
      * /{id}/abandon, before anything is returned or changed: Some(the 404
      * `{"error":"no incident <id>"}` an unknown id answers) when `incident`
      * is on a resource the limited caller may not read (a lookup by id, so
      * no oracle: D2). None otherwise, a missing incident included (the
      * route answers its own 404), and for a caller that is not limited
      * (neither reader called). */
    def incidentRefusal(
        request: HttpServletRequest,
        id: String,
        incident: Option[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig
    ): Option[ResponseEntity[String]] =
        limitedKey(request).flatMap { rk =>
            incident.filterNot(i => resourceReadable(rk, i, readPipeline, readTap)).map(_ =>
                ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            )
        }

    /** A visible incident as a limited caller sees it: the names in its
      * evidence's `blastRadius.downstreamPipelines` that name a pipeline it
      * may not read (or one no longer stored) read `[hidden]`; the list keeps
      * its length. `i` itself when nothing changes and for a caller that is
      * not limited (no reader called). Free text (step summaries and
      * details, trigger `error` and `aiSummary`, `outcome`, the proposal) is
      * left as is. */
    def shownIncident(
        request: HttpServletRequest,
        i: Incident,
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig
    ): Incident =
        limitedKey(request) match {
            case None => i
            case Some(rk) => shownFor(rk, i, readPipeline)
        }

    private def shownFor(rk: ResolvedKey, i: Incident, readPipeline: String => PipelineConfig): Incident =
        if (i == null) i
        else
            i.evidence match {
                case Some(e) if e.has("blastRadius") && e.get("blastRadius").isJsonObject =>
                    val blast = e.getAsJsonObject("blastRadius")
                    if (!blast.has("downstreamPipelines") || !blast.get("downstreamPipelines").isJsonArray) i
                    else {
                        val names = blast.getAsJsonArray("downstreamPipelines")
                        def hidden(el: com.google.gson.JsonElement): Boolean =
                            el != null && el.isJsonPrimitive && !pipelineReadable(rk, readPipeline(el.getAsString))
                        if (!names.asScala.exists(hidden)) i
                        else {
                            val copy = e.deepCopy()
                            val out = new JsonArray()
                            names.forEach(el => if (hidden(el)) out.add(HiddenTapScrub.Placeholder) else out.add(el))
                            copy.getAsJsonObject("blastRadius").add("downstreamPipelines", out)
                            i.copy(evidence = Some(copy))
                        }
                    }
                case _ => i
            }

    private[api] def errorJson(msg: String): String =
        "{\"error\":\"" + Option(msg).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
}
