package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.auth.{Allow, CapabilityScopes, ChatCaller, ChatIdentity, MCPToolRoutes, Refuse, RoleToolSets, TapRunTokens}
import ai.datris.config.{CapabilityInterceptor, RequiresRole, RoleEnforcementInterceptor, TenantInterceptor}
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, TapConfig, TenantContext, User, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import com.google.gson.{JsonObject, JsonParser}
import org.mockito.Mockito.{mock, when}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod

import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._

/** Story: In-platform chats act with the signed-in user's role, not the
  * platform key's (plans/stories/chat-session-role-authority.md): the
  * "Caller classes, before -> after" table, for every chat endpoint and
  * every caller class.
  *
  * For each (caller, chat route) the request runs the real interceptor
  * chain up to the controller: TenantInterceptor, the session user
  * (SessionAuthenticator's UserContext), CapabilityInterceptor and
  * RoleEnforcementInterceptor (with a mocked HandlerMethod answering the real
  * controller class and method), then `ChatCaller.from`, the decision every
  * chat controller makes first. No controller is called, no stream opens,
  * no model or MCP server is reached. For an allowed caller, the tools are
  * listed the way the chat's MCP calls reach the server: the key
  * `ChatCaller.toolKey` picks, plus `X-Datris-On-Behalf-Of` from the
  * worker's UserContext (`ChatCaller.onWorker`, as MCPClient sends it),
  * through TenantInterceptor into `MCPToolsAPIController.listMcpTools`.
  *
  * Keys on resolve through the key resolution cache (ChatAuthorityFixtures);
  * no secret store is read. */
class ChatRoleAuthorityMatrixSpec extends AnyFunSuite with BeforeAndAfterEach with BeforeAndAfterAll {

    private var saved: DatrisEnvironment = _

    private def keysOnEnv: DatrisEnvironment = InMemoryUserDb.env().copy(useApiKeys = true)
    private def keysOffEnv: DatrisEnvironment = InMemoryUserDb.env()
    private def authOffEnv: DatrisEnvironment = InMemoryUserDb.env().copy(useUserAuth = false)

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = keysOnEnv
        val db = new InMemoryUserDb
        UserStore.dbOverride = db
        allUsers.foreach(db.put)
        APIKeyValidator.invalidateCache()
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    // ------------------------------------------------------------ routes

    private case class ChatRoute(name: String, method: String, path: String, cls: Class[_], methodName: String) {
        lazy val handler: java.lang.reflect.Method =
            cls.getDeclaredMethods.find(m => m.getName == methodName && AnnotatedElementUtils.findMergedAnnotation(m, classOf[RequestMapping]) != null)
                .getOrElse(fail(s"no mapped $methodName on ${cls.getSimpleName}"))
        def handlerMethod: HandlerMethod = {
            val hm = mock(classOf[HandlerMethod])
            when(hm.getMethodAnnotation(classOf[RequiresRole])).thenReturn(AnnotatedElementUtils.findMergedAnnotation(handler, classOf[RequiresRole]))
            when(hm.getBeanType).thenAnswer(_ => cls)
            hm
        }
    }

    private val Routes: Seq[ChatRoute] = Seq(
        ChatRoute("assistant", "POST", "/api/v1/assistant/chat", classOf[AssistantAPIController], "chat"),
        ChatRoute("ops", "POST", "/api/v1/ops-chat/chat", classOf[OpsChatAPIController], "chat"),
        ChatRoute("catalog", "POST", "/api/v1/catalog-chat/chat", classOf[CatalogChatAPIController], "chat"),
        ChatRoute("search", "POST", "/api/v1/search-chat/chat", classOf[SearchChatAPIController], "chat"),
        ChatRoute("config", "POST", "/api/v1/config-chat/chat", classOf[ConfigChatAPIController], "chat"),
        ChatRoute("init", "GET", "/api/v1/assistant/init", classOf[AssistantAPIController], "init"),
        ChatRoute("attachment", "POST", "/api/v1/assistant/attachment", classOf[AssistantAttachmentController], "stage")
    )

    private val ChatNames = Seq("assistant", "ops", "catalog", "search")

