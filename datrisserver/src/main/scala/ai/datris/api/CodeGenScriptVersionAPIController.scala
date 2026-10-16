package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess}
import ai.datris.model.{CodeGenScriptVersion, DatrisEnvironment, DatrisException, PipelineConfig}
import ai.datris.util._
import com.google.common.base.Throwables
import com.google.gson.{JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Version history of the CodeGen scripts a pipeline runs (AI data-quality
  * rule, AI transformation): list, read, diff and restore. Backed by the
  * append-only `<env>-codegen-script-version` collection through
  * [[PipelineScripts]]. A restore writes the chosen text back as a new latest
  * version; the pipeline's definition version does not change.
  *
  * The route methods do the key, pipeline-404 and owner-scope checks; the
  * bodies are built by the pure helpers on the companion. */
object CodeGenScriptVersionAPIController {

    private def json(status: HttpStatus, body: String): ResponseEntity[String] =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body[String](body)

    private def error(status: HttpStatus, message: String): ResponseEntity[String] =
        json(status, QueryAPIController.errorBody(new DatrisException(message)))

    private def unknownKind(kind: String): ResponseEntity[String] =
        error(HttpStatus.BAD_REQUEST, PipelineScripts.unknownKindMessage(kind))

    private def notFound(config: PipelineConfig, kind: String, version: Int): ResponseEntity[String] =
        error(HttpStatus.NOT_FOUND, PipelineScripts.noVersionMessage(config.name, kind, version))

    private def currentFingerprint(config: PipelineConfig, kind: String): Option[String] =
        PipelineScripts.instructionOf(config, kind).map(i => PipelineScripts.fingerprint(i, PipelineScripts.schemaSignature(config)))

    private def summary(v: CodeGenScriptVersion, current: Option[String], live: Int): JsonObject = {
        val o = new JsonObject
        o.addProperty("version", v.version)
        o.addProperty("createdAt", v.createdAt)
        o.addProperty("createdBy", v.createdBy)
        o.addProperty("changeNote", v.changeNote)
        o.addProperty("origin", v.origin)
        o.addProperty("model", v.model)
        o.add("commitSha", if (v.scriptCommitSha == null) com.google.gson.JsonNull.INSTANCE else new com.google.gson.JsonPrimitive(v.scriptCommitSha))
        o.addProperty("fingerprintMatchesCurrent", current.contains(v.fingerprint))
        o.addProperty("isLive", live > 0 && v.version == live)
        o
    }

    /** Gson drops null properties when serialising; keep `commitSha: null`. */
    private def render(el: com.google.gson.JsonElement): String = {
        val gson = new com.google.gson.GsonBuilder().serializeNulls().create()
        gson.toJson(el)
    }

    /** GET …/versions: newest first, without the script text. 400 unknown kind. */
    def listVersionsWith(scripts: PipelineScripts, config: PipelineConfig, kind: String): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind)) return unknownKind(kind)
        val all = scripts.scriptVersions(config.name, kind)
        val versions = all.sortBy(-_.version)
        val current = currentFingerprint(config, kind)
        val live = scripts.liveVersion(config.name, kind, all)
        val arr = new JsonArray
        versions.foreach(v => arr.add(summary(v, current, live)))
        json(HttpStatus.OK, render(arr))
    }

    /** GET …/versions/{version}: the list fields plus script, fingerprint,
      * generatedAt, contractVersion, storage and repoPath. 404 unknown version. */
    def getVersionWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind)) return unknownKind(kind)
        val all = scripts.scriptVersions(config.name, kind)
        all.find(_.version == version) match {
            case None => notFound(config, kind, version)
            case Some(v) =>
                val o = summary(v, currentFingerprint(config, kind), scripts.liveVersion(config.name, kind, all))
                o.addProperty("kind", v.kind)
                o.addProperty("script", v.script)
                o.addProperty("fingerprint", v.fingerprint)
                o.addProperty("generatedAt", v.generatedAt)
                o.addProperty("contractVersion", v.contractVersion)
                o.addProperty("storage", v.storage)
                o.addProperty("repoPath", v.scriptRepoPath)
                json(HttpStatus.OK, render(o))
        }
    }

    /** GET …/versions/{version}/diff?against=N:
      * `{from: N, to: version, script: {before, after, lines: [{type, text}]}}`,
      * `type` ctx | add | del. 404 when either version is unknown. */
    def diffVersionsWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int, against: Int): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind)) return unknownKind(kind)
        val all = scripts.scriptVersions(config.name, kind)
        val from = all.find(_.version == against)
        val to = all.find(_.version == version)
        if (from.isEmpty) return notFound(config, kind, against)
        if (to.isEmpty) return notFound(config, kind, version)
        val out = new JsonObject
        out.addProperty("from", against)
        out.addProperty("to", version)
        val lines = new JsonArray
        VersionDiff.scriptDiff(from.get.script, to.get.script).foreach { dl =>
            val o = new JsonObject
            o.addProperty("type", dl.`type`)
            o.addProperty("text", dl.text)
            lines.add(o)
        }
        val script = new JsonObject
        script.addProperty("before", from.get.script)
        script.addProperty("after", to.get.script)
        script.add("lines", lines)
        out.add("script", script)
        json(HttpStatus.OK, out.toString)
    }

    /** POST …/versions/{version}/restore: 200 with the kind's GET entry
      * (carrying the new `version`); 400 unknown kind or no instruction; 404
      * unknown version; 409 a repository file edited since the recorded
      * commit; 422 a version written for another instruction, source schema
      * or script contract; 502 the script could not be stored. */
    def restoreVersionWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int, actor: String): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind)) return unknownKind(kind)
        if (PipelineScripts.instructionOf(config, kind).isEmpty)
            return error(HttpStatus.BAD_REQUEST, "Pipeline: " + config.name + " has no AI " + kind + " instruction to restore a script for")
        scripts.restoreVersion(config, kind, version, actor) match {
            case Right(rec) =>
                val entry = PipelineAPIController.codegenScriptEntry(scripts, config.name, kind, rec.instruction, scripts.modelNow)
                json(HttpStatus.OK, entry.toString)
            case Left(msg) => error(restoreFailureStatus(config, kind, version, msg), msg)
        }
    }

    private[api] def restoreFailureStatus(config: PipelineConfig, kind: String, version: Int, msg: String): HttpStatus =
        if (msg == PipelineScripts.unknownKindMessage(kind)) HttpStatus.BAD_REQUEST
        else if (msg == PipelineScripts.noVersionMessage(config.name, kind, version)) HttpStatus.NOT_FOUND
        else if (msg.contains(PipelineScripts.ConflictMarker)) HttpStatus.CONFLICT
        else if (msg.contains("different instruction or source schema") || msg.contains("contract version") || msg.contains("holds no script text"))
            HttpStatus.UNPROCESSABLE_ENTITY
        else HttpStatus.BAD_GATEWAY
}

