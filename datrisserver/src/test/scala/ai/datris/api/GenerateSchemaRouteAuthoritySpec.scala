package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityRoutes, RouteCheck, TapRunTokens}
import ai.datris.config.{CapabilityInterceptor, TenantInterceptor}
import ai.datris.incident.RecoveryKey
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: Generating a validation schema needs the right to author pipelines
  * and never replaces a schema another pipeline depends on
  * (plans/stories/generate-schema-write-authority.md). Owner decision D1 (a):
  * `POST /api/v1/config/generate-schema` requires `pipeline:update`.
  *
  * Runs the real `CapabilityInterceptor` (the route gate) for every kind of
  * caller, with the identity attached as TenantInterceptor attaches it. The
  * handler's overwrite rule is in GenerateSchemaAuthoritySpec. */
class GenerateSchemaRouteAuthoritySpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Path = "/api/v1/config/generate-schema"

    private var savedEnv: DatrisEnvironment = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        captured.clear()
        AuditLog.sinkOverride = (a: AuditEntry) => captured.synchronized { captured += a; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    private def req(rk: ResolvedKey, method: String = "POST", path: String = Path): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    /** Runs the real capability gate; (continue?, status, body). */
    private def gate(rk: ResolvedKey, method: String = "POST", path: String = Path): (Boolean, Int, String) = {
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var status = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { i => status = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = new CapabilityInterceptor().preHandle(req(rk, method, path), resp, null)
        writer.flush()
        (cont, status, out.toString)
    }

    // ------------------------------------------------------------ callers

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)
    private def user(role: String): User = User("u_" + role, "h", role, null, null, null)
    private def restricted(role: String, g: (String, String)*): ResolvedKey =
        session(User("bob", "h", role, null, null, null, restricted = true, catalogGrants = grants(g: _*)))
    private def key(label: String, caps: Seq[String]): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)
    private def template(name: String): ResolvedKey = key("tpl-" + name, KeyTemplatesForSpecs.capabilities(name))

    /** The `ui` key acting for a signed-in user: what an in-platform chat's
      * tool calls carry (TenantInterceptor narrows to the user's bundle). */
    private val uiCarrier = ResolvedKey(None, "ui", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private def chatFor(u: User): ResolvedKey = TenantInterceptor.onBehalfOfIdentity(uiCarrier, u)

    private val keysOff = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val recovery = key(RecoveryKey.Label, RecoveryKey.Capabilities)

    /** (caller, identity, may generate a schema). The required outcome of D1 (a). */
    private def callers: Seq[(String, ResolvedKey, Boolean)] = Seq(
        ("admin session", session(user(User.RoleAdmin)), true),
        ("editor session", session(user(User.RoleEditor)), true),
        ("viewer session", session(user(User.RoleViewer)), false),
        ("restricted editor, edit grant", restricted(User.RoleEditor, "Sales" -> "edit"), true),
        ("restricted editor, own grant", restricted(User.RoleEditor, "Sales" -> "own"), true),
        ("restricted editor, view grant", restricted(User.RoleEditor, "Sales" -> "view"), false),
        ("restricted editor, no grant", restricted(User.RoleEditor), false),
        ("restricted viewer, edit grant", restricted(User.RoleViewer, "Sales" -> "edit"), false),
        ("template read-only", template("read-only"), false),
        ("template rag-builder", template("rag-builder"), false),
        ("template reporting", template("reporting"), false),
        ("template ops", template("ops"), false),
        ("template full-access", template("full-access"), true),
        ("unscoped pipeline-authoring key", key("author", Seq("pipeline:read", "pipeline:create", "pipeline:update")), true),
        ("unscoped pipeline-authoring key with metadata:read", key("author-meta", Seq("pipeline:read", "pipeline:update", "metadata:read")), true),
        ("pipeline:create-only key", key("creator", Seq("pipeline:read", "pipeline:create", "metadata:read")), false),
        ("read-only key (pipeline:read + metadata:read)", key("reader", Seq("pipeline:read", "metadata:read")), false),
        (
            "key limited to a catalog",
            key("sales-builder", Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales", "pipeline:update:catalog=Sales")),
            true
        ),
        ("key limited to what it created", key("own-builder", Seq("pipeline:read:owner=self", "pipeline:create", "pipeline:update:owner=self")), true),
        ("tap callback token", ResolvedKey(None, TapRunTokens.LabelPrefix + "orders_tap", TapRunTokens.Capabilities, isLegacyFullAccess = false), false),
        ("keys-off anonymous", keysOff, true),
        ("legacy full-access key", ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true), true),
        ("chat acting for an editor", chatFor(user(User.RoleEditor)), true),
        ("chat acting for a viewer", chatFor(user(User.RoleViewer)), false),
        (
            "chat acting for a restricted editor with an edit grant",
            chatFor(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))),
            true
        ),
        (
            "chat acting for a restricted editor with a view grant",
            chatFor(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))),
            false
        ),
        ("recovery key", recovery, true)
    )

    // ------------------------------------------------------------ Acceptance 1-2: the route's capability

    test("generating a schema needs the pipeline update capability") {
        assert(CapabilityRoutes.lookup("POST", Path) == RouteCheck.Require("pipeline", "update"))
    }

    test("every kind of caller may generate a schema exactly when it may change pipelines") {
        val wrong = callers.flatMap { case (what, rk, allowed) =>
            val (cont, status, body) = gate(rk)
            if (cont != allowed) Some(s"$what: expected ${if (allowed) "pass" else "403"}, got ${if (cont) "pass" else status + " " + body}")
            else if (!allowed && status != 403) Some(s"$what: refused with $status, expected 403")
            else None
        }
        assert(wrong.isEmpty, wrong.mkString("\n", "\n", ""))
    }

    test("a read-only template key is refused with the capability-denied body naming pipeline:update") {
        val (cont, status, body) = gate(template("read-only"))
        assert(!cont && status == 403, s"$status $body")
        assert(body.contains("capability_denied"), body)
        assert(body.contains("\"required\":\"pipeline:update\""), body)
    }

    test("a tap callback token is refused the route") {
        val token = TapRunTokens.issue("orders_tap", None, 60)
        try {
            val rk = TapRunTokens.resolve(token).get
            assert(rk.label == "tap:orders_tap")
            val (cont, status, body) = gate(rk)
            assert(!cont && status == 403, s"tap token passed the gate: $status $body")
            assert(body.contains("\"required\":\"pipeline:update\""), body)
        } finally TapRunTokens.revoke(token)
    }

    test("the audit row records a validation schema, not a pipeline, and is written whether or not reads are logged") {
        val expected = Some(ai.datris.audit.AuditRoute("pipeline", "update", "validation-schema"))
        assert(ai.datris.audit.AuditClassifier.classify("POST", Path, logReads = false) == expected)
        assert(ai.datris.audit.AuditClassifier.classify("POST", Path, logReads = true) == expected)
        // Other pipeline:update routes keep the pipeline resource type.
        assert(
            ai.datris.audit.AuditClassifier.classify("POST", "/api/v1/pipeline/dest-types", logReads = false) ==
                Some(ai.datris.audit.AuditRoute("pipeline", "update", "pipeline"))
        )
    }

    // ------------------------------------------------------------ unchanged guards: the other routes

    test("config upload and the other schema routes keep their requirements") {
        assert(CapabilityRoutes.lookup("POST", "/api/v1/config/upload") == RouteCheck.Require("config", "write"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/propose-schema") == RouteCheck.Require("pipeline", "read"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/generate") == RouteCheck.Require("pipeline", "create"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/profile") == RouteCheck.Require("metadata", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/pipeline/dest-types") == RouteCheck.Require("pipeline", "read"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/dest-types") == RouteCheck.Require("pipeline", "update"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline") == RouteCheck.Require("pipeline", "create"))
        // Not a listing refused to restricted sessions, not an install-wide route.
        assert(!CapabilityRoutes.refusedForRestrictedSession("POST", Path))
        assert(!CapabilityRoutes.refusedForLimited("POST", Path))
    }

    test("config upload answers each caller as before") {
        val upload = "/api/v1/config/upload"
        // config:write: admins, full-access keys and keys-off pass; editors,
        // read-only keys and tap tokens do not.
        Seq(
            ("admin session", session(user(User.RoleAdmin)), true),
            ("full-access template", template("full-access"), true),
            ("keys-off anonymous", keysOff, true),
            ("editor session", session(user(User.RoleEditor)), false),
            ("read-only template", template("read-only"), false),
            ("unscoped pipeline-authoring key", key("author", Seq("pipeline:read", "pipeline:create", "pipeline:update")), false),
            ("tap callback token", ResolvedKey(None, "tap:orders_tap", TapRunTokens.Capabilities, isLegacyFullAccess = false), false)
        ).foreach { case (what, rk, allowed) =>
            val (cont, status, body) = gate(rk, path = upload)
            assert(cont == allowed, s"$what on config upload: $status $body")
            if (!allowed) assert(status == 403 && body.contains("\"required\":\"config:write\""), s"$what: $body")
        }
    }
}
