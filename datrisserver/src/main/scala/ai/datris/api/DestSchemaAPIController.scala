package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.auth.VersionActor
import ai.datris.model.{DatrisException, SchemaField}
import ai.datris.util.{APIKeyValidator, DestSchemaApply}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Destination-side typing, pull-based (plans/destination-schema-after-load.md):
  * GET infers a typed proposal on demand from landed rows; POST applies the
  * (possibly edited) types — migrating the landed table first, then writing
  * the typed config as a new version. Nothing is ever stored between the two. */
@RestController
@RequestMapping(Array("/api/v1"))
class DestSchemaAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[DestSchemaAPIController])

    /** Propose destination column types from landed data. Stateless: samples
      * up to 1000 landed rows, infers, and returns the fields with per-column
      * evidence (sample values; for stayed-string columns, the first value
      * that blocked a type). `eligible: false` carries a `reason`:
      * destination-not-supported, already-typed, or no-landed-rows. */
    @GetMapping(path = Array("/pipeline/dest-types"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def propose(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam pipeline: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline/dest-types called for pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)
            if (pipeline == null || pipeline.isEmpty)
                throw new DatrisException("Pipeline name is required")

            val proposal = DestSchemaApply.propose(pipeline)
            new ResponseEntity[String](new Gson().toJson(proposal), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Dest-types proposal refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    /** Apply destination column types. Body:
      * {"pipeline": "...", "fields": [{"name": "...", "type": "..."}, ...]}
      * (`fields` must name every destination column — set unwanted ones to
      * "string"). Destination-first: landed data is migrated before the config
      * changes; any un-castable value fails the whole apply with the column
      * named and nothing applied. The migration locks or replaces the table —
      * don't apply while a run is in flight. */
    @PostMapping(path = Array("/pipeline/dest-types"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def apply(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: ApplyDestTypesRequest,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline/dest-types called for pipeline: " + body.pipeline)
            APIKeyValidator.validate(apiKey)
            if (body.pipeline == null || body.pipeline.isEmpty)
                throw new DatrisException("Pipeline name is required")

            val applied = DestSchemaApply.apply(body.pipeline, body.fields, VersionActor.resolve(request))
            new ResponseEntity[String](new Gson().toJson(applied), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Dest-types apply refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}

/** Request body for apply — bound the same way PipelineConfig is on
  * POST /pipeline. */
case class ApplyDestTypesRequest(
    pipeline: String = null,
    fields: java.util.List[SchemaField] = null
)
