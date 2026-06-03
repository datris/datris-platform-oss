package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, ResultSet}
import java.util.Properties
import scala.collection.JavaConverters._

object PostgresQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    // No MAX cap: callers (tap scripts) may need the full table. Callers that want
    // a preview-sized result pass an explicit positive limit.
    private val DEFAULT_LIMIT = 100

    // Safety cap on how long a *limited* (preview/agent) query may run before the
    // server cancels it. Ad-hoc queries written by an agent (e.g. an exact
    // COUNT(*) on a large table) can do an expensive sequential scan and tie up a
    // JDBC connection + worker thread for many seconds. This bounds that blast
    // radius. NOT applied to unlimited tap-script reads (limit < 0), which may
    // legitimately stream a whole large table and are allowed to run long.
    private val QUERY_TIMEOUT_SECONDS = 30
    // Bound connection establishment so a wedged/unreachable Postgres host fails
    // fast instead of hanging the request. These cap connect/login only, never
    // query duration, so they're safe for tap-script reads too.
    private val CONNECT_TIMEOUT_SECONDS = 10

    // Keywords that indicate write operations — checked as whole words (case-insensitive)
    private val BLOCKED_KEYWORDS = Set(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "GRANT", "REVOKE", "COPY", "CALL", "EXECUTE", "EXEC"
    )

    def query(sql: String, database: String = null, limit: Int = DEFAULT_LIMIT): java.util.List[java.util.Map[String, Any]] = {
        validateQuery(sql)

        val effectiveDb = if (database != null && database.nonEmpty) database
            else DatrisEnvironment.current.postgresDatabase

        // `limit < 0` is the "unlimited" sentinel used by tap scripts. `limit == 0`
        // or missing falls back to the preview default. Unlimited leaves the SQL
        // untouched (caller's own LIMIT clause, if any, still applies).
        val unlimited = limit < 0
        val effectiveLimit = if (unlimited) -1 else if (limit > 0) limit else DEFAULT_LIMIT
        val finalSql = if (unlimited) sql.trim.stripSuffix(";")
            else appendLimitIfNeeded(sql.trim.stripSuffix(";"), effectiveLimit)

        logger.info("Executing read-only query: " + finalSql)

        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")

        val properties = new Properties()
        properties.setProperty("user", secrets.username)
        properties.setProperty("password", secrets.password)
        // TCP connect + login timeout (pgjdbc, in seconds). Bounds connection
        // setup only — does not limit how long a query runs.
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS.toString)
        properties.setProperty("loginTimeout", CONNECT_TIMEOUT_SECONDS.toString)

        // Append database name to JDBC URL (same pattern as PostgresLoader)
        // URL format: jdbc:postgresql://host:port/dbname — check if dbname is already present after host:port
        val jdbcUrl = {
            val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
            val hasDatabase = afterProtocol.contains("/")
            if (hasDatabase) secrets.jdbcUrl else secrets.jdbcUrl + "/" + effectiveDb
        }

        var conn: Connection = null
        try {
            conn = DriverManager.getConnection(jdbcUrl, properties)
            conn.setReadOnly(true)
            conn.setAutoCommit(true)

            val stmt = conn.createStatement()
            // Cancel a runaway *limited* query after QUERY_TIMEOUT_SECONDS (pgjdbc
            // maps setQueryTimeout to statement_timeout). Unlimited tap-script
            // reads (limit < 0) keep the prior no-timeout behavior so a legitimate
            // full-table export isn't cut off mid-stream.
            if (!unlimited) stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS)
            val rs = stmt.executeQuery(finalSql)
            val metaData = rs.getMetaData
            val columnCount = metaData.getColumnCount

            val results = new java.util.ArrayList[java.util.Map[String, Any]]()
            while (rs.next()) {
                val row = new java.util.LinkedHashMap[String, Any]()
                for (i <- 1 to columnCount) {
                    val columnName = metaData.getColumnLabel(i)
                    val value = rs.getObject(i) match {
                        case xml: java.sql.SQLXML => xml.getString
                        case d: java.sql.Date => d.toString
                        case t: java.sql.Timestamp => t.toString
                        case other => other
                    }
                    row.put(columnName, value)
                }
                results.add(row)
            }

            rs.close()
            stmt.close()
            logger.info("Query returned " + results.size() + " rows")
            results
        } finally {
            if (conn != null) conn.close()
        }
    }

    private def validateQuery(sql: String): Unit = {
        if (sql == null || sql.trim.isEmpty)
            throw new DatrisException("SQL query cannot be empty")

        val normalized = sql.trim.stripSuffix(";")

        // Must start with SELECT or WITH (CTE)
        val upper = normalized.toUpperCase
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH"))
            throw new DatrisException("Only SELECT queries are allowed (WITH/CTE is also permitted)")

        // Reject stacked queries (semicolons remaining after stripping trailing one)
        if (normalized.contains(";"))
            throw new DatrisException("Semicolons are not allowed in queries")

        // Reject SQL comments that could be used for obfuscation
        if (normalized.contains("--") || normalized.contains("/*"))
            throw new DatrisException("SQL comments are not allowed in queries")

        // Check for blocked keywords as whole words
        val upperSql = normalized.toUpperCase
        BLOCKED_KEYWORDS.foreach { keyword =>
            val pattern = ("(?i)\\b" + keyword + "\\b").r
            if (pattern.findFirstIn(normalized).isDefined)
                throw new DatrisException("Query contains blocked keyword: " + keyword)
        }
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        val upperSql = sql.toUpperCase
        if (!upperSql.contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
