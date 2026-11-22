package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityScopes, InertScopeDenial, InstallWideDenial, ScopeDenialFixtures}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, User, UserContext}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletResponse
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: Scoped API keys (plans/stories/scoped-key-read-routes.md), Part C
  * review: the order of the gate's answers for a scope-limited key (lead
  * ruling 4) in each enforcement and inert-scope mode, the log-only ruling
  * (a scope-limited caller is refused an install-wide route whether or not
  * it holds the capability; a restricted session on the raw-query routes
  * too), and the exact install-wide message ([[InstallWideDenial]]). Drives
  * the real `CapabilityInterceptor.preHandle`. */
class ScopedKeyInstallWideReviewSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var savedProp: Option[String] = None
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedProp = sys.props.get(CapabilityScopes.InertScopesProperty)
        captured.clear()
        UserContext.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv(logReads = false))
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "log-only set in this environment")
        assume(sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty, CapabilityScopes.InertScopesEnv + " is set in this environment")
        sys.props -= CapabilityScopes.InertScopesProperty
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        UserContext.clear()
        ScopeDenialFixtures.install(saved)
        savedProp match {
            case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
            case None => sys.props -= CapabilityScopes.InertScopesProperty
        }
    }

    private def allow[A](body: => A): A = {
        sys.props(CapabilityScopes.InertScopesProperty) = "allow"
        try body
        finally sys.props -= CapabilityScopes.InertScopesProperty
    }

    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    /** Each install-wide route with its capability and an inert-scoped form of it. */
    private val Routes: Seq[(String, String, String, String)] = Seq(
        ("GET", "/api/v1/metadata/postgres/tables", "metadata:read", "metadata:read:catalog=Sales"),
        ("GET", "/api/v1/unity-catalog/browse", "metadata:read", "metadata:read:catalog=Sales"),
        ("POST", "/api/v1/query/postgres", "query:postgres", "query:postgres:database=x"),
        ("POST", "/api/v1/query/mongodb", "query:mongodb", "query:mongodb:database=x"),
        ("POST", "/api/v1/query/natural", "query:natural", "query:natural:database=x")
    )

    private val Scope = "pipeline:read:catalog=Sales"

    private case class Gate(cont: Boolean, status: Int, body: String)

    private def gate(rk: ResolvedKey, method: String, path: String, i: CapabilityInterceptor): Gate = {
        val r = ScopeDenialFixtures.request(rk, method, path)._1
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var status = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { a => status = a.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = i.preHandle(r, resp, null)
        writer.flush()
        Gate(cont, status, out.toString)
    }

    private def enforcing = new CapabilityInterceptor()

    private def logOnly: CapabilityInterceptor = {
        val i = new CapabilityInterceptor()
        val f = classOf[CapabilityInterceptor].getDeclaredFields.find(f => f.getType == java.lang.Boolean.TYPE && f.getName.toLowerCase.contains("enforce")).get
        f.setAccessible(true)
        f.setBoolean(i, false)
        i
    }

    private def message(g: Gate): String = JsonParser.parseString(g.body).getAsJsonObject.get("message").getAsString

    private def genericMessage(label: String, req: String): String =
        "API key '" + label + "' does not hold capability '" + req + "'. " +
            "This is a permission boundary on the API key, not a problem with the upstream service. " +
            "The key would need '" + req + "' added to its capability bundle, " +
            "or the operator must use a different key with the required capability."

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))

    test("enforce, refuse mode: a scope-limited key lacking the capability gets the does-not-hold 403") {
        for ((m, p, req, _) <- Routes) {
            val rk = stored("lacking", Scope)
            assert(CallerVisibility.limited(rk))
            val g = gate(rk, m, p, enforcing)
            assert(!g.cont && g.status == 403, s"$m $p ${g.body}")
            assert(message(g) == genericMessage("lacking", req), g.body)
        }
    }

    test("enforce, refuse mode: a scope-limited key whose only matching capability is inert gets the inert-scope 403") {
        for ((m, p, req, inert) <- Routes) {
            val rk = stored("inert", Scope, inert)
            assert(rk.inertCapabilities.map(_.raw) == Seq(inert), rk.inertCapabilities)
            val g = gate(rk, m, p, enforcing)
            assert(!g.cont && g.status == 403, s"$m $p ${g.body}")
            InertScopeDenial.assertBody(g.body, "inert", req, m + " " + p, inert, inert.split(":")(2).takeWhile(_ != '='))
            assert(message(g) != InstallWideDenial.Message)
        }
    }

    test("enforce: a scope-limited key holding the capability gets the install-wide 403 with the pinned message") {
        for ((m, p, req, _) <- Routes) {
            val rk = stored("holder", Scope, req)
            val g = gate(rk, m, p, enforcing)
            assert(!g.cont && g.status == 403, s"$m $p ${g.body}")
            InstallWideDenial.assertGateBody(g.body, "holder", req, m + " " + p)
        }
    }

    test("enforce, allow mode: an inert-only match grants, so the key gets the install-wide 403") {
        allow {
            for ((m, p, req, inert) <- Routes) {
                val rk = stored("inert", Scope, inert)
                val g = gate(rk, m, p, enforcing)
                assert(!g.cont && g.status == 403, s"$m $p ${g.body}")
                InstallWideDenial.assertGateBody(g.body, "inert", req, m + " " + p)
            }
        }
    }

    test("log-only: a scope-limited key is refused every install-wide route whether or not it holds the capability, in both inert modes") {
        for (mode <- Seq("refuse", "allow")) {
            if (mode == "allow") sys.props(CapabilityScopes.InertScopesProperty) = "allow"
            try {
                for ((m, p, req, inert) <- Routes; rk <- Seq(stored("lacking", Scope), stored("inert", Scope, inert), stored("holder", Scope, req))) {
                    captured.clear()
                    val g = gate(rk, m, p, logOnly)
                    assert(!g.cont && g.status == 403, s"$mode ${rk.label} $m $p ${g.body}")
                    InstallWideDenial.assertGateBody(g.body, rk.label, req, m + " " + p)
                    assert(captured.count(e => e.category == "security" && e.action == "denied") == 1, captured)
                }
            } finally sys.props -= CapabilityScopes.InertScopesProperty
        }
    }

    test("log-only: a restricted session is refused the raw-query routes and keeps the listing answer on listings") {
        val rk = bob
        for ((m, p, req, _) <- Routes) {
            val g = gate(rk, m, p, logOnly)
            assert(!g.cont && g.status == 403, s"$m $p ${g.body}")
            if (req == "metadata:read") assert(message(g) == CapabilityInterceptor.restrictedListingMessage(rk.label, req), g.body)
            else InstallWideDenial.assertGateBody(g.body, rk.label, req, m + " " + p)
        }
    }

    test("log-only: unscoped keys and unrestricted sessions keep today's answer (would-deny passes, a holder passes)") {
        val lacking = stored("unscoped-lacking", "pipeline:read", "tap:read")
        val holder = stored("unscoped-holder", "pipeline:read", "tap:read", "metadata:read", "query:postgres", "query:mongodb", "query:natural")
        val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
        for ((m, p, _, _) <- Routes; rk <- Seq(lacking, holder, editor)) {
            val g = gate(rk, m, p, logOnly)
            assert(g.cont && g.status == 200 && g.body.isEmpty, s"${rk.label} $m $p ${g.body}")
        }
    }

    test("the install-wide message is the one CallerVisibility writes, and Part A's refusals share its words") {
        assert(CallerVisibility.InstallWideRequestMessage == InstallWideDenial.Message)
        assert(CallerVisibility.installWideMessage("this request") == InstallWideDenial.Message)
        assert(CallerVisibility.installWideMessage("MCP activity") == InstallWideDenial.Message.replace("this request", "MCP activity"))
    }
}
