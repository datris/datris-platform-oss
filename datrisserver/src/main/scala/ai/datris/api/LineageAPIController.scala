package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, CatalogGrants}
import ai.datris.auth.HiddenTapScrub
import ai.datris.model.{DatrisEnvironment, PipelineConfig, TapConfig}
import ai.datris.util.{CatalogOps, LineageService, PipelineConfigIO, TapConfigIO}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Deterministic lineage views built from configuration alone (see
  * [[LineageService]]). Read-only. Node types: source, tap, pipeline,
  * dataset, catalog. A limited caller (a restricted user's session, or an
  * API key limited by a `catalog=` / `owner=` scope) sees only the part of
  * the graph it may read (see [[LineageAPIController.visibleGraph]]); a node
  * outside answers the same 404 as an unknown node, and a named pipeline's
  * columns outside it the pipeline 404 (session) or 403 (key). */
@RestController
@RequestMapping(Array("/api/v1/lineage"))
class LineageAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[LineageAPIController])
    private val gson = new Gson()

    private val nodeTypes = Set("source", "tap", "pipeline", "dataset", "catalog")

    @GetMapping(path = Array(""), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def wholeGraph(request: HttpServletRequest): ResponseEntity[String] = {
        try {
            new ResponseEntity[String](gson.toJson(LineageAPIController.callerGraph(request).toJson), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error building lineage graph: " + Throwables.getStackTraceAsString(e))
                error500(e)
        }
    }

    /** `direction` up|down|both (default both); `depth` hop bound (0 =
      * unbounded); `runs` appends that many recent recorded runs (max 50). */
    @GetMapping(path = Array("/{nodeType}/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def neighborhood(
        @PathVariable nodeType: String,
        @PathVariable name: String,
        @RequestParam(name = "direction", required = false) direction: String,
        @RequestParam(name = "depth", required = false) depth: java.lang.Integer,
        @RequestParam(name = "runs", required = false) runs: java.lang.Integer,
        @RequestParam(name = "columns", required = false) columns: java.lang.Boolean,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val t = Option(nodeType).map(_.trim.toLowerCase).getOrElse("")
            if (!nodeTypes.contains(t))
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body[String]("{\"error\":\"type must be one of source, tap, pipeline, dataset, catalog\"}")
            val d = Option(direction).map(_.trim.toLowerCase).getOrElse("both")
            if (!Set("up", "down", "both").contains(d))
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body[String]("{\"error\":\"direction must be one of up, down, both\"}")
            val result = LineageService.neighborhood(
                t,
                name,
                d,
                if (depth == null) 0 else math.max(0, depth.intValue()),
                if (runs == null) 0 else math.max(0, runs.intValue()),
                columns != null && columns.booleanValue(),
                // A limited caller walks only its visible graph; null = the whole cached graph.
                if (CapabilityCheck.isLimited(request)) LineageAPIController.callerGraph(request)
                else null
            )
            if (result == null)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such node: " + t + ":" + Option(name).getOrElse("").replace("\"", "'") + "\"}")
            else {
                val shown = LineageAPIController.scrubNeighborhood(request, result, n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n))
                new ResponseEntity[String](gson.toJson(shown), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error building lineage neighborhood: " + Throwables.getStackTraceAsString(e))
                error500(e)
        }
    }

    /** Column-level lineage for one pipeline definition (plan L3). `version`
      * selects a definition snapshot (default: current); `infer=true` runs the
      * opt-in AI extraction for pipelines with an AI transformation (cached per
      * version afterwards). Deterministic edges are always returned. Declared
      * before the `{nodeType}/{name}` route by Spring's specificity rules. */
    @GetMapping(path = Array("/columns/{pipeline}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def columns(
        @PathVariable pipeline: String,
        @RequestParam(name = "version", required = false) version: java.lang.Integer,
        @RequestParam(name = "infer", required = false) infer: java.lang.Boolean,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val refused = LineageAPIController.columnsRefusal(
                request,
                pipeline,
                n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
            )
            if (refused.isDefined) return refused.get
            val v = Option(version).map(_.intValue()).filter(_ > 0)
            val inferOn = infer != null && infer.booleanValue()
            val result =
                if (!CapabilityCheck.isLimited(request)) ai.datris.util.ColumnLineageService.forPipeline(pipeline, v, inferOn)
                else
                    ai.datris.util.ColumnLineageService.forPipeline(
                        pipeline,
                        v,
                        inferOn,
                        storedInference = LineageAPIController.recordsShown(
                            request,
                            pipeline,
                            PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
                        )
                    )
            if (result == null)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such pipeline" + (if (version != null) " version" else "") + ": " + Option(pipeline).getOrElse("").replace(
                        "\"",
                        "'"
                    ) + "\"}")
            else
                new ResponseEntity[String](gson.toJson(result.toJson), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error building column lineage: " + Throwables.getStackTraceAsString(e))
                error500(e)
        }
    }

    private def error500(e: Exception): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
}

