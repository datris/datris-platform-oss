package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.{CapabilityCheck, VersionActor}
import ai.datris.model.{TapConfig, PipelineConfig, DatrisEnvironment, EntityVersion, ValidationException}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Definition-version history: list / view / diff / restore for taps and
  * pipelines. Backed by the append-only `<env>-tap-version` /
  * `<env>-pipeline-version` collections (see plans/tap-pipeline-versioning.md).
  * Diff is computed server-side and returned ready-to-render.
  *
  * Distinct from [[VersionAPIController]], which serves the server BUILD version
  * at `GET /version`. */
object EntityVersionAPIController {

    /** Field protection 8: restoring a tap version is a save, so a snapshot
      * whose secretName is a platform secret (or a server-managed one) is
      * refused like POST /tap. Same contract as
      * [[TapAPIController.tapSecretSaveProblem]]. */
    def tapRestoreProblem(restored: TapConfig, typeOf: String => Option[Option[String]], enforced: Boolean): Option[String] =
        TapAPIController.tapSecretSaveProblem(restored, typeOf, enforced)

    /** Restoring a pipeline version is a save: a snapshot whose new or
      * changed `destination.database` block fails the save rule
      * ([[PipelineAPIController.databaseNameRefusal]]), whose new or changed
      * field type is not one of the documented forms
      * ([[PipelineAPIController.columnTypeRefusal]]), or whose new or
      * changed pgvector schema or table can never be right
      * ([[PipelineAPIController.vectorTableNameRefusal]]), is refused for
      * every caller, with the live config as the stored one; a snapshot naming a vector
      * container that the live config (null when deleted) does not, and
      * that another pipeline names, is refused like POST /pipeline; so is a
      * snapshot that, for a caller who cannot see every pipeline, adds a
      * dataset a pipeline hidden from it writes, and (restricted sessions)
      * one whose new destination already exists. The environment values are
      * read only for such a caller. */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig]
    ): Option[ResponseEntity[String]] =
        PipelineAPIController
            .databaseNameRefusal(snap, live)
            .orElse(PipelineAPIController.columnTypeRefusal(snap, live))
            .orElse(PipelineAPIController.vectorTableNameRefusal(snap, live))
            .orElse(
                if (PipelineAPIController.limitedVisibility(request))
                    pipelineRestoreRefusal(request, snap, live, others, PipelineAPIController.datasetEnv())
                else PipelineAPIController.vectorContainerRefusal(request, snap, live, others)
            )

    /** Version restore, API keys: 403 when the key's scope does not grant
      * `pipeline:update` on the pipeline being restored over (the live
      * config, or the snapshot itself when the pipeline was deleted). None
      * for restricted sessions (`pipelineHidden` answers them), no key, full
      * keys and unrestricted sessions. */
    def pipelineRestoreScopeRefusal(request: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig): Option[ResponseEntity[String]] =
        PipelineAPIController.overwriteRefusal(request, Option(live).getOrElse(snap), PipelineAPIController.RestoreOverwriteActions)

    /** Version restore of a deleted pipeline (no live config),
      * limited-visibility callers: when a pipeline hidden from the caller
      * lists the snapshot's name in `previousNames`
      * ([[PipelineAPIController.hiddenAliasClaimant]]), the refusal a hidden
      * live pipeline of that name gets today: `hiddenResponse` (the 404) for
      * a restricted session, the [[pipelineRestoreScopeRefusal]] 403 for an
      * API key. None otherwise. */
    def pipelineRestoreAliasRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        all: Seq[PipelineConfig],
        hiddenResponse: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] =
        if (snap == null || PipelineAPIController.hiddenAliasClaimant(request, snap.name, all).isEmpty) None
        else if (CapabilityCheck.isRestrictedSession(request)) Some(hiddenResponse)
        else PipelineAPIController.hiddenAliasSaveRefusal(request, snap, all, PipelineAPIController.RestoreOverwriteActions)

    /** [[pipelineRestoreRefusal]] with the shared-dataset environment given;
      * a restricted session's fresh destination is probed with the
      * snapshot's own connection ([[DestinationProbe]]). */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env
    ): Option[ResponseEntity[String]] =
        pipelineRestoreRefusal(request, snap, live, others, env, (t: DestinationTargets.Target) => DestinationProbe.exists(snap, t))

    /** [[pipelineRestoreRefusal]] with the existence probe given: the
      * database name rule ([[PipelineAPIController.databaseNameRefusal]]),
      * the field type rule ([[PipelineAPIController.columnTypeRefusal]]),
      * the pgvector name rule ([[PipelineAPIController.vectorTableNameRefusal]],
      * every caller; a restore does not run the save validator), the vector
      * container rule, then the hidden-writer rule, then (restricted
      * sessions only) the new-destinations-only rule with `snap` as the
      * candidate and `live` as the stored config. */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] =
        PipelineAPIController
            .databaseNameRefusal(snap, live)
            .orElse(PipelineAPIController.columnTypeRefusal(snap, live))
            .orElse(PipelineAPIController.vectorTableNameRefusal(snap, live))
            .orElse(PipelineAPIController.vectorContainerRefusal(request, snap, live, others))
            .orElse(PipelineAPIController.hiddenWriterRefusal(request, snap, live, others, env))
            .orElse(PipelineAPIController.existingDestinationRefusal(request, snap, live, others, env, exists, DestinationProbe.catalogCommitted))

    /** [[pipelineRestoreRefusal]] with the pgvector table probe given too
      * (the six-argument form probes the snapshot's pgvector table with
      * `DestinationProbe.vectorTableExists`). */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        vectorTableExists: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] =
        PipelineAPIController
            .databaseNameRefusal(snap, live)
            .orElse(PipelineAPIController.columnTypeRefusal(snap, live))
            .orElse(PipelineAPIController.vectorTableNameRefusal(snap, live))
            .orElse(PipelineAPIController.vectorContainerRefusal(request, snap, live, others))
            .orElse(PipelineAPIController.hiddenWriterRefusal(request, snap, live, others, env))
            .orElse(
                PipelineAPIController.existingDestinationRefusal(
                    request,
                    snap,
                    live,
                    others,
                    env,
                    exists,
                    DestinationProbe.catalogCommitted,
                    DestinationProbe.DefaultDeadline,
                    vectorTableExists
                )
            )

    /** The config a pipeline version restore saves (decision D2): with a
      * live pipeline, the snapshot's definition under the live `name`,
      * `catalog`, owner (`createdByKeyLabel`) and earlier names, so a restore
      * brings back the definition and never moves the pipeline; for a
      * deleted pipeline (`live` null), the snapshot under the requested name. */
    def pipelineRestoreCandidate(name: String, snap: PipelineConfig, live: PipelineConfig): PipelineConfig =
        if (live != null)
            snap.copy(name = name, catalog = live.catalog, createdByKeyLabel = live.createdByKeyLabel, previousNames = live.previousNames)
        else snap.copy(name = name)

    /** Version restore, every caller, decided from ONE live read (`stored`,
      * null when the pipeline was deleted), in this order: a restricted
      * session whose live pipeline is missing or outside its grants gets
      * `hidden` (the version 404) ([[hiddenFromRestricted]]); an API key
      * outside the scope of the live pipeline (or of the snapshot when
      * deleted) gets the [[pipelineRestoreScopeRefusal]] 403; for a deleted
      * pipeline and a limited-visibility caller, the previous-name check
      * ([[pipelineRestoreAliasRefusal]]; `readAll` is called only then, and a
      * failure is `aliasCheckUnavailable("restored")`, 500); then the
      * restricted-session catalog rules
      * ([[PipelineAPIController.catalogForSave]]). Right is the config to
      * save. Reads nothing itself besides `readAll`. */
    def restoreGuardDecision(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        readAll: () => Seq[PipelineConfig],
        hidden: => ResponseEntity[String]
    ): Either[ResponseEntity[String], PipelineConfig] = {
        if (hiddenFromRestricted(request, "pipeline", "update", Option(stored).map(p => (p.catalog, p.createdByKeyLabel))))
            return Left(hidden)
        pipelineRestoreScopeRefusal(request, candidate, stored).foreach(refused => return Left(refused))
        if (stored == null && PipelineAPIController.limitedVisibility(request)) {
            val all =
                try readAll()
                catch { case _: Exception => return Left(PipelineAPIController.aliasCheckUnavailable("restored")) }
            pipelineRestoreAliasRefusal(request, candidate, all, hidden).foreach(refused => return Left(refused))
        }
        PipelineAPIController.catalogForSave(request, candidate, stored)
    }

    /** Version restore, under `CatalogOps.withCatalogLock` just before the
      * write: `fresh` is the config of that name read under the lock. A
      * restore [[restoreGuardDecision]] refuses on `fresh` gets that refusal;
      * one allowed on `fresh` whose stored config changed since the decision
      * (`decided`: appeared, disappeared, or moved catalog, owner or earlier
      * names) gets [[PipelineAPIController.restoreConflict]] (409). None:
      * write. */
    def restoreRecheck(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        decided: PipelineConfig,
        fresh: PipelineConfig,
        readAll: () => Seq[PipelineConfig],
        hidden: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] =
        restoreGuardDecision(request, candidate, fresh, readAll, hidden) match {
            case Left(refused) => Some(refused)
            case Right(_) if !PipelineAPIController.sameGuardState(decided, fresh) => Some(PipelineAPIController.restoreConflict(candidate.name))
            case Right(_) => None
        }

    /** For a restricted user's session only: true when the item's history
      * must look absent, i.e. the live item is missing (orphaned history of a
      * deleted item) or outside the user's catalog grants for `action`.
      * `live` is (catalog, owner) of the live config, None when there is none.
      * Always false for everyone else, so API keys keep today's behaviour. */
    def hiddenFromRestricted(
        request: HttpServletRequest,
        resource: String,
        action: String,
        live: Option[(String, String)]
    ): Boolean =
        CapabilityCheck.isRestrictedSession(request) && (live match {
            case None => true
            case Some((catalog, owner)) => !CapabilityCheck.grants(request, resource, action, CatalogOps.scopeContext(catalog, owner))
        })
}