@RestController
@RequestMapping(Array("/api/v1"))
class CodeGenScriptVersionAPIController {
    import CodeGenScriptVersionAPIController._

    private val logger: Logger = LoggerFactory.getLogger(classOf[CodeGenScriptVersionAPIController])

    /** Validate the key, load the pipeline (404), check owner scope, then run
      * `body`. An unknown kind is answered by `body` (400), so it comes after
      * authentication and a refused restore is audited. */
    private def withPipeline(apiKey: String, name: String, action: String, request: HttpServletRequest)(
        body: PipelineConfig => ResponseEntity[String]
    ): ResponseEntity[String] =
        try {
            APIKeyValidator.validate(apiKey)
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            else {
                CapabilityCheck.assertOwnerScope(request, "pipeline", action, config.createdByKeyLabel)
                body(config)
            }
        } catch {
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }

    @GetMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/versions"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listScriptVersions(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        logger.info("API endpoint GET /pipelines/" + name + "/codegen-scripts/" + kind + "/versions called")
        withPipeline(apiKey, name, "read", request)(config => listVersionsWith(PipelineScripts, config, kind))
    }

    @GetMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/versions/{version}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getScriptVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        @PathVariable("version") version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        logger.info("API endpoint GET /pipelines/" + name + "/codegen-scripts/" + kind + "/versions/" + version + " called")
        withPipeline(apiKey, name, "read", request)(config => getVersionWith(PipelineScripts, config, kind, version))
    }

    @GetMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/diff"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def diffScriptVersions(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        @PathVariable("version") version: Int,
        @RequestParam("against") against: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        logger.info("API endpoint GET /pipelines/" + name + "/codegen-scripts/" + kind + "/versions/" + version + "/diff called")
        withPipeline(apiKey, name, "read", request)(config => diffVersionsWith(PipelineScripts, config, kind, version, against))
    }

    @PostMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/restore"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def restoreScriptVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        @PathVariable("version") version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        logger.info("API endpoint POST /pipelines/" + name + "/codegen-scripts/" + kind + "/versions/" + version + "/restore called")
        withPipeline(apiKey, name, "update", request) { config =>
            val response = restoreVersionWith(PipelineScripts, config, kind, version, ResolvedKeyAccess.keyLabel(request).orNull)
            if (response.getStatusCode.is2xxSuccessful)
                PipelineAPIController.auditCodegenScript(request, "restore-version", name, kind, "success", None)
            else PipelineAPIController.auditCodegenScript(request, "restore-version", name, kind, "failure", Some(String.valueOf(response.getBody)))
            response
        }
    }
}