    test("the matrix covers every chat endpoint the controllers map, with the controllers' own paths") {
        for (rt <- Routes) {
            val a = AnnotatedElementUtils.findMergedAnnotation(rt.handler, classOf[RequestMapping])
            val prefix = AnnotatedElementUtils.findMergedAnnotation(rt.cls, classOf[RequestMapping]).path().head
            assert((a.path().toSeq ++ a.value()).map(prefix + _).contains(rt.path), rt.name)
            assert(a.method().map(_.name()).contains(rt.method), rt.name)
        }
        // every controller method that decides with ChatCaller.from is in the matrix
        val chatControllers = Seq(
            classOf[AssistantAPIController],
            classOf[OpsChatAPIController],
            classOf[CatalogChatAPIController],
            classOf[SearchChatAPIController],
            classOf[ConfigChatAPIController],
            classOf[AssistantAttachmentController]
        )
        val mapped = for {
            c <- chatControllers
            m <- c.getDeclaredMethods.toSeq
            a <- Option(AnnotatedElementUtils.findMergedAnnotation(m, classOf[RequestMapping])).toSeq
            p <- a.path().toSeq ++ a.value()
            if p.contains("chat") || p.contains("assistant")
        } yield c.getSimpleName + "." + m.getName
        assert(mapped.toSet == Routes.map(r => r.cls.getSimpleName + "." + r.methodName).toSet, mapped)
    }

    // ------------------------------------------------------------ callers

    private case class Caller(
        name: String,
        session: Option[User] = None,
        headers: Seq[(String, String)] = Nil,
        seeds: Seq[(String, ResolvedKey)] = Nil,
        rejection: Option[String] = None
    ) {
        def presented: String = headers.find(_._1 == "x-api-key").map(_._2).orNull
        override def toString: String = name
    }

    private val fullKey = RoleToolSets.stored("full", "*:*")
    private val salesKey = RoleToolSets.stored("ci_scoped", RoleToolSets.CatalogSalesCaps: _*)
    private val ownerKey = RoleToolSets.stored("rag-builder", RoleToolSets.OwnerSelfCaps: _*)
    private val inertKey = RoleToolSets.stored("inert-only", RoleToolSets.InertOnlyCaps: _*)

    private def withKey(name: String, value: String, rk: ResolvedKey, extra: (String, String)*): Caller =
        Caller(name, headers = ("x-api-key" -> value) +: extra, seeds = Seq(value -> rk))

    private def uiOnBehalfOf(u: User): Caller =
        withKey("ui key on behalf of " + u.username, UiValue, uiLegacy, AuditActor.HeaderOnBehalfOf -> u.username)

    // ------------------------------------------------------------ expectations

    private sealed trait Expect
    private case class Refused(status: Int, body: String) extends Expect
    private case class Allowed(user: Option[String], ownKey: Boolean, tools: Seq[String]) extends Expect

    private val InsufficientRole = Refused(403, """{"error":"Insufficient role"}""")
    private val ReadOnlyRole = Refused(403, """{"error":"Read-only role"}""")
    private val AuthRequired = Refused(401, ApiErrors.AuthenticationRequiredBody)
    private val RejectedKey = Refused(401, CapabilityInterceptor.RejectedKeyBody)
    private val TapRefused = Refused(403, ChatCaller.TapTokenRefusedBody)

    private val All: Seq[String] = MCPToolRoutes.allToolNames

    private case class Row(caller: Caller, chats: Expect, init: Expect, attachment: Expect, config: Expect, incidents: Boolean) {
        def expect(route: String): Expect = route match {
            case "init" => init
            case "attachment" => attachment
            case "config" => config
            case _ => chats
        }
    }

    private def same(e: Expect, config: Expect, incidents: Boolean)(c: Caller): Row = Row(c, e, e, e, config, incidents)

