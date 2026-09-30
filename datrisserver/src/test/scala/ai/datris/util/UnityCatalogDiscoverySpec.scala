package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.sql.SQLException
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Story: Unity Catalog 2: discovery (browse + find_data federation)
  * (plans/stories/unity-catalog-2-discovery.md), Acceptance bullet 1.
  *
  * Seams this spec pins. The story names the renderers, `resolveWarehouse`,
  * the injectable clock and the `Iterator[Map[String, Any]]` row idiom; the
  * rest is the smallest shape that lets tag fallback and caching run without
  * a warehouse or JDBC mocks:
  *
  * {{{
  *   object UnityCatalogDiscovery {
  *     def showCatalogsSql: String
  *     def showSchemasSql(catalog: String): String
  *     def showTablesSql(catalog: String, schema: String): String
  *     def describeSql(catalog: String, schema: String, table: String): String   // DESCRIBE TABLE EXTENDED
  *     def tableTagsSql(catalog: String, schema: String, table: String): String  // system.information_schema.table_tags
  *     def searchSql(tokens: List[String], limit: Int): String
  *
  *     // DESCRIBE TABLE EXTENDED rows (col_name, data_type, comment) -> [{name, type, comment?}],
  *     // stopping at the first blank or '#'-prefixed col_name.
  *     def describeColumns(rows: Iterator[Map[String, Any]]): JsonArray
  *
  *     // Runs tableTagsSql through `runQuery`; rows (tag_name, tag_value) ->
  *     // {tagsAvailable: true, tags: {name: value}}. Any SQLException ->
  *     // {tagsAvailable: false} with no tags and no error key; never throws.
  *     def tagsFor(runQuery: String => Iterator[Map[String, Any]],
  *                 catalog: String, schema: String, table: String): JsonObject
  *
  *     // The connection-free core of browse(target, catalog, schema, table):
  *     // browse(...) = withConnection { conn => browseWith(sql => rowsOf(conn, sql), ...) }.
  *     // Catalog and schema listings are served from `cache` per secret; tables
  *     // and columns always hit runQuery.
  *     def browseWith(runQuery: String => Iterator[Map[String, Any]], secret: String,
  *                    catalog: Option[String], schema: Option[String], table: Option[String],
  *                    cache: UnityCatalogCache): JsonObject
  *
  *     // The story's resolveWarehouse(secret, explicit, pipelines), with the
  *     // secret passed as its name plus its fields (the shape
  *     // SecretsRetrieverUtil.platformSecrets() yields).
  *     def resolveWarehouse(secretName: String, secretFields: java.util.Map[String, String],
  *                          explicit: Option[String], pipelines: List[PipelineConfig]): Either[String, String]
  *   }
  *
  *   // TTL fixed at 5 minutes; the clock is the only test knob.
  *   class UnityCatalogCache(clock: () => Long = () => System.currentTimeMillis())
  * }}}
  */
class UnityCatalogDiscoverySpec extends AnyFunSuite {

    // ---------------------------------------------------------------- renderers

