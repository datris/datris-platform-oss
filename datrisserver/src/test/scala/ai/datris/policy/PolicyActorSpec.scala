package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, ResolvedKey, User, UserContext}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

class PolicyActorSpec extends AnyFunSuite {

    private def key(label: String) = ResolvedKey(None, label, Capability.parseList(Seq("tap:delete")), isLegacyFullAccess = false)

    private def req(key: Option[ResolvedKey], agentSession: Boolean = false, session: Option[User] = None): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("DELETE")
        when(r.getRequestURI).thenReturn("/api/v1/tap")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => key.orNull)
        when(r.getHeader(AuditActor.HeaderAgentSession)).thenAnswer(_ => if (agentSession) "sess-1" else null)
        session match {
            case Some(u) => UserContext.set(u)
            case None => UserContext.clear()
        }
        r
    }

    private val todd = User("todd", "hash", User.RoleAdmin, "t", "t", null)

    test("a browser session with no MCP session header is a human") {
        assert(!PolicyActor.isAgent(req(Some(key("session:todd")), session = Some(todd))))
        UserContext.clear()
    }

    test("anything relayed by the MCP server is an agent — including the Assistant acting for a user") {
        assert(PolicyActor.isAgent(req(Some(key("session:todd")), agentSession = true)))
        assert(PolicyActor.isAgent(req(Some(key("ui")), agentSession = true)))
        assert(PolicyActor.isAgent(req(Some(key("anonymous")), agentSession = true)))
        assert(PolicyActor.isAgent(req(None, agentSession = true)))
    }

    test("an external programmatic key is an agent") {
        assert(PolicyActor.isAgent(req(Some(key("claude-desktop")))))
        assert(PolicyActor.isAgent(req(Some(key("research-agent")))))
    }

    test("the ui key, the anonymous identity and a relabeled session key without the MCP header are human") {
        assert(!PolicyActor.isAgent(req(Some(key(AuditActor.UiKeyLabel)))))
        assert(!PolicyActor.isAgent(req(Some(key(AuditActor.Anonymous)))))
        assert(!PolicyActor.isAgent(req(Some(key("session:todd")))))
        assert(!PolicyActor.isAgent(req(None)))
    }

    test("a tap's per-run callback token is never an agent") {
        assert(!PolicyActor.isAgent(req(Some(key("tap:prices")))))
        assert(!PolicyActor.isAgent(req(Some(key("tap:prices")), agentSession = true)))
    }
}
