package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.config.RequiresRole
import ai.datris.auth.{CallerVisibility, CapabilityCheck, HiddenTapScrub}
import ai.datris.incident.{Incident, IncidentIO, IncidentRunner}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, RunLineage, TapConfig}
import ai.datris.util.{PipelineConfigIO, RunLineageIO, TapConfigIO}
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
                arr.addAll(IncidentsAPIController.listedJson(
                    request,
                    IncidentIO.list(st, Option(limit).map(_.intValue()).getOrElse(50)),
                    readPipeline,
                    readTap,
                    IncidentsAPIController.storeRuns()
                ))
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
            IncidentsAPIController.getResponse(request, id, IncidentIO.get(id), readPipeline, readTap, IncidentsAPIController.storeRuns())
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
            IncidentsAPIController.evidenceResponse(request, id, IncidentIO.get(id), readPipeline, readTap, IncidentsAPIController.storeRuns())
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
            val updated =
                IncidentsAPIController.shownIncident(request, IncidentIO.get(id).getOrElse(incident), readPipeline, readTap, IncidentsAPIController.storeRuns())
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
                    val updated = IncidentsAPIController.shownIncident(
                        request,
                        IncidentIO.get(id).getOrElse(incident),
                        readPipeline,
                        readTap,
                        IncidentsAPIController.storeRuns()
                    )
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

    /** No run record for any id: profiles whose run cannot be established
      * are judged hidden (fail closed). The default where no run reader is
      * given. */
    val NoRuns: String => Option[RunLineage] = _ => None

    /** The live run-lineage reader for [[shownIncident]], memoised for one
      * request; called only for a limited caller's visible incident whose
      * evidence holds a profile of a recorded run. A failed read is None. */
    def storeRuns(): String => Option[RunLineage] = memo(id =>
        try RunLineageIO.read(id)
        catch { case _: Exception => None }
    )

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
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): List[Incident] =
        limitedKey(request) match {
            case None => incidents
            case Some(rk) => visibleIncidentsFor(rk, incidents, readPipeline, readTap, run)
        }

    /** [[visibleIncidents]] for a limited identity `rk` (the Ops chat block,
      * which has no request on its worker thread). */
    def visibleIncidentsFor(
        rk: ResolvedKey,
        incidents: List[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): List[Incident] =
        if (incidents == null) incidents
        else {
            val rp = memo(readPipeline)
            val rt = memo(readTap)
            incidents.filter(i => resourceReadable(rk, i, rp, rt)).map(i => shownFor(rk, i, rp, rt, run))
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

    /** A visible incident as a limited caller sees it ([[shownFor]]). `i`
      * itself when nothing changes and for a caller that is not limited (no
      * reader called). Free text (step summaries and details, trigger
      * `error` and `aiSummary`, `outcome`, the proposal) is left as is. */
    def shownIncident(
        request: HttpServletRequest,
        i: Incident,
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): Incident =
        limitedKey(request) match {
            case None => i
            case Some(rk) => shownFor(rk, i, readPipeline, readTap, run)
        }

    /** The structured evidence of a visible incident as a limited identity
      * `rk` sees it (lead ruling on finding 7). In `blastRadius`:
      * `downstreamPipelines` entries it may not read read `[hidden]`;
      * a `downstreamDatasets` entry written by downstream pipelines none of
      * which it may read reads `[hidden]` (kind and coords), and when an
      * entry cannot be tied to a downstream pipeline and any downstream
      * pipeline is hidden the whole list becomes one `[hidden]` entry;
      * `ucTables` and `consumersFromContracts` cannot be tied to a pipeline,
      * so a non-empty list becomes one `[hidden]` entry when any downstream
      * pipeline is hidden; `agentsThatQueriedLast7d` (other callers' labels)
      * and `secretName` are removed, and `lastDownstreamRunAt` when any
      * downstream pipeline is hidden. Row profiles are judged on the run each
      * describes (see the body); `verdict` stays. A copy; the stored incident is never altered. `i` itself
      * when nothing changes. Free text stays as it is. */
    private def shownFor(
        rk: ResolvedKey,
        i: Incident,
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage]
    ): Incident =
        if (i == null || i.evidence.isEmpty || i.evidence.get == null) i
        else {
            val e = i.evidence.get
            val copy = e.deepCopy()
            var changed = false
            val ph = HiddenTapScrub.Placeholder
            def arr(o: JsonObject, k: String): Option[JsonArray] = if (o.has(k) && o.get(k).isJsonArray) Some(o.getAsJsonArray(k)) else None
            if (copy.has("blastRadius") && copy.get("blastRadius").isJsonObject) {
                val blast = copy.getAsJsonObject("blastRadius")
                val downstream = arr(blast, "downstreamPipelines").map(_.asScala.toList.filter(_.isJsonPrimitive).map(_.getAsString)).getOrElse(Nil)
                val hiddenNames = downstream.filterNot(n => pipelineReadable(rk, readPipeline(n))).toSet
                val anyHidden = hiddenNames.nonEmpty
                arr(blast, "downstreamPipelines").foreach { names =>
                    if (anyHidden) {
                        val out = new JsonArray()
                        names.forEach(el => if (el.isJsonPrimitive && hiddenNames.contains(el.getAsString)) out.add(ph) else out.add(el))
                        blast.add("downstreamPipelines", out)
                        changed = true
                    }
                }
                arr(blast, "downstreamDatasets").filter(_.size > 0).foreach { ds =>
                    if (anyHidden) {
                        // writers of each dataset among the downstream pipelines still stored
                        val stored = downstream.flatMap(n => Option(readPipeline(n)))
                        def writers(kind: String, coords: String): List[PipelineConfig] =
                            stored.filter(p => ai.datris.util.LineageService.datasets(p).exists(d => d.name == kind + ":" + coords))
                        val entries = ds.asScala.toList.map { el =>
                            if (!el.isJsonObject) (el, Nil)
                            else {
                                val o = el.getAsJsonObject
                                def f(k: String) = if (o.has(k) && o.get(k).isJsonPrimitive) o.get(k).getAsString else ""
                                (el, writers(f("kind"), f("coords")))
                            }
                        }
                        def hiddenEntry: JsonObject = { val h = new JsonObject(); h.addProperty("kind", ph); h.addProperty("coords", ph); h }
                        val out = new JsonArray()
                        if (entries.exists(_._2.isEmpty)) out.add(hiddenEntry)
                        else entries.foreach { case (el, w) => if (w.exists(p => pipelineReadable(rk, p))) out.add(el) else out.add(hiddenEntry) }
                        blast.add("downstreamDatasets", out)
                        changed = true
                    }
                }
                Seq("ucTables", "consumersFromContracts").foreach { k =>
                    arr(blast, k).filter(_.size > 0).foreach { _ =>
                        if (anyHidden) {
                            val out = new JsonArray(); out.add(ph); blast.add(k, out); changed = true
                        }
                    }
                }
                Seq("agentsThatQueriedLast7d", "secretName").foreach { k =>
                    if (blast.has(k)) { blast.remove(k); changed = true }
                }
                // when a downstream pipeline is hidden, its last run time may be what this is
                if (anyHidden && blast.has("lastDownstreamRunAt")) { blast.remove("lastDownstreamRunAt"); changed = true }
            }
            // Row profiles. Each is judged on the run it describes (its `runId`):
            // a tap's own last run (`tap:<name>@<time>`) describes that tap; any
            // other run id is resolved through `run` to its recorded pipeline and
            // feeding tap. On a tap incident a profile whose pipeline the caller
            // may not read, or whose run cannot be established (no run id, no
            // record), is removed, and with it the diff and checks computed from
            // the profiles (column names, record delta, removed and retyped column
            // counts, null-rate increase, DQ pass rate of the landed rows); the
            // tap may have been retargeted since, so its current target is not
            // what decides. On any incident a kept profile loses the tap identity
            // fields ([[HiddenTapScrub.RunProfileTapFields]]) unless its feeding
            // tap is established and readable.
            val tapOwn = "tap:"
            def runIdOf(o: JsonObject): Option[String] =
                if (o.has("runId") && o.get("runId").isJsonPrimitive) Option(o.get("runId").getAsString).filter(_.nonEmpty) else None
            def tapReadable(name: String): Boolean = {
                val t = readTap(name)
                t != null && CallerVisibility.mayReadStored(Some(rk), "tap", t.catalog, t.createdByKeyLabel)
            }
            var profileRemoved = false
            Seq("baseline", "candidate", "real").foreach { k =>
                if (copy.has(k) && copy.get(k).isJsonObject) {
                    val prof = copy.getAsJsonObject(k)
                    val id = runIdOf(prof)
                    val ownTapRun = id.exists(_.startsWith(tapOwn))
                    val record = if (ownTapRun) None else id.flatMap(x => Option(run(x)).flatten)
                    val describesReadable =
                        ownTapRun || record.exists(rl => rl.pipeline != null && pipelineReadable(rk, readPipeline(rl.pipeline)))
                    if (i.resourceType == "tap" && !describesReadable) {
                        copy.remove(k); changed = true; profileRemoved = true
                    } else {
                        val feedingTapReadable =
                            if (ownTapRun) tapReadable(id.get.stripPrefix(tapOwn).takeWhile(_ != '@'))
                            else
                                record.exists { rl =>
                                    val in = rl.input
                                    in == null || in.kind == "upload" || in.tapName == null || in.tapName.isEmpty || tapReadable(in.tapName)
                                }
                        if (!feedingTapReadable)
                            HiddenTapScrub.RunProfileTapFields.foreach { f =>
                                if (prof.has(f)) { prof.remove(f); changed = true }
                            }
                    }
                }
            }
            if (profileRemoved)
                Seq("diff", "checks").foreach { k =>
                    if (copy.has(k)) { copy.remove(k); changed = true }
                }
            if (changed) i.copy(evidence = Some(copy)) else i
        }

    /** GET /incidents: the `incidents` array the caller sees ([[visibleIncidents]]). */
    def listedJson(
        request: HttpServletRequest,
        incidents: List[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): JsonArray = {
        val arr = new JsonArray()
        visibleIncidents(request, incidents, readPipeline, readTap, run).foreach(i => arr.add(i.toPublicJson))
        arr
    }

    /** GET /incidents/{id} on the stored incident (None = unknown): the
      * hidden-incident 404 ([[incidentRefusal]]), else 200 with the incident
      * as the caller sees it ([[shownIncident]]), or the unknown-incident 404. */
    def getResponse(
        request: HttpServletRequest,
        id: String,
        stored: Option[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): ResponseEntity[String] =
        incidentRefusal(request, id, stored, readPipeline, readTap).getOrElse {
            stored.map(i => shownIncident(request, i, readPipeline, readTap, run)) match {
                case Some(i) => new ResponseEntity[String](new Gson().toJson(i.toPublicJson), HttpStatus.OK)
                case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            }
        }

    /** GET /incidents/{id}/evidence on the stored incident: as [[getResponse]],
      * returning the evidence as the caller sees it, or 404 when absent. */
    def evidenceResponse(
        request: HttpServletRequest,
        id: String,
        stored: Option[Incident],
        readPipeline: String => PipelineConfig,
        readTap: String => TapConfig,
        run: String => Option[RunLineage] = NoRuns
    ): ResponseEntity[String] =
        incidentRefusal(request, id, stored, readPipeline, readTap).getOrElse {
            stored.map(i => shownIncident(request, i, readPipeline, readTap, run)) match {
                case Some(i) =>
                    i.evidence match {
                        case Some(e) => new ResponseEntity[String](new Gson().toJson(e), HttpStatus.OK)
                        case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("incident " + id + " has no evidence"))
                    }
                case None => ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("no incident " + id))
            }
        }

    private[api] def errorJson(msg: String): String =
        "{\"error\":\"" + Option(msg).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
}
