package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.config.ChatAuthorityFixtures.{seedKey, uiLegacy, UiValue}
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.config.{AuditInterceptor, CapabilityInterceptor, PolicyInterceptor, RoleEnforcementInterceptor, TenantInterceptor}
import ai.datris.model.{Capability, DatrisEnvironment, PipelineConfig, ResolvedKey, TapConfig, TenantContext, User, UserContext}
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
  * a replay runs as it did on v1.61.0: with the approver's own identity (their
  * narrowed session bundle through the carrier), so every check a restricted
  * session meets limits it, and what it creates belongs to the approver.
  * Nothing else limits it. The requester is still checked first: a requesting
  * key that is gone or replaced, or a chat user who is gone, refuses 401 and
  * an unreadable store 503, before anything runs. An approver who is not
  * restricted: the replay runs with the requester's identity
  * (ApprovalReplayRequesterScopeSpec).
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
  * (`RestrictedCatalogRules.catalogRefusal`), a named-pipeline action
  * (`PipelineAPIController.refusalFor`) and a tap run's checks
  * (`TapAPIController.scopeRefusal`, `TapAPIController.targetPipelineRefusal`). */
class ApprovalReplayRestrictedApproverSpec extends AnyFunSuite with BeforeAndAfterEach {

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

    /** Runs taps; holds no pipeline capability at all. */
    private val tapRunner = key("tap-runner", "tap:read", "tap:run")

    /** May create pipelines only in Finance. */
    private val finCreator = key("fin-creator", "pipeline:read:catalog=Finance", "pipeline:create:catalog=Finance")

    /** Holds the capability to decide approvals. */
    private val decider = key("decider", "approval:read", "approval:decide")

    private val extraKeys: Map[String, ResolvedKey] =
        Seq(selfDeleter, finReader, secretWriter, tapRunner, finCreator, decider).map(k => k.label -> k).toMap

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

    private def approvePath(id: String): String = "/api/v1/approvals/" + id + "/approve"

    test("a restricted user's session does not hold approval:decide: with capabilities enforced the approve route refuses it at the gate") {
        ScopeDenialFixtures.install(serverEnv)
        val bundle = APIKeyValidator.resolveFromSession(olga)
        assert(!bundle.matchesResourceAction("approval", "decide"), bundle.capabilities.map(_.raw).toString)
        val (r, _) = call("POST", approvePath("pa_x"))
        val resp = new Resp
        UserContext.set(olga)
        try assert(!new CapabilityInterceptor().preHandle(r, resp.response, handlerOf(new ApprovalsAPIController, "approve")))
        finally UserContext.clear()
        assert(resp.status == 403, resp.status + " " + resp.body)
    }

    test(
        "a restricted editor's session sent with an API key that holds approval:decide reaches approve with capabilities enforced; the approval is the editor's and runs with the editor's identity"
    ) {
        queueDelete("pa_decider", keyActor(opsAgent), "sales_orders")
        ScopeDenialFixtures.install(serverEnv)
        seedKey("decider-value", decider)
        val (r, _) = call("POST", approvePath("pa_decider"), headers = Seq("x-api-key" -> "decider-value"))
        val resp = new Resp
        UserContext.set(olga)
        try {
            assert(new TenantInterceptor().preHandle(r, resp.response, null))
            assert(new CapabilityInterceptor().preHandle(r, resp.response, handlerOf(new ApprovalsAPIController, "approve")), resp.status + " " + resp.body)
            // the gate decided on the key, not on the editor's session bundle
            assert(ai.datris.auth.ResolvedKeyAccess.fromRequest(r).map(_.label).contains("decider"))
            ScopeDenialFixtures.install(approveEnv)
            val res = new ApprovalsAPIController().approve("pa_decider", r)
            assertRan("pa_decider", res)
        } finally UserContext.clear()
        assert(record("pa_decider").getString("decidedBy") == "olga", record("pa_decider").toJson)
        assert(deleted.asScala.toList == List("sales_orders"))
        assert(lastIdentity.contains(v161Identity(uiLegacy, olga)), String.valueOf(lastIdentity))
    }

    // ================================================================ the identity: v1.61.0's

    /** The identity v1.61.0 attached to the replay of an approval by `u`
      * (`TenantInterceptor.onBehalfOfIdentity(carrier, u, approvalReplay = true)`
      * as released), written out field by field. */
    private def v161Identity(carrier: ResolvedKey, u: User): ResolvedKey =
        if (u.role == User.RoleAdmin || !u.isRestricted) carrier.copy(label = "session:" + u.username)
        else {
            val bundle = APIKeyValidator.resolveFromSession(u)
            val narrowed =
                if (carrier.isLegacyFullAccess) bundle.capabilities
                else bundle.capabilities.filter(c => carrier.grants(c.resource, c.action, c.scope))
            carrier.copy(
                label = bundle.label,
                capabilities = narrowed,
                isLegacyFullAccess = false,
                restrictedSession = bundle.restrictedSession,
                inertCapabilities = Nil
            )
        }