    /** Keys on, user auth on: the story's table, one row per caller class. */
    private def keysOnRows: Seq[Row] = {
        val viewerChat = (u: User, tools: Seq[String]) => {
            val a = Allowed(Some(u.username), ownKey = false, tools)
            Row(Caller(u.username + " session", session = Some(u)), a, a, ReadOnlyRole, InsufficientRole, incidents = false)
        }
        val tap = TapRunTokens.issue("ci_probe", None, 600)
        issued = tap :: issued
        Seq(
            same(Allowed(Some("root"), ownKey = false, All), Allowed(Some("root"), ownKey = false, All), incidents = true)(Caller(
                "admin session",
                session = Some(root)
            )),
            same(Allowed(Some("ed"), ownKey = false, RoleToolSets.Editor), InsufficientRole, incidents = false)(Caller("editor session", session = Some(ed))),
            viewerChat(vic, RoleToolSets.Viewer),
            same(Allowed(Some("bob"), ownKey = false, RoleToolSets.RestrictedEditorSales), InsufficientRole, incidents = false)(
                Caller("restricted editor session", session = Some(bob))
            ),
            viewerChat(rita, RoleToolSets.RestrictedViewerSales),
            same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(withKey(
                "full-access *:* key",
                "full-value",
                fullKey
            )),
            same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(withKey("legacy key", LegacyValue, legacy)),
            same(Allowed(None, ownKey = true, RoleToolSets.CatalogSalesKey), InsufficientRole, incidents = false)(withKey(
                "catalog=Sales key",
                "sales-value",
                salesKey
            )),
            same(Allowed(None, ownKey = true, RoleToolSets.OwnerSelfKey), InsufficientRole, incidents = false)(withKey(
                "owner=self key",
                "owner-value",
                ownerKey
            )),
            same(Allowed(None, ownKey = true, RoleToolSets.InertOnlyKey), InsufficientRole, incidents = false)(withKey(
                "inert-only key",
                "inert-value",
                inertKey
            )),
            same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(withKey("ui key, no header", UiValue, uiLegacy)),
            same(Allowed(Some("root"), ownKey = false, All), Allowed(Some("root"), ownKey = false, All), incidents = true)(uiOnBehalfOf(root)),
            same(Allowed(Some("ed"), ownKey = false, RoleToolSets.Editor), InsufficientRole, incidents = false)(uiOnBehalfOf(ed)),
            same(Allowed(Some("vic"), ownKey = false, RoleToolSets.Viewer), InsufficientRole, incidents = false)(uiOnBehalfOf(vic)),
            same(Allowed(Some("bob"), ownKey = false, RoleToolSets.RestrictedEditorSales), InsufficientRole, incidents = false)(uiOnBehalfOf(bob)),
            same(Allowed(Some("rita"), ownKey = false, RoleToolSets.RestrictedViewerSales), InsufficientRole, incidents = false)(uiOnBehalfOf(rita)),
            // a key that is not the ui key: the header is not trusted, neither narrowing nor relabelling
            same(Allowed(None, ownKey = true, RoleToolSets.CatalogSalesKey), InsufficientRole, incidents = false)(
                withKey("catalog=Sales key naming root", "sales-value", salesKey, AuditActor.HeaderOnBehalfOf -> "root")
            ),
            same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(
                withKey("*:* key (not ui) naming ed", "full-value", fullKey, AuditActor.HeaderOnBehalfOf -> "ed")
            ),
            same(TapRefused, InsufficientRole, incidents = false)(Caller("tap-run token", headers = Seq("x-api-key" -> tap))),
            same(TapRefused, InsufficientRole, incidents = false)(Caller(
                "tap-run token naming ed",
                headers = Seq("x-api-key" -> tap, AuditActor.HeaderOnBehalfOf -> "ed")
            )),
            same(AuthRequired, AuthRequired, incidents = false)(Caller("no credential")),
            same(RejectedKey, RejectedKey, incidents = false)(
                Caller("bogus key", headers = Seq("x-api-key" -> "bogus"), rejection = Some(APIKeyValidator.InvalidKeyMessage))
            ),
            same(RejectedKey, RejectedKey, incidents = false)(
                Caller("revoked key", headers = Seq("x-api-key" -> "old"), rejection = Some("API key 'old' is revoked"))
            ), {
                val outage = Refused(503, CapabilityInterceptor.rejectionResponse(APIKeyValidator.KeyStoreUnavailableMessage)._2)
                same(outage, outage, incidents = false)(
                    Caller("key store outage", headers = Seq("x-api-key" -> "any"), rejection = Some(APIKeyValidator.KeyStoreUnavailableMessage))
                )
            }
        )
    }

