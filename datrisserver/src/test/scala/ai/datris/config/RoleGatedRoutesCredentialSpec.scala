package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.ApiErrors
import ai.datris.auth.{ScopeDenialFixtures, TapRunTokens}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.`type`.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod

import java.io.{PrintWriter, StringWriter}
import scala.collection.JavaConverters._

/** Story: In-platform chats act with the signed-in user's role, not the
  * platform key's (plans/stories/chat-session-role-authority.md), Goal
  * defects 2 and 3, Decisions 3 and 6.
  *
  * Driven through the real `RoleEnforcementInterceptor.preHandle` for every
  * handler mapping of the application, read by reflection from the
  * controllers' own mapping annotations (as HandlerMappingDecisionSpec
  * does), so a role-gated route or a chat route added later is covered
  * without editing this spec. The handler is a mocked `HandlerMethod` that
  * answers the real controller class and the real `@RequiresRole`; no
  * controller is instantiated and nothing is called.
  *
  * Decision 6: a caller with no credential gets 401 on every role-gated
  * route, a rejected key 401, a key-store outage 503; a caller WITH a
  * credential but without the role keeps the 403 `Insufficient role`.
  * Decision 3: a viewer session may POST exactly the four chat routes (and
  * the credential-free auth endpoints, exempt as before). */
class RoleGatedRoutesCredentialSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    /** Keys on, user auth on, audit log off. */
    private def keysOn: DatrisEnvironment = InMemoryUserDb.env().copy(useApiKeys = true)

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = keysOn
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    // ------------------------------------------------------------ mappings

    private case class Route(method: String, pattern: String, cls: Class[_], handler: java.lang.reflect.Method) {
        val ann: Option[RequiresRole] =
            Option(AnnotatedElementUtils.findMergedAnnotation(handler, classOf[RequiresRole]))
                .orElse(Option(cls.getAnnotation(classOf[RequiresRole])))
        def roles: Set[String] = ann.map(_.value().toSet).getOrElse(Set.empty)
        def path: String = pattern.replaceAll("\\{\\*[^}]+\\}", "example/item").replaceAll("\\{[^}]+\\}", "example")
        override def toString: String = method + " " + pattern + " (" + cls.getSimpleName + "." + handler.getName + ")"
    }

    /** Every handler mapping under /api of every application controller. */
    private lazy val mappings: Seq[Route] = {
        val scanner = new ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(new AnnotationTypeFilter(classOf[Controller]))
        val classes = scanner.findCandidateComponents("ai.datris").asScala.toSeq.map(bd => Class.forName(bd.getBeanClassName))
            .filterNot(c => Option(c.getProtectionDomain.getCodeSource).exists(_.getLocation.getPath.contains("test-classes")))
        classes.flatMap { cls =>
            val prefixes = Option(AnnotatedElementUtils.findMergedAnnotation(cls, classOf[RequestMapping]))
                .map(a => (a.path().toSeq ++ a.value().toSeq).distinct)
                .filter(_.nonEmpty)
                .getOrElse(Seq(""))
            cls.getDeclaredMethods.toSeq.flatMap { m =>
                Option(AnnotatedElementUtils.findMergedAnnotation(m, classOf[RequestMapping])).toSeq.flatMap { a =>
                    val paths = (a.path().toSeq ++ a.value().toSeq).distinct match {
                        case Seq() => Seq("")
                        case ps => ps
                    }
                    for (pre <- prefixes; p <- paths; meth <- a.method().toSeq.map(_.name())) yield Route(meth, pre + p, cls, m)
                }
            }
        }.filter(_.pattern.startsWith("/api/"))
    }

    private lazy val roleGated: Seq[Route] = mappings.filter(_.ann.isDefined)

    private val ChatPosts: Set[String] =
        Set("/api/v1/assistant/chat", "/api/v1/ops-chat/chat", "/api/v1/catalog-chat/chat", "/api/v1/search-chat/chat")

    // ------------------------------------------------------------ driving the gate

    private case class Outcome(proceed: Boolean, status: Option[Int], body: String)

    private def handlerFor(rt: Route): HandlerMethod = {
        val hm = mock(classOf[HandlerMethod])
        when(hm.getMethodAnnotation(classOf[RequiresRole])).thenReturn(AnnotatedElementUtils.findMergedAnnotation(rt.handler, classOf[RequiresRole]))
        when(hm.getBeanType).thenAnswer(_ => rt.cls)
        hm
    }

    /** One request through RoleEnforcementInterceptor.preHandle. `resolved` /
      * `rejection` are what TenantInterceptor attached; `apiKey` is the
      * x-api-key header; `session` is the SessionAuthenticator user. */
    private def gate(
        rt: Route,
        session: Option[User] = None,
        resolved: Option[ResolvedKey] = None,
        rejection: Option[String] = None,
        apiKey: Option[String] = None,
        stale: Boolean = false
    ): Outcome = {
        val (r, attrs) = ScopeDenialFixtures.request(resolved.orNull, rt.method, rt.path)
        rejection.foreach(v => attrs.put(TenantInterceptor.ApiKeyRejectedAttr, v))
        if (stale) attrs.put(SessionAuthenticator.StaleSessionAttribute, java.lang.Boolean.TRUE)
        when(r.getHeader("x-api-key")).thenReturn(apiKey.orNull)
        val out = new StringWriter()
        var status: Option[Int] = None
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(new PrintWriter(out, true))
        doAnswer { i => status = Some(i.getArgument[Int](0)); null }.when(resp).setStatus(anyInt())
        UserContext.clear()
        session.foreach(UserContext.set)
        try {
            val proceed = new RoleEnforcementInterceptor().preHandle(r, resp, handlerFor(rt))
            Outcome(proceed, status, out.toString)
        } finally UserContext.clear()
    }

    private val Refused401 = Outcome(proceed = false, Some(401), ApiErrors.AuthenticationRequiredBody)
    private val InsufficientRole = Outcome(proceed = false, Some(403), """{"error":"Insufficient role"}""")
    private val ReadOnlyRole = Outcome(proceed = false, Some(403), """{"error":"Read-only role"}""")
    private val Proceed = Outcome(proceed = true, None, "")

    private val admin = User("root", "h", User.RoleAdmin, null, null, null)
    private val editor = User("ed", "h", User.RoleEditor, null, null, null)
    private val viewer = User("vic", "h", User.RoleViewer, null, null, null)
    private val restrictedViewer = User("rita", "h", User.RoleViewer, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    private val restrictedEditor = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))

    private val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val anonymous = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val monitor = ResolvedKey(None, "monitor", Capability.parseList(Seq("job:read")), isLegacyFullAccess = false)
    private val tapKey = ResolvedKey(None, "tap:t1", TapRunTokens.Capabilities, isLegacyFullAccess = false)

    // ------------------------------------------------------------ the scan

    test("the scan finds every role-gated route, including the Configuration chat, keys, users, secrets, audit log, policy, approvals and incidents") {
        val found = roleGated.map(rt => rt.method + " " + rt.pattern).toSet
        Seq(
            "POST /api/v1/config-chat/chat",
            "POST /api/v1/keys",
            "GET /api/v1/keys",
            "DELETE /api/v1/keys/{label}",
            "POST /api/v1/auth/users",
            "GET /api/v1/auth/users",
            "DELETE /api/v1/auth/users/{username}",
            "GET /api/v1/secrets",
            "PUT /api/v1/secrets/{name}",
            "GET /api/v1/audit-log",
            "PUT /api/v1/policy",
            "POST /api/v1/approvals/{id}/approve",
            "POST /api/v1/incidents/{id}/proceed"
        ).foreach(r => assert(found.contains(r), r + " not found among " + found.toSeq.sorted.mkString(", ")))
        // The chat POSTs and the attachment carry no role annotation.
        (ChatPosts + "/api/v1/assistant/attachment").foreach { p =>
            val rts = mappings.filter(rt => rt.method == "POST" && rt.pattern == p)
            assert(rts.nonEmpty, p)
            rts.foreach(rt => assert(rt.ann.isEmpty, rt.toString))
        }
    }

    // Story: Change a tap's owner (plans/stories/tap-change-owner.md), O1. The
    // route is gated to administrators and mapped to tap:update; the handler
    // itself refuses every caller that is not full access
    // (TapChangeOwnerRouteSpec), because the role gate admits a key holding
    // the mapped capability.
    test("POST /api/v1/tap/owner is role-gated to administrators and checked as tap:update") {
        val rts = mappings.filter(rt => rt.method == "POST" && rt.pattern == "/api/v1/tap/owner")
        assert(rts.size == 1, "one handler for POST /api/v1/tap/owner: " + rts)
        val rt = rts.head
        assert(rt.cls.getName == "ai.datris.api.TapAPIController", rt.toString)
        assert(rt.roles == Set(User.RoleAdmin), rt.toString)
        assert(
            ai.datris.auth.CapabilityRoutes.lookup("POST", "/api/v1/tap/owner") == ai.datris.auth.RouteCheck.Require("tap", "update"),
            "mapped with the tap rows as tap:update"
        )
        assert(gate(rt, session = Some(admin)) == Proceed)
        assert(gate(rt, resolved = Some(legacy), apiKey = Some("legacy-value")) == Proceed)
        for (u <- Seq(editor, viewer, restrictedEditor, restrictedViewer)) assert(gate(rt, session = Some(u)) == InsufficientRole, u.username)
        assert(gate(rt) == Refused401)
        // API keys off, user sign-in on: only a signed-in administrator passes.
        DatrisEnvironment.values = keysOn.copy(useApiKeys = false)
        assert(gate(rt, resolved = Some(anonymous)) == InsufficientRole)
        assert(gate(rt, session = Some(admin), resolved = Some(anonymous)) == Proceed)
    }

    // ------------------------------------------------------------ Decision 6

    test("no credential, keys on: every role-gated route answers 401 Authentication required") {
        for (rt <- roleGated) assert(gate(rt) == Refused401, rt.toString)
    }

    test("a rejected key on a role-gated route is 401 (unknown, revoked, missing) and a key-store outage is 503") {
        for (rt <- roleGated) {
            Seq(APIKeyValidator.InvalidKeyMessage, "API key 'old' is revoked", APIKeyValidator.MissingKeyMessage).foreach { reason =>
                val o = gate(rt, rejection = Some(reason), apiKey = Some("bogus"))
                assert(o == Outcome(proceed = false, Some(401), CapabilityInterceptor.RejectedKeyBody), rt.toString + " " + reason + " " + o)
            }
            Seq(APIKeyValidator.MetadataUnavailableMessage, APIKeyValidator.KeyStoreUnavailableMessage).foreach { reason =>
                val (status, body) = CapabilityInterceptor.rejectionResponse(reason)
                assert(status == 503)
                assert(gate(rt, rejection = Some(reason), apiKey = Some("bogus")) == Outcome(proceed = false, Some(status), body), rt.toString + " " + reason)
            }
        }
    }

    test("a caller with a credential but without the role still gets 403 Insufficient role") {
        for (rt <- roleGated) {
            // a resolved key that satisfies no role gate
            assert(!RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(monitor, rt.method, rt.path), rt.toString)
            assert(gate(rt, resolved = Some(monitor), apiKey = Some("monitor-value")) == InsufficientRole, rt.toString)
            assert(gate(rt, resolved = Some(tapKey), apiKey = Some("trt_x")) == InsufficientRole, rt.toString)
            // sessions whose role the route does not name
            Seq(admin, editor, viewer, restrictedEditor, restrictedViewer).filterNot(u => rt.roles.contains(u.role)).foreach { u =>
                assert(gate(rt, session = Some(u)) == InsufficientRole, rt.toString + " as " + u.username)
            }
        }
    }

    test("unchanged: an admin session and a full-access key pass every role-gated route") {
        for (rt <- roleGated) {
            assert(gate(rt, session = Some(admin)) == Proceed, rt.toString)
            assert(gate(rt, resolved = Some(legacy), apiKey = Some("legacy-value")) == Proceed, rt.toString)
            if (rt.roles.contains(User.RoleEditor)) assert(gate(rt, session = Some(editor)) == Proceed, rt.toString)
        }
    }

    test("unchanged: with user auth off every role-gated route passes the role gate, with or without a credential") {
        DatrisEnvironment.values = keysOn.copy(useUserAuth = false)
        for (rt <- roleGated) {
            assert(gate(rt) == Proceed, rt.toString)
            assert(gate(rt, rejection = Some(APIKeyValidator.InvalidKeyMessage), apiKey = Some("bogus")) == Proceed, rt.toString)
        }
    }

    // Story reading (OPEN QUESTION): with keys off TenantInterceptor always
    // attaches the anonymous identity, so `unauthenticated(Some(anonymous), None)`
    // is None and the gate keeps its 403, although no credential was sent.
    // Decision 6 says "a caller with no credentials gets 401"; the story's
    // `unauthenticated` only answers 401 when no key resolved. Pinned to the
    // story's reading, which keeps today's answer.
    test("keys off, user auth on, no session: the anonymous identity is attached, so a role-gated route stays 403 Insufficient role") {
        DatrisEnvironment.values = keysOn.copy(useApiKeys = false)
        for (rt <- roleGated) assert(gate(rt, resolved = Some(anonymous)) == InsufficientRole, rt.toString)
    }

    test("unchanged: a stale session cookie answers 401 Session expired on every role-gated route, with or without a key") {
        for (rt <- roleGated) {
            val expected = Outcome(proceed = false, Some(401), """{"error":"Session expired"}""")
            assert(gate(rt, stale = true) == expected, rt.toString)
            assert(gate(rt, stale = true, resolved = Some(legacy), apiKey = Some("legacy-value")) == expected, rt.toString)
        }
    }

    test("unchanged: a route without a role gate keeps its answer for a caller with no credential (401 with keys on, through with keys off)") {
        val chat = mappings.find(rt => rt.method == "POST" && rt.pattern == "/api/v1/assistant/chat").get
        assert(gate(chat) == Refused401)
        DatrisEnvironment.values = keysOn.copy(useApiKeys = false)
        assert(gate(chat, resolved = Some(anonymous)) == Proceed)
    }

    // ------------------------------------------------------------ Decision 3

    test("a viewer session may POST exactly the four chat routes among every non-GET handler mapping (plus the credential-free auth endpoints)") {
        val nonGet = mappings.filter(_.method != "GET")
        assert(nonGet.size > 60, nonGet.size)
        for (u <- Seq(viewer, restrictedViewer); rt <- nonGet) {
            val o = gate(rt, session = Some(u))
            val publicAuth = rt.ann.isEmpty && rt.cls.getName == "ai.datris.api.AuthAPIController"
            if (rt.ann.isDefined) assert(o == InsufficientRole, rt.toString + " as " + u.username)
            else if (ChatPosts.contains(rt.pattern) || publicAuth) assert(o == Proceed, rt.toString + " as " + u.username + ": " + o)
            else assert(o == ReadOnlyRole, rt.toString + " as " + u.username + ": " + o)
        }
    }

    test("a viewer's chat POSTs pass however the path is spelled for the dispatcher; the attachment and Configuration chat stay refused") {
        for (u <- Seq(viewer, restrictedViewer); p <- ChatPosts) {
            val rt = mappings.find(r => r.method == "POST" && r.pattern == p).get
            assert(gate(rt, session = Some(u)) == Proceed, p)
            // a path parameter and a percent-encoded letter route to the same handler
            val variants = Seq(p + ";x=1", p.replace("/chat", "/ch%61t"))
            variants.foreach { v =>
                val (r, _) = ScopeDenialFixtures.request(null, "POST", v)
                assert(ai.datris.auth.RequestPaths.path(r) == p, v)
                assert(gateAt(rt, v, u) == Proceed, v)
            }
        }
        val attachment = mappings.find(r => r.method == "POST" && r.pattern == "/api/v1/assistant/attachment").get
        val config = mappings.find(r => r.method == "POST" && r.pattern == "/api/v1/config-chat/chat").get
        for (u <- Seq(viewer, restrictedViewer)) {
            assert(gate(attachment, session = Some(u)) == ReadOnlyRole, u.username)
            assert(gate(config, session = Some(u)) == InsufficientRole, u.username)
        }
    }

    test("unchanged: editors keep the chat POSTs and the attachment; the Configuration chat stays 403 for editors") {
        for (u <- Seq(editor, restrictedEditor)) {
            for (p <- ChatPosts + "/api/v1/assistant/attachment") {
                val rt = mappings.find(r => r.method == "POST" && r.pattern == p).get
                assert(gate(rt, session = Some(u)) == Proceed, p + " as " + u.username)
            }
            val config = mappings.find(r => r.method == "POST" && r.pattern == "/api/v1/config-chat/chat").get
            assert(gate(config, session = Some(u)) == InsufficientRole, u.username)
        }
    }

    /** [[gate]] for a session at a raw request URI `uri` (a spelling of `rt`). */
    private def gateAt(rt: Route, uri: String, u: User): Outcome = {
        val (r, _) = ScopeDenialFixtures.request(null, rt.method, uri)
        when(r.getHeader("x-api-key")).thenReturn(null)
        val out = new StringWriter()
        var status: Option[Int] = None
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(new PrintWriter(out, true))
        doAnswer { i => status = Some(i.getArgument[Int](0)); null }.when(resp).setStatus(anyInt())
        UserContext.set(u)
        try Outcome(new RoleEnforcementInterceptor().preHandle(r, resp, handlerFor(rt)), status, out.toString)
        finally UserContext.clear()
    }
}
