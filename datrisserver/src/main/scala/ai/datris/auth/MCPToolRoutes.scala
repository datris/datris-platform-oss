package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ResolvedKey
import org.slf4j.LoggerFactory

/** Maps every MCP tool the Python MCP server advertises to the primary REST
  * route that tool calls, so the server can answer "which tools may this key
  * see" from the same route→capability table [[CapabilityRoutes]] already
  * enforces. The MCP server's `list_tools` fetches the allowed names from
  * `GET /api/v1/mcp/tools` and returns that subset of its catalog — it never
  * owns a copy of this mapping, so the two cannot drift.
  *
  * Rules:
  *   - Visibility is decided on `resource:action` only. Scope predicates
  *     (`owner=self`, `_type=tap`) need the loaded resource and stay
  *     call-time enforced by the controllers — same split as the
  *     pre-action gate in CapabilityInterceptor.
  *   - Multi-call tools are classified by their PRIMARY route. Secondary
  *     calls with other capabilities may still 403 at call time; that
  *     backstop is unchanged.
  *   - A tool added to server.py WITHOUT a row here is hidden from every
  *     scoped key (fail-closed) until someone classifies it. Legacy `*:*`
  *     keys and keys-off installs always get the full catalog, so a
  *     missing row can never break an unscoped install.
  */
object MCPToolRoutes {

    private val logger = LoggerFactory.getLogger(getClass)

    /** How a tool relates to the REST capability surface. */
    sealed trait ToolRoute

    /** No capability-checked REST call: a pure local helper (wait_seconds)
      * or public Skip-class infrastructure (/version, /health). Always
      * visible to every session. */
    case object Local extends ToolRoute

    /** The tool's primary REST call. Visible iff the key would pass the
      * interceptor's grant test for this method+path. */
    case class Mapped(method: String, path: String) extends ToolRoute