    /** Keys off, user auth on (Decision 5): sessions are held to the role bundle in the chat. */
    private def keysOffRows: Seq[Row] = Seq(
        same(Allowed(Some("root"), ownKey = false, All), Allowed(Some("root"), ownKey = false, All), incidents = true)(Caller(
            "keys off: admin session",
            session = Some(root)
        )),
        same(Allowed(Some("ed"), ownKey = false, RoleToolSets.Editor), InsufficientRole, incidents = false)(Caller(
            "keys off: editor session",
            session = Some(ed)
        )), {
            val a = Allowed(Some("vic"), ownKey = false, RoleToolSets.Viewer)
            Row(Caller("keys off: viewer session", session = Some(vic)), a, a, ReadOnlyRole, InsufficientRole, incidents = false)
        },
        same(Allowed(Some("bob"), ownKey = false, RoleToolSets.RestrictedEditorSales), InsufficientRole, incidents = false)(
            Caller("keys off: restricted editor session", session = Some(bob))
        ),
        // no session, no key: the legacy no-auth programmatic path, as before
        // (the Configuration chat's 403 is the story's reading; OPEN QUESTION)
        same(Allowed(None, ownKey = false, All), InsufficientRole, incidents = true)(Caller("keys off: no credential")),
        same(Allowed(Some("ed"), ownKey = false, RoleToolSets.Editor), InsufficientRole, incidents = false)(
            Caller("keys off: anonymous on behalf of ed", headers = Seq(AuditActor.HeaderOnBehalfOf -> "ed"))
        )
    )

    /** User auth off, keys off: unchanged, everything as the anonymous full-access identity. */
    private def authOffRows: Seq[Row] = Seq(
        same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(Caller("auth off: no credential")),
        same(Allowed(None, ownKey = false, All), Allowed(None, ownKey = false, All), incidents = true)(
            Caller("auth off: header naming ed", headers = Seq(AuditActor.HeaderOnBehalfOf -> "ed"))
        )
    )

    @volatile private var issued: List[String] = Nil
    override def afterAll(): Unit = { issued.foreach(TapRunTokens.revoke); super.afterAll() }

    // ------------------------------------------------------------ driving one request

    /** Switch the environment; the resolution cache holds the keys-off identity under "". */
    private def useEnv(e: DatrisEnvironment): Unit = {
        DatrisEnvironment.values = e
        APIKeyValidator.invalidateCache()
    }

    private def seedAll(c: Caller): Unit = if (DatrisEnvironment.values.useApiKeys) {
        seedKey(UiValue, uiLegacy)
        c.seeds.foreach { case (v, rk) => seedKey(v, rk) }
    }

    /** The decision a chat route makes for `c`, up to and including ChatCaller.from. */
    private def decide(c: Caller, rt: ChatRoute): Either[Refused, ChatIdentity] = {
        seedAll(c)
        val (r, attrs) = request(rt.method, rt.path, c.headers: _*)
        val cap = new Captured
        c.rejection match {
            case Some(reason) => attrs.put(TenantInterceptor.ApiKeyRejectedAttr, reason)
            case None => assert(new TenantInterceptor().preHandle(r, cap.response, null))
        }
        UserContext.clear()
        c.session.foreach(UserContext.set)
        try {
            val hm = rt.handlerMethod
            if (!new CapabilityInterceptor().preHandle(r, cap.response, hm)) Left(Refused(cap.status.get, cap.body))
            else if (!new RoleEnforcementInterceptor().preHandle(r, cap.response, hm)) Left(Refused(cap.status.get, cap.body))
            else
                ChatCaller.from(r) match {
                    case Allow(id) => Right(id)
                    case Refuse(s, b) => Left(Refused(s, b))
                }
        } finally UserContext.clear()
    }

    /** The tools the chat's MCP calls see for `id`, and the identity they run with. */
    private def toolsOf(c: Caller, id: ChatIdentity): (Seq[String], ResolvedKey) = {
        val keysOn = DatrisEnvironment.values.useApiKeys
        val key = ChatCaller.toolKey(id, c.presented, () => if (keysOn) UiValue else ChatCaller.uiApiKey(c.presented))
        val obo = new AtomicReference[Option[String]](None)
        ChatCaller.onWorker(id, None)(obo.set(UserContext.get().map(_.username)))
        seedAll(c)
        val headers = Option(key).map("x-api-key" -> _).toSeq ++ obo.get.map(AuditActor.HeaderOnBehalfOf -> _)
        val (r, attrs) = request("GET", "/api/v1/mcp/tools", headers: _*)
        assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
        UserContext.clear() // the MCP server carries no session cookie
        val resp = new MCPToolsAPIController().listMcpTools(r)
        assert(resp.getStatusCode.value == 200, resp.getBody)
        val tools = JsonParser.parseString(resp.getBody).getAsJsonObject.getAsJsonArray("tools").asScala.map(_.getAsString).toSeq
        (tools, resolved(attrs).getOrElse(fail("no identity on the tool request")))
    }

