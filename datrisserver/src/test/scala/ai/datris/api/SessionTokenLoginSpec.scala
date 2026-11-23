package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.SessionAuthenticator
import ai.datris.model.{DatrisEnvironment, TenantContext, User, UserContext}
import ai.datris.util.{FakeSessionDb, InMemoryUserDb, PasswordHasher, SessionStore, UserStore}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{atLeast, mock, mockingDetails, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.JavaConverters._

/** Story: sign-in session tokens are stored as hashes
  * (plans/stories/session-tokens-hashed.md), through the controller and the
  * interceptor that use SessionStore.
  *
  * Password login sets the `datris-session` cookie to the token `create`
  * returns, and the session collection holds only its hash. That cookie
  * signs the browser in through SessionAuthenticator.preHandle; the stored
  * hash used as a cookie is a stale session (the 401 path). Logout removes
  * the session. No log line at INFO or above carries the token or its hash.
  *
  * No Spring, no Mongo: the controller is called directly with mocked
  * servlet objects; UserStore runs against [[InMemoryUserDb]] and
  * SessionStore against [[FakeSessionDb]] (both through `dbOverride`).
  * See SessionStoreHashSpec for the pinned SessionStore seam.
  */
class SessionTokenLoginSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Password = "login-spec-password"
    private var savedValues: DatrisEnvironment = _
    private var users: InMemoryUserDb = _
    private var sessions: FakeSessionDb = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        val e = InMemoryUserDb.env().copy(userSessionTableName = "test-user-session")
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        users = new InMemoryUserDb
        sessions = new FakeSessionDb
        UserStore.dbOverride = users
        SessionStore.dbOverride = sessions
        val now = java.time.Instant.now().toString
        users.put(User("alice", PasswordHasher.hash(Password), User.RoleEditor, now, now, null))
    }

    override def afterEach(): Unit = {
        SessionStore.dbOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    private def sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

    /** Password login; returns the status and the session cookie value set. */
    private def login(username: String = "alice", password: String = Password): (Int, Option[String]) = {
        val response = mock(classOf[HttpServletResponse])
        val body = s"""{"username":"$username","password":"$password"}"""
        val status = new AuthAPIController().login(body, response).getStatusCode.value
        val captor = ArgumentCaptor.forClass(classOf[Cookie])
        verify(response, atLeast(0)).addCookie(captor.capture())
        val cookie = captor.getAllValues.asScala.find(_.getName == SessionAuthenticator.SessionCookieName).map(_.getValue)
        (status, cookie)
    }

    private def requestWithCookie(value: String): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(Array(new Cookie(SessionAuthenticator.SessionCookieName, value)))
        when(r.getHeader(anyString())).thenReturn(null)
        when(r.getMethod).thenReturn("GET")
        when(r.getRequestURI).thenReturn("/api/v1/auth/me")
        r
    }

    /** preHandle with the given cookie: the user it attached, and whether the request was marked stale. */
    private def preHandle(cookie: String): (Option[User], Boolean) = {
        val r = requestWithCookie(cookie)
        UserContext.clear()
        assert(new SessionAuthenticator().preHandle(r, mock(classOf[HttpServletResponse]), null))
        val stale = mockingDetails(r).getInvocations.asScala.exists(i =>
            i.getMethod.getName == "setAttribute" && i.getArguments.headOption.contains(SessionAuthenticator.StaleSessionAttribute)
        )
        val u = UserContext.get()
        UserContext.clear()
        (u, stale)
    }

    private def storedHashes: Seq[String] =
        sessions.docs.toSeq.flatMap(d => Option(d.get("tokenHash")).filter(_.isJsonPrimitive).map(_.getAsString))

    // ---- Acceptance 1 + 3: password login stores only the hash, the cookie still signs in ----

    test("password login sets the cookie to the session token and the stored session holds only its hash") {
        val (status, cookie) = login()
        assert(status == 200)
        val raw = cookie.getOrElse(fail("login set no session cookie"))
        assert(raw.nonEmpty)
        assert(sessions.docs.size == 1, s"${sessions.docs}")
        val stored = sessions.docs.head
        assert(!stored.has("token"), s"$stored")
        assert(storedHashes == Seq(sha256Hex(raw)))
        assert(!stored.toString.contains(raw), "the cookie value appears nowhere in the stored session")
        assert(SessionStore.hash(raw) == storedHashes.head)
    }

    test("the login cookie signs the browser in through SessionAuthenticator, and validatedSessionUser sees the same user") {
        val (_, cookie) = login()
        val (user, stale) = preHandle(cookie.get)
        assert(user.map(_.username).contains("alice"))
        assert(!stale)
        assert(SessionAuthenticator.validatedSessionUser(requestWithCookie(cookie.get)).map(_.username).contains("alice"))
        assert(sessions.docs.forall(!_.has("token")), "the renew on each request keeps the hashed shape")
    }

    test("the stored hash used as the cookie value is a stale session: no user, marked for the 401") {
        login()
        val stored = storedHashes.head
        val (user, stale) = preHandle(stored)
        assert(user.isEmpty)
        assert(stale, "a cookie that matches no live session is marked stale")
        assert(SessionAuthenticator.validatedSessionUser(requestWithCookie(stored)).isEmpty)
    }

    test("unchanged: a bad password sets no session cookie and stores no session") {
        val (status, cookie) = login(password = "wrong-password")
        assert(status == 401)
        assert(cookie.isEmpty)
        assert(sessions.docs.isEmpty)
    }

    test("logout with the login cookie removes the session and clears the cookie; the old cookie is then stale") {
        val (_, cookie) = login()
        val raw = cookie.get
        val response = mock(classOf[HttpServletResponse])
        val resp = new AuthAPIController().logout(requestWithCookie(raw), response)
        assert(resp.getStatusCode.value == 200)
        assert(sessions.docs.isEmpty, s"left behind: ${sessions.docs}")
        val captor = ArgumentCaptor.forClass(classOf[Cookie])
        verify(response).addCookie(captor.capture())
        assert(captor.getValue.getName == SessionAuthenticator.SessionCookieName && captor.getValue.getValue == "" && captor.getValue.getMaxAge == 0)

        val (user, stale) = preHandle(raw)
        assert(user.isEmpty && stale)
    }

    test("a session from before the change keeps the browser signed in through preHandle and is rewritten hashed") {
        val raw = "legacy-browser-token-0123456789abcdefghijk"
        val o = new com.google.gson.JsonObject
        o.addProperty("token", raw)
        o.addProperty("username", "alice")
        o.addProperty("expiresAt", java.time.Instant.now().plusSeconds(3600).toString)
        o.addProperty("createdAt", java.time.Instant.now().toString)
        sessions.putJson(o.toString)

        val (user, stale) = preHandle(raw)
        assert(user.map(_.username).contains("alice"))
        assert(!stale)
        assert(sessions.docs.size == 1 && !sessions.docs.head.has("token"), s"${sessions.docs}")
        assert(storedHashes == Seq(sha256Hex(raw)))
    }

    // ---- Acceptance 4: no INFO+ log line carries a session token -------------------------

    test("no log line at INFO or above carries the session token or its hash across login, a request, renew and logout") {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val savedLevel = root.getLevel
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        root.addAppender(appender)
        root.setLevel(ch.qos.logback.classic.Level.INFO)
        val (raw, events) =
            try {
                login(username = "nobody") // logs "Login failed": proves the capture works
                val (_, cookie) = login()
                val raw = cookie.getOrElse(fail("login set no session cookie"))
                preHandle(raw)
                preHandle(SessionStore.hash(raw))
                new AuthAPIController().logout(requestWithCookie(raw), mock(classOf[HttpServletResponse]))
                (raw, appender.list.asScala.toList)
            } finally {
                root.detachAppender(appender)
                root.setLevel(savedLevel)
            }

        val infoPlus = events.filter(_.getLevel.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
        assert(infoPlus.exists(_.getFormattedMessage.contains("Login failed")), s"capture did not see the login log: ${events.map(_.getFormattedMessage)}")
        val hash = sha256Hex(raw)
        def text(e: ILoggingEvent): String =
            e.getFormattedMessage + Option(e.getThrowableProxy).map(t => " " + t.getMessage).getOrElse("")
        val leaking = infoPlus.filter(e => text(e).contains(raw) || text(e).contains(hash))
        assert(leaking.isEmpty, s"log lines carrying a session token: ${leaking.map(text)}")
    }
}
