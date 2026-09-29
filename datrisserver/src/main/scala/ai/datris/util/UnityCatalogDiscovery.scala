package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisEnvironment, PipelineConfig, TenantContext}
import com.google.gson.{JsonArray, JsonObject}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.Connection
import java.util.concurrent.{Callable, ConcurrentHashMap, ExecutionException, Executors, ThreadFactory, TimeUnit, TimeoutException}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** One Unity Catalog table matching a `find_data` query. `comment` is the
  * table comment (nullable); `matchedColumns` are the column names that
  * matched a query token; `tags` carries the Datris tags story 1 writes
  * (`datris_pipeline`, `datris_catalog`) when the tag lookup was allowed. */
case class UcHit(
    secret: String,
    catalog: String,
    schema: String,
    table: String,
    comment: String,
    matchedColumns: List[String],
    tags: Map[String, String]
)

/** Federated Unity Catalog results handed to `CatalogFind.find`: the secrets
  * searched, the secrets skipped with a reason, and the hits. */
case class UnityCatalogResults(searched: List[String], skipped: List[(String, String)], hits: List[UcHit])

/** In-process cache of catalog and schema listings, per secret. Tables and
  * columns are never cached. TTL is fixed at 5 minutes; the clock is
  * injectable for tests. */
class UnityCatalogCache(clock: () => Long = () => System.currentTimeMillis()) {
    private val TtlMillis = 5L * 60L * 1000L
    private val entries = new ConcurrentHashMap[(String, String, String), (Long, JsonArray)]()

    def getOrLoad(secret: String, kind: String, key: String)(load: => JsonArray): JsonArray = {
        val k = (secret, kind, key)
        val now = clock()
        val hit = entries.get(k)
        if (hit != null && now < hit._1) hit._2.deepCopy()
        else {
            val fresh = load
            entries.put(k, (now + TtlMillis, fresh.deepCopy()))
            fresh
        }
    }
}

/** Read-only discovery over the Unity Catalog a Databricks Platform secret
  * can see: catalog → schema → table → column browse, and a column/table-name
  * search over `system.information_schema` for `find_data` federation.
  *
  * The SQL renderers and row mappers are pure; `browse`/`search` open one
  * connection via [[DatabricksConnectionUtil.withConnection]] and feed rows
  * through the `Iterator[Map[String, Any]]` idiom so the core runs in specs
  * without JDBC mocks. */
object UnityCatalogDiscovery {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val QueryTimeoutSeconds = 30
    private val MaxRows = 1000
    private val PipelineTag = "datris_pipeline"

    /** Shared browse cache for the server process. Keys are tenant-qualified
      * by `browse`, so two tenants with the same secret name never share. */
    val cache = new UnityCatalogCache()

    import DatabricksConnectionUtil.ident

    // ------------------------------------------------------------ renderers

    def showCatalogsSql: String = "SHOW CATALOGS"
    def showSchemasSql(catalog: String): String = "SHOW SCHEMAS IN " + ident(catalog)
    def showTablesSql(catalog: String, schema: String): String = "SHOW TABLES IN " + ident(catalog) + "." + ident(schema)
    def describeSql(catalog: String, schema: String, table: String): String =
        "DESCRIBE TABLE EXTENDED " + ident(catalog) + "." + ident(schema) + "." + ident(table)

    private def lit(v: String): String = UnityCatalogMetadataSync.lit(v)
    private def name(v: String): String = lit(DatabricksConnectionUtil.effectiveName(v))

    def tableTagsSql(catalog: String, schema: String, table: String): String =
        "SELECT tag_name, tag_value FROM system.information_schema.table_tags" +
            " WHERE catalog_name = " + name(catalog) +
            " AND schema_name = " + name(schema) +
            " AND table_name = " + name(table)

    /** The `datris_pipeline` tag of every table in one schema — one query
      * for a whole SHOW TABLES listing. */
    def schemaPipelineTagsSql(catalog: String, schema: String): String =
        "SELECT table_name, tag_value FROM system.information_schema.table_tags" +
            " WHERE catalog_name = " + name(catalog) +
            " AND schema_name = " + name(schema) +
            " AND tag_name = " + lit(PipelineTag)

