package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ResolvedKey

/** Who sees only part of the install (plans/stories/scoped-key-read-routes.md,
  * Design 1, Decision D1). Pure on [[ResolvedKey]].
  *
  * A restricted user's session is always limited. An API key is limited for
  * a resource (`pipeline` or `tap`) when it is not full access, holds at
  * least one `pipeline:*` or `tap:*` capability carrying a `catalog=` or
  * `owner=` scope, and holds no unscoped read on that resource. The scoped
  * capability may be on either resource: a key holding only
  * `pipeline:read:catalog=Sales` is limited for taps too (it reads no tap),
  * and one holding unscoped `pipeline:read` and `tap:run:owner=self` is
  * limited for taps only. A key with no scoped pipeline or tap capability (a
  * `job:read` monitor key, the read-only, reporting and ops templates) is not
  * limited, which is where this differs from the write-side
  * `PipelineAPIController.limitedVisibility` (kept).
  *
  * Whether the key carries a `catalog=` / `owner=` scope is read from every
  * stored capability, inert ones included, in both modes (inert scopes fail
  * closed); whether it holds unscoped read is read from the capabilities that
  * grant ([[ResolvedKey.effectiveCapabilities]]). So a key whose only scoped
  * pipeline or tap capability is inert is limited in both modes when it
  * lacks unscoped read. */
object CallerVisibility {

    private val ScopedResources = Set("pipeline", "tap")
    private val LimitingScopeKeys = Set("catalog", "owner")

    /** Limited for pipelines (pipeline reads and writes are held to the scope). */
    def limitedPipelines(rk: ResolvedKey): Boolean = limitedFor(rk, "pipeline")

    /** Limited for taps. */
    def limitedTaps(rk: ResolvedKey): Boolean = limitedFor(rk, "tap")

    /** Limited for pipelines or taps. */
    def limited(rk: ResolvedKey): Boolean = limitedPipelines(rk) || limitedTaps(rk)

    /** [[limitedPipelines]] / [[limitedTaps]] by resource name; false for any
      * other resource. */
    def limitedFor(rk: ResolvedKey, resource: String): Boolean =
        if (rk == null) false
        else if (rk.restrictedSession) true
        else if (!ScopedResources.contains(resource) || rk.isFullAccess) false
        else holdsScopedPipelineOrTap(rk) && !holdsUnscoped(rk, resource, "read")

    /** The one per-item read rule for a STORED pipeline or tap
      * (plans/stories/scoped-key-read-routes.md, Part B): the identity's
      * grants or key scope allow `<resource>:read` on the stored item's
      * catalog and owner (`createdByKeyLabel`), never on request input. No
      * identity (auth off): true. Record routes apply it only to a limited
      * caller ([[limited]]); `PipelineAPIController.mayRead` and
      * `TapAPIController.mayRead` are this rule on a request. */
    def mayReadStored(rk: Option[ResolvedKey], resource: String, catalog: String, owner: String): Boolean =
        rk.forall(_.grants(resource, "read", ai.datris.util.CatalogOps.scopeContext(catalog, owner)))

    /** True when some stored capability on `pipeline` or `tap` carries a
      * `catalog=` or `owner=` scope. Read from the applied AND the inert
      * capabilities in both modes, so a scoped capability whose other scope
      * key is not applied (`pipeline:read:catalog=Sales,collection=docs`)
      * still marks the key as meant to be limited: inert scopes fail closed. */
    private def holdsScopedPipelineOrTap(rk: ResolvedKey): Boolean =
        (rk.capabilities ++ rk.inertCapabilities).exists(c => ScopedResources.contains(c.resource) && c.scope.keySet.exists(LimitingScopeKeys.contains))

    /** True when a granting capability covers (resource, action) with no scope. */
    def holdsUnscoped(rk: ResolvedKey, resource: String, action: String): Boolean =
        rk.isLegacyFullAccess || rk.effectiveCapabilities.exists(c => c.scope.isEmpty && c.matchesResourceAction(resource, action))

    /** True when some granting capability matches (resource, action), scoped or not. */
    def holdsAny(rk: ResolvedKey, resource: String, action: String): Boolean =
        rk.isLegacyFullAccess || rk.effectiveCapabilities.exists(_.matchesResourceAction(resource, action))

    /** Whether a key's scope can refuse it (resource, action) on a named item
      * at all, so the item is read only then. True when the caller is an API
      * key (not a session), not full access, holds no unscoped capability for
      * (resource, action), and either holds a scoped one or is limited for
      * the resource. A key that holds the action nowhere and is not limited
      * keeps today's answer (the route gate decided): this is what keeps, for
      * example, the rag-builder template (unscoped `tap:create`, no
      * `tap:update`) generating scripts for any tap. False for sessions:
      * restricted ones have their own not-found rules, unrestricted ones hold
      * unscoped role bundles. */
    def keyScopeApplies(rk: ResolvedKey, resource: String, action: String): Boolean =
        rk != null && !rk.restrictedSession && !rk.isFullAccess && !holdsUnscoped(rk, resource, action) &&
            (holdsAny(rk, resource, action) || limitedFor(rk, resource))

