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
        @RequestParam(name = "runs", required = false) runs: java.lang.Integer
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
                t, name, d,
                if (depth == null) 0 else math.max(0, depth.intValue()),
                if (runs == null) 0 else math.max(0, runs.intValue())
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

    private def error500(e: Exception): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
}