@RestController
@RequestMapping(Array("/api/v1"))
class EntityVersionAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[EntityVersionAPIController])
    private val gson = new Gson

    // ---- taps -------------------------------------------------------------

    @GetMapping(path = Array("/tap/versions"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listTapVersions(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        // Hidden: the same empty list as a name with no history.
        if (tapHidden(request, name, "read")) ok("[]")
        else listResponse(DatrisEnvironment.current.tapVersionTableName, name)
    }

    @GetMapping(path = Array("/tap/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        if (tapHidden(request, name, "read")) return notFound("tap", name, version)
        EntityVersionIO.get(DatrisEnvironment.current.tapVersionTableName, name, version) match {
            case Some(v) => ok(snapshotJson(v, withScript = true))
            case None => notFound("tap", name, version)
        }
    }

    @GetMapping(path = Array("/tap/version/diff"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def diffTapVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam against: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        if (tapHidden(request, name, "read")) return notFound("tap", name, version)
        diffResponse(DatrisEnvironment.current.tapVersionTableName, name, against, version, withScript = true)
    }

    @PostMapping(path = Array("/tap/version/restore"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def restoreTapVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        val env = DatrisEnvironment.current
        if (tapHidden(request, name, "update")) return notFound("tap", name, version)
        EntityVersionIO.get(env.tapVersionTableName, name, version) match {
            case None => notFound("tap", name, version)
            case Some(snapshot) =>
                val snap = gson.fromJson(snapshot.config, classOf[TapConfig])
                val live = TapConfigIO.read(env.tapTableName, name)
                // A restricted user may not restore a snapshot into a catalog
                // outside its grants (same rules as a save).
                TapAPIController.catalogForSave(request, snap, live) match {
                    case Left(refused) => return refused
                    case Right(_) =>
                }
                TapAPIController.targetPipelineRefusal(request, snap).foreach(r => return r)
                // Restore the DEFINITION; preserve current run-status + ownership.
                val restored = TapCronGate.normalizeCron(
                    if (live != null)
                        snap.copy(
                            createdAt = live.createdAt,
                            createdByKeyLabel = live.createdByKeyLabel,
                            lastRunStatus = live.lastRunStatus,
                            lastRunTime = live.lastRunTime,
                            lastRunRecordCount = live.lastRunRecordCount,
                            lastRunError = live.lastRunError,
                            lastRunDataType = live.lastRunDataType,
                            lastRunColumns = live.lastRunColumns,
                            lastTestRunStatus = live.lastTestRunStatus,
                            lastTestRunTime = live.lastTestRunTime,
                            lastTestRunRecordCount = live.lastTestRunRecordCount,
                            lastTestRunError = live.lastTestRunError,
                            lastTestRunDataType = live.lastTestRunDataType,
                            lastTestRunColumns = live.lastTestRunColumns,
                            lastTestRunScriptId = live.lastTestRunScriptId
                        )
                    else snap
                )
                // Cron validation: a snapshot minted with an unparseable cron is
                // refused (400) rather than restored as a never-running schedule.
                // `handle` maps exceptions to 500, so answer the 400 explicitly.
                val cronError = TapCronValidation.check(restored.name, restored.cronExpression)
                // Test-before-cron gate: restoring a snapshot whose script bytes differ
                // from the tested stamp while a cron is set is refused, same as a save.
                lazy val secretProblem = EntityVersionAPIController.tapRestoreProblem(
                    restored,
                    TapAPIController.liveSecretType,
                    SecretNames.tapScopeEnforced
                )
                if (cronError.isDefined)
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](TapCronValidation.errorBody(cronError.get))
                else if (secretProblem.isDefined)
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body[String]("{\"error\": " + gson.toJson(secretProblem.get) + "}")
                else TapCronGate.check(live, restored) match {
                    case Some(msg) =>
                        ResponseEntity.status(HttpStatus.CONFLICT).body[String]("{\"error\": \"" + msg.replace("\"", "'") + "\"}")
                    case None =>
                        val saved = TapConfigIO.writeVersioned(
                            restored,
                            "restored from version " + version,
                            VersionActor.resolve(request)
                        )
                        ok(gson.toJson(saved))
                }
        }
    }

    // ---- pipelines --------------------------------------------------------

    @GetMapping(path = Array("/pipeline/versions"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listPipelineVersions(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        // Hidden: the same empty list as a name with no history.
        if (pipelineHidden(request, name, "read")) ok("[]")
        else listResponse(DatrisEnvironment.current.pipelineVersionTableName, name)
    }

    @GetMapping(path = Array("/pipeline/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        if (pipelineHidden(request, name, "read")) return notFound("pipeline", name, version)
        EntityVersionIO.get(DatrisEnvironment.current.pipelineVersionTableName, name, version) match {
            case Some(v) => ok(snapshotJson(v, withScript = false))
            case None => notFound("pipeline", name, version)
        }
    }

    @GetMapping(path = Array("/pipeline/version/diff"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def diffPipelineVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam against: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        if (pipelineHidden(request, name, "read")) return notFound("pipeline", name, version)
        diffResponse(DatrisEnvironment.current.pipelineVersionTableName, name, against, version, withScript = false)
    }

    @PostMapping(path = Array("/pipeline/version/restore"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def restorePipelineVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        val env = DatrisEnvironment.current
        restorePipelineVersionWith(
            name,
            version,
            request,
            () => PipelineConfigIO.read(env.pipelineTableName, name),
            () => EntityVersionIO.get(env.pipelineVersionTableName, name, version)
        )
    }

    /** [[restorePipelineVersion]] after key validation, with the reads given.
      * ONE live read (`readLive`) decides the guard (scope, earlier names,
      * catalog, restricted-session visibility) and the candidate. It comes
      * first: a restricted session whose live pipeline is missing or outside
      * its grants gets the version 404 before the version is read, as the
      * other version endpoints answer it, so the answer and the reads do not
      * depend on whether the version exists or parses. */
    private[api] def restorePipelineVersionWith(
        name: String,
        version: Int,
        request: HttpServletRequest,
        readLive: () => PipelineConfig,
        readVersion: () => Option[EntityVersion]
    ): ResponseEntity[String] = {
        val live = readLive()
        if (EntityVersionAPIController.hiddenFromRestricted(request, "pipeline", "update", Option(live).map(p => (p.catalog, p.createdByKeyLabel))))
            return notFound("pipeline", name, version)
        readVersion() match {
            case None => notFound("pipeline", name, version)
            case Some(snapshot) =>
                val snap = gson.fromJson(snapshot.config, classOf[PipelineConfig])
                restorePipelineSnapshot(name, version, snap, live, request)
        }
    }

    /** Restore `snap` (version `version` of `name`) over `live` (the one
      * stored-config read, null when deleted): the candidate
      * ([[EntityVersionAPIController.pipelineRestoreCandidate]]) is saved
      * through the `POST /pipeline` path, so it is checked like a save of
      * that version. A refusal is returned as is, a `ValidationException` is
      * the 400 `POST /pipeline` gives, and the 200 body is the saved config. */
    private[api] def restorePipelineSnapshot(
        name: String,
        version: Int,
        snap: PipelineConfig,
        live: PipelineConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        val candidate = EntityVersionAPIController.pipelineRestoreCandidate(name, snap, live)
        try
            new PipelineAPIController().restorePipelineInternal(candidate, live, version, notFound("pipeline", name, version), request) match {
                case Left(refused) =>
                    logger.warn("Restore of pipeline " + name + " version " + version + " rejected: HTTP " + refused.getStatusCode.value)
                    refused
                case Right(saved) => ok(gson.toJson(saved))
            }
        catch {
            case v: ValidationException =>
                logger.warn("Restore of pipeline " + name + " version " + version + " rejected: " + v.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](QueryAPIController.errorBody(v))
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
        if (withScript) o.addProperty("script", resolveScript(v))
        o.toString
    }

    private def diffResponse(table: String, name: String, against: Int, version: Int, withScript: Boolean): ResponseEntity[String] = {
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
            val beforeScript = resolveScript(from.get)
            val afterScript = resolveScript(to.get)
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

    /** A tap snapshot's script text; null if absent. A built-in-store tap
      * pins `scriptPath`; a repository-backed tap's snapshot has no
      * `scriptPath` but its config carries `scriptRepoPath` + `scriptCommitSha`,
      * so read the file at that commit (read-only, cached per sha). */
    private def resolveScript(v: EntityVersion): String = {
        if (v.scriptPath != null && v.scriptPath.nonEmpty) return resolveScript(v.scriptPath)
        try {
            val snap = if (v.config == null) null else gson.fromJson(v.config, classOf[TapConfig])
            if (snap == null || snap.scriptStorage != "github" || snap.scriptRepoPath == null || snap.scriptRepoPath.isEmpty) null
            else TapCodeStore.forTap(snap).readScript(snap).orNull
        } catch {
            case e: Exception =>
                logger.warn("Failed to read tap script for '" + v.entityName + "' v" + v.version + " from the code repository", e)
                null
        }
    }

    /** Read a pinned tap script's content from object storage; null if absent. */
    private def resolveScript(scriptPath: String): String = {
        if (scriptPath == null || scriptPath.isEmpty) return null
        try {
            val bucket = DatrisEnvironment.current.environment + "-config"
            ObjectStoreUtil.readBucketObject(bucket, scriptPath).orNull
        } catch {
            case e: Exception =>
                logger.warn("Failed to read tap script '" + scriptPath + "' from object storage", e)
                null
        }
    }

    private def tapHidden(request: HttpServletRequest, name: String, action: String): Boolean =
        CapabilityCheck.isRestrictedSession(request) &&
            EntityVersionAPIController.hiddenFromRestricted(
                request,
                "tap",
                action,
                Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)).map(t => (t.catalog, t.createdByKeyLabel))
            )

    private def pipelineHidden(request: HttpServletRequest, name: String, action: String): Boolean =
        CapabilityCheck.isRestrictedSession(request) &&
            EntityVersionAPIController.hiddenFromRestricted(
                request,
                "pipeline",
                action,
                Option(PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)).map(p => (p.catalog, p.createdByKeyLabel))
            )

    private def ok(json: String): ResponseEntity[String] =
        new ResponseEntity[String](json, HttpStatus.OK)

    private def notFound(kind: String, name: String, version: Int): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](
            "{\"error\": \"No version " + version + " for " + kind + " '" + name + "'\"}"
        )

    private def handle(body: => ResponseEntity[String]): ResponseEntity[String] = {
        try body
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}
