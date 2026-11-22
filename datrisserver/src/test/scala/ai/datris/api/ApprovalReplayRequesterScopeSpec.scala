package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.config.ChatAuthorityFixtures.{seedKey, uiLegacy, UiValue}
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.config.{AuditInterceptor, PolicyInterceptor, TenantInterceptor}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.policy.{FakePendingActionCollection, PendingAction, PendingActionIO, PolicyReplay}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, MongoDBUtil, PipelineDeletePlan, UserStore}
import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

/** Story: an approved agent action runs with the requester's access, and
  * internal headers count only from the platform's own callers
  * (plans/stories/approval-replay-requester-scope.md), Acceptance bullet 2,
  * owner decisions D1 (a) and D4.
  *
  * `ApprovalsAPIController.approve` end to end: the approval is stored in
  * [[FakePendingActionCollection]] behind `PendingActionIO.dbOverride`, and
  * `PolicyReplay.execute` sends the replay to a loopback stand-in for this
  * server (`PolicyReplay.port`). The stand-in runs the real
  * `TenantInterceptor`, `PolicyInterceptor` (which uses the token) and
  * `AuditInterceptor` with API keys on, adding the platform's `ui` key the
  * replay carries (the approve call itself runs with keys off so that
  * `PolicyReplay` reads no secret store), then the real DELETE /pipeline
  * scope check (`PipelineAPIController.deleteRefusal`); only when that
  * passes does it record the pipeline as deleted.
  *
  * Requesting keys are resolved through the assumed
  * `APIKeyValidator.resolveByLabelOverride` seam (see
  * ReplayRequesterIdentitySpec). */
class ApprovalReplayRequesterScopeSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var savedPort: Int = 0
    private var fake: FakePendingActionCollection = _
    private var server: HttpServer = _
    private var db: InMemoryUserDb = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()
    private val deleted = new ConcurrentLinkedQueue[String]()
    @volatile private var keys: Map[String, ResolvedKey] = liveKeys

    private val pipelines: Map[String, PipelineConfig] = Seq(
        PipelineConfig(name = "fin_ledger", catalog = "Finance", createdByKeyLabel = "someone"),
        PipelineConfig(name = "sales_orders", catalog = "Sales", createdByKeyLabel = "someone")
    ).map(p => p.name -> p).toMap

    private def baseEnv: DatrisEnvironment =
        InMemoryUserDb.env(useAuditLog = true).copy(useAgentPolicy = true, pendingActionTableName = "test-pending-action")

    /** What the approve call runs under: keys off (PolicyReplay sends no key). */
    private def approveEnv: DatrisEnvironment = baseEnv.copy(useApiKeys = false)

    /** What the replayed request is judged under: keys on. */
    private def serverEnv: DatrisEnvironment = baseEnv.copy(useApiKeys = true)

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedPort = PolicyReplay.port
        ScopeDenialFixtures.install(approveEnv)
        fake = new FakePendingActionCollection
        PendingActionIO.dbOverride = new MongoDBUtil(fake.database)
        PolicyInterceptor.store = PolicyInterceptor.MongoStore
        db = new InMemoryUserDb
        users.foreach(db.put)
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        keys = liveKeys
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        audit.clear()
        deleted.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => { audit.add(e); () }
        UserContext.clear()

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", (ex: HttpExchange) => standIn(ex))
        server.start()
        PolicyReplay.port = server.getAddress.getPort
    }

    override def afterEach(): Unit = {
        if (server != null) server.stop(0)
        PolicyReplay.port = savedPort
        APIKeyValidator.resolveByLabelOverride = null
        AuditLog.sinkOverride = null
        PendingActionIO.dbOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        APIKeyValidator.invalidateCache()
        ScopeDenialFixtures.install(saved)
        TenantContext.clear()
    }

    // ------------------------------------------------------------ the stand-in server

    private def standIn(ex: HttpExchange): Unit = {
        val (status, text) =
            try {
                ScopeDenialFixtures.install(serverEnv)
                try handle(ex)
                finally { ScopeDenialFixtures.install(approveEnv); TenantContext.clear() }
            } catch { case e: Throwable => (500, "stand-in failed: " + e) }
        val out = text.getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, out.length.toLong)
        ex.getResponseBody.write(out)
        ex.close()
    }

    private def handle(ex: HttpExchange): (Int, String) = {
        val h = ex.getRequestHeaders
        val headers = Seq("x-api-key" -> UiValue) ++
            Seq(AuditActor.HeaderOnBehalfOf, PolicyReplay.HeaderApproval).flatMap(n => Option(h.getFirst(n)).map(n -> _))
        val body = Option(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)).filter(_.nonEmpty)
        seedKey(UiValue, uiLegacy)
        val (r, _) = call(ex.getRequestMethod, ex.getRequestURI.getRawPath, Option(ex.getRequestURI.getRawQuery), body, headers)
        val resp = new Resp
        if (!new TenantInterceptor().preHandle(r, resp.response, null)) return (resp.status, resp.body)
        if (!new PolicyInterceptor().preHandle(r, resp.response, handler)) return (resp.status, resp.body)
        val ai = new AuditInterceptor()
        ai.preHandle(r, resp.response, handler)
        val name = Option(r.getParameter("pipeline")).getOrElse("")
        val answer = pipelines.get(name) match {
            case None => (404, "{\"error\":\"no pipeline " + name + "\"}")
            case Some(cfg) =>
                PipelineAPIController.deleteRefusal(r, cfg, PipelineDeletePlan.of("true", "true")) match {
                    case Some(refusal) => (refusal.getStatusCode.value, refusal.getBody)
                    case None =>
                        deleted.add(name)
                        (200, PipelineAPIController.deleteResponseBody(Nil, None))
                }
        }
        resp.status = answer._1
        ai.afterCompletion(r, resp.response, handler, null)
        answer
    }

    // ------------------------------------------------------------ the approval

    private def queued(id: String, actor: AuditActorInfo, pipeline: String): PendingAction = {
        val now = Instant.now()
        PendingAction(
            id = id,
            action = "pipeline:delete",
            resourceType = Some("pipeline"),
            resourceName = None,
            resourceVersion = None,
            actor = actor,
            reason = Some("cleanup"),
            agentSession = None,
            method = "DELETE",
            path = DeletePath,
            query = Some(deleteQuery(pipeline)),
            contentType = None,
            body = None,
            bodyHash = "h-" + id,
            createdAt = now,
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Pending,
            hashVersion = Some(PendingAction.CurrentHashVersion)
        )
    }

    private def approveAs(approver: User, id: String) = {
        val (req, _) = ScopeDenialFixtures.request(null, "POST", "/api/v1/approvals/" + id + "/approve")
        UserContext.set(approver)
        try new ApprovalsAPIController().approve(id, req)
        finally UserContext.clear()
    }

    private val ed: User = ai.datris.config.ChatAuthorityFixtures.ed
    private val root: User = ai.datris.config.ChatAuthorityFixtures.root

    private def record(id: String) = fake.doc(id).getOrElse(fail("no record " + id))

    private def assertFailedWith(id: String, res: org.springframework.http.ResponseEntity[String], status: Int): Unit = {
        assert(res.getStatusCode.value == 502, s"${res.getStatusCode} ${res.getBody}")
        val body = JsonParser.parseString(res.getBody).getAsJsonObject
        assert(body.get("state").getAsString == PendingAction.Failed, res.getBody)
        assert(body.get("resultStatus").getAsInt == status, res.getBody)
        val d = record(id)
        assert(d.getString("state") == PendingAction.Failed, d.toJson)
        assert(d.getInteger("resultStatus") == status, d.toJson)
        assert(!d.containsKey("replayTokenHash"), d.toJson)
    }

    // ================================================================

    test("an approved delete outside the requesting key's scope does not run; the approval ends failed with the key's 403 as a plain reason") {
        PendingActionIO.insert(queued("pa_fin", keyActor(salesDeleter), "fin_ledger"))
        val res = approveAs(ed, "pa_fin")
        assert(deleted.isEmpty, "the Finance pipeline was deleted")
        assertFailedWith("pa_fin", res, 403)
        val reason = JsonParser.parseString(record("pa_fin").getString("resultBody")).getAsJsonObject
        assert(reason.get("errorKind").getAsString == "capability_denied", reason.toString)
        val msg = reason.get("message").getAsString
        assert(msg.contains("sales-deleter") && msg.contains("pipeline:delete"), msg)
        assert(!msg.contains("Finance"), msg)
    }

    test("unchanged: an approved delete inside the requesting key's scope runs, attributed to the approver with the key as originalActor") {
        PendingActionIO.insert(queued("pa_sales", keyActor(salesDeleter), "sales_orders"))
        val res = approveAs(ed, "pa_sales")
        assert(res.getStatusCode.value == 200, res.getBody)
        assert(deleted.asScala.toList == List("sales_orders"))
        val d = record("pa_sales")
        assert(d.getString("state") == PendingAction.Executed, d.toJson)
        assert(d.getInteger("resultStatus") == 200, d.toJson)
        val rows = audit.asScala.toList
        val decision = rows.filter(e => e.category == "approval" && e.action == "approve")
        assert(decision.size == 1 && decision.head.actor.label == "session:ed", decision.toString)
        val action = rows.filter(e => e.resourceType.contains("pipeline") && e.category != "approval" && e.category != "security")
        assert(action.size == 1, rows.toString)
        assert(action.head.actor.actorType == "user" && action.head.actor.label == "session:ed", action.head.actor.toString)
        val md = action.head.metadata.getOrElse(fail("no metadata"))
        assert(md.get("originalActor").getAsString == "sales-deleter", md.toString)
        assert(md.get("approvedBy").getAsString == "ed", md.toString)
    }

    test("unchanged: an approved delete by an unscoped key runs, wherever the pipeline is") {
        PendingActionIO.insert(queued("pa_ops", keyActor(opsAgent), "fin_ledger"))
        val res = approveAs(ed, "pa_ops")
        assert(res.getStatusCode.value == 200, res.getBody)
        assert(deleted.asScala.toList == List("fin_ledger"))
    }

    test("a requesting key revoked between request and approval: nothing runs and the approval ends failed with 401") {
        PendingActionIO.insert(queued("pa_rev", keyActor(salesDeleter), "sales_orders"))
        keys = liveKeys - "sales-deleter"
        val res = approveAs(ed, "pa_rev")
        assert(deleted.isEmpty)
        assertFailedWith("pa_rev", res, 401)
        assert(!record("pa_rev").containsKey("replayConsumedAt"), "the token was not used")
    }

    test("a chat acting for a restricted user: an approved delete outside its grants does not run and ends failed") {
        PendingActionIO.insert(queued("pa_olga", chatActor(olga), "fin_ledger"))
        val res = approveAs(root, "pa_olga")
        assert(deleted.isEmpty, "a Finance pipeline was deleted for a Sales-only user")
        val st = JsonParser.parseString(res.getBody).getAsJsonObject.get("resultStatus").getAsInt
        assert(st == 404 || st == 403, res.getBody)
        assertFailedWith("pa_olga", res, st)
    }

    test("unchanged: a chat acting for an unrestricted editor runs its approved delete") {
        PendingActionIO.insert(queued("pa_ed", chatActor(ed), "fin_ledger"))
        val res = approveAs(root, "pa_ed")
        assert(res.getStatusCode.value == 200, res.getBody)
        assert(deleted.asScala.toList == List("fin_ledger"))
    }

    test("approving the same action twice runs it at most once") {
        PendingActionIO.insert(queued("pa_twice", keyActor(salesDeleter), "sales_orders"))
        assert(approveAs(ed, "pa_twice").getStatusCode.value == 200)
        assert(approveAs(ed, "pa_twice").getStatusCode.value == 409)
        assert(deleted.asScala.toList == List("sales_orders"))
    }
}
