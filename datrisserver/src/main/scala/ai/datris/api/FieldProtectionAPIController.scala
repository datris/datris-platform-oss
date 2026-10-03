package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException}
import ai.datris.model.{DatrisEnvironment, DatrisException, SchemaField}
import ai.datris.util.{APIKeyValidator, CatalogOps, FieldCipher, FieldProtectionAdvisor, FieldProtectionKey, PipelineConfigIO}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonNull, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** Field protection suggestions (plans/stories/field-protection-3-classifier.md).
  * Stateless and read-only: asks the CodeGen model which fields should carry
  * `protect`, from field names and types only. Nothing is saved.
  *
  * Reveal and encryption-key rotation (plans/stories/field-protection-5-encrypt-reveal.md):
  * REST only, gated by `protect:reveal` / `protect:admin` in CapabilityRoutes,
  * never exposed as an MCP tool. Reveal is stateless (the caller supplies the
  * ciphertext it read from the destination) and always audited; neither a
  * value nor a ciphertext is ever logged or written to audit metadata. */
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

    /** Body: `{"pipeline": "<name>", "field": "<name>", "values": ["enc:v1:...", ...]}`.
      * Response: `{"pipeline", "field", "values": [plaintext | null], "revealed", "failed", "errors": [{index, message}]}`. */
    @PostMapping(
        path = Array("/protect/reveal"),
        consumes = Array(MediaType.APPLICATION_JSON_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def reveal(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: RevealRequest,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        val pipeline = Option(body).flatMap(b => Option(b.pipeline)).map(_.trim).filter(_.nonEmpty).orNull
        val fieldName = Option(body).flatMap(b => Option(b.field)).map(_.trim).filter(_.nonEmpty).orNull
        val values0: java.util.List[String] = Option(body).map(_.values).orNull
        val requested = Option(values0).map(_.size).getOrElse(0)

        // Names and counts only: never a value or a ciphertext.
        def audit(outcome: String, status: Int, revealed: Int, failed: Int, error: String): Unit =
            try {
                val md = new JsonObject()
                if (fieldName != null) md.addProperty("field", fieldName)
                md.addProperty("requested", requested)
                md.addProperty("revealed", revealed)
                md.addProperty("failed", failed)
                AuditLog.record(request, "protect", "reveal", "pipeline", pipeline, outcome, status, md, error)
            } catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }

        try {
            logger.info("API endpoint POST /protect/reveal called" + Option(pipeline).map(" for pipeline: " + _).getOrElse(""))
            FieldProtectionAPIController.requireCapability(request, "reveal")
            APIKeyValidator.validate(apiKey)
            if (pipeline == null) throw new DatrisException("'pipeline' is required")
            if (fieldName == null) throw new DatrisException("'field' is required")
            if (values0 == null) throw new DatrisException("'values' is required (an array of enc:v<n>: ciphertexts)")
            if (requested > FieldProtectionAPIController.MaxRevealValues)
                throw new DatrisException(
                    "At most " + FieldProtectionAPIController.MaxRevealValues + " values per reveal call (got " + requested + ")"
                )

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
            val sp = if (config.source != null) config.source.schemaProperties else null
            val sourceField: SchemaField =
                Option(sp).flatMap(s => Option(s.fields)).map(_.asScala.toList).getOrElse(Nil)
                    .find(f => f != null && f.name != null && f.name.trim.equalsIgnoreCase(fieldName))
                    .orNull
            val isEncrypt = sourceField != null && sourceField.protect != null && sourceField.protect.method != null &&
                sourceField.protect.method.trim.equalsIgnoreCase("encrypt")
            if (!isEncrypt)
                throw new DatrisException("Field '" + fieldName + "' of pipeline '" + pipeline + "' is not protected with method 'encrypt'")

            // The ciphertext is bound to the names the run used: the stored
            // pipeline name and the source field name.
            val boundPipeline = Option(config.name).getOrElse(pipeline)
            val boundField = sourceField.name

            // One secret read per key version per call, not per value. A
            // read failure is a server error, not a bad ciphertext.
            val keys = scala.collection.mutable.Map[Int, Array[Byte]]()
            var keyStoreError: DatrisException = null
            val lookup: Int => Array[Byte] = v =>
                keys.getOrElseUpdate(
                    v,
                    try FieldProtectionKey.encryptionKey(v)
                    catch {
                        case e: DatrisException =>
                            if (e.getMessage != null && e.getMessage.contains("could not read")) keyStoreError = e
                            null
                    }
                )

            val (values, errors, revealed, failed) =
                FieldProtectionAPIController.decryptAll(lookup, boundPipeline, boundField, values0.asScala.toSeq)
            if (keyStoreError != null) throw keyStoreError

            audit(if (failed > 0) "warning" else "success", 200, revealed, failed, null)
            val response = new JsonObject()
            response.addProperty("pipeline", pipeline)
            response.addProperty("field", fieldName)
            response.add("values", values)
            response.addProperty("revealed", revealed)
            response.addProperty("failed", failed)
            response.add("errors", errors)
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: CapabilityDeniedException =>
                FieldProtectionAPIController.denied(request, e, "reveal")
            case e: DatrisException if e.getMessage != null && e.getMessage.startsWith("Field protection: could not read") =>
                logger.error("Field protection reveal: " + e.getMessage)
                audit("failure", 500, 0, 0, e.getMessage)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: DatrisException =>
                logger.warn("Field protection reveal refused: " + e.getMessage)
                audit("failure", 400, 0, 0, e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                // Class name only: an underlying message could quote a value.
                logger.error("Field protection reveal failed (" + e.getClass.getName + ")")
                audit("failure", 500, 0, 0, "Reveal failed (" + e.getClass.getSimpleName + ")")
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String]("{\"error\": \"Reveal failed\"}")
        }
    }

    /** Issue a new encryption key version and make it current; older versions
      * stay readable. Response: `{"version": n}`. Audited as `key / rotate`. */
    @PostMapping(path = Array("/protect/keys/rotate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def rotateKey(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /protect/keys/rotate called")
            FieldProtectionAPIController.requireCapability(request, "admin")
            APIKeyValidator.validate(apiKey)
            val version = FieldProtectionKey.rotateEncryptionKey((action: String, field: String) =>
                try AuditLog.record(request, "key", action, "field-protection", field)
                catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }
            )
            val response = new JsonObject()
            response.addProperty("version", version)
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: CapabilityDeniedException =>
                FieldProtectionAPIController.denied(request, e, "admin")
            case e: DatrisException =>
                logger.warn("Field protection key rotation refused: " + e.getMessage)
                try AuditLog.record(request, "key", "rotate", "field-protection", null, "failure", 400, null, e.getMessage)
                catch { case _: Exception => () }
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": " + new Gson().toJson(e.getMessage) + "}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                try AuditLog.record(request, "key", "rotate", "field-protection", null, "failure", 500, null, e.getMessage)
                catch { case _: Exception => () }
                ApiErrors.internal(e)
        }
    }
}

