package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, CatalogGrants}
import ai.datris.model.{DatrisEnvironment, PipelineConfig}
import ai.datris.util.{CatalogOps, LineageService, PipelineConfigIO}
import com.google.common.base.Throwables
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Deterministic lineage views built from configuration alone (see
  * [[LineageService]]). Read-only. Node types: source, tap, pipeline,
  * dataset, catalog. A restricted user's session sees only the part of the
  * graph inside its granted catalogs (see [[LineageAPIController.visibleGraph]]);
  * anything outside answers the same 404 as an unknown node or pipeline. */
@RestController
@RequestMapping(Array("/api/v1/lineage"))
class LineageAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[LineageAPIController])
    private val gson = new Gson()

    private val nodeTypes = Set("source", "tap", "pipeline", "dataset", "catalog")

    @GetMapping(path = Array(""), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def wholeGraph(request: HttpServletRequest): ResponseEntity[String] = {
        try {
            new ResponseEntity[String](gson.toJson(LineageAPIController.visibleGraph(request, LineageService.graph()).toJson), HttpStatus.OK)
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
                // A restricted session walks only its visible graph; null = the whole cached graph.
                if (CapabilityCheck.isRestrictedSession(request)) LineageAPIController.visibleGraph(request, LineageService.graph())
                else null
            )
            if (result == null)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such node: " + t + ":" + Option(name).getOrElse("").replace("\"", "'") + "\"}")
            else
                new ResponseEntity[String](gson.toJson(result), HttpStatus.OK)
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
            val result = ai.datris.util.ColumnLineageService.forPipeline(
                pipeline,
                Option(version).map(_.intValue()).filter(_ > 0),
                infer != null && infer.booleanValue()
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

    /** The graph a caller may see: for a restricted user's session, only
      * pipeline and tap nodes its grants allow it to read and catalog nodes
      * among its visible catalogs, plus the sources and datasets still linked
      * to them ([[LineageService.visibleTo]]). Everyone else: `g` unchanged. */
    def visibleGraph(request: HttpServletRequest, g: LineageService.Graph): LineageService.Graph =
        CapabilityCheck.resolvedKey(request).filter(_.restrictedSession) match {
            case None => g
            case Some(rk) =>
                val catalogs = CatalogGrants.visibleCatalogs(rk).toSet
                LineageService.visibleTo(
                    g,
                    n =>
                        n.nodeType match {
                            case "pipeline" | "tap" =>
                                CapabilityCheck.grants(request, n.nodeType, "read", CatalogOps.scopeContext(n.catalog.orNull, null))
                            case "catalog" => catalogs.contains(n.name)
                            case _ => true
                        }
                )
        }

    /** GET /lineage/columns/{pipeline} for a restricted session: the
      * `no such pipeline` 404 when the pipeline is missing or outside the
      * grants (`read` loads it; null = missing). None for everyone else. */
    def columnsRefusal(request: HttpServletRequest, pipeline: String, read: String => PipelineConfig): Option[ResponseEntity[String]] =
        if (!CapabilityCheck.isRestrictedSession(request)) None
        else if (PipelineAPIController.hiddenConfig(request, read(pipeline), "read"))
            Some(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such pipeline: " + Option(pipeline).getOrElse("").replace("\"", "'") + "\"}")
            )
        else None
}
