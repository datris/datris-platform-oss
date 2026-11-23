package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, UserContext}
import jakarta.servlet.http.HttpServletRequest

/** Resolves the actor to stamp on a definition version (`createdBy`), per the
  * deployment's auth mode (tap-pipeline-versioning decision #2):
  *
  *   - `useUserAuth`  → the logged-in username (UI edits), falling back to the
  *                      request's API key label, then "system".
  *   - `useApiKeys`   → the request's API key label (MCP / API edits), else "agent".
  *   - neither (OSS)  → the API key label if somehow present, else "system".
  *
  * The replay of an approved agent action runs with the requester's
  * identity (its label is the requester's, for owner tagging), but the
  * version names the approver, `session:<approver>`, as before; with no
  * approver named (user sign-in off), the platform key that presented it.
  */
object VersionActor {
    def resolve(request: HttpServletRequest): String = {
        val env = DatrisEnvironment.current
        val replay = request != null && request.getAttribute(ai.datris.audit.AuditActor.ApprovalReplayAttr) != null
        val approver =
            if (!replay) None
            else Option(request.getAttribute(ai.datris.audit.AuditActor.OnBehalfOfAttr)).collect { case u: ai.datris.model.User => u }
        val presentedBy = if (replay) ai.datris.config.TenantInterceptor.verifiedReplay(request).map(_.carrier.label) else None
        if (approver.isDefined) "session:" + approver.get.username
        else if (presentedBy.isDefined) presentedBy.get
        else if (env.useUserAuth) {
            UserContext.get().map(_.username)
                .orElse(ResolvedKeyAccess.keyLabel(request))
                .getOrElse("system")
        } else if (env.useApiKeys) {
            ResolvedKeyAccess.keyLabel(request).getOrElse("agent")
        } else {
            ResolvedKeyAccess.keyLabel(request).getOrElse("system")
        }
    }
}
