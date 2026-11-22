package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityScopes, ScopeDenialFixtures, TapRunTokens}
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, DatrisEnvironment, PipelineConfig, ResolvedKey, User}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part C, Acceptance
  * `ScopedKeyInstallWideSpec` bullet 2: "a caller `sql` on a warehouse route
  * is 403 for a scope-limited key". In ai.datris.api (not config) because the
  * seam is private[api]; the rest of the bullet list is
  * ai.datris.config.ScopedKeyInstallWideSpec.
  *
  * Seam (exists at 9624d481; Part C changes who it refuses, Files: "api/QueryAPIController.scala `restrictedSqlRefusal`"):
  * {{{
  * object QueryAPIController {
  *   // snowflake / sqlserver / databricks, called with Option(body.get("sql")).map(_.toString)
  *   // after restrictedPipelineRefusal and before the warehouse is called
  *   def restrictedSqlRefusal(request: HttpServletRequest, sql: Option[String]): Option[ResponseEntity[String]]
  *   def restrictedPipelineRefusal(request: HttpServletRequest, pipelineName: String, read: String => PipelineConfig): Option[ResponseEntity[String]]
  * }
  * }}}
  * What the handlers send to the warehouse (read from QueryAPIController and
  * {Snowflake,SqlServer,Databricks}QueryUtil at 9624d481): only the body key
  * `sql` (case-sensitive java.util.Map; no `query` / `SQL` alias), as
  * `toString` of whatever JSON value it holds; the util runs it when
  * `sql.map(_.trim).filter(_.nonEmpty)` is defined, else the server-built
  * preview of the pipeline's table. So a caller SQL is exactly a non-blank
  * (after `String.trim`) `sql` value, and that is what must be refused.
  */
class ScopedKeyInstallWideSqlSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var savedProp: Option[String] = None
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedProp = sys.props.get(CapabilityScopes.InertScopesProperty)
        captured.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv(logReads = false))
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
        savedProp match {
            case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
            case None => sys.props -= CapabilityScopes.InertScopesProperty
        }
    }

    private def bothModes(body: String => Unit): Unit = {
        sys.props -= CapabilityScopes.InertScopesProperty
        if (sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty) body("refuse")
        sys.props(CapabilityScopes.InertScopesProperty) = "allow"
        body("allow")
    }

    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private val WarehouseCaps = Seq("query:snowflake", "query:sqlserver", "query:databricks")
    private val WarehouseRoutes = Seq("/api/v1/query/snowflake", "/api/v1/query/sqlserver", "/api/v1/query/databricks")

    private def limitedKeys: Seq[ResolvedKey] = Seq(
        stored("cat-sales", "pipeline:read:catalog=Sales" +: WarehouseCaps: _*),
        stored("own-self", Seq("pipeline:read:owner=self", "tap:read:owner=self") ++ WarehouseCaps: _*),
        stored("taps-only", Seq("pipeline:read", "tap:run:owner=self") ++ WarehouseCaps: _*),
        stored("pipelines-only", Seq("tap:read", "pipeline:read:catalog=Sales") ++ WarehouseCaps: _*),
        stored("cat-inert", "pipeline:read:catalog=Sales,collection=docs" +: WarehouseCaps: _*),
        stored("wide-sales", "*:*:catalog=Sales" +: WarehouseCaps: _*)
    )

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view")))

    private def session(role: String, name: String): ResolvedKey = APIKeyValidator.resolveFromSession(User(name, "h", role, null, null, null))

    private def unchangedCallers: Seq[ResolvedKey] = Seq(
        session(User.RoleAdmin, "alice"),
        session(User.RoleEditor, "ed"),
        session(User.RoleViewer, "vic"),
        ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true),
        stored("star", "*:*"),
        stored("monitor", "job:read" +: WarehouseCaps: _*),
        stored("inert-only", "search:vector:collection=docs" +: WarehouseCaps: _*),
        stored(RecoveryKey.Label, RecoveryKey.Capabilities: _*)
    ) ++ KeyTemplatesForSpecs.names.map(n => stored("tpl-" + n, KeyTemplatesForSpecs.capabilities(n): _*))

    private def req(rk: ResolvedKey, path: String): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", path)._1

    /** How the handlers read the caller SQL from the request body. */
    private def callerSql(body: java.util.Map[String, Any]): Option[String] = Option(body.get("sql")).map(_.toString)

    private def bodyOf(pairs: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    /** Bodies that carry a caller SQL (the warehouse would run it). */
    private def sqlBodies: Seq[java.util.Map[String, Any]] = Seq(
        bodyOf("pipeline" -> "sales_orders", "sql" -> "select * from HR.PAYROLL"),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "SELECT * FROM sales_orders"), // even its own table: the SQL can address any
        bodyOf("pipeline" -> "sales_orders", "sql" -> "  select 1  "),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "SHOW TABLES"),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "SELECT * FROM INFORMATION_SCHEMA.COLUMNS"),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "SeLeCt _DATRIS_TAP_RUN FrOm t"),
        bodyOf("pipeline" -> "sales_orders", "sql" -> " select 1"), // non-breaking space survives String.trim
        bodyOf("pipeline" -> "sales_orders", "sql" -> " "),
        bodyOf("pipeline" -> "sales_orders", "sql" -> java.lang.Double.valueOf(1.0)), // JSON number: toString "1.0"
        bodyOf("pipeline" -> "sales_orders", "sql" -> java.util.Arrays.asList("select 1")),
        bodyOf("pipeline" -> "sales_orders", "sql" -> java.lang.Boolean.TRUE),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "select 1", "SQL" -> "", "query" -> "")
    )

    /** Bodies with no caller SQL: the server-built preview runs. */
    private def previewBodies: Seq[java.util.Map[String, Any]] = Seq(
        bodyOf("pipeline" -> "sales_orders"),
        bodyOf("pipeline" -> "sales_orders", "sql" -> ""),
        bodyOf("pipeline" -> "sales_orders", "sql" -> " "),
        bodyOf("pipeline" -> "sales_orders", "sql" -> "   \n\t "),
        bodyOf("pipeline" -> "sales_orders", "sql" -> null),
        // the handlers read only `sql`: other keys never reach the warehouse
        bodyOf("pipeline" -> "sales_orders", "SQL" -> "select * from HR.PAYROLL"),
        bodyOf("pipeline" -> "sales_orders", "Sql" -> "select * from HR.PAYROLL"),
        bodyOf("pipeline" -> "sales_orders", "query" -> "select * from HR.PAYROLL"),
        bodyOf("pipeline" -> "sales_orders", "statement" -> "select * from HR.PAYROLL")
    )

    private def salesPipeline: String => PipelineConfig = {
        case "sales_orders" => PipelineConfig(name = "sales_orders", catalog = "Sales", createdByKeyLabel = "own-self")
        case _ => null
    }

    // ================================================================ Acceptance bullet 2

    test("a caller `sql` on a warehouse route is 403 for a scope-limited key") {
        bothModes { m =>
            for (rk <- limitedKeys; path <- WarehouseRoutes; body <- sqlBodies) {
                assert(CallerVisibility.limited(rk), rk.label)
                val sql = callerSql(body)
                val r = QueryAPIController.restrictedSqlRefusal(req(rk, path), sql)
                assert(r.isDefined, s"$m: ${rk.label} $path sql=$sql must be refused")
                assert(r.get.getStatusCode.value == 403, s"$m: ${rk.label} $path sql=$sql -> ${r.get.getStatusCode} ${r.get.getBody}")
                val o = JsonParser.parseString(r.get.getBody).getAsJsonObject
                assert(o.has("error"), r.get.getBody)
                // names nothing the SQL addressed
                Seq("HR.PAYROLL", "PAYROLL", "INFORMATION_SCHEMA", "_DATRIS_TAP_RUN").foreach(h =>
                    assert(!r.get.getBody.toUpperCase.contains(h), r.get.getBody)
                )
            }
        }
    }

    test("the pipeline-bound form without sql still works for a scope-limited key on an in-scope pipeline") {
        bothModes { m =>
            for (rk <- limitedKeys; path <- WarehouseRoutes) {
                val r = req(rk, path)
                // sales_orders is in every key's scope (catalog Sales, created by own-self). The Part B rule is
                // unchanged; cat-inert's only pipeline read carries collection=docs, which no pipeline context
                // holds, so it reads no pipeline in either mode (api-key-inert-scopes, not Part C).
                if (rk.label != "cat-inert")
                    assert(QueryAPIController.restrictedPipelineRefusal(r, "sales_orders", salesPipeline).isEmpty, s"$m: ${rk.label} $path")
                for (body <- previewBodies) {
                    val sql = callerSql(body)
                    assert(QueryAPIController.restrictedSqlRefusal(r, sql).isEmpty, s"$m: ${rk.label} $path: no caller sql ($sql) is the preview")
                }
            }
        }
    }

    test("a restricted session keeps exactly today's caller-sql answer; unchanged callers, a tap-run token, no identity and a null request are never refused") {
        for (path <- WarehouseRoutes; body <- sqlBodies) {
            val r = QueryAPIController.restrictedSqlRefusal(req(bob, path), callerSql(body))
            assert(r.exists(_.getStatusCode.value == 403), s"$path ${callerSql(body)}")
            assert(r.get.getBody == QueryAPIController.jsonError(QueryAPIController.RestrictedSqlRefusal), r.get.getBody)
        }
        previewBodies.foreach(b => assert(QueryAPIController.restrictedSqlRefusal(req(bob, WarehouseRoutes.head), callerSql(b)).isEmpty, b))

        val token = TapRunTokens.issue("t_sales_feed", None, 60)
        try {
            val callers = unchangedCallers :+ TapRunTokens.resolve(token).getOrElse(fail("token did not resolve"))
            bothModes { m =>
                for (rk <- callers; path <- WarehouseRoutes; body <- sqlBodies ++ previewBodies) {
                    assert(!CallerVisibility.limited(rk), rk.label)
                    assert(
                        QueryAPIController.restrictedSqlRefusal(req(rk, path), callerSql(body)).isEmpty,
                        s"$m: ${rk.label} $path ${callerSql(body)} must not be refused"
                    )
                }
            }
        } finally TapRunTokens.revoke(token)

        for (body <- sqlBodies) {
            assert(QueryAPIController.restrictedSqlRefusal(req(null, WarehouseRoutes.head), callerSql(body)).isEmpty, "no identity (auth off)")
            assert(QueryAPIController.restrictedSqlRefusal(null, callerSql(body)).isEmpty, "null request")
        }
    }
}
