package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonElement, JsonObject, JsonParseException, JsonParser}
import ai.datris.audit.AuditLog
import ai.datris.auth.CapabilityCheck
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ValidationException}
import ai.datris.util.{AISchemaUtil, APIKeyValidator, AiSampleValues, CatalogOps, ObjectStoreUtil, PipelineConfigIO}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.ByteArrayInputStream
import java.net.URI

/** Pure request checks for [[ConfigAPIController]]. Every refusal is a
  * [[ValidationException]], which `ApiErrors.classify` maps to
  * `400 {"error": <message>}`. No message echoes the request body or the JSON
  * parser's own text. */
object ConfigAPIController {
    private[api] val ValidTypes = Set("validation-schema", "javascript")
    private[api] val ValidSchemaTypes = Set("json-schema", "xsd")

    private val RequiredFields = Seq("type", "name", "sampleData")

    /** A checked generate-schema request; `name` is trimmed and already a safe key segment. */
    case class GenerateSchemaRequest(schemaType: String, name: String, sampleData: String)

    /** Reduce a user-supplied filename to a safe single object-key segment.
      * Without this, a name like `../tap-scripts/x.py` escapes the intended
      * prefix and can write into another prefix of the same config bucket —
      * including the tap-scripts prefix, whose files are later executed. Strip
      * any path, reject traversal, and restrict to a safe charset. */
    private[api] def sanitizeKeySegment(name: String): String = {
        if (name == null) throw new ValidationException("File name is required")
        val base = name.replace("\\", "/").split("/").filter(_.nonEmpty).lastOption.getOrElse("")
        if (base.isEmpty || base == "." || base == "..")
            throw new ValidationException("Invalid file name: " + name)
        if (!base.matches("[A-Za-z0-9._-]+"))
            throw new ValidationException("File name '" + base + "' contains invalid characters. Allowed: letters, digits, . _ -")
        base
    }

    private[api] def requireUploadType(fileType: String): Unit =
        if (!ValidTypes.contains(fileType))
            throw new ValidationException("Invalid config file type: " + fileType + ". Must be one of: " + ValidTypes.mkString(", "))

    /** Parse and check a POST /config/generate-schema body. Checks, in order:
      * valid JSON, a JSON object, all of type/name/sampleData present and not
      * null, each a JSON primitive, then the schema type, blank name, blank
      * sample data, and finally the name as an object-key segment. */
    private[api] def parseGenerateSchema(body: String): GenerateSchemaRequest = {
        val parsed: JsonElement =
            if (body == null) null
            else
                try JsonParser.parseString(body)
                catch { case _: JsonParseException => throw new ValidationException("Request body is not valid JSON") }
        if (parsed == null || !parsed.isJsonObject)
            throw new ValidationException("Request body must be a JSON object with string fields: " + RequiredFields.mkString(", "))
        val json = parsed.getAsJsonObject

        val missing = RequiredFields.filter(f => !json.has(f) || json.get(f).isJsonNull)
        if (missing.nonEmpty)
            throw new ValidationException("Missing required field(s): " + missing.mkString(", "))
        RequiredFields.find(f => !json.get(f).isJsonPrimitive).foreach { f =>
            throw new ValidationException("Field '" + f + "' must be a string")
        }

        val schemaType = json.get("type").getAsString
        val name = json.get("name").getAsString
        val sampleData = json.get("sampleData").getAsString

        if (!ValidSchemaTypes.contains(schemaType))
            throw new ValidationException("Invalid schema type: " + schemaType + ". Must be one of: " + ValidSchemaTypes.mkString(", "))
        if (name.trim.isEmpty)
            throw new ValidationException("Schema name is required")
        if (sampleData.trim.isEmpty)
            throw new ValidationException("Sample data is required")

        GenerateSchemaRequest(schemaType, sanitizeKeySegment(name.trim), sampleData)
    }

    private val companionLogger: Logger = LoggerFactory.getLogger(classOf[ConfigAPIController])

    /** What POST /config/generate-schema reads and writes, so the handler can
      * be exercised without the object store, the config DB or a model. */
    trait GenerateSchemaDeps {

        /** Every stored pipeline config. Throws when they cannot be read. */
        def pipelines(): Seq[PipelineConfig]

        /** The model call; schemaType is "json-schema" or "xsd". */
        def generate(schemaType: String, sampleData: String): String

        def write(bucket: String, key: String, bytes: Array[Byte]): Unit
    }

