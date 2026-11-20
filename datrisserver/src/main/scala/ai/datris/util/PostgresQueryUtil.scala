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
    // radius.
    private[util] val QUERY_TIMEOUT_SECONDS = 30
    // The ceiling for an *unlimited* read (limit < 0, tap scripts reading a whole
    // table). Longer than the preview ceiling so a legitimate full-table read is
    // not cut off, but every query has one.
    private[util] val UNLIMITED_QUERY_TIMEOUT_SECONDS = 300
    // Bound connection establishment so a wedged/unreachable Postgres host fails
    // fast instead of hanging the request. These cap connect/login only, never
    // query duration, so they're safe for tap-script reads too.
    private val CONNECT_TIMEOUT_SECONDS = 10

    /** Issued on the connection before it goes back to the pool, after the
      * query's transaction is rolled back: returns the session to its
      * starting state (pgjdbc invalidates its own prepared-statement cache
      * when it sees this command complete). Startup
      * parameters of the connection are its reset values, so the session ends
      * as the pool opened it. */
    private[util] val SessionReset = "DISCARD ALL"

    /** Statements run at the start of every query transaction, before the
      * query: the transaction is read-only on the server whatever the
      * driver's `readOnlyMode`, and the statement timeout is set server-side
      * for this transaction only. */
    private[util] def transactionSetup(timeoutSeconds: Int): Seq[String] =
        Seq("SET TRANSACTION READ ONLY", "SET LOCAL statement_timeout = " + (timeoutSeconds.toLong * 1000L))

    /** The timeout a query runs under: [[QUERY_TIMEOUT_SECONDS]] for a
      * bounded query, [[UNLIMITED_QUERY_TIMEOUT_SECONDS]] for `limit < 0`. */
    private[util] def timeoutSeconds(limit: Int): Int =
        if (limit < 0) UNLIMITED_QUERY_TIMEOUT_SECONDS else QUERY_TIMEOUT_SECONDS

    // Keywords that indicate write operations — checked as whole words (case-insensitive)
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
        "COPY",
        "CALL",
        "EXECUTE",
        "EXEC"
    )

    // A second line, not the control: the read-only transaction is what stops
    // writes, and the database role's privileges are what bound the rest. This
    // list refuses, as whole words (case-insensitive, like the keywords above),
    // functions that have no place in a query: ones that act outside the
    // query's transaction (other backends, the server process and its files,
    // replication state, statistics, another connection) and a few others. It
    // is the only such list; keep additions here.
    private[util] val BLOCKED_FUNCTIONS: Seq[String] = Seq(
        // backend and server control
        "pg_terminate_backend",
        "pg_cancel_backend",
        "pg_reload_conf",
        "pg_rotate_logfile",
        "pg_promote",
        "pg_switch_wal",
        "pg_create_restore_point",
        "pg_backup_\\w+",
        "pg_stat_reset\\w*",
        // replication state
        "pg_(create|drop|copy)_\\w*replication_slot\\w*",
        "pg_replication_slot_advance",
        "pg_sync_replication_slots",
        "pg_logical_\\w+",
        // server files and large objects
        "pg_read_file",
        "pg_read_binary_file",
        "pg_ls_\\w+",
        "pg_stat_file",
        "lo_import",
        "lo_export",
        // another connection
        "dblink\\w*",
        // other functions with no place in a query
        "query_to_xml\\w*",
        "table_to_xml\\w*",
        "cursor_to_xml\\w*",
        "database_to_xml\\w*",
        "schema_to_xml\\w*"
    )

    // Quoting forms the word check does not read are not accepted.
    private val UnicodeEscapeQuote = "(?i)u&[\"']".r

    /** Lightweight reachability probe: connect with a short login timeout and
     *  run SELECT 1. Returns None when Postgres answers, Some(error message)
     *  when it is down or its secret is missing. Shared by the /health and
     *  /destinations/available endpoints and the create-pipeline
     *  pre-validation so "postgres is available" always means the same thing
     *  everywhere. */
    def probeError(): Option[String] = {
        try {
            val secrets = SecretsRetrieverUtil.postgresSecrets()
            Class.forName("org.postgresql.Driver")

            val properties = new Properties()
            properties.setProperty("user", secrets.username)
            properties.setProperty("password", secrets.password)
            properties.setProperty("loginTimeout", "2")

            // Append a database name when the URL has none (same pattern as query()).
            val jdbcUrl = PostgresSql.jdbcUrl(secrets.jdbcUrl, "datris")

            var conn: Connection = null
            try {
                conn = DriverManager.getConnection(jdbcUrl, properties)
                conn.setReadOnly(true)
                val stmt = conn.createStatement()
                stmt.setQueryTimeout(5)
                val rs = stmt.executeQuery("SELECT 1")
                rs.close()
                stmt.close()
                None
            } finally {
                if (conn != null) conn.close()
            }
        } catch {
            case e: Exception => Some(e.getMessage)
        }
    }

    /** Test seam: when set, queries take their connection from this pool
      * (and the secret is not read). */
    @volatile private[util] var poolOverride: com.zaxxer.hikari.HikariDataSource = null

    def query(sql: String, database: String = null, limit: Int = DEFAULT_LIMIT): java.util.List[java.util.Map[String, Any]] =
        run(sql, Nil, database, limit)

    /** [[query]] with `params` bound, in order, to the `?` placeholders of
      * `sql` (a server-built statement: names and values are never spliced
      * into its text). Same validation, read-only connection, limit and
      * timeout handling as [[query]]. */
    def queryBound(sql: String, params: Seq[String], database: String = null, limit: Int = DEFAULT_LIMIT): java.util.List[java.util.Map[String, Any]] =
        run(sql, params, database, limit)

    private def run(sql: String, params: Seq[String], database: String, limit: Int): java.util.List[java.util.Map[String, Any]] = {
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

        val timeout = timeoutSeconds(limit)
        val testPool = poolOverride
        if (testPool != null)
            return PostgresPool.withEvictableConnectionFrom(testPool) { (conn, evict) => runOn(conn, finalSql, params, timeout, evict) }

        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")

        // The secret's URL, with the database percent-encoded into its path
        // when it names none: the name can never add connection settings.
        val jdbcUrl = PostgresSql.jdbcUrl(secrets.jdbcUrl, effectiveDb)

        // Pooled: this is the interactive path where per-request DriverManager
        // connections hurt most. Connect/login timeouts live in PostgresPool.
        PostgresPool.withEvictableConnection(jdbcUrl, secrets.username, secrets.password) { (conn, evict) =>
            runOn(conn, finalSql, params, timeout, evict)
        }
    }

    /** Runs `finalSql` on `conn` inside [[inReadOnlyTransaction]] and reads
      * every row. The caller's own SQL (no params) stays a plain statement,
      * so a `?` in it (e.g. a jsonb operator) is not read as a placeholder;
      * a server-built statement with params is a prepared one. */
    private[util] def runOn(
        conn: Connection,
        finalSql: String,
        params: Seq[String],
        timeoutSeconds: Int,
        evict: Connection => Unit
    ): java.util.List[java.util.Map[String, Any]] =
        inReadOnlyTransaction(conn, timeoutSeconds, evict) { c =>
            val prepared = if (params.isEmpty) None else Some(c.prepareStatement(finalSql))
            val stmt: java.sql.Statement = prepared.getOrElse(c.createStatement())
            try {
                prepared.foreach(ps => params.zipWithIndex.foreach { case (p, i) => ps.setString(i + 1, p) })
                // Client-side cancel as well as the server-side statement_timeout.
                stmt.setQueryTimeout(timeoutSeconds)
                val rs = prepared.map(_.executeQuery()).getOrElse(stmt.executeQuery(finalSql))
                try {
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
                    logger.info("Query returned " + results.size() + " rows")
                    results
                } finally rs.close()
            } finally stmt.close()
        }

    /** Runs `body` on `conn` inside a transaction the database treats as
      * read-only, so the server refuses any write whatever the statement:
      * autocommit off and read-only on, then [[transactionSetup]] (read-only
      * transaction, server-side statement timeout), then `body`. Afterwards,
      * on success and on any failure, the transaction is rolled back (never
      * committed) and the connection is put back in the state the pool hands
      * out: read-only off, autocommit on, then [[SessionReset]]. The order
      * matters: autocommit is switched back on only after a successful
      * rollback, since turning it on with a transaction open would commit it.
      * If any restore step fails the connection is evicted (`evict`) rather
      * than reused. A restore failure never masks `body`'s own outcome. */
    private[util] def inReadOnlyTransaction[A](conn: Connection, timeoutSeconds: Int, evict: Connection => Unit)(body: Connection => A): A = {
        var began = false
        try {
            conn.setAutoCommit(false)
            began = true
            conn.setReadOnly(true)
            val setup = conn.createStatement()
            try transactionSetup(timeoutSeconds).foreach(setup.execute)
            finally setup.close()
            body(conn)
        } finally restoreAfterQuery(conn, began, evict)
    }

    private def restoreAfterQuery(conn: Connection, began: Boolean, evict: Connection => Unit): Unit = {
        val restored =
            try {
                if (began) conn.rollback()
                conn.setReadOnly(false)
                conn.setAutoCommit(true)
                val reset = conn.createStatement()
                try reset.execute(SessionReset)
                finally reset.close()
                true
            } catch {
                case e: Exception =>
                    logger.warn("Could not restore a query connection; evicting it from the pool: " + e.getMessage)
                    false
            }
        if (!restored)
            try evict(conn)
            catch { case e: Exception => logger.warn("Could not evict a query connection: " + e.getMessage) }
    }

    private[util] def validateQuery(sql: String): Unit = {
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

        if (UnicodeEscapeQuote.findFirstIn(normalized).isDefined)
            throw new DatrisException("Unicode escape quoting (U&) is not allowed in queries")

        BLOCKED_FUNCTIONS.foreach { fn =>
            ("(?i)\\b" + fn + "\\b").r.findFirstIn(normalized).foreach { found =>
                throw new DatrisException("Query contains blocked function: " + found)
            }
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