    /** One row per tool in mcp-server/server.py's catalog, same order.
      * Paths are concrete representatives — CapabilityRoutes matches them
      * with AntPathMatcher patterns (e.g. `/api/v1/secrets/example` hits
      * the double-wildcard secrets row). */
    val tools: Seq[(String, ToolRoute)] = Seq(
        // Pipeline management
        "list_pipelines" -> Mapped("GET", "/api/v1/pipelines"),
        "get_pipeline" -> Mapped("GET", "/api/v1/pipeline"),
        "create_pipeline" -> Mapped("POST", "/api/v1/pipeline"),
        "set_catalog" -> Mapped("POST", "/api/v1/pipeline"),
        "delete_pipeline" -> Mapped("DELETE", "/api/v1/pipeline"),
        "upload_data" -> Mapped("POST", "/api/v1/pipeline/upload"),
        "get_job_status" -> Mapped("GET", "/api/v1/pipeline/status"),
        "kill_job" -> Mapped("POST", "/api/v1/job/kill"),
        "profile_data" -> Mapped("POST", "/api/v1/pipeline/profile"),

        // Infrastructure
        "get_version" -> Local,
        "check_service_health" -> Local,

        // Vector search
        "search_qdrant" -> Mapped("POST", "/api/v1/search/qdrant"),
        "search_weaviate" -> Mapped("POST", "/api/v1/search/weaviate"),
        "search_milvus" -> Mapped("POST", "/api/v1/search/milvus"),
        "search_pgvector" -> Mapped("POST", "/api/v1/search/pgvector"),
        "search_chroma" -> Mapped("POST", "/api/v1/search/chroma"),

        // Query
        "query_postgres" -> Mapped("POST", "/api/v1/query/postgres"),
        "query_objectstore" -> Mapped("POST", "/api/v1/query/objectstore"),
        "query_snowflake" -> Mapped("POST", "/api/v1/query/snowflake"),
        "query_databricks" -> Mapped("POST", "/api/v1/query/databricks"),
        "query_mongodb" -> Mapped("POST", "/api/v1/query/mongodb"),
        "query_natural" -> Mapped("POST", "/api/v1/query/natural"),

        // Metadata
        "list_postgres_databases" -> Mapped("GET", "/api/v1/metadata/postgres/databases"),
        "list_postgres_schemas" -> Mapped("GET", "/api/v1/metadata/postgres/schemas"),
        "list_postgres_tables" -> Mapped("GET", "/api/v1/metadata/postgres/tables"),
        "list_postgres_columns" -> Mapped("GET", "/api/v1/metadata/postgres/columns"),
        "list_mongodb_databases" -> Mapped("GET", "/api/v1/metadata/mongodb/databases"),
        "list_mongodb_collections" -> Mapped("GET", "/api/v1/metadata/mongodb/collections"),
        "list_qdrant_collections" -> Mapped("GET", "/api/v1/metadata/qdrant/collections"),
        "list_weaviate_classes" -> Mapped("GET", "/api/v1/metadata/weaviate/classes"),
        "list_milvus_collections" -> Mapped("GET", "/api/v1/metadata/milvus/collections"),
        "list_chroma_collections" -> Mapped("GET", "/api/v1/metadata/chroma/collections"),
        "list_pgvector_collections" -> Mapped("GET", "/api/v1/metadata/postgres/tables"),

        // AI answer + config
        "ai_answer" -> Mapped("POST", "/api/v1/ai/answer"),
        "upload_config" -> Mapped("POST", "/api/v1/config/upload"),

        // Secrets (single-secret paths hit the /api/v1/secrets/** rows)
        "update_secret" -> Mapped("PUT", "/api/v1/secrets/example"),
        "list_tap_secrets" -> Mapped("GET", "/api/v1/secrets"),
        "get_tap_secret_fields" -> Mapped("GET", "/api/v1/secrets/example"),
        "list_platform_secrets" -> Mapped("GET", "/api/v1/secrets"),
        "get_platform_secret_fields" -> Mapped("GET", "/api/v1/secrets/example"),
        "create_tap_secret" -> Mapped("PUT", "/api/v1/secrets/example"),
        "delete_tap_secret" -> Mapped("DELETE", "/api/v1/secrets/example"),

        // Taps
        "create_tap" -> Mapped("POST", "/api/v1/tap"),
        "list_taps" -> Mapped("GET", "/api/v1/taps"),
        "run_tap" -> Mapped("POST", "/api/v1/tap/run"),
        "get_pipeline_status" -> Mapped("GET", "/api/v1/pipeline/status"),
        "delete_tap" -> Mapped("DELETE", "/api/v1/tap"),
        "get_tap" -> Mapped("GET", "/api/v1/tap"),
        "get_tap_logs" -> Mapped("GET", "/api/v1/tap/logs"),
        "get_tap_ledger" -> Mapped("GET", "/api/v1/tap/ledger"),
        "get_tap_state" -> Mapped("GET", "/api/v1/tap/state"),
        "set_tap_state" -> Mapped("POST", "/api/v1/tap/state"),
        "test_tap" -> Mapped("POST", "/api/v1/tap/run"),
        "update_tap" -> Mapped("POST", "/api/v1/tap"),

        // Definition versions
        "list_tap_versions" -> Mapped("GET", "/api/v1/tap/versions"),
        "get_tap_version" -> Mapped("GET", "/api/v1/tap/version"),
        "diff_tap_versions" -> Mapped("GET", "/api/v1/tap/version/diff"),
        "restore_tap_version" -> Mapped("POST", "/api/v1/tap/version/restore"),
        "list_pipeline_versions" -> Mapped("GET", "/api/v1/pipeline/versions"),
        "get_pipeline_version" -> Mapped("GET", "/api/v1/pipeline/version"),
        "diff_pipeline_versions" -> Mapped("GET", "/api/v1/pipeline/version/diff"),
        "restore_pipeline_version" -> Mapped("POST", "/api/v1/pipeline/version/restore"),

        // Agent workflow helper — no REST call at all
        "wait_seconds" -> Local
    )

    val allToolNames: Seq[String] = tools.map(_._1)

    /** Tool names this key may see. Legacy `*:*` keys (which includes the
      * anonymous identity in keys-off mode) get the full catalog. */
    def allowedTools(key: ResolvedKey): Seq[String] = {
        if (key.isLegacyFullAccess) return allToolNames
        tools.collect {
            case (name, Local) => name
            case (name, Mapped(method, path)) if mappedAllowed(name, method, path, key) => name
        }
    }

    private def mappedAllowed(name: String, method: String, path: String, key: ResolvedKey): Boolean =
        CapabilityRoutes.lookup(method, path) match {
            case RouteCheck.Require(resource, action) => key.matchesResourceAction(resource, action)
            case RouteCheck.Skip => true
            case RouteCheck.Unmapped =>
                // A Mapped row pointing at a route CapabilityRoutes doesn't
                // know means the two tables drifted. Fail closed for scoped
                // keys and say so in the log; MCPToolRoutesSpec also fails
                // on this so drift is caught before it ships.
                logger.warn(
                    "MCP tool '{}' maps to unmapped route {} {} — hidden from scoped keys",
                    Array[AnyRef](name, method, path): _*
                )
                false
        }
}
