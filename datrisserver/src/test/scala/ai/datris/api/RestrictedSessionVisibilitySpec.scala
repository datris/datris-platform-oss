package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResourceHiddenException}
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, CatalogOps, InMemoryUserDb, UserStore}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Catalog-scoped user access (server)
  * (plans/stories/catalog-scoped-user-access.md), Acceptance bullets 4 and 6.
  *
  * `GET /pipeline`, `GET /pipelines` and `POST /pipeline` read Mongo, so this
  * spec pins the pure seams each entry point runs on the loaded configs (the
  * same style as `PipelineAPIController.pipelineResponse` in
  * PipelineLookupApiSpec):
  * {{{
  * // ai.datris.auth
  * class ResourceHiddenException(msg: String) extends DatrisException(msg)
  * object CapabilityCheck {
  *   // as assertScope, but throws ResourceHiddenException when rk.restrictedSession
  *   def assertVisible(request: HttpServletRequest, resource: String, action: String, ctx: Map[String, String]): Unit
  * }
  * object PipelineAPIController {
  *   // GET /pipeline: null config -> 404 pipelineNotFound(name); hidden -> the same 404 body;
  *   // scope miss on an API key -> 403 capabilityDenied; else 200 (as pipelineResponse(config, name))
  *   def pipelineResponse(config: PipelineConfig, name: String, request: HttpServletRequest): ResponseEntity[String]
  *   // GET /pipelines: per-item CapabilityCheck.grants(request, "pipeline", "read",
  *   //   CatalogOps.scopeContext(c.catalog, c.createdByKeyLabel)); nulls dropped
  *   def visiblePipelines(request: HttpServletRequest, configs: Seq[PipelineConfig]): Seq[PipelineConfig]
  *   // POST /pipeline, Steps 6-7, before any write. existing = the stored config or null.
  *   // Right = the config to save (catalog filled in when defaulted); Left = the refusal.
  *   def catalogForSave(request: HttpServletRequest, config: PipelineConfig, existing: PipelineConfig): Either[ResponseEntity[String], PipelineConfig]
  * }
  * }}}
  * Bullet 6 (Observable PATCH + /me) runs through the real AuthAPIController
  * against an in-memory user table, with AuditLog captured at its sink.
  */
class RestrictedSessionVisibilitySpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey, method: String = "GET", path: String = "/api/v1/pipeline"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def restrictedSession(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private val salesKey: ResolvedKey =
        ResolvedKey(None, "sales-reader", Capability.parseList(Seq("pipeline:read:catalog=Sales")), isLegacyFullAccess = false)

    private def pipeline(name: String, catalog: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    // ------------------------------------------------------ Acceptance bullet 4

    test("GET /pipelines/{name} outside grants is 404 for a restricted session and 403 for a catalog-scoped API key") {
        val hr = pipeline("payroll", "HR")
        val ctx = CatalogOps.scopeContext(hr.catalog, hr.createdByKeyLabel)

        val session = req(restrictedSession(grants("Sales" -> "edit")))
        intercept[ResourceHiddenException](CapabilityCheck.assertVisible(session, "pipeline", "read", ctx))
        val hidden = PipelineAPIController.pipelineResponse(hr, "payroll", session)
        assert(hidden.getStatusCode.value == 404, hidden.getBody)
        assert(hidden.getBody == PipelineAPIController.pipelineNotFound("payroll").getBody, "hidden must be indistinguishable from missing: " + hidden.getBody)

        val key = req(salesKey)
        val denied = intercept[CapabilityDeniedException](CapabilityCheck.assertVisible(key, "pipeline", "read", ctx))
        assert(!denied.isInstanceOf[ResourceHiddenException])
        val forbidden = PipelineAPIController.pipelineResponse(hr, "payroll", key)
        assert(forbidden.getStatusCode.value == 403, forbidden.getBody)

        // Inside the grant both see it.
        val sales = pipeline("orders", "Sales")
        assert(PipelineAPIController.pipelineResponse(sales, "orders", session).getStatusCode.value == 200)
        assert(PipelineAPIController.pipelineResponse(sales, "orders", key).getStatusCode.value == 200)
    }

    test("an Uncataloged pipeline is 404 for a restricted session") {
        val loose = pipeline("loose", null)
        val r = PipelineAPIController.pipelineResponse(loose, "loose", req(restrictedSession(grants("Sales" -> "own"))))
        assert(r.getStatusCode.value == 404, r.getBody)
    }

    test("list excludes Uncataloged and non-granted catalogs") {
        val configs = Seq(
            pipeline("orders", "Sales"),
            pipeline("payroll", "HR"),
            pipeline("loose", null),
            pipeline("blank", ""),
            null,
            pipeline("leads", "Sales")
        )
        val seen = PipelineAPIController.visiblePipelines(req(restrictedSession(grants("Sales" -> "view"))), configs)
        assert(seen.map(_.name) == Seq("orders", "leads"), seen.map(_.name))
    }

    test("list is unchanged for an unrestricted session and still honours owner=self keys") {
        val configs = Seq(pipeline("orders", "Sales"), pipeline("mine", null, owner = "builder"), pipeline("theirs", null, owner = "other"))
        val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
        assert(PipelineAPIController.visiblePipelines(req(editor), configs).map(_.name) == Seq("orders", "mine", "theirs"))
        val ownerSelf = ResolvedKey(None, "builder", Capability.parseList(Seq("pipeline:read:owner=self")), isLegacyFullAccess = false)
        assert(PipelineAPIController.visiblePipelines(req(ownerSelf), configs).map(_.name) == Seq("mine"))
    }

    test("blank-catalog create with one editable catalog lands there") {
        val r = req(restrictedSession(grants("Sales" -> "edit", "HR" -> "view")), method = "POST")
        PipelineAPIController.catalogForSave(r, pipeline("new_orders", null), null) match {
            case Right(cfg) => assert(cfg.catalog == "Sales", cfg.catalog)
            case Left(refused) => fail("expected the Sales default, got " + refused.getStatusCode + " " + refused.getBody)
        }
        PipelineAPIController.catalogForSave(r, pipeline("new_orders2", ""), null) match {
            case Right(cfg) => assert(cfg.catalog == "Sales", cfg.catalog)
            case Left(refused) => fail("an empty catalog is blank too: " + refused.getBody)
        }
    }

    test("blank-catalog create with two editable catalogs is 400 naming both") {
        val r = req(restrictedSession(grants("Sales" -> "edit", "Ops" -> "own")), method = "POST")
        PipelineAPIController.catalogForSave(r, pipeline("new_orders", null), null) match {
            case Left(refused) =>
                assert(refused.getStatusCode.value == 400, refused.getBody)
                val msg = errorOf(refused)
                assert(msg.startsWith("Choose a catalog:"), msg)
                assert(msg.contains("Sales") && msg.contains("Ops"), msg)
            case Right(cfg) => fail("two editable catalogs must not pick one silently; got " + cfg.catalog)
        }
    }

    test("blank-catalog create for an unrestricted user keeps the blank catalog") {
        val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
        PipelineAPIController.catalogForSave(req(editor, method = "POST"), pipeline("loose", null), null) match {
            case Right(cfg) => assert(cfg.catalog == null || cfg.catalog.isEmpty, String.valueOf(cfg.catalog))
            case Left(refused) => fail("unrestricted create must be unchanged: " + refused.getBody)
        }
    }

    // ------------------------------------------------------ Acceptance bullet 6 (Observable)

    private var savedEnv: DatrisEnvironment = _
    private var db: InMemoryUserDb = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()
    private val adminUser = User("admin", "admin-hash", User.RoleAdmin, null, null, null)

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        db = new InMemoryUserDb
        UserStore.dbOverride = db
        captured.clear()
        AuditLog.sinkOverride = (a: AuditEntry) => captured.synchronized { captured += a; () }
        db.put(adminUser)
        db.put(User("bob", "bob-hash", User.RoleEditor, null, null, null))
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    private def patchAsAdmin(username: String, body: String): ResponseEntity[String] = {
        UserContext.set(adminUser)
        val r = req(APIKeyValidator.resolveFromSession(adminUser), method = "PATCH", path = "/api/v1/auth/users/" + username)
        try new AuthAPIController().patchUser(username, body, r)
        finally UserContext.clear()
    }

    private def meAs(u: User): com.google.gson.JsonObject = {
        UserContext.set(u)
        try JsonParser.parseString(new AuthAPIController().me().getBody).getAsJsonObject
        finally UserContext.clear()
    }

    private def md(e: AuditEntry, k: String): Option[String] =
        e.metadata.flatMap(m => Option(m.get(k))).filterNot(_.isJsonNull).map(_.getAsString)

    test("PATCH restricted + catalogGrants writes user/set-restricted and user/grant-catalog rows") {
        val resp = patchAsAdmin("bob", """{"restricted":true,"catalogGrants":{"Sales":"edit"}}""")
        assert(resp.getStatusCode.value == 200, resp.getBody)

        val stored = db.user("bob").get
        assert(stored.restricted)
        assert(stored.catalogGrants.asScala.toMap == Map("Sales" -> "edit"))

        val rows = captured.filter(_.category == "user").toSeq
        val actions = rows.map(_.action)
        assert(actions.contains("set-restricted"), s"captured: $actions")
        assert(actions.contains("grant-catalog"), s"captured: $actions")
        assert(rows.forall(_.resourceName.contains("bob")), rows.map(_.resourceName))
        val grant = rows.find(_.action == "grant-catalog").get
        assert(md(grant, "catalog").contains("Sales"), s"metadata: ${grant.metadata}")
        assert(md(grant, "level").contains("edit"), s"metadata: ${grant.metadata}")
    }

    test("/me as a restricted user returns restricted and catalogGrants") {
        assert(patchAsAdmin("bob", """{"restricted":true,"catalogGrants":{"Sales":"edit"}}""").getStatusCode.value == 200)
        val me = meAs(UserStore.find("bob").get)
        assert(me.has("restricted") && me.get("restricted").getAsBoolean, me.toString)
        assert(me.has("catalogGrants") && me.getAsJsonObject("catalogGrants").get("Sales").getAsString == "edit", me.toString)
    }

    test("an admin's /me never shows restricted: true and PATCH restricted on an admin is 400") {
        val resp = patchAsAdmin("admin", """{"restricted":true}""")
        assert(resp.getStatusCode.value == 400, resp.getBody)
        assert(db.user("admin").exists(!_.restricted))
        // Even a stored flag (hand-edited document) is not shown for an admin.
        val flagged = adminUser.copy(restricted = true, catalogGrants = grants("Sales" -> "view"))
        val me = meAs(flagged)
        assert(!(me.has("restricted") && !me.get("restricted").isJsonNull && me.get("restricted").getAsBoolean), me.toString)
    }

    test("an existing user document with no grant fields reads restricted=false on /me") {
        db.putJson("""{"username":"legacy","passwordHash":"h","role":"viewer","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}""")
        val me = meAs(UserStore.find("legacy").get)
        assert(me.get("username").getAsString == "legacy" && me.get("role").getAsString == "viewer", me.toString)
        assert(me.has("restricted") && !me.get("restricted").getAsBoolean, me.toString)
    }
}