    private def check(rows: Seq[Row], env: String): Unit =
        for (row <- rows; rt <- Routes) {
            val where = s"[$env] ${row.caller} ${rt.method} ${rt.path}"
            (row.expect(rt.name), decide(row.caller, rt)) match {
                case (r: Refused, Left(got)) => assert(got == r, where)
                case (Allowed(user, own, tools), Right(id)) =>
                    assert(id.user.map(_.username) == user, s"$where: user ${id.user}")
                    assert(id.ownKey == own, s"$where: ownKey ${id.ownKey}")
                    val (got, rk) = toolsOf(row.caller, id)
                    assert(
                        got == tools,
                        s"$where: ${got.size} tools as ${rk.label}; missing ${tools.filterNot(got.contains)}, extra ${got.filterNot(tools.contains)}"
                    )
                    // the tools never exceed what the caller's own identity holds
                    if (!rk.isFullAccess) assert(got.forall(MCPToolRoutes.allowedTools(rk).contains), where)
                case (e, got) => fail(s"$where: expected $e, got $got")
            }
        }

    // ------------------------------------------------------------ the matrix

    test("keys on, user auth on: every chat endpoint for every caller class (allowed, as whom, with which tools, or the refusal)") {
        check(keysOnRows, "keys on")
    }

    test("keys off, user auth on (Decision 5): sessions chat with their role bundle; the keys-off programmatic path is unchanged") {
        DatrisEnvironment.values = keysOffEnv
        check(keysOffRows, "keys off")
    }

    test("unchanged: user auth off: every chat endpoint answers the anonymous full-access identity with every tool") {
        DatrisEnvironment.values = authOffEnv
        check(authOffRows, "auth off")
    }

    test("a viewer's chat runs read-only on every chat endpoint: no listed tool writes, runs, deletes, uploads or decides") {
        for (env <- Seq(keysOnEnv, keysOffEnv); u <- Seq(vic, rita); rt <- Routes.filter(r => ChatNames.contains(r.name) || r.name == "init")) {
            useEnv(env)
            val c = Caller(u.username, session = Some(u))
            val id = decide(c, rt).fold(r => fail(s"${u.username} ${rt.name} refused $r"), identity)
            val (tools, rk) = toolsOf(c, id)
            assert(tools.filter(RoleToolSets.mutating).isEmpty, s"${u.username} ${rt.name} as ${rk.label}")
            assert(!rk.isFullAccess)
        }
    }

    // ------------------------------------------------------------ Decision 4: the Operations chat incident block

    private val t0: Instant = Instant.parse("2026-11-18T10:00:00Z")
    private val incidents: List[Incident] = List(
        Incident("inc-hr", "pipeline_failure", "pipeline", "hr_payroll", t0, "diagnosing", new JsonObject(), List(IncidentStep(t0, "open", "opened"))),
        Incident("inc-sales", "tap_failure", "tap", "t_sales", t0, "diagnosing", new JsonObject(), List(IncidentStep(t0, "open", "tap t_sales failed")))
    )
    private val failRead: String => PipelineConfig = n => fail("no pipeline read expected: " + n)
    private val failReadTap: String => TapConfig = n => fail("no tap read expected: " + n)

    private def incidentBlockFor(id: ChatIdentity): Option[List[Incident]] = {
        var reads = 0
        val loaded = incidents
        val got = OpsChatAPIController.incidentsFor(id, () => { reads += 1; loaded }, failRead, failReadTap, items = IncidentsAPIController.NoItems)
        if (got.isEmpty) { assert(reads == 0, s"$id: the incident store was read for a caller that gets none"); None }
        else { assert(got eq loaded, s"$id: an unlimited caller gets the loaded list itself"); Some(got) }
    }

    test("the Operations chat incident block follows incident:read for every caller class, the store unread for those refused") {
        for ((env, rows) <- Seq(keysOnEnv -> keysOnRows, keysOffEnv -> keysOffRows, authOffEnv -> authOffRows); row <- rows) {
            useEnv(env)
            val ops = Routes.find(_.name == "ops").get
            decide(row.caller, ops) match {
                case Right(id) =>
                    assert(ChatCaller.mayReadIncidents(id) == row.incidents, s"${row.caller}: mayReadIncidents")
                    assert(incidentBlockFor(id).isDefined == row.incidents, s"${row.caller}: incident block")
                case Left(_) => assert(!row.incidents, s"${row.caller} is refused the chat")
            }
        }
    }

