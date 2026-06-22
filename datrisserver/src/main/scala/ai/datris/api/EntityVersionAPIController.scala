package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.VersionActor
import ai.datris.model.{TapConfig, PipelineConfig, DatrisEnvironment, EntityVersion}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Definition-version history: list / view / diff / restore for taps and
  * pipelines. Backed by the append-only `<env>-tap-version` /
  * `<env>-pipeline-version` collections (see docs/plans/tap-pipeline-versioning.md).
  * Diff is computed server-side and returned ready-to-render.
  *
  * Distinct from [[VersionAPIController]], which serves the server BUILD version
  * at `GET /version`. */
@RestController
@RequestMapping(Array("/api/v1"))
class EntityVersionAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[EntityVersionAPIController])
    private val gson = new Gson

    // ---- taps -------------------------------------------------------------

    @GetMapping(path = Array("/tap/versions"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listTapVersions(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                        @RequestParam name: String): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        listResponse(DatrisEnvironment.current.tapVersionTableName, name)
    }

    @GetMapping(path = Array("/tap/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                      @RequestParam name: String,
                      @RequestParam version: Int): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        EntityVersionIO.get(DatrisEnvironment.current.tapVersionTableName, name, version) match {
            case Some(v) => ok(snapshotJson(v, withScript = true))
            case None    => notFound("tap", name, version)
        }
    }

    @GetMapping(path = Array("/tap/version/diff"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def diffTapVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestParam name: String,
                       @RequestParam version: Int,
                       @RequestParam against: Int): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        diffResponse(DatrisEnvironment.current.tapVersionTableName, name, against, version, withScript = true)
    }

    @PostMapping(path = Array("/tap/version/restore"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def restoreTapVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                          @RequestParam name: String,
                          @RequestParam version: Int,
                          request: HttpServletRequest): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        val env = DatrisEnvironment.current
        EntityVersionIO.get(env.tapVersionTableName, name, version) match {
            case None => notFound("tap", name, version)
            case Some(snapshot) =>
                val snap = gson.fromJson(snapshot.config, classOf[TapConfig])
                val live = TapConfigIO.read(env.tapTableName, name)
                // Restore the DEFINITION; preserve current run-status + ownership.
                val restored =
                    if (live != null)
                        snap.copy(
                            createdAt = live.createdAt,
                            createdByKeyLabel = live.createdByKeyLabel,
                            lastRunStatus = live.lastRunStatus, lastRunTime = live.lastRunTime,
                            lastRunRecordCount = live.lastRunRecordCount, lastRunError = live.lastRunError,
                            lastRunDataType = live.lastRunDataType, lastRunColumns = live.lastRunColumns,
                            lastTestRunStatus = live.lastTestRunStatus, lastTestRunTime = live.lastTestRunTime,
                            lastTestRunRecordCount = live.lastTestRunRecordCount, lastTestRunError = live.lastTestRunError,
                            lastTestRunDataType = live.lastTestRunDataType, lastTestRunColumns = live.lastTestRunColumns
                        )
                    else snap
                val saved = TapConfigIO.writeVersioned(
                    restored, "restored from version " + version, VersionActor.resolve(request))
                ok(gson.toJson(saved))
        }
    }

    // ---- pipelines --------------------------------------------------------

    @GetMapping(path = Array("/pipeline/versions"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listPipelineVersions(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                             @RequestParam name: String): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        listResponse(DatrisEnvironment.current.pipelineVersionTableName, name)
    }

    @GetMapping(path = Array("/pipeline/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                           @RequestParam name: String,
                           @RequestParam version: Int): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        EntityVersionIO.get(DatrisEnvironment.current.pipelineVersionTableName, name, version) match {
            case Some(v) => ok(snapshotJson(v, withScript = false))
            case None    => notFound("pipeline", name, version)
        }
    }

    @GetMapping(path = Array("/pipeline/version/diff"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def diffPipelineVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                            @RequestParam name: String,
                            @RequestParam version: Int,
                            @RequestParam against: Int): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        diffResponse(DatrisEnvironment.current.pipelineVersionTableName, name, against, version, withScript = false)
    }

    @PostMapping(path = Array("/pipeline/version/restore"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def restorePipelineVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                               @RequestParam name: String,
                               @RequestParam version: Int,
                               request: HttpServletRequest): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        val env = DatrisEnvironment.current
        EntityVersionIO.get(env.pipelineVersionTableName, name, version) match {
            case None => notFound("pipeline", name, version)
            case Some(snapshot) =>
                val snap = gson.fromJson(snapshot.config, classOf[PipelineConfig])
                val live = PipelineConfigIO.read(env.pipelineTableName, name)
                val restored =
                    if (live != null) snap.copy(createdByKeyLabel = live.createdByKeyLabel) else snap
                val saved = PipelineConfigIO.writeVersioned(
                    restored, "restored from version " + version, VersionActor.resolve(request))
                ok(gson.toJson(saved))
        }
    }

    // ---- shared helpers ---------------------------------------------------

    /** Lightweight list (newest first): version, createdAt, createdBy, changeNote. */
    private def listResponse(table: String, name: String): ResponseEntity[String] = {
        val versions = EntityVersionIO.listVersions(table, name).sortBy(-_.version)
        val arr = new JsonArray()
        versions.foreach { v =>
            val o = new JsonObject()
            o.addProperty("version", v.version)
            o.addProperty("createdAt", v.createdAt)
            o.addProperty("createdBy", v.createdBy)
            o.addProperty("changeNote", v.changeNote)
            arr.add(o)
        }
        ok(arr.toString)
    }

    /** Full snapshot: metadata + parsed config (+ resolved script text for taps). */
    private def snapshotJson(v: EntityVersion, withScript: Boolean): String = {
        val o = new JsonObject()
        o.addProperty("version", v.version)
        o.addProperty("createdAt", v.createdAt)
        o.addProperty("createdBy", v.createdBy)
        o.addProperty("changeNote", v.changeNote)
        o.addProperty("scriptPath", v.scriptPath)
        o.add("config", JsonParser.parseString(if (v.config == null) "{}" else v.config))
        if (withScript) o.addProperty("script", resolveScript(v.scriptPath))
        o.toString
    }

    private def diffResponse(table: String, name: String, against: Int, version: Int,
                             withScript: Boolean): ResponseEntity[String] = {
        val kind = if (withScript) "tap" else "pipeline"
        val from = EntityVersionIO.get(table, name, against)
        val to = EntityVersionIO.get(table, name, version)
        if (from.isEmpty) return notFound(kind, name, against)
        if (to.isEmpty) return notFound(kind, name, version)

        val out = new JsonObject()
        out.addProperty("from", against)
        out.addProperty("to", version)

        val fieldArr = new JsonArray()
        VersionDiff.configDiff(from.get.config, to.get.config).foreach { fc =>
            val o = new JsonObject()
            o.addProperty("path", fc.path)
            o.addProperty("before", fc.before)
            o.addProperty("after", fc.after)
            o.addProperty("change", fc.change)
            fieldArr.add(o)
        }
        out.add("config", fieldArr)

        if (withScript) {
            val beforeScript = resolveScript(from.get.scriptPath)
            val afterScript = resolveScript(to.get.scriptPath)
            val lineArr = new JsonArray()
            VersionDiff.scriptDiff(beforeScript, afterScript).foreach { dl =>
                val o = new JsonObject()
                o.addProperty("type", dl.`type`)
                o.addProperty("text", dl.text)
                lineArr.add(o)
            }
            val scriptObj = new JsonObject()
            scriptObj.addProperty("before", beforeScript)
            scriptObj.addProperty("after", afterScript)
            scriptObj.add("lines", lineArr)
            out.add("script", scriptObj)
        }
        ok(out.toString)
    }

    /** Read a pinned tap script's content from object storage; null if absent. */
    private def resolveScript(scriptPath: String): String = {
        if (scriptPath == null || scriptPath.isEmpty) return null
        try {
            val bucket = DatrisEnvironment.current.environment + "-config"
            ObjectStoreUtil.readBucketObject(bucket, scriptPath).orNull
        } catch { case _: Exception => null }
    }

    private def ok(json: String): ResponseEntity[String] =
        new ResponseEntity[String](json, HttpStatus.OK)

    private def notFound(kind: String, name: String, version: Int): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](
            "{\"error\": \"No version " + version + " for " + kind + " '" + name + "'\"}")

    private def handle(body: => ResponseEntity[String]): ResponseEntity[String] = {
        try body
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
