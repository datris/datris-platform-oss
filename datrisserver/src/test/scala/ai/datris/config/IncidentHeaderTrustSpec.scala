package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditEntry, AuditLog}
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, UserContext}
import ai.datris.policy.{AgentPolicy, PendingAction, PolicyIO}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

/** Story: an approved agent action runs with the requester's access, and
  * internal headers count only from the platform's own callers
  * (plans/stories/approval-replay-requester-scope.md), Acceptance bullet 5
  * (`IncidentHeaderTrustSpec`), Design 3, Steps 4, owner decision D5.
  *
  * The incident header counts only from the platform's own callers (the
  * `ui` key, the recovery key, or any caller with API keys off). From any
  * other key it is ignored everywhere it is read and recorded once as a
  * `security` / `spoofed-incident` audit event; the request is otherwise
  * handled as if it carried no such header.
  *
  * Drives one agent call (POST /api/v1/tap/run, as MCP relays it) through the
  * real TenantInterceptor, PolicyInterceptor and AuditInterceptor on a
  * mocked request; keys resolve through the key resolution cache. The
  * pure accessor is in IncidentHeaderAccessorSpec. */
class IncidentHeaderTrustSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var store: MemoryStore = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()

    private val Incident = "inc_7"
    private val Body = "{\"name\":\"t1\"}"

    private val etl: ResolvedKey =
        ResolvedKey(None, "etl-agent", Capability.parseList(Seq("tap:read", "tap:run", "tap:update")), isLegacyFullAccess = false, keyId = Some("k_etl"))
    private val recovery: ResolvedKey =
        ResolvedKey(None, RecoveryKey.Label, Capability.parseList(RecoveryKey.Capabilities), isLegacyFullAccess = false, keyId = Some("k_rec"))

    private val EtlValue = "test-etl-value"
    private val RecoveryValue = "test-recovery-value"

    private val autoTapRun = """{"actions":{"tap:run":"auto"},"recovery":{"mode":"autopilot"}}"""
    private val autoTapRunRecoveryOff = """{"actions":{"tap:run":"auto"},"recovery":{"mode":"off"}}"""
    private val approveTapRun = """{"actions":{"tap:run":"approve"},"recovery":{"mode":"autopilot"}}"""

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env(useAuditLog = true).copy(useApiKeys = true, useAgentPolicy = true)
        val db = new InMemoryUserDb
        allUsers.foreach(db.put)
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        store = new MemoryStore
        PolicyInterceptor.store = store
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => { audit.add(e); () }
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        PolicyInterceptor.store = PolicyInterceptor.MongoStore
        PolicyIO.currentOverride = null
        AuditLog.sinkOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    private def policy(json: String): AgentPolicy = AgentPolicy.fromJson(json) match {
        case Right(p) => p
        case Left(err) => fail("expected a valid policy: " + err)
    }

    // ------------------------------------------------------------ callers

    private sealed trait Caller { def headers: Seq[(String, String)]; def name: String }
    private case object Customer extends Caller {
        val name = "customer key"
        def headers = { seedKey(EtlValue, etl); Seq("x-api-key" -> EtlValue) }
    }
    private case object Recovery extends Caller {
        val name = "recovery key"
        def headers = { seedKey(RecoveryValue, recovery); Seq("x-api-key" -> RecoveryValue) }
    }
    private case object OpsChatAdmin extends Caller {
        val name = "ui key on behalf of an admin"
        def headers = { seedKey(UiValue, uiLegacy); Seq("x-api-key" -> UiValue, AuditActor.HeaderOnBehalfOf -> "root") }
    }
    private case object OpsChatEditor extends Caller {
        val name = "ui key on behalf of an editor"
        def headers = { seedKey(UiValue, uiLegacy); Seq("x-api-key" -> UiValue, AuditActor.HeaderOnBehalfOf -> "ed") }
    }
    private val trusted: Seq[Caller] = Seq(Recovery, OpsChatAdmin, OpsChatEditor)

    // ------------------------------------------------------------ one call through the chain

    private case class Run(tenant: Boolean, policy: Boolean, status: Int, entries: List[AuditEntry], queued: List[PendingAction]) {
        def spoofEvents: List[AuditEntry] = entries.filter(e => e.category == "security" && e.action == "spoofed-incident")
        def actionRows: List[AuditEntry] = entries.filter(e => e.resourceType.contains("tap") && e.category != "security" && e.category != "policy")
        def queuedRows: List[AuditEntry] = entries.filter(e => e.category == "policy" && e.action == "queued")
    }

    private def run(caller: Caller, p: String, incident: Option[String] = Some(Incident)): Run = {
        PolicyIO.currentOverride = policy(p)
        audit.clear()
        store.inserted.clear()
        val headers = caller.headers ++ Seq(AuditActor.HeaderAgentSession -> "mcp-session-1") ++ incident.map(AuditActor.HeaderIncident -> _)
        val (r, _) = call("POST", "/api/v1/tap/run", body = Some(Body), headers = headers)
        val resp = new Resp
        val ai = new AuditInterceptor()
        val t = new TenantInterceptor().preHandle(r, resp.response, null)
        val p2 = t && new PolicyInterceptor().preHandle(r, resp.response, handler)
        if (p2) {
            ai.preHandle(r, resp.response, handler)
            resp.status = 200
            ai.afterCompletion(r, resp.response, handler, null)
        }
        Run(t, p2, resp.status, audit.asScala.toList, store.inserted.toList)
    }

    private def incidentOn(e: AuditEntry): Option[String] = e.metadata.flatMap(m => Option(m.get("incidentId"))).map(_.getAsString)

    // ================================================================ a customer key

    test("a customer key's incident header puts no incidentId on its audit row, and the call is otherwise recorded normally") {
        val r = run(Customer, autoTapRun)
        assert(r.tenant && r.policy, "the call itself is not refused for the header: " + r)
        assert(r.actionRows.size == 1, r.entries.toString)
        val row = r.actionRows.head
        assert(row.outcome == "success" && row.actor.label == "etl-agent", row.toString)
        assert(incidentOn(row).isEmpty, "incidentId recorded from an untrusted caller: " + row.metadata)
    }

    test("a customer key's incident header does not change the recovery mode applied to its call") {
        val r = run(Customer, autoTapRunRecoveryOff)
        assert(r.tenant, r.toString)
        assert(r.policy, "the matrix says auto; recovery mode applies only to the platform's incident work: " + r.status)
        assert(r.status != 403, r.toString)
    }

    test("a customer key's incident header puts no incident id on the action it queues or on the queued audit row") {
        val r = run(Customer, approveTapRun)
        assert(!r.policy && r.status == 202, r.toString)
        assert(r.queued.size == 1, r.queued.toString)
        assert(r.queued.head.incidentId.isEmpty, "queued with an incident id: " + r.queued.head.incidentId)
        assert(r.queued.head.toPublicJson.get("incidentId") == null)
        assert(r.queuedRows.size == 1 && incidentOn(r.queuedRows.head).isEmpty, r.queuedRows.toString)
    }

    test("a customer key's incident header is recorded once as a spoofed-incident security event") {
        for (p <- Seq(autoTapRun, approveTapRun, autoTapRunRecoveryOff)) {
            val r = run(Customer, p)
            assert(r.spoofEvents.size == 1, s"$p: ${r.entries}")
            val e = r.spoofEvents.head
            assert(e.actor.label == "etl-agent", e.actor.toString)
            // the claimed incident id is not written as if it were the incident's own ledger entry
            assert(incidentOn(e).isEmpty, e.toString)
        }
    }

    test("a customer key with no incident header, or a blank one, records no spoof event") {
        for (h <- Seq(None, Some(""), Some("   "))) {
            val r = run(Customer, autoTapRun, incident = h)
            assert(r.tenant && r.policy, s"$h: $r")
            assert(r.spoofEvents.isEmpty, s"$h: ${r.entries}")
            assert(r.actionRows.forall(incidentOn(_).isEmpty))
        }
    }

    // ================================================================ the platform's own callers: as today

    test("unchanged: the recovery key and the ui key's incident header is on the audit row, with no spoof event") {
        for (c <- trusted) {
            val r = run(c, autoTapRun)
            assert(r.tenant && r.policy, c.name + ": " + r)
            assert(r.actionRows.size == 1, c.name + ": " + r.entries)
            assert(incidentOn(r.actionRows.head).contains(Incident), c.name + ": " + r.actionRows.head.metadata)
            assert(r.spoofEvents.isEmpty, c.name + ": " + r.entries)
        }
    }

    test("unchanged: the recovery mode applies to the recovery key's and the ui key's incident work") {
        for (c <- trusted) {
            val r = run(c, autoTapRunRecoveryOff)
            assert(r.tenant, c.name)
            assert(!r.policy && r.status == 403, c.name + ": recovery off must refuse incident work: " + r)
        }
    }

    test("unchanged: an action the recovery key or the ui key queues keeps its incident id") {
        for (c <- trusted) {
            val r = run(c, approveTapRun)
            assert(r.queued.size == 1, c.name + ": " + r)
            assert(r.queued.head.incidentId.contains(Incident), c.name)
            assert(r.queuedRows.size == 1 && incidentOn(r.queuedRows.head).contains(Incident), c.name + ": " + r.queuedRows)
            assert(r.spoofEvents.isEmpty, c.name)
        }
    }

    test("unchanged: with API keys off the incident header is honoured from any caller") {
        DatrisEnvironment.values = DatrisEnvironment.values.copy(useApiKeys = false)
        val noKey = new Caller { val name = "keys off"; def headers = Nil }
        val r = run(noKey, autoTapRun)
        assert(r.tenant && r.policy, r.toString)
        assert(r.actionRows.size == 1 && incidentOn(r.actionRows.head).contains(Incident), r.entries.toString)
        assert(r.spoofEvents.isEmpty)
        val q = run(noKey, approveTapRun)
        assert(q.queued.headOption.flatMap(_.incidentId).contains(Incident), q.toString)
    }

    // ================================================================ one accessor

    private def mainSources: Seq[Path] = {
        val rel = "src/main/scala/ai/datris"
        val root = Seq(Paths.get(rel), Paths.get("datrisserver", rel)).find(Files.isDirectory(_)).getOrElse(fail(rel + " not found"))
        Files.walk(root).iterator().asScala.filter(p => p.toString.endsWith(".scala")).toSeq
    }

    test("every read of the incident header (audit row, policy, queued action, tap version note) goes through the one trusted accessor") {
        val read = """getHeader\(\s*(ai\.datris\.audit\.)?AuditActor\.HeaderIncident\s*\)""".r
        val literal = "\"X-Datris-Incident\""
        val offenders = mainSources.filterNot(_.getFileName.toString == "AuditActor.scala").flatMap { p =>
            val src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
            val n = read.findAllIn(src).size + (if (src.contains(literal)) 1 else 0)
            if (n > 0) Some(p.getFileName.toString + " (" + n + ")") else None
        }
        assert(offenders.isEmpty, "read the incident header directly: " + offenders.mkString(", "))
    }
}
