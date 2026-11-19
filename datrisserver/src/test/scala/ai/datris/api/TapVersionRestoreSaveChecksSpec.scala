package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.{DocumentTapValidator, SecretNames, TapCronGate, TapCronValidation}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Tap version restore reaches every save check that comes after the save
  * path's first store read, through the save core's store seam
  * (`TapAPIController.TapSaveIO`): the secret type check, the document-tap
  * target, the script existing in its backend, the dependency rule (missing
  * tap, other catalog, cycle), the cron parse and the test-before-cron gate.
  * Each refusal is the answer `POST /tap` gives for the same config (same
  * status, same body) and nothing is written. A restore of an identical
  * version still writes a version ("restored from version N"), and the write
  * under the lock carries the run, retry and test state of the tap read under
  * the lock (lead ruling 5). */
class TapVersionRestoreSaveChecksSpec extends AnyFunSuite with BeforeAndAfterEach {

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
    private var savedScope: Option[String] = None

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        TenantContext.set(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    // ---------------------------------------------------------------- fixtures

    private def authOff: HttpServletRequest = ScopeDenialFixtures.request(null, "POST", "/api/v1/tap/version/restore")._1

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private val hidden: ResponseEntity[String] = ResponseEntity.status(404).body[String]("{\"error\": \"No version 3 for tap 't'\"}")

    /** In-memory stores behind the save core: taps, pipelines, secret types,
      * script objects; every versioned write is recorded. */
    private class Store(
        initial: Seq[TapConfig],
        pipelines: Seq[PipelineConfig] = Nil,
        secretTypes: Map[String, Option[String]] = Map.empty,
        scripts: Map[String, String] = Map.empty,
        existsOverride: Option[TapConfig => Boolean] = None
    ) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val writes: mutable.Buffer[(TapConfig, String)] = mutable.Buffer.empty
        private def scriptKey(t: TapConfig): String = Option(t.scriptPath).filter(_.nonEmpty).getOrElse(t.scriptRepoPath)
        val io: TapAPIController.TapSaveIO = TapAPIController.TapSaveIO(
            readTap = n => taps.get(n).orNull,
            readPipeline = n => pipelines.find(_.name == n).orNull,
            secretType = n => secretTypes.get(n),
            scriptExists = t => existsOverride.map(_(t)).getOrElse(scripts.contains(scriptKey(t))),
            readScript = t => scripts.get(scriptKey(t)),
            loadAll = () => (taps.values.toSeq, pipelines),
            writeVersioned = (t, note, _) => {
                writes += ((t, note))
                val saved = t.copy(version = Option(taps.get(t.name).orNull).map(_.version + 1).getOrElse(1))
                taps(t.name) = saved
                saved
            }
        )
    }

    private def restore(store: Store, snap: TapConfig, live: TapConfig): Either[ResponseEntity[String], TapConfig] =
        new TapAPIController().restoreTapInternal(EntityVersionAPIController.tapRestoreCandidate("t", snap, live), live, 3, hidden, authOff, store.io)

    private def save(store: Store, body: TapConfig): Either[ResponseEntity[String], TapConfig] =
        new TapAPIController().saveTapInternalWith(body, null, authOff, restored = false, store.io)

