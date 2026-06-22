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
  */
object VersionActor {
    def resolve(request: HttpServletRequest): String = {
        val env = DatrisEnvironment.current
        if (env.useUserAuth) {
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
