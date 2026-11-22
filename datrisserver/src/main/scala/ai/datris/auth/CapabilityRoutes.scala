package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ResolvedKey
import org.springframework.util.AntPathMatcher

/** The result of looking up the capability required by an incoming request. */
sealed trait RouteCheck
object RouteCheck {

    /** Route is exempt — handled by user-session auth (login, /me, /users)
      * or genuinely public (health, version). Skip the capability check. */
    case object Skip extends RouteCheck

    /** No mapping found for this method+path combination. In log-only mode
      * this is informational; in enforce mode it should deny by default. */
    case object Unmapped extends RouteCheck

    /** This route requires the named capability. */
    case class Require(resource: String, action: String) extends RouteCheck
}

/** Central declarative mapping from HTTP method + path pattern to the
  * capability a request must hold. Lives in one file so the surface area
  * is auditable at a glance; alternative annotation-per-method approach
  * would require touching every controller. First match wins; patterns use
  * Spring AntPathMatcher syntax (`**` for any path tail). */
object CapabilityRoutes {

    private val matcher = new AntPathMatcher()

    private case class Route(method: String, pattern: String, resource: String, action: String)

    /** Patterns whose requests bypass the capability check entirely. These
      * are handled by the user-session auth path (login, role enforcement)
      * or are genuinely public infrastructure endpoints. */
    private val skipPatterns: Seq[String] = Seq(
        "/api/v1/auth/**",
        "/api/v1/login",
        "/api/v1/logout",
        "/api/v1/change-password",
        "/api/v1/me",
        "/api/v1/users",
        "/api/v1/users/**",
        "/api/v1/keys",
        "/api/v1/keys/**",
        "/api/v1/health/**",
        "/api/v1/version",
        // Which structured destination kinds are usable here (names of kinds
        // only, like /health/services); the MCP server and the create wizard
        // ask it with whatever key or session they hold.
        "/api/v1/destinations/available",
        // Callback from the REST-endpoint destination runner, matched by the
        // run's pipeline token in the body; it presents no key.
        "/api/v1/restendpoint/callback",
        "/api/v1/mcp/activity",
        // Any valid key or logged-in session may ask which MCP tools it can
        // see; the controller itself rejects requests with neither.
        "/api/v1/mcp/tools",
        "/api/v1/assistant/**",
        "/api/v1/ops-chat/**",
        "/api/v1/catalog-chat/**",
        "/api/v1/search-chat/**",
        "/api/v1/config-chat/**"
    )

