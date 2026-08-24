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
        assert(MCPToolRoutes.allToolNames.size == 63)
        assert(MCPToolRoutes.allToolNames.distinct.size == MCPToolRoutes.allToolNames.size)
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
            "list_pipelines", "get_pipeline", "create_pipeline", "upload_data",
            "list_taps", "get_tap", "create_tap", "update_tap", "run_tap", "test_tap",
            "get_tap_ledger", "get_tap_state", "get_tap_logs",
            "list_tap_secrets", "get_tap_secret_fields", "create_tap_secret",
            "delete_tap_secret", "update_secret",
            "search_qdrant", "search_pgvector",
            "get_pipeline_status", "get_job_status",
            "wait_seconds", "get_version", "check_service_health"
        )
        val missing = expected.filterNot(allowed.contains)
        assert(missing.isEmpty, s"rag-builder should see: $missing")

        // Invisible: no delete/update/kill grants, no query/metadata/config.
        val forbidden = Seq(
            "delete_pipeline", "delete_tap", "kill_job",
            "set_tap_state", "restore_tap_version", "restore_pipeline_version",
            "query_postgres", "query_mongodb", "query_natural", "ai_answer",
            "list_postgres_databases", "list_mongodb_databases", "profile_data",
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
            "pipeline:read", "tap:read", "job:read", "metadata:read",
            "config:read", "query:postgres", "query:mongodb", "search:vector"
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
}
