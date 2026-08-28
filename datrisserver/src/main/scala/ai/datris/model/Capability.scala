package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Parsed representation of one capability string.
  *
  * Capability strings have the form `resource:action[:scope]`, e.g.
  *   - pipeline:create:catalog=support
  *   - tap:run:owner=self
  *   - search:vector:collection=*
  *   - *:*  (legacy full access)
  *
  * Scope segments must be `key=value` pairs (comma-separated if multiple).
  * Scope keys are restricted to things knowable at key-issuance time —
  * containers (catalog, database, collection), types (_type, destination_kind),
  * and ownership (owner). Leaf resource names (a tap name, a pipeline name
  * the agent picks at runtime) cannot appear in scope. */
case class Capability(
    resource: String,
    action: String,
    scope: Map[String, String],
    raw: String
) {

    /** Does this granted capability satisfy a request for (requiredResource,
      * requiredAction) given a runtime context?
      *
      * `context` carries the scope values for the resource being touched —
      * e.g. {"catalog" -> "support", "owner" -> "support-rag-builder"}.
      *
      * `callerKeyLabel` is the label of the API key making the request, used
      * to evaluate `owner=self` (which matches when context's `owner` value
      * equals the caller's key label). */
    def grants(
        requiredResource: String,
        requiredAction: String,
        context: Map[String, String],
        callerKeyLabel: String
    ): Boolean = {
        if (!matchesResourceAction(requiredResource, requiredAction)) return false

        scope.forall { case (k, v) =>
            if (k == "owner" && v == "self")
                context.get("owner").exists(_ == callerKeyLabel)
            else if (v == "*")
                context.contains(k)
            else
                context.get(k).contains(v)
        }
    }

    /** Scope-agnostic match for the pre-action interceptor gate. Returns
      * true if this capability's resource and action are compatible with
      * the required pair, ignoring scope predicates entirely. The interceptor
      * uses this because it doesn't have the loaded resource yet — scope
      * checks are deferred to controllers (via CapabilityCheck.assertScope)
      * after the resource is loaded. */
    def matchesResourceAction(requiredResource: String, requiredAction: String): Boolean = {
        val resourceMatch = resource == "*" || resource == requiredResource
        val actionMatch = action == "*" || action == requiredAction
        resourceMatch && actionMatch
    }
}

object Capability {

    /** Scope keys permitted in capability strings. The list is intentionally
      * small: only things a human can know at key-issuance time. Leaf
      * resource names (tap, pipeline) are deliberately absent — agents pick
      * those at runtime, so pre-scoping by them produces unenforceable rules. */
    private val AllowedScopeKeys: Set[String] = Set(
        "catalog",
        "database",
        "collection",
        "destination_kind",
        "_type",
        "owner"
    )

    def parse(s: String): Capability = {
        if (s == null || s.isEmpty)
            throw new DatrisException("Empty capability string")

        val parts = s.split(":", 3)
        if (parts.length < 2)
            throw new DatrisException(
                s"Invalid capability '$s' — expected 'resource:action[:scope]'"
            )

        val resource = parts(0).trim
        val action = parts(1).trim
        val scopeStr = if (parts.length == 3) parts(2).trim else ""

        if (resource.isEmpty || action.isEmpty)
            throw new DatrisException(
                s"Invalid capability '$s' — resource and action must be non-empty"
            )

        val scope: Map[String, String] =
            if (scopeStr.isEmpty) Map.empty
            else {
                scopeStr.split(",").map { kv =>
                    val eq = kv.indexOf('=')
                    if (eq <= 0)
                        throw new DatrisException(
                            s"Invalid capability '$s' — scope segment '$kv' must be 'key=value'"
                        )
                    val k = kv.substring(0, eq).trim
                    val v = kv.substring(eq + 1).trim
                    if (!AllowedScopeKeys.contains(k))
                        throw new DatrisException(
                            s"Invalid capability '$s' — scope key '$k' is not allowed. " +
                                s"Allowed: ${AllowedScopeKeys.toSeq.sorted.mkString(", ")}. " +
                                s"Leaf resource names (e.g. tap=<name>, pipeline=<name>) cannot be scoped — " +
                                s"the agent picks those at runtime."
                        )
                    if (v.isEmpty)
                        throw new DatrisException(
                            s"Invalid capability '$s' — scope value for '$k' is empty"
                        )
                    k -> v
                }.toMap
            }

        Capability(resource, action, scope, s)
    }

    def parseList(strs: Seq[String]): Seq[Capability] = strs.map(parse)

    /** The legacy "full access" capability auto-attached to existing API keys
      * on upgrade so they keep working with no behavior change. */
    val FullAccess: Capability = Capability("*", "*", Map.empty, "*:*")
}

/** The result of validating an x-api-key and looking up its capability
  * bundle. Carries the tenant routing (preserved from
  * `validateAndResolve`'s old return type), the human-readable key label,
  * the parsed capabilities, and a flag indicating whether this key is a
  * legacy unscoped credential (i.e. behaves as `*:*` for backward compat). */
case class ResolvedKey(
    tenantEnvironment: Option[String],
    label: String,
    capabilities: Seq[Capability],
    isLegacyFullAccess: Boolean,
    /** Stable per-issue identifier from the key's metadata blob. A label can
      * be revoked and re-issued — rotate keeps the identity (same label, new
      * secret) but revoke + issue with the same label is a *new* key wearing
      * the old name. The audit log stores this alongside the label so those
      * two are distinguishable. None for keys seeded before ids existed,
      * for session-derived identities, and in anonymous mode. */
    keyId: Option[String] = None
) {

    /** Does this key grant (resource, action) given a runtime context?
      * Legacy keys always grant. */
    def grants(
        resource: String,
        action: String,
        context: Map[String, String] = Map.empty
    ): Boolean = {
        if (isLegacyFullAccess) return true
        capabilities.exists(_.grants(resource, action, context, label))
    }

    /** Scope-agnostic version of `grants` — true if any capability matches
      * the resource+action pair, ignoring scope predicates. Used by the
      * CapabilityInterceptor as a pre-action gate: it doesn't have the
      * loaded resource yet, so scope-aware checks must wait until the
      * controller. A scoped capability like `pipeline:run:owner=self`
      * matches `(pipeline, run)` here even though the in-action scope
      * check may later refuse based on the loaded pipeline's owner. */
    def matchesResourceAction(resource: String, action: String): Boolean = {
        if (isLegacyFullAccess) return true
        capabilities.exists(_.matchesResourceAction(resource, action))
    }
}