    test("SHOW/DESCRIBE statements backtick-quote identifiers with hyphens and spaces and leave simple names bare") {
        assert(UnityCatalogDiscovery.showCatalogsSql.trim.toUpperCase.startsWith("SHOW CATALOGS"))

        val schemasQuoted = UnityCatalogDiscovery.showSchemasSql("my-catalog")
        assert(schemasQuoted.toUpperCase.startsWith("SHOW SCHEMAS") || schemasQuoted.toUpperCase.startsWith("SHOW DATABASES"), schemasQuoted)
        assert(schemasQuoted.contains("`my-catalog`"), schemasQuoted)
        val schemasBare = UnityCatalogDiscovery.showSchemasSql("main")
        assert(schemasBare.contains("main") && !schemasBare.contains("`main`"), schemasBare)

        val tables = UnityCatalogDiscovery.showTablesSql("main", "my schema")
        assert(tables.toUpperCase.startsWith("SHOW TABLES"), tables)
        assert(tables.contains("main.`my schema`"), tables)
        assert(UnityCatalogDiscovery.showTablesSql("main", "sales").contains("main.sales"))

        val describe = UnityCatalogDiscovery.describeSql("main", "sales", "order lines")
        assert(describe.toUpperCase.startsWith("DESCRIBE TABLE EXTENDED"), describe)
        assert(describe.contains("main.sales.`order lines`"), describe)
        val describeBare = UnityCatalogDiscovery.describeSql("main", "sales", "orders")
        assert(describeBare.contains("main.sales.orders") && !describeBare.contains("`"), describeBare)

        // A backtick inside a name is doubled, never allowed to close the quote.
        assert(UnityCatalogDiscovery.showSchemasSql("we`ird").contains("`we``ird`"))

        val tags = UnityCatalogDiscovery.tableTagsSql("main", "sales", "orders")
        assert(tags.toLowerCase.contains("system.information_schema.table_tags"), tags)
    }

    test("search SQL lowercases tokens, doubles single quotes, excludes information_schema and system") {
        val sql = UnityCatalogDiscovery.searchSql(List("Orders", "O'Brien"), 50)
        val lower = sql.toLowerCase
        assert(lower.contains("system.information_schema.columns"), sql)
        assert(lower.contains("lower("), sql)
        assert(sql.contains("orders"), sql)
        assert(!sql.contains("Orders"), "tokens must be lowercased: " + sql)
        assert(sql.contains("o''brien"), "single quotes must be doubled: " + sql)
        assert(!sql.contains("o'brien%"), sql)
        assert(lower.contains("like"), sql)
        // Excluded by literal, not merely referenced in the FROM clause.
        assert(sql.contains("'information_schema'"), sql)
        assert(sql.contains("'system'"), sql)
        assert(sql.contains("50"), sql)
    }

    // ---------------------------------------------------------------- mappers

    test("describe rows map to columns with type and comment, stopping at the first blank partition/detail row") {
        val rows = Iterator[Map[String, Any]](
            Map("col_name" -> "id", "data_type" -> "bigint", "comment" -> "Primary key"),
            Map("col_name" -> "amount", "data_type" -> "decimal(10,2)", "comment" -> null),
            Map("col_name" -> "region", "data_type" -> "string", "comment" -> "Sales region"),
            Map("col_name" -> "", "data_type" -> "", "comment" -> ""),
            Map("col_name" -> "# Partition Information", "data_type" -> "", "comment" -> ""),
            Map("col_name" -> "region", "data_type" -> "string", "comment" -> "Sales region"),
            Map("col_name" -> "# Detailed Table Information", "data_type" -> "", "comment" -> ""),
            Map("col_name" -> "Owner", "data_type" -> "someone", "comment" -> "")
        )
        val cols = UnityCatalogDiscovery.describeColumns(rows).asScala.map(_.getAsJsonObject).toList
        assert(cols.map(_.get("name").getAsString) == List("id", "amount", "region"))
        assert(cols.map(_.get("type").getAsString) == List("bigint", "decimal(10,2)", "string"))
        assert(cols.head.get("comment").getAsString == "Primary key")
        assert(cols(2).get("comment").getAsString == "Sales region")
        val amountComment = cols(1).get("comment")
        assert(amountComment == null || amountComment.isJsonNull || amountComment.getAsString.isEmpty)

        // A '#' header with no preceding blank row also ends the column list.
        val noBlank = Iterator[Map[String, Any]](
            Map("col_name" -> "id", "data_type" -> "int", "comment" -> null),
            Map("col_name" -> "# Detailed Table Information", "data_type" -> "", "comment" -> "")
        )
        assert(UnityCatalogDiscovery.describeColumns(noBlank).size == 1)
    }

