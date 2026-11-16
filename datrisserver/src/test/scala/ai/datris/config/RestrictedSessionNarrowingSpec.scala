package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey, User, UserContext}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Catalog-scoped user access, review round 1: `CapabilityInterceptor`
  * attaches a restricted user's session bundle before the route lookup, both
  * in keys-off mode (replacing the anonymous identity) and for cookie-only
  * requests with keys on (no identity attached yet), so controller checks
  * apply on unmapped routes too. Unrestricted users and presented keys are
  * untouched. */
class RestrictedSessionNarrowingSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val bob = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    private val ed = User("ed", "h", User.RoleEditor, null, null, null)
    private val anonymous = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    override def afterEach(): Unit = UserContext.clear()

    /** A request backed by a real attribute map. */
    private def request(attrs: (String, AnyRef)*): (HttpServletRequest, mutable.Map[String, AnyRef]) = {
        val m = mutable.Map[String, AnyRef](attrs: _*)
        val r = mock(classOf[HttpServletRequest])
        when(r.getAttribute(anyString())).thenAnswer(i => m.getOrElse(i.getArgument[String](0), null))
        doAnswer { i => m.put(i.getArgument[String](0), i.getArgument[AnyRef](1)); null }.when(r).setAttribute(anyString(), any())
        (r, m)
    }

    private def resolved(m: mutable.Map[String, AnyRef]): Option[ResolvedKey] =
        m.get(TenantInterceptor.ResolvedKeyAttr).collect { case rk: ResolvedKey => rk }

    test("keys-off: the anonymous identity is replaced by a restricted user's session bundle") {
        UserContext.set(bob)
        val (r, m) = request(TenantInterceptor.ResolvedKeyAttr -> anonymous)
        new CapabilityInterceptor().narrowForRestrictedSession(r)
        val rk = resolved(m).get
        assert(rk.label == "session:bob" && rk.restrictedSession && !rk.isLegacyFullAccess)
        assert(rk.capabilities == APIKeyValidator.resolveFromSession(bob).capabilities)
    }

    test("keys on, cookie only: a restricted session is attached before the route lookup") {
        UserContext.set(bob)
        val (r, m) = request()
        new CapabilityInterceptor().narrowForRestrictedSession(r)
        assert(resolved(m).exists(rk => rk.restrictedSession && rk.label == "session:bob"))
    }

    test("unrestricted users, presented keys and rejected keys are left alone") {
        UserContext.set(ed)
        val (r1, m1) = request(TenantInterceptor.ResolvedKeyAttr -> anonymous)
        new CapabilityInterceptor().narrowForRestrictedSession(r1)
        assert(resolved(m1).contains(anonymous))
        val (r2, m2) = request()
        new CapabilityInterceptor().narrowForRestrictedSession(r2)
        assert(resolved(m2).isEmpty, "unrestricted keeps the lazy session fallback")

        UserContext.set(bob)
        val key = ResolvedKey(None, "builder", Capability.parseList(Seq("pipeline:read")), isLegacyFullAccess = false)
        val (r3, m3) = request(TenantInterceptor.ResolvedKeyAttr -> key)
        new CapabilityInterceptor().narrowForRestrictedSession(r3)
        assert(resolved(m3).contains(key), "a presented key's identity is never replaced")
        val (r4, m4) = request(TenantInterceptor.ApiKeyRejectedAttr -> "revoked")
        new CapabilityInterceptor().narrowForRestrictedSession(r4)
        assert(resolved(m4).isEmpty, "a rejected key must still fail closed")
    }
}
