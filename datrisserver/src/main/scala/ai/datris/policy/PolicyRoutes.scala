package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import org.springframework.util.AntPathMatcher

/** Turns a request into the policy action key it is judged by.
  *
  * The vocabulary IS the capability table: an action key is the
  * `resource:action` [[CapabilityRoutes]] already assigns the route, so a
  * policy can only name something the capability layer classifies. The
  * small sub-action table below refines one route where a single
  * capability covers materially different operations — the first is the
  * destination-type migration, which shares `pipeline:update` with an
  * ordinary config save but rewrites a landed table. */
object PolicyRoutes {

    private val matcher = new AntPathMatcher()

    /** (method, path pattern, action key). The parent `resource:action`
      * must still be what CapabilityRoutes returns for the same route. */
    private val subActions: Seq[(String, String, String)] = Seq(
        ("POST", "/api/v1/pipeline/dest-types", "pipeline:update:dest-types")
    )

    /** Every key a policy may name. */
    val knownActionKeys: Set[String] = CapabilityRoutes.allActionKeys ++ subActions.map(_._3)

    private val knownResources: Set[String] = knownActionKeys.map(_.split(":")(0))

    def isKnownActionKey(key: String): Boolean =
        knownActionKeys.contains(key) || (key.endsWith(":*") && knownResources.contains(key.dropRight(2)))

    /** The action key for a request, or None when the route is skip-class or
      * unmapped (policy never applies there — capability enforcement is the
      * only gate on unmapped routes, as today). */
    def actionKey(method: String, path: String): Option[String] = {
        subActions.find { case (m, p, _) => m.equalsIgnoreCase(method) && matcher.`match`(p, path) } match {
            case Some((_, _, key)) => Some(key)
            case None =>
                CapabilityRoutes.lookup(method, path) match {
                    case RouteCheck.Require(resource, action) => Some(resource + ":" + action)
                    case _ => None
                }
        }
    }

    def resourceType(actionKey: String): String = actionKey.split(":")(0)

    private val ReadActions = Set("read")
    private val ReadResources = Set("query", "search", "metadata", "mcp")

    /** Reads are never policy-gated: pausing a listing for approval is
      * meaningless and capabilities already scope what can be read. Policy
      * management and approval decisions are governed by the hard rules in
      * PolicyActor (agents may never do either), not by the policy itself. */
    def isGateable(actionKey: String): Boolean = {
        val parts = actionKey.split(":")
        val resource = parts(0)
        val action = if (parts.length > 1) parts(1) else ""
        !ReadActions.contains(action) && !ReadResources.contains(resource) &&
            resource != "policy" && resource != "approval"
    }

    /** Sorted list for the UI / GET /policy, grouped by resource. */
    def catalog: Seq[String] = knownActionKeys.filter(isGateable).toSeq.sorted
}
