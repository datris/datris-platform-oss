package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.User
import ai.datris.util.APIKeyValidator
import org.scalatest.funsuite.AnyFunSuite

/** The Search tab offers Object Store, Databricks, Snowflake and SQL Server queries; the
  *  Editor/Viewer session bundles and the read-only / reporting key templates
  *  must carry the matching query capabilities so those options can run. */
class QueryCapabilityBundlesSpec extends AnyFunSuite {

    private val QueryCaps = Seq("query:postgres", "query:mongodb", "query:objectstore", "query:snowflake", "query:databricks", "query:sqlserver")

    private def sessionCaps(role: String): Seq[String] =
        APIKeyValidator.resolveFromSession(User("u", "h", role, null, null, null)).capabilities.map(_.raw)

    Seq(User.RoleEditor, User.RoleViewer).foreach { role =>
        test(s"$role session bundle grants every structured query capability") {
            val caps = sessionCaps(role)
            QueryCaps.foreach(c => assert(caps.contains(c), s"$role missing $c: $caps"))
        }
    }

    Seq("read-only", "reporting").foreach { name =>
        test(s"$name key template grants every structured query capability") {
            val caps = KeysAPIController.Templates.find(_._1 == name).map(_._3).getOrElse(fail(s"template $name not found"))
            QueryCaps.foreach(c => assert(caps.contains(c), s"$name missing $c: $caps"))
        }
    }

    // Story: SQL Server read-back and on-demand typing
    // (plans/stories/mssql-query-and-typing.md), Acceptance bullet 1 — the
    // `query` bundle in the capability catalog (what the Keys UI offers)
    // carries `sqlserver`, and POST /query/sqlserver is gated by it.
    test("the query bundle contains query:sqlserver") {
        val body = new KeysAPIController().capabilitiesCatalog().getBody
        val resources = com.google.gson.JsonParser.parseString(body).getAsJsonObject.getAsJsonArray("resources")
        val query = (0 until resources.size()).map(resources.get(_).getAsJsonObject)
            .find(_.get("resource").getAsString == "query").getOrElse(fail("no query resource in catalog: " + body))
        val actions = (0 until query.getAsJsonArray("actions").size()).map(query.getAsJsonArray("actions").get(_).getAsString)
        assert(actions.contains("sqlserver"), actions)
    }

    test("POST /api/v1/query/sqlserver requires query:sqlserver and query_sqlserver maps to it") {
        import ai.datris.auth.{CapabilityRoutes, MCPToolRoutes, RouteCheck}
        assert(CapabilityRoutes.lookup("POST", "/api/v1/query/sqlserver") == RouteCheck.Require("query", "sqlserver"))
        assert(MCPToolRoutes.tools.toMap.get("query_sqlserver") == Some(MCPToolRoutes.Mapped("POST", "/api/v1/query/sqlserver")))
    }

    test("viewer bundle stays read-only (no create/update/delete/run/kill)") {
        val caps = sessionCaps(User.RoleViewer)
        assert(!caps.exists(c => Seq(":create", ":update", ":delete", ":run", ":kill", ":write").exists(c.endsWith)), caps)
    }
}