    private def assertApproverIdentity(approver: User): Unit = {
        assert(lastIdentity.contains(v161Identity(uiLegacy, approver)), String.valueOf(lastIdentity))
        assert(lastIdentity.exists(_.restrictedSession), String.valueOf(lastIdentity))
    }

    test("a key's approval by a restricted user runs with the identity v1.61.0 attached, owned and audited as the approver's") {
        queueDelete("pa_ops_own", keyActor(opsAgent), "sales_orders")
        assertRan("pa_ops_own", approveAs(olga, "pa_ops_own"))
        assertApproverIdentity(olga)
        assert(lastOwnerLabel.contains("session:olga"), String.valueOf(lastOwnerLabel))
        val rows = audit.asScala.toList.filter(e => e.category != "security" && e.metadata.exists(_.has("approvalId")))
        assert(rows.size == 1, audit.toString)
        val row = rows.head
        assert(row.actor.label == "session:olga", row.actor.toString)
        val md = row.metadata.get
        assert(md.get("approvalId").getAsString == "pa_ops_own", md.toString)
        assert(md.get("originalActor").getAsString == "ops-agent", md.toString)
        assert(md.get("approvedBy").getAsString == "olga", md.toString)
    }

    test("a chat's approval by a restricted user runs with the identity v1.61.0 attached") {
        queueDelete("pa_olga_sales_oona", chatActor(olga), "sales_orders")
        assertRan("pa_olga_sales_oona", approveAs(oona, "pa_olga_sales_oona"))
        assert(deleted.asScala.toList == List("sales_orders"))
        assertApproverIdentity(oona)
        assert(lastOwnerLabel.contains("session:oona"), String.valueOf(lastOwnerLabel))
    }

    test("an approver who is not restricted: the replay runs with the requesting key's own identity") {
        queueDelete("pa_ed", keyActor(salesDeleter), "sales_orders")
        assertRan("pa_ed", approveAs(ed, "pa_ed"))
        assert(lastIdentity.contains(salesDeleter), String.valueOf(lastIdentity))
        queueDelete("pa_root", keyActor(salesDeleter), "sales_orders")
        assertRan("pa_root", approveAs(root, "pa_root"))
        assert(lastIdentity.contains(salesDeleter), String.valueOf(lastIdentity))
    }

    // ================================================================ the requester is checked first

    test("with a restricted approver a requesting key that is gone ends failed with 401 before anything runs") {
        val keys = (liveKeys ++ extraKeys) - "ops-agent"
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        queueDelete("pa_ops_gone", keyActor(opsAgent), "sales_orders")
        assertFailed("pa_ops_gone", approveAs(olga, "pa_ops_gone"), 401)
        assert(record("pa_ops_gone").getString("resultBody") == TenantInterceptor.RequesterKeyInvalidBody)
        assert(deleted.isEmpty && lastIdentity.isEmpty)
    }

    test("with a restricted approver a requesting key replaced under the same name ends failed with 401 before anything runs") {
        val keys = liveKeys ++ extraKeys + ("ops-agent" -> opsAgent.copy(keyId = Some("k_new")))
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        queueDelete("pa_ops_new", keyActor(opsAgent), "sales_orders")
        assertFailed("pa_ops_new", approveAs(olga, "pa_ops_new"), 401)
        assert(deleted.isEmpty && lastIdentity.isEmpty)
    }

    // ================================================================ held to the approver's catalogs

    test("a restricted approver approving a delete in a catalog they hold no grant on, for an unscoped key, is refused with not found") {
        queueDelete("pa_ops_fin", keyActor(opsAgent), "fin_ledger")
        val res = approveAs(olga, "pa_ops_fin")
        assert(deleted.isEmpty, "a Sales-only approver had a Finance pipeline deleted")
        assertFailed("pa_ops_fin", res, 404)
        assert(record("pa_ops_fin").getString("resultBody") == notFoundBody("fin_ledger"))
        assertApproverIdentity(olga)
    }

    test("a catalog-limited key's delete in a catalog the restricted approver only views is refused with not found") {
        queueDelete("pa_sales_by_pia", keyActor(salesDeleter), "sales_orders")
        assertFailed("pa_sales_by_pia", approveAs(pia, "pa_sales_by_pia"), 404)
        assert(record("pa_sales_by_pia").getString("resultBody") == notFoundBody("sales_orders"))
        assert(deleted.isEmpty)
    }

