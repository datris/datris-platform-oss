package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, SecretNames, TapCronGate, TapCronValidation}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** Story: tap version restore is checked like a save of that version
  * (plans/stories/tap-version-restore-validates.md). Mirrors
  * PipelineVersionRestoreSpec.
  *
  * Pure part: the restore candidate (decisions D2, D3, D4), the guard taken
  * from ONE live read (`tapRestoreGuardDecision`), and its re-check under the
  * catalog lock (`tapRestoreRecheck`, `sameTapGuardState`).
  *
  * Controller part: `EntityVersionAPIController.restoreTapSnapshot` and
  * `restoreTapVersionWith` are called directly, no Spring. Every snapshot sent
  * through them is refused before any store is touched (the guard, the secret
  * rule, the kind and endpoint rules all come before the first store read in
  * the save path), and the environment here has no stores (no Mongo config, no
  * object store), so an answer proves nothing was read or written. Checks that
  * only run after the save path has read Mongo or the script store (document
  * target, script existence, dependency rules, cron parse, test-before-cron
  * gate, owner stamp of a re-created tap) are pinned here on the pure seams
  * the core calls, with the candidate as the config, and end to end in the
  * story's manual Verify (E2E).
  *
  * Expected new symbols (story names; the extra `hidden` argument and
  * `restoreTapVersionWith` mirror the pipeline restore already on the branch):
  * {{{
  * object EntityVersionAPIController {
  *   def tapRestoreCandidate(name: String, snap: TapConfig, live: TapConfig): TapConfig
  *   def tapRestoreGuardDecision(request: HttpServletRequest, candidate: TapConfig, stored: TapConfig,
  *                               readPipeline: String => PipelineConfig,
  *                               hidden: => ResponseEntity[String]): Either[ResponseEntity[String], TapConfig]
  *   def sameTapGuardState(a: TapConfig, b: TapConfig): Boolean
  *   def tapRestoreRecheck(request: HttpServletRequest, candidate: TapConfig, decided: TapConfig, fresh: TapConfig,
  *                         readPipeline: String => PipelineConfig,
  *                         hidden: => ResponseEntity[String]): Option[ResponseEntity[String]]
  * }
  * object TapAPIController {
  *   case class RestoreOf(live: TapConfig, version: Int)
  *   def restoreConflict(name: String): ResponseEntity[String]
  * }
  * class EntityVersionAPIController {
  *   private[api] def restoreTapVersionWith(name: String, version: Int, request: HttpServletRequest,
  *                                          readLive: () => TapConfig,
  *                                          readVersions: () => Seq[EntityVersion],
  *                                          readVersion: () => Option[EntityVersion]): ResponseEntity[String]
  *   private[api] def restoreTapSnapshot(name: String, version: Int, snap: TapConfig, live: TapConfig,
  *                                       request: HttpServletRequest): ResponseEntity[String]
  * }
  * class TapAPIController {
  *   private def saveTapCore(...)            // source pin only
  *   private[api] def restoreTapInternal(...) // source pin only
  * }
  * }}}
  *
  * API keys: the guard keeps Part A's one rule (scoped-key-read-routes): the
  * tap as it would be restored is written only under
  * `TapAPIController.storedTapWriteRefusal`, and its target pipeline under
  * `TapAPIController.targetPipelineRefusal`. Tests named "Part A rule: ..."
  * pin that reading where the story's text (written before Part A) says keys
  * proceed and read no pipeline. */
class TapVersionRestoreSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ------------------------------------------------------------ environment

    private val env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false
    )

    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        TenantContext.set(env)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    // --------------------------------------------------------------- requests

    private def req(rk: ResolvedKey): HttpServletRequest =
        ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap/version/restore")._1

    private def sessionKey(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private def bobMine = req(sessionKey(grants("mine" -> "edit")))
    private def bobMineAndView = req(sessionKey(grants("mine" -> "edit", "viewonly" -> "view")))

    private val mineCaps = Seq("tap:read:catalog=mine", "tap:create:catalog=mine", "tap:update:catalog=mine", "pipeline:read:catalog=mine")
    private def mineKey = req(ScopeDenialFixtures.key("mine-builder", mineCaps: _*))

    /** Unscoped tap:update, tap:read scoped to `mine`. */
    private val updCaps = Seq("tap:update", "tap:read:catalog=mine")
    private def updKey = req(ScopeDenialFixtures.key("upd", updCaps: _*))

    private def full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private def editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private def authOff = req(null)

    // --------------------------------------------------------------- fixtures

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def tap(name: String, catalog: String, owner: String = "someone", target: String = null): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner)

    private def pg(name: String, catalog: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner)

    /** The live tap: every field set, distinct from [[fullSnap]]. */
    private val fullLive: TapConfig = TapConfig(
        name = "t",
        description = "live description",
        scriptPath = "tap-scripts/live.py",
        targetPipeline = "live_p",
        packages = jl("live-pkg"),
        secretName = "live-secret",
        cronExpression = "0 0 * * * ?",
        enabled = false,
        tapType = "structured",
        lastRunStatus = "failure",
        lastRunTime = "2026-11-01 10:00:00",
        lastRunRecordCount = 7,
        lastRunError = "live error",
        lastRunDataType = "csv",
        lastRunColumns = jl("live_col"),
        lastTestRunStatus = "success",
        lastTestRunTime = "2026-10-30 09:00:00",
        lastTestRunRecordCount = 3,
        lastTestRunError = "live test error",
        lastTestRunDataType = "json",
        lastTestRunColumns = jl("live_test_col"),
        createdAt = "2026-01-01 00:00:00",
        updatedAt = "2026-11-01 10:00:00",
        catalog = "New",
        createdByKeyLabel = "live-owner",
        version = 5,
        lastRunTrigger = "manual",
        lastRunRetrySafe = false,
        retryCount = 2,
        scriptStorage = "minio",
        scriptRepoPath = "live/repo.py",
        scriptCommitSha = "live-sha",
        scriptKind = "python",
        endpointUrl = null,
        tags = jl("live-tag"),
        source = "live-source",
        lastTestRunScriptId = "live-script-id",
        dependsOn = jl("live_dep"),
        inferredDependsOn = jl("live_inferred")
    )

    /** A version of the same tap: every field set, different from [[fullLive]]. */
    private val fullSnap: TapConfig = TapConfig(
        name = "inner_other_name",
        description = "snapshot description",
        scriptPath = "tap-scripts/snap.py",
        targetPipeline = "snap_p",
        packages = jl("snap-pkg"),
        secretName = "snap-secret",
        cronExpression = "0 30 * * * ?",
        enabled = true,
        tapType = "document",
        lastRunStatus = "success",
        lastRunTime = "2025-05-05 05:05:05",
        lastRunRecordCount = 99,
        lastRunError = "snap error",
        lastRunDataType = "parquet",
        lastRunColumns = jl("snap_col"),
        lastTestRunStatus = "failure",
        lastTestRunTime = "2025-05-04 04:04:04",
        lastTestRunRecordCount = 42,
        lastTestRunError = "snap test error",
        lastTestRunDataType = "xml",
        lastTestRunColumns = jl("snap_test_col"),
        createdAt = "2025-01-01 00:00:00",
        updatedAt = "2025-05-05 05:05:05",
        catalog = "Old",
        createdByKeyLabel = "other",
        version = 2,
        lastRunTrigger = "cron",
        lastRunRetrySafe = true,
        retryCount = 0,
        scriptStorage = "github",
        scriptRepoPath = "snap/repo.py",
        scriptCommitSha = "snap-sha",
        scriptKind = "http",
        endpointUrl = "https://snap.example.com/tap",
        tags = jl("snap-tag"),
        source = "snap-source",
        lastTestRunScriptId = "snap-script-id",
        dependsOn = jl("snap_dep"),
        inferredDependsOn = jl("snap_inferred")
    )

    /** The fields D2 and D3 take from the live tap, applied to `snap`. */
    private def withLiveFields(snap: TapConfig, name: String, live: TapConfig): TapConfig =
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

    /** `c` with the server-maintained fields the save core sets itself
      * (`version` by the versioned write, `updatedAt` = now,
      * `inferredDependsOn` re-derived) put back to `snap`'s, so equality
      * pins every other field. */
    private def definitionOf(c: TapConfig, snap: TapConfig): TapConfig =
        c.copy(version = snap.version, updatedAt = snap.updatedAt, inferredDependsOn = snap.inferredDependsOn)

    private def candidate(name: String, snap: TapConfig, live: TapConfig): TapConfig =
        EntityVersionAPIController.tapRestoreCandidate(name, snap, live)

    /** The version 404 a hidden tap's history answers (`notFound("tap", name, version)`). */
    private def versionNotFound(name: String, version: Int = 1): ResponseEntity[String] =
        ResponseEntity.status(404).body[String]("{\"error\": \"No version " + version + " for tap '" + name + "'\"}")

    /** A pipeline reader that counts its calls. */
    private class Pipelines(configs: PipelineConfig*) {
        var calls = 0
        val fn: String => PipelineConfig = n => { calls += 1; configs.find(_.name == n).orNull }
    }

    private def sameResponse(a: ResponseEntity[String], b: ResponseEntity[String]): Unit =
        assert(a.getStatusCode == b.getStatusCode && a.getBody == b.getBody, s"${a.getStatusCode} ${a.getBody} vs ${b.getStatusCode} ${b.getBody}")

    private def refused(d: Either[ResponseEntity[String], TapConfig]): ResponseEntity[String] = {
        assert(d.isLeft, s"expected a refusal, got $d")
        d.left.get
    }

    private def decide(r: HttpServletRequest, c: TapConfig, stored: TapConfig, p: Pipelines = new Pipelines()) =
        EntityVersionAPIController.tapRestoreGuardDecision(r, c, stored, p.fn, versionNotFound(c.name))

    private def recheck(r: HttpServletRequest, c: TapConfig, decided: TapConfig, fresh: TapConfig, p: Pipelines = new Pipelines()) =
        EntityVersionAPIController.tapRestoreRecheck(r, c, decided, fresh, p.fn, versionNotFound(c.name))

    private def errorOf(body: String): String = JsonParser.parseString(body).getAsJsonObject.get("error").getAsString

    // ------------------------------------------------------------- candidate

    // Acceptance 1.
    test("the candidate keeps the live name, catalog, owner, createdAt and enabled state") {
        val snap = tap("inner_other_name", "Old", owner = "other").copy(enabled = true, createdAt = "2025-01-01")
        val live = tap("t", "New", owner = "live-owner").copy(enabled = false, createdAt = "2026-01-01")
        val c = candidate("t", snap, live)
        assert(c.name == "t")
        assert(c.catalog == "New", c.catalog)
        assert(c.createdByKeyLabel == "live-owner")
        assert(c.createdAt == "2026-01-01")
        assert(!c.enabled, "enabled comes from the live tap")
    }

    // D2, catalog part (confirmed by the owner).
    test("D2 catalog: a restore keeps the tap in its live catalog, never the snapshot's") {
        // Snapshot from before a rename or a move: the old catalog stays history.
        assert(candidate("t", tap("t", "Old"), tap("t", "New")).catalog == "New")
        // A live tap with no catalog stays without one; a snapshot with none
        // does not take the tap out of its live catalog.
        assert(candidate("t", tap("t", "Old"), tap("t", null)).catalog == null)
        assert(candidate("t", tap("t", null), tap("t", "New")).catalog == "New")
        // Only the catalog: the rest of the definition is still the snapshot's.
        val c = candidate("t", fullSnap, fullLive)
        assert(c.catalog == fullLive.catalog && c.description == fullSnap.description, s"$c")
    }

    // D3 (confirmed by the owner).
    test("D3 enabled: a restore never turns a tap on or off (the live enabled state, both ways)") {
        for (snapEnabled <- Seq(true, false); liveEnabled <- Seq(true, false)) {
            val c = candidate("t", fullSnap.copy(enabled = snapEnabled), fullLive.copy(enabled = liveEnabled))
            assert(c.enabled == liveEnabled, s"snapshot $snapEnabled, live $liveEnabled -> ${c.enabled}")
        }
        // The cron is the snapshot's (it passes the gate as on save), the switch is the live tap's.
        val c = candidate("t", fullSnap.copy(enabled = true, cronExpression = "0 30 * * * ?"), fullLive.copy(enabled = false, cronExpression = null))
        assert(c.cronExpression == "0 30 * * * ?" && !c.enabled, s"$c")
    }

    // Acceptance 2 (D2: run, retry and test state, field by field).
    test("the candidate keeps the live run, retry and test state") {
        val c = candidate("t", fullSnap, fullLive)
        val fields: Seq[(String, Any, Any, Any)] = Seq(
            ("lastRunStatus", c.lastRunStatus, fullLive.lastRunStatus, fullSnap.lastRunStatus),
            ("lastRunTime", c.lastRunTime, fullLive.lastRunTime, fullSnap.lastRunTime),
            ("lastRunRecordCount", c.lastRunRecordCount, fullLive.lastRunRecordCount, fullSnap.lastRunRecordCount),
            ("lastRunError", c.lastRunError, fullLive.lastRunError, fullSnap.lastRunError),
            ("lastRunDataType", c.lastRunDataType, fullLive.lastRunDataType, fullSnap.lastRunDataType),
            ("lastRunColumns", c.lastRunColumns, fullLive.lastRunColumns, fullSnap.lastRunColumns),
            ("lastRunTrigger", c.lastRunTrigger, fullLive.lastRunTrigger, fullSnap.lastRunTrigger),
            ("lastRunRetrySafe", c.lastRunRetrySafe, fullLive.lastRunRetrySafe, fullSnap.lastRunRetrySafe),
            ("retryCount", c.retryCount, fullLive.retryCount, fullSnap.retryCount),
            ("lastTestRunStatus", c.lastTestRunStatus, fullLive.lastTestRunStatus, fullSnap.lastTestRunStatus),
            ("lastTestRunTime", c.lastTestRunTime, fullLive.lastTestRunTime, fullSnap.lastTestRunTime),
            ("lastTestRunRecordCount", c.lastTestRunRecordCount, fullLive.lastTestRunRecordCount, fullSnap.lastTestRunRecordCount),
            ("lastTestRunError", c.lastTestRunError, fullLive.lastTestRunError, fullSnap.lastTestRunError),
            ("lastTestRunDataType", c.lastTestRunDataType, fullLive.lastTestRunDataType, fullSnap.lastTestRunDataType),
            ("lastTestRunColumns", c.lastTestRunColumns, fullLive.lastTestRunColumns, fullSnap.lastTestRunColumns),
            ("lastTestRunScriptId", c.lastTestRunScriptId, fullLive.lastTestRunScriptId, fullSnap.lastTestRunScriptId)
        )
        fields.foreach { case (field, got, live, snap) =>
            assert(live != snap, s"fixture: $field must differ between live and snapshot")
            assert(got == live, s"$field: got $got, live $live, snapshot $snap")
        }
        // Nulls on the live tap win too (a never-run tap stays never-run).
        val neverRun = TapConfig(name = "t", description = "d", targetPipeline = null, catalog = "New")
        val n = candidate("t", fullSnap, neverRun)
        assert(n.lastRunStatus == null && n.lastRunTime == null && n.lastRunTrigger == null && !n.lastRunRetrySafe && n.retryCount == 0, s"$n")
        assert(n.lastTestRunStatus == null && n.lastTestRunScriptId == null && n.lastTestRunTime == null, s"$n")
    }

    // Acceptance 3.
    test("the candidate takes script reference, kind, endpoint, packages, secret, cron, target, tags, source and dependsOn from the snapshot, nulls included") {
        def definition(t: TapConfig): Seq[(String, Any)] = Seq(
            "scriptPath" -> t.scriptPath,
            "scriptStorage" -> t.scriptStorage,
            "scriptRepoPath" -> t.scriptRepoPath,
            "scriptCommitSha" -> t.scriptCommitSha,
            "scriptKind" -> t.scriptKind,
            "endpointUrl" -> t.endpointUrl,
            "packages" -> t.packages,
            "secretName" -> t.secretName,
            "cronExpression" -> t.cronExpression,
            "targetPipeline" -> t.targetPipeline,
            "tags" -> t.tags,
            "source" -> t.source,
            "dependsOn" -> t.dependsOn
        )
        // Values: the snapshot's, never the live tap's.
        val c = candidate("t", fullSnap, fullLive)
        definition(c).zip(definition(fullSnap)).zip(definition(fullLive)).foreach { case (((field, got), (_, snap)), (_, live)) =>
            assert(snap != live, s"fixture: $field must differ")
            assert(got == snap, s"$field: got $got, snapshot $snap, live $live")
        }
        // Nulls: a snapshot that has none does not inherit the live tap's
        // (a restore is not a partial save; nothing is "omitted").
        val empty = TapConfig(name = "inner", description = "d", targetPipeline = null)
        val n = candidate("t", empty, fullLive)
        definition(n).foreach { case (field, got) => assert(got == null, s"$field: the live value leaked: $got") }
    }

    test("the candidate is exactly the snapshot with the D2 / D3 live fields, nothing else") {
        val c = candidate("t", fullSnap, fullLive)
        assert(definitionOf(c, fullSnap) == withLiveFields(fullSnap, "t", fullLive), s"$c")
        // Description and tapType are definition too.
        assert(c.description == fullSnap.description && c.tapType == fullSnap.tapType)
    }

    // Acceptance 4.
    test("the candidate with no live tap is the snapshot under the requested name") {
        val c = candidate("t", fullSnap, null)
        assert(c == fullSnap.copy(name = "t"), s"$c")
    }

    // D4: a tap re-created by restore is saved as POST /tap would save it.
    test("D4 enabled: a tap re-created by restore takes the snapshot's enabled state, as POST /tap takes the body's") {
        for (enabled <- Seq(true, false))
            assert(candidate("t", fullSnap.copy(enabled = enabled), null).enabled == enabled)
    }

    test("D4 cron: a re-created tap's cron is gated as a new tap's, whatever test stamp the snapshot carries") {
        // The snapshot carries a green stamp for its own endpoint; a save of a
        // new tap ignores body test fields, so the gate sees a never-tested tap.
        val snap = TapConfig(
            name = "t",
            description = "d",
            targetPipeline = null,
            scriptKind = "http",
            endpointUrl = "https://a.example.com/tap",
            cronExpression = "0 0 * * * ?",
            lastTestRunStatus = "success",
            lastTestRunScriptId = "http:https://a.example.com/tap"
        )
        val c = candidate("t", snap, null)
        val restoreGate = TapCronGate.check(null, c)
        val saveGate = TapCronGate.check(null, snap)
        assert(restoreGate.isDefined, "a re-created scheduled tap must be refused by the gate")
        assert(restoreGate == saveGate)
        assert(restoreGate.get.contains("never passed a test run"), restoreGate.get)
        // Unscheduled: nothing to gate.
        assert(TapCronGate.check(null, candidate("t", snap.copy(cronExpression = null), null)).isEmpty)
    }

    // --------------------------------------------------------------- schedule

    test("schedule: restoring a version from before a pause leaves the tap paused") {
        val beforePause = fullSnap.copy(enabled = true, cronExpression = "0 0 * * * ?")
        val paused = fullLive.copy(enabled = false, cronExpression = "0 0 * * * ?")
        assert(!candidate("t", beforePause, paused).enabled)
    }

    test("schedule: restoring a version written while the tap was disabled leaves a running schedule running") {
        // The first version a catalog restore writes is always enabled = false.
        val catalogRestoreV1 = fullSnap.copy(enabled = false, cronExpression = "0 0 * * * ?")
        val running = fullLive.copy(enabled = true, cronExpression = "0 0 * * * ?")
        val c = candidate("t", catalogRestoreV1, running)
        assert(c.enabled && c.cronExpression == "0 0 * * * ?", s"$c")
    }

    test("schedule: the retry ladder state after a restore is the live tap's") {
        val snapFresh = fullSnap.copy(lastRunStatus = "success", lastRunTrigger = "cron", lastRunRetrySafe = true, retryCount = 0)
        // A manual failure stays manual (never auto-retried).
        val manual = fullLive.copy(lastRunStatus = "failure", lastRunTrigger = "manual", lastRunRetrySafe = true, retryCount = 0)
        // An exhausted ladder stays exhausted.
        val exhausted = fullLive.copy(lastRunStatus = "failure", lastRunTrigger = "cron", lastRunRetrySafe = true, retryCount = 99)
        // A run that already fed its pipeline stays not retry-safe.
        val fed = fullLive.copy(lastRunStatus = "failure", lastRunTrigger = "cron", lastRunRetrySafe = false, retryCount = 0)
        for (live <- Seq(manual, exhausted, fed)) {
            val c = candidate("t", snapFresh, live)
            assert(
                (c.lastRunStatus, c.lastRunTime, c.lastRunTrigger, c.lastRunRetrySafe, c.retryCount) ==
                    (live.lastRunStatus, live.lastRunTime, live.lastRunTrigger, live.lastRunRetrySafe, live.retryCount),
                s"$c"
            )
        }
    }

    test("schedule: a restored cron the gate refuses on save is refused on restore, with the save's text") {
        // Live HTTP tap tested green on endpoint A, unscheduled.
        val live = TapConfig(
            name = "t",
            description = "d",
            targetPipeline = null,
            catalog = "New",
            scriptKind = "http",
            endpointUrl = "https://a.example.com/tap",
            lastTestRunStatus = "success",
            lastTestRunScriptId = "http:https://a.example.com/tap"
        )
        // What POST /tap would check for the same body: the stored test fields win.
        def saveShape(body: TapConfig): TapConfig =
            body.copy(name = "t", catalog = live.catalog, lastTestRunStatus = live.lastTestRunStatus, lastTestRunScriptId = live.lastTestRunScriptId)
        val untested = live.copy(endpointUrl = "https://b.example.com/tap", cronExpression = "0 0 * * * ?")
        // The snapshot's own green stamp for B does not vouch for it: the live stamp is kept (D2).
        val selfStamped = untested.copy(lastTestRunStatus = "success", lastTestRunScriptId = "http:https://b.example.com/tap")
        for (snap <- Seq(untested, selfStamped)) {
            val r = TapCronGate.check(live, candidate("t", snap, live))
            assert(r.isDefined, s"must be refused: $snap")
            assert(r == TapCronGate.check(live, saveShape(snap)))
            assert(r.get.contains(TapCronGate.ScriptChangedReason), r.get)
        }
        // The tested endpoint with a cron: allowed on both.
        val tested = live.copy(cronExpression = "0 0 * * * ?")
        assert(TapCronGate.check(live, candidate("t", tested, live)).isEmpty)
        assert(TapCronGate.check(live, saveShape(tested)).isEmpty)
        // A cron that does not parse: the save's 400 text, under the live name.
        val bad = candidate("t", tested.copy(name = "inner", cronExpression = "not a cron"), live)
        assert(TapCronValidation.check(bad.name, bad.cronExpression) == TapCronValidation.check("t", "not a cron"))
        assert(TapCronValidation.check(bad.name, bad.cronExpression).isDefined)
    }

    // ------------------------------------------------------------ dependencies

    test("dependencies: a restored dependsOn is checked in the live catalog, with the save's 400 / 409") {
        val catalogNew = Seq(
            tap("a", "New"),
            tap("b", "New").copy(dependsOn = jl("t")),
            tap("t", "New")
        )
        val live = tap("t", "New").copy(dependsOn = jl("a"))
        def saveOf(body: TapConfig) = TapAPIController.dependencySaveProblem(body.copy(name = "t", catalog = "New"), catalogNew)
        // A dependency of the old catalog (the snapshot is from before a move).
        val oldCatalog = tap("t", "Old").copy(dependsOn = jl("old_a"))
        val r1 = TapAPIController.dependencySaveProblem(candidate("t", oldCatalog, live), catalogNew)
        assert(r1.exists(_.getStatusCode.value == 400), s"$r1")
        assert(r1.get.getBody.contains("old_a"), r1.get.getBody)
        sameResponse(r1.get, saveOf(oldCatalog).get)
        // A deleted dependency.
        val deleted = tap("t", "New").copy(dependsOn = jl("gone"))
        val r2 = TapAPIController.dependencySaveProblem(candidate("t", deleted, live), catalogNew)
        assert(r2.exists(_.getStatusCode.value == 400), s"$r2")
        sameResponse(r2.get, saveOf(deleted).get)
        // A cycle: b already depends on t.
        val cycle = tap("t", "New").copy(dependsOn = jl("b"))
        val r3 = TapAPIController.dependencySaveProblem(candidate("t", cycle, live), catalogNew)
        assert(r3.exists(_.getStatusCode.value == 409), s"$r3")
        assert(JsonParser.parseString(r3.get.getBody).getAsJsonObject.has("cycle"), r3.get.getBody)
        sameResponse(r3.get, saveOf(cycle).get)
        // A valid one passes on both.
        val ok = tap("t", "Old").copy(dependsOn = jl("a"))
        assert(TapAPIController.dependencySaveProblem(candidate("t", ok, live), catalogNew).isEmpty)
    }

    // --------------------------------------------------------- guard decision

    // Acceptance 5.
    test("a restricted session gets the version 404 when the live tap is missing or outside its grants") {
        val p = new Pipelines(pg("p", "mine"))
        sameResponse(refused(decide(bobMine, tap("gone", "mine", target = "p"), null, p)), versionNotFound("gone"))
        val hiddenLive = tap("mkt", "secret", target = "p")
        sameResponse(refused(decide(bobMine, candidate("mkt", tap("mkt", "mine", target = "p"), hiddenLive), hiddenLive, p)), versionNotFound("mkt"))
        assert(p.calls == 0, "no pipeline is read for a hidden history")
        // Inside its grants: proceeds.
        val mine = tap("t", "mine")
        assert(decide(bobMine, mine, mine).isRight)
    }

    // Acceptance 6 (D2 catalog).
    test("a restricted session restoring a version from a catalog outside its grants proceeds in the live catalog") {
        val live = tap("t", "mine")
        val c = candidate("t", tap("t", "Old"), live)
        val d = decide(bobMine, c, live)
        assert(d.isRight, s"$d")
        assert(d.right.get.catalog == "mine", d.right.get.catalog)
    }

    // Acceptance 7.
    test("a restricted session whose restored target pipeline it may not run gets the pipeline refusal") {
        val p = new Pipelines(pg("view_p", "viewonly"), pg("hidden_p", "secret"), pg("mine_p", "mine"))
        val live = tap("t", "mine")
        for (target <- Seq("view_p", "hidden_p")) {
            val c = candidate("t", tap("t", "mine", target = target), live)
            val r = refused(decide(bobMineAndView, c, live, p))
            val expected = TapAPIController.targetPipelineRefusal(bobMineAndView, c, p.fn)
            assert(expected.isDefined, target)
            sameResponse(r, expected.get)
        }
        assert(refused(decide(bobMineAndView, candidate("t", tap("t", "mine", target = "view_p"), live), live, p)).getStatusCode.value == 403)
        assert(refused(decide(bobMineAndView, candidate("t", tap("t", "mine", target = "hidden_p"), live), live, p)).getStatusCode.value == 404)
        assert(decide(bobMineAndView, candidate("t", tap("t", "mine", target = "mine_p"), live), live, p).isRight)
        assert(p.calls > 0, "the injected reader decides")
    }

    // Acceptance 8, as Part A built it: full keys, unrestricted sessions and
    // auth off read nothing; a scoped key is the next test.
    test("keys, unrestricted sessions and auth off proceed and read no pipeline") {
        val p = new Pipelines(pg("hr_p", "HR"))
        val live = tap("t", "secret", target = "hr_p")
        for (r <- Seq(full, editor, authOff); stored <- Seq(live, null)) {
            val c = candidate("t", tap("t", "Old", target = "hr_p"), stored)
            val d = decide(r, c, stored, p)
            assert(d.isRight, s"$d")
            assert(d.right.get == c)
        }
        assert(p.calls == 0)
    }

    test("Part A rule: a scoped key's restored target pipeline is held to the load rule (pipeline read in scope)") {
        // The story text says keys read no pipeline; Part A made every
        // load-checked key need the load right and pipeline read in scope on
        // the target. The restore keeps Part A's answer.
        val p = new Pipelines(pg("hr_p", "HR"), pg("mine_p", "mine"))
        val live = tap("t", "mine", owner = "mine-builder")
        val outside = candidate("t", tap("t", "mine", target = "hr_p"), live)
        val r = refused(decide(mineKey, outside, live, p))
        assert(r.getStatusCode.value == 403, r.getBody)
        sameResponse(r, TapAPIController.targetPipelineRefusal(mineKey, outside, p.fn).get)
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message("mine-builder", "pipeline", "read", mineCaps), Seq("HR"))
        assert(decide(mineKey, candidate("t", tap("t", "mine", target = "mine_p"), live), live, p).isRight)
        // A target not stored yet is left to the save, as on POST /tap.
        assert(decide(mineKey, candidate("t", tap("t", "mine", target = "not_yet"), live), live, p).isRight)
    }

    test("Part A rule: a scoped key outside the tap's scope gets the shared 403 on update; unscoped update with scoped read gets 403 on read") {
        val hrLive = tap("hr_feed", "HR")
        val c = candidate("hr_feed", tap("hr_feed", "mine"), hrLive)
        val k = refused(decide(mineKey, c, hrLive))
        assert(k.getStatusCode.value == 403)
        ScopeDenialWording.assertBody(k.getBody, ScopeDenialWording.message("mine-builder", "tap", "update", mineCaps), Seq("HR", "someone"))
        val u = refused(decide(updKey, c, hrLive))
        assert(u.getStatusCode.value == 403)
        ScopeDenialWording.assertBody(u.getBody, ScopeDenialWording.message("upd", "tap", "read", updCaps), Seq("HR", "someone"))
        // Same answers as the stored-tap write rule every tap write uses.
        sameResponse(k, TapAPIController.storedTapWriteRefusal(mineKey, "HR", "someone").get)
        sameResponse(u, TapAPIController.storedTapWriteRefusal(updKey, "HR", "someone").get)
        // In scope: proceeds.
        val mineLive = tap("m", "mine")
        assert(decide(mineKey, candidate("m", tap("m", "HR"), mineLive), mineLive).isRight)
        assert(decide(updKey, candidate("m", tap("m", "HR"), mineLive), mineLive).isRight)
    }

    test("Part A rule: a deleted tap is restored under the snapshot's scope for a key") {
        val outside = candidate("gone", tap("gone", "HR"), null)
        val k = refused(decide(mineKey, outside, null))
        ScopeDenialWording.assertBody(k.getBody, ScopeDenialWording.message("mine-builder", "tap", "update", mineCaps), Seq("HR", "someone"))
        sameResponse(k, EntityVersionAPIController.tapRestoreRefusal(mineKey, tap("gone", "HR"), null).get)
        assert(decide(mineKey, candidate("gone", tap("gone", "mine"), null), null).isRight)
    }

    test("D2 catalog, keys: a key in scope of the live tap restores a version from a catalog outside its scope into the live catalog") {
        // Part A refused this because the snapshot's catalog was written; with
        // D2 the tap as restored is in the live (in-scope) catalog, so the
        // stored-tap write rule holds on what is written.
        val live = tap("m", "mine", owner = "mine-builder")
        val d = decide(mineKey, candidate("m", tap("m", "HR"), live), live)
        assert(d.isRight, s"$d")
        assert(d.right.get.catalog == "mine")
    }

    // --------------------------------------------------------------- re-check

    test("sameTapGuardState: both absent, or same catalog, owner and enabled state") {
        val a = fullLive
        assert(EntityVersionAPIController.sameTapGuardState(null, null))
        assert(!EntityVersionAPIController.sameTapGuardState(a, null))
        assert(!EntityVersionAPIController.sameTapGuardState(null, a))
        assert(EntityVersionAPIController.sameTapGuardState(a, a.copy()))
        assert(!EntityVersionAPIController.sameTapGuardState(a, a.copy(catalog = "Other")))
        assert(!EntityVersionAPIController.sameTapGuardState(a, a.copy(createdByKeyLabel = "else")))
        assert(!EntityVersionAPIController.sameTapGuardState(a, a.copy(enabled = !a.enabled)))
        // Run status, test status and definition edits are not guard state.
        assert(EntityVersionAPIController.sameTapGuardState(a, a.copy(lastRunStatus = "success", retryCount = 0, lastTestRunStatus = "failure")))
        assert(EntityVersionAPIController.sameTapGuardState(a, a.copy(description = "x", cronExpression = null)))
    }

    test("restoreConflict: 409 'changed while it was being restored; nothing was restored'") {
        val c = TapAPIController.restoreConflict("t")
        assert(c.getStatusCode.value == 409)
        assert(errorOf(c.getBody) == "Tap 't' changed while it was being restored; nothing was restored. Try again.", c.getBody)
        val of = TapAPIController.RestoreOf(fullLive, 3)
        assert(of.live == fullLive && of.version == 3)
    }

    // Acceptance 9.
    test("tapRestoreRecheck: a refusal on the fresh tap wins") {
        val mine = tap("t", "mine", owner = "mine-builder")
        val movedAway = tap("t", "secret", owner = "mine-builder")
        // Restricted session: moved out of its grants, or deleted, after the decision.
        for (fresh <- Seq(movedAway, null)) {
            val s = recheck(bobMine, mine, mine, fresh)
            assert(s.isDefined)
            sameResponse(s.get, versionNotFound("t"))
        }
        // Scoped key: the fresh stored tap is out of scope -> the shared 403, not the 409.
        val k = recheck(mineKey, mine, mine, movedAway)
        assert(k.isDefined)
        assert(k.get.getStatusCode.value == 403, k.get.getBody)
        ScopeDenialWording.assertBody(k.get.getBody, ScopeDenialWording.message("mine-builder", "tap", "update", mineCaps), Seq("secret"))
        // The target pipeline moved out of reach under the lock.
        val target = tap("t", "mine", target = "p")
        val view = recheck(bobMineAndView, target, target, target, new Pipelines(pg("p", "viewonly")))
        assert(view.exists(_.getStatusCode.value == 403), s"$view")
    }

    // Acceptance 10.
    test("tapRestoreRecheck: appeared, disappeared, moved catalog, changed owner or changed enabled state is 409 'nothing was restored', for a full key too") {
        val stored = tap("t", "mine", owner = "mine-builder").copy(enabled = true)
        val conflict = TapAPIController.restoreConflict("t")
        val cases: Seq[(String, TapConfig, TapConfig)] = Seq(
            ("appeared", null, stored),
            ("disappeared", stored, null),
            ("moved catalog", stored, stored.copy(catalog = "other")),
            ("changed owner", stored, stored.copy(createdByKeyLabel = "someone-else")),
            ("changed enabled state", stored, stored.copy(enabled = false))
        )
        for ((what, decided, fresh) <- cases; r <- Seq(full, editor, authOff)) {
            val out = recheck(r, stored, decided, fresh)
            assert(out.isDefined, what)
            sameResponse(out.get, conflict)
            assert(out.get.getBody.contains("nothing was restored"), what)
        }
        // A scoped key whose fresh tap is still in scope: the same 409.
        for (fresh <- Seq(stored.copy(enabled = false), stored.copy(createdByKeyLabel = "mine-builder-2"))) {
            val k = recheck(mineKey, stored, stored, fresh)
            assert(k.isDefined)
            sameResponse(k.get, conflict)
        }
        // A restricted session whose fresh tap is still in its grants: the same 409.
        val s = recheck(bobMine, stored, stored, stored.copy(enabled = false))
        assert(s.isDefined)
        sameResponse(s.get, conflict)
    }

    // Acceptance 11.
    test("tapRestoreRecheck: an unchanged tap, or one whose run status alone changed, writes") {
        val stored = tap("t", "mine", owner = "mine-builder")
        val ran = stored.copy(lastRunStatus = "failure", lastRunTime = "2026-11-19 01:00:00", lastRunTrigger = "cron", retryCount = 1)
        for (r <- Seq(full, editor, authOff, mineKey, bobMine); fresh <- Seq(stored, ran))
            assert(recheck(r, stored, stored, fresh).isEmpty, s"$fresh")
        for (r <- Seq(full, editor, authOff))
            assert(recheck(r, tap("gone", "mine"), null, null).isEmpty)
    }

    // ------------------------------------------------------ controller level

    private val ctl = () => new EntityVersionAPIController()

    private def restore(name: String, snap: TapConfig, live: TapConfig, r: HttpServletRequest): ResponseEntity[String] =
        ctl().restoreTapSnapshot(name, 1, snap, live, r)

    private def assert400(out: ResponseEntity[String], expected: String): Unit = {
        assert(out.getStatusCode.value == 400, s"status ${out.getStatusCode}, body ${out.getBody}")
        assert(errorOf(out.getBody) == expected, out.getBody)
    }

    private def probe(kind: String = null) = TapConfig(name = "tvr_probe", description = "d", targetPipeline = null, scriptKind = kind)

    private val liveProbe = TapConfig(
        name = "tvr_probe",
        description = "live",
        targetPipeline = null,
        catalog = "TvrOne",
        createdByKeyLabel = "someone",
        scriptKind = "python",
        enabled = true
    )

    private val unknownKind = probe("bash")
    private val endpointOnScript = probe().copy(endpointUrl = "https://example.com/tap")
    private val httpNoEndpoint = probe("http")
    private val httpRelative = probe("http").copy(endpointUrl = "/relative/tap")
    private val httpFtp = probe("http").copy(endpointUrl = "ftp://example.com/tap")
    private val httpWithScript =
        probe("http").copy(endpointUrl = "https://example.com/tap", scriptPath = "tap-scripts/x.py", scriptStorage = "minio", packages = jl("requests"))
    private val managedSecret = probe().copy(secretName = "field-protection", cronExpression = "not a cron")

    /** Every snapshot POST /tap refuses before it reads a store. */
    private val refusedBeforeStores: Seq[(String, TapConfig)] = Seq(
        "unknown scriptKind" -> unknownKind,
        "endpointUrl on a script tap" -> endpointOnScript,
        "HTTP tap with no endpointUrl" -> httpNoEndpoint,
        "HTTP tap with a relative endpointUrl" -> httpRelative,
        "HTTP tap with a non-http scheme" -> httpFtp,
        "HTTP tap with script fields" -> httpWithScript,
        "server-managed secret" -> managedSecret
    )

    // Acceptance 12.
    test("a version with an unknown scriptKind is refused 400") {
        assert400(restore("tvr_probe", unknownKind, null, authOff), "Unknown scriptKind 'bash'. Valid values: python (default), http.")
        assert400(restore("tvr_probe", unknownKind, liveProbe, authOff), "Unknown scriptKind 'bash'. Valid values: python (default), http.")
    }

    // Acceptance 13.
    test("a version with endpointUrl on a script tap is refused 400") {
        assert400(restore("tvr_probe", endpointOnScript, liveProbe, authOff), "endpointUrl is only valid when scriptKind is 'http'.")
    }

    // Acceptance 14 (first half).
    test("an HTTP version with no endpointUrl is refused 400") {
        assert400(restore("tvr_probe", httpNoEndpoint, liveProbe, authOff), "endpointUrl is required for HTTP taps")
        assert400(restore("tvr_probe", httpRelative, liveProbe, authOff), "endpointUrl must be an absolute http:// or https:// URL")
        assert400(restore("tvr_probe", httpFtp, liveProbe, authOff), "endpointUrl scheme must be http or https (got: ftp)")
    }

    // Acceptance 14 (second half).
    test("an HTTP version carrying a script reference is refused 400, naming the fields") {
        assert400(
            restore("tvr_probe", httpWithScript, liveProbe, authOff),
            "HTTP taps run no code on the platform, so these field(s) do not apply: scriptPath, scriptStorage, packages. Remove them, or set scriptKind to python."
        )
    }

    // Acceptance 15.
    test("a version naming a server-managed secret is refused 400 before its cron is looked at") {
        for (live <- Seq(liveProbe, null))
            assert400(restore("tvr_probe", managedSecret, live, authOff), SecretNames.serverManagedMessage("field-protection"))
    }

    // Acceptance 16.
    test("the refusal is the body saveTapInternal gives for the same config") {
        for ((what, snap) <- refusedBeforeStores; r <- Seq(authOff, full, editor)) {
            // A deleted tap: the snapshot is saved as POST /tap would save it.
            val restored = restore("tvr_probe", snap, null, r)
            val saved = new TapAPIController().saveTapInternal(snap, null, r)
            assert(saved.isLeft, s"$what: POST /tap must refuse it")
            assert(saved.left.get.getStatusCode.value == 400, s"$what: ${saved.left.get.getBody}")
            sameResponse(restored, saved.left.get)
            // POST /tap itself gives the same.
            sameResponse(restored, new TapAPIController().createOrUpdateTap(null, null, snap, r))
            // A live tap: the candidate (snapshot + live D2 / D3 fields) is what is saved.
            val overLive = restore("tvr_probe", snap, liveProbe, r)
            val savedCandidate = new TapAPIController().saveTapInternal(candidate("tvr_probe", snap, liveProbe), null, r)
            assert(savedCandidate.isLeft, what)
            sameResponse(overLive, savedCandidate.left.get)
            sameResponse(overLive, restored)
        }
    }

    // Acceptance 17.
    test("validation applies with no key and with a full key") {
        for ((what, snap) <- refusedBeforeStores) {
            val noKey = restore("tvr_probe", snap, liveProbe, authOff)
            val fullKey = restore("tvr_probe", snap, liveProbe, full)
            val session = restore("tvr_probe", snap, liveProbe, editor)
            assert(noKey.getStatusCode.value == 400, s"$what: ${noKey.getBody}")
            sameResponse(fullKey, noKey)
            sameResponse(session, noKey)
        }
    }

    test("restoreTapSnapshot: scoped keys and restricted sessions get Part A's answers through the save path") {
        val hrLive = liveProbe.copy(catalog = "HR")
        // A scoped key outside the live tap's scope: the shared 403 on update.
        val k = restore("tvr_probe", unknownKind, hrLive, mineKey)
        assert(k.getStatusCode.value == 403, k.getBody)
        ScopeDenialWording.assertBody(k.getBody, ScopeDenialWording.message("mine-builder", "tap", "update", mineCaps), Seq("HR", "someone"))
        // Unscoped update with scoped read: 403 on read.
        val u = restore("tvr_probe", unknownKind, hrLive, updKey)
        ScopeDenialWording.assertBody(u.getBody, ScopeDenialWording.message("upd", "tap", "read", updCaps), Seq("HR", "someone"))
        // A restricted session outside its grants: the version 404.
        sameResponse(restore("tvr_probe", unknownKind, hrLive, bobMine), versionNotFound("tvr_probe"))
        // In scope: the version is checked like a save.
        val mineLive = liveProbe.copy(catalog = "mine")
        for (r <- Seq(mineKey, updKey, bobMine))
            assert400(restore("tvr_probe", unknownKind, mineLive, r), "Unknown scriptKind 'bash'. Valid values: python (default), http.")
    }

    // ------------------------------------------------ route seam: reads and order

    private def version(cfg: String, n: Int = 1, name: String = "tvr_probe") = EntityVersion(name + "|" + n, name, n, cfg)

    private class Reads(live: TapConfig, versions: Seq[EntityVersion], stored: Option[EntityVersion]) {
        var live_ = 0
        var versions_ = 0
        var version_ = 0
        def run(r: HttpServletRequest, name: String = "tvr_probe"): ResponseEntity[String] =
            ctl().restoreTapVersionWith(
                name,
                1,
                r,
                () => { live_ += 1; live },
                () => { versions_ += 1; versions },
                () => { version_ += 1; stored }
            )
    }

    private val gson = new com.google.gson.Gson

    test("a restricted session on a hidden tap gets the version 404 without the version being read") {
        val hidden = liveProbe.copy(catalog = "secret")
        for (
            live <- Seq[TapConfig](hidden, null);
            stored <- Seq[Option[EntityVersion]](
                Some(version(gson.toJson(liveProbe.copy(catalog = "mine")))),
                Some(version(null)),
                Some(version("{not json")),
                None
            )
        ) {
            val reads = new Reads(live, Seq(version(gson.toJson(liveProbe.copy(catalog = "mine")))), stored)
            sameResponse(reads.run(bobMine), versionNotFound("tvr_probe"))
            assert(reads.live_ == 1, s"live reads ${reads.live_}")
            assert(reads.version_ == 0 && reads.versions_ == 0, s"history read for a hidden tap (live $live, stored $stored)")
        }
        // A missing version of a visible tap is still the version 404.
        val visible = new Reads(liveProbe.copy(catalog = "mine"), Nil, None)
        sameResponse(visible.run(bobMine), versionNotFound("tvr_probe"))
        assert(visible.version_ == 1)
    }

    test("route seam: a scoped key is refused before the version is read (live scope; a deleted tap's newest version)") {
        // Live tap outside scope.
        val outside = new Reads(liveProbe.copy(catalog = "HR"), Nil, Some(version(gson.toJson(probe("bash")))))
        val k = outside.run(mineKey)
        ScopeDenialWording.assertBody(k.getBody, ScopeDenialWording.message("mine-builder", "tap", "update", mineCaps), Seq("HR", "someone"))
        assert(outside.version_ == 0)
        // Deleted tap: the newest version that parses decides (Part A ruling 6).
        val hrNewest = Seq(version("""{"name":"tvr_probe","catalog":"mine"}""", 1), version("""{"name":"tvr_probe","catalog":"HR"}""", 2))
        val deletedOutside = new Reads(null, hrNewest, Some(version(gson.toJson(probe("bash").copy(catalog = "mine")))))
        val d = deletedOutside.run(mineKey)
        assert(d.getStatusCode.value == 403, d.getBody)
        assert(deletedOutside.version_ == 0)
        // Deleted, newest in scope: the version is read and checked like a save.
        val mineNewest = Seq(version("""{"name":"tvr_probe","catalog":"HR"}""", 1), version("""{"name":"tvr_probe","catalog":"mine"}""", 2))
        val deletedInside = new Reads(null, mineNewest, Some(version(gson.toJson(probe("bash").copy(catalog = "mine")))))
        assert400(deletedInside.run(mineKey), "Unknown scriptKind 'bash'. Valid values: python (default), http.")
        assert(deletedInside.version_ == 1)
    }

    test("route seam: ONE live read decides the guard and the candidate; unscoped callers read no version list") {
        for (r <- Seq(full, editor, authOff, mineKey, bobMine)) {
            val reads = new Reads(liveProbe.copy(catalog = "mine"), Nil, Some(version(gson.toJson(probe("bash").copy(catalog = "Old")))))
            assert400(reads.run(r), "Unknown scriptKind 'bash'. Valid values: python (default), http.")
            assert(reads.live_ == 1, s"live reads ${reads.live_} for $r")
            assert(reads.version_ == 1)
            assert(reads.versions_ == 0, "the version list is read only for a key whose tap is gone")
        }
        // No such version: 404, as before.
        val none = new Reads(liveProbe, Nil, None)
        sameResponse(none.run(authOff), versionNotFound("tvr_probe"))
    }

    // ------------------------------------------------------------- wiring pins

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    /** From `signature` to the next member declaration at class indent. */
    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature + " not found")
        val rest = src.substring(i + signature.length)
        val next = "\n    (?:private(?:\\[\\w+\\])? |protected |override )*(?:def |val |lazy val |@)".r.findFirstMatchIn(rest)
        src.substring(i, i + signature.length + next.map(_.start).getOrElse(rest.length))
    }

    private val entitySrc = "src/main/scala/ai/datris/api/EntityVersionAPIController.scala"
    private val tapSrc = "src/main/scala/ai/datris/api/TapAPIController.scala"

    // The story's replacement for TapServerManagedSecretSpec's "the restore
    // endpoint applies the tap secret rule before saving" (see the report:
    // PINNED EDITS NEEDED). Lives here so it fails before the change.
    test("the restore endpoint saves through the tap save path") {
        val src = source(entitySrc)
        val route = methodBody(src, "def restoreTapVersion(") + methodBody(src, "def restoreTapSnapshot(")
        assert(route.contains("restoreTapInternal"), "restoreTapVersion / restoreTapSnapshot must save through restoreTapInternal")
        assert(!route.contains("TapConfigIO.writeVersioned"), "the restore route must not write the tap itself")
    }

    test("one tap save core: no hand-copied save rule left in the restore route; the restore writes under the lock with the re-check") {
        val entity = source(entitySrc)
        for (copied <- Seq("TapConfigIO.writeVersioned", "TapCronGate.check(", "TapCronValidation.check(", "TapCronGate.normalizeCron("))
            assert(!entity.contains(copied), s"EntityVersionAPIController still calls $copied")
        val tap = source(tapSrc)
        assert(methodBody(tap, "def saveTapInternal(").contains("saveTapCore("), "saveTapInternal must call the save core")
        val restoreInternal = methodBody(tap, "def restoreTapInternal(")
        assert(restoreInternal.contains("saveTapCore("), "restoreTapInternal must call the save core")
        assert(restoreInternal.contains("RestoreOf("), "restoreTapInternal passes Some(RestoreOf(live, version))")
        assert(!restoreInternal.contains("TapConfigIO.writeVersioned"), "restoreTapInternal must not write the tap itself")
        val coreStart = tap.indexOf("def saveTapCore(")
        val saveEnd = tap.indexOf("@DeleteMapping(path = Array(\"/tap\")")
        assert(coreStart >= 0 && saveEnd > coreStart, "saveTapCore not found before DELETE /tap")
        val core = tap.substring(coreStart, saveEnd)
        assert(core.contains("withCatalogLock"), "the restore's re-read, re-check and write are under CatalogOps.withCatalogLock")
        assert(core.contains("tapRestoreRecheck"), "the restore re-checks under the lock")
        assert(core.contains("tapRestoreGuardDecision"), "the restore's guard is decided in the save core")
    }
}
