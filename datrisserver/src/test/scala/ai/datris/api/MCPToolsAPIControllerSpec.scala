package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.MCPToolRoutes
import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, ResolvedKey, User, UserContext}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: GET /api/v1/mcp/tools answers a logged-in session instead of 401
  * (plans/stories/mcp-tools-session-401.md). With user auth and keys on, a
  * cookie-only request reaches `listMcpTools` with no ResolvedKey attached
  * (the route is skip-class, so CapabilityInterceptor's lazy session fallback
  * never runs) and only `UserContext` carries the identity. The controller
  * must answer that session with its role's tools; a presented key (valid or
  * rejected) and the no-identity case keep today's behaviour. */
class MCPToolsAPIControllerSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val admin = User("alice", "h", User.RoleAdmin, null, null, null)
    private val ed = User("ed", "h", User.RoleEditor, null, null, null)
    private val vic = User("vic", "h", User.RoleViewer, null, null, null)
    private val bob = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))

    override def beforeEach(): Unit = UserContext.clear()
    override def afterEach(): Unit = UserContext.clear()

    /** A request whose attributes come from a fixed map (no x-api-key header
      * is consulted by the controller; only interceptor-set attributes). */
    private def request(attrs: (String, AnyRef)*): HttpServletRequest = {
        val m = Map[String, AnyRef](attrs: _*)
        val r = mock(classOf[HttpServletRequest])
        when(r.getAttribute(anyString())).thenAnswer(i => m.getOrElse(i.getArgument[String](0), null))
        r
    }

    private case class Answer(status: Int, body: String) {
        lazy val json: JsonObject = JsonParser.parseString(body).getAsJsonObject
        def filtered: Boolean = json.get("filtered").getAsBoolean
        def tools: Seq[String] = json.getAsJsonArray("tools").asScala.map(_.getAsString).toSeq
    }

    private def call(r: HttpServletRequest): Answer = {
        val resp = new MCPToolsAPIController().listMcpTools(r)
        Answer(resp.getStatusCode.value, resp.getBody)
    }

    test("cookie-only admin session gets 200, filtered=false and the whole catalog") {
        UserContext.set(admin)
        val a = call(request())
        assert(a.status == 200, s"body: ${a.body}")
        assert(!a.filtered)
        assert(a.tools == MCPToolRoutes.allToolNames)
    }

    test("cookie-only editor session gets 200, filtered=true and exactly its role bundle's tools") {
        UserContext.set(ed)
        val a = call(request())
        assert(a.status == 200, s"body: ${a.body}")
        assert(a.filtered)
        assert(a.tools == MCPToolRoutes.allowedTools(APIKeyValidator.resolveFromSession(ed)))
        assert(a.tools.contains("create_pipeline"))
        assert(!a.tools.contains("update_secret"))
    }

    test("cookie-only viewer session sees reads and no mutating tools") {
        UserContext.set(vic)
        val a = call(request())
        assert(a.status == 200, s"body: ${a.body}")
        assert(a.filtered)
        assert(a.tools == MCPToolRoutes.allowedTools(APIKeyValidator.resolveFromSession(vic)))
        assert(a.tools.contains("list_pipelines"))
        assert(!a.tools.contains("create_pipeline"))
    }

    test("a ResolvedKey on the request wins over the session user") {
        UserContext.set(admin)
        val key = ResolvedKey(None, "builder", Capability.parseList(Seq("pipeline:read")), isLegacyFullAccess = false)
        val a = call(request(TenantInterceptor.ResolvedKeyAttr -> key))
        assert(a.status == 200, s"body: ${a.body}")
        assert(a.filtered, "the key's scope applies, not the admin session's full catalog")
        assert(a.tools == MCPToolRoutes.allowedTools(key))
        assert(a.tools != MCPToolRoutes.allToolNames)
    }

    test("restricted session attribute is answered unchanged") {
        UserContext.set(bob)
        val rk = APIKeyValidator.resolveFromSession(bob)
        assert(rk.restrictedSession)
        val a = call(request(TenantInterceptor.ResolvedKeyAttr -> rk))
        assert(a.status == 200, s"body: ${a.body}")
        assert(a.filtered)
        assert(a.tools == MCPToolRoutes.allowedTools(rk))
        assert(!a.tools.contains("list_postgres_tables"))
    }

    test("no key and no session is 401 Authentication required") {
        val a = call(request())
        assert(a.status == 401)
        assert(a.body == ApiErrors.AuthenticationRequiredBody)
    }

    test("a rejected x-api-key stays 401 even with a logged-in session") {
        UserContext.set(admin)
        val a = call(request(TenantInterceptor.ApiKeyRejectedAttr -> "invalid"))
        assert(a.status == 401)
        assert(a.body == ApiErrors.AuthenticationRequiredBody)
    }
}
