package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.Capability
import ai.datris.util.APIKeyValidator

/** Story: In-platform chats act with the signed-in user's role, not the
  * platform key's (plans/stories/chat-session-role-authority.md).
  *
  * The MCP tool sets each caller class sees, written out by name so a change
  * to a role bundle (`APIKeyValidator.roleToCapabilities`), to a route row
  * (`CapabilityRoutes`) or to the tool table (`MCPToolRoutes.tools`) shows up
  * as a diff here. Derived on 2026-11-19 from `MCPToolRoutes.tools` x
  * `CapabilityRoutes.lookup` / `scopeRefusal` x the role bundles at
  * a0d875c8; in `MCPToolRoutes.tools` order.
  *
  * The story's counts (90 / 76 / 56, 14 lost by an editor) still hold at
  * a0d875c8. */
object RoleToolSets {

    /** An unrestricted editor's role bundle: 76 tools. */
    val Editor: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "create_pipeline",
        "set_catalog",
        "rename_catalog",
        "delete_catalog",
        "export_catalog",
        "restore_catalog",
        "enable_catalog_ordered",
        "catalog_enable_order",
        "delete_pipeline",
        "upload_data",
        "get_job_status",
        "kill_job",
        "profile_data",
        "get_dest_types",
        "apply_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "run_doctor",
        "search_qdrant",
        "search_weaviate",
        "search_milvus",
        "search_pgvector",
        "search_chroma",
        "query_postgres",
        "query_objectstore",
        "query_snowflake",
        "query_databricks",
        "query_sqlserver",
        "query_mongodb",
        "query_natural",
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        "list_pgvector_collections",
        "ai_answer",
        "create_tap",
        "list_taps",
        "run_tap",
        "get_pipeline_status",
        "get_pipeline_result",
        "delete_tap",
        "get_tap",
        "get_tap_logs",
        "get_tap_ledger",
        "get_tap_state",
        "set_tap_state",
        "test_tap",
        "propose_pipeline_schema",
        "update_tap",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "restore_tap_version",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "restore_pipeline_version",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "restore_script_version",
        "wait_seconds",
        "find_data",
        "get_provenance",
        "get_lineage",
        "browse_unity_catalog"
    )

    /** The 14 tools an unrestricted editor's chat loses (the story's table). */
    val EditorLoses: Seq[String] = Seq(
        "upload_config",
        "update_secret",
        "list_tap_secrets",
        "get_tap_secret_fields",
        "list_platform_secrets",
        "get_platform_secret_fields",
        "create_tap_secret",
        "delete_tap_secret",
        "get_agent_policy",
        "list_pending_approvals",
        "get_approval",
        "list_incidents",
        "get_incident",
        "get_incident_evidence"
    )

    /** An unrestricted viewer's role bundle: 56 tools, all reads. */
    val Viewer: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "export_catalog",
        "catalog_enable_order",
        "get_job_status",
        "profile_data",
        "get_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "run_doctor",
        "search_qdrant",
        "search_weaviate",
        "search_milvus",
        "search_pgvector",
        "search_chroma",
        "query_postgres",
        "query_objectstore",
        "query_snowflake",
        "query_databricks",
        "query_sqlserver",
        "query_mongodb",
        "query_natural",
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        "list_pgvector_collections",
        "ai_answer",
        "list_taps",
        "get_pipeline_status",
        "get_pipeline_result",
        "get_tap",
        "get_tap_logs",
        "get_tap_ledger",
        "get_tap_state",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "wait_seconds",
        "find_data",
        "get_provenance",
        "get_lineage",
        "browse_unity_catalog"
    )

    /** A restricted editor granted `Sales -> edit` (unchanged by the story): 57 tools. */
    val RestrictedEditorSales: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "create_pipeline",
        "set_catalog",
        "rename_catalog",
        "export_catalog",
        "restore_catalog",
        "enable_catalog_ordered",
        "catalog_enable_order",
        "upload_data",
        "get_job_status",
        "kill_job",
        "profile_data",
        "get_dest_types",
        "apply_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "run_doctor",
        "search_qdrant",
        "search_weaviate",
        "search_milvus",
        "search_pgvector",
        "search_chroma",
        "query_objectstore",
        "query_snowflake",
        "query_databricks",
        "query_sqlserver",
        "create_tap",
        "list_taps",
        "run_tap",
        "get_pipeline_status",
        "get_pipeline_result",
        "get_tap",
        "get_tap_logs",
        "get_tap_ledger",
        "get_tap_state",
        "set_tap_state",
        "test_tap",
        "propose_pipeline_schema",
        "update_tap",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "restore_tap_version",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "restore_pipeline_version",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "restore_script_version",
        "wait_seconds",
        "find_data",
        "get_provenance",
        "get_lineage"
    )

    /** A restricted viewer granted `Sales -> view` (unchanged by the story): 40 tools. */
    val RestrictedViewerSales: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "export_catalog",
        "catalog_enable_order",
        "get_job_status",
        "profile_data",
        "get_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "run_doctor",
        "search_qdrant",
        "search_weaviate",
        "search_milvus",
        "search_pgvector",
        "search_chroma",
        "query_objectstore",
        "query_snowflake",
        "query_databricks",
        "query_sqlserver",
        "list_taps",
        "get_pipeline_status",
        "get_pipeline_result",
        "get_tap",
        "get_tap_logs",
        "get_tap_ledger",
        "get_tap_state",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "wait_seconds",
        "find_data",
        "get_provenance",
        "get_lineage"
    )

    /** Key `ci_scoped` = `pipeline:read:catalog=Sales`, `tap:read:catalog=Sales` (unchanged): 22 tools. */
    val CatalogSalesKey: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "export_catalog",
        "catalog_enable_order",
        "get_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "list_taps",
        "get_tap",
        "get_tap_ledger",
        "get_tap_state",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "wait_seconds"
    )

    /** Key `rag-builder` = `pipeline:create`, `pipeline:read:owner=self`, `tap:run:owner=self` (unchanged): 21 tools. */
    val OwnerSelfKey: Seq[String] = Seq(
        "list_pipelines",
        "get_pipeline",
        "create_pipeline",
        "set_catalog",
        "export_catalog",
        "restore_catalog",
        "enable_catalog_ordered",
        "get_dest_types",
        "suggest_field_protection",
        "get_version",
        "check_service_health",
        "run_tap",
        "test_tap",
        "propose_pipeline_schema",
        "list_pipeline_versions",
        "get_pipeline_version",
        "diff_pipeline_versions",
        "list_script_versions",
        "get_script_version",
        "diff_script_versions",
        "wait_seconds"
    )

    /** Key `inert-only` = `search:vector:collection=docs`,
      * `document:upload:destination_kind=postgres`, `job:read:owner=self`,
      * `metadata:read`, as key resolution partitions it (the last two
      * scoped capabilities are inert): 24 tools (unchanged). */
    val InertOnlyKey: Seq[String] = Seq(
        "profile_data",
        "get_version",
        "check_service_health",
        "search_qdrant",
        "search_weaviate",
        "search_milvus",
        "search_pgvector",
        "search_chroma",
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        "list_pgvector_collections",
        "wait_seconds",
        "find_data",
        "get_provenance",
        "get_lineage",
        "browse_unity_catalog"
    )

    val CatalogSalesCaps: Seq[String] = Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
    val OwnerSelfCaps: Seq[String] = Seq("pipeline:create", "pipeline:read:owner=self", "tap:run:owner=self")
    val InertOnlyCaps: Seq[String] =
        Seq("search:vector:collection=docs", "document:upload:destination_kind=postgres", "job:read:owner=self", "metadata:read")

    /** A stored key as `APIKeyValidator.resolveAgainst` builds it. */
    def stored(label: String, raw: String*): ai.datris.model.ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ai.datris.model.ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    /** Actions that only read. `query:<engine>` and `search:vector` read by
      * their resource; every other granted action (create, update, delete,
      * run, kill, upload, write, decide, admin, reveal, ...) changes
      * something or decides something. */
    private val ReadResources: Set[String] = Set("query", "search")

    /** True when the tool, classified from its route's method and the
      * capability that route requires (not from its name), writes, runs,
      * deletes, uploads or decides. A `Local` tool calls no capability-checked
      * route. A route the table does not classify counts as mutating. */
    def mutating(tool: String): Boolean =
        MCPToolRoutes.tools.toMap.get(tool) match {
            case Some(MCPToolRoutes.Local) => false
            case Some(MCPToolRoutes.Mapped(method, path)) =>
                CapabilityRoutes.lookup(method, path) match {
                    case RouteCheck.Require(resource, action) => !(action == "read" || ReadResources.contains(resource))
                    case RouteCheck.Skip => method != "GET"
                    case RouteCheck.Unmapped => true
                }
            case None => true
        }

    /** The bundle a role grants, as raw strings. */
    def bundle(role: String): Seq[String] = APIKeyValidator.roleToCapabilities(role).map(_.raw)
}
