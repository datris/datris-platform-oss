package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

/** Read-only queries against the Databricks workspace a pipeline loads into.
 *  Pipeline-scoped like [[SnowflakeQueryUtil]] — the pipeline name selects the
 *  `credentialsSecret`/warehouse/catalog/schema, so credentials never leave
 *  the server. SQL validation mirrors [[SnowflakeQueryUtil]], with the
 *  blocklist extended for Databricks-specific data-movement and maintenance
 *  verbs. Note: if the SQL warehouse is stopped, connection open blocks while
 *  it auto-starts — that happens before the query timeout applies. */
object DatabricksQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val DEFAULT_LIMIT = 100

    // Queries run on the customer's warehouse: the timeout bounds both a hung
    // request thread AND runaway warehouse spend. A bounded query gets
    // QUERY_TIMEOUT_SECONDS; `limit < 0` (every row) gets
    // UNLIMITED_QUERY_TIMEOUT_SECONDS, the Postgres value.
    private val QUERY_TIMEOUT_SECONDS = 30
    private[util] val UNLIMITED_QUERY_TIMEOUT_SECONDS = 300

    /** The timeout a query runs under: [[QUERY_TIMEOUT_SECONDS]] for a
      * bounded query, [[UNLIMITED_QUERY_TIMEOUT_SECONDS]] for `limit < 0`. */
    private[util] def timeoutSeconds(limit: Int): Int =
        if (limit < 0) UNLIMITED_QUERY_TIMEOUT_SECONDS else QUERY_TIMEOUT_SECONDS

    // Write/DDL verbs (shared with Snowflake) plus Databricks-specific data
    // movement and maintenance verbs. USE is blocked so a query can't
    // re-point the session at another catalog/schema than the pipeline's
    // config. GET/SET are deliberately NOT here: as statements they are
    // already rejected by the allowlist (queries must start with
    // SELECT/WITH/SHOW/DESCRIBE), and as words they appear in legitimate
    // reads — get() is a Databricks SQL function and 'GET'/'SET' are common
    // data values in WHERE literals.
    private val BLOCKED_KEYWORDS = Set(
        "INSERT",
        "UPDATE",
        "DELETE",
        "DROP",
        "ALTER",
        "CREATE",
        "TRUNCATE",
        "GRANT",
        "REVOKE",
        "CALL",
        "EXECUTE",
        "EXEC",
        "PUT",
        "REMOVE",
        "COPY",
        "MERGE",
        "USE",
        "OPTIMIZE",
        "VACUUM",
        "RESTORE",
        "CLONE",
        "REFRESH",
        "MSCK",
        "DECLARE"
    )

    /** Functions a read can call that act outside the query: call out over
      * HTTP or to another database, invoke a model or search endpoint, read a
      * secret, read files or streams outside the pipeline's tables, or call
      * Java methods. Each entry is a case-insensitive regex, refused where it
      * is called (the name, optional backticks, optional whitespace, then
      * `(`), so a column or value named `secret` or a table named
      * `ai_results` still reads. Every `ai_*` function is refused, including
      * ones Databricks adds later. `identifier` is refused where it is called:
      * `IDENTIFIER(<string>)(args)` calls a function named by a string, built
      * any way, so no name list could catch what it calls. The cost of
      * matching calls: a column-list alias with a listed name
      * (`WITH secret(a) AS ...`) is refused too. Matched over the whole text, string
      * literals included, as BLOCKED_KEYWORDS is.
      *
      * This is a second line, not the control: Databricks has no read-only
      * session, and the query runs as the pipeline's `credentialsSecret`
      * identity, so its grants bound what a query can do.
      *
      * Vendor check, 2026-11-23: each page under
      * docs.databricks.com/aws/en/sql/language-manual/functions/ was read
      * (fetched, not run against a workspace). Confirmed on Databricks SQL:
      * http_request (HTTP connection), remote_query (remote database through a
      * connection), ai_query (Model Serving endpoint; the other ai_* functions
      * are refused as a class), vector_search (search index; not on classic
      * SQL warehouses), secret and try_secret (secret service), read_files,
      * read_kafka, read_kinesis, read_pubsub, read_pulsar (streaming only, in
      * preview), try_reflect (Databricks SQL, Runtime 14.1+; found during the
      * check, added beside reflect). Documented for Databricks Runtime only,
      * so unavailable on a SQL warehouse today but refused in case that
      * changes: java_method, reflect. Not confirmed: none was executed. */
    private[util] val BLOCKED_FUNCTIONS: Seq[String] = Seq(
        "http_request",
        "remote_query",
        "ai_\\w+",
        "vector_search",
        "secret",
        "try_secret",
        "read_files",
        "read_kafka",
        "read_kinesis",
        "read_pubsub",
        "read_pulsar",
        "java_method",
        "reflect",
        "try_reflect",
        "identifier"
    )

    private lazy val BlockedFunctionPatterns: Seq[scala.util.matching.Regex] =
        BLOCKED_FUNCTIONS.map(fn => ("(?i)(?<!\\w)`?(" + fn + ")`?\\s*\\(").r)

    /** The first [[BLOCKED_FUNCTIONS]] name `sql` calls, as written
      * (`IDENTIFIER` in upper case). */
    private[util] def blockedFunction(sql: String): Option[String] =
        BlockedFunctionPatterns.view
            .flatMap(_.findFirstMatchIn(sql))
            .headOption
            .map(_.group(1))
            .map(name => if (name.equalsIgnoreCase("identifier")) "IDENTIFIER" else name)

    case class DatabricksQueryResult(sql: String, results: java.util.List[java.util.Map[String, Any]])

    /** Run `sql` (or, when None, a preview of the pipeline's destination table)
     *  against the Databricks workspace the named pipeline loads into. */
    def query(pipelineName: String, sql: Option[String], limit: Int = DEFAULT_LIMIT): DatabricksQueryResult = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (config == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)
        val db = if (config.destination != null) config.destination.database else null
        if (db == null || !db.useDatabricks)
            throw new DatrisException("Pipeline '" + pipelineName + "' does not have a Databricks destination. " +
                "query_databricks only works for pipelines with destination.database.useDatabricks=true; " +
                "use the query tool matching this pipeline's destination instead.")

        val requested = sql.map(_.trim).filter(_.nonEmpty)
            .getOrElse("SELECT * FROM " + DatabricksConnectionUtil.qualifiedTable(db))
        validateQuery(requested)

        val unlimited = limit < 0
        val effectiveLimit = if (unlimited) -1 else if (limit > 0) limit else DEFAULT_LIMIT
        val normalized = requested.trim.stripSuffix(";")
        // SHOW/DESCRIBE don't take LIMIT and return naturally small result sets.
        val isSelect = {
            val u = normalized.toUpperCase
            u.startsWith("SELECT") || u.startsWith("WITH")
        }
        val finalSql = if (!isSelect || unlimited) normalized
        else appendLimitIfNeeded(normalized, effectiveLimit)

        logger.info("Executing read-only Databricks query for pipeline '" + pipelineName + "': " + finalSql)

        DatabricksConnectionUtil.withConnection(db, pipelineName = pipelineName) { conn =>
            val stmt = conn.createStatement()
            try {
                stmt.setQueryTimeout(timeoutSeconds(limit))
                val rs = stmt.executeQuery(finalSql)
                try {
                    val metaData = rs.getMetaData
                    val columnCount = metaData.getColumnCount

                    val results = new java.util.ArrayList[java.util.Map[String, Any]]()
                    // Cap SHOW/DESC output at the effective limit too — SHOW has no
                    // LIMIT clause, but a metastore with thousands of objects
                    // shouldn't flood the agent's context.
                    val maxRows = if (unlimited) Int.MaxValue else effectiveLimit
                    while (rs.next() && results.size() < maxRows) {
                        val row = new java.util.LinkedHashMap[String, Any]()
                        for (i <- 1 to columnCount) {
                            val columnName = metaData.getColumnLabel(i)
                            val value = rs.getObject(i) match {
                                case d: java.sql.Date => d.toString
                                case t: java.sql.Timestamp => t.toString
                                case other => other
                            }
                            row.put(columnName, value)
                        }
                        results.add(row)
                    }
                    logger.info("Databricks query returned " + results.size() + " rows")
                    DatabricksQueryResult(finalSql, results)
                } finally {
                    Try(rs.close())
                }
            } finally {
                Try(stmt.close())
            }
        }
    }

    private[util] def validateQuery(sql: String): Unit = {
        if (sql == null || sql.trim.isEmpty)
            throw new DatrisException("SQL query cannot be empty")

        val normalized = sql.trim.stripSuffix(";")

        // Read-only allowlist: SELECT/WITH for data, SHOW/DESCRIBE for metadata
        // discovery (catalogs, schemas, tables, columns).
        val upper = normalized.toUpperCase
        val allowed = upper.startsWith("SELECT") || upper.startsWith("WITH") ||
            upper.startsWith("SHOW") || upper.startsWith("DESCRIBE") || upper.startsWith("DESC")
        if (!allowed)
            throw new DatrisException("Only read-only statements are allowed: SELECT (WITH/CTE), SHOW, and DESCRIBE")

        // Reject stacked queries (semicolons remaining after stripping trailing one)
        if (normalized.contains(";"))
            throw new DatrisException("Semicolons are not allowed in queries")

        // Reject SQL comments that could be used for obfuscation
        if (normalized.contains("--") || normalized.contains("/*"))
            throw new DatrisException("SQL comments are not allowed in queries")

        BLOCKED_KEYWORDS.foreach { keyword =>
            val pattern = ("(?i)\\b" + keyword + "\\b").r
            if (pattern.findFirstIn(normalized).isDefined)
                throw new DatrisException("Query contains blocked keyword: " + keyword)
        }

        blockedFunction(normalized).foreach(fn => throw new DatrisException("Query contains blocked function: " + fn))
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        if (!sql.toUpperCase.contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