    /** Tables whose name, comment or any column name contains a query token
      * (case-insensitive), one row per table with the matching column names
      * joined by commas. System catalogs and information_schema are excluded. */
    def searchSql(tokens: List[String], limit: Int): String = {
        val toks = tokens.map(_.toLowerCase).filter(_.nonEmpty).distinct
        def like(expr: String, t: String): String = "LOWER(" + expr + ") LIKE " + lit("%" + t + "%")
        val colMatch = if (toks.isEmpty) "FALSE" else toks.map(like("c.column_name", _)).mkString(" OR ")
        val anyMatch =
            if (toks.isEmpty) "FALSE"
            else toks.flatMap(t => List(like("c.column_name", t), like("c.table_name", t), like("t.comment", t))).mkString(" OR ")
        "SELECT c.table_catalog, c.table_schema, c.table_name, MAX(t.comment) AS table_comment, " +
            "concat_ws(',', collect_set(CASE WHEN " + colMatch + " THEN c.column_name END)) AS matched_columns " +
            "FROM system.information_schema.columns c " +
            "JOIN system.information_schema.tables t " +
            "ON c.table_catalog = t.table_catalog AND c.table_schema = t.table_schema AND c.table_name = t.table_name " +
            "WHERE c.table_schema <> 'information_schema' AND c.table_catalog <> 'system' " +
            "AND (" + anyMatch + ") " +
            "GROUP BY c.table_catalog, c.table_schema, c.table_name " +
            "ORDER BY size(collect_set(CASE WHEN " + colMatch + " THEN c.column_name END)) DESC, " +
            "c.table_catalog, c.table_schema, c.table_name " +
            "LIMIT " + math.max(1, limit)
    }

    /** The Datris tags on a set of search hits, in one query. */
    def hitTagsSql(hits: List[(String, String, String)]): String =
        "SELECT catalog_name, schema_name, table_name, tag_name, tag_value FROM system.information_schema.table_tags" +
            " WHERE tag_name IN ('datris_pipeline', 'datris_catalog') AND (" +
            hits.map { case (c, s, t) => "(catalog_name = " + name(c) + " AND schema_name = " + name(s) + " AND table_name = " + name(t) + ")" }
                .mkString(" OR ") + ")"

    // ------------------------------------------------------------ mappers

    private def str(row: Map[String, Any], keys: String*): String =
        keys.iterator.flatMap(k => row.get(k)).find(_ != null).map(_.toString).orNull

    /** First non-null of the named columns, else the row's first value. */
    private def firstOf(row: Map[String, Any], keys: String*): String =
        Option(str(row, keys: _*)).getOrElse(row.values.find(_ != null).map(_.toString).orNull)

    def describeColumns(rows: Iterator[Map[String, Any]]): JsonArray = {
        val out = new JsonArray()
        var done = false
        while (!done && rows.hasNext) {
            val row = rows.next()
            val colName = Option(str(row, "col_name")).map(_.trim).getOrElse("")
            if (colName.isEmpty || colName.startsWith("#")) done = true
            else {
                val o = new JsonObject()
                o.addProperty("name", colName)
                Option(str(row, "data_type")).foreach(o.addProperty("type", _))
                Option(str(row, "comment")).filter(_.nonEmpty).foreach(o.addProperty("comment", _))
                out.add(o)
            }
        }
        out
    }

    /** Tags on one table. Any failure (typically missing grants on
      * `system.information_schema`) yields `tagsAvailable: false` — never an
      * error; tags are a hint, not a requirement. */
    def tagsFor(runQuery: String => Iterator[Map[String, Any]], catalog: String, schema: String, table: String): JsonObject = {
        val out = new JsonObject()
        try {
            val tags = new JsonObject()
            runQuery(tableTagsSql(catalog, schema, table)).foreach { row =>
                val k = str(row, "tag_name")
                if (k != null) tags.addProperty(k, Option(str(row, "tag_value")).getOrElse(""))
            }
            out.addProperty("tagsAvailable", true)
            out.add("tags", tags)
        } catch {
            case e: Exception =>
                logger.info("Unity Catalog tag lookup unavailable for " + catalog + "." + schema + "." + table + ": " + e.getMessage)
                out.addProperty("tagsAvailable", false)
        }
        out
    }

    private def namesArray(rows: Iterator[Map[String, Any]], keys: String*): JsonArray = {
        val arr = new JsonArray()
        rows.foreach(r => Option(firstOf(r, keys: _*)).foreach(arr.add))
        arr
    }

