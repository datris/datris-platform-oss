package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.{TapAPIController, TapReferenceRules}
import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{ScopeDenialFixtures, TapPrincipal}
import ai.datris.auth.TapPrincipal.{Resolved, Unresolved}
import ai.datris.model._
import ai.datris.util.DoctorService._
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Tap script, script reference and secret rules, first release
  * (plans/stories/tap-save-references-in-scope.md): the `tap.references`
  * doctor row, the scan behind it, and the once-a-day bound on the report
  * lines (rules 5-8).
  *
  * Names the implementation must provide (modelled on TapOwnerScopeScan and
  * the `tap.owner_scope` row):
  * {{{
  * object ai.datris.util.TapReferenceScan {
  *   val OutsideArea = "outsideArea"                 // a built-in script outside tap-scripts/ (rule 6)
  *   val SharedScript = "sharedScript"               // one script shared by taps of different catalogs or owners (rule 5)
  *   val SecretOutsideOwner = "secretOutsideOwner"   // owner (TapPrincipal) has scoped tap read not covering the secret (rules 7, 8)
  *   val ReviewWithoutUpdate = "reviewWithoutUpdate" // a key that used review without tap update since the server started (rule 4)
  *   case class Finding(group: String, name: String, detail: String = null)   // name: the tap, or the key label for ReviewWithoutUpdate
  *   case class Result(findings: List[Finding], unreadable: List[String])
  *   object Result { val empty: Result }
  *   // pure; each distinct owner label and secret looked up at most once; a failed lookup lists the tap under `unreadable`;
  *   // catalog placeholder taps skipped
  *   def scan(taps: List[TapConfig], resolveOwner: String => TapPrincipal.Resolution,
  *            secretOwner: String => Option[Option[String]], reviewKeys: List[String]): Result
  *   def liveScan(): Result
  * }
  * object DoctorService {
  *   trait Probes { def tapReferenceScan(): TapReferenceScan.Result = TapReferenceScan.Result.empty }   // LiveProbes overrides
  *   class TapReferencesCheck(probes: Probes) extends Check   // id "tap.references", startupSafe, warn at most,
  *                                                            // registered right after tap.owner_scope
  * }
  * }}}
  * The report lines are bounded by the same once-per-UTC-day de-duplication
  * as the previous story's (`TapOwnerScopeReport.today` is the clock).
  */
class TapReferenceScanSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
    }

    // ---------------------------------------------------------------- fixtures

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private val builder = key("builder", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")
    private val rita = APIKeyValidator.resolveFromSession(
        User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    )

    private def tap(name: String, catalog: String, owner: String, path: String, secret: String = null) =
        TapConfig(name = name, description = "d", targetPipeline = "p", catalog = catalog, createdByKeyLabel = owner, scriptPath = path, secretName = secret)

    private val owners: Map[String, TapPrincipal.Resolution] = Map("builder" -> Resolved(builder), "session:rita" -> Resolved(rita), "gone" -> Unresolved)
    private def resolve(label: String): TapPrincipal.Resolution = owners.getOrElse(label, TapPrincipal.Full)
    private val secretOwners: Map[String, Option[String]] = Map("ada-weather" -> Some("session:ada"), "builder-secret" -> Some("builder"))

    private def scan(taps: List[TapConfig], reviewKeys: List[String] = Nil): TapReferenceScan.Result =
        TapReferenceScan.scan(taps, resolve, n => secretOwners.get(n), reviewKeys)

    private def names(r: TapReferenceScan.Result, group: String): List[String] = r.findings.filter(_.group == group).map(_.name)

    // ================================================================ the scan

    test("scan: nothing to report for taps inside the area, unshared, with secrets their owners' access covers") {
        val r = scan(
            List(
                tap("a", "Sales", "session:ada", "tap-scripts/a_11111111.py", "ada-weather"),
                tap("b", "Sales", "builder", "tap-scripts/b_22222222.py", "builder-secret"),
                tap("c", "Sales", "session:rita", "tap-scripts/c_33333333.py", "ada-weather")
            )
        )
        assert(r.findings.isEmpty && r.unreadable.isEmpty, r.toString)
    }

    test("scan: a tap whose built-in script sits outside the tap script area is listed (every form)") {
        val outside =
            Seq("uploads/x.csv", "pipeline-scripts/p/transformation_1a2b.py", "tap-scripts/../uploads/x.py", "/tap-scripts/o_1.py", "TAP-SCRIPTS/o_1.py")
        val taps = outside.zipWithIndex.map { case (p, i) => tap("out-" + i, "Sales", "session:ada", p) }.toList :+ tap(
            "in",
            "Sales",
            "session:ada",
            "tap-scripts/in_1.py"
        )
        val r = scan(taps)
        assert(names(r, TapReferenceScan.OutsideArea).toSet == outside.indices.map("out-" + _).toSet, r.toString)
    }

    test("scan: one script shared by taps of different catalogs or owners lists each of them; sharing within one catalog and owner is not listed") {
        val shared = "tap-scripts/shared_44444444.py"
        val r = scan(
            List(
                tap("s-sales", "Sales", "session:ada", shared),
                tap("s-finance", "Finance", "session:ada", shared),
                tap("o-1", "Sales", "session:ada", "tap-scripts/o_5.py"),
                tap("o-2", "Sales", "builder", "tap-scripts/o_5.py"),
                tap("same-1", "Sales", "session:ada", "tap-scripts/same_6.py"),
                tap("same-2", "Sales", "session:ada", "tap-scripts/same_6.py")
            )
        )
        assert(names(r, TapReferenceScan.SharedScript).toSet == Set("s-sales", "s-finance", "o-1", "o-2"), r.toString)
    }

    test("scan: a tap whose owner's scoped tap read does not cover its secret is listed; full, unresolved and covered owners are not") {
        val r = scan(
            List(
                tap("b-ada", "Sales", "builder", "tap-scripts/b-ada_1.py", "ada-weather"),
                tap("b-own", "Sales", "builder", "tap-scripts/b-own_1.py", "builder-secret"),
                tap("r-ada", "Sales", "session:rita", "tap-scripts/r-ada_1.py", "ada-weather"),
                tap("full-ada", "Sales", "session:ada", "tap-scripts/full-ada_1.py", "ada-weather"),
                tap("gone-ada", "Sales", "gone", "tap-scripts/gone-ada_1.py", "ada-weather"),
                tap("b-missing", "Sales", "builder", "tap-scripts/b-missing_1.py", "no-such-secret")
            )
        )
        assert(names(r, TapReferenceScan.SecretOutsideOwner) == List("b-ada"), r.toString)
    }

    test("scan: each key that used review without tap update is listed") {
        val r = scan(Nil, List("ro-agent", "rag-agent"))
        assert(names(r, TapReferenceScan.ReviewWithoutUpdate).toSet == Set("ro-agent", "rag-agent"), r.toString)
    }

    test("scan: a failed owner or secret lookup lists the tap as unreadable, never as nothing to report; each lookup is made at most once") {
        val secretReads = new AtomicInteger(0)
        val ownerReads = new AtomicInteger(0)
        val taps = List(
            tap("flaky-1", "Sales", "builder", "tap-scripts/f1_1.py", "vault-down"),
            tap("flaky-2", "Sales", "builder", "tap-scripts/f2_1.py", "vault-down"),
            tap("lost-owner", "Sales", "key-store-down", "tap-scripts/l_1.py", "ada-weather")
        )
        val r = TapReferenceScan.scan(
            taps,
            l => { ownerReads.incrementAndGet(); if (l == "key-store-down") throw new IllegalStateException("keys down") else resolve(l) },
            n => { secretReads.incrementAndGet(); if (n == "vault-down") throw new IllegalStateException("vault down") else secretOwners.get(n) },
            Nil
        )
        assert(r.unreadable.toSet == Set("flaky-1", "flaky-2", "lost-owner"), r.toString)
        assert(secretReads.get <= 2, "one read per distinct secret: " + secretReads.get)
        assert(ownerReads.get <= 2, "one lookup per distinct owner: " + ownerReads.get)
    }

    test("scan: catalog placeholder taps are never listed") {
        val r = scan(List(tap(CatalogOps.Placeholder.name("Sales"), "Sales", "session:ada", "uploads/x.csv")))
        assert(r.findings.isEmpty, r.toString)
    }

    // ================================================================ the doctor row

    private val doctorSpec = new DoctorServiceSpec
    private val slots = Slots("oss/ai-primary", "oss/codegen", "oss/embedding")

    private def probes(r: => TapReferenceScan.Result): Probes =
        new doctorSpec.FakeProbes() {
            override def tapReferenceScan(): TapReferenceScan.Result = r
        }

    private def referencesCheck(p: Probes): Check = {
        val all = DoctorService.checks(p, slots, "1.62.0", Map.empty)
        all.find(_.id == "tap.references").getOrElse(fail("no tap.references check in " + all.map(_.id)))
    }

    private val sample = TapReferenceScan.Result(
        List(
            TapReferenceScan.Finding(TapReferenceScan.OutsideArea, "hand-saved-tap"),
            TapReferenceScan.Finding(TapReferenceScan.SharedScript, "finance-copy"),
            TapReferenceScan.Finding(TapReferenceScan.SecretOutsideOwner, "builder-weather"),
            TapReferenceScan.Finding(TapReferenceScan.ReviewWithoutUpdate, "ro-agent")
        ),
        Nil
    )
    private val sampleNames = Seq("hand-saved-tap", "finance-copy", "builder-weather", "ro-agent")

    test("tap.references is ok with nothing to report") {
        val byDefault = referencesCheck(new doctorSpec.FakeProbes()).run()
        assert(byDefault.status == "ok", "the Probes default has nothing to report: " + byDefault)
        val empty = referencesCheck(probes(TapReferenceScan.Result(Nil, Nil))).run()
        assert(empty.status == "ok" && empty.remediation.isEmpty, empty.toString)
    }

    test("tap.references warns naming each tap, and each key that used review without update, with what will stop") {
        val r = referencesCheck(probes(sample)).run()
        assert(r.status == "warn" && r.severity == "warn", r.toString)
        sampleNames.foreach(n => assert(r.detail.contains(n), n + " in " + r.detail))
        val text = r.detail + " " + r.remediation
        assert(text.contains("next release"), "says what stops in the next release: " + text)
        assert(r.detail.contains("since"), "the review group says since when it was recorded: " + r.detail)
        assert(r.remediation.nonEmpty)
    }

    test("tap.references never reports error") {
        val inputs = Seq(
            TapReferenceScan.Result(Nil, Nil),
            sample,
            TapReferenceScan.Result(Nil, List("t-flaky")),
            TapReferenceScan.Result(sample.findings, List("t-flaky")),
            TapReferenceScan.Result((1 to 300).toList.map(i => TapReferenceScan.Finding(TapReferenceScan.OutsideArea, "t" + i)), Nil)
        )
        for (in <- inputs) {
            val r = DoctorService.runOne(referencesCheck(probes(in)))
            assert(r.status != "error" && r.severity != "error", in.toString + " -> " + r)
        }
        val failing = DoctorService.runOne(referencesCheck(probes(throw new RuntimeException("mongo down"))))
        assert(failing.status != "error" && failing.severity != "error", failing.toString)
    }

    test("tap.references warns that it could not check when a store read fails") {
        val thrown = DoctorService.runOne(referencesCheck(probes(throw new RuntimeException("mongo down"))))
        assert(thrown.status == "warn" && thrown.detail.contains("could not"), thrown.toString)
        val partial = referencesCheck(probes(TapReferenceScan.Result(Nil, List("t-flaky", "t-broken")))).run()
        assert(partial.status == "warn" && partial.detail.contains("could not"), partial.toString)
        assert(partial.detail.contains("t-flaky") && partial.detail.contains("t-broken"), partial.detail)
    }

    test("tap.references is startup-safe and in the full report, right after tap.owner_scope; the other checks keep their order") {
        val p = probes(sample)
        assert(referencesCheck(p).startupSafe)
        val fullIds = DoctorService.run("full", Set.empty, Map.empty, p, slots, "1.62.0").checks.map(_.id)
        assert(fullIds.indexOf("tap.references") == fullIds.indexOf("tap.owner_scope") + 1, fullIds.toString)
        val quickIds = DoctorService.run("quick", Set.empty, Map.empty, p, slots, "1.62.0").checks.map(_.id)
        assert(quickIds.contains("tap.references"), quickIds.toString)
        val startup = DoctorService.runStartup(p, slots, "1.62.0")
        assert(startup.find(_.id == "tap.references").exists(_.status == "warn"), startup.toString)
        val v1610Order = Seq(
            "vault.token_ttl",
            "vault.ai_slots",
            "jdbc.mssql_driver",
            "objectstore.bucket_overrides",
            "ai.embedding_model",
            "disk.usage",
            "staging.area",
            "staging.orphans",
            "tap.secret_scope",
            "tap.owner_scope",
            "keys.inert_scopes",
            "codegen.isolation",
            "runner.concurrency",
            "ai.sample_values",
            "governance.controls",
            "audit.strict",
            "provenance.strict",
            "version.skew",
            "env.seen",
            "env.label"
        )
        assert(fullIds.filterNot(_ == "tap.references") == v1610Order, fullIds.toString)
        // a failing scan does not fail the doctor run
        val failing = DoctorService.run("full", Set.empty, Map.empty, probes(throw new RuntimeException("mongo down")), slots, "1.62.0")
        assert(failing.checks.map(_.id) == fullIds)
    }

    test("tap.references: many listed taps give a bounded detail that still states how many") {
        val many = (1 to 500).toList.map(i => TapReferenceScan.Finding(TapReferenceScan.OutsideArea, "tap-number-" + i))
        val r = referencesCheck(probes(TapReferenceScan.Result(many, Nil))).run()
        assert(r.status == "warn")
        assert(r.detail.length < 4000, "detail is " + r.detail.length + " chars")
        assert(r.detail.contains("500") && r.detail.contains("tap-number-1"), r.detail.take(300))
    }

    test("tap.references carries a counts-only detail naming no tap or key, for a caller that does not see the whole installation") {
        val r = referencesCheck(probes(TapReferenceScan.Result(sample.findings, List("t-flaky")))).run()
        assert(r.namelessDetail != null, r.toString)
        (sampleNames :+ "t-flaky").foreach(n => assert(!r.namelessDetail.contains(n), n + " in " + r.namelessDetail))
        assert(r.namelessDetail.contains("next release"), r.namelessDetail)
        val nameless = Report("full", Map.empty, Seq(r)).withoutNames
        assert(nameless.checks.head.detail == r.namelessDetail && nameless.checks.head.status == r.status)
    }

    // ================================================================ the report lines: once per tap and rule a UTC day

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

    test("a reported finding is logged and audited at most once per tap and rule a UTC day, and again the next day") {
        val admin = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val n = "daily-" + System.nanoTime()
        val candidate = TapConfig(name = n, description = "d", targetPipeline = "p", catalog = "Sales", scriptPath = "uploads/x.py")
        val lookups = TapReferenceRules.Lookups(() => Nil, _ => None, () => "")
        def once(): List[String] =
            withWarns(TapAPIController.referenceRefusal(
                ScopeDenialFixtures.request(admin, "POST", "/api/v1/tap")._1,
                TapReferenceRules.Save,
                candidate,
                null,
                lookups
            ))._2
                .filter(_.contains(n))
        val savedToday = TapOwnerScopeReport.today
        try {
            TapOwnerScopeReport.today = () => 30000L
            assert(once().size == 1)
            assert(once().isEmpty && once().isEmpty)
            TapOwnerScopeReport.today = () => 30001L
            assert(once().size == 1, "the next UTC day reports again")
            assert(audit.synchronized(audit.toList).count(_.resourceName.contains(n)) == 2)
        } finally TapOwnerScopeReport.today = savedToday
    }
}
