package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, CatalogArchive, SecretNames, TapScriptResult}
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Tap runs and owner access, report-only release
  * (plans/stories/tap-runs-hold-owner-scope.md), Design "Run-time lines" at
  * the controller sites, and the additive `lastRunUsedCallback` field.
  * Nothing here is refused, filtered or answered differently: each site keeps
  * its v1.61.0 answer and only adds a report.
  *
  * - Rule 5, at the save (`POST /tap`, driven through the real save core
  *   `saveTapInternalWith` with in-memory stores): a key whose loads are
  *   checked saves a tap whose target does not exist yet. Saved as before,
  *   plus one WARN line naming the tap and the missing pipeline.
  * - Rule 1, at `POST /tap/test` and `POST /tap/run` after the run: when the
  *   caller is limited and the script used the callback, one WARN line and
  *   one audit row; nothing otherwise. `testTap` / `runTap` read Mongo and
  *   Vault before they answer, so the report is a companion seam they call.
  * - Rule 6, at `POST /tap/script` (reads TapConfigIO and the code store
  *   before it answers): one WARN line when the target rule would refuse.
  *
  * Pinned seams (do not exist at 4f38ae31; the implementation adds them):
  * {{{
  * object TapAPIController {
  *   // Called by testTap and runTap (every mode) right after TapRunner.run, before
  *   // the response is built. Never throws; never sets AuditLog.RecordedAttr (the
  *   // request's own audit entry is kept). Audit action "reads-narrowed" (name chosen here).
  *   def reportCallerReads(request: HttpServletRequest, tap: TapConfig, result: TapScriptResult): Unit
  *   // Called by storeScript after the existing checks, with the stored tap. Never throws.
  *   def reportScriptTarget(request: HttpServletRequest, tap: TapConfig, readPipeline: String => PipelineConfig): Unit
  * }
  * }}}
  * The rule-5 report is observed through the save core. One seam change is
  * needed for that, identical in production: `saveTapCore` must pass
  * `io.readPipeline` to `targetPipelineRefusal` on `POST /tap` (at 4f38ae31 it
  * uses the default `PipelineConfigIO.read`, so a load-checked caller's save
  * reaches Mongo even with a TapSaveIO given; the version restore already
  * passes `io.readPipeline`). `ProductionSaveIO.readPipeline` is that same read.
  */
class TapOwnerScopeReportSitesSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private var savedScope: Option[String] = None
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    // ---------------------------------------------------------------- identities

    private def req(rk: ResolvedKey, uri: String = "/api/v1/tap"): (HttpServletRequest, mutable.Map[String, AnyRef]) =
        ScopeDenialFixtures.request(rk, "POST", uri)

    private val salesCaps = Seq(
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )
    private val salesKey = ScopeDenialFixtures.key("sales-agent", salesCaps: _*)
    private val fullKey = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val rita = APIKeyValidator.resolveFromSession(
        User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    )
    private val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))

    // ---------------------------------------------------------------- stores

    private val salesOrders = PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada")
    private val financeLedger = PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")
    private val pipelineMap = Seq(salesOrders, financeLedger).map(p => p.name -> p).toMap
    private val readPipeline: String => PipelineConfig = n => pipelineMap.get(n).orNull

    private class Store(initial: Seq[TapConfig] = Nil) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val writes: mutable.Buffer[TapConfig] = mutable.Buffer.empty
        val io: TapAPIController.TapSaveIO = TapAPIController.TapSaveIO(
            readTap = n => taps.get(n).orNull,
            readPipeline = readPipeline,
            secretType = _ => None,
            scriptExists = t => t.scriptPath == "tap-scripts/early.py",
            readScript = _ => Some("print('hello')"),
            loadAll = () => (taps.values.toSeq, pipelineMap.values.toSeq),
            writeVersioned = (t, _, _) => {
                writes += t
                taps(t.name) = t
                t
            }
        )
    }

    private def body(name: String, target: String) =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = "Sales", scriptPath = "tap-scripts/early.py")

    private def withWarns[T](f: => T): (T, List[String]) = {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)
        try {
            val r = f
            (r, appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage))
        } finally rootLogger.detachAppender(appender)
    }

    private def save(rk: ResolvedKey, store: Store, b: TapConfig): (Either[ResponseEntity[String], TapConfig], List[String]) =
        withWarns(new TapAPIController().saveTapInternalWith(b, null, req(rk)._1, restored = false, store.io))

    private def comparable(t: TapConfig): TapConfig = t.copy(createdAt = null, updatedAt = null, createdByKeyLabel = null)

    // ================================================================ rule 5: save of a tap whose target does not exist

    test("rule 5: a load-checked key saving a tap whose target does not exist is saved as before, with one WARN line naming the tap and the pipeline") {
        val store = new Store()
        val (out, warns) = save(salesKey, store, body("early-tap", "not-created-yet"))
        assert(out.isRight, out.left.toOption.map(_.getBody).toString)
        assert(store.writes.size == 1)
        val written = store.writes.head
        assert(written.targetPipeline == "not-created-yet" && written.createdByKeyLabel == "sales-agent", written.toString)
        // the same body saved by a full-access key is written identically (owner aside)
        val fullStore = new Store()
        val (fullOut, fullWarns) = save(fullKey, fullStore, body("early-tap", "not-created-yet"))
        assert(fullOut.isRight && comparable(fullStore.writes.head) == comparable(written))
        // the report
        assert(warns.count(w => w.contains("early-tap") && w.contains("not-created-yet")) == 1, warns.mkString("\n"))
        assert(!fullWarns.exists(w => w.contains("early-tap") && w.contains("not-created-yet")), "a full-access key is never load-checked: " + fullWarns)
    }

    test("rule 5: no WARN line when the target exists in the key's scope") {
        val store = new Store()
        val (out, warns) = save(salesKey, store, body("sales-tap", "sales-orders"))
        assert(out.isRight && store.writes.size == 1)
        assert(!warns.exists(w => w.contains("sales-tap") && w.contains("sales-orders")), warns.mkString("\n"))
    }

    test("rule 5: an update of an existing early tap by the same key is saved as before") {
        val existing = body("early-tap", "not-created-yet").copy(createdByKeyLabel = "sales-agent", createdAt = "2026-11-01", version = 3)
        val store = new Store(Seq(existing))
        val (out, _) = save(salesKey, store, body("early-tap", "not-created-yet").copy(description = "changed"))
        assert(out.isRight && store.writes.size == 1 && store.writes.head.description == "changed", out.toString)
    }

    test("unchanged: a restricted session's save of a tap whose target does not exist gets today's answer, nothing written") {
        val store = new Store()
        val (out, _) = save(rita, store, body("rita-early", "not-created-yet"))
        val today = TapAPIController.targetPipelineRefusal(req(rita)._1, body("rita-early", "not-created-yet"), readPipeline)
        assert(today.isDefined, "a restricted session's target must exist (v1.61.0)")
        assert(out.isLeft && out.left.get.getStatusCode == today.get.getStatusCode && out.left.get.getBody == today.get.getBody, out.toString)
        assert(store.writes.isEmpty)
    }

    // ================================================================ rule 1: test and manual run

    private def result(usedCallback: Boolean): TapScriptResult =
        TapScriptResult(
            staged = StagedPayload("/nonexistent/staged.ndjson", StagedFormat.NdJson, 1L, 10L, arraySource = true),
            recordCount = 1,
            error = null,
            logs = "SCRIPT-LOG-MARKER-55d0",
            usedCallback = usedCallback
        )

    private def tapOwnedBy(name: String, owner: String, target: String = "sales-orders") =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = "Sales", createdByKeyLabel = owner, secretName = "weather-api")

    private def rowsFor(name: String): List[AuditEntry] = audit.synchronized(audit.toList).filter(_.resourceName.contains(name))

    test("rule 1: a limited caller whose script used the callback gets one WARN line and one audit row naming the tap and the rule") {
        for ((rk, name) <- Seq(rita -> "rita-reads-test", salesKey -> "key-reads-test")) {
            val (r, attrs) = req(rk, "/api/v1/tap/test")
            val (_, warns) = withWarns(TapAPIController.reportCallerReads(r, tapOwnedBy(name, rk.label), result(usedCallback = true)))
            assert(warns.count(_.contains(name)) == 1, warns.mkString("\n"))
            val rows = rowsFor(name)
            assert(rows.size == 1, rows.toString)
            assert(rows.head.category == "tap" && rows.head.action == "reads-narrowed" && rows.head.resourceType.contains("tap"), rows.head.toString)
            assert(!attrs.contains(AuditLog.RecordedAttr), "the request's own audit entry is kept (no AuditLog.record)")
            val text = rows.head.toJson.toString + warns.mkString
            assert(!text.contains("SCRIPT-LOG-MARKER-55d0") && !text.contains("weather-api"), "names only: " + text)
        }
    }

    test("rule 1: no report when the script did not use the callback, or when the caller is not limited") {
        val cases = Seq(
            (rita, false, "rita-quiet-test"),
            (salesKey, false, "key-quiet-test"),
            (editor, true, "editor-reads-test"),
            (fullKey, true, "full-reads-test"),
            (null, true, "anon-reads-test")
        )
        for ((rk, used, name) <- cases) {
            val (_, warns) = withWarns(TapAPIController.reportCallerReads(req(rk, "/api/v1/tap/run")._1, tapOwnedBy(name, null), result(used)))
            assert(rowsFor(name).isEmpty && !warns.exists(_.contains(name)), name + ": " + warns)
        }
    }

    test("rule 1: the report never throws, whatever it is given") {
        TapAPIController.reportCallerReads(req(rita)._1, null, result(usedCallback = true))
        TapAPIController.reportCallerReads(req(rita)._1, tapOwnedBy("nulls", null), null)
        TapAPIController.reportCallerReads(null, tapOwnedBy("nulls", null), result(usedCallback = true))
        AuditLog.sinkOverride = (_: AuditEntry) => throw new IllegalStateException("audit sink down")
        TapAPIController.reportCallerReads(req(rita)._1, tapOwnedBy("sink-down", "session:rita"), result(usedCallback = true))
    }

    // ================================================================ rule 6: script upload

    test("rule 6: a script upload to a tap whose stored target the caller may not load into gets one WARN line naming the tap and the pipeline") {
        for ((rk, name) <- Seq(salesKey -> "key-finance-script", rita -> "rita-finance-script")) {
            val (_, warns) =
                withWarns(TapAPIController.reportScriptTarget(req(rk, "/api/v1/tap/script")._1, tapOwnedBy(name, rk.label, "finance-ledger"), readPipeline))
            assert(warns.count(w => w.contains(name) && w.contains("finance-ledger")) == 1, warns.mkString("\n"))
        }
    }

    test("rule 6: no WARN line when the target is in the caller's scope, for full access, or with no target") {
        val cases = Seq(
            (salesKey, "sales-orders", "key-sales-script"),
            (rita, "sales-orders", "rita-sales-script"),
            (fullKey, "finance-ledger", "full-finance-script"),
            (editor, "finance-ledger", "editor-finance-script"),
            (salesKey, null, "key-none-script"),
            (salesKey, "not-created-yet", "key-early-script") // a key's missing target is the save's case (rule 5)
        )
        for ((rk, target, name) <- cases) {
            val (_, warns) = withWarns(TapAPIController.reportScriptTarget(req(rk, "/api/v1/tap/script")._1, tapOwnedBy(name, rk.label, target), readPipeline))
            assert(!warns.exists(_.contains(name)), name + ": " + warns)
        }
    }

    test("rule 6: the report never throws, even when the pipeline read fails") {
        TapAPIController.reportScriptTarget(
            req(salesKey)._1,
            tapOwnedBy("read-fails", "sales-agent", "finance-ledger"),
            _ => throw new IllegalStateException("mongo down")
        )
        TapAPIController.reportScriptTarget(req(salesKey)._1, null, readPipeline)
        TapAPIController.reportScriptTarget(null, tapOwnedBy("no-request", "sales-agent", "finance-ledger"), readPipeline)
    }

    // ================================================================ unchanged decisions on the run and save paths

    test("unchanged: a run or save of a limited owner's tap by a caller who may load into the target is not refused") {
        // POST /tap/run and the save apply the caller's load rule only, as on v1.61.0
        val ritaFinance = tapOwnedBy("rita-finance", "session:rita", "finance-ledger")
        for (rk <- Seq(fullKey, editor, null))
            assert(TapAPIController.targetPipelineRefusal(req(rk, "/api/v1/tap/run")._1, ritaFinance, readPipeline).isEmpty, String.valueOf(rk))
        // a key whose target does not exist yet is left to the save, which accepts it
        assert(TapAPIController.targetPipelineRefusal(req(salesKey)._1, tapOwnedBy("early", "sales-agent", "not-created-yet"), readPipeline).isEmpty)
    }

    // ================================================================ the additive field

    /** `new Gson().toJson` of this tap on v1.61.0 (GET /tap serializes the stored TapConfig with Gson). */
    private val sample = TapConfig(
        name = "t",
        description = "d",
        targetPipeline = "p",
        catalog = "Sales",
        createdByKeyLabel = "session:rita",
        lastRunStatus = "success",
        lastRunRetrySafe = true,
        retryCount = 2
    )
    private val SampleJsonV1610 =
        """{"name":"t","description":"d","targetPipeline":"p","enabled":true,"tapType":"structured","lastRunStatus":"success",""" +
            """"lastRunRecordCount":0,"lastTestRunRecordCount":0,"catalog":"Sales","createdByKeyLabel":"session:rita","version":1,""" +
            """"lastRunRetrySafe":true,"retryCount":2}"""

    test("the existing fields of a tap serialize byte-identically; the new field is present beside them") {
        val o = JsonParser.parseString(new Gson().toJson(sample)).getAsJsonObject
        assert(o.has("lastRunUsedCallback") && !o.get("lastRunUsedCallback").getAsBoolean, o.toString)
        o.remove("lastRunUsedCallback")
        assert(o.toString == SampleJsonV1610, o.toString)
    }

    test("a tap stored before the upgrade (no lastRunUsedCallback) reads as false, and every other field reads as before") {
        val read = new Gson().fromJson(SampleJsonV1610, classOf[TapConfig])
        assert(!read.lastRunUsedCallback)
        assert(read.copy(lastRunUsedCallback = false) == sample.copy(lastRunUsedCallback = false))
    }

    test("a version restore keeps the live tap's lastRunUsedCallback, like the other run stamps") {
        val live = sample.copy(lastRunUsedCallback = true)
        val snap = sample.copy(description = "older", lastRunUsedCallback = false)
        assert(EntityVersionAPIController.tapRestoreCandidate("t", snap, live).lastRunUsedCallback)
        val liveQuiet = sample.copy(lastRunUsedCallback = false)
        assert(!EntityVersionAPIController.tapRestoreCandidate("t", snap.copy(lastRunUsedCallback = true), liveQuiet).lastRunUsedCallback)
    }

    test("a catalog export clears lastRunUsedCallback, like the other run stamps") {
        assert(!CatalogArchive.stripTapForExport(sample.copy(lastRunUsedCallback = true)).lastRunUsedCallback)
    }
}