    test("table_tags rows yield a tags map; a SQLException on the tag query yields tagsAvailable=false and no error") {
        val seen = ListBuffer[String]()
        val ok = UnityCatalogDiscovery.tagsFor(
            sql => {
                seen += sql
                Iterator[Map[String, Any]](
                    Map("tag_name" -> "datris_pipeline", "tag_value" -> "orders_daily"),
                    Map("tag_name" -> "datris_catalog", "tag_value" -> "sales")
                )
            },
            "main",
            "sales",
            "orders"
        )
        assert(seen.nonEmpty && seen.head.toLowerCase.contains("table_tags"), seen)
        assert(ok.get("tagsAvailable").getAsBoolean)
        val tags = ok.getAsJsonObject("tags")
        assert(tags.get("datris_pipeline").getAsString == "orders_daily")
        assert(tags.get("datris_catalog").getAsString == "sales")

        val denied = UnityCatalogDiscovery.tagsFor(
            _ => throw new SQLException("[INSUFFICIENT_PERMISSIONS] User does not have USE SCHEMA on system.information_schema"),
            "main",
            "sales",
            "orders"
        )
        assert(!denied.get("tagsAvailable").getAsBoolean)
        assert(!denied.has("error"), denied.toString)
        assert(!denied.has("tags") || denied.getAsJsonObject("tags").size() == 0, denied.toString)
    }

    // ---------------------------------------------------------------- cache

    /** Counts statements by kind and answers each with one plausible row. */
    private class FakeWarehouse {
        val statements = ListBuffer[String]()
        def count(prefix: String*): Int = statements.count(s => prefix.exists(p => s.trim.toUpperCase.startsWith(p)))
        val run: String => Iterator[Map[String, Any]] = { sql =>
            statements += sql
            val u = sql.trim.toUpperCase
            if (u.startsWith("SHOW CATALOGS")) Iterator(Map[String, Any]("catalog" -> "main"))
            else if (u.startsWith("SHOW SCHEMAS") || u.startsWith("SHOW DATABASES")) Iterator(Map[String, Any]("databaseName" -> "sales"))
            else if (u.startsWith("SHOW TABLES")) Iterator(Map[String, Any]("database" -> "sales", "tableName" -> "orders", "isTemporary" -> false))
            else if (u.startsWith("DESCRIBE")) Iterator(Map[String, Any]("col_name" -> "id", "data_type" -> "bigint", "comment" -> null))
            else Iterator.empty
        }
    }

    private val Minute = 60L * 1000L

    test("catalogs and schemas are served from cache within 5 minutes and refetched after, per secret") {
        var now = 1000000L
        val cache = new UnityCatalogCache(() => now)
        val wh = new FakeWarehouse
        val CATALOGS = Seq("SHOW CATALOGS")
        val SCHEMAS = Seq("SHOW SCHEMAS", "SHOW DATABASES")

        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", None, None, None, cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), None, None, cache)
        assert(wh.count(CATALOGS: _*) == 1)
        assert(wh.count(SCHEMAS: _*) == 1)

        now += 5 * Minute - 1
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", None, None, None, cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), None, None, cache)
        assert(wh.count(CATALOGS: _*) == 1, "catalogs should come from cache within 5 minutes")
        assert(wh.count(SCHEMAS: _*) == 1, "schemas should come from cache within 5 minutes")

        // Another secret never reads the first secret's cache.
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-b", None, None, None, cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-b", Some("main"), None, None, cache)
        assert(wh.count(CATALOGS: _*) == 2)
        assert(wh.count(SCHEMAS: _*) == 2)

