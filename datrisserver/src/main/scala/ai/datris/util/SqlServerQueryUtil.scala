package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.sql.SQLException
import java.util.Locale
import scala.util.Try

/** Read-only queries against the SQL Server / Azure SQL database a pipeline
 *  loads into. Pipeline-scoped like [[SnowflakeQueryUtil]]: the pipeline name
 *  selects the `credentialsSecret`, host and database, so credentials never
 *  leave the server, and every `authMethod` [[SqlServerConnectionUtil]]
 *  supports works here unchanged. Only `SELECT` and `WITH` statements run;
 *  metadata discovery goes through `INFORMATION_SCHEMA` views, which are
 *  plain SELECTs. */
object SqlServerQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val DEFAULT_LIMIT = 100

    // Bounds a hung request thread and runaway load on the customer's server.
    // Not applied when the caller explicitly asks for unlimited (limit < 0).
    private val QUERY_TIMEOUT_SECONDS = 30

    // Write/DDL/exec verbs, ad hoc remote-server access (OPENROWSET,
    // OPENDATASOURCE, OPENQUERY) and statements that change the session. INTO
    // blocks `SELECT ... INTO`, which creates a table; USE would re-point the
    // session's default database. Three-part names (`otherdb.dbo.t`,
    // `master.sys.sql_logins`) are NOT blocked: what a query can read beyond the
    // pipeline's database is bounded only by the login's grants, which is why
    // the destination docs call for a least-privilege login.
    private val BLOCKED_KEYWORDS = Seq(
        "INSERT",
        "UPDATE",
        "DELETE",
        "DROP",
        "ALTER",
        "CREATE",
        "TRUNCATE",
        "GRANT",
        "REVOKE",
        "DENY",
        "EXEC",
        "EXECUTE",
        "MERGE",
        "INTO",
        "USE",
        "BULK",
        "OPENROWSET",
        "OPENDATASOURCE",
        "OPENQUERY",
        "DBCC",
        "BACKUP",
        "RESTORE",
        "SHUTDOWN",
        "KILL",
        "RECONFIGURE",
        "WAITFOR",
        "DECLARE",
        // T-SQL needs no `;` between statements, so `SELECT 1 DISABLE TRIGGER ...`
        // is two statements: block the verbs that could ride along that way.
        "TRIGGER",
        "SETUSER",
        "REVERT",
        "WRITETEXT",
        "UPDATETEXT"
    )

    /** SQL Server errors raised when the derived-table wrapper itself is the
     *  problem, not the caller's SQL: 8155 (a result column has no name, e.g.
     *  `COUNT(*)`), 8156 (two result columns share a name, e.g. a join's
     *  `a.id, b.id`), 1086 (`FOR XML`/`FOR JSON` inside a derived table).
     *  On these the query runs unwrapped with the JDBC row cap instead. */
    private val WrapperErrorCodes = Set(8155, 8156, 1086)

    case class SqlServerQueryResult(sql: String, results: java.util.List[java.util.Map[String, Any]])

    /** Run `sql` (or, when None, a preview of the pipeline's destination table)
     *  against the SQL Server database the named pipeline loads into. */
    def query(pipelineName: String, sql: Option[String], limit: Int = DEFAULT_LIMIT): SqlServerQueryResult = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (config == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)
        val db = if (config.destination != null) config.destination.database else null
        if (db == null || !db.useSqlServer)
            throw new DatrisException("Pipeline '" + pipelineName + "' does not have a SQL Server destination. " +
                "query_sqlserver only works for pipelines with destination.database.useSqlServer=true; " +
                "use the query tool matching this pipeline's destination instead.")

        val requested = sql.map(_.trim).filter(_.nonEmpty).getOrElse(defaultQuery(db))
        validateQuery(requested)

        val unlimited = limit < 0
        val effectiveLimit = if (unlimited) -1 else if (limit > 0) limit else DEFAULT_LIMIT
        val normalized = requested.trim.stripSuffix(";").trim
        val finalSql = if (unlimited) normalized else applyLimit(normalized, effectiveLimit)

        logger.info("Executing read-only SQL Server query for pipeline '" + pipelineName + "': " + finalSql)

        SqlServerConnectionUtil.withConnection(db) { conn =>
            try run(conn, finalSql, unlimited, effectiveLimit)
            catch {
                case e: SQLException if !unlimited && finalSql != normalized && WrapperErrorCodes.contains(e.getErrorCode) =>
                    logger.info("SQL Server rejected the TOP wrapper (error " + e.getErrorCode + "); running the query unwrapped with a row cap")
                    run(conn, normalized, unlimited, effectiveLimit)
            }
        }
    }

    private def run(conn: java.sql.Connection, sql: String, unlimited: Boolean, effectiveLimit: Int): SqlServerQueryResult = {
        val stmt = conn.createStatement()
        try {
            if (!unlimited) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS)
                stmt.setMaxRows(effectiveLimit)
            }
            val rs = stmt.executeQuery(sql)
            try {
                val metaData = rs.getMetaData
                val columnCount = metaData.getColumnCount
                val results = new java.util.ArrayList[java.util.Map[String, Any]]()
                val maxRows = if (unlimited) Int.MaxValue else effectiveLimit
                while (results.size() < maxRows && rs.next()) {
                    val row = new java.util.LinkedHashMap[String, Any]()
                    for (i <- 1 to columnCount) {
                        val label = Option(metaData.getColumnLabel(i)).filter(_.nonEmpty).getOrElse("column" + i)
                        row.put(label, jsonValue(rs.getObject(i)))
                    }
                    results.add(row)
                }
                logger.info("SQL Server query returned " + results.size() + " rows")
                SqlServerQueryResult(sql, results)
            } finally Try(rs.close())
        } finally Try(stmt.close())
    }

    /** JSON-friendly cell values: temporal and driver-specific types (e.g.
     *  DATETIMEOFFSET) as their string form, binary as `0x` hex. */
    private def jsonValue(v: Any): Any = v match {
        case null => null
        case d: java.sql.Date => d.toString
        case t: java.sql.Timestamp => t.toString
        case t: java.sql.Time => t.toString
        case b: Array[Byte] => "0x" + b.map(x => f"${x & 0xff}%02X").mkString
        case s: String => s
        case n: java.lang.Number => n
        case b: java.lang.Boolean => b
        case other => other.toString
    }

    /** `SELECT * FROM schema.table` for the pipeline's destination. */
    private[util] def defaultQuery(db: Database): String =
        "SELECT * FROM " + SqlServerConnectionUtil.qualifiedTable(db)

    private[util] def validateQuery(sql: String): Unit = {
        if (sql == null || sql.trim.isEmpty)
            throw new DatrisException("SQL query cannot be empty")

        val normalized = sql.trim.stripSuffix(";").trim
        val upper = normalized.toUpperCase(Locale.ROOT)
        val allowed = startsWithWord(upper, "SELECT") || startsWithWord(upper, "WITH")
        if (!allowed)
            throw new DatrisException(
                "Only read-only statements are allowed: SELECT (WITH/CTE); for metadata, SELECT from INFORMATION_SCHEMA views"
            )

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

    private def startsWithWord(upper: String, word: String): Boolean =
        upper.startsWith(word) && (upper.length == word.length || !isWordChar(upper.charAt(word.length)))

    private def isWordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '@' || c == '#' || c == '$'

    /** Cap the rows `sql` returns.
     *
     *  - With a top-level `ORDER BY` (and no `OFFSET` or `TOP` yet):
     *    `... ORDER BY x OFFSET 0 ROWS FETCH NEXT <limit> ROWS ONLY`.
     *  - With a top-level `OFFSET` already: unchanged (the caller paged it).
     *  - Otherwise (including `SELECT TOP ... ORDER BY`, since SQL Server
     *    refuses TOP and OFFSET in one query) the statement is wrapped:
     *    `SELECT TOP (<limit>) * FROM (<sql>) q`. For a `WITH` statement the
     *    CTE definitions stay in front and only the final statement is
     *    wrapped: `WITH c AS (...) SELECT TOP (<limit>) * FROM (<final>) q`
     *    (a CTE cannot sit inside a derived table).
     *
     *  "Top-level" means outside parentheses, string literals and quoted
     *  identifiers, so an `ORDER BY` inside `OVER (...)` or a subquery does not
     *  count. */
    private[util] def applyLimit(sql: String, limit: Int): String = {
        val normalized = sql.trim.stripSuffix(";").trim
        val words = topLevelWords(normalized)
        val upperWords = words.map { case (w, _) => w.toUpperCase(Locale.ROOT) }
        val hasOrderBy = upperWords.sliding(2).exists(p => p.size == 2 && p(0) == "ORDER" && p(1) == "BY")
        val hasOffset = upperWords.contains("OFFSET")
        // TOP in the final statement's select list: before its first top-level FROM.
        val hasTop = upperWords.takeWhile(_ != "FROM").contains("TOP")

        if (hasOffset) normalized
        else if (hasOrderBy && !hasTop) normalized + " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY"
        else if (upperWords.headOption.contains("WITH")) {
            // The final statement starts at the first top-level SELECT: every
            // CTE body sits inside parentheses.
            words.find { case (w, _) => w.equalsIgnoreCase("SELECT") } match {
                case Some((_, pos)) =>
                    normalized.substring(0, pos) + "SELECT TOP (" + limit + ") * FROM (" + normalized.substring(pos) + ") q"
                case None => normalized
            }
        } else "SELECT TOP (" + limit + ") * FROM (" + normalized + ") q"
    }

    /** Words (letters, digits, `_`) at parenthesis depth 0, with their start
     *  offsets, skipping `'...'` literals, `[...]` and `"..."` identifiers. */
    private def topLevelWords(sql: String): Seq[(String, Int)] = {
        val out = Seq.newBuilder[(String, Int)]
        var depth = 0
        var i = 0
        val n = sql.length
        def skipQuoted(close: Char): Unit = {
            i += 1
            var done = false
            while (i < n && !done) {
                if (sql.charAt(i) == close) {
                    if (i + 1 < n && sql.charAt(i + 1) == close) i += 2 // doubled = escaped
                    else { i += 1; done = true }
                } else i += 1
            }
        }
        while (i < n) {
            val c = sql.charAt(i)
            if (c == '\'') skipQuoted('\'')
            else if (c == '"') skipQuoted('"')
            else if (c == '[') skipQuoted(']')
            else if (c == '(') { depth += 1; i += 1 }
            else if (c == ')') { depth = math.max(0, depth - 1); i += 1 }
            else if (c.isLetterOrDigit || c == '_') {
                val start = i
                while (i < n && isWordChar(sql.charAt(i))) i += 1
                if (depth == 0) out += ((sql.substring(start, i), start))
            } else i += 1
        }
        out.result()
    }
}
