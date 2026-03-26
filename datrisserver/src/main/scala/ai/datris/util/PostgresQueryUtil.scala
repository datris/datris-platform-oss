package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, ResultSet}
import java.util.Properties
import scala.collection.JavaConverters._

object PostgresQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MAX_LIMIT = 1000
    private val DEFAULT_LIMIT = 100

    // Keywords that indicate write operations — checked as whole words (case-insensitive)
    private val BLOCKED_KEYWORDS = Set(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "GRANT", "REVOKE", "COPY", "CALL", "EXECUTE", "EXEC"
    )

    def query(sql: String, database: String = "datris", limit: Int = DEFAULT_LIMIT): java.util.List[java.util.Map[String, Any]] = {
        validateQuery(sql)

        val effectiveLimit = math.min(if (limit > 0) limit else DEFAULT_LIMIT, MAX_LIMIT)
        val finalSql = appendLimitIfNeeded(sql.trim.stripSuffix(";"), effectiveLimit)

        logger.info("Executing read-only query: " + finalSql)

        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")

        val properties = new Properties()
        properties.setProperty("user", secrets.username)
        properties.setProperty("password", secrets.password)

        // Append database name to JDBC URL (same pattern as PostgresLoader)
        // URL format: jdbc:postgresql://host:port/dbname — check if dbname is already present after host:port
        val jdbcUrl = {
            val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
            val hasDatabase = afterProtocol.contains("/")
            if (hasDatabase) secrets.jdbcUrl else secrets.jdbcUrl + "/" + database
        }

        var conn: Connection = null
        try {
            conn = DriverManager.getConnection(jdbcUrl, properties)
            conn.setReadOnly(true)
            conn.setAutoCommit(true)

            val stmt = conn.createStatement()
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
