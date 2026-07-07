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
    // request thread AND runaway warehouse spend. Not applied when the caller
    // explicitly asks for unlimited (limit < 0).
    private val QUERY_TIMEOUT_SECONDS = 30

    // Write/DDL verbs (shared with Postgres) plus Snowflake-specific data
    // movement and session verbs. USE is blocked so a query can't re-point the
    // session at another database/role than the pipeline's config.
    private val BLOCKED_KEYWORDS = Set(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXECUTE", "EXEC",
        "PUT", "REMOVE", "COPY", "MERGE", "USE"
    )

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
                if (!unlimited) stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS)
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

    private def validateQuery(sql: String): Unit = {
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
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        if (!sql.toUpperCase.contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
