package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey}
import org.scalatest.funsuite.AnyFunSuite

class MCPToolRoutesSpec extends AnyFunSuite {

    private def key(caps: String*): ResolvedKey =
        ResolvedKey(None, "test-key", Capability.parseList(caps), isLegacyFullAccess = false)

    private val legacyKey =
        ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    // Mirrors the rag-builder template in KeysAPIController — if the template
    // changes, this test states what the MCP catalog consequences are.
    private val ragBuilder = key(
        "pipeline:create",
        "pipeline:read",
        "pipeline:run:owner=self",
        "tap:create",
        "tap:read",
        "tap:run:owner=self",
        "document:upload",
        "search:vector",
        "secret:read:_type=tap",
        "secret:write:_type=tap",
        "job:read"
    )

    test("catalog has one row per MCP tool, no duplicates") {
        assert(MCPToolRoutes.allToolNames.size == 79)
        assert(MCPToolRoutes.allToolNames.distinct.size == MCPToolRoutes.allToolNames.size)
    }

    test("discovery + provenance routes are capability-mapped as metadata reads") {
        assert(CapabilityRoutes.lookup("GET", "/api/v1/provenance") == RouteCheck.Require("metadata", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/lineage") == RouteCheck.Require("metadata", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/lineage/pipeline/example") == RouteCheck.Require("metadata", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/catalog/find") == RouteCheck.Require("metadata", "read"))
        // Story: Unity Catalog 2: discovery (plans/stories/unity-catalog-2-discovery.md).
        assert(CapabilityRoutes.lookup("GET", "/api/v1/unity-catalog/browse") == RouteCheck.Require("metadata", "read"))
    }

    // Story: Unity Catalog 2: discovery. browse_unity_catalog is a metadata read,
    // mapped like find_data, and hidden from keys without metadata:read (fail-closed).
    test("browse_unity_catalog is a metadata:read tool mapped to GET /api/v1/unity-catalog/browse") {
        assert(MCPToolRoutes.tools.toMap.get("browse_unity_catalog") == Some(MCPToolRoutes.Mapped("GET", "/api/v1/unity-catalog/browse")))
        assert(MCPToolRoutes.allowedTools(key("metadata:read")).contains("browse_unity_catalog"))
        assert(!MCPToolRoutes.allowedTools(key("secret:read")).contains("browse_unity_catalog"))
        assert(!MCPToolRoutes.allowedTools(key("pipeline:read")).contains("browse_unity_catalog"))
        assert(MCPToolRoutes.allowedTools(legacyKey).contains("browse_unity_catalog"))
    }

    test("doctor route is capability-mapped as config:read, not public") {
        assert(CapabilityRoutes.lookup("GET", "/api/v1/doctor") == RouteCheck.Require("config", "read"))
        assert(MCPToolRoutes.allowedTools(key("config:read")).contains("run_doctor"))
        assert(!MCPToolRoutes.allowedTools(ragBuilder).contains("run_doctor"))
    }

    // Story: Scratch results over MCP and the CLI (plans/stories/scratch-mcp-cli-prompts.md).
    // get_pipeline_result is a job read, classified exactly like get_pipeline_status.
    // Story: Field protection 3 (plans/stories/field-protection-3-classifier.md).
    test("suggest_field_protection is a pipeline:read tool mapped to POST /api/v1/pipeline/protect/suggest") {
        assert(
            MCPToolRoutes.tools.toMap.get("suggest_field_protection") == Some(MCPToolRoutes.Mapped("POST", "/api/v1/pipeline/protect/suggest"))
        )
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/protect/suggest") == RouteCheck.Require("pipeline", "read"))
        assert(MCPToolRoutes.allowedTools(key("pipeline:read")).contains("suggest_field_protection"))
        assert(!MCPToolRoutes.allowedTools(key("job:read")).contains("suggest_field_protection"))
    }

    test("get_pipeline_result is a job:read tool mapped to GET /api/v1/pipeline/result") {
        assert(MCPToolRoutes.tools.toMap.get("get_pipeline_result") == Some(MCPToolRoutes.Mapped("GET", "/api/v1/pipeline/result")))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/pipeline/result") == RouteCheck.Require("job", "read"))
        assert(MCPToolRoutes.allowedTools(key("job:read")).contains("get_pipeline_result"))
        assert(MCPToolRoutes.allowedTools(ragBuilder).contains("get_pipeline_result"))
        assert(!MCPToolRoutes.allowedTools(key("nonexistent:nothing")).contains("get_pipeline_result"))
        assert(!MCPToolRoutes.allowedTools(key("pipeline:read")).contains("get_pipeline_result"))
    }

    // Story: Catalog rename and delete (plans/stories/catalog-ops-server-mcp.md).
    test("rename_catalog and delete_catalog are mapped to the catalog PUT/DELETE routes, not Unmapped") {
        val rows = MCPToolRoutes.tools.toMap
        assert(rows.get("rename_catalog") == Some(MCPToolRoutes.Mapped("PUT", "/api/v1/catalog/example")))
        assert(rows.get("delete_catalog") == Some(MCPToolRoutes.Mapped("DELETE", "/api/v1/catalog/example")))
        assert(CapabilityRoutes.lookup("PUT", "/api/v1/catalog/example") == RouteCheck.Require("pipeline", "update"))
        assert(CapabilityRoutes.lookup("DELETE", "/api/v1/catalog/example") == RouteCheck.Require("pipeline", "delete"))
        // The existing discovery read is untouched.
        assert(CapabilityRoutes.lookup("GET", "/api/v1/catalog/find") == RouteCheck.Require("metadata", "read"))
    }

    test("catalog tools are visible only with the mapped capability (fail-closed)") {
        assert(MCPToolRoutes.allowedTools(key("pipeline:update")).contains("rename_catalog"))
        assert(!MCPToolRoutes.allowedTools(key("pipeline:update")).contains("delete_catalog"))
        assert(MCPToolRoutes.allowedTools(key("pipeline:delete")).contains("delete_catalog"))
        assert(!MCPToolRoutes.allowedTools(key("pipeline:read")).contains("rename_catalog"))
        assert(!MCPToolRoutes.allowedTools(ragBuilder).contains("rename_catalog"))
        assert(!MCPToolRoutes.allowedTools(ragBuilder).contains("delete_catalog"))
        assert(Set("rename_catalog", "delete_catalog").subsetOf(MCPToolRoutes.allowedTools(legacyKey).toSet))
    }

    test("drift guard: every Mapped row resolves in CapabilityRoutes") {
        val unmapped = MCPToolRoutes.tools.collect {
            case (name, MCPToolRoutes.Mapped(method, path))
                if CapabilityRoutes.lookup(method, path) == RouteCheck.Unmapped =>
                s"$name -> $method $path"
        }
        assert(unmapped.isEmpty, s"tools mapped to routes CapabilityRoutes does not know: $unmapped")
    }

    test("tap sync state routes are capability-mapped (was a live unmapped hole)") {
        assert(CapabilityRoutes.lookup("GET", "/api/v1/tap/state") == RouteCheck.Require("tap", "read"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/tap/state") == RouteCheck.Require("tap", "update"))
        assert(CapabilityRoutes.lookup("DELETE", "/api/v1/tap/state") == RouteCheck.Require("tap", "update"))
    }

    test("the mcp/tools endpoint itself is skip-class") {
        assert(CapabilityRoutes.lookup("GET", "/api/v1/mcp/tools") == RouteCheck.Skip)
    }

    test("legacy full-access key sees the entire catalog") {
        assert(MCPToolRoutes.allowedTools(legacyKey) == MCPToolRoutes.allToolNames)
    }

    test("rag-builder sees its workflow and never the destructive tools") {
        val allowed = MCPToolRoutes.allowedTools(ragBuilder).toSet

        // The canonical workflow: list secrets → create tap secret →
        // create tap → test → run → poll.
        val expected = Seq(
            "list_pipelines",
            "get_pipeline",
            "create_pipeline",
            "upload_data",
            "list_taps",
            "get_tap",
            "create_tap",
            "update_tap",
            "run_tap",
            "test_tap",
            "get_tap_ledger",
            "get_tap_state",
            "get_tap_logs",
            "list_tap_secrets",
            "get_tap_secret_fields",
            "create_tap_secret",
            "delete_tap_secret",
            "update_secret",
            "search_qdrant",
            "search_pgvector",
            "get_pipeline_status",
            "get_pipeline_result",
            "get_job_status",
            "wait_seconds",
            "get_version",
            "check_service_health"
        )
        val missing = expected.filterNot(allowed.contains)
        assert(missing.isEmpty, s"rag-builder should see: $missing")

        // Invisible: no delete/update/kill grants, no query/metadata/config.
        val forbidden = Seq(
            "delete_pipeline",
            "delete_tap",
            "kill_job",
            "set_tap_state",
            "restore_tap_version",
            "restore_pipeline_version",
            "query_postgres",
            "query_mongodb",
            "query_natural",
            "ai_answer",
            "list_postgres_databases",
            "list_mongodb_databases",
            "profile_data",
            "upload_config"
        )
        val leaked = forbidden.filter(allowed.contains)
        assert(leaked.isEmpty, s"rag-builder must not see: $leaked")
    }

    test("scope suffixes do not hide tools — scoped grants match on resource:action") {
        // tap:run:owner=self must still surface run_tap; owner is call-time.
        assert(MCPToolRoutes.allowedTools(key("tap:run:owner=self")).contains("run_tap"))
        assert(MCPToolRoutes.allowedTools(key("secret:write:_type=tap")).contains("update_secret"))
    }

    test("read-only-shaped key gets reads plus local tools, nothing mutating") {
        val readOnly = key(
            "pipeline:read",
            "tap:read",
            "job:read",
            "metadata:read",
            "config:read",
            "query:postgres",
            "query:mongodb",
            "search:vector"
        )
        val allowed = MCPToolRoutes.allowedTools(readOnly).toSet
        assert(allowed.contains("list_pipelines"))
        assert(allowed.contains("query_postgres"))
        assert(allowed.contains("list_postgres_databases"))
        assert(allowed.contains("wait_seconds"))
        // No secret capabilities at all → every secret tool hidden.
        assert(!allowed.exists(_.contains("secret")))
        Seq("create_pipeline", "delete_pipeline", "run_tap", "kill_job", "set_tap_state", "upload_config")
            .foreach(t => assert(!allowed.contains(t), s"read-only must not see $t"))
    }

    test("a key with no capabilities still sees the local tools") {
        val none = key("nonexistent:nothing")
        assert(MCPToolRoutes.allowedTools(none) == Seq("get_version", "check_service_health", "wait_seconds"))
    }

    // Story: Field protection 5 (plans/stories/field-protection-5-encrypt-reveal.md).
    // Calls CapabilityRoutes.lookup, MCPToolRoutes.tools / allowedTools only.
    test("POST /api/v1/protect/reveal requires protect:reveal") {
        assert(CapabilityRoutes.lookup("POST", "/api/v1/protect/reveal") == RouteCheck.Require("protect", "reveal"))
        // Reveal is REST only: no MCP tool maps to it, so no agent key ever sees one.
        assert(!MCPToolRoutes.tools.exists { case (_, m) => m == MCPToolRoutes.Mapped("POST", "/api/v1/protect/reveal") })
        assert(!MCPToolRoutes.allToolNames.exists(_.toLowerCase.contains("reveal")))
        assert(!ragBuilder.matchesResourceAction("protect", "reveal"), "the rag-builder template does not carry reveal")
        assert(key("protect:reveal").matchesResourceAction("protect", "reveal"))
    }

    test("POST /api/v1/protect/keys/rotate requires protect:admin") {
        assert(CapabilityRoutes.lookup("POST", "/api/v1/protect/keys/rotate") == RouteCheck.Require("protect", "admin"))
        assert(!MCPToolRoutes.tools.exists { case (_, m) => m == MCPToolRoutes.Mapped("POST", "/api/v1/protect/keys/rotate") })
        // reveal does not imply admin, and the reverse.
        assert(!key("protect:reveal").matchesResourceAction("protect", "admin"))
        assert(!key("protect:admin").matchesResourceAction("protect", "reveal"))
    }
}