    test("an editor or viewer session gets no incidents even when the request carries a full-access key identity (keys off: anonymous)") {
        for (u <- Seq(ed, vic, bob, rita); k <- Seq(anonymous, uiLegacy, legacy)) {
            val id = ChatIdentity(Some(u), Some(k), ownKey = false)
            assert(!ChatCaller.mayReadIncidents(id), s"${u.username} with ${k.label}")
            assert(incidentBlockFor(id).isEmpty, s"${u.username} with ${k.label}")
        }
        // an admin session is judged as an admin, whatever key rides along
        for (k <- Seq(None, Some(anonymous), Some(RoleToolSets.stored("ops-ro", "pipeline:read")))) {
            val id = ChatIdentity(Some(root), k, ownKey = false)
            assert(ChatCaller.mayReadIncidents(id), String.valueOf(k))
            assert(incidentBlockFor(id).isDefined)
        }
    }

    test("unchanged: keys with unscoped incident:read get the block, a scope-limited one is filtered, keys without it get none") {
        val unscoped = Seq(RoleToolSets.stored("ops-inc", "incident:read", "pipeline:read"), RoleToolSets.stored("monitor", "job:read", "incident:read"))
        unscoped.foreach(k => assert(incidentBlockFor(ChatIdentity(None, Some(k), ownKey = true)).isDefined, k.label))
        // limited by a catalog= scope on pipelines: allowed, then filtered (ScopedKeyRecordVisibilitySpec pins the filter)
        val limited = RoleToolSets.stored("ops-inc-sales-p", "incident:read", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
        val lid = ChatIdentity(None, Some(limited), ownKey = true)
        assert(ChatCaller.mayReadIncidents(lid))
        assert(OpsChatAPIController.incidentsFor(
            lid,
            () => incidents,
            _ => throw new RuntimeException("store down"),
            _ => throw new RuntimeException("store down"),
            items = IncidentsAPIController.NoItems
        ) == Nil)
        Seq(
            RoleToolSets.stored("ops-inc-scoped", "incident:read:catalog=Sales", "pipeline:read"),
            RoleToolSets.stored("ops-ro", "pipeline:read", "tap:read"),
            salesKey,
            ownerKey,
            inertKey
        ).foreach(k => assert(incidentBlockFor(ChatIdentity(None, Some(k), ownKey = true)).isEmpty, k.label))
    }

    test("keys on: the chat gives a caller incidents exactly when GET /api/v1/incidents admits that same caller") {
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "log-only set in this environment")
        assume(sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty)
        val ops = Routes.find(_.name == "ops").get
        for (row <- keysOnRows; id <- decide(row.caller, ops).toOption) {
            seedAll(row.caller)
            val (r, _) = request("GET", "/api/v1/incidents", row.caller.headers: _*)
            val cap = new Captured
            assert(new TenantInterceptor().preHandle(r, cap.response, null))
            row.caller.session.foreach(UserContext.set)
            val rest =
                try new CapabilityInterceptor().preHandle(r, cap.response, null)
                finally UserContext.clear()
            val chat = incidentBlockFor(id).isDefined
            assert(chat == rest, s"${row.caller}: chat incidents=$chat, GET /api/v1/incidents admitted=$rest ${cap.body}")
        }
    }

    // ------------------------------------------------------------ async and streaming

