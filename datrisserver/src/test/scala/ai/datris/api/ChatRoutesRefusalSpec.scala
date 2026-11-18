package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{ChatCaller, TapRunTokens}
import ai.datris.config.TenantInterceptor
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{mock, never, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.web.multipart.MultipartFile

import java.io.{PrintWriter, StringWriter}

/** Story: Chat endpoints act with the caller's own authority (review
  * follow-up). Every one of the seven chat routes calls `ChatCaller.from`
  * before any side effect: a request carrying a tap-run identity gets 403, a
  * request with no identity under keys-on gets 401, and in both cases no
  * stream opens (the SSE handlers return null, so no worker runs: no MCP call,
  * no incident read), init answers before any MCP call, and the attachment
  * endpoint never touches the uploaded file. */
class ChatRoutesRefusalSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true)
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
    }

    override def afterEach(): Unit = {
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    private val tapKey: ResolvedKey = ResolvedKey(None, "tap:ci_probe", TapRunTokens.Capabilities, isLegacyFullAccess = false)

    private val body: String = """{"messages":[{"role":"user","content":"List every pipeline and every open incident."}]}"""

    private def request(resolved: Option[ResolvedKey]): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        // Unstubbed attributes answer null; only the resolved key is attached.
        resolved.foreach(rk => when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk))
        when(r.getHeader(anyString())).thenReturn(null)
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/chat")
        r
    }

    private class Captured {
        val out = new StringWriter()
        val response: HttpServletResponse = mock(classOf[HttpServletResponse])
        when(response.getWriter).thenReturn(new PrintWriter(out, true))
    }

    /** The five SSE routes plus init and attachment, each returning (status, body). */
    private def routes: Seq[(String, (HttpServletRequest) => (Int, String))] = {
        def sse(call: (HttpServletRequest, HttpServletResponse) => AnyRef): HttpServletRequest => (Int, String) = { req =>
            val c = new Captured
            val emitter = call(req, c.response)
            assert(emitter == null, "a refused chat must not open a stream")
            val status = org.mockito.ArgumentCaptor.forClass(classOf[Int])
            verify(c.response).setStatus(status.capture())
            (status.getValue, c.out.toString)
        }
        Seq(
            "POST /ops-chat/chat" -> sse((r, s) => new OpsChatAPIController().chat("k", body, r, s)),
            "POST /assistant/chat" -> sse((r, s) => new AssistantAPIController().chat("k", body, r, s)),
            "POST /catalog-chat/chat" -> sse((r, s) => new CatalogChatAPIController().chat("k", body, r, s)),
            "POST /search-chat/chat" -> sse((r, s) => new SearchChatAPIController().chat("k", body, r, s)),
            "POST /config-chat/chat" -> sse((r, s) => new ConfigChatAPIController().chat("k", body, r, s)),
            "GET /assistant/init" -> { (r: HttpServletRequest) =>
                val e = new AssistantAPIController().init("k", r)
                (e.getStatusCode.value, e.getBody)
            },
            "POST /assistant/attachment" -> { (r: HttpServletRequest) =>
                val file = mock(classOf[MultipartFile])
                val e = new AssistantAttachmentController().stage("k", file, r)
                verifyNoInteractions(file)
                (e.getStatusCode.value, e.getBody)
            }
        )
    }

    test("all seven chat routes are covered") {
        assert(routes.size == 7)
    }

    test("a tap-run identity gets 403 on every chat route, before any side effect") {
        routes.foreach { case (name, call) =>
            val (status, b) = call(request(Some(tapKey)))
            assert(status == 403, name)
            assert(b == ChatCaller.TapTokenRefusedBody, name)
        }
    }

    test("no identity under keys-on gets 401 on every chat route, before any side effect") {
        routes.foreach { case (name, call) =>
            val (status, b) = call(request(None))
            assert(status == 401, name)
            assert(b == ApiErrors.AuthenticationRequiredBody, name)
        }
    }

    test("a refused SSE chat never flushes an event stream content type") {
        val c = new Captured
        assert(new OpsChatAPIController().chat("k", body, request(Some(tapKey)), c.response) == null)
        verify(c.response).setContentType("application/json")
        verify(c.response, never()).setContentType("text/event-stream")
    }
}
