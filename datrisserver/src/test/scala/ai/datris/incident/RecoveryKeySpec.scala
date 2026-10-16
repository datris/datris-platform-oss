package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, MCPToolRoutes, RouteCheck}
import ai.datris.model.{Capability, ResolvedKey}
import org.scalatest.funsuite.AnyFunSuite

/** Capability widening for an already-issued recovery-agent key. */
class RecoveryKeySpec extends AnyFunSuite {

    test("a key issued before config:read existed is missing exactly that") {
        val old = RecoveryKey.Capabilities.filterNot(_ == "config:read")
        val json = s"""{"capabilities":[${old.map("\"" + _ + "\"").mkString(",")}],"revoked":false,"keyId":"k_1"}"""
        assert(RecoveryKey.missingCapabilities(json) == Seq("config:read"))
    }

    test("a current key is missing nothing") {
        val json = s"""{"capabilities":[${RecoveryKey.Capabilities.map("\"" + _ + "\"").mkString(",")}],"revoked":false}"""
        assert(RecoveryKey.missingCapabilities(json).isEmpty)
    }

    test("a revoked key is never widened; malformed metadata is left alone") {
        assert(RecoveryKey.missingCapabilities("""{"capabilities":[],"revoked":true}""").isEmpty)
        assert(RecoveryKey.missingCapabilities("not json").isEmpty)
    }

    // Story: Schema-drift signal, credential and platform classifications,
    // wider diagnosis tools (plans/stories/self-healing-4-signals.md),
    // Acceptance bullet 4 — the recovery key already holds every capability
    // its tools need, so the wider DiagnosisTools grant nothing new.

    private val NewDiagnosisTools = Set("get_lineage", "find_data", "get_provenance", "list_pipeline_versions", "diff_pipeline_versions")

    test("every DiagnosisTools / ExecutableTools entry resolves to a capability the recovery key holds") {
        assert(NewDiagnosisTools.subsetOf(IncidentRunner.DiagnosisTools), IncidentRunner.DiagnosisTools)
        val key = ResolvedKey(None, RecoveryKey.Label, Capability.parseList(RecoveryKey.Capabilities), isLegacyFullAccess = false)
        val routes = MCPToolRoutes.tools.toMap
        val problems = (IncidentRunner.DiagnosisTools ++ IncidentRunner.ExecutableTools).toList.sorted.flatMap { tool =>
            routes.get(tool) match {
                case None => Some(tool + ": not in MCPToolRoutes.tools")
                case Some(MCPToolRoutes.Local) => None
                case Some(MCPToolRoutes.Mapped(method, path)) =>
                    CapabilityRoutes.lookup(method, path) match {
                        case RouteCheck.Skip => None
                        case RouteCheck.Unmapped => Some(tool + ": " + method + " " + path + " is unmapped in CapabilityRoutes")
                        case RouteCheck.Require(resource, action) =>
                            if (key.matchesResourceAction(resource, action)) None
                            else Some(tool + ": needs " + resource + ":" + action + ", not in RecoveryKey.Capabilities")
                    }
            }
        }
        assert(problems.isEmpty, problems.mkString("; "))
    }

    test("no lineage:read / provenance:read capability is added to the recovery key") {
        assert(!RecoveryKey.Capabilities.exists(c => c.startsWith("lineage:") || c.startsWith("provenance:")), RecoveryKey.Capabilities)
    }

    test("a key issued with the pre-story capability list is missing nothing (ensure() grants nothing on upgrade)") {
        // RecoveryKey.Capabilities as of the release before this story.
        val issued = Seq(
            "tap:read",
            "tap:run",
            "tap:update",
            "tap:create",
            "pipeline:read",
            "job:read",
            "metadata:read",
            "query:postgres",
            "query:mongodb",
            "query:snowflake",
            "query:databricks",
            "query:objectstore",
            "policy:read",
            "approval:read:owner=self",
            "config:read"
        )
        val json = s"""{"capabilities":[${issued.map("\"" + _ + "\"").mkString(",")}],"revoked":false,"keyId":"k_1"}"""
        assert(RecoveryKey.missingCapabilities(json).isEmpty, RecoveryKey.missingCapabilities(json))
    }
}
