package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Tap runs and owner access, report-only release, review follow-ups for
  * [[TapOwnerScopeReport]]: the once-a-day map's overflow, the script-upload
  * report's one pipeline read per tap a day whatever the answer, rule 1 for a
  * caller lacking a run-token read, and catalog placeholder taps never
  * reported. Report only: none of these calls changes an answer. */
class TapOwnerScopeReportSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private val audit = mutable.ArrayBuffer[AuditEntry]()

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

    private val salesKey = ScopeDenialFixtures.key("sales-agent", "pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "tap:read:catalog=Sales")
    private val monitorKey = ScopeDenialFixtures.key("monitor", "job:read", "pipeline:read", "tap:read")
    private val rita = APIKeyValidator.resolveFromSession(
        User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    )

    private val salesOrders = PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada")
    private val financeLedger = PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")
    private val pipelines = Seq(salesOrders, financeLedger).map(p => p.name -> p).toMap

    private def tap(name: String, target: String, owner: String = "sales-agent") =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = "Sales", createdByKeyLabel = owner)

    private def used = TapScriptResult(null, 1, null, usedCallback = true)

    // ================================================================ the once-a-day map

    test("the de-duplication map: past MaxEntries it drops earlier days first, then everything but the new key; a dropped key reports once more") {
        val saved = TapOwnerScopeReport.today
        val day = new AtomicLong(40000L)
        TapOwnerScopeReport.today = () => day.get
        try {
            assert(TapOwnerScopeReport.firstToday("overflow|old"))
            assert(!TapOwnerScopeReport.firstToday("overflow|old"), "the same day: not first")
            day.incrementAndGet()
            (1 to TapOwnerScopeReport.MaxEntries - 2).foreach(i => TapOwnerScopeReport.firstToday("overflow|today-" + i))
            // the entries that cross the bound drop every earlier day's entry (the first day's at least), keeping today's
            TapOwnerScopeReport.firstToday("overflow|cross-1")
            TapOwnerScopeReport.firstToday("overflow|cross-2")
            assert(TapOwnerScopeReport.seenSize == TapOwnerScopeReport.MaxEntries, "exactly today's entries: " + TapOwnerScopeReport.seenSize)
            assert(!TapOwnerScopeReport.firstToday("overflow|today-1"), "today's entries survive the first drop")
            // still over with only today's entries: everything but the new key goes
            (1 to TapOwnerScopeReport.MaxEntries).foreach(i => TapOwnerScopeReport.firstToday("overflow|more-" + i))
            assert(TapOwnerScopeReport.seenSize <= TapOwnerScopeReport.MaxEntries, TapOwnerScopeReport.seenSize.toString)
            assert(TapOwnerScopeReport.firstToday("overflow|today-2"), "a key dropped by an overflow is first again: one extra line at most")
            // a new day is first again for every key
            day.incrementAndGet()
            assert(TapOwnerScopeReport.firstToday("overflow|today-2"))
        } finally TapOwnerScopeReport.today = saved
    }

    // ================================================================ rule 6: one pipeline read per tap a day

    test("rule 6: a limited caller whose target is in scope pays one pipeline read per tap a day, not one per upload") {
        val reads = new AtomicInteger(0)
        val read: String => PipelineConfig = n => { reads.incrementAndGet(); pipelines.get(n).orNull }
        val r = ScopeDenialFixtures.request(salesKey, "POST", "/api/v1/tap/script")._1
        val (_, warns) = withWarns((1 to 5).foreach(_ => TapOwnerScopeReport.scriptTarget(r, tap("in-scope-uploads", "sales-orders"), read)))
        assert(reads.get == 1, reads.get.toString)
        assert(!warns.exists(_.contains("in-scope-uploads")), warns.mkString("\n"))
        // the refused case also reads once and warns once
        reads.set(0)
        val (_, refusedWarns) = withWarns((1 to 5).foreach(_ => TapOwnerScopeReport.scriptTarget(r, tap("out-of-scope-uploads", "finance-ledger"), read)))
        assert(reads.get == 1, reads.get.toString)
        assert(refusedWarns.count(_.contains("out-of-scope-uploads")) == 1, refusedWarns.mkString("\n"))
    }

    test("rule 6: a pipeline read that throws is not counted as the day's read") {
        val reads = new AtomicInteger(0)
        val r = ScopeDenialFixtures.request(salesKey, "POST", "/api/v1/tap/script")._1
        TapOwnerScopeReport.scriptTarget(
            r,
            tap("flaky-read-uploads", "finance-ledger"),
            _ => { reads.incrementAndGet(); throw new IllegalStateException("mongo down") }
        )
        val (_, warns) =
            withWarns(TapOwnerScopeReport.scriptTarget(r, tap("flaky-read-uploads", "finance-ledger"), n => { reads.incrementAndGet(); pipelines(n) }))
        assert(reads.get == 2 && warns.count(_.contains("flaky-read-uploads")) == 1, (reads.get, warns).toString)
    }

    // ================================================================ rule 1: a caller lacking a run-token read

    test("rule 1: a caller that is not limited but holds no query capability is reported, in plain words") {
        val r = ScopeDenialFixtures.request(monitorKey, "POST", "/api/v1/tap/test")._1
        val (_, warns) = withWarns(TapOwnerScopeReport.callerReads(r, tap("monitor-test-run", "sales-orders", "monitor"), used))
        val line = warns.filter(_.contains("monitor-test-run"))
        assert(line.size == 1, warns.mkString("\n"))
        assert(line.head.contains("cannot query data") && line.head.contains("next release"), line.head)
        assert(!line.head.contains("query:*"), "no capability strings: " + line.head)
        assert(audit.synchronized(audit.toList).count(e => e.action == "reads-narrowed" && e.resourceName.contains("monitor-test-run")) == 1)
        // a limited caller's line says what it is limited to
        val (_, ritaWarns) = withWarns(TapOwnerScopeReport.callerReads(
            ScopeDenialFixtures.request(rita, "POST", "/api/v1/tap/run")._1,
            tap("rita-test-run", "sales-orders", "session:rita"),
            used
        ))
        assert(ritaWarns.exists(w => w.contains("rita-test-run") && w.contains("is limited to the catalogs it is granted")), ritaWarns.mkString("\n"))
    }

    // ================================================================ catalog placeholders

    test("a catalog placeholder tap is never reported by the run, save or script-upload lines, and nothing is read for it") {
        val reads = new AtomicInteger(0)
        val read: String => PipelineConfig = n => { reads.incrementAndGet(); pipelines.get(n).orNull }
        val placeholder = tap(CatalogOps.Placeholder.name("Finance"), "finance-ledger")
        val (_, warns) = withWarns {
            TapOwnerScopeReport.callerReads(ScopeDenialFixtures.request(rita, "POST", "/api/v1/tap/test")._1, placeholder, used)
            TapOwnerScopeReport.saveTarget(ScopeDenialFixtures.request(salesKey, "POST", "/api/v1/tap")._1, placeholder.copy(targetPipeline = "missing"), read)
            TapOwnerScopeReport.scriptTarget(ScopeDenialFixtures.request(salesKey, "POST", "/api/v1/tap/script")._1, placeholder, read)
            TapOwnerScopeReport.feedReport(placeholder, _ => throw new IllegalStateException("never called"), read)
        }
        assert(reads.get == 0, reads.get.toString)
        assert(!warns.exists(_.contains(placeholder.name)), warns.mkString("\n"))
        assert(audit.synchronized(audit.toList).forall(e => !e.resourceName.contains(placeholder.name)))
    }

    // ================================================================ remedy text (plans/stories/tap-change-owner.md)

    // Story: Change a tap's owner, Design 8: the two scheduled-feed WARN lines
    // name an administrator's owner change instead of recreating the tap.
    // Widening the owner stays a remedy; the audit rows are unchanged.

    private def assertNamesOwnerChange(line: String): Unit = {
        assert(line.contains("change the tap's owner"), "names the change-owner action: " + line)
        assert(line.contains("POST /api/v1/tap/owner"), "names the route: " + line)
        assert(line.contains("administrator"), "says an administrator does it: " + line)
        assert(!line.toLowerCase.contains("recreate"), "no longer says recreate: " + line)
    }

    test("the scheduled-feed line for an owner that may not load into the target names the owner change, not recreating the tap") {
        val name = "remedy-outside-" + System.nanoTime()
        val (_, warns) = withWarns {
            TapOwnerScopeReport.feedReport(tap(name, "finance-ledger"), _ => ai.datris.auth.TapPrincipal.Resolved(salesKey), n => pipelines.get(n).orNull)
        }
        val lines = warns.filter(_.contains(name))
        assert(lines.size == 1, warns.mkString("\n"))
        assertNamesOwnerChange(lines.head)
        assert(lines.head.contains("point the tap at a pipeline its owner may load into"), lines.head)
        assert(audit.synchronized(audit.toList).count(e => e.resourceName.contains(name) && e.action == "feed-outside-owner-scope") == 1)
    }

    test("the scheduled-feed line for an owner that no longer exists names the owner change, not recreating the tap") {
        val name = "remedy-unresolved-" + System.nanoTime()
        val (_, warns) = withWarns {
            TapOwnerScopeReport.feedReport(tap(name, "sales-orders", "gone-agent"), _ => ai.datris.auth.TapPrincipal.Unresolved, n => pipelines.get(n).orNull)
        }
        val lines = warns.filter(_.contains(name))
        assert(lines.size == 1, warns.mkString("\n"))
        assertNamesOwnerChange(lines.head)
        assert(audit.synchronized(audit.toList).count(e => e.resourceName.contains(name) && e.action == "owner-unresolved") == 1)
    }

    // Story: Change a tap's owner. A tap handed to another owner that is
    // also ineligible is reported again the same day for the new owner; the
    // same tap and owner is still reported once a day.

    test("a tap handed to another owner that also may not load into the target is reported again the same day") {
        val name = "handed-outside-" + System.nanoTime()
        val otherKey = ScopeDenialFixtures.key("other-sales-agent", "pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "tap:read:catalog=Sales")
        val resolve: String => ai.datris.auth.TapPrincipal.Resolution = {
            case "other-sales-agent" => ai.datris.auth.TapPrincipal.Resolved(otherKey)
            case _ => ai.datris.auth.TapPrincipal.Resolved(salesKey)
        }
        val (_, warns) = withWarns {
            TapOwnerScopeReport.feedReport(tap(name, "finance-ledger"), resolve, n => pipelines.get(n).orNull)
            TapOwnerScopeReport.feedReport(tap(name, "finance-ledger"), resolve, n => pipelines.get(n).orNull)
            TapOwnerScopeReport.feedReport(tap(name, "finance-ledger", "other-sales-agent"), resolve, n => pipelines.get(n).orNull)
            TapOwnerScopeReport.feedReport(tap(name, "finance-ledger", "other-sales-agent"), resolve, n => pipelines.get(n).orNull)
        }
        val lines = warns.filter(_.contains(name))
        assert(lines.size == 2, warns.mkString("\n"))
        assert(lines.head.contains("'sales-agent'") && lines(1).contains("'other-sales-agent'"), lines.mkString("\n"))
        val rows = audit.synchronized(audit.toList).filter(e => e.resourceName.contains(name) && e.action == "feed-outside-owner-scope")
        assert(rows.size == 2, rows.toString)
    }

    test("a tap handed to another owner that also no longer exists is reported again the same day") {
        val name = "handed-unresolved-" + System.nanoTime()
        val gone: String => ai.datris.auth.TapPrincipal.Resolution = _ => ai.datris.auth.TapPrincipal.Unresolved
        val (_, warns) = withWarns {
            TapOwnerScopeReport.feedReport(tap(name, "sales-orders", "gone-agent"), gone, n => pipelines.get(n).orNull)
            TapOwnerScopeReport.feedReport(tap(name, "sales-orders", "gone-agent"), gone, n => pipelines.get(n).orNull)
            TapOwnerScopeReport.feedReport(tap(name, "sales-orders", "also-gone-agent"), gone, n => pipelines.get(n).orNull)
        }
        val lines = warns.filter(_.contains(name))
        assert(lines.size == 2, warns.mkString("\n"))
        assert(lines.head.contains("'gone-agent'") && lines(1).contains("'also-gone-agent'"), lines.mkString("\n"))
        val rows = audit.synchronized(audit.toList).filter(e => e.resourceName.contains(name) && e.action == "owner-unresolved")
        assert(rows.size == 2, rows.toString)
    }
}
