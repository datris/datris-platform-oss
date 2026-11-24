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
      * changes: java_method, reflect. Not confirmed: none was executed.
      *
      * Vendor check, 2026-11-24 (same pages, fetched, not run): read_statestore
      * and read_state_metadata read a streaming checkpoint location, and
      * cloud_files_state an ingest checkpoint directory, all on Databricks
      * SQL; list_secrets returns the secret scope and key names the identity
      * can see (no values; Databricks SQL, preview). Left accepted:
      * event_log (callable only by the owner of the table, names a table or
      * pipeline), table_changes (a named table under SELECT), range, explode
      * and the other generators. Reads of a file location in place of a
      * table are refused by [[pathRead]], not here. */
    private[util] val BLOCKED_FUNCTIONS: Seq[String] = Seq(
        "http_request",
        "remote_query",
        "ai_[\\p{L}\\p{N}_]+",
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
        "identifier",
        "read_statestore",
        "read_state_metadata",
        "cloud_files_state",
        "list_secrets"
    )

    // [[firstCall]] tries an entry only at a backtick or at the entry's
    // first character, compared in lower case: an entry that starts any other
    // way (a group, a class, an upper-case letter) would never be tried and
    // would refuse nothing, so it fails here when the object loads.
    require(
        BLOCKED_FUNCTIONS.forall(fn => fn.nonEmpty && fn.charAt(0) >= 'a' && fn.charAt(0) <= 'z'),
        "every DatabricksQueryUtil.BLOCKED_FUNCTIONS entry must start with a lower-case ASCII letter"
    )

    // Between a called name and its `(`: whitespace taken widely (ASCII,
    // Unicode separators, control and format characters; the Spark lexer
    // hides Unicode space separators too). Wider than the store errs toward
    // seeing a call. Possessive, and only name characters come before it, so
    // a run of gap characters is read from one match start at most.
    private val GapBeforeParen = "[\\s\\p{Z}\\p{Cc}\\p{Cf}]*+"

    /** Each [[BLOCKED_FUNCTIONS]] entry's first letter and its call pattern,
      * anchored where it is tried ([[firstCall]] decides where that is). */
    private lazy val BlockedFunctionPatterns: Seq[(Char, java.util.regex.Pattern)] =
        BLOCKED_FUNCTIONS.map(fn => (fn.charAt(0), ("(?i)`?(" + fn + ")`?" + GapBeforeParen + "\\(").r.pattern))

    /** Whether code point `cp` continues a name: a letter, a number or `_`,
      * ASCII or not (the class the `ai_` entry reads a name with). */
    private[util] def continuesName(cp: Int): Boolean =
        cp == '_' || Character.isLetter(cp) || (Character.getType(cp) match {
            case Character.DECIMAL_DIGIT_NUMBER | Character.LETTER_NUMBER | Character.OTHER_NUMBER => true
            case _ => false
        })

    /** The name in the leftmost call `pattern` finds in `text`, trying it
      * only where a call can start: at a backtick or at the entry's first
      * letter, and only where the code point before does not continue a name
      * ([[continuesName]]; read in code, by code point, so a letter outside
      * the Basic Multilingual Plane is one letter). Spark takes only ASCII
      * letters, digits and `_` into an unquoted name, so a listed name
      * directly after a non-ASCII letter or number is not a call to it; in
      * backticks it still is, the backtick being the boundary. Inside one
      * name only its first character is tried, so a long name is read from
      * one start (two with a backtick in front) and the search takes time in
      * proportion to the text's length. */
    private def firstCall(text: String, first: Char, pattern: java.util.regex.Pattern): Option[String] = {
        val m = pattern.matcher(text)
        val n = text.length
        var p = 0
        while (p < n) {
            val c = text.charAt(p)
            if (
                (c == '`' || Character.toLowerCase(c) == first) &&
                (p == 0 || !continuesName(text.codePointBefore(p)))
            ) {
                m.region(p, n)
                if (m.lookingAt()) return Some(m.group(1))
            }
            p += 1
        }
        None
    }

    /** The first [[BLOCKED_FUNCTIONS]] name `sql` calls, in lower case
      * (`IDENTIFIER` in upper case). The text is lower-cased with Unicode
      * rules first, as function lookup folds names, so a letter that only
      * those rules fold to an ASCII one (the Kelvin sign) does not hide a
      * name; `(?i)` alone folds ASCII only. */
    private[util] def blockedFunction(sql: String): Option[String] = {
        val lower = QueryTextCase.lower(sql)
        BlockedFunctionPatterns.view
            .flatMap { case (first, pattern) => firstCall(lower, first, pattern) }
            .headOption
            .map(name => if (name.equalsIgnoreCase("identifier")) "IDENTIFIER" else name)
    }

    /** The format words a table name may start with to read files by path
      * (`json.`/p``), compared in ASCII lower case. */
    private val PathFormatWords: Set[String] =
        Set("json", "csv", "avro", "parquet", "orc", "binaryfile", "text", "delta", "xml")
    private val LongestPathFormatWord = PathFormatWords.map(_.length).max

    /** Whether code point `cp` may stand between two parts of a name: whitespace
      * taken widely, the set [[GapBeforeParen]] reads (ASCII whitespace,
      * Unicode separators, control and format characters). */
    private def gapCodePoint(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || (Character.getType(cp) match {
            case Character.CONTROL | Character.FORMAT => true
            case _ => false
        })

    /** Whether `sql` names a file location in place of a table: a two-part
      * name whose first part is not preceded by `.` (gap characters between
      * allowed) and whose second part is quoted, where either the first part
      * is a format word ([[PathFormatWords]], bare or quoted, any ASCII letter
      * case) and the second part holds `/` or `:`, or the second part starts
      * with `/` or holds `:/` whatever the first part. Gap characters
      * ([[gapCodePoint]]) may stand on both sides of the dot. A further
      * `.part` after the second part does not matter: the name is refused
      * either way.
      *
      * Vendor check, 2026-11-24 (docs.databricks.com/aws/en, "Names" and
      * "Work with files on Databricks", fetched, not run): a table name may be
      * `{ file_format | `file_format` } . `path``, the format one of json, csv,
      * avro, parquet, orc, binaryFile, text, delta (case insensitive), and a
      * path as a table needs backticks; volume paths, cloud URIs, `file:` and
      * DBFS mount paths are all shown read this way. UNCONFIRMED: xml as a
      * format word on a SQL warehouse (kept in the list); whether a backticked
      * name with no `/` falls back to a path (`delta.`events`` is not refused).
      *
      * The text is read twice, each time once from start to end with a fixed
      * amount of state, so the cost is in proportion to its length whatever
      * it holds:
      *  - as Spark lexes it: `'...'` and `"..."` are string literals (a
      *    backslash escapes the next character; `r'...'` / `r"..."` are raw,
      *    with no escapes), so a backtick inside a literal cannot shift where a
      *    backticked name starts. A `"..."` token also counts as a quoted name,
      *    as it is one when the session reads double quotes as identifiers; its
      *    escapes are not decoded, so one holding a backslash counts as both a
      *    format word and a path;
      *  - with quote characters taken as ordinary characters, so a path written
      *    inside a string literal is refused too, as the function list behaves.
      * In both, a backticked name runs to the next backtick that is not one of
      * a doubled pair (to the end of the text when there is none), and a bare
      * name is a run of code points that continue a name ([[continuesName]]),
      * so a format word directly after or before a letter, digit or `_`
      * (ASCII or not) is part of a longer name. Pure: takes the text as
      * [[validateQuery]] holds it. */
    private[util] def pathRead(sql: String): Boolean =
        sql != null && (pathReadScan(sql, literals = true) || pathReadScan(sql, literals = false))

    private def asciiLower(c: Char): Char = if (c >= 'A' && c <= 'Z') (c + 32).toChar else c

    /** One pass of [[pathRead]]; `literals` chooses whether quote characters
      * open string literals. Keeps only the kinds of the last three tokens
      * (gaps are not tokens) and whether the latest name was a format word. */
    private def pathReadScan(sql: String, literals: Boolean): Boolean = {
        val Other = 0
        val Dot = 1
        val Name = 2
        val n = sql.length
        var last = Other // the latest token
        var before = Other // the one before it
        var beforeThat = Other // the one before that
        var lastNameFormat = false
        // Facts about the quoted name just read
        var qFormat = false
        var qSlash = false
        var qColon = false
        var qStartsSlash = false
        var qColonSlash = false

        def push(kind: Int): Unit = { beforeThat = before; before = last; last = kind }

        /** Reads a quoted name opened at `open` by `q`; `doubled` says whether
          * a doubled `q` is part of it (backticks), else a backslash escapes the
          * next character (double-quoted strings), unless `raw`. Sets the q*
          * facts; returns the index after the name. */
        def quoted(open: Int, q: Char, doubled: Boolean, raw: Boolean): Int = {
            qFormat = false; qSlash = false; qColon = false; qStartsSlash = false; qColonSlash = false
            val word = new java.lang.StringBuilder(LongestPathFormatWord + 1)
            var wordFits = true
            var sawEscape = false
            var prev: Char = 0
            var first = true
            var i = open + 1
            var done = false
            while (!done && i < n) {
                val c = sql.charAt(i)
                if (c == q && doubled && i + 1 < n && sql.charAt(i + 1) == q) {
                    if (wordFits) { if (word.length < LongestPathFormatWord) word.append(q) else wordFits = false }
                    prev = q; first = false; i += 2
                } else if (c == q) {
                    done = true; i += 1
                } else if (c == '\\' && !doubled && !raw) {
                    sawEscape = true; i += 2
                } else {
                    if (c == '/') { qSlash = true; if (first) qStartsSlash = true; if (prev == ':') qColonSlash = true }
                    if (c == ':') qColon = true
                    if (wordFits) { if (word.length < LongestPathFormatWord) word.append(asciiLower(c)) else wordFits = false }
                    prev = c; first = false; i += 1
                }
            }
            qFormat = (wordFits && PathFormatWords.contains(word.toString)) || sawEscape
            if (sawEscape) { qSlash = true; qColon = true; qStartsSlash = true; qColonSlash = true }
            math.min(i, n)
        }

        /** After a quoted name: whether it is the path part of a refused name. */
        def refusedHere(): Boolean =
            last == Dot && before == Name && beforeThat != Dot &&
                ((lastNameFormat && (qSlash || qColon)) || qStartsSlash || qColonSlash)

        var p = 0
        while (p < n) {
            val cp = sql.codePointAt(p)
            val width = Character.charCount(cp)
            if (gapCodePoint(cp)) p += width
            else if (cp == '.') { push(Dot); p += 1 }
            else if (cp == '`' || (cp == '"' && literals)) {
                p = quoted(p, cp.toChar, doubled = cp == '`', raw = false)
                if (refusedHere()) return true
                push(Name); lastNameFormat = qFormat
            } else if (cp == '\'' && literals) {
                // a string literal: opaque, backslash escapes
                var i = p + 1
                var done = false
                while (!done && i < n) {
                    val c = sql.charAt(i)
                    if (c == '\\') i += 2
                    else { i += 1; if (c == '\'') done = true }
                }
                p = math.min(i, n)
                push(Other)
            } else if (continuesName(cp)) {
                val start = p
                var end = p
                while (end < n && continuesName(sql.codePointAt(end))) end += Character.charCount(sql.codePointAt(end))
                val len = end - start
                val rawQuote = if (literals && len == 1 && end < n && (cp == 'r' || cp == 'R')) sql.charAt(end) else 0.toChar
                if (rawQuote == '\'') {
                    // a raw string literal: opaque, no escapes
                    var i = end + 1
                    while (i < n && sql.charAt(i) != '\'') i += 1
                    p = math.min(i + 1, n)
                    push(Other)
                } else if (rawQuote == '"') {
                    p = quoted(end, '"', doubled = false, raw = true)
                    if (refusedHere()) return true
                    push(Name); lastNameFormat = qFormat
                } else {
                    var fmt = len <= LongestPathFormatWord
                    if (fmt) {
                        val sb = new java.lang.StringBuilder(len)
                        var i = start
                        while (i < end) { sb.append(asciiLower(sql.charAt(i))); i += 1 }
                        fmt = PathFormatWords.contains(sb.toString)
                    }
                    p = end
                    push(Name); lastNameFormat = fmt
                }
            } else { push(Other); p += width }
        }
        false
    }

    case class DatabricksQueryResult(sql: String, results: java.util.List[java.util.Map[String, Any]])

    /** Run `sql` (or, when None, a preview of the pipeline's destination table)
     *  against the Databricks workspace the named pipeline loads into. */
    def query(pipelineName: String, sql: Option[String], limit: Int = DEFAULT_LIMIT): DatabricksQueryResult = {
        // The length limit counts the caller's text as received, before the
        // trim below and before the pipeline is read.
        sql.foreach(QueryTextLimit.check)
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
            val u = QueryTextCase.upperPrefix(normalized, 16)
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
        QueryTextLimit.check(sql)
        if (sql == null || sql.trim.isEmpty)
            throw new DatrisException("SQL query cannot be empty")

        val normalized = sql.trim.stripSuffix(";")

        // Read-only allowlist: SELECT/WITH for data, SHOW/DESCRIBE for metadata
        // discovery (catalogs, schemas, tables, columns).
        // Only the start is tested: upper-case just enough of it (QueryTextCase).
        val upper = QueryTextCase.upperPrefix(normalized, 16)
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
            // PostgresQueryUtil.WordStart: `\b` in front, tried only where a
            // word character follows, so its reading back over combining
            // marks happens at most once per run of them.
            val pattern = ("(?i)" + PostgresQueryUtil.WordStart + keyword + "\\b").r
            if (pattern.findFirstIn(normalized).isDefined)
                throw new DatrisException("Query contains blocked keyword: " + keyword)
        }

        blockedFunction(normalized).foreach(fn => throw new DatrisException("Query contains blocked function: " + fn))

        // No part of the caller's path is echoed.
        if (pathRead(normalized))
            throw new DatrisException("Queries cannot read from a file path; name a table instead")
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        if (!QueryTextCase.upper(sql).contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