    private val routes: Seq[Route] = Seq(
        // Pipelines
        Route("GET", "/api/v1/pipeline", "pipeline", "read"),
        Route("GET", "/api/v1/pipelines", "pipeline", "read"),
        Route("GET", "/api/v1/pipelines/*/unity-catalog", "pipeline", "read"),
        // Stored CodeGen scripts: read them and their version history; force a
        // new one, adopt the repository version, or restore a version. Those
        // three change what the pipeline runs, so they are pipeline updates.
        Route("GET", "/api/v1/pipelines/*/codegen-scripts", "pipeline", "read"),
        Route("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions", "pipeline", "read"),
        Route("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions/*", "pipeline", "read"),
        Route("GET", "/api/v1/pipelines/*/codegen-scripts/*/versions/*/diff", "pipeline", "read"),
        Route("POST", "/api/v1/pipelines/*/codegen-scripts/*/versions/*/restore", "pipeline", "update"),
        Route("POST", "/api/v1/pipelines/*/codegen-scripts/*/regenerate", "pipeline", "update"),
        Route("POST", "/api/v1/pipelines/*/codegen-scripts/*/pull", "pipeline", "update"),
        Route("POST", "/api/v1/pipeline", "pipeline", "create"),
        Route("DELETE", "/api/v1/pipeline", "pipeline", "delete"),
        // Rename needs what a delete needs (own on the pipeline's catalog):
        // the old name stops existing. Audited as action "rename".
        Route("POST", "/api/v1/pipelines/*/rename", "pipeline", "delete"),
        Route("POST", "/api/v1/pipeline/generate", "pipeline", "create"),
        Route("POST", "/api/v1/pipeline/upload", "document", "upload"),
        Route("POST", "/api/v1/pipeline/profile", "metadata", "read"),
        Route("GET", "/api/v1/pipeline/status", "job", "read"),
        Route("GET", "/api/v1/pipeline/result", "job", "read"),
        Route("DELETE", "/api/v1/pipeline/status", "job", "kill"),
        // Pipeline definition versions (read/diff = read; restore = update)
        Route("GET", "/api/v1/pipeline/versions", "pipeline", "read"),
        Route("GET", "/api/v1/pipeline/version", "pipeline", "read"),
        Route("GET", "/api/v1/pipeline/version/diff", "pipeline", "read"),
        Route("POST", "/api/v1/pipeline/version/restore", "pipeline", "update"),
        // Destination-side typing: proposing reads landed data; applying
        // changes the pipeline definition (and may migrate the dest table).
        Route("GET", "/api/v1/pipeline/dest-types", "pipeline", "read"),
        Route("POST", "/api/v1/pipeline/dest-types", "pipeline", "update"),
        // Preview-row schema proposal (before a pipeline exists): stateless,
        // reads only the posted records, saves nothing.
        Route("POST", "/api/v1/pipeline/propose-schema", "pipeline", "read"),
        // Field protection suggestions: stateless, names and types only, saves nothing.
        Route("POST", "/api/v1/pipeline/protect/suggest", "pipeline", "read"),
        // Safe Harbor preset proposal: stateless, deterministic name table, no model call.
        Route("POST", "/api/v1/pipeline/protect/preset", "pipeline", "read"),
        // Field protection reveal (decrypts `encrypt` ciphertext) and key
        // rotation. REST only, no MCP tool; `protect:*` is in no template,
        // role bundle or agent key, so only admins, `*:*` keys and keys
        // granted it explicitly pass.
        Route("POST", "/api/v1/protect/reveal", "protect", "reveal"),
        Route("POST", "/api/v1/protect/keys/rotate", "protect", "admin"),
        // Ordered enable of a catalog's taps (one server-side job): start and
        // cancel are tap:run, status a tap read. Listed BEFORE the catalog
        // `/**` rows so DELETE .../enable-ordered (cancel) is not a catalog
        // delete. The controller also scope-checks tap:update and tap:run
        // on every member before a job starts.
        Route("POST", "/api/v1/catalog/*/enable-ordered", "tap", "run"),
        Route("GET", "/api/v1/catalog/*/enable-ordered", "tap", "read"),
        Route("DELETE", "/api/v1/catalog/*/enable-ordered", "tap", "run"),
        // Catalog operations (rename / detach-or-cascade delete). One route
        // row per method; members are owner-scope checked in the controller.
        Route("PUT", "/api/v1/catalog/**", "pipeline", "update"),
        Route("DELETE", "/api/v1/catalog/**", "pipeline", "delete"),
        // Catalog backup (zip export) and restore. Members are additionally
        // scope-checked in the controller as tap/pipeline read (export) or
        // create (restore).
        Route("GET", "/api/v1/catalog/*/export", "pipeline", "read"),
        Route("POST", "/api/v1/catalog/*/restore", "pipeline", "create"),
        // Enable order of a catalog's taps (dependency levels + readiness).
        // A tap read; each member is scope-checked in the controller.
        Route("GET", "/api/v1/catalog/*/enable-order", "tap", "read"),

        // Taps
        Route("GET", "/api/v1/tap", "tap", "read"),
        Route("GET", "/api/v1/taps", "tap", "read"),
        Route("POST", "/api/v1/tap", "tap", "create"),
        Route("DELETE", "/api/v1/tap", "tap", "delete"),
        Route("POST", "/api/v1/tap/run", "tap", "run"),
        Route("POST", "/api/v1/tap/cron", "tap", "update"),
        Route("POST", "/api/v1/tap/script", "tap", "update"),
        Route("POST", "/api/v1/tap/test", "tap", "run"),
        Route("POST", "/api/v1/tap/generate", "tap", "create"),
        Route("GET", "/api/v1/tap/generate/status", "tap", "read"),
        Route("POST", "/api/v1/tap/fix", "tap", "update"),
        Route("POST", "/api/v1/tap/review", "tap", "read"),
        Route("POST", "/api/v1/tap/optimize", "tap", "update"),
        Route("POST", "/api/v1/tap/brainstorm", "tap", "read"),
        Route("GET", "/api/v1/tap/ledger", "tap", "read"),
        Route("DELETE", "/api/v1/tap/ledger", "tap", "update"),
        // Tap incremental sync state (read = read; set/reset mutate the
        // cursor a scheduled run resumes from, so they gate like an update)
        Route("GET", "/api/v1/tap/state", "tap", "read"),
        Route("POST", "/api/v1/tap/state", "tap", "update"),
        Route("DELETE", "/api/v1/tap/state", "tap", "update"),
        Route("GET", "/api/v1/tap/logs", "job", "read"),
        Route("GET", "/api/v1/tap/logs/all", "job", "read"),
        // Tap definition versions (read/diff = read; restore = update)
        Route("GET", "/api/v1/tap/versions", "tap", "read"),
        Route("GET", "/api/v1/tap/version", "tap", "read"),
        Route("GET", "/api/v1/tap/version/diff", "tap", "read"),
        Route("POST", "/api/v1/tap/version/restore", "tap", "update"),
        // Tap script storage (code repository)
        Route("POST", "/api/v1/tap/script/pull", "tap", "update"),
        Route("POST", "/api/v1/tap/migrate-storage", "tap", "update"),

        // Code repository connection — its own resource so tap-editing keys
        // can't rewire where scripts are committed
        Route("GET", "/api/v1/code-repo", "code-repo", "read"),
        Route("PUT", "/api/v1/code-repo", "code-repo", "write"),
        Route("POST", "/api/v1/code-repo/test", "code-repo", "read"),

        // Tap prompts (curated tap-building hints)
        Route("GET", "/api/v1/tap-prompts", "tap", "read"),
        Route("GET", "/api/v1/tap-prompts/**", "tap", "read"),
        Route("POST", "/api/v1/tap-prompts", "tap", "update"),
        Route("POST", "/api/v1/tap-prompts/suggest", "tap", "read"),
        Route("DELETE", "/api/v1/tap-prompts/**", "tap", "update"),

        // Secrets
        Route("GET", "/api/v1/secrets", "secret", "read"),
        Route("GET", "/api/v1/secrets/**", "secret", "read"),
        Route("PUT", "/api/v1/secrets/**", "secret", "write"),
        Route("DELETE", "/api/v1/secrets/**", "secret", "write"),

        // Query / job
        Route("POST", "/api/v1/query/postgres", "query", "postgres"),
        Route("POST", "/api/v1/query/mongodb", "query", "mongodb"),
        Route("POST", "/api/v1/query/objectstore", "query", "objectstore"),
        Route("POST", "/api/v1/query/snowflake", "query", "snowflake"),
        Route("POST", "/api/v1/query/databricks", "query", "databricks"),
        Route("POST", "/api/v1/query/sqlserver", "query", "sqlserver"),
        Route("POST", "/api/v1/query/natural", "query", "natural"),
        Route("POST", "/api/v1/ai/answer", "query", "natural"),
        Route("POST", "/api/v1/job/kill", "job", "kill"),

        // Vector search (one capability across all stores; the scope identifies the collection)
        Route("POST", "/api/v1/search/**", "search", "vector"),

        // Metadata. The raw table/collection listings under /metadata/** cannot
        // be narrowed to a catalog, so restricted sessions are refused them
        // even though their bundle holds metadata:read (UnfilterableForRestricted),
        // and so are scope-limited API keys (InstallWideForLimited).
        Route("GET", "/api/v1/metadata/**", "metadata", "read"),
        Route("GET", "/api/v1/vector-stores/available", "metadata", "read"),
        Route("POST", "/api/v1/config/generate-schema", "metadata", "read"),

        // Config
        Route("POST", "/api/v1/config/upload", "config", "write"),
        Route("GET", "/api/v1/ai/model-catalog", "config", "read"),
        // Live model discovery for a provider (Configuration tab).
        Route("GET", "/api/v1/ai/models", "config", "read"),
        // Doctor — operational self-check. Not public: the report names env
        // keys, Vault secret paths and model ids, so it rides config:read.
        Route("GET", "/api/v1/doctor", "config", "read"),

        // Audit log — admin surface. Full-access keys hold it implicitly; a
        // scoped key needs `audit:read` explicitly (see the Keys catalog).
        Route("GET", "/api/v1/audit-log", "audit", "read"),
        Route("GET", "/api/v1/audit-log/**", "audit", "read"),

        // Agent policy — readable by anyone (agents consult it before acting);
        // writable only by an admin, and never by an agent (PolicyInterceptor
        // hard rule, independent of capability).
        Route("GET", "/api/v1/policy", "policy", "read"),
        Route("PUT", "/api/v1/policy", "policy", "update"),

        // Approvals — agents list/poll their own; people decide.
        Route("GET", "/api/v1/approvals", "approval", "read"),
        Route("GET", "/api/v1/approvals/**", "approval", "read"),
        Route("POST", "/api/v1/approvals/*/approve", "approval", "decide"),
        Route("POST", "/api/v1/approvals/*/reject", "approval", "decide"),

        // Recovery-agent incidents — read for everyone the key allows;
        // abandoning one, or proceeding past a needs-human scratch verdict, is
        // a person's call (role-gated on top; agents get 403).
        Route("GET", "/api/v1/incidents", "incident", "read"),
        Route("GET", "/api/v1/incidents/**", "incident", "read"),
        Route("POST", "/api/v1/incidents/*/abandon", "incident", "abandon"),
        Route("POST", "/api/v1/incidents/*/proceed", "incident", "proceed"),

        // Server-side Activity signals (failures / stale / volume) — derived
        // operational metadata.
        Route("GET", "/api/v1/activity/signals", "metadata", "read"),

        // Provenance / lineage / discovery (v1.26) — read-only derived
        // metadata. `/catalog/find` additionally filters hits per item with
        // the caller's pipeline/tap read scopes; `howToQuery` execution is
        // still gated at the query call itself.
        Route("GET", "/api/v1/provenance", "metadata", "read"),
        Route("GET", "/api/v1/lineage", "metadata", "read"),
        Route("GET", "/api/v1/lineage/**", "metadata", "read"),
        Route("GET", "/api/v1/catalog/find", "metadata", "read"),
        // Unity Catalog browse — read-only warehouse metadata. The controller
        // additionally requires that the caller could read the named secret
        // (the list_platform_secrets predicate); an unreadable secret answers
        // 404 like an unknown one. Refused to restricted sessions
        // (UnfilterableForRestricted): warehouse listings have no catalog.
        // Lineage, provenance, /catalog/find and /activity/signals above stay
        // open to them and filter their results to the granted catalogs.
        Route("GET", "/api/v1/unity-catalog/browse", "metadata", "read")
    )