    /** The live dependencies: config DB, model, object store. */
    object LiveGenerateSchemaDeps extends GenerateSchemaDeps {
        override def pipelines(): Seq[PipelineConfig] = PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
        override def generate(schemaType: String, sampleData: String): String = schemaType match {
            case "json-schema" => AISchemaUtil.generateJsonSchema(sampleData)
            case "xsd" => AISchemaUtil.generateXsdSchema(sampleData)
        }
        override def write(bucket: String, key: String, bytes: Array[Byte]): Unit =
            ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new ByteArrayInputStream(bytes), bytes.length.toLong)
    }

    /** The object (bucket, key) a `dataQuality.validationSchema` value names,
      * resolved as `DataQuality` and `SchemaValidationUtil` resolve it at run
      * time: an `s3://` value as given, any other value under
      * `<configBucket>/validation-schema/`; bucket and key through
      * `java.net.URI`, so percent-encoding is decoded. None when the value
      * cannot be parsed (a run cannot read any object through it either). */
    private[api] def schemaObject(value: String, configBucket: String): Option[(String, String)] = {
        if (value == null || value.isEmpty) return None
        val url = if (value.startsWith("s3://")) value else "s3://" + configBucket + "/validation-schema/" + value
        try {
            val uri = new URI(url)
            Option(uri.getPath).map(p => (uri.getHost, if (p.startsWith("/")) p.substring(1) else p))
        } catch { case _: Exception => None }
    }

    /** The stored pipelines whose validation schema is the object at
      * configBucket/validation-schema/file. Keys are case-sensitive. */
    private[api] def pipelinesUsing(file: String, configBucket: String, all: Seq[PipelineConfig]): Seq[PipelineConfig] = {
        val target = schemaObject(file, configBucket)
        all.filter { p =>
            p != null && p.dataQuality != null &&
            target.isDefined && schemaObject(p.dataQuality.validationSchema, configBucket) == target
        }
    }

    private def inUseMessage(file: String): String =
        "A validation schema named '" + file + "' is already in use. Choose another schema name."

    /** 409 when a pipeline the caller may not update validates against the
      * schema `file` (whether or not the file is stored yet): writing it would
      * change what that pipeline accepts. The answer names no pipeline and is
      * the same for every caller. None otherwise. */
    def schemaOverwriteRefusal(file: String, referencing: Seq[PipelineConfig], mayUpdate: PipelineConfig => Boolean): Option[ResponseEntity[String]] =
        if (referencing.forall(mayUpdate)) None
        else
            Some(
                ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(inUseMessage(file)))
            )

    /** The stored-item rule: the caller's identity grants `pipeline:update`
      * on the pipeline's catalog and owner. No identity (keys off): yes. */
    private[api] def mayUpdatePipeline(request: HttpServletRequest)(p: PipelineConfig): Boolean =
        CapabilityCheck.resolvedKey(request) match {
            case None => true
            case Some(rk) => rk.grants("pipeline", "update", CatalogOps.scopeContext(p.catalog, p.createdByKeyLabel))
        }

    /** POST /config/generate-schema after the key check: the request checks
      * of [[parseGenerateSchema]] (400), then the in-use rule (409), then the
      * model call and the write. A failed pipeline read is a 500 with nothing
      * generated or written. */
    def generateSchemaWith(request: HttpServletRequest, body: String, deps: GenerateSchemaDeps): ResponseEntity[String] = {
        try {
            val req = parseGenerateSchema(body)
            val extension = if (req.schemaType == "xsd") ".xsd" else ".json"
            val filename = req.name + extension
            val bucket = DatrisEnvironment.current.environment + "-config"
            val key = "validation-schema/" + filename

            if (request != null) {
                val md = new JsonObject()
                md.addProperty("file", filename)
                request.setAttribute(AuditLog.MetadataAttr, md)
            }

            val referencing = pipelinesUsing(filename, bucket, deps.pipelines())
            schemaOverwriteRefusal(filename, referencing, mayUpdatePipeline(request)) match {
                case Some(refusal) =>
                    companionLogger.info("Generate schema refused: " + filename + " is used by a pipeline the caller may not update")
                    if (request != null) request.setAttribute(AuditLog.ErrorMessageAttr, inUseMessage(filename))
                    refusal
                case None =>
                    val schema = deps.generate(req.schemaType, req.sampleData)
                    deps.write(bucket, key, schema.getBytes("UTF-8"))

                    companionLogger.info("Generated schema uploaded: s3://" + bucket + "/" + key)

                    val gson = new Gson
                    val response = new java.util.LinkedHashMap[String, Any]()
                    response.put("filename", filename)
                    response.put("path", "s3://" + bucket + "/" + key)
                    response.put("schema", schema)
                    // DATRIS_AI_SAMPLE_VALUES=false: generated from a value-free skeleton.
                    if (!AiSampleValues.enabled) response.put("valuesWithheld", true)
                    new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                companionLogger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}

@RestController
@RequestMapping(Array("/api/v1"))
class ConfigAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ConfigAPIController])

    @PostMapping(path = Array("/config/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadConfigFile(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam("type") fileType: String,
        @RequestPart("file") file: MultipartFile
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/upload called, type: " + fileType + ", filename: " + file.getOriginalFilename)
            APIKeyValidator.validate(apiKey)

            ConfigAPIController.requireUploadType(fileType)

            val filename = ConfigAPIController.sanitizeKeySegment(file.getOriginalFilename)

            val bucket = DatrisEnvironment.current.environment + "-config"
            val key = fileType + "/" + filename

            val bytes = file.getBytes
            ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new ByteArrayInputStream(bytes), bytes.length.toLong)

            logger.info("Config file uploaded: s3://" + bucket + "/" + key)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("filename", filename)
            response.put("path", "s3://" + bucket + "/" + key)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(
        path = Array("/config/generate-schema"),
        consumes = Array(MediaType.APPLICATION_JSON_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def generateSchema(
        request: HttpServletRequest,
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/generate-schema called")
            APIKeyValidator.validate(apiKey)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                return ApiErrors.internal(e)
        }
        ConfigAPIController.generateSchemaWith(request, body, ConfigAPIController.LiveGenerateSchemaDeps)
    }
}
