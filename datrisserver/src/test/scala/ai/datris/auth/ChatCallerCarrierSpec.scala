package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: Chat endpoints act with the caller's own authority (review
  * follow-up). With API keys off any key may vouch for a user, so
  * TenantInterceptor relabels a tap-run token sending `X-Datris-On-Behalf-Of`
  * to `session:<user>`. Rule 1 must still refuse it: `ChatCaller.from` passes
  * the carrier label (`AuditActor.CarrierKeyLabelAttr`, or the presented live
  * tap token) to `decide`. */
class ChatCallerCarrierSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env() // keys off, user auth on
        val db = new InMemoryUserDb
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        db.put(User("admin", "h", User.RoleAdmin, null, null, null))
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    private val admin: User = User("admin", "h", User.RoleAdmin, null, null, null)
    private val tapKey: ResolvedKey = ResolvedKey(None, "tap:t1", TapRunTokens.Capabilities, isLegacyFullAccess = false)
    private val uiKey: ResolvedKey = ResolvedKey(None, "ui", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** A mock request whose attributes are a real map, with the given headers. */
    private def request(headers: (String, String)*): HttpServletRequest = {
        val attrs = new java.util.HashMap[String, AnyRef]()
        val r = mock(classOf[HttpServletRequest])
        when(r.getCookies).thenReturn(null)
        when(r.getHeader(anyString())).thenReturn(null)
        headers.foreach { case (k, v) => when(r.getHeader(k)).thenReturn(v) }
        doAnswer(inv => { attrs.put(inv.getArgument[String](0), inv.getArgument[AnyRef](1)); null })
            .when(r).setAttribute(anyString(), any())
        doAnswer(inv => { attrs.remove(inv.getArgument[String](0)); null }).when(r).removeAttribute(anyString())
        when(r.getAttribute(anyString())).thenAnswer(inv => attrs.get(inv.getArgument[String](0)))
        r
    }

    test("decide refuses a relabelled key whose carrier is a tap-run token, with or without a session user") {
        val relabelled = uiKey.copy(label = "session:admin")
        Seq((None, Some(admin)), (Some(admin), None), (None, None)).foreach { case (sess, obo) =>
            ChatCaller.decide(sess, obo, Some(relabelled), None, Some("tap:t1")) match {
                case Refuse(403, body) => assert(body == ChatCaller.TapTokenRefusedBody)
                case other => fail(s"expected 403, got $other")
            }
        }
    }

    test("decide with a non-tap or no carrier label matches the four-argument decide") {
        Seq(Some("ui"), Some("anonymous"), None).foreach { carrier =>
            assert(ChatCaller.decide(None, Some(admin), Some(uiKey), None, carrier) == ChatCaller.decide(None, Some(admin), Some(uiKey), None))
            assert(ChatCaller.decide(None, None, Some(tapKey), None, carrier) == ChatCaller.decide(None, None, Some(tapKey), None))
        }
    }

    test("carrierLabel: the attribute when it is a tap label, else a presented live tap token, else the attribute") {
        assert(ChatCaller.carrierLabel(Some("tap:x"), null).contains("tap:x"))
        assert(ChatCaller.carrierLabel(Some("anonymous"), null).contains("anonymous"))
        assert(ChatCaller.carrierLabel(None, null).isEmpty)
        assert(ChatCaller.carrierLabel(None, "trt_not-a-live-token").isEmpty)
        val t = TapRunTokens.issue("ci_probe", None, 60)
        try {
            assert(ChatCaller.carrierLabel(None, t).contains("tap:ci_probe"))
            assert(ChatCaller.carrierLabel(Some("anonymous"), t).contains("tap:ci_probe"))
        } finally TapRunTokens.revoke(t)
    }

    test("keys off: a tap-run token sending X-Datris-On-Behalf-Of: admin is refused 403 after TenantInterceptor relabels it") {
        val t = TapRunTokens.issue("ci_probe", None, 60)
        try {
            val r = request("x-api-key" -> t, AuditActor.HeaderOnBehalfOf -> "admin")
            assert(new TenantInterceptor().preHandle(r, mock(classOf[HttpServletResponse]), null))
            // The bypass the fix closes: the key arrives relabelled as the admin.
            val rk = r.getAttribute(TenantInterceptor.ResolvedKeyAttr).asInstanceOf[ResolvedKey]
            assert(rk.label == "session:admin")
            assert(r.getAttribute(AuditActor.OnBehalfOfAttr).isInstanceOf[User])
            ChatCaller.from(r) match {
                case Refuse(403, body) => assert(body == ChatCaller.TapTokenRefusedBody)
                case other => fail(s"expected 403, got $other")
            }
        } finally TapRunTokens.revoke(t)
    }

    test("keys off: the anonymous identity on behalf of admin still chats as admin") {
        val r = request(AuditActor.HeaderOnBehalfOf -> "admin")
        assert(new TenantInterceptor().preHandle(r, mock(classOf[HttpServletResponse]), null))
        ChatCaller.from(r) match {
            case Allow(id) =>
                assert(id.user.map(_.username).contains("admin"))
                assert(!id.ownKey)
            case other => fail(s"expected Allow, got $other")
        }
    }
}
