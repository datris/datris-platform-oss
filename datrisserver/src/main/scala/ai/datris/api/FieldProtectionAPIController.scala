package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.{DatrisEnvironment, DatrisException, SchemaField}
import ai.datris.util.{APIKeyValidator, FieldProtectionAdvisor, PipelineConfigIO}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** Field protection suggestions (plans/stories/field-protection-3-classifier.md).
  * Stateless and read-only: asks the CodeGen model which fields should carry
  * `protect`, from field names and types only. Nothing is saved. */
@RestController
@RequestMapping(Array("/api/v1"))
class FieldProtectionAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FieldProtectionAPIController])

    /** Body: `{"pipeline": "<name>"}` (uses the stored source schema) or
      * `{"fields": [{"name": "...", "type": "...", "protect"?: {...}}]}`. */
    @PostMapping(
        path = Array("/pipeline/protect/suggest"),
        consumes = Array(MediaType.APPLICATION_JSON_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def suggest(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: SuggestProtectionRequest,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        val pipeline = Option(body).flatMap(b => Option(b.pipeline)).map(_.trim).filter(_.nonEmpty).orNull
        // Set once the field list is resolved, i.e. once the call can reach
        // the model: from then on every outcome is audited (names only).
        var md: JsonObject = null

        def audit(outcome: String, status: Int, error: String): Unit =
            if (md != null)
                try AuditLog.record(request, "pipeline", "protect-suggest", "pipeline", pipeline, outcome, status, md, error)
                catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }

        try {
            logger.info("API endpoint POST /pipeline/protect/suggest called" + Option(pipeline).map(" for pipeline: " + _).getOrElse(""))
            APIKeyValidator.validate(apiKey)

            val (fields, keyFields, destTypes): (List[SchemaField], Set[String], Map[String, String]) =
                if (pipeline != null) {
                    val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
                    if (config == null)
                        throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
                    val sp = if (config.source != null) config.source.schemaProperties else null
                    if (sp == null || sp.fields == null || sp.fields.isEmpty)
                        throw new DatrisException("Pipeline '" + pipeline + "' has no source schema fields")
                    val (keys, dest) = FieldProtectionAdvisor.constraintsOf(config)
                    (sp.fields.asScala.toList.filter(_ != null), keys, dest)
                } else if (body != null && body.fields != null && !body.fields.isEmpty)
                    (body.fields.asScala.toList.filter(_ != null), Set.empty[String], Map.empty[String, String])
                else
                    throw new DatrisException("Pass either 'pipeline' (a pipeline name) or 'fields' (an array of {name, type})")

            md = new JsonObject()
            val names = new JsonArray()
            fields.flatMap(f => Option(f.name)).foreach(names.add)
            md.add("fields", names)
            val model =
                try Option(DatrisEnvironment.aiConfigForCodegen).map(_.model).orNull
                catch { case _: Exception => null }
            if (model != null) md.addProperty("model", model)

            val suggestion = FieldProtectionAdvisor.suggest(fields, keyFields, destTypes)
            audit("success", 200, null)
            new ResponseEntity[String](FieldProtectionAdvisor.toJson(suggestion), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Field protection suggest refused: " + e.getMessage)
                audit("failure", 400, e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                audit("failure", 500, e.getMessage)
                ApiErrors.internal(e)
        }
    }
}

/** Request body — bound the same way ApplyDestTypesRequest is. */
case class SuggestProtectionRequest(
    pipeline: String = null,
    fields: java.util.List[SchemaField] = null
)
