package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, DoctorService, TapOwnerScopeScan}
import ai.datris.util.DoctorService.{CheckResult, Report}
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.funsuite.AnyFunSuite

/** Tap runs and owner access, report-only release, review follow-up:
  * `GET /api/v1/doctor` needs only `config:read`, so the `tap.owner_scope`
  * row gives a caller that does not see the whole installation counts only,
  * and names to one that does ([[DoctorAPIController.forCaller]]). */
class DoctorReportCallerSpec extends AnyFunSuite {

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val admin = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val editor = session(User("ed", "h", User.RoleEditor, null, null, null))
    private val viewer = session(User("vi", "h", User.RoleViewer, null, null, null))
    private val restrictedEditor = session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val restrictedViewer = session(User("val", "h", User.RoleViewer, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "read")))
    private val fullKey = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val wildcardKey = ScopeDenialFixtures.key("wild", "*:*")
    private val reportingKey = ScopeDenialFixtures.key("reporting", "pipeline:read", "tap:read", "config:read", "query:postgres")
    private val catalogKey = ScopeDenialFixtures.key("sales-agent", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "config:read")
    private val ownKey = ScopeDenialFixtures.key("own-agent", "pipeline:read:owner=self", "tap:read:owner=self", "config:read")
    private val configOnlyKey = ScopeDenialFixtures.key("config-reader", "config:read")

    private val row = {
        val probes = new DoctorServiceSpecProbes(
            TapOwnerScopeScan.Result(
                List(
                    TapOwnerScopeScan.Finding(TapOwnerScopeScan.FeedOutsideOwner, "rita-finance", "finance-ledger", "session:rita"),
                    TapOwnerScopeScan.Finding(TapOwnerScopeScan.OwnerUnresolved, "orphan-tap", "sales-orders", "gone-agent")
                ),
                Nil
            )
        )
        DoctorService.checks(probes, DoctorService.Slots("", "", ""), "1.62.0", Map.empty).find(_.id == "tap.owner_scope").get.run()
    }
    private val report = Report("full", Map("server" -> "1.62.0"), Seq(row, CheckResult("other.check", "ok", "ok", "fine", "")))
    private val names = Seq("rita-finance", "finance-ledger", "session:rita", "orphan-tap", "sales-orders", "gone-agent")

    test("unrestricted callers see the names: administrator, unrestricted editor and viewer, full and unscoped keys, no identity (API keys off)") {
        for (
            (who, caller) <- Seq(
                "admin" -> Some(admin),
                "editor" -> Some(editor),
                "viewer" -> Some(viewer),
                "full key" -> Some(fullKey),
                "wildcard key" -> Some(wildcardKey),
                "unscoped read key" -> Some(reportingKey),
                "keys off" -> None
            )
        ) {
            assert(DoctorAPIController.seesWholeInstall(caller), who)
            val out = DoctorAPIController.forCaller(report, caller)
            assert(out eq report, who)
            for (n <- names) assert(out.toJson.contains(n), who + " sees " + n)
        }
    }

    test("a restricted session, a key limited to a catalog or to what it created, and a key that cannot read taps see counts only") {
        for (
            (who, caller) <- Seq(
                "restricted editor" -> restrictedEditor,
                "restricted viewer" -> restrictedViewer,
                "catalog key" -> catalogKey,
                "own key" -> ownKey,
                "config-only key" -> configOnlyKey
            )
        ) {
            assert(!DoctorAPIController.seesWholeInstall(Some(caller)), who)
            val out = DoctorAPIController.forCaller(report, Some(caller))
            val json = out.toJson
            for (n <- names) assert(!json.contains(n), who + " must not see " + n + ": " + json)
            val r = out.checks.find(_.id == "tap.owner_scope").get
            assert(r.status == "warn" && r.detail.contains("(1)") && r.detail.contains("2 tap(s)"), who + ": " + r.detail)
            assert(r.remediation == row.remediation, "remediation unchanged")
            assert(out.checks.find(_.id == "other.check").get.detail == "fine", "other rows unchanged")
            assert(out.summary == report.summary, "same statuses")
        }
    }
}

/** The DoctorServiceSpec fake probes with a fixed tap owner scan. */
private class DoctorServiceSpecProbes(scan: TapOwnerScopeScan.Result) extends DoctorService.Probes {
    override def tapOwnerScopeScan(): TapOwnerScopeScan.Result = scan
    def vaultLookupSelf(): Option[Map[String, String]] = None
    def secret(name: String): Option[Map[String, String]] = None
    def apiKeyResolves(provider: String, rawKey: String): Boolean = false
    def classPresent(className: String): Boolean = false
    def pipelineSources(): List[(String, String)] = Nil
    def objectStoreBucketOverrides(): List[(String, String, String)] = Nil
    def httpGet(url: String, timeoutMs: Int): (Int, String) = throw new IllegalStateException("no network in spec")
    def diskUsage(path: String): Option[(Long, Long)] = None
    def envSeen(names: Seq[String]): Map[String, Boolean] = Map.empty
    def stagingArea(): DoctorService.StagingAreaState = DoctorService.StagingAreaState("/tmp", exists = true, writable = true, usableBytes = Long.MaxValue)
    def stagingOrphans(): (List[String], Long) = (Nil, 0L)
    def chatSlots(): Seq[(String, ai.datris.model.AIConfig)] = Nil
    def probeChat(config: ai.datris.model.AIConfig, timeoutMs: Int): (Int, String) = (200, "")
    def probeEmbedding(provider: String, endpoint: String, model: String, apiKey: String, timeoutMs: Int): (Int, String) = (200, "")
    def tapSecretRefs(): List[(String, String, Option[String])] = Nil
    def anyPipelineProtects(): Boolean = false
    def codegenRunnerEnabled(): Boolean = true
    def codegenRunnerHealth(): Either[String, Unit] = Right(())
    def tapRunnerEnabled(): Boolean = true
    def tapRunnerHealth(): Either[String, (Int, Int)] = Right((0, 4))
    def recentRunnerWaits(hours: Int): (Int, Int, Long) = (0, 0, 0L)
}
