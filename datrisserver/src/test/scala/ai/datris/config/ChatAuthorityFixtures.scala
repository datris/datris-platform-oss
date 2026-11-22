package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.common.cache.Cache
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{doAnswer, mock, when}

import java.io.{PrintWriter, StringWriter}
import scala.collection.mutable

/** Fixtures for plans/stories/chat-session-role-authority.md specs.
  *
  * Nothing here reaches a secret store: keys-on requests resolve through
  * `APIKeyValidator`'s own resolution cache, seeded with [[seedKey]] right
  * before use (a tap-run token resolves in memory). A presented value that is
  * not seeded must never be sent through `TenantInterceptor` with keys on. */
object ChatAuthorityFixtures {

    // ------------------------------------------------------------ users

    val root: User = User("root", "h", User.RoleAdmin, null, null, null)
    val ed: User = User("ed", "h", User.RoleEditor, null, null, null)
    val vic: User = User("vic", "h", User.RoleViewer, null, null, null)
    val bob: User = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    val rita: User = User("rita", "h", User.RoleViewer, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))

    /** An admin flagged restricted: `isRestricted` is false for admins. */
    val rootFlagged: User = User("rootf", "h", User.RoleAdmin, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))

    /** A role outside `User.ValidRoles` (a hand-edited or future row). */
    val odd: User = User("odd", "h", "auditor", null, null, null)

    val allUsers: Seq[User] = Seq(root, ed, vic, bob, rita, rootFlagged, odd)

    // ------------------------------------------------------------ carriers

    /** The `ui` key with no metadata (legacy full access). */
    val uiLegacy: ResolvedKey = ResolvedKey(None, "ui", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** The `ui` key with `*:*` metadata. */
    val uiStored: ResolvedKey = ResolvedKey(None, "ui", Capability.parseList(Seq("*:*")), isLegacyFullAccess = false, keyId = Some("k-ui"))

    /** The keys-off identity. */
    val anonymous: ResolvedKey = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    val legacy: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    // Presented values seeded into the resolution cache.
    val UiValue = "test-ui-value"
    val LegacyValue = "test-legacy-value"

    // ------------------------------------------------------------ key resolution without a secret store

    private def cache: Cache[String, ResolvedKey] = {
        val f = APIKeyValidator.getClass.getDeclaredField("resolvedKeyCache")
        f.setAccessible(true)
        f.get(APIKeyValidator).asInstanceOf[Cache[String, ResolvedKey]]
    }

    /** Make `value` resolve to `rk` with keys on, without a secret-store read. */
    def seedKey(value: String, rk: ResolvedKey): Unit = {
        cache.put(value, rk)
        val got = APIKeyValidator.resolveKey(value)
        require(got eq rk, "the resolution cache seam did not take " + rk.label)
    }

    // ------------------------------------------------------------ requests

    /** A mocked request backed by a real attribute map, with the given
      * headers (a header listed twice: `getHeader` answers the first,
      * `getHeaders` both). */
    def request(method: String, uri: String, headers: (String, String)*): (HttpServletRequest, mutable.Map[String, AnyRef]) = {
        val attrs = mutable.Map[String, AnyRef]()
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(null)
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(uri)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getHeader(anyString())).thenReturn(null)
        headers.groupBy(_._1).foreach { case (k, vs) =>
            when(r.getHeader(k)).thenReturn(vs.head._2)
            when(r.getHeaders(k)).thenAnswer(_ => java.util.Collections.enumeration(java.util.Arrays.asList(vs.map(_._2): _*)))
        }
        when(r.getAttribute(anyString())).thenAnswer(i => attrs.getOrElse(i.getArgument[String](0), null))
        doAnswer { i => attrs.put(i.getArgument[String](0), i.getArgument[AnyRef](1)); null }.when(r).setAttribute(anyString(), any())
        doAnswer { i => attrs.remove(i.getArgument[String](0)); null }.when(r).removeAttribute(anyString())
        (r, attrs)
    }

    /** A response that records its status and body. */
    class Captured {
        val out = new StringWriter()
        @volatile var status: Option[Int] = None
        val response: HttpServletResponse = mock(classOf[HttpServletResponse])
        when(response.getWriter).thenReturn(new PrintWriter(out, true))
        doAnswer { i => status = Some(i.getArgument[Int](0)); null }.when(response).setStatus(anyInt())
        def body: String = out.toString
    }

    /** The ResolvedKey TenantInterceptor attached, if any. */
    def resolved(attrs: mutable.Map[String, AnyRef]): Option[ResolvedKey] =
        attrs.get(TenantInterceptor.ResolvedKeyAttr).collect { case rk: ResolvedKey => rk }
}
