package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import org.springframework.util.AntPathMatcher

/** What an audited request was, in audit-log terms. */
case class AuditRoute(category: String, action: String, resourceType: String)

/** Maps `method + path` to an audit category/action.
  *
  * Reuses [[CapabilityRoutes]] as the source of truth — an audit category IS
  * the capability resource, an audit action IS the capability action — so
  * there is one route table to keep complete, not two. The small supplemental
  * table below covers the routes the capability check deliberately skips
  * (login, users, keys) because they are gated by role rather than by key.
  *
  * Reads are dropped unless `logReads` is on; secret reads are the one
  * carve-out (they answer "who looked at the prod DB password"). Anything
  * non-GET that neither table knows about is logged as `unmapped` so a new
  * endpoint can't silently escape coverage. */
object AuditClassifier {

    private val matcher = new AntPathMatcher()

    private case class Supplemental(method: String, pattern: String, route: AuditRoute)

    /** Routes outside the capability table. Verified against the controllers
      * as of v1.22.0; the AuditCoverageSpec fails the build if a non-GET
      * controller method is missing from both this table and CapabilityRoutes. */
    private val supplemental: Seq[Supplemental] = Seq(
        Supplemental("POST", "/api/v1/auth/login", AuditRoute("auth", "login", "user")),
        Supplemental("POST", "/api/v1/auth/logout", AuditRoute("auth", "logout", "user")),
        Supplemental("POST", "/api/v1/auth/change-password", AuditRoute("auth", "change-password", "user")),
        Supplemental("POST", "/api/v1/auth/users", AuditRoute("user", "create", "user")),
        Supplemental("PATCH", "/api/v1/auth/users/*", AuditRoute("user", "update", "user")),
        Supplemental("DELETE", "/api/v1/auth/users/*", AuditRoute("user", "delete", "user")),
        Supplemental("POST", "/api/v1/keys", AuditRoute("key", "issue", "key")),
        Supplemental("DELETE", "/api/v1/keys/*", AuditRoute("key", "revoke", "key")),
        Supplemental("POST", "/api/v1/keys/*/rotate", AuditRoute("key", "rotate", "key")),
        Supplemental("DELETE", "/api/v1/mcp/activity", AuditRoute("mcp", "clear-activity", "mcp")),
        // Assistant attachments: a file dropped into the chat. The chat streams
        // themselves are not audited — the tool calls they make are, via the
        // MCP → REST hop.
        Supplemental("POST", "/api/v1/assistant/attachment", AuditRoute("document", "upload", "attachment"))
    )

    /** Non-GET routes that are deliberately NOT audited. Each is a stream or
      * an internal callback, not a user action; what the user actually did
      * through them is audited elsewhere. Keep this list short and justified —
      * AuditCoverageSpec asserts every entry still matches a live route. */
    private[audit] val neverAudited: Seq[(String, String, String)] = Seq(
        ("POST", "/api/v1/assistant/chat", "SSE chat stream; its tool calls are audited on the MCP → REST hop"),
        ("POST", "/api/v1/ops-chat/chat", "SSE chat stream; its tool calls are audited on the MCP → REST hop"),
        ("POST", "/api/v1/catalog-chat/chat", "SSE chat stream; its tool calls are audited on the MCP → REST hop"),
        ("POST", "/api/v1/search-chat/chat", "SSE chat stream; read-only tool catalog"),
        ("POST", "/api/v1/restendpoint/callback", "internal callback from the REST-endpoint runner, not a caller action")
    )

    private def isNeverAudited(method: String, path: String): Boolean =
        neverAudited.exists { case (m, pattern, _) => m == method && matcher.`match`(pattern, path) }

    /** Capability resources whose every action is a read. */
    private val readOnlyResources: Set[String] = Set("query", "search", "metadata")

    private def isRead(resource: String, action: String): Boolean =
        action == "read" || readOnlyResources.contains(resource)

    def classify(method: String, path: String, logReads: Boolean): Option[AuditRoute] = {
        val m = if (method == null) "" else method.toUpperCase
        if (m == "HEAD" || m == "OPTIONS") return None
        if (isNeverAudited(m, path)) return None

        CapabilityRoutes.lookup(m, path) match {
            case RouteCheck.Require(resource, action) =>
                if (isRead(resource, action) && !logReads && resource != "secret") None
                else Some(AuditRoute(resource, action, resource))

            case RouteCheck.Skip =>
                supplemental
                    .find(s => s.method == m && matcher.`match`(s.pattern, path))
                    .map(_.route)

            case RouteCheck.Unmapped =>
                if (m == "GET") None
                else Some(AuditRoute("unmapped", m.toLowerCase, "route"))
        }
    }

    /** True when the route is one the classifier knows (either table). Used by
      * the coverage spec. */
    def isKnown(method: String, path: String): Boolean =
        classify(method, path, logReads = true).exists(_.category != "unmapped")

    /** Deliberately un-audited (see [[neverAudited]]). Exposed for the
      * coverage spec so it can tell "deliberately skipped" from "forgotten". */
    def isDeliberatelySkipped(method: String, path: String): Boolean =
        isNeverAudited(if (method == null) "" else method.toUpperCase, path)
}
