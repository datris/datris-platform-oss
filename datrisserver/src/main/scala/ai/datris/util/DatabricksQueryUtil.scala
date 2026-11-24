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
        "identifier"
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
    }

    private def appendLimitIfNeeded(sql: String, limit: Int): String = {
        if (!QueryTextCase.upper(sql).contains("LIMIT"))
            sql + " LIMIT " + limit
        else
            sql
    }
}
