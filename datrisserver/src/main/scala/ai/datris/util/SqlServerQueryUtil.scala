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
    // A bounded query gets QUERY_TIMEOUT_SECONDS; `limit < 0` (every row) gets
    // UNLIMITED_QUERY_TIMEOUT_SECONDS, the Postgres value.
    private val QUERY_TIMEOUT_SECONDS = 30
    private[util] val UNLIMITED_QUERY_TIMEOUT_SECONDS = 300

    /** The timeout a query runs under: [[QUERY_TIMEOUT_SECONDS]] for a
      * bounded query, [[UNLIMITED_QUERY_TIMEOUT_SECONDS]] for `limit < 0`. */
    private[util] def timeoutSeconds(limit: Int): Int =
        if (limit < 0) UNLIMITED_QUERY_TIMEOUT_SECONDS else QUERY_TIMEOUT_SECONDS

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
        // T-SQL needs no `;` between statements. These verbs were the first
        // attempt at stopping a second statement word by word; they stay, but
        // the one-statement scan in [[singleStatementProblem]] and the
        // rolled-back transaction in [[run]] are what bound it now.
        "TRIGGER",
        "SETUSER",
        "REVERT",
        "WRITETEXT",
        "UPDATETEXT"
    )

    /** Reserved T-SQL words that only start or steer a statement of their
      * own (control flow, transactions, cursors, session settings). Refused
      * outside string literals and quoted identifiers; being reserved, none can
      * be an unquoted column name. Not here on purpose: `END` (every `CASE`
      * has one), `FETCH` (`OFFSET ... FETCH`), and the non-reserved `THROW`,
      * `RECEIVE`, `SEND`, `GET`, `MOVE`, which are legal column names; the
      * one-statement scan and the rollback cover those. */
    private[util] val STATEMENT_KEYWORDS: Seq[String] = Seq(
        "SET",
        "BEGIN",
        "COMMIT",
        "ROLLBACK",
        "SAVE",
        "WHILE",
        "IF",
        "GOTO",
        "PRINT",
        "RAISERROR",
        "CHECKPOINT",
        "OPEN",
        "CLOSE",
        "DEALLOCATE",
        "RETURN",
        "BREAK",
        "CONTINUE",
        "READTEXT"
    )

    private val OneStatementOnly = "Only one statement is allowed in a query"

    // Words that may stand right before a second SELECT of the same statement.
    private val SetOperators = Set("UNION", "EXCEPT", "INTERSECT")

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

    /** Runs `sql` with autocommit off and always rolls the transaction back,
      * on success and on failure, so anything transactional a statement did is
      * undone; never commits. The statement runs under [[timeoutSeconds]]
      * whatever the limit. On success a failed rollback is raised (the undo
      * cannot be confirmed); after a failure it is attached to that failure. */
    private[util] def run(conn: java.sql.Connection, sql: String, unlimited: Boolean, effectiveLimit: Int): SqlServerQueryResult = {
        conn.setAutoCommit(false)
        var failure: Throwable = null
        try {
            val stmt = conn.createStatement()
            try {
                stmt.setQueryTimeout(timeoutSeconds(if (unlimited) -1 else effectiveLimit))
                if (!unlimited) stmt.setMaxRows(effectiveLimit)
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
        } catch {
            case t: Throwable =>
                failure = t
                throw t
        } finally {
            try conn.rollback()
            catch {
                case e: SQLException =>
                    if (failure != null) failure.addSuppressed(e)
                    else throw e
            }
            Try(conn.setAutoCommit(true))
        }
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
            // Not `\b` in front: T-SQL ends a number at its first letter, so a
            // keyword glued to a digit is still the keyword.
            val pattern = ("(?i)(?<![A-Za-z_@#$])" + keyword + "\\b").r
            if (pattern.findFirstIn(normalized).isDefined)
                throw new DatrisException("Query contains blocked keyword: " + keyword)
        }

        singleStatementProblem(normalized).foreach(problem => throw new DatrisException(problem))
    }

    /** Why `sql` is not exactly one read-only statement, or None when it is.
      * Pure; scans outside `'...'` literals and `[...]` / `"..."` identifiers:
      *
      *  - an unterminated literal or quoted identifier is refused;
      *  - a `)` with no matching `(`, or a `(` never closed, is refused (a stray
      *    `)` would close the `SELECT TOP (n) * FROM (...)` wrapper);
      *  - a [[STATEMENT_KEYWORDS]] word, or the phrase `NEXT VALUE FOR`, is
      *    refused as `Query contains blocked keyword: <WORD>`;
      *  - within one parenthesis level, a `SELECT` after the first must
      *    directly follow `UNION`, `UNION ALL`, `EXCEPT` or `INTERSECT`;
      *    otherwise it starts a second statement.
      *
      * Statements cannot sit inside parentheses, so a subquery or a CTE body
      * starts a fresh level with its own first `SELECT`. */
    private[util] def singleStatementProblem(sql: String): Option[String] =
        scan(sql) match {
            case Left(problem) => Some(problem)
            case Right(tokens) => blockedWord(tokens).orElse(secondSelect(tokens))
        }

    private def blockedWord(tokens: Vector[Token]): Option[String] = {
        val words = tokens.collect { case Word(w, _, _) => w.toUpperCase(Locale.ROOT) }
        val word = STATEMENT_KEYWORDS.find(words.contains)
        // NEXT VALUE FOR: three words in a row, nothing between them.
        lazy val nextValueFor = tokens.sliding(3).exists {
            case Seq(Word(a, _, _), Word(b, _, _), Word(c, _, _)) =>
                a.equalsIgnoreCase("NEXT") && b.equalsIgnoreCase("VALUE") && c.equalsIgnoreCase("FOR")
            case _ => false
        }
        word.orElse(if (nextValueFor) Some("NEXT VALUE FOR") else None).map("Query contains blocked keyword: " + _)
    }

    /** One frame per parenthesis level: whether a SELECT has been seen at that
      * level, and the level's last two tokens (for `UNION ALL`). */
    private final class Level {
        var sawSelect = false
        var prev: Token = Other
        var prevPrev: Token = Other
        def note(t: Token): Unit = { prevPrev = prev; prev = t }
        def afterSetOperator: Boolean = (prev, prevPrev) match {
            case (Word(p, _, _), _) if SetOperators.contains(p.toUpperCase(Locale.ROOT)) => true
            case (Word(all, _, _), Word(union, _, _)) => all.equalsIgnoreCase("ALL") && union.equalsIgnoreCase("UNION")
            case _ => false
        }
    }

    private def secondSelect(tokens: Vector[Token]): Option[String] = {
        var levels = List(new Level)
        val found = tokens.exists {
            case Open =>
                levels.head.note(Open)
                levels = new Level :: levels
                false
            case Close =>
                levels = levels.tail // scan has checked the parentheses balance
                levels.head.note(Close)
                false
            case t @ Word(w, _, _) =>
                val l = levels.head
                val second = w.equalsIgnoreCase("SELECT") && l.sawSelect && !l.afterSetOperator
                if (w.equalsIgnoreCase("SELECT")) l.sawSelect = true
                l.note(t)
                second
            case t =>
                levels.head.note(t)
                false
        }
        if (found) Some(OneStatementOnly) else None
    }

    private sealed trait Token
    private case object Open extends Token
    private case object Close extends Token
    private case object Quoted extends Token
    private case object Other extends Token
    private final case class Word(text: String, start: Int, depth: Int) extends Token

    /** Tokens of `sql`: words (letters, digits, `_`, starting at a letter,
      * digit or `_`) with their start offset and parenthesis depth, `(` and
      * `)`, one token per `'...'` literal or `[...]` / `"..."` identifier, and
      * one per other character that is not whitespace. Left when a literal or
      * identifier is unterminated or the parentheses do not balance. Depth is
      * reported as it is, never clamped. */
    private def scan(sql: String): Either[String, Vector[Token]] = {
        val out = Vector.newBuilder[Token]
        var depth = 0
        var i = 0
        val n = sql.length
        def skipQuoted(close: Char): Boolean = {
            i += 1
            while (i < n) {
                if (sql.charAt(i) == close) {
                    if (i + 1 < n && sql.charAt(i + 1) == close) i += 2 // doubled = escaped
                    else { i += 1; return true }
                } else i += 1
            }
            false
        }
        while (i < n) {
            val c = sql.charAt(i)
            if (c == '\'' || c == '"' || c == '[') {
                val close = if (c == '[') ']' else c
                if (!skipQuoted(close))
                    return Left("Query has an unterminated string literal or quoted identifier")
                out += Quoted
            } else if (c == '(') { depth += 1; i += 1; out += Open }
            else if (c == ')') {
                depth -= 1
                if (depth < 0) return Left("Query has a closing parenthesis with no matching opening one")
                i += 1
                out += Close
            } else if (c.isDigit) {
                // A number. T-SQL ends it at the first character that cannot
                // continue it, so a letter right after it starts a new word
                // (a keyword included): refuse that outright.
                val start = i
                if (c == '0' && i + 1 < n && (sql.charAt(i + 1) == 'x' || sql.charAt(i + 1) == 'X')) {
                    i += 2
                    while (i < n && isHexDigit(sql.charAt(i))) i += 1
                } else {
                    while (i < n && sql.charAt(i).isDigit) i += 1
                    if (i < n && sql.charAt(i) == '.') { i += 1; while (i < n && sql.charAt(i).isDigit) i += 1 }
                    if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
                        val sign = if (i + 1 < n && (sql.charAt(i + 1) == '+' || sql.charAt(i + 1) == '-')) 1 else 0
                        if (i + 1 + sign < n && sql.charAt(i + 1 + sign).isDigit) {
                            i += 1 + sign
                            while (i < n && sql.charAt(i).isDigit) i += 1
                        }
                    }
                }
                if (i < n && isWordChar(sql.charAt(i)))
                    return Left("Query has a number joined to a word; put a space between them")
                out += Word(sql.substring(start, i), start, depth)
            } else if (c.isLetter || c == '_') {
                val start = i
                while (i < n && isWordChar(sql.charAt(i))) i += 1
                out += Word(sql.substring(start, i), start, depth)
            } else {
                if (!c.isWhitespace) out += Other
                i += 1
            }
        }
        if (depth != 0) Left("Query has an opening parenthesis that is never closed")
        else Right(out.result())
    }

    private def startsWithWord(upper: String, word: String): Boolean =
        upper.startsWith(word) && (upper.length == word.length || !isWordChar(upper.charAt(word.length)))

    private def isHexDigit(c: Char): Boolean = c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

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
     *  offsets, skipping `'...'` literals, `[...]` and `"..."` identifiers.
     *  Text that does not scan (unterminated quote, unbalanced parentheses)
     *  has no words: [[validateQuery]] refuses it before a limit is applied. */
    private def topLevelWords(sql: String): Seq[(String, Int)] =
        scan(sql) match {
            case Right(tokens) => tokens.collect { case Word(w, start, 0) => (w, start) }
            case Left(_) => Seq.empty
        }
}