    private def errorOf(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def sameResponse(a: ResponseEntity[String], b: ResponseEntity[String]): Unit =
        assert(a.getStatusCode == b.getStatusCode && a.getBody == b.getBody, s"${a.getStatusCode} ${a.getBody} vs ${b.getStatusCode} ${b.getBody}")

    /** Restore `snap` over `live` (stored as `stores()` builds it), expect a
      * refusal with `status`, nothing written; and `POST /tap` of the same
      * config over the same stores gives the same answer and writes nothing. */
    private def refusedLikeSave(stores: () => Store, snap: TapConfig, live: TapConfig, status: Int): ResponseEntity[String] = {
        val rs = stores()
        val r = restore(rs, snap, live)
        assert(r.isLeft, s"restore must be refused: $r")
        assert(r.left.get.getStatusCode.value == status, r.left.get.getBody)
        assert(rs.writes.isEmpty, "a refused restore writes nothing")
        val ss = stores()
        val s = save(ss, EntityVersionAPIController.tapRestoreCandidate("t", snap, live))
        assert(s.isLeft, s"POST /tap must refuse the same config: $s")
        sameResponse(r.left.get, s.left.get)
        assert(ss.writes.isEmpty)
        r.left.get
    }

    /** A live script tap on the built-in store, in catalog C. */
    private val live: TapConfig = TapConfig(
        name = "t",
        description = "live",
        targetPipeline = null,
        catalog = "C",
        createdByKeyLabel = "owner",
        createdAt = "2026-01-01 00:00:00",
        scriptKind = "python",
        scriptStorage = "minio",
        scriptPath = "tap-scripts/t.py",
        dependsOn = jl(),
        // As a save stamps it: the script was read and reads no other tap.
        inferredDependsOn = jl(),
        enabled = true,
        version = 3
    )
    private val scripts = Map("tap-scripts/t.py" -> "print('live')", "tap-scripts/v1.py" -> "print('v1')")

    // ------------------------------------------------------------ secret type

    test("restore: a version naming a secret that is not a tap secret is refused 400 like a save; nothing written") {
        val snap = live.copy(secretName = "ai-primary")
        val r = refusedLikeSave(() => new Store(Seq(live), secretTypes = Map("ai-primary" -> None), scripts = scripts), snap, live, 400)
        assert(errorOf(r) == SecretNames.tapSecretMessage("t", "ai-primary"), r.getBody)
        // A tap secret restores.
        val ok = new Store(Seq(live), secretTypes = Map("weather" -> Some("tap")), scripts = scripts)
        assert(restore(ok, live.copy(secretName = "weather"), live).isRight)
        assert(ok.writes.size == 1)
    }

    // ------------------------------------------------------- document target

    test("restore: a document tap version whose target pipeline cannot take documents is refused 400 like a save; nothing written") {
        val structured = PipelineConfig(name = "p", catalog = "C")
        val snap = live.copy(tapType = "document", targetPipeline = "p")
        val r = refusedLikeSave(() => new Store(Seq(live), pipelines = Seq(structured), scripts = scripts), snap, live, 400)
        val reason = DocumentTapValidator.incompatibilityReason(structured).get
        assert(errorOf(r) == "Document tap 't' cannot target pipeline 'p': " + reason.replace("\"", "'"), r.getBody)
        // A target that no longer exists.
        val gone = refusedLikeSave(() => new Store(Seq(live), scripts = scripts), live.copy(tapType = "document", targetPipeline = "gone"), live, 400)
        assert(errorOf(gone) == "Document tap 't' cannot target pipeline 'gone': target pipeline not found", gone.getBody)
    }

    // -------------------------------------------------------- script exists

    test("restore: a version whose script is no longer in its storage backend is refused 400 like a save; nothing written") {
        val snap = live.copy(scriptPath = "tap-scripts/gone.py")
        val r = refusedLikeSave(() => new Store(Seq(live), scripts = scripts), snap, live, 400)
        assert(
            errorOf(r) == "Tap script not found in its storage backend. " +
                "The script must be uploaded (via the tap wizard's script-store endpoints) before saving the tap config.",
            r.getBody
        )
        // A store that cannot answer counts as missing, as on save.
        val failing = () => new Store(Seq(live), scripts = scripts, existsOverride = Some(_ => throw new RuntimeException("store down")))
        refusedLikeSave(failing, live.copy(scriptPath = "tap-scripts/v1.py"), live, 400)
        // A script that is there restores.
        val ok = new Store(Seq(live), scripts = scripts)
        assert(restore(ok, live.copy(scriptPath = "tap-scripts/v1.py"), live).isRight)
        assert(ok.writes.map(_._1.scriptPath) == Seq("tap-scripts/v1.py"))
    }

    // ---------------------------------------------------------- dependencies

    test("restore: a changed dependsOn naming a deleted tap, a tap of another catalog, or closing a cycle is refused like a save; nothing written") {
        val a = TapConfig(name = "a", description = "d", targetPipeline = null, catalog = "C")
        val b = TapConfig(name = "b", description = "d", targetPipeline = null, catalog = "C", dependsOn = jl("t"))
        val x = TapConfig(name = "x", description = "d", targetPipeline = null, catalog = "Other")
        val stores = () => new Store(Seq(live, a, b, x), scripts = scripts)
        val catalogTaps = Seq(live, a, b)
        def expected(snap: TapConfig) =
            TapAPIController.dependencySaveProblem(EntityVersionAPIController.tapRestoreCandidate("t", snap, live), catalogTaps).get
        val missing = live.copy(dependsOn = jl("gone"))
        val m = refusedLikeSave(stores, missing, live, 400)
        sameResponse(m, expected(missing))
        assert(errorOf(m).contains("gone"), m.getBody)
        val otherCatalog = live.copy(dependsOn = jl("x"))
        val o = refusedLikeSave(stores, otherCatalog, live, 400)
        sameResponse(o, expected(otherCatalog))
        assert(errorOf(o).contains("x"), o.getBody)
        val cycle = live.copy(dependsOn = jl("b"))
        val c = refusedLikeSave(stores, cycle, live, 409)
        sameResponse(c, expected(cycle))
        assert(JsonParser.parseString(c.getBody).getAsJsonObject.has("cycle"), c.getBody)
        // A valid one restores.
        val ok = stores()
        assert(restore(ok, live.copy(dependsOn = jl("a")), live).isRight)
        assert(ok.writes.map(_._1.dependsOn) == Seq(jl("a")))
    }

    // ------------------------------------------------------------------ cron

    test("restore: a version whose cron does not parse is refused 400 like a save; nothing written") {
        val snap = live.copy(cronExpression = "not a cron")
        val r = refusedLikeSave(() => new Store(Seq(live), scripts = scripts), snap, live, 400)
        sameResponse(r, ResponseEntity.status(400).body[String](TapCronValidation.errorBody(TapCronValidation.check("t", "not a cron").get)))
    }

    test("restore: a scheduled version whose endpoint was not the tested one is refused 409 by the gate like a save; nothing written") {
        val httpLive = TapConfig(
            name = "t",
            description = "live",
            targetPipeline = null,
            catalog = "C",
            createdByKeyLabel = "owner",
            scriptKind = "http",
            endpointUrl = "https://a.example.com/tap",
            lastTestRunStatus = "success",
            lastTestRunScriptId = "http:https://a.example.com/tap",
            version = 3
        )
        val snap = httpLive.copy(endpointUrl = "https://b.example.com/tap", cronExpression = "0 0 * * * ?")
        val r = refusedLikeSave(() => new Store(Seq(httpLive)), snap, httpLive, 409)
        assert(errorOf(r).contains(TapCronGate.ScriptChangedReason), r.getBody)
        assert(errorOf(r) == TapCronGate.check(httpLive, EntityVersionAPIController.tapRestoreCandidate("t", snap, httpLive)).get.replace("\"", "'"))
        // The tested endpoint with a cron restores.
        val ok = new Store(Seq(httpLive))
        assert(restore(ok, httpLive.copy(cronExpression = "0 0 * * * ?"), httpLive).isRight)
        assert(ok.writes.size == 1)
        // A deleted tap re-created with a cron is gated as a new tap.
        val created = refusedLikeSave(() => new Store(Nil), snap.copy(endpointUrl = "https://a.example.com/tap"), null, 409)
        assert(errorOf(created).contains("never passed a test run"), created.getBody)
    }

    // ----------------------------------------------------------- no shortcut

    test("restore: an identical version still writes a new version noted 'restored from version N'; a save of it writes nothing") {
        val rs = new Store(Seq(live), scripts = scripts)
        val r = restore(rs, live.copy(version = 1), live)
        assert(r.isRight, s"$r")
        assert(rs.writes.size == 1 && rs.writes.head._2 == "restored from version 3", s"${rs.writes}")
        assert(r.right.get.version == 4)
        val ss = new Store(Seq(live), scripts = scripts)
        assert(save(ss, live).right.get eq live)
        assert(ss.writes.isEmpty, "a save that changes nothing writes nothing")
    }

    // --------------------------------------------- the write under the lock

    test("restore: a run that finished between the check and the write is not rolled back (ruling 5)") {
        // Decided on `live` (last run failed, retry 1, test green on id-1);
        // under the lock the stored tap has a newer run and test.
        val decided = live.copy(
            lastRunStatus = "failure",
            lastRunTime = "2026-11-19 01:00:00",
            lastRunTrigger = "cron",
            lastRunRetrySafe = true,
            retryCount = 1,
            lastTestRunStatus = "success",
            lastTestRunScriptId = "id-1"
        )
        val fresh = decided.copy(
            lastRunStatus = "success",
            lastRunTime = "2026-11-19 01:30:00",
            lastRunRecordCount = 42,
            lastRunTrigger = "manual",
            lastRunRetrySafe = false,
            retryCount = 0,
            lastTestRunStatus = "failure",
            lastTestRunTime = "2026-11-19 01:20:00",
            lastTestRunScriptId = "id-2"
        )
        val store = new Store(Seq(fresh), scripts = scripts)
        val r = restore(store, decided.copy(description = "v1", scriptPath = "tap-scripts/v1.py"), decided)
        assert(r.isRight, s"$r")
        assert(store.writes.size == 1)
        val w = store.writes.head._1
        assert(w.description == "v1" && w.scriptPath == "tap-scripts/v1.py", s"$w")
        assert(
            (w.lastRunStatus, w.lastRunTime, w.lastRunRecordCount, w.lastRunTrigger, w.lastRunRetrySafe, w.retryCount) ==
                (fresh.lastRunStatus, fresh.lastRunTime, fresh.lastRunRecordCount, fresh.lastRunTrigger, fresh.lastRunRetrySafe, fresh.retryCount),
            s"$w"
        )
        assert((w.lastTestRunStatus, w.lastTestRunTime, w.lastTestRunScriptId) == (fresh.lastTestRunStatus, fresh.lastTestRunTime, fresh.lastTestRunScriptId))
    }

    test("restore: a tap enabled, disabled or moved between the check and the write is 409 'nothing was restored'; nothing written") {
        for (fresh <- Seq(live.copy(enabled = false), live.copy(catalog = "Other"), live.copy(createdByKeyLabel = "else"))) {
            val store = new Store(Seq(fresh), scripts = scripts)
            val r = restore(store, live.copy(description = "v1"), live)
            assert(r.isLeft)
            sameResponse(r.left.get, TapAPIController.restoreConflict("t"))
            assert(store.writes.isEmpty)
        }
        // Deleted under the lock.
        val gone = new Store(Nil, scripts = scripts)
        val r = restore(gone, live.copy(description = "v1"), live)
        sameResponse(r.left.get, TapAPIController.restoreConflict("t"))
        assert(gone.writes.isEmpty)
    }

    test("restore: a deleted tap is re-created as POST /tap creates it: owner is the caller, test status cleared") {
        val store = new Store(Nil, scripts = scripts)
        val snap = live.copy(createdByKeyLabel = "old-owner", lastTestRunStatus = "success", lastTestRunScriptId = "id-1", lastRunStatus = "success")
        val r = restore(store, snap, null)
        assert(r.isRight, s"$r")
        val w = store.writes.head
        assert(w._2 == "restored from version 3")
        assert(w._1.createdByKeyLabel == null, "auth off: no caller label, as POST /tap")
        assert(w._1.lastTestRunStatus == null && w._1.lastTestRunScriptId == null, s"${w._1}")
        assert(w._1.lastRunStatus == "success", "other recorded run details are as in the version")
    }
}