    /** True when an API key is refused (resource, action) on an item whose
      * scope context is `context` ([[keyScopeApplies]] and no granting
      * capability covers it). Always false for sessions. */
    def keyOutOfScope(rk: ResolvedKey, resource: String, action: String, context: Map[String, String]): Boolean =
        keyScopeApplies(rk, resource, action) && !rk.grants(resource, action, context)

    /** The one rule for an API key writing to a STORED pipeline or tap
      * whose scope context is `context` (`POST /tap`, every tap write
      * sub-route, tap and pipeline version restore, dest-types apply):
      *   1. refused on `named` when some action of `accepted` is out of the
      *      key's scope ([[keyOutOfScope]]) and no action of `accepted` is
      *      granted on `context` (for taps `accepted` = create, update, the
      *      tap twin of `PipelineAPIController.overwriteRefusal`);
      *   2. else refused on `read` when the key's read is out of scope: a
      *      stored item the key cannot read is never written, and a route
      *      that returns the item's content never returns it.
      * The write check runs first so the 403 names the action attempted, as
      * `POST /pipeline` does. None: allowed. Pure. */
    def storedWriteDenial(rk: ResolvedKey, resource: String, context: Map[String, String], accepted: Seq[String], named: String): Option[String] =
        if (accepted.exists(a => keyOutOfScope(rk, resource, a, context)) && !accepted.exists(a => rk.grants(resource, a, context))) Some(named)
        else if (keyOutOfScope(rk, resource, "read", context)) Some("read")
        else None

    /** The refusal text for a route or request that covers the whole
      * installation and has nothing to filter, for a limited caller
      * ([[limited]]). `what` says what is refused; nothing else is named. */
    def installWideMessage(what: String): String =
        "capability denied: " + what + " covers the whole installation and is not available to an API key or user " +
            "limited to a catalog or to what it created"

    /** [[installWideMessage]] for every Part C refusal of a scope-limited
      * key (plans/stories/scoped-key-read-routes.md): table and collection
      * listings, Unity Catalog browse, the raw Postgres, MongoDB and
      * natural-language queries, and a caller `sql` on a warehouse query.
      * One text for all, naming nothing from the request. */
    val InstallWideRequestMessage: String = installWideMessage("this request")

    /** Tap writes accept `tap:create` or `tap:update` on the stored tap. */
    val TapWriteActions: Seq[String] = Seq("create", "update")

    /** The pipeline right a load into a pipeline needs (upload, `kill_job`,
      * a tap's target pipeline): `run` when the caller holds an APPLIED
      * `pipeline:run` capability ([[holdsAppliedRun]]), otherwise `read`. An
      * inert `pipeline:run` (one whose scope the platform does not apply)
      * never decides the load action, in either `DATRIS_KEY_INERT_SCOPES`
      * mode. */
    def loadAction(rk: ResolvedKey): String = if (rk != null && holdsAppliedRun(rk)) "run" else "read"

    /** True when an applied (non-inert) capability matches `pipeline:run`. */
    def holdsAppliedRun(rk: ResolvedKey): Boolean =
        rk.isLegacyFullAccess || rk.capabilities.exists(_.matchesResourceAction("pipeline", "run"))

    /** The decision for a load into a stored pipeline with scope `context`
      * (upload, `kill_job`, a tap's target pipeline on save, run, restore and
      * enable-in-order): the [[loadAction]] right, then read, through the one
      * stored-item rule ([[storedWriteDenial]] with the load action), so a
      * key may load only into a pipeline it may also read. A restricted
      * session is decided on its grants for the load action (they imply
      * read). Some(action refused) or None. Callers apply [[loadChecked]]
      * first. */
    def loadDenial(rk: ResolvedKey, context: Map[String, String]): Option[String] = {
        val action = loadAction(rk)
        if (rk.restrictedSession) { if (rk.grants("pipeline", action, context)) None else Some(action) }
        else storedWriteDenial(rk, "pipeline", context, Seq(action), action)
    }

    /** Whether a load into a pipeline is checked against the caller's scope
      * ([[loadDenial]]): a restricted session, or a key that is not full
      * access and holds an applied `pipeline:run`, is limited for pipelines,
      * or whose pipeline read can be refused ([[keyScopeApplies]] on read,
      * which in allow mode includes a key holding only an inert-scoped
      * `pipeline:read`). Skipped, reading nothing: no identity, full access,
      * a key with no applied `pipeline:run` whose pipeline read cannot be
      * refused (unscoped read, or no pipeline read and not limited, such as a
      * `document:upload`-only key), and a caller holding unscoped
      * `pipeline:run` and `pipeline:read` (the ops template, editor
      * sessions), which every load grants. */
    def loadChecked(rk: ResolvedKey): Boolean =
        rk != null && (rk.restrictedSession || !rk.isFullAccess &&
            (holdsAppliedRun(rk) || limitedPipelines(rk) || keyScopeApplies(rk, "pipeline", "read")) &&
            // unscoped pipeline:run and read (the ops template, editor sessions)
            // pass every load: nothing is read to decide
            !(holdsUnscoped(rk, "pipeline", "run") && holdsUnscoped(rk, "pipeline", "read")))
}
