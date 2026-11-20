package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.CapabilityCheck
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
      * config, or the snapshot itself when the pipeline was deleted), or,
      * when it does, 403 on `pipeline:read` when the key cannot read it. None
      * for restricted sessions (`versionRefusal` answers them), no key, full
      * keys and unrestricted sessions. */
    def pipelineRestoreScopeRefusal(request: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig): Option[ResponseEntity[String]] = {
        val target = Option(live).getOrElse(snap)
        PipelineAPIController.overwriteRefusal(request, target, PipelineAPIController.RestoreOverwriteActions).orElse {
            // A key that may update but cannot read the pipeline does not
            // restore over it (scoped-key-read-routes, the stored-item write rule).
            if (target == null) None
            else
                CapabilityCheck.resolvedKey(request).flatMap { rk =>
                    if (
                        ai.datris.auth.CallerVisibility.keyOutOfScope(rk, "pipeline", "read", CatalogOps.scopeContext(target.catalog, target.createdByKeyLabel))
                    )
                        Some(PipelineAPIController.readScopeDenied(request, target))
                    else None
                }
        }
    }

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
      * `DestinationProbe.vectorTableExists`); qdrant, weaviate, milvus and
      * chroma containers with `DestinationProbe.vectorContainerExists`. */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        vectorTableExists: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] =
        pipelineRestoreRefusal(
            request,
            snap,
            live,
            others,
            env,
            exists,
            vectorTableExists,
            (t: DestinationTargets.VectorContainerTarget) => DestinationProbe.vectorContainerExists(t)
        )

    /** [[pipelineRestoreRefusal]] with the vector container probe given too
      * (`vectorContainerExists`, restricted sessions: a snapshot's new or
      * changed qdrant / weaviate / milvus / chroma container must not exist
      * yet; one the live config keeps is not probed). */
    def pipelineRestoreRefusal(
        request: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        vectorTableExists: DestinationTargets.Target => Boolean,
        vectorContainerExists: DestinationTargets.VectorContainerTarget => Boolean
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
                    vectorTableExists,
                    vectorContainerExists
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
      * pipeline, the right to create it as re-created
      * ([[PipelineAPIController.recreateRefusal]], 403 on `create`); for a
      * deleted pipeline and a limited-visibility caller, the previous-name check
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
        // Sessions only: an API key outside the scope keeps its 403 below.
        if (
            CapabilityCheck.isRestrictedSession(request) &&
            hiddenFromRestricted(request, "pipeline", "update", Option(stored).map(p => (p.catalog, p.createdByKeyLabel)))
        )
            return Left(hidden)
        pipelineRestoreScopeRefusal(request, candidate, stored).foreach(refused => return Left(refused))
        // A deleted pipeline is re-created: the caller must be allowed to
        // create it, as POST /pipeline requires for a new pipeline (owner
        // decision).
        if (stored == null) PipelineAPIController.recreateRefusal(request, candidate).foreach(refused => return Left(refused))
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

    /** True when the item's history is outside the caller's view. A
      * restricted user's session: the live item is missing (orphaned history
      * of a deleted item) or outside the user's catalog grants for `action`.
      * An API key (plans/stories/scoped-key-read-routes.md, D0): `live` is
      * given and the key's scope refuses `action` on it
      * ([[ai.datris.auth.CallerVisibility.keyOutOfScope]]); a key with no
      * `live` is decided by the caller from the latest stored version. `live`
      * is (catalog, owner) of the live config, None when there is none.
      * Always false for unrestricted sessions, full keys and no identity. */
    def hiddenFromRestricted(
        request: HttpServletRequest,
        resource: String,
        action: String,
        live: Option[(String, String)]
    ): Boolean =
        if (CapabilityCheck.isRestrictedSession(request)) live match {
            case None => true
            case Some((catalog, owner)) => !CapabilityCheck.grants(request, resource, action, CatalogOps.scopeContext(catalog, owner))
        }
        else
            live.exists { case (catalog, owner) =>
                CapabilityCheck.resolvedKey(request).exists(rk =>
                    ai.datris.auth.CallerVisibility.keyOutOfScope(rk, resource, action, CatalogOps.scopeContext(catalog, owner))
                )
            }

    /** The version routes' scope decision on `live` (the live item's
      * (catalog, owner), or for an API key the latest stored version's when
      * the item was deleted; None when nothing establishes scope). None:
      * answer as today. A restricted session hidden from the history gets
      * `sessionAnswer` (the route's answer for a name with no history); an
      * API key outside its scope gets the capability-denied 403 for
      * `resource:action`. */
    def versionRefusal(
        request: HttpServletRequest,
        resource: String,
        action: String,
        live: Option[(String, String)],
        sessionAnswer: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] =
        if (CapabilityCheck.isRestrictedSession(request)) {
            if (hiddenFromRestricted(request, resource, action, live)) Some(sessionAnswer) else None
        } else
            live.flatMap { case (catalog, owner) =>
                // A restore is a write: the stored-item write rule (the action
                // in scope, then read in scope), as on a save.
                if (action == "read") PipelineAPIController.keyRefusal(request, resource, "read", CatalogOps.scopeContext(catalog, owner))
                else if (resource == "tap") TapAPIController.storedTapWriteRefusal(request, catalog, owner)
                else PipelineAPIController.storedWriteRefusal(request, resource, CatalogOps.scopeContext(catalog, owner), Seq(action), action)
            }

    /** Whether the version routes must read anything to decide: a
      * restricted session, or an API key whose scope can refuse
      * (resource, action). */
    def versionCheckNeeded(request: HttpServletRequest, resource: String, action: String): Boolean =
        CapabilityCheck.isRestrictedSession(request) || {
            val actions =
                if (action == "read") Seq("read")
                else if (resource == "tap") ai.datris.auth.CallerVisibility.TapWriteActions :+ "read"
                else Seq(action, "read")
            actions.exists(a => CapabilityCheck.keyScopeApplies(request, resource, a))
        }

    /** The version routes' refusal for one item, reading only for a caller
      * it can refuse ([[versionCheckNeeded]]): `readLive` gives the live
      * item's (catalog, owner); for an API key whose item is gone, the latest
      * stored version (`readVersions`) that parses decides (Decision: ruling
      * 6), and a key with nothing that establishes scope gets
      * `sessionAnswer` (the route's answer for a name that never existed).
      * A restricted session never reads the versions. */
    def versionScopeRefusalWith(
        request: HttpServletRequest,
        resource: String,
        action: String,
        readLive: () => Option[(String, String)],
        readVersions: () => Seq[EntityVersion],
        sessionAnswer: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] =
        if (!versionCheckNeeded(request, resource, action)) None
        else {
            val session = CapabilityCheck.isRestrictedSession(request)
            val live = readLive()
            val scope =
                if (live.isDefined || session) live
                else latestScope(resource, readVersions())
            scope match {
                case None if !session => Some(sessionAnswer)
                case _ => versionRefusal(request, resource, action, scope, sessionAnswer)
            }
        }

    /** (catalog, owner) of the newest version among `versions` whose config
      * parses; None when none does. */
    private[api] def latestScope(resource: String, versions: Seq[EntityVersion]): Option[(String, String)] = {
        val gson = new Gson
        versions.filter(v => v != null && v.config != null).sortBy(-_.version).view.flatMap { v =>
            try
                if (resource == "tap") Option(gson.fromJson(v.config, classOf[TapConfig])).map(t => (t.catalog, t.createdByKeyLabel))
                else Option(gson.fromJson(v.config, classOf[PipelineConfig])).map(p => (p.catalog, p.createdByKeyLabel))
            catch { case _: Exception => None }
        }.headOption
    }

    /** Tap version restore, API keys: the tap as it would be restored (the
      * snapshot's catalog; the live owner, or the snapshot's when the tap was
      * deleted) is written under the stored-tap write rule
      * ([[TapAPIController.storedTapWriteRefusal]]), so a restore cannot move
      * a tap out of the key's scope. None for sessions and no identity. */
    def tapRestoreRefusal(request: HttpServletRequest, snap: TapConfig, live: TapConfig): Option[ResponseEntity[String]] =
        if (snap == null) None
        else TapAPIController.storedTapWriteRefusal(request, snap.catalog, if (live != null) live.createdByKeyLabel else snap.createdByKeyLabel)

    /** The config a tap version restore saves (decisions D2 and D3): with a
      * live tap, the snapshot's definition under the live `name`, `catalog`,
      * `createdAt`, owner (`createdByKeyLabel`), `enabled` state, run state
      * (every `lastRun*` field, `lastRunTrigger`, `lastRunRetrySafe`,
      * `retryCount`) and test state (every `lastTestRun*` field), so a
      * restore brings back the definition and never moves the tap, turns it
      * on or off, or rewrites its run, retry and test state. For a deleted
      * tap (`live` null, D4), the snapshot under the requested name; the save
      * path then creates it as `POST /tap` creates a tap. `version`,
      * `updatedAt` and `inferredDependsOn` are left to the save path. */
    def tapRestoreCandidate(name: String, snap: TapConfig, live: TapConfig): TapConfig =
        if (live == null) snap.copy(name = name)
        else
            snap.copy(
                name = name,
                catalog = live.catalog,
                createdAt = live.createdAt,
                createdByKeyLabel = live.createdByKeyLabel,
                enabled = live.enabled,
                lastRunStatus = live.lastRunStatus,
                lastRunTime = live.lastRunTime,
                lastRunRecordCount = live.lastRunRecordCount,
                lastRunError = live.lastRunError,
                lastRunDataType = live.lastRunDataType,
                lastRunColumns = live.lastRunColumns,
                lastRunTrigger = live.lastRunTrigger,
                lastRunRetrySafe = live.lastRunRetrySafe,
                retryCount = live.retryCount,
                lastTestRunStatus = live.lastTestRunStatus,
                lastTestRunTime = live.lastTestRunTime,
                lastTestRunRecordCount = live.lastTestRunRecordCount,
                lastTestRunError = live.lastTestRunError,
                lastTestRunDataType = live.lastTestRunDataType,
                lastTestRunColumns = live.lastTestRunColumns,
                lastTestRunScriptId = live.lastTestRunScriptId
            )

    /** Tap version restore, every caller, decided from ONE live read
      * (`stored`, null when the tap was deleted), in this order: a
      * restricted session whose live tap is missing or outside its grants
      * gets `hidden` (the version 404) ([[hiddenFromRestricted]]); an API key
      * writes the stored tap only under the stored-tap write rule
      * ([[TapAPIController.storedTapWriteRefusal]]) and the tap as it would
      * be restored only under the same rule ([[tapRestoreRefusal]]); for a
      * deleted tap, the right to create it as re-created
      * ([[TapAPIController.recreateRefusal]]); the restricted-session catalog
      * rules ([[TapAPIController.catalogForSave]]);
      * then the target pipeline must be one the caller may load into
      * ([[TapAPIController.targetPipelineRefusal]], `readPipeline` called only
      * for a restricted session or a load-checked key with a target). Right
      * is the config to save. Reads nothing itself besides `readPipeline`. */
    def tapRestoreGuardDecision(
        request: HttpServletRequest,
        candidate: TapConfig,
        stored: TapConfig,
        readPipeline: String => PipelineConfig,
        hidden: => ResponseEntity[String]
    ): Either[ResponseEntity[String], TapConfig] = {
        if (
            CapabilityCheck.isRestrictedSession(request) &&
            hiddenFromRestricted(request, "tap", "update", Option(stored).map(t => (t.catalog, t.createdByKeyLabel)))
        )
            return Left(hidden)
        if (stored != null) TapAPIController.storedTapWriteRefusal(request, stored.catalog, stored.createdByKeyLabel).foreach(r => return Left(r))
        tapRestoreRefusal(request, candidate, stored).foreach(r => return Left(r))
        // A deleted tap is re-created: the caller must be allowed to create
        // it, as POST /tap requires for a new tap (owner decision).
        if (stored == null) TapAPIController.recreateRefusal(request, candidate).foreach(r => return Left(r))
        TapAPIController.catalogForSave(request, candidate, stored) match {
            case Left(refused) => Left(refused)
            case Right(c) => TapAPIController.targetPipelineRefusal(request, c, readPipeline).toLeft(c)
        }
    }

    /** The stored tap fields [[tapRestoreGuardDecision]] and the restore's
      * kept live state depend on: both absent, or the same `catalog`, owner
      * (`createdByKeyLabel`) and `enabled` state. */
    def sameTapGuardState(a: TapConfig, b: TapConfig): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.catalog == b.catalog && a.createdByKeyLabel == b.createdByKeyLabel && a.enabled == b.enabled

    /** Tap version restore, under `CatalogOps.withCatalogLock` just before
      * the write: `fresh` is the tap of that name read under the lock. A
      * restore [[tapRestoreGuardDecision]] refuses on `fresh` gets that
      * refusal; one allowed on `fresh` whose stored tap changed since the
      * decision (`decided`: appeared, disappeared, or moved catalog, changed
      * owner or enabled state) gets [[TapAPIController.restoreConflict]]
      * (409). None: write. */
    def tapRestoreRecheck(
        request: HttpServletRequest,
        candidate: TapConfig,
        decided: TapConfig,
        fresh: TapConfig,
        readPipeline: String => PipelineConfig,
        hidden: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] =
        tapRestoreGuardDecision(request, candidate, fresh, readPipeline, hidden) match {
            case Left(refused) => Some(refused)
            case Right(_) if !sameTapGuardState(decided, fresh) => Some(TapAPIController.restoreConflict(candidate.name))
            case Right(_) => None
        }

    /** The config a tap version restore writes, under the lock, once
      * [[tapRestoreRecheck]] has passed: the checked config with the live
      * fields of [[tapRestoreCandidate]] taken from `fresh` (the tap read
      * under the lock), so a run, retry or test stamp written since the
      * decision is never rolled back. `fresh` null (a deleted tap re-created):
      * the checked config itself. */
    def tapRestoreWriteConfig(checked: TapConfig, fresh: TapConfig): TapConfig =
        if (fresh == null) checked else tapRestoreCandidate(checked.name, checked, fresh)
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
        // Hidden from a restricted session: the same empty list as a name
        // with no history; a key outside its scope: 403.
        versionScopeRefusal(request, "tap", name, "read", ok("[]"))
            .getOrElse(listResponse(DatrisEnvironment.current.tapVersionTableName, name))
    }

    @GetMapping(path = Array("/tap/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        versionScopeRefusal(request, "tap", name, "read", notFound("tap", name, version)).foreach(return _)
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
        versionScopeRefusal(request, "tap", name, "read", notFound("tap", name, version)).foreach(return _)
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
        restoreTapVersionWith(
            name,
            version,
            request,
            () => TapConfigIO.read(env.tapTableName, name),
            () => EntityVersionIO.listVersions(env.tapVersionTableName, name),
            () => EntityVersionIO.get(env.tapVersionTableName, name, version)
        )
    }

    /** [[restoreTapVersion]] after key validation, with the reads given.
      * ONE live read (`readLive`) decides the route's scope answer, the save
      * guard and the candidate. The scope answer comes before the version is
      * read: a restricted session whose live tap is missing or outside its
      * grants gets the version 404, and an API key outside the live tap's
      * scope (or, for a deleted tap, the newest stored version's, from
      * `readVersions`, read only then) gets the shared 403, so the answer and
      * the reads do not depend on whether the version exists or parses. */
    private[api] def restoreTapVersionWith(
        name: String,
        version: Int,
        request: HttpServletRequest,
        readLive: () => TapConfig,
        readVersions: () => Seq[EntityVersion],
        readVersion: () => Option[EntityVersion]
    ): ResponseEntity[String] = {
        val live = readLive()
        EntityVersionAPIController
            .versionScopeRefusalWith(
                request,
                "tap",
                "update",
                () => Option(live).map(t => (t.catalog, t.createdByKeyLabel)),
                readVersions,
                notFound("tap", name, version)
            )
            .foreach(return _)
        readVersion() match {
            case None => notFound("tap", name, version)
            case Some(snapshot) =>
                val snap = gson.fromJson(snapshot.config, classOf[TapConfig])
                restoreTapSnapshot(name, version, snap, live, request)
        }
    }

    /** Restore `snap` (version `version` of `name`) over `live` (the one
      * stored-tap read, null when deleted): the candidate
      * ([[EntityVersionAPIController.tapRestoreCandidate]]) is saved through
      * the `POST /tap` path ([[TapAPIController.restoreTapInternal]]), so it
      * is checked like a save of that version and nothing is written on a
      * refusal. A refusal is returned as is; the 200 body is the saved tap. */
    private[api] def restoreTapSnapshot(
        name: String,
        version: Int,
        snap: TapConfig,
        live: TapConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        val candidate = EntityVersionAPIController.tapRestoreCandidate(name, snap, live)
        new TapAPIController().restoreTapInternal(candidate, live, version, notFound("tap", name, version), request) match {
            case Left(refused) =>
                logger.warn("Restore of tap " + LogSafe.text(name) + " version " + version + " rejected: HTTP " + refused.getStatusCode.value)
                refused
            case Right(saved) => ok(gson.toJson(saved))
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
        // Hidden from a restricted session: the same empty list as a name
        // with no history; a key outside its scope: 403.
        versionScopeRefusal(request, "pipeline", name, "read", ok("[]"))
            .getOrElse(listResponse(DatrisEnvironment.current.pipelineVersionTableName, name))
    }

    @GetMapping(path = Array("/pipeline/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineVersion(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam version: Int,
        request: HttpServletRequest
    ): ResponseEntity[String] = handle {
        APIKeyValidator.validate(apiKey)
        versionScopeRefusal(request, "pipeline", name, "read", notFound("pipeline", name, version)).foreach(return _)
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
        versionScopeRefusal(request, "pipeline", name, "read", notFound("pipeline", name, version)).foreach(return _)
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
        // A restricted session hidden from the live pipeline: the version
        // 404; an API key outside the live pipeline's scope: 403, before the
        // version is read. A deleted pipeline is decided on the snapshot by
        // the save guard (restoreGuardDecision).
        EntityVersionAPIController
            .versionRefusal(request, "pipeline", "update", Option(live).map(p => (p.catalog, p.createdByKeyLabel)), notFound("pipeline", name, version))
            .foreach(return _)
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
                    logger.warn("Restore of pipeline " + LogSafe.text(name) + " version " + version + " rejected: HTTP " + refused.getStatusCode.value)
                    refused
                case Right(saved) => ok(gson.toJson(saved))
            }
        catch {
            case v: ValidationException =>
                logger.warn("Restore of pipeline " + LogSafe.text(name) + " version " + version + " rejected: " + LogSafe.text(v.getMessage))
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

    /** [[EntityVersionAPIController.versionScopeRefusalWith]] with the live
      * item and its stored versions read from Mongo. */
    private def versionScopeRefusal(
        request: HttpServletRequest,
        resource: String,
        name: String,
        action: String,
        sessionAnswer: => ResponseEntity[String]
    ): Option[ResponseEntity[String]] = {
        val env = DatrisEnvironment.current
        EntityVersionAPIController.versionScopeRefusalWith(
            request,
            resource,
            action,
            () =>
                if (resource == "tap") Option(TapConfigIO.read(env.tapTableName, name)).map(t => (t.catalog, t.createdByKeyLabel))
                else Option(PipelineConfigIO.read(env.pipelineTableName, name)).map(p => (p.catalog, p.createdByKeyLabel)),
            () => EntityVersionIO.listVersions(if (resource == "tap") env.tapVersionTableName else env.pipelineVersionTableName, name),
            sessionAnswer
        )
    }

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
