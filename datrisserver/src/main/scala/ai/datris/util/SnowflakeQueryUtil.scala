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
    // session at another database/role than the pipeline's config. UNDROP
    // (restores a dropped object; `\bDROP\b` does not match inside it) is a
    // second line: it can only start a statement, and a second statement is
    // refused by the semicolon and chained-statement checks.
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
        "UNDROP"
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
      * starting at `i` ends when each `//` comment is read as ending at its
      * first candidate end (a control character or a line or paragraph
      * separator). The gap is whitespace and `//` line comments, in any mix.
      * Whitespace is taken widely (ASCII, Unicode separators, control and
      * format characters). Read by code point, so a character outside the
      * Basic Multilingual Plane counts as what it is. One backward pass.
      *
      * This is one reading only. Where a comment really ends is not known
      * here (a tab or other control character may be ordinary comment text
      * to the store), so validation uses [[gapReaches]], which takes every
      * reading, this one included. */
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

    /** Where a `//` comment may end: a control character, a line or paragraph separator. */
    private def endsComment(cp: Int): Boolean = Character.getType(cp) match {
        case Character.CONTROL | Character.LINE_SEPARATOR | Character.PARAGRAPH_SEPARATOR => true
        case _ => false
    }

    /** For every position `i` of `text` (and `text.length`), whether a gap
      * starting at `i` can end at a position `t` where `isTarget(t)` holds,
      * under ANY reading of where each `//` comment in the gap ends. The gap
      * is the one [[gapEnds]] describes. A comment may end at any candidate
      * end (a control character or a line or paragraph separator, where the
      * gap goes on, since those are gap characters) or run past it; it always
      * ends at a line feed, and a comment that reaches the end of the text
      * ends the gap there with no target. Anything inside a comment (`//`,
      * `(`, `-`, `>`) is text under the readings that run past it, and a
      * token under the readings that end the comment before it; both are
      * taken. Every reading [[gapEnds]] takes is one of these, so this sees a
      * target wherever [[gapEnds]] does, and more: it errs toward refusing.
      *
      * One backward pass by code point with two booleans per position:
      * `gap(i)` (a gap starting at i reaches a target) and `comment(i)` (a
      * comment running through i, from i on, reaches one). `isTarget` must
      * only read positions after `i` from tables already built, so the whole
      * table takes time in proportion to the text's length however many
      * starts, comments or lines it holds. */
    private[util] def gapReaches(text: String, isTarget: Int => Boolean): Array[Boolean] = {
        val n = text.length
        val gap = new Array[Boolean](n + 1)
        val comment = new Array[Boolean](n + 1)
        var i = n - 1
        while (i >= 0) {
            val cp = text.codePointAt(i)
            val next = math.min(n, i + Character.charCount(cp))
            // A candidate end is itself a gap character, so ending the
            // comment there continues the gap at `next`.
            comment(i) =
                if (cp == '\n') gap(next)
                else if (endsComment(cp)) gap(next) || comment(next)
                else comment(next)
            gap(i) =
                if (isTarget(i)) true
                else if (isGapCharacter(cp)) gap(next)
                else if (cp == '/' && i + 1 < n && text.charAt(i + 1) == '/') comment(i + 2)
                else false
            i -= 1
        }
        gap
    }

    /** A target that a gap starting at `start` reaches under some reading,
      * given the [[gapReaches]] table `gap` built for the same `isTarget`; -1
      * when `gap(start)` is false. Walks forward once, guided by the table:
      * inside a comment it ends the comment at a candidate end only where the
      * gap after it still reaches a target (at a line feed it must end, and
      * the table then says the gap after it reaches one). Each step moves
      * forward, so it takes time in proportion to the text's length. Used
      * only to name what is refused. */
    private def walkToTarget(text: String, gap: Array[Boolean], isTarget: Int => Boolean, start: Int): Int = {
        val n = text.length
        if (start > n || !gap(start)) return -1
        var s = start
        var inComment = false
        var found = -1
        while (found < 0 && s < n) {
            val cp = text.codePointAt(s)
            val next = math.min(n, s + Character.charCount(cp))
            if (!inComment) {
                if (isTarget(s)) found = s
                else if (isGapCharacter(cp)) s = next
                else { s += 2; inComment = true } // the `//` that opens a comment
            } else {
                if (cp == '\n' || (endsComment(cp) && gap(next))) inComment = false
                s = next
            }
        }
        found
    }

    /** Whether `sql` holds Snowflake's flow operator (`-`, `>`, `>`), which
      * runs a further statement after the first: a `-`, then a gap, a `>`,
      * a gap and a `>`, where each gap is read as [[gapReaches]] reads it
      * (any reading of where its comments end). Anywhere in the text, string
      * literals and quoted identifiers included, so no scanner of
      * Snowflake's quoting is needed. Two [[gapReaches]] tables, built only
      * when the text holds both a `-` and a `>` (first for the last `>`, then
      * for the middle one), then constant work per `-`: linear in the text's
      * length.
      *
      * Vendor check, 2026-11-24 (docs.snowflake.com "Flow operators" and the
      * "SQL operators" overview, read, not run against an account): the
      * operator chains SQL statements, any statement may follow it, the chain
      * runs as one anonymous block and client drivers see one statement; it
      * is the only flow operator. Snowflake has no JSON operator of that
      * shape, and the lambda arrow (`-` then one `>`) cannot be followed by
      * another `>` in valid text. Not confirmed: whether the store accepts a
      * gap between the operator's characters (refused regardless), whether
      * scripting statements may follow it, and that the JDBC driver passes a
      * chain through `executeQuery`. */
    private[util] def chainedStatement(sql: String): Boolean = {
        if (sql.indexOf('-') < 0 || sql.indexOf('>') < 0) return false
        val n = sql.length
        // toLast(i): a gap from i reaches the operator's last `>`.
        val toLast = gapReaches(sql, t => sql.charAt(t) == '>')
        // toMiddle(i): a gap from i reaches a `>` that a gap then joins to a last `>`.
        val toMiddle = gapReaches(sql, t => sql.charAt(t) == '>' && toLast(t + 1))
        var i = 0
        while (i < n) {
            if (sql.charAt(i) == '-' && toMiddle(i + 1)) return true
            i += 1
        }
        false
    }

    // BLOCKED_FUNCTIONS entries refused only as calls rather than as whole words.
    private val CallOnlyFunctions = Set("IDENTIFIER")

    /** Each [[BLOCKED_FUNCTIONS]] entry with its pattern. For a
      * [[CallOnlyFunctions]] entry the pattern finds the name (optionally in
      * double quotes) and [[gapReaches]] decides whether it is called: a
      * gap after it reaches `(` under any reading of where its comments end. */
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
      * The [[gapReaches]] table is built at most once, and only when a
      * call-only name is found. */
    private def functionsUsed(text: String): Iterator[String] = {
        lazy val called = gapReaches(text, t => text.charAt(t) == '(')
        BlockedFunctionPatterns.iterator.flatMap {
            case (callOnly, pattern) =>
                pattern
                    .findAllMatchIn(text)
                    .filter(m => !callOnly || called(m.end))
                    .map(m => QueryTextCase.upper(m.group(1)))
        }
    }

    /** The first [[BLOCKED_FUNCTIONS]] name `sql` uses, in upper case. The
      * text is searched as written and upper-cased with Unicode rules, so a
      * letter that only those rules fold to an ASCII one (a long s, a dotless
      * i) does not hide a name; `(?i)` alone folds ASCII only. The two texts'
      * lengths can differ, so each has its own [[gapReaches]] table. */
    private[util] def blockedFunction(sql: String): Option[String] =
        Iterator(sql, QueryTextCase.upper(sql))
            .flatMap(functionsUsed)
            .find(name => !SYSTEM_FUNCTION_ALLOWLIST.contains(name))
            .orElse(modelOrServiceCall(sql))

    // ---- calls to a model, a search or agent service, or a model's methods ----
    //
    // These send row values to a hosted model and bill per token, run a search
    // or agent service, start a fine-tuning job, or run a trained model, and
    // on an account left at its defaults every role may call them: PUBLIC
    // holds the SNOWFLAKE.CORTEX_USER database role and the USE AI FUNCTIONS
    // privilege. As with BLOCKED_FUNCTIONS this is a second line; the role's
    // grants are the control. Every name is refused, the helpers that call no
    // model (AI_COUNT_TOKENS, SNOWFLAKE.CORTEX.COUNT_TOKENS and the SPLIT_TEXT_*
    // functions) included. Accepted: the VECTOR_* functions (arithmetic on
    // stored vectors), TO_FILE, TRY_TO_FILE, PROMPT, FL_*,
    // CORTEX_SEARCH_DATA_SCAN, CORTEX_SEARCH_REFRESH_HISTORY,
    // SNOWFLAKE.LOCAL.GET_AI_*, other SNOWFLAKE schemas such as ML, and
    // unqualified Cortex names (a query cannot set its search path to
    // SNOWFLAKE.CORTEX: USE and ALTER are refused, one statement per connection).
    //
    // Vendor check, 2026-11-24 (docs.snowflake.com/en, read, not run against an
    // account). Confirmed: the AI_ functions AI_COMPLETE, AI_CLASSIFY,
    // AI_FILTER, AI_AGG, AI_EMBED, AI_MULTI_EMBED, AI_EXTRACT, AI_SENTIMENT,
    // AI_SUMMARIZE, AI_SUMMARIZE_AGG, AI_SIMILARITY, AI_TRANSCRIBE,
    // AI_PARSE_DOCUMENT, AI_REDACT, AI_TRANSLATE, AI_COUNT_TOKENS,
    // AI_FUNCTION_EVALUATION, AI_FUNCTION_OPTIMIZATION; in SNOWFLAKE.CORTEX:
    // COMPLETE, TRY_COMPLETE, CLASSIFY_TEXT, EXTRACT_ANSWER, SENTIMENT,
    // ENTITY_SENTIMENT, TRANSLATE, EMBED_TEXT_768, EMBED_TEXT_1024,
    // PARSE_DOCUMENT, SEARCH_PREVIEW, AGENT_RUN, DATA_AGENT_RUN,
    // THREAD_MESSAGES, FINETUNE, COUNT_TOKENS, SPLIT_TEXT_RECURSIVE_CHARACTER,
    // SPLIT_TEXT_MARKDOWN_HEADER; CORTEX_SEARCH_BATCH (queries a search
    // service); the model methods <model>!PREDICT and <model>!FORECAST; the
    // default grants to PUBLIC above. Not confirmed: whether Snowflake accepts
    // a gap or comment around the dots of a qualified name (refused
    // regardless), the document-model !PREDICT form, the cost of
    // AI_COUNT_TOKENS, and whether Snowflake folds the non-ASCII letters of
    // AsciiFolds in unquoted names (refused regardless).

    /** Prefix of a function refused as a call: every Cortex AI function, a
      * user function of that name too (as on Databricks). */
    private[util] val AI_FUNCTION_PREFIX = "AI_"

    /** The database and schema whose every name is refused, call or not. */
    private[util] val CORTEX_DATABASE = "SNOWFLAKE"
    private[util] val CORTEX_SCHEMA = "CORTEX"

    /** Unqualified names refused as calls. CORTEX_SEARCH_BATCH queries a
      * search service. EXECUTE_AI_EVALUATION starts, cancels or deletes an
      * agent evaluation run (vendor function index, "System functions
      * (Control)", read 2026-11-24); its documented examples use CALL, which
      * is already refused, and whether a SELECT form works is UNCONFIRMED
      * (refused regardless). */
    private[util] val CALL_ONLY_SERVICE_FUNCTIONS: Seq[String] = Seq("CORTEX_SEARCH_BATCH", "EXECUTE_AI_EVALUATION")

    /** Every non-ASCII code point that some case rule maps to ASCII letters,
      * with those letters in upper case: `toUpperCase` / `toLowerCase`
      * (Locale.ROOT) of the code point as a string, `Character.toUpperCase`,
      * `Character.toLowerCase`, and the per-character fold
      * `String.equalsIgnoreCase` uses (`toLowerCase(toUpperCase(c))`). So the
      * long s, the dotless i, the dotted capital I, the Kelvin sign, the
      * sharp s (to `SS`) and the Latin ligatures (`FI`, `FL`, `FFI`, `ST`,
      * ...) are here; on Java 17 no code point has two different ASCII
      * results. Built once, by reading every assigned code point. */
    private lazy val AsciiFolds: java.util.Map[Integer, String] = {
        def asciiLetters(s: String): Boolean =
            s.nonEmpty && s.forall(c => (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
        def str(cp: Int): String = new String(Character.toChars(cp))
        val folds = new java.util.HashMap[Integer, String]()
        var cp = 128
        while (cp <= Character.MAX_CODE_POINT) {
            Character.getType(cp) match {
                case Character.UNASSIGNED | Character.SURROGATE | Character.PRIVATE_USE => ()
                case _ =>
                    val s = str(cp)
                    Seq(
                        s.toUpperCase(java.util.Locale.ROOT),
                        s.toLowerCase(java.util.Locale.ROOT),
                        str(Character.toUpperCase(cp)),
                        str(Character.toLowerCase(cp)),
                        str(Character.toLowerCase(Character.toUpperCase(cp)))
                    ).find(asciiLetters).foreach(f => folds.put(cp, f.toUpperCase(java.util.Locale.ROOT)))
            }
            cp += 1
        }
        folds
    }

    /** `text` with every ASCII letter in upper case and every code point of
      * [[AsciiFolds]] replaced by its ASCII letters; anything else is kept.
      * Gap characters, `/`, `(`, `.`, `!` and `"` are never letters, so the
      * gaps, comments and punctuation of the result are those of `text`. One
      * pass by code point, constant work for each. */
    private[util] def asciiFold(text: String): String = {
        val n = text.length
        val out = new java.lang.StringBuilder(n + 16)
        var i = 0
        while (i < n) {
            val c = text.charAt(i)
            if (c < 128) {
                out.append(if (c >= 'a' && c <= 'z') (c - 32).toChar else c)
                i += 1
            } else {
                val cp = text.codePointAt(i)
                val f = AsciiFolds.get(cp)
                if (f != null) out.append(f) else out.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        out.toString
    }

    private def isAsciiNameChar(c: Char): Boolean =
        (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '$'

    /** Whether the UTF-16 unit at `i` is part of a name: an ASCII letter,
      * digit, `_` or `$`, or any non-ASCII code point that is not a gap
      * character (taken widely, so a name runs on over what might belong to
      * it). Both units of a surrogate pair give the answer of their code point. */
    private def isNameUnit(text: String, i: Int): Boolean = {
        val c = text.charAt(i)
        if (c < 128) isAsciiNameChar(c)
        else {
            val cp =
                if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1)))
                    text.codePointAt(i - 1)
                else text.codePointAt(i)
            !isGapCharacter(cp)
        }
    }

    /** The first call to a model, a search or agent service, or a model's
      * method in `sql`, named in ASCII upper case:
      *  - anything after `SNOWFLAKE` `.` `CORTEX` `.`, call or not, reported
      *    as `SNOWFLAKE.CORTEX.<NAME>`;
      *  - a name beginning [[AI_FUNCTION_PREFIX]] with at least one more name
      *    character, as a call, reported as the name;
      *  - a [[CALL_ONLY_SERVICE_FUNCTIONS]] name as a call;
      *  - a method call: `!`, a gap, a name, a gap, `(`, reported as `!<NAME>`.
      * A part may be in double quotes (`"SNOWFLAKE"`, `"AI_COMPLETE"`,
      * `!"PREDICT"`). A gap is the one [[gapReaches]] reads (wide whitespace
      * and `//` comments, every reading of where a comment ends), allowed
      * before each `(`, on both sides of each dot of the prefix and on both
      * sides of the `!`. A name is in front of `AI_`, `SNOWFLAKE` or
      * `CORTEX_SEARCH_BATCH` only where no ASCII letter, digit, `_` or `$`
      * is directly before it, as for `IDENTIFIER` (so `MY_SNOWFLAKE.CORTEX.`
      * and `my_ai_complete(` are other names). Matched over the whole text,
      * string literals included.
      *
      * Letter case: the rules run over [[asciiFold]] of the text, in which
      * upper-casing, lower-casing and the `equalsIgnoreCase` fold of each
      * code point all give the same ASCII letters, so a name spelled with a
      * letter that any of them folds to ASCII is caught.
      *
      * Linear in the text's length for any input: each rule finds a fixed
      * literal (`AI_`, `SNOWFLAKE`, `CORTEX_SEARCH_BATCH`, `!`) by a plain
      * search, then answers each place it is found with a constant number of
      * lookups in tables of one backward pass each: where a name ends, where
      * a double-quoted name starting at a position closes, whether a gap
      * reaches `(` ([[gapReaches]]), and, for the prefix and the method, a
      * [[gapReaches]] table per step whose targets read only the table
      * before. No regex, so nothing is rescanned from many starts. Naming
      * what is refused walks forward once ([[walkToTarget]]). */
    private[util] def modelOrServiceCall(sql: String): Option[String] = {
        val f = asciiFold(sql)
        val n = f.length
        def at(i: Int): Char = if (i >= 0 && i < n) f.charAt(i) else ' '
        def nameBoundaryBefore(p: Int): Boolean = p == 0 || !isAsciiNameChar(f.charAt(p - 1))

        // nameEnd(i): the end of the run of name units starting at i.
        lazy val nameEnd: Array[Int] = {
            val a = new Array[Int](n + 1)
            a(n) = n
            var i = n - 1
            while (i >= 0) { a(i) = if (isNameUnit(f, i)) a(i + 1) else i; i -= 1 }
            a
        }
        // closeFrom(i): for a double-quoted name whose text starts at i, the
        // position of its closing quote (a doubled quote is one quote inside
        // the name), or -1 when none follows.
        lazy val closeFrom: Array[Int] = {
            val a = new Array[Int](n + 1)
            a(n) = -1
            var runEnd = n // the end of the run of quotes the current one is in
            var i = n - 1
            while (i >= 0) {
                if (f.charAt(i) != '"') a(i) = a(i + 1)
                else {
                    if (i + 1 >= n || f.charAt(i + 1) != '"') runEnd = i + 1
                    // A run of r quotes inside a name: r / 2 doubled quotes,
                    // and the last one closes the name when r is odd.
                    a(i) = if ((runEnd - i) % 2 == 1) runEnd - 1 else a(runEnd)
                }
                i -= 1
            }
            a
        }
        lazy val called: Array[Boolean] = gapReaches(f, t => f.charAt(t) == '(')
        def unquote(s: String): String = s.replace("\"\"", "\"")
        // The name starting at p: unquoted, or the text of a double-quoted one.
        def nameAt(p: Int): String =
            if (at(p) == '"' && closeFrom(p + 1) >= 0) unquote(f.substring(p + 1, closeFrom(p + 1)))
            else f.substring(p, nameEnd(p))

        // SNOWFLAKE . CORTEX . : three tables, built back to front.
        def cortex(): Option[String] = {
            lazy val toLastDot = gapReaches(f, t => f.charAt(t) == '.')
            val quotedSchema = "\"" + CORTEX_SCHEMA + "\""
            def cortexPartEnd(t: Int): Int =
                if (f.startsWith(CORTEX_SCHEMA, t)) t + CORTEX_SCHEMA.length
                else if (f.startsWith(quotedSchema, t)) t + quotedSchema.length
                else -1
            val isCortexPart: Int => Boolean = t => { val e = cortexPartEnd(t); e >= 0 && toLastDot(e) }
            lazy val toCortexPart = gapReaches(f, isCortexPart)
            val isFirstDot: Int => Boolean = t => f.charAt(t) == '.' && toCortexPart(t + 1)
            lazy val toFirstDot = gapReaches(f, isFirstDot)
            val len = CORTEX_DATABASE.length
            var p = f.indexOf(CORTEX_DATABASE)
            while (p >= 0) {
                if (nameBoundaryBefore(p)) {
                    val e = p + len
                    val start =
                        if (toFirstDot(e)) e
                        else if (at(p - 1) == '"' && at(e) == '"' && toFirstDot(e + 1)) e + 1
                        else -1
                    if (start >= 0) {
                        val firstDot = walkToTarget(f, toFirstDot, isFirstDot, start)
                        val part = walkToTarget(f, toCortexPart, isCortexPart, firstDot + 1)
                        val lastDot = walkToTarget(f, toLastDot, (t: Int) => f.charAt(t) == '.', cortexPartEnd(part))
                        val name = gapEnds(f)(lastDot + 1)
                        return Some(CORTEX_DATABASE + "." + CORTEX_SCHEMA + "." + nameAt(name))
                    }
                }
                p = f.indexOf(CORTEX_DATABASE, p + 1)
            }
            None
        }

        // AI_<name> as a call, unquoted or in double quotes.
        def aiFunction(): Option[String] = {
            val len = AI_FUNCTION_PREFIX.length
            var p = f.indexOf(AI_FUNCTION_PREFIX)
            while (p >= 0) {
                if (nameBoundaryBefore(p)) {
                    val e = nameEnd(p + len)
                    if (e > p + len && called(if (at(e) == '"') e + 1 else e)) return Some(f.substring(p, e))
                    if (at(p - 1) == '"') {
                        val c = closeFrom(p)
                        if (c > p + len && called(c + 1)) return Some(unquote(f.substring(p, c)))
                    }
                }
                p = f.indexOf(AI_FUNCTION_PREFIX, p + 1)
            }
            None
        }

        // A listed name as a call, unquoted or in double quotes.
        def serviceFunction(name: String): Option[String] = {
            var p = f.indexOf(name)
            while (p >= 0) {
                val e = p + name.length
                if (nameBoundaryBefore(p) && called(if (at(e) == '"') e + 1 else e)) return Some(name)
                p = f.indexOf(name, p + 1)
            }
            None
        }

        // ! <gap> name <gap> ( : a method call on an object.
        def method(): Option[String] = {
            if (f.indexOf('!') < 0) return None
            val isMethod: Int => Boolean = t => {
                val c = f.charAt(t)
                if (c == '"') { val q = closeFrom(t + 1); q >= 0 && called(q + 1) }
                else !(c >= '0' && c <= '9') && isNameUnit(f, t) && called(nameEnd(t))
            }
            lazy val toMethod = gapReaches(f, isMethod)
            var i = f.indexOf('!')
            while (i >= 0) {
                if (toMethod(i + 1)) return Some("!" + nameAt(walkToTarget(f, toMethod, isMethod, i + 1)))
                i = f.indexOf('!', i + 1)
            }
            None
        }

        cortex()
            .orElse(aiFunction())
            .orElse(CALL_ONLY_SERVICE_FUNCTIONS.iterator.map(serviceFunction).collectFirst { case Some(x) => x })
            .orElse(method())
    }

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

        // One statement per query: a second statement can follow the first
        // only after a semicolon (any left after stripping the trailing one)
        // or the flow operator; both are refused.
        if (normalized.contains(";"))
            throw new DatrisException("Semicolons are not allowed in queries")
        if (chainedStatement(normalized))
            throw new DatrisException("Chained statements are not allowed in queries (the ->> operator)")

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
