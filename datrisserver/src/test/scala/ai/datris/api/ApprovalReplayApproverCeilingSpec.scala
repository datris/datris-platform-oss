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
  * (plans/stories/approval-replay-requester-scope.md), owner decision D1 (a),
  * for an approver who is a restricted user (limited to some catalogs). Such
  * a replay runs with the approver's own identity, so every check a
  * restricted session meets limits it; the requester's own identity (the
  * key, or the chat user's on-behalf-of identity, unchanged) is a second
  * limit beside it (`TenantInterceptor.replayAccess`), consulted at the
  * route gate and by the shared per-item checks (`CapabilityCheck.assertScope`,
  * `assertVisible`, `grants`), each with the requester's ordinary refusal.
  * An approver who is not restricted adds no limit: the replay runs with the
  * requester's identity (ApprovalReplayRequesterScopeSpec).
  *
  * End to end as in ApprovalReplayRequesterScopeSpec:
  * `ApprovalsAPIController.approve` with the approval in
  * [[FakePendingActionCollection]], and `PolicyReplay.execute` sending the
  * replay to a loopback stand-in that runs the real `TenantInterceptor`,
  * `CapabilityInterceptor`, `PolicyInterceptor`, `RoleEnforcementInterceptor`
  * and `AuditInterceptor` with API keys on, then the real controller checks:
  * DELETE /pipeline (`PipelineAPIController.deleteRefusal`) and, through
  * [[check]], the catalog rules of a save or move
  * (`PipelineAPIController.catalogForSave`), a catalog-level operation
  * (`RestrictedCatalogRules.catalogRefusal`) and a named-pipeline action
  * (`PipelineAPIController.refusalFor`). */
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
    @volatile private var lastSecondLimit: Option[ResolvedKey] = None
    @volatile private var lastOwnerLabel: Option[String] = None

    /** The controller check the stand-in applies to a request other than
      * DELETE /pipeline, after the interceptors: Some((status, body)) is the
      * route's refusal, None lets it run. */
    @volatile private var check: jakarta.servlet.http.HttpServletRequest => Option[(Int, String)] = (_: jakarta.servlet.http.HttpServletRequest) => None

    private def entity(e: org.springframework.http.ResponseEntity[String]): (Int, String) = (e.getStatusCode.value, e.getBody)

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
        lastSecondLimit = None
        lastOwnerLabel = None
        check = _ => None
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
        lastSecondLimit = CapabilityCheck.replaySecondLimit(r)
        // the label the save paths stamp as createdByKeyLabel
        lastOwnerLabel = ai.datris.auth.ResolvedKeyAccess.keyLabel(r)
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
            } else
                check(r).getOrElse {
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

    /** The identity a replay approved by `approver` (restricted) runs with. */
    private def approverOwn(approver: User): ResolvedKey = TenantInterceptor.onBehalfOfIdentity(uiLegacy, approver)

    private def assertApproverIdentity(approver: User, requester: ResolvedKey): Unit = {
        assert(lastIdentity.contains(approverOwn(approver)), String.valueOf(lastIdentity))
        assert(lastIdentity.exists(_.restrictedSession), String.valueOf(lastIdentity))
        assert(lastSecondLimit.contains(requester), String.valueOf(lastSecondLimit))
    }

    test("a key limited to what it created, deleting its own pipeline in a catalog the approver owns, runs; the key is the second limit") {
        queueDelete("pa_self_own", keyActor(selfDeleter), "self_sales")
        val res = approveAs(olga, "pa_self_own")
        assertRan("pa_self_own", res)
        assert(deleted.asScala.toList == List("self_sales"))
        assertApproverIdentity(olga, selfDeleter)
        // what the replay creates is owned by the identity: the approver
        assert(lastOwnerLabel.contains("session:olga"), String.valueOf(lastOwnerLabel))
        // the audit row names the approver, the approval and the requester
        val rows = audit.asScala.toList.filter(e => e.category != "security" && e.metadata.exists(_.has("approvalId")))
        assert(rows.size == 1, audit.toString)
        val row = rows.head
        assert(row.actor.label == "session:olga", row.actor.toString)
        val md = row.metadata.get
        assert(md.get("approvalId").getAsString == "pa_self_own", md.toString)
        assert(md.get("originalActor").getAsString == "self-deleter", md.toString)
        assert(md.get("approvedBy").getAsString == "olga", md.toString)
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

    test(
        "a key with unscoped delete and read limited to another catalog, approved by a restricted user who owns the pipeline's catalog, runs with the approver's identity"
    ) {
        queueDelete("pa_fin_reader", keyActor(finReader), "sales_orders")
        val res = approveAs(olga, "pa_fin_reader")
        assertRan("pa_fin_reader", res)
        assert(deleted.asScala.toList == List("sales_orders"))
        assertApproverIdentity(olga, finReader)
    }

    test("a restricted approver approving a delete in a catalog they hold no grant on, for an unscoped key, is refused with not found") {
        queueDelete("pa_ops_fin", keyActor(opsAgent), "fin_ledger")
        val res = approveAs(olga, "pa_ops_fin")
        assert(deleted.isEmpty, "a Sales-only approver had a Finance pipeline deleted")
        assertFailed("pa_ops_fin", res, 404)
        assert(record("pa_ops_fin").getString("resultBody") == notFoundBody("fin_ledger"))
        assertApproverIdentity(olga, opsAgent)
    }

    test("the same unscoped key's delete inside the restricted approver's grants runs") {
        queueDelete("pa_ops_sales", keyActor(opsAgent), "sales_orders")
        assertRan("pa_ops_sales", approveAs(olga, "pa_ops_sales"))
        assert(deleted.asScala.toList == List("sales_orders"))
    }

    test("an approver who is not restricted adds no second limit") {
        queueDelete("pa_ed", keyActor(salesDeleter), "sales_orders")
        assertRan("pa_ed", approveAs(ed, "pa_ed"))
        assert(lastSecondLimit.isEmpty, String.valueOf(lastSecondLimit))
        queueDelete("pa_root", keyActor(salesDeleter), "sales_orders")
        approveAs(root, "pa_root")
        assert(lastSecondLimit.isEmpty, String.valueOf(lastSecondLimit))
    }

    test("with a restricted approver a requesting key that is gone ends failed with 401 before anything runs") {
        val keys = (liveKeys ++ extraKeys) - "ops-agent"
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        queueDelete("pa_ops_gone", keyActor(opsAgent), "sales_orders")
        assertFailed("pa_ops_gone", approveAs(olga, "pa_ops_gone"), 401)
        assert(record("pa_ops_gone").getString("resultBody") == TenantInterceptor.RequesterKeyInvalidBody)
        assert(deleted.isEmpty && lastIdentity.isEmpty)
    }

    // ================================================================ checks a restricted session meets limit the replay

    /** The name PolicyGate reads from the request, as the replay sends it. */
    private def nameOf(method: String, path: String, body: Option[String]): Option[String] =
        ai.datris.policy.PolicyGate.resourceName(call(method, path, None, body)._1)

    private def queueRequest(id: String, actor: AuditActorInfo, method: String, path: String, body: String, action: String): Unit =
        PendingActionIO.insert(pending(id, actor, method, path, None, Some(body), action).copy(resourceName = nameOf(method, path, Some(body))))

    private def saveBody(cfg: PipelineConfig): String = "{\"name\":\"" + cfg.name + "\",\"catalog\":\"" + cfg.catalog + "\"}"

    private def catalogNotFound(c: String): String = RestrictedCatalogRules.catalogNotFound(c).getBody

    test(
        "an unscoped key's save or move into a catalog the restricted approver holds no grant on ends failed with not found; into the approver's catalog it runs"
    ) {
        @volatile var saving: PipelineConfig = null
        check = r => PipelineAPIController.catalogForSave(r, saving, pipelines.get(saving.name).orNull).left.toOption.map(entity)

        saving = PipelineConfig(name = "fin_new", catalog = "Finance")
        queueRequest("pa_save_fin", keyActor(oldAgent), "POST", CreatePath, saveBody(saving), "pipeline:create")
        assertFailed("pa_save_fin", approveAs(olga, "pa_save_fin"), 404)
        assert(record("pa_save_fin").getString("resultBody") == catalogNotFound("Finance"))
        assertApproverIdentity(olga, oldAgent)

        saving = PipelineConfig(name = "sales_orders", catalog = "Finance")
        queueRequest("pa_move_fin", keyActor(oldAgent), "POST", CreatePath, saveBody(saving), "pipeline:create")
        assertFailed("pa_move_fin", approveAs(olga, "pa_move_fin"), 404)
        assert(record("pa_move_fin").getString("resultBody") == catalogNotFound("Finance"))
        assert(handled.isEmpty, handled.toString)

        saving = PipelineConfig(name = "sales_new", catalog = "Sales")
        queueRequest("pa_save_sales", keyActor(oldAgent), "POST", CreatePath, saveBody(saving), "pipeline:create")
        assertRan("pa_save_sales", approveAs(olga, "pa_save_sales"))
        assert(handled.asScala.toList == List("POST " + CreatePath))
        assert(lastOwnerLabel.contains("session:olga"), String.valueOf(lastOwnerLabel))
    }

    test("an unscoped key's catalog-level operation on a catalog the restricted approver holds no grant on ends failed with not found") {
        @volatile var catalog = ""
        check = r => RestrictedCatalogRules.catalogRefusal(r, catalog, "delete").map(entity)

        catalog = "Finance"
        queueRequest("pa_cat_fin", keyActor(oldAgent), "PUT", "/api/v1/catalog/Finance", "{\"newName\":\"Fin2\"}", "pipeline:update")
        assertFailed("pa_cat_fin", approveAs(olga, "pa_cat_fin"), 404)
        assert(record("pa_cat_fin").getString("resultBody") == catalogNotFound("Finance"))
        assertApproverIdentity(olga, oldAgent)
        assert(handled.isEmpty, handled.toString)

        catalog = "Sales"
        queueRequest("pa_cat_sales", keyActor(oldAgent), "PUT", "/api/v1/catalog/Sales", "{\"newName\":\"Sales2\"}", "pipeline:update")
        assertRan("pa_cat_sales", approveAs(olga, "pa_cat_sales"))
    }

    private def renamePath(p: String): String = "/api/v1/pipelines/" + p + "/rename"

    test("an unscoped key's named-pipeline action outside the restricted approver's grants ends failed with not found") {
        @volatile var target = ""
        check = r => PipelineAPIController.refusalFor(r, target, pipelines(target), "delete").map(entity)

        target = "fin_ledger"
        queueRequest("pa_rename_fin", keyActor(oldAgent), "POST", renamePath(target), "{\"newName\":\"x\"}", "pipeline:delete")
        assertFailed("pa_rename_fin", approveAs(olga, "pa_rename_fin"), 404)
        assert(record("pa_rename_fin").getString("resultBody") == notFoundBody("fin_ledger"))
        assertApproverIdentity(olga, oldAgent)
        assert(handled.isEmpty, handled.toString)

        target = "sales_orders"
        queueRequest("pa_rename_sales", keyActor(oldAgent), "POST", renamePath(target), "{\"newName\":\"y\"}", "pipeline:delete")
        assertRan("pa_rename_sales", approveAs(olga, "pa_rename_sales"))
    }

    // ================================================================ the requester's own scope, with a restricted approver

    test(
        "a key's approved request on a route the key holds no capability for, approved by a restricted user who holds it, ends failed with the key's capability refusal"
    ) {
        val cfg = PipelineConfig(name = "sales_new", catalog = "Sales")
        queueRequest("pa_sd_create", keyActor(salesDeleter), "POST", CreatePath, saveBody(cfg), "pipeline:create")
        val res = approveAs(olga, "pa_sd_create")
        assert(handled.isEmpty, "the key created a pipeline it holds no capability to create")
        assertFailed("pa_sd_create", res, 403)
        assert(
            record("pa_sd_create").getString("resultBody") ==
                CapabilityInterceptor.deniedBody("sales-deleter", "pipeline", "create", "POST", CreatePath, None)
        )
        val md = gateDenials.head.metadata.get
        assert(md.get("approvalId").getAsString == "pa_sd_create" && md.get("originalActor").getAsString == "sales-deleter", md.toString)
    }

    /** The per-item check of a named-pipeline action through the shared
      * [[CapabilityCheck.assertScope]], answered as the controllers do. */
    private def scopeCheck(r: jakarta.servlet.http.HttpServletRequest, p: String): Option[(Int, String)] =
        try {
            CapabilityCheck.assertScope(r, "pipeline", "delete", ai.datris.util.CatalogOps.scopeContext(pipelines(p).catalog, pipelines(p).createdByKeyLabel))
            None
        } catch { case e: ai.datris.auth.CapabilityDeniedException => Some(entity(PipelineAPIController.capabilityDenied(e))) }

    test(
        "a catalog-limited key's approved action in another catalog, approved by a restricted user who owns it, is refused by the shared checks as a direct call by the key is"
    ) {
        val seen = new ConcurrentLinkedQueue[(Boolean, Boolean)]()
        def readable(r: jakarta.servlet.http.HttpServletRequest, p: String) =
            CapabilityCheck.grants(r, "pipeline", "read", ai.datris.util.CatalogOps.scopeContext(pipelines(p).catalog, pipelines(p).createdByKeyLabel))
        check = r => { seen.add((readable(r, "fin_ledger"), readable(r, "sales_orders"))); scopeCheck(r, "fin_ledger") }

        queueRequest("pa_sd_rename_fin", keyActor(salesDeleter), "POST", renamePath("fin_ledger"), "{\"newName\":\"x\"}", "pipeline:delete")
        val res = approveAs(pia, "pa_sd_rename_fin")
        assertApproverIdentity(pia, salesDeleter)
        assert(handled.isEmpty, handled.toString)

        ScopeDenialFixtures.install(serverEnv)
        val (direct, directReadable) =
            try {
                val (r, _) = ScopeDenialFixtures.request(salesDeleter, "POST", renamePath("fin_ledger"))
                (scopeCheck(r, "fin_ledger").getOrElse(fail("a direct call by the key is refused")), (readable(r, "fin_ledger"), readable(r, "sales_orders")))
            } finally ScopeDenialFixtures.install(approveEnv)
        assertFailed("pa_sd_rename_fin", res, direct._1)
        assert(record("pa_sd_rename_fin").getString("resultBody") == direct._2)
        assert(direct._2.contains("sales-deleter"), direct._2)
        // grants: the approver may read both, the key only Sales
        assert(seen.asScala.toList == List(directReadable) && directReadable == ((false, true)), seen.toString)
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
        assertApproverIdentity(oona, TenantInterceptor.onBehalfOfIdentity(uiLegacy, olga))
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
