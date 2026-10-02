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
        try {
            val pipeline = Option(body).flatMap(b => Option(b.pipeline)).map(_.trim).filter(_.nonEmpty).orNull
            logger.info("API endpoint POST /pipeline/protect/suggest called" + Option(pipeline).map(" for pipeline: " + _).getOrElse(""))
            APIKeyValidator.validate(apiKey)

            val fields: List[SchemaField] =
                if (pipeline != null) {
                    val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
                    if (config == null)
                        throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
                    val sp = if (config.source != null) config.source.schemaProperties else null
                    if (sp == null || sp.fields == null || sp.fields.isEmpty)
                        throw new DatrisException("Pipeline '" + pipeline + "' has no source schema fields")
                    sp.fields.asScala.toList.filter(_ != null)
                } else if (body != null && body.fields != null && !body.fields.isEmpty)
                    body.fields.asScala.toList.filter(_ != null)
                else
                    throw new DatrisException("Pass either 'pipeline' (a pipeline name) or 'fields' (an array of {name, type})")

            val suggestion = FieldProtectionAdvisor.suggest(fields)

            // Names only: an operator can see the model was consulted, never a value.
            val md = new JsonObject()
            val names = new JsonArray()
            fields.flatMap(f => Option(f.name)).foreach(names.add)
            md.add("fields", names)
            if (suggestion.model != null) md.addProperty("model", suggestion.model)
            try AuditLog.record(request, "pipeline", "protect-suggest", "pipeline", pipeline, metadata = md)
            catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }

            new ResponseEntity[String](FieldProtectionAdvisor.toJson(suggestion), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                logger.warn("Field protection suggest refused: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}

/** Request body — bound the same way ApplyDestTypesRequest is. */
case class SuggestProtectionRequest(
    pipeline: String = null,
    fields: java.util.List[SchemaField] = null
)