        now += 2
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", None, None, None, cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), None, None, cache)
        assert(wh.count(CATALOGS: _*) == 3, "catalogs must be refetched after 5 minutes")
        assert(wh.count(SCHEMAS: _*) == 3, "schemas must be refetched after 5 minutes")
    }

    test("tables are never cached") {
        val now = 1000000L
        val cache = new UnityCatalogCache(() => now)
        val wh = new FakeWarehouse
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), Some("sales"), None, cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), Some("sales"), None, cache)
        assert(wh.count("SHOW TABLES") == 2)

        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), Some("sales"), Some("orders"), cache)
        UnityCatalogDiscovery.browseWith(wh.run, "dbx-a", Some("main"), Some("sales"), Some("orders"), cache)
        assert(wh.count("DESCRIBE") == 2, "columns must never be cached")
    }

    // ---------------------------------------------------------------- warehouse

    test("resolveWarehouse prefers the explicit param, then the secret field, then a pipeline using the secret, else Left with the three options") {
        def fields(kv: (String, String)*): java.util.Map[String, String] = {
            val m = new java.util.HashMap[String, String]()
            m.put("host", "dbc-a1b2c3d4-e5f6.cloud.databricks.com")
            m.put("token", "dapi-test")
            kv.foreach { case (k, v) => m.put(k, v) }
            m
        }
        def dbxPipeline(name: String, secret: String, warehouse: String) =
            PipelineConfig(
                name = name,
                destination = Destination(database =
                    Database(dbName = "main", schema = "sales", table = name, useDatabricks = true, credentialsSecret = secret, warehouse = warehouse)
                )
            )
        val pgPipeline = PipelineConfig(
            name = "pg",
            destination = Destination(database =
                Database(dbName = "datris", schema = "public", table = "t", usePostgres = true, credentialsSecret = "dbx", warehouse = "pgwh")
            )
        )
        val otherSecret = dbxPipeline("other", "dbx-other", "otherwh")
        val ours = dbxPipeline("ours", "dbx", "pipelinewh")
        val pipelines = List(pgPipeline, otherSecret, ours)
        def resolve(f: java.util.Map[String, String], explicit: Option[String], ps: List[PipelineConfig]) =
            UnityCatalogDiscovery.resolveWarehouse("dbx", f, explicit, ps).map(DatabricksConnectionUtil.warehouseHttpPath _)

        // 1. explicit param beats everything
        assert(resolve(fields("warehouse" -> "secretwh"), Some("explicitwh"), pipelines) == Right("/sql/1.0/warehouses/explicitwh"))
        // 2. secret field beats a pipeline; aliases accepted
        assert(resolve(fields("warehouse" -> "secretwh"), None, pipelines) == Right("/sql/1.0/warehouses/secretwh"))
        assert(resolve(fields("httpPath" -> "/sql/1.0/warehouses/abc123"), None, pipelines) == Right("/sql/1.0/warehouses/abc123"))
        assert(resolve(fields("http_path" -> "/sql/1.0/warehouses/abc124"), None, pipelines) == Right("/sql/1.0/warehouses/abc124"))
        assert(resolve(fields("DATABRICKS_WAREHOUSE" -> "abc125"), None, pipelines) == Right("/sql/1.0/warehouses/abc125"))
        // 3. the first Databricks pipeline whose credentialsSecret is this secret
        //    (a Postgres pipeline or one on another secret never counts)
        assert(resolve(fields(), None, pipelines) == Right("/sql/1.0/warehouses/pipelinewh"))
        // 4. nothing → Left naming all three ways to supply one
        val left = UnityCatalogDiscovery.resolveWarehouse("dbx", fields(), None, List(pgPipeline, otherSecret))
        assert(left.isLeft, left)
        val msg = left.swap.toOption.get.toLowerCase
        assert(msg.contains("warehouse"), msg)
        assert(msg.contains("param") || msg.contains("query"), "must mention the warehouse query param: " + msg)
        assert(msg.contains("secret"), "must mention the secret field: " + msg)
        assert(msg.contains("pipeline"), "must mention a pipeline using the secret: " + msg)
    }

    // ---------------------------------------------------------------- warm cache skips the connection

    test("with a warm cache, catalogs and schemas listings never open a connection; tables and columns always do") {
        val now = 1000000L
        val cache = new UnityCatalogCache(() => now)
        val wh = new FakeWarehouse
        var opened = 0
        val connect: ((String => Iterator[Map[String, Any]]) => com.google.gson.JsonObject) => com.google.gson.JsonObject = { f =>
            opened += 1
            f(wh.run)
        }

        // Cold: each level opens once and fills the cache.
        val cold = UnityCatalogDiscovery.browseVia(connect, "dbx-a", None, None, None, cache)
        UnityCatalogDiscovery.browseVia(connect, "dbx-a", Some("main"), None, None, cache)
        assert(opened == 2)

        // Warm: no connection at all, same answer shape.
        opened = 0
        val warm = UnityCatalogDiscovery.browseVia(connect, "dbx-a", None, None, None, cache)
        val warmSchemas = UnityCatalogDiscovery.browseVia(connect, "dbx-a", Some(" main "), None, None, cache)
        assert(opened == 0, "a cached listing must not open a JDBC session")
        assert(warm == cold, warm.toString + " vs " + cold.toString)
        assert(warmSchemas.get("level").getAsString == "schemas")
        assert(warmSchemas.get("catalog").getAsString == "main")
        assert(warmSchemas.getAsJsonArray("schemas").get(0).getAsString == "sales")

        UnityCatalogDiscovery.browseVia(connect, "dbx-a", Some("main"), Some("sales"), None, cache)
        UnityCatalogDiscovery.browseVia(connect, "dbx-a", Some("main"), Some("sales"), Some("orders"), cache)
        assert(opened == 2, "tables and columns always connect")

        // Another secret is cold.
        UnityCatalogDiscovery.browseVia(connect, "dbx-b", None, None, None, cache)
        assert(opened == 3)
    }

    // ---------------------------------------------------------------- warehouse error translation

    /** A ~30 KB Databricks JDBC message: driver prefix, the class twice (once
      * before a Java class name), a sentence, then a Thrift status dump and
      * hundreds of Spark stack frames. */
    private def databricksError(cls: String, sentence: String): java.sql.SQLException = {
        val frames = (1 to 400).map(i => "\n\tat org.apache.spark.sql.execution.SparkPlan.executeQuery" + i + "(SparkPlan.scala:" + i + ")").mkString
        val msg = "[Databricks][JDBCDriver](500051) ERROR processing query/statement. Error Code: 0, SQL state: 42704, " +
            "Query: SHOW SCH***, Error message from Server: org.apache.hive.service.cli.HiveSQLException: Error running query: [" + cls +
            "] org.apache.spark.sql.catalyst.analysis.SomeException: [" + cls + "] " + sentence + " Please verify and retry. SQLSTATE: 42704" +
            frames + "\nTGetOperationStatusResp(status:TStatus(statusCode:ERROR_STATUS, infoMessages:[*org.apache.hive.service.cli.HiveSQLException:" +
            ("x" * 12000) + "))"
        new java.sql.SQLException(msg)
    }

    test("warehouse errors map to a status and a short message without stack text") {
        val cases = List(
            ("NO_SUCH_CATALOG_EXCEPTION", "Catalog 'bogus' was not found.", 404),
            ("SCHEMA_NOT_FOUND", "The schema `main`.`nope` cannot be found.", 404),
            ("TABLE_OR_VIEW_NOT_FOUND", "The table or view `main`.`sales`.`gone` cannot be found.", 404),
            ("INVALID_PARAMETER_VALUE.LOCATION_OVERLAP", "Input path overlaps with other external tables.", 400),
            ("PERMISSION_DENIED", "User does not have USE CATALOG on Catalog 'restricted'.", 403),
            ("INSUFFICIENT_PERMISSIONS", "User does not have USE SCHEMA on system.information_schema.", 403),
            ("INTERNAL_ERROR", "Something went wrong on the server.", 502)
        )
        cases.foreach { case (cls, sentence, expected) =>
            val e = databricksError(cls, sentence)
            assert(e.getMessage.length > 25000, "fixture should be ~30 KB")
            val (status, message) = UnityCatalogDiscovery.translateWarehouseError(e)
            assert(status == expected, cls + " -> " + status + ": " + message)
            assert(message == "[" + cls + "] " + sentence, cls + ": " + message)
            assert(message.length <= DatabricksErrorText.MaxMessageLength)
            assert(!message.contains("\tat ") && !message.contains("TGetOperationStatusResp") && !message.contains("org.apache"), message)
        }
    }

    test("the class is found even when it appears only inside the Thrift status dump") {
        val msg = "[Databricks][JDBCDriver](500051) ERROR processing query/statement. Error message from Server: " +
            "TGetOperationStatusResp(status:TStatus(statusCode:ERROR_STATUS, infoMessages:[*org.apache.hive.service.cli.HiveSQLException:" +
            "Error running query: [NO_SUCH_CATALOG_EXCEPTION] org.apache.spark.sql.catalyst.analysis.NoSuchCatalogException: " +
            "[NO_SUCH_CATALOG_EXCEPTION] Catalog 'bogus' was not found. Please verify. SQLSTATE: 42704:17:16, org.apache.spark.X:run:X.java:1, " +
            ("org.apache.spark.Y:run:Y.java:2, " * 800) + "])"
        val (status, message) = UnityCatalogDiscovery.translateWarehouseError(new java.sql.SQLException(msg))
        assert(status == 404)
        assert(message == "[NO_SUCH_CATALOG_EXCEPTION] Catalog 'bogus' was not found.", message)
    }

    test("unclassified warehouse errors are 502 with the first line only, capped at 500 chars") {
        val frames = (1 to 400).map(i => "\n\tat org.apache.spark.Foo.bar" + i + "(Foo.scala:" + i + ")").mkString
        val (s1, m1) = UnityCatalogDiscovery.translateWarehouseError(new java.sql.SQLException("Connection reset by peer" + frames))
        assert(s1 == 502 && m1 == "Connection reset by peer", m1)

        val (s2, m2) = UnityCatalogDiscovery.translateWarehouseError(new RuntimeException("y" * 30000))
        assert(s2 == 502 && m2.length <= 500, m2.length.toString)

        val (s3, m3) = UnityCatalogDiscovery.translateWarehouseError(new RuntimeException("[SOME_CLASS] " + ("z" * 30000)))
        assert(s3 == 502 && m3.length <= 500 && m3.startsWith("[SOME_CLASS] "), m3.take(40))

        // A bare PERMISSION_DENIED (no brackets) is still 403.
        val (s4, m4) = UnityCatalogDiscovery.translateWarehouseError(new java.sql.SQLException("PERMISSION_DENIED: no access to warehouse" + frames))
        assert(s4 == 403 && m4 == "PERMISSION_DENIED: no access to warehouse", m4)

        // Messages only in the cause chain are used; a message-less error names its class.
        val (_, m5) = UnityCatalogDiscovery.translateWarehouseError(new RuntimeException(null, new java.sql.SQLException("[SCHEMA_NOT_FOUND] Schema gone.")))
        assert(m5 == "[SCHEMA_NOT_FOUND] Schema gone.", m5)
        assert(UnityCatalogDiscovery.translateWarehouseError(new NullPointerException())._2 == "NullPointerException")
    }

    test("connect-time DatrisExceptions stay 502 with their first line, capped") {
        val e =
            new DatrisException("Databricks SQL warehouse 'bogus' was not found in this workspace. [PERMISSION_DENIED] x" + ("\n\tat a.b.C.d(C.java:1)" * 200))
        val (status, message) = UnityCatalogDiscovery.translateWarehouseError(e)
        assert(status == 502)
        assert(message == "Databricks SQL warehouse 'bogus' was not found in this workspace. [PERMISSION_DENIED] x", message)
    }
}
