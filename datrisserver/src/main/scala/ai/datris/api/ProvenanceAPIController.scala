package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.ProvenanceResolver
import com.google.common.base.Throwables
import com.google.gson.Gson
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
        @RequestParam(name = "configVersion", required = false) configVersion: java.lang.Integer
    ): ResponseEntity[String] = {
        try {
            if (runId == null || runId.trim.isEmpty)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"runId is required\"}")
            val resolved = ProvenanceResolver.resolve(
                if (pipeline != null) pipeline.trim else null,
                runId.trim,
                if (tapRun != null) tapRun.trim else null,
                configVersion
            )
            new ResponseEntity[String](gson.toJson(resolved), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error resolving provenance: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
        }
    }
}
