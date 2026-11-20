package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.auth.{Allow, ChatCaller, MCPToolRoutes, RoleToolSets}
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext, UserSession}
import ai.datris.policy.PolicyReplay
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, SessionStore, UserStore}
import com.google.gson.Gson
import jakarta.servlet.http.{Cookie, HttpServletRequest}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.mutable

/** Story: In-platform chats act with the signed-in user's role, not the
  * platform key's (plans/stories/chat-session-role-authority.md), owner
  * Decisions 7 and 8, through the real `TenantInterceptor.preHandle`.
  *
  * Decision 7 (fail closed): when a trusted carrier (the `ui` key; any
  * request with API keys off) names an on-behalf-of user who cannot be
  * resolved (unknown, deleted, or the user store read fails), the request is
  * refused before any handler runs: 401 for an unknown or deleted user, 503
  * when the user store could not be read. No identity is attached, so the
  * carrier's own capabilities are never the acting identity.
  *
  * Decision 8: a request that carries its own validated sign-in session acts
  * as that session's user; the header is ignored on it. */
class OnBehalfOfFailClosedSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var db: InMemoryUserDb = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env() // keys off, user auth on
        db = new InMemoryUserDb
        UserStore.dbOverride = db
        allUsers.foreach(db.put)
        APIKeyValidator.invalidateCache()
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        SessionStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    private def keysOn(): Unit = {
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true)
        APIKeyValidator.invalidateCache()
    }

    /** A user store whose reads fail. */
    private class FailingUserDb extends InMemoryUserDb {
        override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] =
            throw new RuntimeException("user store down")
    }

    /** A session table holding `sessions` (token -> username, live), or failing reads. */
    private class SessionDb(sessions: Map[String, String], fail: Boolean = false) extends InMemoryUserDb {
        private val gson = new Gson
        override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] =
            if (fail) throw new RuntimeException("session store down")
            else
                sessions.get(key).map(u =>
                    gson.toJson(UserSession(key, u, Instant.now().plusSeconds(3600).toString, Instant.now().toString))
                )
    }

    private case class Outcome(proceed: Boolean, c: Captured, attrs: mutable.Map[String, AnyRef], r: HttpServletRequest) {
        def identity: Option[ResolvedKey] = resolved(attrs)
    }

    private def preHandle(headers: (String, String)*)(cookie: Option[String] = None): Outcome = {
        if (DatrisEnvironment.values.useApiKeys) seedKey(UiValue, uiLegacy)
        val (r, attrs) = request("GET", "/api/v1/pipelines", headers: _*)
        cookie.foreach(t => when(r.getCookies).thenReturn(Array(new Cookie(SessionAuthenticator.SessionCookieName, t))))
        val c = new Captured
        val proceed = new TenantInterceptor().preHandle(r, c.response, null)
        Outcome(proceed, c, attrs, r)
    }

    /** The headers a chat's tool call (or the approval replay) carries for `user`. */
    private def carrierHeaders(user: String, replay: Boolean = false): Seq[(String, String)] =
        (if (DatrisEnvironment.values.useApiKeys) Seq("x-api-key" -> UiValue) else Nil) ++
            Seq(AuditActor.HeaderOnBehalfOf -> user) ++
            (if (replay) Seq(PolicyReplay.HeaderApproval -> "pa_1.tok") else Nil)

    private def carrier: ResolvedKey = if (DatrisEnvironment.values.useApiKeys) uiLegacy else anonymous

    private def assertRefused(o: Outcome, status: Int, body: String, claimed: String): Unit = {
        assert(!o.proceed, "the request must not reach a handler")
        assert(o.c.status.contains(status), String.valueOf(o.c.status))
        assert(o.c.body == body)
        // names no user and nothing about the carrier
        Seq(claimed, "ui", "anonymous", "session:").filter(_.nonEmpty).foreach(s => assert(!o.c.body.contains("\"" + s), s))
        // no identity at all: the carrier's capabilities are never the acting identity
        assert(o.identity.isEmpty, String.valueOf(o.identity))
        assert(!o.attrs.contains(AuditActor.OnBehalfOfAttr))
        assert(!o.attrs.contains(TenantInterceptor.OnBehalfOfCarrierAttr))
        assert(TenantContext.get().isEmpty, "no tenant is left on the thread")
        // and the body is not the capability-denied answer a missing permission gets
        assert(!o.c.body.contains("capability_denied"))
    }

    private def bothModes(body: => Unit): Unit = {
        body
        keysOn()
        body
    }

    // ================================================================ Decision 7

    test("an unknown on-behalf-of user is refused 401, with the ui key and with keys off, and never acts as the carrier") {
        bothModes {
            for (claimed <- Seq("nobody", "ghost", "ADMINX")) {
                assertRefused(preHandle(carrierHeaders(claimed): _*)(), 401, TenantInterceptor.UnverifiedUserBody, claimed.toLowerCase)
                // the approval replay has no exemption from it
                assertRefused(preHandle(carrierHeaders(claimed, replay = true): _*)(), 401, TenantInterceptor.UnverifiedUserBody, claimed.toLowerCase)
            }
        }
    }

    test("a user deleted mid-stream: resolved on one tool call, refused 401 on the next, never the carrier's identity") {
        bothModes {
            for (u <- Seq(root, ed, vic, bob)) {
                db.put(u)
                val first = preHandle(carrierHeaders(u.username): _*)()
                assert(first.proceed)
                assert(first.identity.contains(TenantInterceptor.onBehalfOfIdentity(carrier, u, approvalReplay = false)), u.username)
                db.rows.remove(u.username)
                assertRefused(preHandle(carrierHeaders(u.username): _*)(), 401, TenantInterceptor.UnverifiedUserBody, u.username)
                assertRefused(preHandle(carrierHeaders(u.username, replay = true): _*)(), 401, TenantInterceptor.UnverifiedUserBody, u.username)
                db.put(u)
            }
        }
    }

    test("a user store that cannot be read refuses 503, never the carrier's identity") {
        bothModes {
            UserStore.dbOverride = new FailingUserDb
            for (u <- Seq("root", "ed", "nobody"))
                assertRefused(preHandle(carrierHeaders(u): _*)(), 503, TenantInterceptor.UserStoreUnavailableBody, u)
            UserStore.dbOverride = db
        }
    }

    test("a refused tool call answers before the capability gate; the next request with a known user is served as that user") {
        bothModes {
            val refused = preHandle(carrierHeaders("nobody"): _*)()
            assert(!refused.proceed)
            val next = preHandle(carrierHeaders("ed"): _*)()
            assert(next.proceed)
            assert(next.identity.map(MCPToolRoutes.allowedTools).contains(RoleToolSets.Editor))
        }
    }

    test("unchanged: no header, a blank header, an untrusted key and user auth off are not refused") {
        assert(preHandle()().identity.contains(anonymous))
        assert(preHandle(AuditActor.HeaderOnBehalfOf -> "  ")().identity.contains(anonymous))
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useUserAuth = false)
        assert(preHandle(AuditActor.HeaderOnBehalfOf -> "nobody")().identity.contains(anonymous))
        keysOn()
        val monitor = RoleToolSets.stored("monitor", "job:read")
        seedKey("monitor-value", monitor)
        val (r, attrs) = request("GET", "/api/v1/pipelines", "x-api-key" -> "monitor-value", AuditActor.HeaderOnBehalfOf -> "nobody")
        assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
        assert(resolved(attrs).contains(monitor))
    }

    // ================================================================ Decision 8

    test("a request with its own valid session ignores the header: no relabel, no narrowing to another user, no refusal") {
        SessionStore.dbOverride = new SessionDb(Map("tok-bob" -> "bob", "tok-ed" -> "ed"))
        for (claimed <- Seq("root", "ed", "vic", "nobody")) {
            val o = preHandle(AuditActor.HeaderOnBehalfOf -> claimed)(cookie = Some("tok-bob"))
            assert(o.proceed, claimed)
            assert(o.identity.contains(anonymous), s"bob naming $claimed: ${o.identity}")
            assert(!o.attrs.contains(AuditActor.OnBehalfOfAttr), claimed)
            // SessionAuthenticator then finds bob; the capability gate holds bob to his grants
            UserContext.set(bob)
            try new CapabilityInterceptor().narrowForRestrictedSession(o.r)
            finally UserContext.clear()
            assert(o.identity.exists(k => k.restrictedSession && k.label == "session:bob"), String.valueOf(o.identity))
        }
        // an unrestricted session naming an admin stays itself (the REST session identity, as without the header)
        val o = preHandle(AuditActor.HeaderOnBehalfOf -> "root", PolicyReplay.HeaderApproval -> "pa_1.tok")(cookie = Some("tok-ed"))
        assert(o.identity.contains(anonymous))
        assert(!o.attrs.contains(AuditActor.OnBehalfOfAttr))
    }

    test("an invalid or expired session cookie is not a session: the header applies as it does with no cookie") {
        SessionStore.dbOverride = new SessionDb(Map.empty)
        val o = preHandle(AuditActor.HeaderOnBehalfOf -> "ed")(cookie = Some("expired"))
        assert(o.proceed)
        assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(anonymous, ed, approvalReplay = false)))
        // and an unknown user is refused, as with no cookie
        assertRefused(preHandle(AuditActor.HeaderOnBehalfOf -> "nobody")(cookie = Some("expired")), 401, TenantInterceptor.UnverifiedUserBody, "nobody")
    }

    test("a session the interceptor could not check but SessionAuthenticator later finds: the gate drops the header's identity") {
        SessionStore.dbOverride = new SessionDb(Map.empty, fail = true)
        for ((sessionUser, claimed) <- Seq(bob -> "root", rita -> "ed", ed -> "root", vic -> "root")) {
            val o = preHandle(AuditActor.HeaderOnBehalfOf -> claimed)(cookie = Some("tok"))
            assert(o.proceed)
            assert(o.attrs.contains(AuditActor.OnBehalfOfAttr), "the header applied: the session could not be checked")
            UserContext.set(sessionUser)
            try new CapabilityInterceptor().narrowForRestrictedSession(o.r)
            finally UserContext.clear()
            assert(!o.attrs.contains(AuditActor.OnBehalfOfAttr) && !o.attrs.contains(AuditActor.CarrierKeyLabelAttr))
            val expected =
                if (sessionUser.isRestricted) APIKeyValidator.resolveFromSession(sessionUser)
                else anonymous
            assert(o.identity.contains(expected), s"${sessionUser.username} naming $claimed: ${o.identity}")
            // and the chat decision sees the session user, not the named one
            UserContext.set(sessionUser)
            try ChatCaller.from(o.r) match {
                    case Allow(id) => assert(id.user.contains(sessionUser))
                    case other => fail(other.toString)
                }
            finally UserContext.clear()
        }
    }

    test("the platform's internal calls (no cookie) keep the header: chat workers and the approval replay") {
        bothModes {
            assert(preHandle(carrierHeaders("ed"): _*)().identity.contains(TenantInterceptor.onBehalfOfIdentity(carrier, ed, approvalReplay = false)))
            assert(preHandle(carrierHeaders("ed", replay = true): _*)().identity.contains(carrier.copy(label = "session:ed")))
            assert(preHandle(carrierHeaders("root"): _*)().identity.contains(carrier.copy(label = "session:root")))
        }
    }
}