    def browseWith(
        runQuery: String => Iterator[Map[String, Any]],
        secret: String,
        catalog: Option[String],
        schema: Option[String],
        table: Option[String],
        cache: UnityCatalogCache
    ): JsonObject = {
        val cat = catalog.map(_.trim).filter(_.nonEmpty)
        val sch = schema.map(_.trim).filter(_.nonEmpty)
        val tbl = table.map(_.trim).filter(_.nonEmpty)
        if (sch.isDefined && cat.isEmpty) throw new IllegalArgumentException("schema requires catalog")
        if (tbl.isDefined && sch.isEmpty) throw new IllegalArgumentException("table requires catalog and schema")

        val out = new JsonObject()
        out.addProperty("secret", secret)
        (cat, sch, tbl) match {
            case (None, _, _) =>
                out.addProperty("level", "catalogs")
                val arr = cache.getOrLoad(secret, "catalogs", "")(namesArray(runQuery(showCatalogsSql), "catalog", "catalogName"))
                out.add("catalogs", arr)
            case (Some(c), None, _) =>
                out.addProperty("level", "schemas")
                out.addProperty("catalog", c)
                val arr = cache.getOrLoad(secret, "schemas", c)(namesArray(runQuery(showSchemasSql(c)), "databaseName", "namespace", "schemaName"))
                out.add("schemas", arr)
            case (Some(c), Some(s), None) =>
                out.addProperty("level", "tables")
                out.addProperty("catalog", c)
                out.addProperty("schema", s)
                val names = runQuery(showTablesSql(c, s))
                    .filterNot(r => r.get("isTemporary").exists(v => v != null && v.toString.equalsIgnoreCase("true")))
                    .flatMap(r => Option(firstOf(r, "tableName", "table_name")))
                    .toList
                val (tagsAvailable, owners) =
                    try {
                        val m = runQuery(schemaPipelineTagsSql(c, s)).flatMap { r =>
                            for (t <- Option(str(r, "table_name")); v <- Option(str(r, "tag_value"))) yield t.toLowerCase -> v
                        }.toMap
                        (true, m)
                    } catch {
                        case e: Exception =>
                            logger.info("Unity Catalog tag lookup unavailable for " + c + "." + s + ": " + e.getMessage)
                            (false, Map.empty[String, String])
                    }
                val arr = new JsonArray()
                names.foreach { n =>
                    val o = new JsonObject()
                    o.addProperty("name", n)
                    owners.get(n.toLowerCase).foreach(o.addProperty("datrisPipeline", _))
                    arr.add(o)
                }
                out.add("tables", arr)
                out.addProperty("tagsAvailable", tagsAvailable)
            case (Some(c), Some(s), Some(t)) =>
                out.addProperty("level", "columns")
                out.addProperty("catalog", c)
                out.addProperty("schema", s)
                out.addProperty("table", t)
                out.add("columns", describeColumns(runQuery(describeSql(c, s, t))))
                val tags = tagsFor(runQuery, c, s, t)
                out.add("tagsAvailable", tags.get("tagsAvailable"))
                if (tags.has("tags")) {
                    val tagObj = tags.getAsJsonObject("tags")
                    out.add("tags", tagObj)
                    if (tagObj.has(PipelineTag)) out.addProperty("datrisPipeline", tagObj.get(PipelineTag).getAsString)
                }
        }
        out
    }