    test("a restricted user's chat approved by a restricted user who only views the catalog is refused with not found") {
        queueDelete("pa_olga_sales_pia", chatActor(olga), "sales_orders")
        assertFailed("pa_olga_sales_pia", approveAs(pia, "pa_olga_sales_pia"), 404)
        assert(deleted.isEmpty)
    }

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
        assertApproverIdentity(olga)

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
        assertApproverIdentity(olga)
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
        assertApproverIdentity(olga)
        assert(handled.isEmpty, handled.toString)

        target = "sales_orders"
        queueRequest("pa_rename_sales", keyActor(oldAgent), "POST", renamePath(target), "{\"newName\":\"y\"}", "pipeline:delete")
        assertRan("pa_rename_sales", approveAs(olga, "pa_rename_sales"))
    }

    // ================================================================ runs inside the approver's grants

    test("the same unscoped key's delete inside the restricted approver's grants runs") {
        queueDelete("pa_ops_sales", keyActor(opsAgent), "sales_orders")
        assertRan("pa_ops_sales", approveAs(olga, "pa_ops_sales"))
        assert(deleted.asScala.toList == List("sales_orders"))
    }

    test("a key limited to what it created, deleting its own pipeline in a catalog the restricted approver owns, runs") {
        queueDelete("pa_self_own", keyActor(selfDeleter), "self_sales")
        assertRan("pa_self_own", approveAs(olga, "pa_self_own"))
        assert(deleted.asScala.toList == List("self_sales"))
        assertApproverIdentity(olga)
    }

    private val salesTap = TapConfig(name = "sales_feed", description = "d", targetPipeline = "sales_orders", catalog = "Sales", createdByKeyLabel = "someone")

    test("a key with no pipeline capability whose approved tap run feeds a pipeline in the restricted approver's catalog runs") {
        check = r =>
            TapAPIController.scopeRefusal(r, salesTap, "run").orElse(TapAPIController.targetPipelineRefusal(r, salesTap, pipelines.apply)).map(entity)
        assert(!tapRunner.capabilities.exists(_.resource == "pipeline"))
        queueRequest("pa_tap_run", keyActor(tapRunner), "POST", "/api/v1/tap/run", "{\"name\":\"sales_feed\",\"mode\":\"run\"}", "tap:run")
        assertRan("pa_tap_run", approveAs(olga, "pa_tap_run"))
        assert(handled.asScala.toList == List("POST /api/v1/tap/run"))
        assertApproverIdentity(olga)
    }

    // ================================================================ released behaviour for an approver limited to some catalogs

    test(
        "released behaviour for an approver limited to some catalogs: a key limited to another catalog saving a pipeline into the approver's catalog runs, owned by the approver"
    ) {
        assert(!finCreator.grants("pipeline", "create", Map("catalog" -> "Sales")))
        val saving = PipelineConfig(name = "sales_new", catalog = "Sales")
        check = r => PipelineAPIController.catalogForSave(r, saving, null).left.toOption.map(entity)
        queueRequest("pa_fc_save", keyActor(finCreator), "POST", CreatePath, saveBody(saving), "pipeline:create")
        assertRan("pa_fc_save", approveAs(olga, "pa_fc_save"))
        assert(handled.asScala.toList == List("POST " + CreatePath))
        assert(lastOwnerLabel.contains("session:olga"), String.valueOf(lastOwnerLabel))
        assertApproverIdentity(olga)
    }

    test(
        "released behaviour for an approver limited to some catalogs: a key that may delete a pipeline but not read it deletes it when the approver owns its catalog"
    ) {
        assert(directRefusal(finReader, "sales_orders").isDefined, "a direct call by the key is refused")
        queueDelete("pa_fin_reader", keyActor(finReader), "sales_orders")
        assertRan("pa_fin_reader", approveAs(olga, "pa_fin_reader"))
        assert(deleted.asScala.toList == List("sales_orders"))
        assertApproverIdentity(olga)
    }

    // ================================================================ the route gate's audit row

    private val SecretPath = "/api/v1/secrets/tap-cred"

    private def queueSecretWrite(id: String, actor: AuditActorInfo): Unit =
        PendingActionIO.insert(pending(id, actor, "PUT", SecretPath, None, Some("{\"value\":\"x\"}"), "secret:write").copy(resourceName = Some("tap-cred")))

    private def gateDenials = audit.asScala.toList.filter(e => e.category == "security" && e.action == "denied")

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
