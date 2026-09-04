package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.LineageService
import com.google.common.base.Throwables
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Deterministic lineage views built from configuration alone (see
  * [[LineageService]]). Read-only. Node types: source, tap, pipeline,
  * dataset, catalog. */
@RestController
@RequestMapping(Array("/api/v1/lineage"))
class LineageAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[LineageAPIController])
    private val gson = new Gson()

    private val nodeTypes = Set("source", "tap", "pipeline", "dataset", "catalog")

    @GetMapping(path = Array(""), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def wholeGraph(): ResponseEntity[String] = {
        try {
            new ResponseEntity[String](gson.toJson(LineageService.graph().toJson), HttpStatus.OK)
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
        @RequestParam(name = "columns", required = false) columns: java.lang.Boolean
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
                columns != null && columns.booleanValue()
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
        @RequestParam(name = "infer", required = false) infer: java.lang.Boolean
    ): ResponseEntity[String] = {
        try {
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