    /** Search rows → hits, then a best-effort tag lookup for them. */
    def searchWith(runQuery: String => Iterator[Map[String, Any]], secret: String, tokens: List[String], limit: Int): List[UcHit] = {
        if (tokens.forall(_.trim.isEmpty)) return Nil
        val base = runQuery(searchSql(tokens, limit)).flatMap { r =>
            for {
                c <- Option(str(r, "table_catalog"))
                s <- Option(str(r, "table_schema"))
                t <- Option(str(r, "table_name"))
            } yield UcHit(
                secret = secret,
                catalog = c,
                schema = s,
                table = t,
                comment = Option(str(r, "table_comment")).filter(_.nonEmpty).orNull,
                matchedColumns = Option(str(r, "matched_columns")).map(_.split(",").map(_.trim).filter(_.nonEmpty).toList.sorted).getOrElse(Nil),
                tags = Map.empty
            )
        }.toList
        if (base.isEmpty) return base
        val tagMap: Map[(String, String, String), Map[String, String]] =
            try {
                runQuery(hitTagsSql(base.map(h => (h.catalog, h.schema, h.table)))).toList.flatMap { r =>
                    for {
                        c <- Option(str(r, "catalog_name"))
                        s <- Option(str(r, "schema_name"))
                        t <- Option(str(r, "table_name"))
                        k <- Option(str(r, "tag_name"))
                    } yield (c.toLowerCase, s.toLowerCase, t.toLowerCase) -> (k -> Option(str(r, "tag_value")).getOrElse(""))
                }.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toMap }
            } catch {
                case e: Exception =>
                    logger.info("Unity Catalog tag lookup unavailable for search hits: " + e.getMessage)
                    Map.empty
            }
        base.map(h => h.copy(tags = tagMap.getOrElse((h.catalog.toLowerCase, h.schema.toLowerCase, h.table.toLowerCase), Map.empty)))
    }

    // ------------------------------------------------------------ warehouse

    /** The SQL warehouse to browse with: explicit param → secret field
      * `warehouse` (aliases httpPath/http_path/DATABRICKS_WAREHOUSE) → the
      * first Databricks pipeline using this secret. Left names all three. */
    def resolveWarehouse(
        secretName: String,
        secretFields: java.util.Map[String, String],
        explicit: Option[String],
        pipelines: List[PipelineConfig]
    ): Either[String, String] = {
        def nonBlank(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)
        explicit.flatMap(nonBlank)
            .orElse(Option(secretFields).flatMap(f => CredentialResolver.secretField(f, "warehouse", "httpPath", "http_path", "DATABRICKS_WAREHOUSE")).flatMap(
                nonBlank
            ))
            .orElse(
                pipelines.iterator
                    .filter(p => p != null && p.destination != null && p.destination.database != null)
                    .map(_.destination.database)
                    .find(db => db.useDatabricks && secretName != null && secretName == db.credentialsSecret && nonBlank(db.warehouse).isDefined)
                    .map(_.warehouse.trim)
            ) match {
            case Some(w) => Right(w)
            case None =>
                Left(
                    "No SQL warehouse is known for Databricks secret '" + secretName + "'. Supply one of: " +
                        "the 'warehouse' query param (the warehouse ID from SQL Warehouses → Connection details), " +
                        "a 'warehouse' field on the secret, " +
                        "or a Databricks pipeline whose credentialsSecret is this secret (its destination warehouse is used)."
                )
        }
    }

    // ------------------------------------------------------------ search deadline

    val DefaultSearchTimeoutSeconds = 45L

    /** Per-secret deadline for `find_data includeUnityCatalog`:
      * `DATRIS_UNITY_CATALOG_SEARCH_TIMEOUT_SECONDS` (or the
      * `datris.unityCatalogSearchTimeoutSeconds` system property), default 45.
      * Blank, non-numeric or non-positive values fall back to the default. */
    def searchTimeoutSeconds: Long =
        sys.props.get("datris.unityCatalogSearchTimeoutSeconds")
            .orElse(sys.env.get("DATRIS_UNITY_CATALOG_SEARCH_TIMEOUT_SECONDS"))
            .flatMap(v => Try(v.trim.toLong).toOption)
            .filter(_ > 0)
            .getOrElse(DefaultSearchTimeoutSeconds)

    private lazy val searchPool = Executors.newCachedThreadPool(new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            val t = new Thread(r, "uc-search")
            t.setDaemon(true)
            t
        }
    })

    /** Run `f` on a pool thread carrying the caller's tenant environment
      * (TenantContext is thread-local) and wait at most `timeoutSeconds`.
      * Left("timed out after Ns") past the deadline (the task is interrupted
      * and abandoned); an exception thrown by `f` is rethrown as-is. */
    def withDeadline[T](timeoutSeconds: Long)(f: => T): Either[String, T] = {
        val env = DatrisEnvironment.current
        val future = searchPool.submit(new Callable[T] {
            override def call(): T = {
                TenantContext.set(env)
                try f
                finally TenantContext.clear()
            }
        })
        try Right(future.get(timeoutSeconds, TimeUnit.SECONDS))
        catch {
            case _: TimeoutException =>
                future.cancel(true)
                Left("timed out after " + timeoutSeconds + "s")
            case e: ExecutionException if e.getCause != null => throw e.getCause
        }
    }

    // ------------------------------------------------------------ connection

    private def target(secret: String, warehouse: String): Database =
        Database(useDatabricks = true, credentialsSecret = secret, warehouse = warehouse)

    /** Materialize one statement's rows (capped) so the statement can close. */
    private def rowsOf(conn: Connection, sql: String): Iterator[Map[String, Any]] = {
        val stmt = conn.createStatement()
        try {
            stmt.setQueryTimeout(QueryTimeoutSeconds)
            val rs = stmt.executeQuery(sql)
            try {
                val md = rs.getMetaData
                val n = md.getColumnCount
                val buf = ListBuffer[Map[String, Any]]()
                while (buf.size < MaxRows && rs.next()) {
                    buf += (1 to n).map(i => md.getColumnLabel(i) -> (rs.getObject(i): Any)).toMap
                }
                buf.iterator
            } finally Try(rs.close())
        } finally Try(stmt.close())
    }

    def browse(secret: String, warehouse: String, catalog: Option[String], schema: Option[String], table: Option[String]): JsonObject = {
        val cacheKey = DatrisEnvironment.current.environment + "/" + secret
        val out = DatabricksConnectionUtil.withConnection(target(secret, warehouse)) { conn =>
            browseWith(sql => rowsOf(conn, sql), cacheKey, catalog, schema, table, cache)
        }
        out.addProperty("secret", secret)
        out
    }

    def search(secret: String, warehouse: String, tokens: List[String], limit: Int): List[UcHit] =
        DatabricksConnectionUtil.withConnection(target(secret, warehouse)) { conn =>
            searchWith(sql => rowsOf(conn, sql), secret, tokens, limit)
        }
}
