package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.auth.{CapabilityCheck, VersionActor}
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, SchemaField}
import ai.datris.util.{APIKeyValidator, CatalogOps, DestSchemaApply, PipelineConfigIO}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

object DestSchemaAPIController {

    /** The answer the dest-types routes give for a missing pipeline (400). */
    private def notFound(pipeline: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body[String]("{\"error\": " + new Gson().toJson("Pipeline not found: " + pipeline) + "}")

    /** GET (`read`) and POST (`update`) /pipeline/dest-types on the stored
      * config (null = missing), before anything is sampled or applied. A
      * restricted session outside its grants (or the config missing) gets
      * today's 400 "Pipeline not found: <name>"; an API key whose scope
      * refuses `action` on it gets the capability-denied 403
      * (plans/stories/scoped-key-read-routes.md). None for everyone else. */
    def scopeRefusal(request: HttpServletRequest, pipeline: String, config: PipelineConfig, action: String): Option[ResponseEntity[String]] =
        if (CapabilityCheck.isRestrictedSession(request)) {
            if (PipelineAPIController.hiddenConfig(request, config, action)) Some(notFound(pipeline)) else None
        } else if (config == null) None
        else PipelineAPIController.keyRefusal(request, "pipeline", action, CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))

    /** [[scopeRefusal]] with the stored config read only for a caller it can
      * refuse. */
    def refusalFor(request: HttpServletRequest, pipeline: String, action: String): Option[ResponseEntity[String]] =
        if (!CapabilityCheck.isRestrictedSession(request) && !CapabilityCheck.keyScopeApplies(request, "pipeline", action)) None
        else scopeRefusal(request, pipeline, PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline), action)
}

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
        @RequestParam pipeline: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline/dest-types called for pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)
            if (pipeline == null || pipeline.isEmpty)
                throw new DatrisException("Pipeline name is required")
            // Restricted users: a pipeline outside the grants reads exactly as
            // a missing one; a scoped key outside its scope: 403. Before any
            // landed row is sampled.
            DestSchemaAPIController.refusalFor(request, pipeline, "read").foreach(return _)

            val proposal = DestSchemaApply.propose(pipeline)
            new ResponseEntity[String](new Gson().toJson(proposal), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Dest-types proposal refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Propose destination column types from preview rows (e.g. a tap's
      * `test_tap` records) before any pipeline exists. Body:
      * {"columns": ["..."]?, "records": [{...}, ...]}. Stateless — nothing is
      * read or stored. Conservative: a column needs
      * [[DestSchemaApply.MinVotes]] typed values to be typed; fewer stays
      * `string` with a `note`. 400 when `records` is empty. */
    @PostMapping(
        path = Array("/pipeline/propose-schema"),
        consumes = Array(MediaType.APPLICATION_JSON_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def proposeFromRecords(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: ProposeSchemaRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline/propose-schema called")
            APIKeyValidator.validate(apiKey)
            val javaRecords: Seq[java.util.Map[String, Object]] =
                if (body == null || body.records == null) Seq.empty
                else body.records.asScala.toSeq.filter(_ != null)
            // Column order: explicit list, else the union of record keys in
            // first-seen order (read from the ordered JSON maps, before
            // conversion to Scala maps loses key order).
            val columns: List[String] =
                if (body != null && body.columns != null && !body.columns.isEmpty) body.columns.asScala.toList
                else {
                    val seen = new java.util.LinkedHashSet[String]()
                    javaRecords.foreach(r => r.keySet().asScala.foreach(seen.add))
                    seen.asScala.toList
                }
            val records: Seq[Map[String, Any]] = javaRecords.map(_.asScala.toMap[String, Any])
            val proposal = DestSchemaApply.proposeFromRecords(columns, records)
            new ResponseEntity[String](new Gson().toJson(proposal), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Propose-schema refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
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
            // Restricted users: a pipeline outside the grants (or not editable)
            // reads as missing; a scoped key outside its scope: 403. Before
            // anything is migrated or written.
            DestSchemaAPIController.refusalFor(request, body.pipeline, "update").foreach(return _)

            val applied = DestSchemaApply.apply(body.pipeline, body.fields, VersionActor.resolve(request))
            new ResponseEntity[String](new Gson().toJson(applied), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Dest-types apply refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}

/** Request body for apply — bound the same way PipelineConfig is on
  * POST /pipeline. */
case class ApplyDestTypesRequest(
    pipeline: String = null,
    fields: java.util.List[SchemaField] = null
)

/** Request body for propose-schema: preview rows plus an optional column
  * order (defaults to the union of record keys in first-seen order). */
case class ProposeSchemaRequest(
    columns: java.util.List[String] = null,
    records: java.util.List[java.util.Map[String, Object]] = null
)
