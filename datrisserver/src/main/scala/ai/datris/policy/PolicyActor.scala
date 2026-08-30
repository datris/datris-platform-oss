package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.auth.{ResolvedKeyAccess, TapRunTokens}
import ai.datris.model.UserContext
import jakarta.servlet.http.HttpServletRequest

/** Decides whether a request is agent-initiated — the only requests the
  * policy applies to. Humans are the approvers and are never gated.
  *
  *   - Browser session, no MCP session header → human.
  *   - Anything the MCP server relayed (it always forwards
  *     `X-Datris-Agent-Session`) → agent, including the in-platform
  *     Assistant / Ops chat acting on behalf of a logged-in user.
  *   - A programmatic key that is not the platform's own `ui` key and not the
  *     anonymous no-keys identity → agent (external MCP clients, CLI scripts).
  *   - A tap's per-run callback token → not an agent (read-only, and a tap is
  *     not a decision-maker).
  *   - `ui` key with no session header → the UI itself → human. */
object PolicyActor {

    def isAgent(request: HttpServletRequest): Boolean = {
        val agentSession = Option(request.getHeader(AuditActor.HeaderAgentSession)).exists(_.trim.nonEmpty)
        if (UserContext.get().isDefined && !agentSession) return false
        ResolvedKeyAccess.fromRequest(request) match {
            case Some(rk) if TapRunTokens.isTapLabel(rk.label) => false
            case Some(_) if agentSession => true
            case Some(rk) =>
                rk.label != AuditActor.UiKeyLabel &&
                    rk.label != AuditActor.Anonymous &&
                    !rk.label.startsWith("session:")
            case None => agentSession
        }
    }

    /** The label the pending action is filed under — the same string the
      * audit log uses, so the two can be joined. */
    def label(request: HttpServletRequest): String =
        AuditActor.resolve(request).label
}
