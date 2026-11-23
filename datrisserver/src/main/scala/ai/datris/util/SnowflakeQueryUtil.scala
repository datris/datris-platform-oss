package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

/** Read-only queries against the Snowflake account a pipeline loads into.
 *  Pipeline-scoped like [[ObjectStoreQueryUtil]] — the pipeline name selects
 *  the `credentialsSecret`/warehouse/db/schema, so credentials never leave the
 *  server. SQL validation mirrors [[PostgresQueryUtil]], with Snowflake's
 *  read-only introspection statements (SHOW, DESCRIBE) also allowed so agents
 *  can discover databases/schemas/tables/columns without extra endpoints. */
object SnowflakeQueryUtil {
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

    // Write/DDL verbs (shared with Postgres) plus Snowflake-specific data
    // movement and session verbs. USE is blocked so a query can't re-point the
    // session at another database/role than the pipeline's config.
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
        "USE"
    )

    /** Functions a read can call that act outside the query: cancel or abort
      * other queries, sessions and transactions, resume pipes and tasks, hold
      * the warehouse, or advance a sequence. Each entry is a case-insensitive
      * regex. A `SYSTEM$` name is refused where it is called (the name,
      * optional whitespace, then `(`), every one of them except
      * [[SYSTEM_FUNCTION_ALLOWLIST]], so a name added by Snowflake later is
      * refused too. `NEXTVAL` is written without parentheses
      * (`<sequence>.NEXTVAL`), so it is refused as a whole word. Matched over
      * the whole text, string literals included, as BLOCKED_KEYWORDS is.
      *
      * This is a second line, not the control: Snowflake has no read-only
      * session, and the query runs as the pipeline's `credentialsSecret` role,
      * so that role's grants bound what a query can do. External functions
      * (a user-defined name that calls out over the network) cannot be told
      * apart by name and are bounded only by those grants.
      *
      * Vendor check, 2026-11-23: each page under
      * docs.snowflake.com/en/sql-reference/functions/ was read (fetched, not
      * run against an account). Confirmed as System Control functions that
      * act: SYSTEM$CANCEL_QUERY, SYSTEM$CANCEL_ALL_QUERIES,
      * SYSTEM$ABORT_SESSION, SYSTEM$ABORT_TRANSACTION, SYSTEM$PIPE_FORCE_RESUME,
      * SYSTEM$USER_TASK_CANCEL_ONGOING_EXECUTIONS,
      * SYSTEM$TASK_DEPENDENTS_ENABLE, SYSTEM$WAIT. Confirmed as System
      * Information functions that only read (the allowlist): SYSTEM$TYPEOF,
      * SYSTEM$CLUSTERING_INFORMATION, SYSTEM$CLUSTERING_DEPTH. NEXTVAL:
      * confirmed in the sequences guide (user-guide/querying-sequences); each
      * reference generates new values. Not confirmed: none of these was
      * executed, and no other SYSTEM$ name was reviewed one by one (they are
      * refused as a class). */
    private[util] val BLOCKED_FUNCTIONS: Seq[String] = Seq(
        "SYSTEM\\$[\\w$]+",
        "NEXTVAL"
    )

    /** Read-only `SYSTEM$` functions a query may call. */
    private[util] val SYSTEM_FUNCTION_ALLOWLIST: Set[String] = Set(
        "SYSTEM$TYPEOF",
        "SYSTEM$CLUSTERING_INFORMATION",
        "SYSTEM$CLUSTERING_DEPTH"
    )

    // BLOCKED_FUNCTIONS entries refused as whole words rather than as calls.
    private val WordOnlyFunctions = Set("NEXTVAL")

    private lazy val BlockedFunctionPatterns: Seq[scala.util.matching.Regex] = BLOCKED_FUNCTIONS.map { fn =>
        if (WordOnlyFunctions.contains(fn)) ("(?i)(?<![\\w$])(" + fn + ")(?![\\w$])").r
        else ("(?i)(?<![\\w$])\"?(" + fn + ")\"?\\s*\\(").r
    }

    /** The first [[BLOCKED_FUNCTIONS]] name `sql` uses, as written. */
    private[util] def blockedFunction(sql: String): Option[String] =
        BlockedFunctionPatterns.iterator
            .flatMap(_.findAllMatchIn(sql).map(_.group(1)))
            .find(name => !SYSTEM_FUNCTION_ALLOWLIST.contains(name.toUpperCase(java.util.Locale.ROOT)))

    case class SnowflakeQueryResult(sql: String, results: java.util.List[java.util.Map[String, Any]])

    /** Run `sql` (or, when None, a preview of the pipeline's destination table)
     *  against the Snowflake account the named pipeline loads into. */
    def query(pipelineName: String, sql: Option[String], limit: Int = DEFAULT_LIMIT): SnowflakeQueryResult = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (config == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)
        val db = if (config.destination != null) config.destination.database else null
        if (db == null || !db.useSnowflake)
            throw new DatrisException("Pipeline '" + pipelineName + "' does not have a Snowflake destination. " +
                "query_snowflake only works for pipelines with destination.database.useSnowflake=true; " +
                "use the query tool matching this pipeline's destination instead.")

        val requested = sql.map(_.trim).filter(_.nonEmpty)
            .getOrElse("SELECT * FROM " + SnowflakeConnectionUtil.qualifiedTable(db))
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

        logger.info("Executing read-only Snowflake query for pipeline '" + pipelineName + "': " + finalSql)

        SnowflakeConnectionUtil.withConnection(db) { conn =>
            val stmt = conn.createStatement()
            try {
                stmt.setQueryTimeout(timeoutSeconds(limit))
                val rs = stmt.executeQuery(finalSql)
                try {
                    val metaData = rs.getMetaData
                    val columnCount = metaData.getColumnCount

                    val results = new java.util.ArrayList[java.util.Map[String, Any]]()
                    // Cap SHOW/DESC output at the effective limit too — SHOW has no
                    // LIMIT clause, but an account with thousands of objects
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
                    logger.info("Snowflake query returned " + results.size() + " rows")
                    SnowflakeQueryResult(finalSql, results)
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
        // discovery (databases, schemas, tables, columns).
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