    /** Routes a restricted user's session is refused even though its bundle
      * holds the capability the route requires: raw table/collection and
      * warehouse listings cannot be narrowed to the granted catalogs.
      * Unrestricted sessions are unaffected; a limited API key is refused
      * them (and the raw queries) through [[InstallWideForLimited]]. */
    val UnfilterableForRestricted: Seq[(String, String)] = Seq(
        ("GET", "/api/v1/metadata/**"),
        ("GET", "/api/v1/unity-catalog/browse")
    )

    /** True when `method path` is one of [[UnfilterableForRestricted]]. */
    def refusedForRestrictedSession(method: String, path: String): Boolean =
        method != null && path != null &&
            UnfilterableForRestricted.exists { case (m, p) => m.equalsIgnoreCase(RequestPaths.method(method)) && matcher.`match`(p, path) }

    /** Install-wide routes with nothing to filter, refused to every caller
      * limited to a catalog or to what it created ([[CallerVisibility.limited]]:
      * a restricted session, or an API key carrying `catalog=` / `owner=` on a
      * pipeline or tap capability without unscoped read), even when the
      * capability was granted explicitly (plans/stories/scoped-key-read-routes.md,
      * Part C, Decision D3): the listings in [[UnfilterableForRestricted]] and
      * the raw Postgres, MongoDB and natural-language queries, which can read
      * any table or collection the connection reaches. */
    val InstallWideForLimited: Seq[(String, String)] = UnfilterableForRestricted ++ Seq(
        ("POST", "/api/v1/query/postgres"),
        ("POST", "/api/v1/query/mongodb"),
        ("POST", "/api/v1/query/natural")
    )

