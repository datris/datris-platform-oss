package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.auth.TapPrincipal
import ai.datris.auth.TapPrincipal.{Full, Resolution, Resolved, Unresolved}
import ai.datris.model._
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Tap runs and owner access, report-only release
  * (plans/stories/tap-runs-hold-owner-scope.md), Acceptance bullet 5
  * (`TapFeedOwnerReportSpec`): a scheduled run of a tap whose owner may not
  * load into the target still feeds, and records one report line; the same
  * for an unresolved owner; a failure while resolving the owner does not fail
  * the run. Plus the central guard of this release: every run of a tap in a
  * "would be refused" situation has exactly the outcome it has on v1.61.0
  * (same result, same rows fed, same tap stamps, same run log); the only
  * differences are one WARN line, one audit row and the additive
  * `lastRunUsedCallback` stamp.
  *
  * Driven like TapRunnerScratchSpec: TapRunner.effects and
  * TapScriptRunner.executeOverride, no Mongo, no MinIO, no sidecar. The audit
  * log is on with a test sink (AuditLog.sinkOverride); WARN lines are read off
  * the root logger.
  *
  * Pinned seams (do not exist at 4f38ae31; the implementation adds them):
  * {{{
  * object TapRunner {
  *   trait Effects {
  *     ... // unchanged abstract members
  *     // The tap owner's identity for the feed report. CONCRETE default, so
  *     // existing Effects fakes compile and resolve a null owner to Full with
  *     // no store read. May throw: the run then proceeds exactly as before.
  *     def resolveOwner(label: String): TapPrincipal.Resolution = TapPrincipal.resolve(label)
  *   }
  * }
  * case class TapScriptResult(..., usedCallback: Boolean = false)   // TapScriptRunner sets it from TapRunTokens.revoke
  * case class TapConfig(..., lastRunUsedCallback: Boolean = false)  // stamped where lastRunRetrySafe is stamped
  * }}}
  * Report emitted at the feed of a scheduled (`trigger = "cron"`) run:
  * one `logger.warn` naming the tap and the target pipeline, and
  * `AuditLog.system("tap", <action>, "tap", <tap name>, metadata)` with
  * action `feed-outside-owner-scope` (rule 3, named by the story) or
  * `owner-unresolved` (rule 4, name chosen here). Metadata carries names only.
  */
class TapFeedOwnerReportSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val root: Path = Files.createTempDirectory("tap-feed-owner-report-spec")

    private val env: DatrisEnvironment = ScopeDenialFixtures
        .auditEnv()
        .copy(tempDir = root.toString, pipelineTableName = "test-pipeline", tapTableName = "test-tap", tapLogTableName = "test-tap-log")

    private val audit = mutable.ArrayBuffer[AuditEntry]()
    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        ScopeDenialFixtures.install(env)
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
    }

    // ---------------------------------------------------------------- identities

    private val rita: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    )
    private val salesKey: ResolvedKey = ResolvedKey(
        None,
        "sales-agent",
        Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "tap:read:catalog=Sales", "tap:create:catalog=Sales")),
        isLegacyFullAccess = false
    )

    // ---------------------------------------------------------------- stored items

    private val SecretRowValue = "SENSITIVE-ROW-VALUE-7f3a"
    private val ScriptMarker = "SCRIPT-BODY-MARKER-91c2"
    private val LogMarker = "SCRIPT-LOG-MARKER-55d0"

    private def pipelineIn(name: String, catalog: String) = PipelineConfig(
        name = name,
        catalog = catalog,
        createdByKeyLabel = "session:ada",
        source = Source(
            schemaProperties = SchemaProperties("db", new java.util.ArrayList[SchemaField]()),
            fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())
        ),
        destination = Destination(
            schemaProperties = SchemaProperties("db", new java.util.ArrayList[SchemaField](java.util.Arrays.asList(SchemaField("id", "int")))),
            database = Database(dbName = "db", schema = "public", table = name.replace('-', '_'))
        )
    )

    private val pipelines: Map[String, PipelineConfig] =
        Seq(pipelineIn("finance-ledger", "Finance"), pipelineIn("sales-orders", "Sales")).map(p => p.name -> p).toMap

    private def tapNamed(name: String, owner: String, target: String = "finance-ledger") = TapConfig(
        name = name,
        description = "d",
        targetPipeline = target,
        scriptPath = "taps/" + name + "/does-not-exist-in-spec.py",
        secretName = "weather-api",
        cronExpression = "0 */5 * * * ?",
        catalog = "Sales",
        createdByKeyLabel = owner,
        source = "source.example",
        lastRunStatus = "success",
        lastRunTime = "2026-11-21 09:00:00 UTC"
    )

    // ---------------------------------------------------------------- fakes

    private class FakeEffects(owner: String => Resolution) extends TapRunner.Effects {
        val tapWrites = mutable.ArrayBuffer[TapConfig]()
        val runLogs = mutable.ArrayBuffer[TapRunLog]()
        val submits = mutable.ArrayBuffer[(String, String, PipelineConfig, TapFeedInfo, Long)]() // (pipeline, filename, override, feed, bytes)
        val ownerReads = new AtomicInteger(0)
        override def writeTap(t: TapConfig): Unit = synchronized { tapWrites += t; () }
        override def readState(tapName: String): String = null
        override def commitState(s: TapState): Unit = ()
        override def writeRunLog(log: TapRunLog): Unit = synchronized { runLogs += log; () }
        override def readPipeline(name: String): PipelineConfig = pipelines.get(name).orNull
        override def submit(
            staged: StagedPayload,
            sizeHint: Long,
            filename: String,
            pipeline: String,
            publisherToken: String,
            tapFeed: TapFeedInfo,
            configOverride: PipelineConfig
        ): String = synchronized { submits += ((pipeline, filename, configOverride, tapFeed, sizeHint)); "pt-" + submits.size }
        override def resolveOwner(label: String): TapPrincipal.Resolution = { ownerReads.incrementAndGet(); owner(label) }
    }

    private def stagedResult(records: Int = 3, usedCallback: Boolean = false): TapScriptResult = {
        val f = Files.createTempFile(root, "staged", ".ndjson")
        val body = (1 to records).map(i => s"""{"id":$i,"note":"$SecretRowValue"}""").mkString("", "\n", if (records > 0) "\n" else "")
        Files.write(f, body.getBytes(StandardCharsets.UTF_8))
        TapScriptResult(
            staged = StagedPayload(f.toString, StagedFormat.NdJson, records.toLong, Files.size(f), arraySource = true),
            recordCount = records,
            error = null,
            logs = LogMarker,
            columns = java.util.Arrays.asList("id", "note"),
            usedCallback = usedCallback
        )
    }

    /** Runs `tap` with the fakes; returns the effects, the result and every WARN line logged. */
    private def runWith(
        tap: TapConfig,
        owner: String => Resolution,
        mode: String = "run",
        trigger: String = "cron",
        script: () => TapScriptResult = () => stagedResult()
    ): (FakeEffects, TapScriptResult, List[String]) = {
        val fx = new FakeEffects(owner)
        val savedEffects = TapRunner.effects
        val savedExec = TapScriptRunner.executeOverride
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)
        TapRunner.effects = fx
        TapScriptRunner.executeOverride = (_: TapConfig, _: String, _: String) => script()
        try {
            val result = TapRunner.run(tap, mode = mode, trigger = trigger, scriptOverride = "# " + ScriptMarker + "\n")
            val warns = appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
            (fx, result, warns)
        } finally {
            rootLogger.detachAppender(appender)
            TapRunner.effects = savedEffects
            TapScriptRunner.executeOverride = savedExec
        }
    }

    private def ownerRows(tapName: String): List[AuditEntry] =
        audit.synchronized(audit.toList).filter(e => e.category == "tap" && e.resourceName.contains(tapName) && e.action != "run")

    private def warnsFor(warns: List[String], tap: TapConfig): List[String] = warns.filter(w => w.contains(tap.name) && w.contains(tap.targetPipeline))

    // The run as v1.61.0 has it, with the clock and random tokens taken out.
    private case class Outcome(
        error: String,
        recordCount: Int,
        dataType: String,
        columns: java.util.List[String],
        pipelineTokens: java.util.List[String],
        hasPublisherToken: Boolean,
        submits: List[(String, String, PipelineConfig, TapFeedInfo, Long)],
        tapWrites: List[TapConfig],
        runLogs: List[TapRunLog]
    )

    private def outcomeOf(fx: FakeEffects, r: TapScriptResult): Outcome = Outcome(
        r.error,
        r.recordCount,
        r.dataType,
        r.columns,
        r.pipelineTokens,
        r.publisherToken != null,
        fx.submits.toList.map { case (p, f, o, feed, b) => (p, f, o, feed.copy(runTime = null), b) },
        // the additive stamp is the one permitted difference; compared separately
        fx.tapWrites.toList.map(_.copy(lastRunTime = null, lastRunUsedCallback = false)),
        fx.runLogs.toList.map(l => l.copy(runTime = null, durationMs = 0L, publisherToken = if (l.publisherToken == null) null else "set"))
    )

    private val fine: String => Resolution = _ => Full

    // ================================================================ rule 3: feed outside the owner's scope

    test("a scheduled run of a tap whose owner may not load into the target still feeds, and records one report line") {
        val t = tapNamed("rita-finance-feed", "session:rita")
        val (fx, result, warns) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full })
        // the run is a success that fed the stored config of the target, as on v1.61.0
        assert(result.error == null, String.valueOf(result.error))
        assert(fx.submits.size == 1 && fx.submits.head._1 == "finance-ledger" && fx.submits.head._3 == null, fx.submits.toString)
        assert(fx.tapWrites.map(_.lastRunStatus).toList == List("running", "success"))
        assert(fx.runLogs.map(_.status).toList == List("success"))
        // one report: a WARN line and an audit row naming the tap, the rule and the pipeline
        assert(warnsFor(warns, t).size == 1, warns.mkString("\n"))
        val rows = ownerRows(t.name)
        assert(rows.size == 1, rows.toString)
        val row = rows.head
        assert(row.action == "feed-outside-owner-scope" && row.resourceType.contains("tap") && row.actor == ai.datris.audit.AuditActorInfo.System, row.toString)
        val md = row.metadata.map(_.toString).getOrElse("")
        assert(md.contains("finance-ledger"), "names the target pipeline: " + md)
        assert(md.contains("session:rita"), "names the owner: " + md)
    }

    test("the same holds for a catalog-scoped key owner") {
        val t = tapNamed("key-finance-feed", "sales-agent")
        val (fx, result, _) = runWith(t, { case "sales-agent" => Resolved(salesKey); case _ => Full })
        assert(result.error == null && fx.submits.size == 1, String.valueOf(result.error))
        assert(ownerRows(t.name).map(_.action) == List("feed-outside-owner-scope"), ownerRows(t.name).toString)
    }

    test("the run of a tap whose owner may not load into the target has exactly the outcome of the same tap whose owner may") {
        val t = tapNamed("guard-compare", "session:rita")
        val (fxReported, rReported, _) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full })
        val (fxFine, rFine, _) = runWith(t, fine)
        assert(outcomeOf(fxReported, rReported) == outcomeOf(fxFine, rFine))
    }

    test("no report when the owner may load into the target, for a full-access owner, or for a tap with no owner") {
        val inScope = tapNamed("rita-sales-feed", "session:rita", target = "sales-orders")
        val (_, r1, w1) = runWith(inScope, { case "session:rita" => Resolved(rita); case _ => Full })
        assert(r1.error == null && ownerRows(inScope.name).isEmpty && warnsFor(w1, inScope).isEmpty, ownerRows(inScope.name).toString)

        val adminOwned = tapNamed("ada-finance-feed", "session:ada")
        val adminKey = APIKeyValidator.resolveFromSession(User("ada", "h", User.RoleAdmin, null, null, null))
        val (_, r2, w2) = runWith(adminOwned, _ => Resolved(adminKey))
        assert(r2.error == null && ownerRows(adminOwned.name).isEmpty && warnsFor(w2, adminOwned).isEmpty)

        val noOwner = tapNamed("nobody-finance-feed", null)
        val (_, r3, w3) = runWith(noOwner, fine)
        assert(r3.error == null && ownerRows(noOwner.name).isEmpty && warnsFor(w3, noOwner).isEmpty)
    }

    test("the default Effects.resolveOwner resolves a tap with no owner to full access without any store read") {
        // An Effects fake that does not override resolveOwner (every existing spec's fake): a
        // null owner must not reach UserStore or the key store, which are not wired here.
        val t = tapNamed("default-seam", null)
        val fx = new TapRunner.Effects {
            val submits = new AtomicInteger(0)
            override def writeTap(tap: TapConfig): Unit = ()
            override def readState(tapName: String): String = null
            override def commitState(state: TapState): Unit = ()
            override def writeRunLog(log: TapRunLog): Unit = ()
            override def readPipeline(name: String): PipelineConfig = pipelines.get(name).orNull
            override def submit(s: StagedPayload, h: Long, f: String, p: String, pt: String, feed: TapFeedInfo, o: PipelineConfig): String = {
                submits.incrementAndGet(); "pt"
            }
        }
        assert(fx.resolveOwner(null) == Full)
        val savedEffects = TapRunner.effects
        val savedExec = TapScriptRunner.executeOverride
        TapRunner.effects = fx
        TapScriptRunner.executeOverride = (_: TapConfig, _: String, _: String) => stagedResult()
        try {
            val r = TapRunner.run(t, mode = "run", trigger = "cron", scriptOverride = "# x\n")
            assert(r.error == null && fx.submits.get == 1, String.valueOf(r.error))
        } finally {
            TapRunner.effects = savedEffects
            TapScriptRunner.executeOverride = savedExec
        }
    }

    // ================================================================ rule 4: owner no longer resolves

    test("a scheduled run of a tap whose owner no longer resolves still feeds, and records one report line") {
        val t = tapNamed("orphan-feed", "gone-agent", target = "sales-orders")
        val (fx, result, warns) = runWith(t, { case "gone-agent" => Unresolved; case _ => Full })
        assert(result.error == null, String.valueOf(result.error))
        assert(fx.submits.size == 1 && fx.submits.head._1 == "sales-orders")
        assert(fx.tapWrites.map(_.lastRunStatus).toList == List("running", "success"))
        val rows = ownerRows(t.name)
        assert(rows.map(_.action) == List("owner-unresolved"), rows.toString)
        assert(rows.head.metadata.map(_.toString).getOrElse("").contains("gone-agent"), rows.toString)
        assert(warns.count(_.contains(t.name)) == 1, warns.mkString("\n"))
        val (fxFine, rFine, _) = runWith(t, fine)
        assert(outcomeOf(fx, result) == outcomeOf(fxFine, rFine))
    }

    // ================================================================ a failing report never breaks the run

    test("a failure while resolving the owner does not fail the run, and the run is otherwise unchanged") {
        val t = tapNamed("flaky-owner-feed", "session:rita")
        val (fx, result, _) = runWith(t, _ => throw new IllegalStateException("user store down"))
        assert(result.error == null, "the owner lookup failing is not the run failing: " + result.error)
        assert(fx.submits.size == 1)
        assert(fx.tapWrites.map(_.lastRunStatus).toList == List("running", "success"), fx.tapWrites.map(_.lastRunError).toString)
        assert(!fx.tapWrites.exists(_.lastRunError != null), fx.tapWrites.map(_.lastRunError).toString)
        val (fxFine, rFine, _) = runWith(t, fine)
        assert(outcomeOf(fx, result) == outcomeOf(fxFine, rFine))
        assert(!ownerRows(t.name).exists(_.action == "feed-outside-owner-scope"), "nothing is claimed about an owner that could not be read")
    }

    test("a failure in the audit write of a report does not fail the run") {
        AuditLog.sinkOverride = (_: AuditEntry) => throw new IllegalStateException("audit sink down")
        val t = tapNamed("audit-down-feed", "session:rita")
        val (fx, result, _) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full })
        assert(result.error == null && fx.submits.size == 1, String.valueOf(result.error))
        assert(fx.tapWrites.map(_.lastRunStatus).toList == List("running", "success"))
    }

    test("an owner lookup that answers null does not fail the run") {
        val t = tapNamed("null-answer-feed", "session:rita")
        val (fx, result, _) = runWith(t, _ => null)
        assert(result.error == null && fx.submits.size == 1, String.valueOf(result.error))
    }

    // ================================================================ no secrets, scripts or data in the report

    test("the report row and line carry names only: no record values, script text, script logs or secret name") {
        val t = tapNamed("names-only-feed", "session:rita")
        val (_, _, warns) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full })
        val rows = ownerRows(t.name)
        assert(rows.size == 1, rows.toString)
        val text = rows.head.toJson.toString + "\n" + warnsFor(warns, t).mkString("\n")
        for (marker <- Seq(SecretRowValue, ScriptMarker, LogMarker, "weather-api"))
            assert(!text.contains(marker), "report carries '" + marker + "': " + text)
    }

    // ================================================================ when the report is computed

    test("a test run never checks the feed: no owner read, no report, nothing written to the tap") {
        val t = tapNamed("test-mode-feed", "session:rita")
        val (fx, result, warns) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full }, mode = "test", trigger = "manual")
        assert(result.error == null)
        assert(fx.ownerReads.get == 0, "a test run resolves no owner")
        assert(fx.submits.isEmpty && fx.tapWrites.isEmpty, "a test run feeds and stamps nothing, as before")
        assert(ownerRows(t.name).isEmpty && warnsFor(warns, t).isEmpty)
    }

    // PROPOSED, for the lead to confirm (the story does not say): the owner is
    // resolved only when the run reaches the feed, at most once per run.
    test("(proposed) the owner is resolved at most once per run, and not at all when the run feeds nothing") {
        val t = tapNamed("once-per-run-feed", "session:rita")
        val (fx, _, _) = runWith(t, { case "session:rita" => Resolved(rita); case _ => Full })
        assert(fx.ownerReads.get <= 1, fx.ownerReads.get.toString)
        val empty = tapNamed("empty-run-feed", "session:rita")
        val (fx0, r0, _) = runWith(empty, { case "session:rita" => Resolved(rita); case _ => Full }, script = () => stagedResult(records = 0))
        assert(r0.error == null && fx0.submits.isEmpty)
        assert(fx0.ownerReads.get == 0, "nothing fed, no owner read")
        assert(ownerRows(empty.name).isEmpty)
        val failed = tapNamed("failed-run-feed", "session:rita")
        val (fx1, r1, _) = runWith(failed, { case "session:rita" => Resolved(rita); case _ => Full }, script = () => TapScriptResult(null, 0, "script failed"))
        assert(r1.error == "script failed" && fx1.ownerReads.get == 0)
    }

    // PROPOSED, for the lead to confirm: the story does not bound report volume.
    // A tap on a 5-minute schedule would otherwise write 288 audit rows and WARN
    // lines a day, forever. Proposed rule: at most once per tap per rule per day.
    test("(proposed) repeated scheduled runs of the same reported tap write one audit row and one WARN line, not one per run") {
        val t = tapNamed("every-five-minutes-feed", "session:rita")
        val owner: String => Resolution = { case "session:rita" => Resolved(rita); case _ => Full }
        val all = (1 to 3).map(_ => runWith(t, owner))
        assert(all.forall { case (fx, r, _) => r.error == null && fx.submits.size == 1 }, "every run still feeds")
        assert(ownerRows(t.name).size == 1, ownerRows(t.name).toString)
        assert(all.map { case (_, _, w) => warnsFor(w, t).size }.sum == 1, all.map(_._3).toString)
    }

    // ================================================================ the additive lastRunUsedCallback stamp

    test("a run whose script used the callback stamps lastRunUsedCallback on the tap; one that did not stamps false") {
        val t = tapNamed("stamp-feed", null)
        val (fxUsed, rUsed, _) = runWith(t, fine, script = () => stagedResult(usedCallback = true))
        assert(rUsed.usedCallback, "the result carries the mark")
        assert(fxUsed.tapWrites.last.lastRunStatus == "success" && fxUsed.tapWrites.last.lastRunUsedCallback, fxUsed.tapWrites.toString)
        val (fxQuiet, _, _) = runWith(t.copy(lastRunUsedCallback = true), fine, script = () => stagedResult(usedCallback = false))
        assert(!fxQuiet.tapWrites.last.lastRunUsedCallback, "the mark is per run, not sticky")
        // nothing else differs between the two runs' tap writes
        assert(fxUsed.tapWrites.map(_.copy(lastRunTime = null, lastRunUsedCallback = false)).toList ==
            fxQuiet.tapWrites.map(_.copy(lastRunTime = null, lastRunUsedCallback = false)).toList)
    }

    test("a failed run stamps the mark the same way (where lastRunRetrySafe is stamped)") {
        val t = tapNamed("stamp-failure", null)
        val (fx, r, _) = runWith(t, fine, script = () => TapScriptResult(null, 0, "boom", usedCallback = true))
        assert(r.error == "boom")
        assert(
            fx.tapWrites.last.lastRunStatus == "failure" && fx.tapWrites.last.lastRunRetrySafe && fx.tapWrites.last.lastRunUsedCallback,
            fx.tapWrites.toString
        )
    }

    test("a test run stamps nothing on the tap, used callback or not (as before)") {
        val t = tapNamed("stamp-test", null)
        val (fx, r, _) = runWith(t, fine, mode = "test", trigger = "manual", script = () => stagedResult(usedCallback = true))
        assert(r.error == null && fx.tapWrites.isEmpty)
    }
}
