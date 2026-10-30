package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext, User, UserContext}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.Mockito.{mock, when}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite

/** The chat endpoints return an SseEmitter, so Spring never runs
  * `afterCompletion` on the servlet thread that ran `preHandle`. Without
  * these guards the thread kept its UserContext / TenantContext and the next
  * unrelated request on it (an x-api-key call, or an anonymous one) ran as
  * the browser user. Both interceptors must clear on
  * `afterConcurrentHandlingStarted` AND at the top of `preHandle`. */
class AsyncThreadLocalLeakSpec extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

    // preHandle reads DatrisEnvironment.values (useUserAuth / multiTenant);
    // the defaults there (both false) are the OSS single-node setup.
    private var savedEnv: DatrisEnvironment = _
    override def beforeAll(): Unit = {
        savedEnv = DatrisEnvironment.values
        DatrisEnvironment.values = ai.datris.util.CatalogEnableJobSpec.env
    }
    override def afterAll(): Unit = DatrisEnvironment.values = savedEnv

    private val alice = User("alice", "hash", User.RoleAdmin, "", "", "")

    private def request(): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(null)
        when(r.getHeader("x-api-key")).thenReturn(null)
        r
    }

    private val response = mock(classOf[HttpServletResponse])

    override def afterEach(): Unit = {
        UserContext.clear()
        TenantContext.clear()
    }

    test("SessionAuthenticator clears UserContext when async handling starts") {
        UserContext.set(alice)
        new SessionAuthenticator().afterConcurrentHandlingStarted(request(), response, null)
        assert(UserContext.get().isEmpty)
    }

    test("SessionAuthenticator.preHandle drops a leftover user from a previous request") {
        UserContext.set(alice)
        val proceed = new SessionAuthenticator().preHandle(request(), response, null)
        assert(proceed)
        assert(UserContext.get().isEmpty, "a cookie-less request must never inherit the thread's last user")
    }

    test("TenantInterceptor clears TenantContext when async handling starts") {
        TenantContext.set(DatrisEnvironment.values)
        new TenantInterceptor().afterConcurrentHandlingStarted(request(), response, null)
        assert(TenantContext.get().isEmpty)
    }

    test("TenantInterceptor.preHandle drops a leftover tenant from a previous request") {
        TenantContext.set(DatrisEnvironment.values)
        val proceed = new TenantInterceptor().preHandle(request(), response, null)
        assert(proceed)
        assert(TenantContext.get().isEmpty)
    }
}