    /** True when `method path` is one of [[InstallWideForLimited]]. */
    def refusedForLimited(method: String, path: String): Boolean =
        method != null && path != null &&
            InstallWideForLimited.exists { case (m, p) => m.equalsIgnoreCase(RequestPaths.method(method)) && matcher.`match`(p, path) }

    /** Why a caller is refused a route whose capability it may hold. */
    sealed trait ScopeRefusal

    /** A restricted session on a table or collection listing
      * ([[refusedForRestrictedSession]]): refused whether or not it holds
      * the capability, with the listing message. */
    case object RestrictedListing extends ScopeRefusal

    /** A limited caller ([[CallerVisibility.limited]]) on an
      * [[InstallWideForLimited]] route: refused when it holds the route
      * capability (a caller that does not hold it keeps the does-not-hold
      * or inert-scope answer). */
    case object InstallWide extends ScopeRefusal

    /** The one decision the capability gate (`CapabilityInterceptor`) and the
      * MCP tool list ([[MCPToolRoutes.allowedTools]]) both apply, so a tool is
      * hidden exactly when the gate refuses its route. None for a null
      * identity, a route outside both lists, and every caller that is not
      * limited (nothing is computed for them beyond the route match). */
    def scopeRefusal(rk: ResolvedKey, method: String, path: String): Option[ScopeRefusal] =
        if (rk == null) None
        else if (rk.restrictedSession && refusedForRestrictedSession(method, path)) Some(RestrictedListing)
        else if (refusedForLimited(method, path) && CallerVisibility.limited(rk)) Some(InstallWide)
        else None

    /** Every `resource:action` pair the table grants — the vocabulary the
      * agent policy is allowed to use. */
    lazy val allActionKeys: Set[String] = routes.map(r => r.resource + ":" + r.action).toSet

    /** The capability `method path` requires. `path` is the matched path
      * ([[RequestPaths.path]]); `method` is decided as [[RequestPaths.method]]
      * (HEAD as GET). OPTIONS is [[RouteCheck.Skip]]: the dispatcher answers
      * it (CORS preflight, or the `Allow` header) without running a
      * controller method, and no controller maps it (HandlerMappingDecisionSpec). */
    def lookup(method: String, path: String): RouteCheck = {
        if (path == null) return RouteCheck.Unmapped
        if (skipPatterns.exists(p => matcher.`match`(p, path))) return RouteCheck.Skip
        val m = RequestPaths.method(method)
        if (m == "OPTIONS") return RouteCheck.Skip

        routes.find(r => r.method.equalsIgnoreCase(m) && matcher.`match`(r.pattern, path)) match {
            case Some(r) => RouteCheck.Require(r.resource, r.action)
            case None => RouteCheck.Unmapped
        }
    }
}
