package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityCheck
import ai.datris.model.{DatrisEnvironment, PipelineConfig}
import ai.datris.util.{PipelineConfigIO, ProvenanceResolver}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Resolves a stamped `_datris_run_id` back to the run, tap run, script commit,
  * config version and source that produced it (see [[ProvenanceResolver]]).
  * Read-only; wrapped by the `get_provenance` MCP tool. */
@RestController
@RequestMapping(Array("/api/v1"))
class ProvenanceAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ProvenanceAPIController])
    private val gson = new Gson()

    @GetMapping(path = Array("/provenance"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def provenance(
        @RequestParam(name = "runId") runId: String,
        @RequestParam(name = "pipeline", required = false) pipeline: String,
        @RequestParam(name = "tapRun", required = false) tapRun: String,
        @RequestParam(name = "configVersion", required = false) configVersion: java.lang.Integer,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            if (runId == null || runId.trim.isEmpty)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"runId is required\"}")
            // A restricted session's hints are ignored: the run's own records
            // name its pipeline and tap run, so a visible pipeline name cannot
            // vouch for a hidden run.
            val restricted = CapabilityCheck.isRestrictedSession(request)
            val resolved = ProvenanceResolver.resolve(
                if (pipeline != null && !restricted) pipeline.trim else null,
                runId.trim,
                if (tapRun != null && !restricted) tapRun.trim else null,
                configVersion
            )
            val refused = ProvenanceAPIController.provenanceRefusal(
                request,
                runId.trim,
                resolved,
                n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
            )
            if (refused.isDefined) return refused.get
            new ResponseEntity[String](gson.toJson(resolved), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error resolving provenance: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
        }
    }
}

object ProvenanceAPIController {

    /** For a restricted user's session, after [[ProvenanceResolver.resolve]]:
      * 404 `no such run: <runId>` when the resolved run names no pipeline, or
      * one that is missing or outside the grants (`read` loads it; null =
      * missing). The body never names the pipeline. None for everyone else. */
    def provenanceRefusal(
        request: HttpServletRequest,
        runId: String,
        resolved: JsonObject,
        read: String => PipelineConfig
    ): Option[ResponseEntity[String]] = {
        if (!CapabilityCheck.isRestrictedSession(request)) return None
        val pipeline =
            if (resolved != null && resolved.has("pipeline") && resolved.get("pipeline").isJsonPrimitive) resolved.get("pipeline").getAsString
            else null
        val hidden = pipeline == null || pipeline.isEmpty || PipelineAPIController.hiddenConfig(request, read(pipeline), "read")
        if (!hidden) None
        else
            Some(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such run: " + Option(runId).getOrElse("").replace("\"", "'").replace("\\", "") + "\"}")
            )
    }
}
