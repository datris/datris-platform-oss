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
      * regex, refused as a whole word anywhere in the text. Every `SYSTEM$`
      * name is refused except [[SYSTEM_FUNCTION_ALLOWLIST]], so a name added
      * by Snowflake later is refused too; nothing else is named `SYSTEM$...`.
      * `NEXTVAL` (`<sequence>.NEXTVAL`) and `GETNEXTVAL` (the table-function
      * form) advance a sequence. `IDENTIFIER` is refused only where it is
      * called (the name, then any mix of whitespace and `//` line comments,
      * then `(`; `--` and block comments are refused outright): it turns a string,
      * built any way, into a function name, so no name list could catch what
      * it calls. Matched over the whole text, string literals included, as
      * BLOCKED_KEYWORDS is. Names are reported in upper case.
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
      * reference generates new values; GETNEXTVAL is its table-function form
      * (review, same date; page not re-read). IDENTIFIER: documented for
      * function names; not confirmed that it resolves SYSTEM$ names, refused
      * regardless. Not confirmed: none of these was
      * executed, and no other SYSTEM$ name was reviewed one by one (they are
      * refused as a class). */
    private[util] val BLOCKED_FUNCTIONS: Seq[String] = Seq(
        "SYSTEM\\$[\\p{L}\\p{N}_$]+",
        "NEXTVAL",
        "GETNEXTVAL",
        "IDENTIFIER"
    )

    /** Read-only `SYSTEM$` functions a query may call. */
    private[util] val SYSTEM_FUNCTION_ALLOWLIST: Set[String] = Set(
        "SYSTEM$TYPEOF",
        "SYSTEM$CLUSTERING_INFORMATION",
        "SYSTEM$CLUSTERING_DEPTH"
    )

    /** For every position `i` of `text` (and `text.length`), where the gap
      * starting at `i` ends: the gap a called name and its `(` may have
      * between them, so a name is called when the character at the end of the
      * gap after it is `(`. The gap is whitespace and `//` line comments, in
      * any mix. Whitespace is taken widely (ASCII, Unicode separators, control
      * and format characters) and a comment ends at the first control
      * character or line or paragraph separator: where this differs from the
      * store, it errs toward seeing a call. A `(` inside a comment is not the
      * call's. Read by code point, so a character outside the Basic
      * Multilingual Plane counts as what it is. One backward pass, so the
      * whole table takes time in proportion to the text's length however many
      * names it holds and however many lines a gap runs over. */
    private[util] def gapEnds(text: String): Array[Int] = {
        val n = text.length
        val ends = new Array[Int](n + 1)
        // commentStop(i): the first position at or after i where a `//`
        // comment running through i stops.
        val commentStop = new Array[Int](n + 1)
        ends(n) = n
        commentStop(n) = n
        var i = n - 1
        while (i >= 0) {
            val cp = text.codePointAt(i)
            val next = math.min(n, i + Character.charCount(cp))
            commentStop(i) = if (endsComment(cp)) i else commentStop(next)
            ends(i) =
                if (isGapCharacter(cp)) ends(next)
                else if (cp == '/' && i + 1 < n && text.charAt(i + 1) == '/') ends(commentStop(i + 2))
                else i
            i -= 1
        }
        ends
    }

    /** Whitespace in a gap: ASCII whitespace, Unicode separators, control and
      * format characters. */
    private def isGapCharacter(cp: Int): Boolean = Character.getType(cp) match {
        case Character.SPACE_SEPARATOR | Character.LINE_SEPARATOR | Character.PARAGRAPH_SEPARATOR | Character.CONTROL |
            Character.FORMAT =>
            true
        case _ => false
    }

    /** What ends a `//` comment: a control character, a line or paragraph separator. */
    private def endsComment(cp: Int): Boolean = Character.getType(cp) match {
        case Character.CONTROL | Character.LINE_SEPARATOR | Character.PARAGRAPH_SEPARATOR => true
        case _ => false
    }

    // BLOCKED_FUNCTIONS entries refused only as calls rather than as whole words.
    private val CallOnlyFunctions = Set("IDENTIFIER")

    /** Each [[BLOCKED_FUNCTIONS]] entry with its pattern. For a
      * [[CallOnlyFunctions]] entry the pattern finds the name (optionally in
      * double quotes) and [[gapEnds]] decides whether it is called. */
    private lazy val BlockedFunctionPatterns: Seq[(Boolean, scala.util.matching.Regex)] = BLOCKED_FUNCTIONS.map { fn =>
        // The SYSTEM$ entry takes Unicode letters and digits into the name,
        // so an allowlisted name with more after it is not the allowlisted
        // one. The boundaries stay ASCII: a non-ASCII neighbour then counts
        // as a boundary, which errs toward refusing. Each match runs to the
        // end of its name and the search goes on after it, so a long name is
        // read once.
        val tail = if (fn.startsWith("SYSTEM")) "(?![\\p{L}\\p{N}_$])" else "(?![\\w$])"
        if (CallOnlyFunctions.contains(fn)) (true, ("(?i)(?<![\\w$])\"?(" + fn + ")\"?").r)
        else (false, ("(?i)(?<![\\w$])(" + fn + ")" + tail).r)
    }

    /** The [[BLOCKED_FUNCTIONS]] names `text` uses, in order, in upper case.
      * [[gapEnds]] is built at most once, and only when a call-only name is
      * found. */
    private def functionsUsed(text: String): Iterator[String] = {
        lazy val ends = gapEnds(text)
        BlockedFunctionPatterns.iterator.flatMap {
            case (callOnly, pattern) =>
                pattern
                    .findAllMatchIn(text)
                    .filter(m => !callOnly || { val e = ends(m.end); e < text.length && text.charAt(e) == '(' })
                    .map(m => QueryTextCase.upper(m.group(1)))
        }
    }

    /** The first [[BLOCKED_FUNCTIONS]] name `sql` uses, in upper case. The
      * text is searched as written and upper-cased with Unicode rules, so a
      * letter that only those rules fold to an ASCII one (a long s, a dotless
      * i) does not hide a name; `(?i)` alone folds ASCII only. The two texts'
      * lengths can differ, so each has its own [[gapEnds]]. */
    private[util] def blockedFunction(sql: String): Option[String] =
        Iterator(sql, QueryTextCase.upper(sql))
            .flatMap(functionsUsed)
            .find(name => !SYSTEM_FUNCTION_ALLOWLIST.contains(name))

    case class SnowflakeQueryResult(sql: String, results: java.util.List[java.util.Map[String, Any]])

    /** Run `sql` (or, when None, a preview of the pipeline's destination table)
     *  against the Snowflake account the named pipeline loads into. */
    def query(pipelineName: String, sql: Option[String], limit: Int = DEFAULT_LIMIT): SnowflakeQueryResult = {
        // The length limit counts the caller's text as received, before the
        // trim below and before the pipeline is read.
        sql.foreach(QueryTextLimit.check)
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
            val u = QueryTextCase.upperPrefix(normalized, 16)
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
        QueryTextLimit.check(sql)
        if (sql == null || sql.trim.isEmpty)
            throw new DatrisException("SQL query cannot be empty")

        val normalized = sql.trim.stripSuffix(";")

        // Read-only allowlist: SELECT/WITH for data, SHOW/DESCRIBE for metadata
        // discovery (databases, schemas, tables, columns).
        // Only the start is tested: upper-case just enough of it (QueryTextCase).
        val upper = QueryTextCase.upperPrefix(normalized, 16)
        val allowed = upper.startsWith("SELECT") || upper.startsWith("WITH") ||
            upper.startsWith("SHOW") || upper.startsWith("DESCRIBE") || upper.startsWith("DESC")
        if (!allowed)
            throw new DatrisException("Only read-only statements are allowed: SELECT (WITH/CTE), SHOW, and DESCRIBE")

        // Reject stacked queries (semicolons remaining after stripping trailing one)
        if (normalized.contains(";"))
            throw new DatrisException("Semicolons are not allowed in queries")

        // Reject SQL comments that could be used for obfuscation. Snowflake
        // also reads `//` as a line comment; that is not refused here (a URL
        // in a literal holds one), so the call-form check in
        // blockedFunction allows for it instead.
        if (normalized.contains("--") || normalized.contains("/*"))
            throw new DatrisException("SQL comments are not allowed in queries")

        BLOCKED_KEYWORDS.foreach { keyword =>
            // PostgresQueryUtil.WordStart: `\b` in front, tried only where a
            // word character follows, so its reading back over combining
            // marks happens at most once per run of them.
            val pattern = ("(?i)" + PostgresQueryUtil.WordStart + keyword + "\\b").r
            if (pattern.findFirstIn(normalized).isDefined)
                throw new DatrisException("Query contains blocked keyword: " + keyword)
        }

        blockedFunction(normalized).foreach(fn => throw new DatrisException("Query contains blocked function: " + fn))
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        if (!QueryTextCase.upper(sql).contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