object FieldProtectionAPIController {

    /** Values per reveal call. */
    val MaxRevealValues = 1000

    /** Decrypt each token for (pipeline, field): a bad one yields `null` in
      * its slot plus `{index, message}` in errors and the rest still reveal.
      * Empty and null inputs pass through unchanged and count as neither
      * revealed nor failed. Returns (values, errors, revealed, failed). */
    private[api] def decryptAll(
        lookup: Int => Array[Byte],
        pipeline: String,
        field: String,
        tokens: Seq[String]
    ): (JsonArray, JsonArray, Int, Int) = {
        val values = new JsonArray()
        val errors = new JsonArray()
        var revealed = 0
        var failed = 0
        tokens.zipWithIndex.foreach { case (token, i) =>
            try {
                val plain = FieldCipher.decrypt(lookup, pipeline, field, token)
                if (plain == null) values.add(JsonNull.INSTANCE) else values.add(plain)
                if (token != null && token.nonEmpty) revealed += 1
            } catch {
                case e: Exception =>
                    values.add(JsonNull.INSTANCE)
                    failed += 1
                    val err = new JsonObject()
                    err.addProperty("index", i)
                    err.addProperty(
                        "message",
                        e match {
                            case d: DatrisException => d.getMessage
                            case _ => "Value could not be revealed"
                        }
                    )
                    errors.add(err)
            }
        }
        (values, errors, revealed, failed)
    }

    /** Defence in depth behind CapabilityInterceptor: reveal decrypts PHI and
      * admin rotates its key, so the controller enforces `protect:<action>`
      * itself and CAPABILITY_ENFORCEMENT=log-only never opens either route.
      * Runs before anything is read. A no-op when the request carries no
      * resolved key (the platform-wide auth-disabled posture). */
    private[api] def requireCapability(request: HttpServletRequest, action: String): Unit =
        CapabilityCheck.assertScope(request, "protect", action, Map.empty)

    /** Boolean form of [[requireCapability]]: true when the request's resolved
      * key holds `protect:<action>`, and true when no resolved key is on the
      * request (auth disabled). */
    private[api] def holdsCapability(request: HttpServletRequest, action: String): Boolean =
        CapabilityCheck.grants(request, "protect", action, Map.empty)

    /** 403 in the CapabilityInterceptor's shape, recorded once as a
      * `security` denied entry (no `protect` entry for a denied call). */
    private[api] def denied(request: HttpServletRequest, e: CapabilityDeniedException, action: String): ResponseEntity[String] = {
        LoggerFactory.getLogger(classOf[FieldProtectionAPIController]).warn("Field protection " + action + " denied: " + e.getMessage)
        try AuditLog.denied(request, e.getMessage, 403, Some("protect:" + action))
        catch { case _: Exception => () }
        ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body[String](CatalogOps.capabilityDeniedBody(e.getMessage))
    }
}

/** Reveal request body. */
case class RevealRequest(
    pipeline: String = null,
    field: String = null,
    values: java.util.List[String] = null
)

/** Request body — bound the same way ApplyDestTypesRequest is. */
case class SuggestProtectionRequest(
    pipeline: String = null,
    fields: java.util.List[SchemaField] = null
)
