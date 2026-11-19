package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.{KeyTemplatesForSpecs, MCPToolsAPIController}
import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{
    CallerVisibility,
    CapabilityCheck,
    CapabilityRoutes,
    CapabilityScopes,
    InertScopeDenial,
    MCPToolRoutes,
    RouteCheck,
    ScopeDenialFixtures,
    TapRunTokens
}
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, User, UserContext}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.{anyInt, anyString}
import org.mockito.Mockito.{doAnswer, mock, never, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.util.AntPathMatcher

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part C (install-wide
  * refusals), Acceptance `ScopedKeyInstallWideSpec`, Design 5, Decision D3
  * (refuse explicitly granted `query:postgres`, `metadata:read`, ... to a
  * scope-limited key, as for restricted users).
  *
  * Drives the real `CapabilityInterceptor.preHandle` with a request backed by
  * an attribute map and the AuditLog test sink (the InertScopeGateSpec seam),
  * and `MCPToolRoutes.allowedTools` / `MCPToolsAPIController.listMcpTools`.
  * Nothing here reaches a handler: a refused request returns `false` from
  * preHandle, so no database, warehouse, Unity Catalog, model or MCP server is
  * touched; an allowed one only returns `true` (no handler is called either).
  *
  * The caller-`sql` bullet needs `QueryAPIController.restrictedSqlRefusal`
  * (private[api]), so it lives in ai.datris.api.ScopedKeyInstallWideSqlSpec.
  *
  * Seams relied on (all exist at 9624d481; names kept):
  * {{{
  * object CapabilityRoutes {
  *   val UnfilterableForRestricted: Seq[(String, String)]   // gains POST /query/postgres, /query/mongodb, /query/natural
  *   def refusedForRestrictedSession(method: String, path: String): Boolean   // not called here (story: "renamed")
  *   private val routes: Seq[Route]          // read reflectively: route-completeness drift guard
  *   private val skipPatterns: Seq[String]   // read reflectively: same
  * }
  * class CapabilityInterceptor { private val enforce: Boolean }   // set reflectively for the log-only case
  * }}}
  *
  * Readings of the story's text (see the report, OPEN QUESTIONS):
  *   - "scope-limited" is [[CallerVisibility.limited]] (limited for pipelines
  *     OR taps): Design 5 and Files say "limited keys"; Backward compat says
  *     "Such keys lose ... table/collection listings, Unity Catalog browse and
  *     raw Postgres/Mongo/natural-language queries". So a key limited for
  *     taps only and one limited for pipelines only are refused every Part C
  *     route alike (raw SQL can read tap stamp columns, a listing names other
  *     pipelines' tables).
  *   - The refusal holds in both `CAPABILITY_ENFORCEMENT` modes, as the
  *     restricted-session refusal does ("the grants are a hard boundary") and
  *     as controller scope checks do ("log-only never covered controller checks").
  *   - A restricted session keeps exactly today's answer on every Part C route
  *     ("Unchanged: ... restricted sessions (except rows 7 and 10)").
  */
class ScopedKeyInstallWideSpec extends AnyFunSuite with BeforeAndAfterEach {

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
        assume(
            !sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")),
            "CAPABILITY_ENFORCEMENT=log-only is set in this environment"
        )
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

    // ------------------------------------------------------------ modes

    private def mode[A](value: Option[String])(body: => A): A = {
        value match {
            case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
            case None => sys.props -= CapabilityScopes.InertScopesProperty
        }
        body
    }

    private def refuseMode[A](body: => A): A = mode(None) {
        assume(sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty, CapabilityScopes.InertScopesEnv + " is set in this environment")
        body
    }

    private def allowMode[A](body: => A): A = mode(Some("allow"))(body)

    /** Runs `body` once in each `DATRIS_KEY_INERT_SCOPES` mode. */
    private def bothModes(body: String => Unit): Unit = {
        refuseMode(body("refuse"))
        captured.clear()
        allowMode(body("allow"))
        captured.clear()
    }

    // ------------------------------------------------------------ callers

    /** A stored key as `APIKeyValidator.resolveAgainst` returns it (capabilities split by CapabilityScopes.partition). */
    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    /** The route capabilities of every Part C route. */
    private val InstallWideCaps = Seq("metadata:read", "query:postgres", "query:mongodb", "query:natural")

    /** Scope-limited key shapes (the ones that broke earlier parts), each also
      * holding every Part C route capability explicitly (D3). */
    private def limitedKeys: Seq[ResolvedKey] = Seq(
        // the Verify block's sk_sales key
        stored(
            "sk-sales",
            Seq(
                "pipeline:read:catalog=SkSales",
                "pipeline:create:catalog=SkSales",
                "tap:read:catalog=SkSales",
                "tap:update:catalog=SkSales",
                "job:read",
                "job:kill",
                "query:objectstore",
                "search:vector",
                "document:upload"
            ) ++ InstallWideCaps: _*
        ),
        stored("cat-sales", "pipeline:read:catalog=Sales" +: InstallWideCaps: _*),
        stored("own-self", Seq("pipeline:read:owner=self", "tap:read:owner=self") ++ InstallWideCaps: _*),
        stored("own-run-only", "tap:run:owner=self" +: InstallWideCaps: _*),
        // limited for taps only: unscoped pipeline:read, a scoped tap capability
        stored("taps-only", Seq("pipeline:read", "tap:run:owner=self") ++ InstallWideCaps: _*),
        // limited for pipelines only: unscoped tap:read, a scoped pipeline capability
        stored("pipelines-only", Seq("tap:read", "pipeline:read:catalog=Sales") ++ InstallWideCaps: _*),
        // the scoped pipeline capability also carries an inert scope
        stored("cat-inert", "pipeline:read:catalog=Sales,collection=docs" +: InstallWideCaps: _*),
        // a wildcard carrying a scope is never an administrator key
        stored("wide-sales", "*:*:catalog=Sales" +: InstallWideCaps: _*)
    )

    private def restrictedUser(g: (String, String)*): User =
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*))

    private def bob: ResolvedKey = APIKeyValidator.resolveFromSession(restrictedUser("Sales" -> "edit"))

    private def sessionOf(role: String, name: String): ResolvedKey =
        APIKeyValidator.resolveFromSession(User(name, "h", role, null, null, null))

    private val legacyKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private def starKey = stored("star", "*:*")

    private def templateKey(name: String): ResolvedKey = stored("tpl-" + name, KeyTemplatesForSpecs.capabilities(name): _*)

    private def recoveryKey: ResolvedKey = stored(RecoveryKey.Label, RecoveryKey.Capabilities: _*)

    /** Callers Part C must not change: they are never limited. */
    private def unchangedCallers: Seq[ResolvedKey] =
        Seq(
            sessionOf(User.RoleAdmin, "alice"),
            sessionOf(User.RoleEditor, "ed"),
            sessionOf(User.RoleViewer, "vic"),
            legacyKey,
            starKey,
            stored("monitor", "job:read" +: InstallWideCaps: _*),
            stored(
                "inert-only",
                Seq("search:vector:collection=docs", "document:upload:destination_kind=postgres", "job:read:owner=self") ++ InstallWideCaps: _*
            ),
            recoveryKey
        ) ++ KeyTemplatesForSpecs.names.map(templateKey)

    // ------------------------------------------------------------ routes

    private val matcher = new AntPathMatcher()

    /** The install-wide routes Part C refuses to a scope-limited caller
      * (Files, Part C: `UnfilterableForRestricted` plus /query/postgres,
      * /query/mongodb, /query/natural). */
    private val RefusedPatterns: Seq[(String, String)] = Seq(
        "GET" -> "/api/v1/metadata/**",
        "GET" -> "/api/v1/unity-catalog/browse",
        "POST" -> "/api/v1/query/postgres",
        "POST" -> "/api/v1/query/mongodb",
        "POST" -> "/api/v1/query/natural"
    )

    private def refusedRoute(method: String, path: String): Boolean =
        RefusedPatterns.exists { case (m, p) => m.equalsIgnoreCase(method) && matcher.`match`(p, path) }

    /** Concrete requests on every refused route: each MCP tool's path plus one /metadata path no tool uses yet. */
    private val RefusedRequests: Seq[(String, String)] =
        (MCPToolRoutes.tools.collect { case (_, MCPToolRoutes.Mapped(m, p)) if refusedRoute(m, p) => (m, p) } ++
            Seq("GET" -> "/api/v1/metadata/some/future/listing")).distinct

    private def required(method: String, path: String): String = CapabilityRoutes.lookup(method, path) match {
        case RouteCheck.Require(r, a) => r + ":" + a
        case other => fail(s"$method $path is not a mapped route: $other")
    }

    // ------------------------------------------------------------ the interceptor seam

    private case class Gate(cont: Boolean, status: Int, body: String, request: HttpServletRequest)

    /** Names a request could carry that a refusal must never echo. */
    private val HiddenNames = Seq("hr_payroll", "SkHR", "payroll_2026", "public.salaries")

    private def requestFor(rk: ResolvedKey, method: String, path: String): HttpServletRequest = {
        val r = ScopeDenialFixtures.request(rk, method, path)._1
        when(r.getQueryString).thenReturn("database=hr_payroll&schema=SkHR&table=payroll_2026")
        when(r.getParameter(anyString())).thenAnswer(i =>
            i.getArgument[String](0) match {
                case "database" => "hr_payroll"
                case "schema" => "SkHR"
                case "table" => "payroll_2026"
                case _ => null
            }
        )
        r
    }

    private def gate(rk: ResolvedKey, method: String, path: String, interceptor: CapabilityInterceptor = new CapabilityInterceptor()): Gate = {
        val r = requestFor(rk, method, path)
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var status = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { i => status = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = interceptor.preHandle(r, resp, null)
        writer.flush()
        Gate(cont, status, out.toString, r)
    }

    private def entries: List[AuditEntry] = captured.synchronized(captured.toList)
    private def denials: List[AuditEntry] = entries.filter(e => e.category == "security" && e.action == "denied")

    /** Today's generic "does not hold" message (CapabilityInterceptor.deniedBody, message None). */
    private def genericMessage(label: String, req: String): String =
        "API key '" + label + "' does not hold capability '" + req + "'. " +
            "This is a permission boundary on the API key, not a problem with the upstream service. " +
            "The key would need '" + req + "' added to its capability bundle, " +
            "or the operator must use a different key with the required capability."

    /** Today's restricted-session listing message (v1.59.0 text, pinned in RestrictedHiddenTapScrubSpec). */
    private def todaysListingMessage(label: String, req: String): String =
        "'" + label + "' holds " + req + ", but this listing is refused for a catalog-scoped user: " +
            "table and collection listings cannot be limited to the user's catalogs. " +
            "Use find_data or get_pipeline to locate data in those catalogs instead."

    private def sixKeyBody(label: String, req: String, method: String, path: String, message: String): JsonObject = {
        val o = new JsonObject
        o.addProperty("error", "capability denied")
        o.addProperty("errorKind", "capability_denied")
        o.addProperty("key", label)
        o.addProperty("required", req)
        o.addProperty("route", method + " " + path)
        o.addProperty("message", message)
        o
    }

    /** A Part C refusal for a scope-limited key: decided before the handler,
      * the interceptor's six-key capability-denied body with the key's label,
      * the route capability and the route, a message that is not the
      * "does not hold" text (the key does hold it) and names nothing from the
      * request, one security/denied audit row naming the capability, and the
      * request body never read. */
    private def assertKeyRefusal(rk: ResolvedKey, method: String, path: String, g: Gate, ctx: String): Unit = {
        val req = required(method, path)
        assert(!g.cont, s"$ctx: $method $path must be decided before the handler runs (preHandle returned true)")
        assert(g.status == 403, s"$ctx: $method $path -> ${g.status} ${g.body}")
        val o = JsonParser.parseString(g.body).getAsJsonObject
        assert(o.keySet().asScala.toSet == InertScopeDenial.BodyKeys, s"$ctx: ${g.body}")
        assert(o.get("error").getAsString == "capability denied", g.body)
        assert(o.get("errorKind").getAsString == "capability_denied", g.body)
        assert(o.get("key").getAsString == rk.label, g.body)
        assert(o.get("required").getAsString == req, g.body)
        assert(o.get("route").getAsString == method + " " + path, g.body)
        val msg = o.get("message").getAsString
        assert(msg.nonEmpty, g.body)
        assert(!msg.contains("does not hold capability"), s"$ctx: the key holds $req; the message must not say it does not: $msg")
        HiddenNames.foreach(h => assert(!g.body.contains(h), s"$ctx: '$h' disclosed: ${g.body}"))
        assert(!g.body.contains("(scope:"), g.body)
        assert(denials.size == 1, s"$ctx: $method $path audit: " + entries.map(e => e.category + "/" + e.action))
        val d = denials.head
        assert(d.outcome == "denied" && d.httpStatus.contains(403), d)
        assert(d.metadata.exists(m => m.has("required") && m.get("required").getAsString == req), d.metadata)
        verify(g.request, never()).getInputStream
        verify(g.request, never()).getReader
    }

    private def assertPasses(rk: ResolvedKey, method: String, path: String, g: Gate, ctx: String): Unit = {
        assert(g.cont && g.status == 200 && g.body.isEmpty, s"$ctx: ${rk.label} $method $path -> ${g.status} ${g.body}")
        assert(denials.isEmpty, s"$ctx: ${rk.label} $method $path wrote a denial: $entries")
    }

    // ================================================================ Acceptance bullet 1

    test(
        "metadata listings, Unity Catalog browse and raw postgres/mongodb/natural queries are 403 capability-denied for a scope-limited key holding the capability, and pass for an unscoped key"
    ) {
        // The refused routes, derived from the MCP route table: the 10 distinct /metadata paths the 11 listing
        // tools use (list_pgvector_collections shares /metadata/postgres/tables) plus one no tool uses yet,
        // browse and the three raw queries.
        assert(RefusedRequests.count(_._2.startsWith("/api/v1/metadata/")) == 11, RefusedRequests)
        assert(RefusedRequests.map(_._2).toSet.contains("/api/v1/unity-catalog/browse"))
        Seq("/api/v1/query/postgres", "/api/v1/query/mongodb", "/api/v1/query/natural").foreach(p => assert(RefusedRequests.contains("POST" -> p), p))

        bothModes { m =>
            for (rk <- limitedKeys; (method, path) <- RefusedRequests) {
                assert(CallerVisibility.limited(rk), s"$m: precondition: ${rk.label} is scope-limited")
                val req = required(method, path)
                assert(rk.matchesResourceAction(req.takeWhile(_ != ':'), req.dropWhile(_ != ':').drop(1)), s"$m: precondition: ${rk.label} holds $req")
                captured.clear()
                assertKeyRefusal(rk, method, path, gate(rk, method, path), s"$m/${rk.label}")
            }
            // An unscoped key holding the same capabilities passes every one.
            val unscoped = stored("unscoped", Seq("pipeline:read", "tap:read") ++ InstallWideCaps: _*)
            assert(!CallerVisibility.limited(unscoped))
            for ((method, path) <- RefusedRequests) {
                captured.clear()
                assertPasses(unscoped, method, path, gate(unscoped, method, path), m)
            }
        }
    }

    // ------------------------------------------------------------ additions: the three groups of callers

    test("a restricted session keeps exactly today's answer on every Part C route (listing message on listings, the does-not-hold body on raw queries)") {
        val rk = bob
        assert(rk.restrictedSession && rk.label == "session:bob")
        for ((method, path) <- RefusedRequests) {
            captured.clear()
            val g = gate(rk, method, path)
            assert(!g.cont && g.status == 403, s"$method $path -> ${g.status} ${g.body}")
            val req = required(method, path)
            val expected =
                if (req == "metadata:read") sixKeyBody(rk.label, req, method, path, todaysListingMessage(rk.label, req))
                else {
                    // query:postgres / mongodb / natural are not in a restricted bundle (CatalogGrants.RefusedForRestricted)
                    assert(!rk.matchesResourceAction("query", req.drop("query:".length)), req)
                    sixKeyBody(rk.label, req, method, path, genericMessage(rk.label, req))
                }
            assert(JsonParser.parseString(g.body).getAsJsonObject == expected, s"$method $path: ${g.body}")
            assert(denials.size == 1, entries)
        }
    }

    test("unchanged callers pass every Part C route they hold the capability for, and keep today's does-not-hold 403 where they do not") {
        bothModes { m =>
            for (rk <- unchangedCallers; (method, path) <- RefusedRequests) {
                assert(!CallerVisibility.limited(rk), s"$m: precondition: ${rk.label} is not limited")
                captured.clear()
                val g = gate(rk, method, path)
                val req = required(method, path)
                val (res, act) = (req.takeWhile(_ != ':'), req.dropWhile(_ != ':').drop(1))
                if (rk.matchesResourceAction(res, act)) assertPasses(rk, method, path, g, m)
                else {
                    assert(!g.cont && g.status == 403, s"$m: ${rk.label} $method $path -> ${g.status} ${g.body}")
                    val msg = JsonParser.parseString(g.body).getAsJsonObject.get("message").getAsString
                    // an inert capability matching the route keeps its own (inert-scope) denial; otherwise the generic text
                    if (rk.inertMatching(res, act).isEmpty || m == "allow")
                        assert(msg == genericMessage(rk.label, req), s"$m: ${rk.label} $method $path: $msg")
                }
            }
        }
    }

    test("templates: read-only, reporting, ops, full-access and rag-builder are not limited and Part C takes nothing from them") {
        // Backward compat: "Unchanged: ... the read-only, reporting, ops, full-access templates (no scoped capability).
        // rag-builder keeps everything except uploading into pipelines it did not create" (Part A). So Part C takes
        // nothing from any template: each passes every Part C route whose capability it holds.
        val expectedHeld = Map(
            "read-only" -> Set("metadata:read", "query:postgres", "query:mongodb"), // no query:natural in the template
            "reporting" -> Set("metadata:read", "query:postgres", "query:mongodb"),
            "ops" -> Set.empty[String],
            "full-access" -> Set("metadata:read", "query:postgres", "query:mongodb", "query:natural"),
            "rag-builder" -> Set.empty[String]
        )
        assert(KeyTemplatesForSpecs.names.toSet == expectedHeld.keySet, "a new template needs a Part C decision here: " + KeyTemplatesForSpecs.names)
        for ((name, held) <- expectedHeld) {
            val rk = templateKey(name)
            assert(!CallerVisibility.limited(rk), name)
            for ((method, path) <- RefusedRequests) {
                captured.clear()
                val req = required(method, path)
                val g = gate(rk, method, path)
                if (held.contains(req)) assertPasses(rk, method, path, g, name)
                else {
                    assert(!g.cont && g.status == 403, s"$name $method $path: ${g.status} ${g.body}")
                    assert(JsonParser.parseString(g.body).getAsJsonObject.get("message").getAsString == genericMessage(rk.label, req), g.body)
                }
            }
        }
    }

    test("a tap-run token identity, no identity (auth off) and a null request are unchanged") {
        val token = TapRunTokens.issue("t_sales_feed", None, 60)
        try {
            val tapRun = TapRunTokens.resolve(token).getOrElse(fail("token did not resolve"))
            assert(!CallerVisibility.limited(tapRun))
            for ((method, path) <- RefusedRequests) {
                captured.clear()
                // tap scripts read through /query/postgres and /metadata (TapDependencies): they keep working
                assertPasses(tapRun, method, path, gate(tapRun, method, path), "tap-run")
            }
        } finally TapRunTokens.revoke(token)

        for ((method, path) <- RefusedRequests) {
            captured.clear()
            val g = gate(null, method, path)
            assert(g.cont && g.status == 200 && g.body.isEmpty, s"no identity $method $path -> ${g.status} ${g.body}")
        }
        assert(!CapabilityCheck.isLimited(null))
    }

    // ------------------------------------------------------------ additions: taps-only / pipelines-only

    test("OPEN QUESTION reading: a key limited for taps only and a key limited for pipelines only are both refused every Part C route") {
        // Story: "Such keys lose ... table/collection listings, Unity Catalog browse and raw
        // Postgres/Mongo/natural-language queries" with "scope-limited" = limited for either resource
        // (Design 5 / Files: "applies to limited keys"). Raw SQL reaches the tap stamp columns
        // (_datris_tap_run, _datris_source) a taps-only key may not read; a listing names other pipelines' tables.
        val tapsOnly = stored("taps-only", Seq("pipeline:read", "tap:run:owner=self") ++ InstallWideCaps: _*)
        val pipelinesOnly = stored("pipelines-only", Seq("tap:read", "pipeline:read:catalog=Sales") ++ InstallWideCaps: _*)
        assert(CallerVisibility.limitedTaps(tapsOnly) && !CallerVisibility.limitedPipelines(tapsOnly))
        assert(CallerVisibility.limitedPipelines(pipelinesOnly) && !CallerVisibility.limitedTaps(pipelinesOnly))
        for (rk <- Seq(tapsOnly, pipelinesOnly); (method, path) <- RefusedRequests) {
            captured.clear()
            assertKeyRefusal(rk, method, path, gate(rk, method, path), rk.label)
        }
    }

    // ------------------------------------------------------------ additions: log-only

    /** An interceptor running as under CAPABILITY_ENFORCEMENT=log-only (its
      * `enforce` flag is read once from the environment at construction). */
    private def logOnlyInterceptor(): CapabilityInterceptor = {
        val i = new CapabilityInterceptor()
        val f = classOf[CapabilityInterceptor].getDeclaredFields
            .find(f => f.getType == java.lang.Boolean.TYPE && f.getName.toLowerCase.contains("enforce"))
            .getOrElse(fail("CapabilityInterceptor has no boolean 'enforce' field to switch to log-only"))
        f.setAccessible(true)
        f.setBoolean(i, false)
        i
    }

    test("under CAPABILITY_ENFORCEMENT=log-only the Part C refusal still holds for a scope-limited key, as it does for a restricted session") {
        val lo = logOnlyInterceptor()
        // the switch worked: a key lacking a capability passes as would-deny
        val lacking = stored("lacking", "pipeline:read")
        assert(gate(lacking, "GET", "/api/v1/audit-log", lo).cont, "log-only lets a would-deny through")
        // restricted session: today's refusal holds in both modes
        captured.clear()
        assert(!gate(bob, "GET", "/api/v1/metadata/postgres/tables", lo).cont)
        for (rk <- limitedKeys; (method, path) <- RefusedRequests) {
            captured.clear()
            assertKeyRefusal(rk, method, path, gate(rk, method, path, lo), "log-only/" + rk.label)
        }
        // unscoped keys still pass
        val unscoped = stored("unscoped", Seq("pipeline:read", "tap:read") ++ InstallWideCaps: _*)
        for ((method, path) <- RefusedRequests) {
            captured.clear()
            assertPasses(unscoped, method, path, gate(unscoped, method, path, lo), "log-only")
        }
    }

    // ================================================================ Acceptance bullet 3

    /** The tools whose primary route Part C refuses, derived from the route table. */
    private def refusedTools: Set[String] =
        MCPToolRoutes.tools.collect { case (n, MCPToolRoutes.Mapped(m, p)) if refusedRoute(m, p) => n }.toSet

    private val StoryHidden: Set[String] = Set(
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        "list_pgvector_collections",
        "browse_unity_catalog",
        "query_postgres",
        "query_mongodb",
        "query_natural"
    )

    /** Tools a scope-limited key holding their capability must keep. */
    private val Kept = Seq(
        "find_data",
        "get_lineage",
        "get_provenance",
        "profile_data",
        "ai_answer",
        "query_objectstore",
        "query_snowflake",
        "query_sqlserver",
        "query_databricks",
        "list_pipelines",
        "get_pipeline"
    )

    /** A scope-limited key holding every capability the route table grants,
      * with pipeline and tap read scoped to Sales. */
    private def allCapsLimited: ResolvedKey = {
        val caps = CapabilityRoutes.allActionKeys.toSeq.sorted.filterNot(Set("pipeline:read", "tap:read")) ++
            Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
        stored("all-caps-sales", caps: _*)
    }

    test(
        "allowedTools hides the 11 listing tools, browse_unity_catalog, query_postgres, query_mongodb and query_natural from a scope-limited key and keeps find_data"
    ) {
        // derived from the route table, then checked against the story's names
        assert(refusedTools == StoryHidden, s"route-derived ${refusedTools.toSeq.sorted} vs story ${StoryHidden.toSeq.sorted}")
        assert(StoryHidden.count(_.startsWith("list_")) == 11)

        bothModes { m =>
            val rk = allCapsLimited
            assert(CallerVisibility.limited(rk))
            val seen = MCPToolRoutes.allowedTools(rk).toSet
            val hidden = MCPToolRoutes.allToolNames.toSet -- seen
            assert(hidden == StoryHidden, s"$m: hidden ${hidden.toSeq.sorted}")
            Kept.foreach(t => assert(seen.contains(t), s"$m: $t must stay visible"))

            for (k <- limitedKeys) {
                val s = MCPToolRoutes.allowedTools(k).toSet
                StoryHidden.foreach(t => assert(!s.contains(t), s"$m: $t visible to ${k.label}"))
            }
            // find_data stays for a limited key holding metadata:read
            assert(MCPToolRoutes.allowedTools(stored("cat-sales", "pipeline:read:catalog=Sales" +: InstallWideCaps: _*)).contains("find_data"))
        }

        // unchanged callers holding the capabilities keep the tools
        val unscoped = stored("unscoped", Seq("pipeline:read", "tap:read") ++ InstallWideCaps: _*)
        assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(unscoped).toSet))
        assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(sessionOf(User.RoleEditor, "ed")).toSet))
        assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(sessionOf(User.RoleViewer, "vic")).toSet))
        assert(MCPToolRoutes.allowedTools(legacyKey) == MCPToolRoutes.allToolNames)
        assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(starKey).toSet))
        assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(templateKey("full-access")).toSet))
        assert((StoryHidden - "query_natural").subsetOf(MCPToolRoutes.allowedTools(templateKey("reporting")).toSet))
        assert((StoryHidden - "query_natural").subsetOf(MCPToolRoutes.allowedTools(templateKey("read-only")).toSet))
        assert((StoryHidden - "query_natural").subsetOf(MCPToolRoutes.allowedTools(recoveryKey).toSet), "recovery key keeps its diagnosis tools")
        val token = TapRunTokens.issue("t_sales_feed", None, 60)
        try assert(StoryHidden.subsetOf(MCPToolRoutes.allowedTools(TapRunTokens.resolve(token).get).toSet))
        finally TapRunTokens.revoke(token)

        // a restricted session's list is unchanged: listings and browse hidden (route), raw queries hidden (bundle)
        val bobSeen = MCPToolRoutes.allowedTools(bob).toSet
        StoryHidden.foreach(t => assert(!bobSeen.contains(t), t))
        Seq("find_data", "get_lineage", "get_provenance", "profile_data").foreach(t => assert(bobSeen.contains(t), t))
    }

    test(
        "the tool list and the gate cannot drift: for a scope-limited key every hidden tool's route is refused and every visible tool's route passes the gate"
    ) {
        refuseMode {
            val rk = allCapsLimited
            val seen = MCPToolRoutes.allowedTools(rk).toSet
            MCPToolRoutes.tools.foreach {
                case (name, MCPToolRoutes.Mapped(method, path)) =>
                    captured.clear()
                    val g = gate(rk, method, path)
                    if (seen.contains(name)) assert(g.cont, s"$name visible but $method $path refused: ${g.status} ${g.body}")
                    else assert(!g.cont && g.status == 403, s"$name hidden but $method $path passes the gate")
                case (name, MCPToolRoutes.Local) => assert(seen.contains(name), name)
            }
        }
    }

    test("GET /api/v1/mcp/tools for a scope-limited key lists none of the hidden tools (what the MCP server and the chats' list_tools receive)") {
        // The Assistant, Ops, Catalog, Search and Configuration chats list tools through the MCP server, which asks
        // this endpoint with the caller's own key (ChatCaller.toolKey) or with the ui key on behalf of a restricted
        // user (narrowed to the session bundle). So the chats' tool sets are this answer.
        def call(rk: ResolvedKey): Seq[String] = {
            val r = mock(classOf[HttpServletRequest])
            when(r.getAttribute(anyString())).thenAnswer(i => if (i.getArgument[String](0) == TenantInterceptor.ResolvedKeyAttr) rk else null)
            val resp = new MCPToolsAPIController().listMcpTools(r)
            assert(resp.getStatusCode.value == 200, resp.getBody)
            JsonParser.parseString(resp.getBody).getAsJsonObject.getAsJsonArray("tools").asScala.map(_.getAsString).toSeq
        }
        val sk = limitedKeys.head
        val tools = call(sk)
        StoryHidden.foreach(t => assert(!tools.contains(t), s"$t listed for ${sk.label}"))
        assert(!tools.exists(_.startsWith("list_postgres_")), tools)
        assert(tools.contains("find_data"))
        val unscoped = stored("unscoped", Seq("pipeline:read", "tap:read") ++ InstallWideCaps: _*)
        assert(StoryHidden.subsetOf(call(unscoped).toSet))
    }

    // ------------------------------------------------------------ additions: route completeness

    private sealed trait Decision
    private case object Refused extends Decision

    /** Not refused at the gate, with the reason. */
    private case class Allowed(reason: String) extends Decision

    private val PerItem = Allowed("per-item scope in the controller (Parts A/B, or the earlier catalog-scoped stories)")
    private val NoData = Allowed("carries no stored pipeline or tap data: stateless, or works only on what the caller sends")
    private val OwnCap = Allowed("its own install-wide capability (Out of scope: audit log, doctor, config, code-repo, tap prompts, protect admin)")
    private val Identity = Allowed("identity / key / user administration or public infrastructure, outside the capability table")
    private val ChatTools = Allowed("chats run tools with the caller's key; the tool list and the gate decide per tool")

    /** Every route row and skip pattern in CapabilityRoutes, with its Part C decision. */
    private val Decisions: Map[(String, String), Decision] = Map(
        ("GET", "/api/v1/pipeline") -> PerItem,
        ("GET", "/api/v1/pipelines") -> PerItem,
        ("GET", "/api/v1/pipelines/*/unity-catalog") -> PerItem,
        ("GET", "/api/v1/pipelines/*/codegen-scripts") -> PerItem,
        ("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions") -> PerItem,
        ("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions/*") -> PerItem,
        ("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions/*/diff") -> PerItem,
        ("POST", "/api/v1/pipelines/*/codegen-scripts/*/versions/*/restore") -> PerItem,
        ("POST", "/api/v1/pipelines/*/codegen-scripts/*/regenerate") -> PerItem,
        ("POST", "/api/v1/pipelines/*/codegen-scripts/*/pull") -> PerItem,
        ("POST", "/api/v1/pipeline") -> PerItem,
        ("DELETE", "/api/v1/pipeline") -> PerItem,
        ("POST", "/api/v1/pipelines/*/rename") -> PerItem,
        ("POST", "/api/v1/pipeline/generate") -> NoData,
        ("POST", "/api/v1/pipeline/upload") -> PerItem,
        ("POST", "/api/v1/pipeline/profile") -> NoData,
        ("GET", "/api/v1/pipeline/status") -> PerItem,
        ("GET", "/api/v1/pipeline/result") -> PerItem,
        ("DELETE", "/api/v1/pipeline/status") -> Allowed("refused to limited callers in the controller (Part A clearAllRefusal)"),
        ("GET", "/api/v1/pipeline/versions") -> PerItem,
        ("GET", "/api/v1/pipeline/version") -> PerItem,
        ("GET", "/api/v1/pipeline/version/diff") -> PerItem,
        ("POST", "/api/v1/pipeline/version/restore") -> PerItem,
        ("GET", "/api/v1/pipeline/dest-types") -> PerItem,
        ("POST", "/api/v1/pipeline/dest-types") -> PerItem,
        ("POST", "/api/v1/pipeline/propose-schema") -> NoData,
        ("POST", "/api/v1/pipeline/protect/suggest") -> PerItem,
        ("POST", "/api/v1/pipeline/protect/preset") -> PerItem,
        ("POST", "/api/v1/protect/reveal") -> PerItem,
        ("POST", "/api/v1/protect/keys/rotate") -> OwnCap,
        ("POST", "/api/v1/catalog/*/enable-ordered") -> PerItem,
        ("GET", "/api/v1/catalog/*/enable-ordered") -> PerItem,
        ("DELETE", "/api/v1/catalog/*/enable-ordered") -> PerItem,
        ("PUT", "/api/v1/catalog/**") -> PerItem,
        ("DELETE", "/api/v1/catalog/**") -> PerItem,
        ("GET", "/api/v1/catalog/*/export") -> PerItem,
        ("POST", "/api/v1/catalog/*/restore") -> PerItem,
        ("GET", "/api/v1/catalog/*/enable-order") -> PerItem,
        ("GET", "/api/v1/tap") -> PerItem,
        ("GET", "/api/v1/taps") -> PerItem,
        ("POST", "/api/v1/tap") -> PerItem,
        ("DELETE", "/api/v1/tap") -> PerItem,
        ("POST", "/api/v1/tap/run") -> PerItem,
        ("POST", "/api/v1/tap/cron") -> NoData,
        ("POST", "/api/v1/tap/script") -> PerItem,
        ("POST", "/api/v1/tap/test") -> PerItem,
        ("POST", "/api/v1/tap/generate") -> PerItem,
        ("GET", "/api/v1/tap/generate/status") -> PerItem,
        ("POST", "/api/v1/tap/fix") -> PerItem,
        ("POST", "/api/v1/tap/review") -> PerItem,
        ("POST", "/api/v1/tap/optimize") -> PerItem,
        ("POST", "/api/v1/tap/brainstorm") -> NoData,
        ("GET", "/api/v1/tap/ledger") -> PerItem,
        ("DELETE", "/api/v1/tap/ledger") -> PerItem,
        ("GET", "/api/v1/tap/state") -> PerItem,
        ("POST", "/api/v1/tap/state") -> PerItem,
        ("DELETE", "/api/v1/tap/state") -> PerItem,
        ("GET", "/api/v1/tap/logs") -> PerItem,
        ("GET", "/api/v1/tap/logs/all") -> PerItem,
        ("GET", "/api/v1/tap/versions") -> PerItem,
        ("GET", "/api/v1/tap/version") -> PerItem,
        ("GET", "/api/v1/tap/version/diff") -> PerItem,
        ("POST", "/api/v1/tap/version/restore") -> PerItem,
        ("POST", "/api/v1/tap/script/pull") -> PerItem,
        ("POST", "/api/v1/tap/migrate-storage") -> PerItem,
        ("GET", "/api/v1/code-repo") -> OwnCap,
        ("PUT", "/api/v1/code-repo") -> OwnCap,
        ("POST", "/api/v1/code-repo/test") -> OwnCap,
        ("GET", "/api/v1/tap-prompts") -> OwnCap,
        ("GET", "/api/v1/tap-prompts/**") -> OwnCap,
        ("POST", "/api/v1/tap-prompts") -> OwnCap,
        ("POST", "/api/v1/tap-prompts/suggest") -> OwnCap,
        ("DELETE", "/api/v1/tap-prompts/**") -> OwnCap,
        ("GET", "/api/v1/secrets") -> Allowed("per-secret _type / owner scope in the controller"),
        ("GET", "/api/v1/secrets/**") -> Allowed("per-secret _type / owner scope in the controller"),
        ("PUT", "/api/v1/secrets/**") -> Allowed("per-secret _type / owner scope in the controller"),
        ("DELETE", "/api/v1/secrets/**") -> Allowed("per-secret _type / owner scope in the controller"),
        ("POST", "/api/v1/query/postgres") -> Refused,
        ("POST", "/api/v1/query/mongodb") -> Refused,
        ("POST", "/api/v1/query/objectstore") -> Allowed("pipeline-bound: pipeline outside scope refused (Part B); takes no sql"),
        ("POST", "/api/v1/query/snowflake") -> Allowed("pipeline-bound: pipeline outside scope refused (Part B); caller sql refused (Part C, controller)"),
        ("POST", "/api/v1/query/databricks") -> Allowed("pipeline-bound: pipeline outside scope refused (Part B); caller sql refused (Part C, controller)"),
        ("POST", "/api/v1/query/sqlserver") -> Allowed("pipeline-bound: pipeline outside scope refused (Part B); caller sql refused (Part C, controller)"),
        ("POST", "/api/v1/query/natural") -> Refused,
        ("POST", "/api/v1/ai/answer") -> Allowed("the model answers only from the context the caller sends; reads no store, runs no SQL"),
        ("POST", "/api/v1/job/kill") -> PerItem,
        ("POST", "/api/v1/search/**") -> PerItem,
        ("GET", "/api/v1/metadata/**") -> Refused,
        ("GET", "/api/v1/vector-stores/available") -> Allowed("names which vector store kinds are up; no collection, table, pipeline or tap"),
        ("POST", "/api/v1/config/generate-schema") -> NoData,
        ("POST", "/api/v1/config/upload") -> OwnCap,
        ("GET", "/api/v1/ai/model-catalog") -> OwnCap,
        ("GET", "/api/v1/doctor") -> OwnCap,
        ("GET", "/api/v1/audit-log") -> OwnCap,
        ("GET", "/api/v1/audit-log/**") -> OwnCap,
        ("GET", "/api/v1/policy") -> Allowed("the agent policy document: no pipeline or tap data"),
        ("PUT", "/api/v1/policy") -> OwnCap,
        ("GET", "/api/v1/approvals") -> Allowed("agents see only their own approvals (controller)"),
        ("GET", "/api/v1/approvals/**") -> Allowed("agents see only their own approvals (controller)"),
        ("POST", "/api/v1/approvals/*/approve") -> Allowed("people decide; role-gated"),
        ("POST", "/api/v1/approvals/*/reject") -> Allowed("people decide; role-gated"),
        ("GET", "/api/v1/incidents") -> PerItem,
        ("GET", "/api/v1/incidents/**") -> PerItem,
        ("POST", "/api/v1/incidents/*/abandon") -> PerItem,
        ("POST", "/api/v1/incidents/*/proceed") -> PerItem,
        ("GET", "/api/v1/activity/signals") -> PerItem,
        ("GET", "/api/v1/provenance") -> PerItem,
        ("GET", "/api/v1/lineage") -> PerItem,
        ("GET", "/api/v1/lineage/**") -> PerItem,
        ("GET", "/api/v1/catalog/find") -> Allowed(
            "hits filtered per pipeline / tap read (story: allowedTools keeps find_data); UC branch: see OPEN QUESTIONS"
        ),
        ("GET", "/api/v1/unity-catalog/browse") -> Refused,
        // skip patterns (any method)
        ("*", "/api/v1/auth/**") -> Identity,
        ("*", "/api/v1/login") -> Identity,
        ("*", "/api/v1/logout") -> Identity,
        ("*", "/api/v1/change-password") -> Identity,
        ("*", "/api/v1/me") -> Identity,
        ("*", "/api/v1/users") -> Identity,
        ("*", "/api/v1/users/**") -> Identity,
        ("*", "/api/v1/keys") -> Identity,
        ("*", "/api/v1/keys/**") -> Identity,
        ("*", "/api/v1/health/**") -> Identity,
        ("*", "/api/v1/version") -> Identity,
        ("*", "/api/v1/mcp/activity") -> Allowed("refused to limited callers in the controller (Part A activityRefusal)"),
        ("*", "/api/v1/mcp/tools") -> Allowed("the answer is allowedTools, filtered by Part C"),
        ("*", "/api/v1/assistant/**") -> ChatTools,
        ("*", "/api/v1/ops-chat/**") -> ChatTools,
        ("*", "/api/v1/catalog-chat/**") -> ChatTools,
        ("*", "/api/v1/search-chat/**") -> ChatTools,
        ("*", "/api/v1/config-chat/**") -> ChatTools
    )

    private def privateSeq(name: String): Seq[Any] = {
        val f =
            try CapabilityRoutes.getClass.getDeclaredField(name)
            catch { case _: NoSuchFieldException => fail(s"CapabilityRoutes has no '$name' field; update this drift guard with the new table") }
        f.setAccessible(true)
        f.get(CapabilityRoutes).asInstanceOf[Seq[Any]]
    }

    private def tableRows: Seq[(String, String)] = privateSeq("routes").map {
        case p: Product => (p.productElement(0).toString, p.productElement(1).toString)
        case other => fail("unexpected route row " + other)
    }

    private def skipRows: Seq[(String, String)] = privateSeq("skipPatterns").map(p => ("*", p.toString))

    private def concrete(pattern: String): String =
        pattern.replace("/**", "/example/item").replace("*", "example")

    test("route completeness: every route and skip pattern has a Part C decision, refused ones are refused to a scope-limited key, the rest pass the gate") {
        val all = tableRows ++ skipRows
        assert(all.size > 100, "the reflective read found too few rows: " + all.size)
        val undecided = all.filterNot(Decisions.contains)
        assert(undecided.isEmpty, "routes added without a Part C decision (refuse to limited keys, or allowlist with a reason): " + undecided.mkString(", "))
        val stale = Decisions.keySet.toSeq.filterNot(all.contains)
        assert(stale.isEmpty, "decisions for routes no longer in CapabilityRoutes: " + stale.mkString(", "))
        assert(Decisions.toSeq.collect { case (k, Refused) => k }.toSet == RefusedPatterns.toSet)

        refuseMode {
            tableRows.foreach { case (method, pattern) =>
                val path = concrete(pattern)
                val req = CapabilityRoutes.lookup(method, path) match {
                    case RouteCheck.Require(r, a) => r + ":" + a
                    case other => fail(s"$method $path ($pattern) -> $other")
                }
                val rk = stored("route-sales", req, "pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
                assert(CallerVisibility.limited(rk))
                captured.clear()
                val g = gate(rk, method, path)
                Decisions((method, pattern)) match {
                    case Refused => assertKeyRefusal(rk, method, path, g, s"$method $pattern")
                    case Allowed(reason) =>
                        assert(g.cont, s"$method $pattern is allowlisted ($reason) but the gate refused a limited key holding $req: ${g.status} ${g.body}")
                }
            }
        }
    }
}