    test("a pooled SSE worker that served an admin chat serves the next editor chat as the editor, on every tool call") {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val adminId = decide(Caller("admin", session = Some(root)), Routes.head).toOption.get
            val edId = decide(Caller("ed", session = Some(ed)), Routes.head).toOption.get
            val vicId = decide(Caller("vic", session = Some(vic)), Routes.head).toOption.get
            val seen = new java.util.concurrent.ConcurrentLinkedQueue[String]()
            def chatOn(id: ChatIdentity, calls: Int): Unit =
                pool.submit(new Runnable {
                    override def run(): Unit = ChatCaller.onWorker(id, None) {
                        (1 to calls).foreach(_ => seen.add(UserContext.get().map(_.username).getOrElse("-")))
                    }
                }).get(10, TimeUnit.SECONDS)
            chatOn(adminId, 2)
            chatOn(edId, 3)
            chatOn(vicId, 2)
            // a task that is not a chat on the same worker sees no user at all
            pool.submit(new Runnable { override def run(): Unit = seen.add(UserContext.get().map(_.username).getOrElse("-")) }).get(10, TimeUnit.SECONDS)
            assert(seen.asScala.toList == List("root", "root", "ed", "ed", "ed", "vic", "vic", "-"))
            // and the identity each tool call is narrowed to, from that header
            for ((u, tools) <- Seq(root -> All, ed -> RoleToolSets.Editor, vic -> RoleToolSets.Viewer)) {
                val id = decide(Caller(u.username, session = Some(u)), Routes.head).toOption.get
                val result = new AtomicReference[Seq[String]]()
                pool.submit(new Runnable {
                    override def run(): Unit = result.set(toolsOf(Caller(u.username, session = Some(u)), id)._1)
                }).get(10, TimeUnit.SECONDS)
                assert(result.get == tools, u.username)
            }
        } finally pool.shutdownNow()
    }

    test("a second request on the same servlet thread from a different user is decided as that user, and an anonymous one as nobody") {
        val order = Seq(root, ed, vic, bob, rita, ed, root)
        for (u <- order) {
            // SessionAuthenticator clears the thread first; then the cookie's user
            new ai.datris.config.SessionAuthenticator().preHandle(request("POST", "/api/v1/assistant/chat")._1, new Captured().response, null)
            val id = decide(Caller(u.username, session = Some(u)), Routes.head).toOption.get
            assert(id.user.contains(u), u.username)
            val expected = Map(
                "root" -> All,
                "ed" -> RoleToolSets.Editor,
                "vic" -> RoleToolSets.Viewer,
                "bob" -> RoleToolSets.RestrictedEditorSales,
                "rita" -> RoleToolSets.RestrictedViewerSales
            )
            assert(toolsOf(Caller(u.username, session = Some(u)), id)._1 == expected(u.username), u.username)
        }
        // a leftover user on the thread (an SSE request that skipped afterCompletion) is not used
        UserContext.set(root)
        new ai.datris.config.SessionAuthenticator().preHandle(request("POST", "/api/v1/assistant/chat")._1, new Captured().response, null)
        assert(decide(Caller("no credential"), Routes.head) == Left(AuthRequired))
    }

    test("init lists the same tools the chat uses: GET /api/v1/assistant/init is decided like the chats, read for viewers") {
        for (u <- Seq(root, ed, vic, bob, rita)) {
            val c = Caller(u.username, session = Some(u))
            val viaInit = decide(c, Routes.find(_.name == "init").get).toOption.get
            val viaChat = decide(c, Routes.head).toOption.get
            assert(viaInit == viaChat, u.username)
            assert(toolsOf(c, viaInit)._1 == toolsOf(c, viaChat)._1, u.username)
        }
    }

    test("the Search chat prompt treats every user (narrowed or not) as not key-limited, and a scoped key as limited, as before") {
        for (u <- Seq(root, ed, vic, bob, rita)) {
            val id = decide(Caller(u.username, session = Some(u)), Routes.find(_.name == "search").get).toOption.get
            assert(!SearchChatAPIController.keyLimited(id), u.username)
            val obo = decide(uiOnBehalfOf(u), Routes.find(_.name == "search").get).toOption.get
            assert(!SearchChatAPIController.keyLimited(obo), "on behalf of " + u.username)
        }
        val sales = decide(withKey("sales", "sales-value", salesKey), Routes.find(_.name == "search").get).toOption.get
        assert(SearchChatAPIController.keyLimited(sales))
    }

    test("a full-access key's Configuration chat still passes the gate and the decision (the controller then requires a session, as before)") {
        val config = Routes.find(_.name == "config").get
        for (c <- Seq(withKey("full", "full-value", fullKey), withKey("legacy", LegacyValue, legacy), withKey("ui", UiValue, uiLegacy))) {
            val id = decide(c, config).toOption.getOrElse(fail(c.name))
            assert(id.user.isEmpty && !id.ownKey)
            assert(ConfigChatAPIController.actorFor(useUserAuth = true, None).isEmpty, "no session user: the controller's own 403")
        }
    }
}
