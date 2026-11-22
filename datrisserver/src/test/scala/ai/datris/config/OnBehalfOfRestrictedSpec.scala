package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{atLeastOnce, mock, never, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Catalog-scoped user access (server)
  * (plans/stories/catalog-scoped-user-access.md), Acceptance bullet 3 and
  * Step 4: `TenantInterceptor.applyOnBehalfOf` narrows the carrier key to the
  * vouched user's session bundle when that user is restricted, and leaves the
  * carrier's bundle alone otherwise.
  *
  * Driven through the real `TenantInterceptor.preHandle`. The `ui` key lives
  * in Vault, which a unit spec cannot reach, so the carrier here is the
  * keys-off identity (`useApiKeys=false`), which `AuditActor.trustsOnBehalfOf`
  * trusts exactly as it trusts `ui`, and which holds full access: the
  * widest possible carrier, so any narrowing is visible.
  */
class OnBehalfOfRestrictedSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var db: InMemoryUserDb = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env()
        db = new InMemoryUserDb
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        db.put(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
        db.put(User("alice", "h", User.RoleEditor, null, null, null))
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    /** Run preHandle with the on-behalf-of header and return the ResolvedKey it attached last. */
    private def resolvedFor(onBehalfOf: String): (ResolvedKey, HttpServletRequest) = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(null)
        when(r.getHeader("x-api-key")).thenReturn(null)
        when(r.getHeader(AuditActor.HeaderOnBehalfOf)).thenReturn(onBehalfOf)
        assert(new TenantInterceptor().preHandle(r, mock(classOf[HttpServletResponse]), null))
        val captor = ArgumentCaptor.forClass(classOf[AnyRef])
        verify(r, atLeastOnce()).setAttribute(eqTo(TenantInterceptor.ResolvedKeyAttr), captor.capture())
        (captor.getAllValues.asScala.last.asInstanceOf[ResolvedKey], r)
    }

    test("ui key on behalf of a restricted user narrows to that user's bundle") {
        val (rk, r) = resolvedFor("bob")
        val expected = APIKeyValidator.resolveFromSession(UserStore.find("bob").get)
        assert(rk.label == "session:bob")
        assert(rk.restrictedSession, "the narrowed identity is a restricted session")
        assert(!rk.isLegacyFullAccess, "the carrier's full access must not survive")
        assert(rk.capabilities.map(_.raw).toSet == expected.capabilities.map(_.raw).toSet)
        assert(!rk.capabilities.contains(Capability.FullAccess))
        assert(rk.grants("pipeline", "read", Map("catalog" -> "Sales")))
        assert(!rk.grants("pipeline", "read", Map("catalog" -> "HR")))
        assert(!rk.grants("pipeline", "read", Map.empty))
        // Attribution attributes still name the user and the carrying key.
        verify(r).setAttribute(eqTo(AuditActor.OnBehalfOfAttr), any())
        verify(r).setAttribute(eqTo(AuditActor.CarrierKeyLabelAttr), eqTo("anonymous"))
    }

    // Story: In-platform chats act with the signed-in user's role, not the
    // platform key's (plans/stories/chat-session-role-authority.md), Step 2 /
    // "Pinned tests that change": was "on behalf of an unrestricted user keeps
    // the key's bundle".
    test("on behalf of an unrestricted editor narrows to the editor role bundle") {
        val (rk, r) = resolvedFor("alice")
        assert(rk.label == "session:alice")
        assert(!rk.isLegacyFullAccess)
        assert(rk.capabilities == APIKeyValidator.roleToCapabilities(User.RoleEditor))
        assert(!rk.restrictedSession)
        verify(r).setAttribute(eqTo(AuditActor.CarrierKeyLabelAttr), eqTo("anonymous"))
    }

    // plans/stories/chat-session-role-authority.md, owner Decision 7 (approved
    // edit): was "an unknown on-behalf-of user keeps the key's identity and
    // bundle". An unknown user now refuses the request (401) before any handler
    // runs, and the carrier's identity is never attached.
    test("an unknown on-behalf-of user is refused and never acts with the key's identity") {
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(null)
        when(r.getHeader("x-api-key")).thenReturn(null)
        when(r.getHeader(AuditActor.HeaderOnBehalfOf)).thenReturn("nobody")
        val resp = mock(classOf[HttpServletResponse])
        val out = new java.io.StringWriter()
        when(resp.getWriter).thenReturn(new java.io.PrintWriter(out, true))
        assert(!new TenantInterceptor().preHandle(r, resp, null), "the request must not proceed")
        verify(resp).setStatus(401)
        assert(out.toString == TenantInterceptor.UnverifiedUserBody)
        assert(!out.toString.contains("nobody") && !out.toString.contains("anonymous"))
        verify(r, never()).setAttribute(eqTo(TenantInterceptor.ResolvedKeyAttr), any())
        verify(r, never()).setAttribute(eqTo(AuditActor.OnBehalfOfAttr), any())
    }
}
