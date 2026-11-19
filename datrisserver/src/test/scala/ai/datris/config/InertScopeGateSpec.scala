package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityScopes, InertScopeDenial, MCPToolRoutes, ScopeDenialFixtures}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: API keys, a scope the server does not apply can no longer be put on
  * a key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
  * Acceptance, `InertScopeGateSpec` (Fix 3, Decision D1: fail closed with the
  * `DATRIS_KEY_INERT_SCOPES=allow` opt-out).
  *
  * Drives the real `CapabilityInterceptor.preHandle` with a request backed by
  * an attribute map and the AuditLog test sink. The stored key is built the way
  * `APIKeyValidator.resolveAgainst` builds it after the story: its parsed
  * capabilities split by `CapabilityScopes.partition` (APIKeyValidatorSpec pins
  * that resolveAgainst does exactly this).
  *
  * Pinned seam (does not exist at d3e13515; the implementation adds it):
  * {{{
  * // ai.datris.model.ResolvedKey: new last field
  * inertCapabilities: Seq[Capability] = Nil
  * // ai.datris.auth.CapabilityScopes: the switch, read at call time like
  * // SecretNames.tapScope (system property first, then the env var;
  * // trimmed, lowercased; only "allow" opts out, anything else refuses)
  * val InertScopesEnv: String       // "DATRIS_KEY_INERT_SCOPES"
  * val InertScopesProperty: String  // a datris.* system property, read before the env var
  * def refuseInert: Boolean
  * }}}
  * Refuse mode (the default): a request whose route only an inert capability
  * matches is refused 403 with the interceptor's capability-denied body (key
  * set unchanged: [[InertScopeDenial.BodyKeys]]), a message naming the inert
  * capability and its scope key, and one `security/denied` audit row whose
  * reason names both. Capabilities of the same key that are applied keep
  * working. Allow mode: the request passes as in v1.60.0 and writes one
  * `security/inert-scope-used` row; a request granted by an applied
  * capability writes none. */
class InertScopeGateSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var savedProp: Option[String] = None
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedProp = sys.props.get(CapabilityScopes.InertScopesProperty)
        captured.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv(logReads = false))
        assume(
            !sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")),
            "CAPABILITY_ENFORCEMENT=log-only is set in this environment"
        )
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
        savedProp match {
            case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
            case None => sys.props -= CapabilityScopes.InertScopesProperty
        }
    }

    private def mode[A](value: Option[String])(body: => A): A = {
        value match {
            case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
            case None => sys.props -= CapabilityScopes.InertScopesProperty
        }
        body
    }

    private def refuse[A](body: => A): A = mode(None) {
        assume(sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty, CapabilityScopes.InertScopesEnv + " is set in this environment")
        body
    }

    private def allow[A](body: => A): A = mode(Some("allow"))(body)

    /** A stored key as resolveAgainst returns it after the story. */
    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private val ikOld = stored("ik_old", "job:read:owner=self", "pipeline:read")

    /** Runs the real interceptor; returns (continue?, status, body). */
    private def gate(rk: ResolvedKey, method: String, path: String): (Boolean, Int, String) = {
        val r: HttpServletRequest = ScopeDenialFixtures.request(rk, method, path)._1
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var status = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { i => status = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = new CapabilityInterceptor().preHandle(r, resp, null)
        writer.flush()
        (cont, status, out.toString)
    }

    private def entries: List[AuditEntry] = captured.synchronized(captured.toList)
    private def denials: List[AuditEntry] = entries.filter(e => e.category == "security" && e.action == "denied")
    private def inertUses: List[AuditEntry] = entries.filter(e => e.category == "security" && e.action == "inert-scope-used")

    private def reasonOf(e: AuditEntry): String =
        e.metadata.filter(_.has("reason")).map(_.get("reason").getAsString).getOrElse("") + " " + e.errorMessage.getOrElse("")

    /** Requests the key's applied capability grants: pass in both modes, no inert row. */
    private def assertAppliedStillWork(rk: ResolvedKey): Unit = {
        val (cont, status, body) = gate(rk, "GET", "/api/v1/pipelines")
        assert(cont && status == 200 && body.isEmpty, s"GET /pipelines -> $status $body")
    }

    test("refuse mode: GET /pipeline/status with that key is denied with a message naming the capability and the scope; the audit reason names both") {
        refuse {
            assert(CapabilityScopes.refuseInert)
            val (cont, status, body) = gate(ikOld, "GET", "/api/v1/pipeline/status")
            assert(!cont, "the controller must not run")
            assert(status == 403, body)
            InertScopeDenial.assertBody(body, "ik_old", "job:read", "GET /api/v1/pipeline/status", "job:read:owner=self", "owner")

            assert(denials.size == 1, entries.map(e => e.category + "/" + e.action))
            val d = denials.head
            assert(d.outcome == "denied" && d.httpStatus.contains(403), d)
            assert(d.metadata.exists(m => m.has("required") && m.get("required").getAsString == "job:read"), d.metadata)
            InertScopeDenial.assertNames(reasonOf(d), "job:read:owner=self", "owner")
            assert(inertUses.isEmpty, "refuse mode writes no inert-scope-used row")

            // the other job:read routes are refused the same way
            Seq(("GET", "/api/v1/pipeline/result"), ("GET", "/api/v1/tap/logs")).foreach { case (m, p) =>
                val (c2, s2, b2) = gate(ikOld, m, p)
                assert(!c2 && s2 == 403, s"$m $p -> $s2 $b2")
                InertScopeDenial.assertBody(b2, "ik_old", "job:read", m + " " + p, "job:read:owner=self", "owner")
            }

            // the key's applied capability keeps working (Verify step 4: GET /pipelines -> 200)
            captured.clear()
            assertAppliedStillWork(ikOld)
            assert(denials.isEmpty && inertUses.isEmpty, entries)
        }
    }

    test("refuse mode: a stored *:*:catalog=Sales key keeps pipeline and tap routes and is refused elsewhere") {
        refuse {
            val wide = stored("sales_all", "*:*:catalog=Sales")
            assert(gate(wide, "GET", "/api/v1/pipelines")._1)
            assert(gate(wide, "GET", "/api/v1/taps")._1)
            assert(gate(wide, "POST", "/api/v1/tap")._1, "tap:create:catalog=Sales survives as an applied capability")
            Seq(("POST", "/api/v1/query/postgres"), ("GET", "/api/v1/audit-log"), ("GET", "/api/v1/pipeline/status")).foreach { case (m, p) =>
                val (cont, status, body) = gate(wide, m, p)
                assert(!cont && status == 403, s"$m $p -> $status $body")
                assert(body.contains("*:*:catalog=Sales"), "names the capability that no longer grants it: " + body)
            }
        }
    }

    test("allow mode: the same request passes and yields exactly one inert-scope-used entry; a request granted by another capability of the key yields none") {
        allow {
            assert(!CapabilityScopes.refuseInert)
            val (cont, status, body) = gate(ikOld, "GET", "/api/v1/pipeline/status")
            assert(cont && status == 200 && body.isEmpty, s"$status $body")
            assert(inertUses.size == 1, entries.map(e => e.category + "/" + e.action))
            assert(denials.isEmpty, denials)
            val u = inertUses.head
            assert(u.actor.label == "ik_old", u.actor)
            val md = u.metadata.map(_.toString).getOrElse("") + " " + u.errorMessage.getOrElse("")
            assert(md.contains("job:read:owner=self"), "the row names the capability: " + md)

            captured.clear()
            assertAppliedStillWork(ikOld)
            assert(inertUses.isEmpty && denials.isEmpty, entries)

            // v1.60.0 behaviour for a narrowed scoped wildcard too
            captured.clear()
            val wide = stored("sales_all", "*:*:catalog=Sales")
            assert(gate(wide, "GET", "/api/v1/audit-log")._1, "allow mode restores v1.60.0")
            assert(inertUses.size == 1, entries.map(e => e.category + "/" + e.action))
            captured.clear()
            assert(gate(wide, "GET", "/api/v1/pipelines")._1)
            assert(inertUses.isEmpty, "pipelines is granted by the narrowed, applied capability")
        }
    }

    test("a key with job:read and job:read:owner=self is not denied in either mode") {
        val both = stored("ik_both", "job:read", "job:read:owner=self")
        assert(both.inertCapabilities.map(_.raw) == Seq("job:read:owner=self"))
        refuse {
            val (cont, status, body) = gate(both, "GET", "/api/v1/pipeline/status")
            assert(cont && status == 200, s"$status $body")
            assert(denials.isEmpty && inertUses.isEmpty, entries)
        }
        captured.clear()
        allow {
            val (cont, status, body) = gate(both, "GET", "/api/v1/pipeline/status")
            assert(cont && status == 200, s"$status $body")
            assert(inertUses.isEmpty, "granted by job:read, not by the inert capability: " + entries)
        }
    }

    test("allowedTools hides get_pipeline_status from a key whose only job capability is inert") {
        refuse {
            val tools = MCPToolRoutes.allowedTools(ikOld)
            assert(!tools.contains("get_pipeline_status"), tools)
            assert(tools.contains("list_pipelines") && tools.contains("get_pipeline"), "applied pipeline:read keeps its tools: " + tools)
            // a key that also holds job:read keeps the tool
            assert(MCPToolRoutes.allowedTools(stored("ik_both", "job:read", "job:read:owner=self", "pipeline:read")).contains("get_pipeline_status"))
        }
    }

    // Reading of Fix 3 ("DATRIS_KEY_INERT_SCOPES=allow restores today's
    // behaviour"): with the opt-out the tool list matches the route gate, as in
    // v1.60.0. The story does not say this explicitly (see the report).
    test("allow mode (opt-out restores v1.60.0): allowedTools shows get_pipeline_status to a key whose only job capability is inert") {
        allow {
            assert(MCPToolRoutes.allowedTools(ikOld).contains("get_pipeline_status"))
        }
    }

    test("an unknown value of DATRIS_KEY_INERT_SCOPES means refuse") {
        Seq("", " ", "refuse", "deny", "yes", "true", "1", "allowed", "allow-all", "off").foreach { v =>
            mode(Some(v)) {
                assert(CapabilityScopes.refuseInert, "'" + v + "' must refuse")
            }
        }
        Seq("allow", "ALLOW", " Allow ").foreach { v =>
            mode(Some(v)) {
                assert(!CapabilityScopes.refuseInert, "'" + v + "' opts out")
            }
        }
        assert(CapabilityScopes.InertScopesEnv == "DATRIS_KEY_INERT_SCOPES")
        refuse(assert(CapabilityScopes.refuseInert, "unset means refuse"))
        // and the gate follows it
        mode(Some("yes")) {
            val (cont, status, body) = gate(ikOld, "GET", "/api/v1/pipeline/status")
            assert(!cont && status == 403, s"$status $body")
        }
    }
}
