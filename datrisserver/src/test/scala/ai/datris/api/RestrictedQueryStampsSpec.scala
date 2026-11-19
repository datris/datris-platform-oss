package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.HiddenTapScrub
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, ObjectStoreQueryUtil}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Catalog-scoped user access: pipeline query routes never show a
  * restricted user tap-identity stamps (or another pipeline's table)
  * (plans/stories/catalog-scoped-user-access-query-stamps.md), Acceptance,
  * RestrictedQueryStampsSpec.
  *
  * bob: restricted, grants `{Sales: view}`. Assumed:
  * {{{
  * object HiddenTapScrub {
  *   def isTapStampColumn(name: String): Boolean
  *   def dropTapStampColumns(rows: java.util.List[java.util.Map[String, Any]]): java.util.List[java.util.Map[String, Any]]
  *   def dropTapStampNames(columns: java.util.List[String]): java.util.List[String]
  * }
  * object QueryAPIController {
  *   val RestrictedSqlRefusal: String
  *   def restrictedSqlRefusal(request: HttpServletRequest, sql: Option[String]): Option[ResponseEntity[String]]
  *   def restrictedRows(request: HttpServletRequest,
  *                      rows: java.util.List[java.util.Map[String, Any]]): java.util.List[java.util.Map[String, Any]]
  *   def restrictedObjectStoreResult(request: HttpServletRequest,
  *                                   result: ObjectStoreQueryUtil.QueryResult): ObjectStoreQueryUtil.QueryResult
  * }
  * }}}
  */
class RestrictedQueryStampsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private type Row = java.util.Map[String, Any]
    private type Rows = java.util.List[java.util.Map[String, Any]]

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/query/snowflake"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read")),
            isLegacyFullAccess = false
        )

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ------------------------------------------------------------ helpers

    private def row(pairs: (String, Any)*): Row = {
        val m = new java.util.LinkedHashMap[String, Any]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def rows(rs: Row*): Rows = {
        val l = new java.util.ArrayList[Row]()
        rs.foreach(l.add)
        l
    }

    private def keys(r: Row): Seq[String] = r.keySet.asScala.toList

    private def strList(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private val Kept: Seq[String] = Seq("id", "_datris_run_id", "_datris_ingested_at", "_datris_config_version", "amount")

    /** A row with data columns, the three kept stamps and the three tap stamps,
      * interleaved so key order is meaningful. */
    private def stampedRow(id: Int, tapRun: String = "t_hr_feed|2026-11-16T10:00:00Z"): Row =
        row(
            "id" -> id,
            "_datris_tap_run" -> tapRun,
            "_datris_run_id" -> ("run-" + id),
            "_datris_script_sha" -> "sha-abc",
            "_datris_ingested_at" -> "2026-11-16T10:00:01Z",
            "_datris_source" -> "feeds.example.com",
            "_datris_config_version" -> 3,
            "amount" -> (id * 10)
        )

    private def osResult(cols: java.util.List[String], rs: Rows): ObjectStoreQueryUtil.QueryResult =
        ObjectStoreQueryUtil.QueryResult(
            cols,
            rs,
            "s3://datris/qs_orders/",
            "parquet",
            snapshotId = java.lang.Long.valueOf(42L),
            snapshotTimestamp = "2026-11-16T10:00:02Z"
        )

    private val AllCols: Seq[String] =
        Seq("id", "_datris_tap_run", "_datris_run_id", "_datris_script_sha", "_datris_ingested_at", "_datris_source", "_datris_config_version", "amount")

    // ------------------------------------------------------------ restrictedSqlRefusal

    test("restrictedSqlRefusal refuses every caller SQL for a restricted session") {
        assert(
            QueryAPIController.RestrictedSqlRefusal ==
                "Custom SQL is not available for this user. Leave `sql` out to preview the pipeline's table."
        )
        val inputs = Seq(
            "SELECT * FROM t",
            "SELECT _datris_tap_run AS x FROM t",
            "SELECT substr(_datris_tap_run, 1, 3) FROM t",
            "SELECT concat(_datris_source, '') FROM t",
            "SELECT cast(_datris_script_sha AS varchar) FROM t",
            "SELECT \"_datris_tap_run\" FROM t",
            "SELECT [_datris_tap_run] FROM t",
            "SELECT `_datris_tap_run` FROM t",
            "SeLeCt _DATRIS_TAP_RUN FrOm T",
            "SELECT x FROM (SELECT _datris_tap_run AS x FROM t) s",
            "WITH s AS (SELECT _datris_source FROM t) SELECT * FROM s",
            "SELECT id FROM t UNION SELECT _datris_tap_run FROM t",
            "SELECT 1 FROM t WHERE _datris_source LIKE 'a%'",
            "SELECT * FROM other_schema.other_table",
            "SHOW TABLES",
            "DESCRIBE TABLE t",
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS",
            "SELECT id FROM t"
        )
        inputs.foreach { sql =>
            val r = QueryAPIController.restrictedSqlRefusal(req(bob), Some(sql))
            assert(r.isDefined, s"restricted sql must be refused: $sql")
            assert(r.get.getStatusCode.value == 403, s"$sql -> ${r.get.getStatusCode}")
            assert(r.get.getBody == QueryAPIController.jsonError(QueryAPIController.RestrictedSqlRefusal), s"$sql -> ${r.get.getBody}")
        }
    }

    test(
        "restrictedSqlRefusal is None for a restricted session without sql, with blank sql, and for an unrestricted editor and a catalog-scoped API key with any sql"
    ) {
        assert(QueryAPIController.restrictedSqlRefusal(req(bob), None).isEmpty)
        Seq("", " ", "   \n\t ").foreach(s => assert(QueryAPIController.restrictedSqlRefusal(req(bob), Some(s)).isEmpty, s"blank sql '$s' is absent"))
        val anySql = Seq(
            None,
            Some(""),
            Some("SELECT * FROM t"),
            Some("SELECT _datris_tap_run FROM t"),
            Some("SHOW TABLES"),
            Some("SELECT * FROM INFORMATION_SCHEMA.COLUMNS")
        )
        for (rk <- Seq(editor, salesKey, fullKey); sql <- anySql)
            assert(QueryAPIController.restrictedSqlRefusal(req(rk), sql).isEmpty, s"${rk.label} / $sql must not be refused")
    }

    // ------------------------------------------------------------ dropTapStampColumns

    test(
        "dropTapStampColumns removes the three tap columns in any letter case and keeps _datris_run_id, _datris_ingested_at, _datris_config_version, data columns and key order"
    ) {
        Seq("_datris_tap_run", "_datris_script_sha", "_datris_source", "_DATRIS_TAP_RUN", "_Datris_Source", "_DATRIS_SCRIPT_SHA").foreach(c =>
            assert(HiddenTapScrub.isTapStampColumn(c), c)
        )
        Seq("_datris_run_id", "_datris_ingested_at", "_datris_config_version", "id", "_datris_tap_run_x", "source", null).foreach(c =>
            assert(!HiddenTapScrub.isTapStampColumn(c), String.valueOf(c))
        )

        val upper = row(
            "ID" -> 2,
            "_DATRIS_TAP_RUN" -> "t_hr_feed|2026-11-16T10:00:00Z",
            "_DATRIS_RUN_ID" -> "run-2",
            "_Datris_Source" -> "feeds.example.com",
            "_DATRIS_SCRIPT_SHA" -> "sha-abc",
            "_DATRIS_INGESTED_AT" -> "t",
            "_DATRIS_CONFIG_VERSION" -> 3
        )
        val in = rows(stampedRow(1), upper)
        val out = HiddenTapScrub.dropTapStampColumns(in)
        assert(out.size == 2)
        assert(keys(out.get(0)) == Kept)
        assert(keys(out.get(1)) == Seq("ID", "_DATRIS_RUN_ID", "_DATRIS_INGESTED_AT", "_DATRIS_CONFIG_VERSION"))
        assert(out.get(0).get("_datris_run_id") == "run-1")
        assert(out.get(0).get("amount") == 10)
    }

    test("dropTapStampColumns returns the same list instance when no row carries a tap stamp column") {
        val clean = rows(row("id" -> 1, "_datris_run_id" -> "run-1", "_datris_ingested_at" -> "t", "_datris_config_version" -> 1), row("id" -> 2))
        assert(HiddenTapScrub.dropTapStampColumns(clean) eq clean)
        val empty = rows()
        assert(HiddenTapScrub.dropTapStampColumns(empty) eq empty)

        // Only rows that change are copied; an untouched row is the same instance.
        val untouched = row("id" -> 9)
        val mixed = rows(stampedRow(1), untouched)
        val out = HiddenTapScrub.dropTapStampColumns(mixed)
        assert(!(out eq mixed))
        assert(out.get(1) eq untouched)
        assert(mixed.get(0).containsKey("_datris_tap_run"), "the input row must not be mutated")

        val cols = strList("id", "_datris_run_id")
        assert(HiddenTapScrub.dropTapStampNames(cols) eq cols)
    }

    test("a nested map value with a stamp-named key is left alone") {
        val nested = row("_datris_tap_run" -> "t_hr_feed|x", "_datris_source" -> "s")
        val r = row("id" -> 1, "payload" -> nested)
        val in = rows(r)
        val out = HiddenTapScrub.dropTapStampColumns(in)
        assert(out eq in, "only top-level keys count")
        assert(out.get(0).get("payload").asInstanceOf[Row].keySet.asScala == Set("_datris_tap_run", "_datris_source"))
    }

    // ------------------------------------------------------------ restrictedRows / restrictedObjectStoreResult

    test("restrictedRows and restrictedObjectStoreResult return their argument for an unrestricted editor and a catalog-scoped API key") {
        for (rk <- Seq(editor, fullKey)) {
            val in = rows(stampedRow(1), stampedRow(2))
            assert(QueryAPIController.restrictedRows(req(rk), in) eq in, rk.label)
            assert(keys(in.get(0)) == keys(stampedRow(1)), "rows are untouched")
            val res = osResult(strList(AllCols: _*), rows(stampedRow(1)))
            assert(QueryAPIController.restrictedObjectStoreResult(req(rk, path = "/api/v1/query/objectstore"), res) eq res, rk.label)
        }
        // A catalog-scoped API key is held to its scope (scoped-key-read-routes, Part B): the columns go, as for the session.
        val keyRows = QueryAPIController.restrictedRows(req(salesKey), rows(stampedRow(1), stampedRow(2)))
        keyRows.asScala.foreach(r => assert(keys(r) == Kept, salesKey.label))
        val keyRes = QueryAPIController.restrictedObjectStoreResult(
            req(salesKey, path = "/api/v1/query/objectstore"),
            osResult(strList(AllCols: _*), rows(stampedRow(1)))
        )
        assert(keyRes.columns.asScala.toList == Kept && keys(keyRes.rows.get(0)) == Kept, salesKey.label)
    }

    test("restrictedObjectStoreResult drops the columns from both columns and rows and keeps path, format, snapshotId, snapshotTimestamp") {
        val res = osResult(strList(AllCols: _*), rows(stampedRow(1), stampedRow(2)))
        val out = QueryAPIController.restrictedObjectStoreResult(req(bob, path = "/api/v1/query/objectstore"), res)
        assert(out.columns.asScala.toList == Kept)
        assert(out.rows.size == 2)
        out.rows.asScala.foreach(r => assert(keys(r) == Kept))
        assert(out.path == "s3://datris/qs_orders/")
        assert(out.format == "parquet")
        assert(out.snapshotId == java.lang.Long.valueOf(42L))
        assert(out.snapshotTimestamp == "2026-11-16T10:00:02Z")

        // Upper-case column labels (Snowflake folds unquoted names).
        val up = strList("ID", "_DATRIS_TAP_RUN", "_DATRIS_RUN_ID", "_Datris_Source", "_datris_script_sha")
        assert(HiddenTapScrub.dropTapStampNames(up).asScala.toList == Seq("ID", "_DATRIS_RUN_ID"))
    }

    test("restricted rows lose the columns for a readable tap too") {
        // bob can read Sales; t_sales is filed in Sales. Decision 2: always drop.
        val in = rows(stampedRow(1, tapRun = "t_sales|2026-11-16T10:00:00Z"), stampedRow(2, tapRun = ""))
        val out = QueryAPIController.restrictedRows(req(bob), in)
        assert(out.size == 2)
        out.asScala.foreach(r => assert(keys(r) == Kept))
        val res = osResult(strList(AllCols: _*), rows(stampedRow(3, tapRun = "t_sales|2026-11-16T10:00:00Z")))
        val os = QueryAPIController.restrictedObjectStoreResult(req(bob, path = "/api/v1/query/objectstore"), res)
        assert(os.columns.asScala.toList == Kept)
        assert(keys(os.rows.get(0)) == Kept)
    }
}
