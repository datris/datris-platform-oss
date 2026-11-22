package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityCheck, ScopeDenialFixtures}
import ai.datris.config.ChatAuthorityFixtures.{seedKey, uiLegacy, UiValue}
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.config.{AuditInterceptor, CapabilityInterceptor, PolicyInterceptor, RoleEnforcementInterceptor, TenantInterceptor}
import ai.datris.model.{Capability, DatrisEnvironment, PipelineConfig, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.policy.{FakePendingActionCollection, PendingAction, PendingActionIO, PolicyReplay}
import ai.datris.util.InMemoryUserDb.grants
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

/** Story: an approved agent action runs with the requester's access
  * (plans/stories/approval-replay-requester-scope.md), owner decision D1 (a):
  * the replay runs with the requester's own identity, never more than the
  * requester has, and a restricted approver (a signed-in user limited to
  * some catalogs) cannot approve beyond their own grants. The approver's
  * bundle is a second limit beside the requester's identity
  * (`TenantInterceptor.replayCeiling`), consulted at the route gate and by
  * the shared per-item checks; it is never folded into the identity, so the
  * rules for keys apply to a key requester exactly as on a direct call.
  *
  * End to end as in ApprovalReplayRequesterScopeSpec:
  * `ApprovalsAPIController.approve` with the approval in
  * [[FakePendingActionCollection]], and `PolicyReplay.execute` sending the
  * replay to a loopback stand-in that runs the real `TenantInterceptor`,
  * `CapabilityInterceptor`, `PolicyInterceptor`, `RoleEnforcementInterceptor`
  * and `AuditInterceptor` with API keys on, then the real DELETE /pipeline
  * check (`PipelineAPIController.deleteRefusal`). */
class ApprovalReplayApproverCeilingSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ------------------------------------------------------------ people and keys

    /** Restricted editor: owns Finance, views Sales. */
    private val pia: User =
        User("pia", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Finance" -> "own", "Sales" -> "view"))

    /** Restricted editor: owns Sales. */
    private val oona: User = User("oona", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))

    private val root: User = ai.datris.config.ChatAuthorityFixtures.root
    private val ed: User = ai.datris.config.ChatAuthorityFixtures.ed

    private def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false, keyId = Some("k_" + label))

    /** Limited to what it created. */
    private val selfDeleter = key("self-deleter", "pipeline:read:owner=self", "pipeline:delete:owner=self")

    /** Unscoped delete, read only in Finance. */
    private val finReader = key("fin-reader", "pipeline:delete", "pipeline:read:catalog=Finance")

    private val secretWriter = key("secret-writer", "secret:write")

    private val extraKeys: Map[String, ResolvedKey] = Seq(selfDeleter, finReader, secretWriter).map(k => k.label -> k).toMap

    // ------------------------------------------------------------ harness

    private var saved: DatrisEnvironment = _
    private var savedPort: Int = 0
    private var fake: FakePendingActionCollection = _
    private var server: HttpServer = _
    private var db: InMemoryUserDb = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()
    private val deleted = new ConcurrentLinkedQueue[String]()
    private val handled = new ConcurrentLinkedQueue[String]()
    @volatile private var lastIdentity: Option[ResolvedKey] = None
    @volatile private var lastCeiling: Option[ResolvedKey] = None

    private val pipelines: Map[String, PipelineConfig] = Seq(
        PipelineConfig(name = "fin_ledger", catalog = "Finance", createdByKeyLabel = "someone"),
        PipelineConfig(name = "sales_orders", catalog = "Sales", createdByKeyLabel = "someone"),
        PipelineConfig(name = "self_sales", catalog = "Sales", createdByKeyLabel = "self-deleter")
    ).map(p => p.name -> p).toMap

    private val fullDelete = PipelineDeletePlan.of("true", "true")

    private def baseEnv: DatrisEnvironment =
        InMemoryUserDb.env(useAuditLog = true).copy(useAgentPolicy = true, pendingActionTableName = "test-pending-action")
    private def approveEnv: DatrisEnvironment = baseEnv.copy(useApiKeys = false)
    private def serverEnv: DatrisEnvironment = baseEnv.copy(useApiKeys = true)

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedPort = PolicyReplay.port
        ScopeDenialFixtures.install(approveEnv)
        fake = new FakePendingActionCollection
        PendingActionIO.dbOverride = new MongoDBUtil(fake.database)
        PolicyInterceptor.store = PolicyInterceptor.MongoStore
        db = new InMemoryUserDb
        (users ++ Seq(pia, oona)).foreach(db.put)
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        val keys = liveKeys ++ extraKeys
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        audit.clear()
        deleted.clear()
        handled.clear()
        lastIdentity = None
        lastCeiling = None
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

    private def handlerOf(bean: AnyRef, name: String) =
        new org.springframework.web.method.HandlerMethod(bean, bean.getClass.getMethods.find(_.getName == name).get)

    private def handle(ex: HttpExchange): (Int, String) = {
        val h = ex.getRequestHeaders
        val headers = Seq("x-api-key" -> UiValue) ++
            Seq(AuditActor.HeaderOnBehalfOf, PolicyReplay.HeaderApproval).flatMap(n => Option(h.getFirst(n)).map(n -> _))
        val body = Option(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)).filter(_.nonEmpty)
        seedKey(UiValue, uiLegacy)
        val (r, _) = call(ex.getRequestMethod, ex.getRequestURI.getRawPath, Option(ex.getRequestURI.getRawQuery), body, headers)
        val resp = new Resp
        val path = ex.getRequestURI.getRawPath
        val secret = "/api/v1/secrets/([^/]+)".r
        val hm = (ex.getRequestMethod, path) match {
            case ("DELETE", DeletePath) => handlerOf(new PipelineAPIController, "deletePipeline")
            case ("PUT", secret(n)) =>
                r.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map("name" -> n).asJava)
                handlerOf(new SecretsAPIController, "putSecret")
            case _ => handler
        }
        if (!new TenantInterceptor().preHandle(r, resp.response, null)) return (resp.status, resp.body)
        lastIdentity = ai.datris.auth.ResolvedKeyAccess.fromRequest(r)
        lastCeiling = CapabilityCheck.approverCeiling(r)
        if (!new CapabilityInterceptor().preHandle(r, resp.response, hm)) return (resp.status, resp.body)
        if (!new PolicyInterceptor().preHandle(r, resp.response, hm)) return (resp.status, resp.body)
        if (!new RoleEnforcementInterceptor().preHandle(r, resp.response, hm)) return (resp.status, resp.body)
        val auditI = new AuditInterceptor()
        auditI.preHandle(r, resp.response, hm)
        val answer =
            if (ex.getRequestMethod == "DELETE" && path == DeletePath) {
                val name = Option(r.getParameter("pipeline")).getOrElse("")
                pipelines.get(name) match {
                    case None => (404, "{\"error\":\"no pipeline " + name + "\"}")
                    case Some(cfg) =>
                        PipelineAPIController.deleteRefusal(r, cfg, fullDelete) match {
                            case Some(refusal) => (refusal.getStatusCode.value, refusal.getBody)
                            case None =>
                                deleted.add(name)
                                (200, PipelineAPIController.deleteResponseBody(Nil, None))
                        }
                }
            } else {
                handled.add(ex.getRequestMethod + " " + path)
                (200, "{\"status\":\"ok\"}")
            }
        resp.status = answer._1
        auditI.afterCompletion(r, resp.response, hm, null)
        answer
    }

    // ------------------------------------------------------------ approvals

    private def pending(id: String, actor: AuditActorInfo, method: String, path: String, query: Option[String], body: Option[String], action: String) = {
        val now = Instant.now()
        PendingAction(
            id = id,
            action = action,
            resourceType = Some(action.takeWhile(_ != ':')),
            resourceName = None,
            resourceVersion = None,
            actor = actor,
            reason = Some("cleanup"),
            agentSession = None,
            method = method,
            path = path,
            query = query,
            contentType = body.map(_ => "application/json"),
            body = body,
            bodyHash = "h-" + id,
            createdAt = now,
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Pending,
            hashVersion = Some(PendingAction.CurrentHashVersion)
        )
    }

    private def queueDelete(id: String, actor: AuditActorInfo, pipeline: String): Unit =
        PendingActionIO.insert(pending(id, actor, "DELETE", DeletePath, Some(deleteQuery(pipeline)), None, "pipeline:delete"))

    private def approveAs(approver: User, id: String) = {
        ScopeDenialFixtures.install(approveEnv)
        val (req, _) = ScopeDenialFixtures.request(null, "POST", "/api/v1/approvals/" + id + "/approve")
        UserContext.set(approver)
        try new ApprovalsAPIController().approve(id, req)
        finally UserContext.clear()
    }

    private def record(id: String) = fake.doc(id).getOrElse(fail("no record " + id))

    private def assertFailed(id: String, res: org.springframework.http.ResponseEntity[String], status: Int): Unit = {
        assert(res.getStatusCode.value == 502, s"${res.getStatusCode} ${res.getBody}")
        val d = record(id)
        assert(d.getString("state") == PendingAction.Failed, d.toJson)
        assert(d.getInteger("resultStatus") == status, d.toJson)
    }

    private def assertRan(id: String, res: org.springframework.http.ResponseEntity[String]): Unit = {
        assert(res.getStatusCode.value == 200, res.getBody)
        assert(record(id).getString("state") == PendingAction.Executed, record(id).toJson)
    }

    /** What DELETE /pipeline answers `rk` calling it directly (no approval). */
    private def directRefusal(rk: ResolvedKey, pipeline: String): Option[(Int, String)] = {
        ScopeDenialFixtures.install(serverEnv)
        try {
            val (r, _) = ScopeDenialFixtures.request(rk, "DELETE", DeletePath)
            PipelineAPIController.deleteRefusal(r, pipelines(pipeline), fullDelete).map(e => (e.getStatusCode.value, e.getBody))
        } finally ScopeDenialFixtures.install(approveEnv)
    }

    private val notFoundBody: String => String = n => PipelineAPIController.pipelineNotFound(n).getBody

    // ================================================================ reachability

    test("a restricted user's session does not hold approval:decide: with capabilities enforced the approve route refuses it at the gate") {
        ScopeDenialFixtures.install(serverEnv)
        val bundle = APIKeyValidator.resolveFromSession(olga)
        assert(!bundle.matchesResourceAction("approval", "decide"), bundle.capabilities.map(_.raw).toString)
        val (r, _) = call("POST", "/api/v1/approvals/pa_x/approve")
        val resp = new Resp
        UserContext.set(olga)
        try assert(!new CapabilityInterceptor().preHandle(r, resp.response, handlerOf(new ApprovalsAPIController, "approve")))
        finally UserContext.clear()
        assert(resp.status == 403, resp.status + " " + resp.body)
    }

    // ================================================================ a key requester with a restricted approver

    test("a key limited to what it created, deleting its own pipeline in a catalog the approver owns, runs with the key's identity") {
        queueDelete("pa_self_own", keyActor(selfDeleter), "self_sales")
        val res = approveAs(olga, "pa_self_own")
        assertRan("pa_self_own", res)
        assert(deleted.asScala.toList == List("self_sales"))
        assert(lastIdentity.contains(selfDeleter), String.valueOf(lastIdentity))
        assert(lastCeiling.contains(APIKeyValidator.resolveFromSession(olga)), String.valueOf(lastCeiling))
    }

    test("the same key on a pipeline it did not create ends failed with the key's ordinary refusal") {
        queueDelete("pa_self_other", keyActor(selfDeleter), "sales_orders")
        val res = approveAs(olga, "pa_self_other")
        assert(deleted.isEmpty)
        val (st, body) = directRefusal(selfDeleter, "sales_orders").getOrElse(fail("a direct call by the key is refused"))
        assertFailed("pa_self_other", res, st)
        assert(record("pa_self_other").getString("resultBody") == body)
    }

    test("a key limited to one catalog, approved by a user limited to another, is refused in either catalog") {
        queueDelete("pa_sales_by_pia", keyActor(salesDeleter), "sales_orders")
        val inKeyScope = approveAs(pia, "pa_sales_by_pia")
        assertFailed("pa_sales_by_pia", inKeyScope, 404)
        assert(record("pa_sales_by_pia").getString("resultBody") == notFoundBody("sales_orders"))

        queueDelete("pa_fin_by_pia", keyActor(salesDeleter), "fin_ledger")
        val inApproverScope = approveAs(pia, "pa_fin_by_pia")
        val (st, body) = directRefusal(salesDeleter, "fin_ledger").getOrElse(fail("a direct call by the key is refused"))
        assertFailed("pa_fin_by_pia", inApproverScope, st)
        assert(record("pa_fin_by_pia").getString("resultBody") == body)
        assert(deleted.isEmpty)
    }

    test("a key with unscoped delete and read limited to another catalog is refused exactly as a direct call by that key is") {
        queueDelete("pa_fin_reader", keyActor(finReader), "sales_orders")
        val res = approveAs(olga, "pa_fin_reader")
        assert(deleted.isEmpty, "a key that cannot read the pipeline deleted it")
        val (st, body) = directRefusal(finReader, "sales_orders").getOrElse(fail("a direct call by the key is refused"))
        assert(st == 403, body)
        assertFailed("pa_fin_reader", res, st)
        assert(record("pa_fin_reader").getString("resultBody") == body)
    }

    test("a restricted approver approving a delete in a catalog they hold no grant on, for an unscoped key, is refused with not found") {
        queueDelete("pa_ops_fin", keyActor(opsAgent), "fin_ledger")
        val res = approveAs(olga, "pa_ops_fin")
        assert(deleted.isEmpty, "a Sales-only approver had a Finance pipeline deleted")
        assertFailed("pa_ops_fin", res, 404)
        assert(record("pa_ops_fin").getString("resultBody") == notFoundBody("fin_ledger"))
        assert(lastIdentity.contains(opsAgent), String.valueOf(lastIdentity))
    }

    test("the same unscoped key's delete inside the restricted approver's grants runs") {
        queueDelete("pa_ops_sales", keyActor(opsAgent), "sales_orders")
        assertRan("pa_ops_sales", approveAs(olga, "pa_ops_sales"))
        assert(deleted.asScala.toList == List("sales_orders"))
    }

    test("an approver who is not restricted adds no second limit") {
        queueDelete("pa_ed", keyActor(salesDeleter), "sales_orders")
        assertRan("pa_ed", approveAs(ed, "pa_ed"))
        assert(lastCeiling.isEmpty, String.valueOf(lastCeiling))
        queueDelete("pa_root", keyActor(salesDeleter), "sales_orders")
        approveAs(root, "pa_root")
        assert(lastCeiling.isEmpty, String.valueOf(lastCeiling))
    }

    // ================================================================ a chat requester with a restricted approver

    test("a restricted user's chat approved by a restricted user is held to both users' grants") {
        // olga owns Sales; pia owns Finance and only views Sales
        queueDelete("pa_olga_sales_pia", chatActor(olga), "sales_orders")
        assertFailed("pa_olga_sales_pia", approveAs(pia, "pa_olga_sales_pia"), 404)
        queueDelete("pa_olga_fin_pia", chatActor(olga), "fin_ledger")
        assertFailed("pa_olga_fin_pia", approveAs(pia, "pa_olga_fin_pia"), 404)
        assert(deleted.isEmpty)

        // oona owns Sales too: both allow it
        queueDelete("pa_olga_sales_oona", chatActor(olga), "sales_orders")
        assertRan("pa_olga_sales_oona", approveAs(oona, "pa_olga_sales_oona"))
        assert(deleted.asScala.toList == List("sales_orders"))
        assert(lastIdentity.exists(rk => rk.label == "session:olga" && rk.restrictedSession), String.valueOf(lastIdentity))
        assert(lastCeiling.contains(APIKeyValidator.resolveFromSession(oona)), String.valueOf(lastCeiling))
    }

    // ================================================================ the route gate and its audit row

    private val SecretPath = "/api/v1/secrets/tap-cred"

    private def queueSecretWrite(id: String, actor: AuditActorInfo): Unit =
        PendingActionIO.insert(pending(id, actor, "PUT", SecretPath, None, Some("{\"value\":\"x\"}"), "secret:write").copy(resourceName = Some("tap-cred")))

    private def gateDenials = audit.asScala.toList.filter(e => e.category == "security" && e.action == "denied")

    test("a key's approved request on a route the restricted approver's bundle does not hold is refused at the gate, audited with the approval and requester") {
        queueSecretWrite("pa_sw_olga", keyActor(secretWriter))
        val res = approveAs(olga, "pa_sw_olga")
        assert(handled.isEmpty, "a secret was written beyond the approver's own access")
        assertFailed("pa_sw_olga", res, 403)
        val reason = JsonParser.parseString(record("pa_sw_olga").getString("resultBody")).getAsJsonObject
        assert(reason.get("errorKind").getAsString == "capability_denied" && reason.get("key").getAsString == "session:olga", reason.toString)
        val denied = gateDenials
        assert(denied.size == 1, audit.toString)
        val md = denied.head.metadata.get
        assert(md.get("approvalId").getAsString == "pa_sw_olga" && md.get("originalActor").getAsString == "secret-writer", md.toString)
    }

    test("a replay refused at the gate for the requester's own access is audited with the approval and requester") {
        queueSecretWrite("pa_sd_root", keyActor(salesDeleter))
        assertFailed("pa_sd_root", approveAs(root, "pa_sd_root"), 403)
        val denied = gateDenials
        assert(denied.size == 1, audit.toString)
        val md = denied.head.metadata.get
        assert(md.get("approvalId").getAsString == "pa_sd_root" && md.get("originalActor").getAsString == "sales-deleter", md.toString)
        assert(md.get("required").getAsString == "secret:write", md.toString)
    }

    test("a capability denial on an ordinary request carries no approval fields") {
        ScopeDenialFixtures.install(serverEnv)
        seedKey("sales-value", salesDeleter)
        val (r, _) = call("PUT", SecretPath, body = Some("{\"value\":\"x\"}"), headers = Seq("x-api-key" -> "sales-value"))
        val resp = new Resp
        assert(new TenantInterceptor().preHandle(r, resp.response, null))
        assert(!new CapabilityInterceptor().preHandle(r, resp.response, handlerOf(new SecretsAPIController, "putSecret")))
        val md = gateDenials.head.metadata.get
        assert(!md.has("approvalId") && !md.has("originalActor"), md.toString)
    }
}