object LineageAPIController {

    /** The graph a caller may see. A limited caller
      * ([[CapabilityCheck.isLimited]]: a restricted user's session, or an API
      * key limited by a `catalog=` / `owner=` scope) keeps only the pipeline
      * and tap nodes it may read, judged on the stored item's catalog and
      * owner (the node's `catalog` and `owner`, taken from the stored config
      * when the graph was built), and catalog nodes it may see: a session's
      * granted catalogs ([[CatalogGrants.visibleCatalogs]]), for a key the
      * catalogs of the pipeline and tap nodes it keeps. Sources and datasets
      * still linked to them stay ([[LineageService.visibleTo]]). Everyone
      * else: `g` itself. */
    def visibleGraph(request: HttpServletRequest, g: LineageService.Graph): LineageService.Graph =
        CapabilityCheck.resolvedKey(request).filter(ai.datris.auth.CallerVisibility.limited) match {
            case None => g
            case Some(rk) =>
                def itemKept(n: LineageService.Node): Boolean =
                    ai.datris.auth.CallerVisibility.mayReadStored(Some(rk), n.nodeType, n.catalog.orNull, n.owner.orNull)
                val catalogs: Set[String] =
                    if (rk.restrictedSession) CatalogGrants.visibleCatalogs(rk).toSet
                    else g.nodes.filter(n => (n.nodeType == "pipeline" || n.nodeType == "tap") && itemKept(n)).flatMap(_.catalog).filter(_.nonEmpty).toSet
                LineageService.visibleTo(
                    g,
                    n =>
                        n.nodeType match {
                            case "pipeline" | "tap" => itemKept(n)
                            case "catalog" => catalogs.contains(n.name)
                            case _ => true
                        }
                )
        }

    /** The graph GET /lineage returns and GET /lineage/{type}/{name} walks:
      * [[visibleGraph]], and for a limited caller without the runs it may
      * not see ([[LineageService.withoutHiddenRuns]]). */
    def callerGraph(request: HttpServletRequest): LineageService.Graph =
        if (!CapabilityCheck.isLimited(request)) visibleGraph(request, LineageService.graph())
        else LineageService.withoutHiddenRuns(visibleGraph(request, LineageService.graph()), LineageService.pipelines())

    /** Limited callers: whether records filed under `name` are shown
      * ([[ai.datris.util.PipelineAliases.recordShown]] with the pipelines the
      * caller can read, [[PipelineAPIController.mayRead]]). True for everyone
      * else. */
    def recordsShown(request: HttpServletRequest, name: String, all: Seq[PipelineConfig]): Boolean =
        !CapabilityCheck.isLimited(request) ||
            ai.datris.util.PipelineAliases.recordShown(
                name,
                Option(all).getOrElse(Nil).filter(_ != null),
                c => PipelineAPIController.mayRead(request, c)
            )

    /** GET /lineage/{type}/{name} for a limited caller: recorded runs whose
      * input names a tap it cannot read (`readTap` loads it; null = missing)
      * lose that tap's identity ([[HiddenTapScrub.scrubRun]]). Everyone else:
      * `result` unchanged, `readTap` not called. Scrubs in place; returns
      * `result`. */
    def scrubNeighborhood(request: HttpServletRequest, result: JsonObject, readTap: String => TapConfig): JsonObject = {
        if (result == null || !CapabilityCheck.isLimited(request)) return result
        if (result.has("runs") && result.get("runs").isJsonArray) {
            val memo = scala.collection.mutable.Map[String, Boolean]()
            val hidden: String => Boolean = n => memo.getOrElseUpdate(n, HiddenTapScrub.tapHidden(request, n, readTap))
            result.getAsJsonArray("runs").forEach { el =>
                if (el.isJsonObject) HiddenTapScrub.scrubRun(el.getAsJsonObject, hidden)
            }
        }
        result
    }

    /** GET /lineage/columns/{pipeline} for a limited caller (`read` loads the
      * pipeline; null = missing): a restricted session gets the `no such
      * pipeline` 404 when it is missing or outside the grants; an API key
      * gets the capability-denied 403 on `pipeline:read` (shared wording)
      * when the stored pipeline is outside its scope, and a missing one the
      * route's own 404. None for everyone else, `read` not called. */
    def columnsRefusal(request: HttpServletRequest, pipeline: String, read: String => PipelineConfig): Option[ResponseEntity[String]] =
        if (!CapabilityCheck.isLimited(request)) None
        else if (CapabilityCheck.isRestrictedSession(request)) {
            if (PipelineAPIController.hiddenConfig(request, read(pipeline), "read"))
                Some(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body[String]("{\"error\":\"no such pipeline: " + Option(pipeline).getOrElse("").replace("\"", "'") + "\"}")
                )
            else None
        } else
            Option(read(pipeline)).flatMap(c =>
                PipelineAPIController.keyRefusal(request, "pipeline", "read", CatalogOps.scopeContext(c.catalog, c.createdByKeyLabel))
            )
}
