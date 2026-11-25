package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.util.Locale

/** Story: Snowflake query: a call to a model or search service is refused, as
  * on Databricks (plans/stories/snowflake-ai-functions-refused.md), the Scala
  * half of Acceptance. Pure: no account, no network.
  *
  * Owner rulings (2026-11-24): every function below is refused, with no
  * allowlist for the helpers that call no model (Decisions 1 and 3), as
  * `Query contains blocked function: <name>`. The rule (Decision 2):
  *  - a name beginning `AI_`, refused as a call only (name, optional double
  *    quotes, a gap, `(`), with the same boundary in front as `IDENTIFIER`;
  *    reported as the name in upper case;
  *  - the qualified prefix `SNOWFLAKE` `.` `CORTEX` `.` anywhere, call or not,
  *    each part optionally double-quoted, a gap allowed on both sides of each
  *    dot; reported as `SNOWFLAKE.CORTEX.<NAME>` in upper case without the
  *    gaps and quotes;
  *  - `CORTEX_SEARCH_BATCH`, refused as a call;
  *  - a method call: `!`, a gap, a name, a gap, `(`; reported as `!<NAME>`.
  * The gap is the one an `IDENTIFIER` call may have today: whitespace taken
  * widely (Unicode separators, control and format characters) and `//` line
  * comments, read every way a comment may end. String literals are covered,
  * as for the existing list. `VECTOR_*` and the other Decision 4 names stay
  * accepted. Members are reached by name (QuerySafetyReflect). */
class SnowflakeAiFunctionSpec extends AnyFunSuite {

    private def validate(sql: String): Unit = QuerySafetyReflect.call(SnowflakeQueryUtil, "validateQuery", sql)

    private val Prefix = "Query contains blocked function: "

    /** Refused with exactly `name` reported. */
    private def refusedAs(sql: String, name: String): Unit = {
        val e = withClue(sql + " was accepted: ")(intercept[DatrisException](validate(sql)))
        assert(e.getMessage == Prefix + name, sql + " -> " + e.getMessage)
    }

    /** Refused as a blocked function whose reported name starts with `start`. */
    private def refusedStartingWith(sql: String, start: String): Unit = {
        val e = withClue(sql + " was accepted: ")(intercept[DatrisException](validate(sql)))
        assert(e.getMessage.startsWith(Prefix + start), sql + " -> " + e.getMessage)
    }

    private def accepted(sql: String): Unit = withClue(sql + " -> ")(validate(sql))

    private def cases(s: String): Seq[String] = Seq(
        s.toUpperCase(Locale.ROOT),
        s.toLowerCase(Locale.ROOT),
        s.zipWithIndex.map {
            case (c, i) => if (i % 2 == 0) c.toUpper else c.toLower
        }.mkString
    ).distinct

    /** `call` in the select list, in WHERE and inside a CTE. */
    private def placements(call: String): Seq[String] = Seq(
        "SELECT " + call,
        "SELECT a FROM t WHERE b = " + call,
        "WITH x AS (SELECT " + call + " AS v FROM t) SELECT v FROM x"
    )

    // Background: "Snowflake Cortex AI Functions" and the function index.
    private val AiNames = Seq(
        "AI_COMPLETE",
        "AI_CLASSIFY",
        "AI_FILTER",
        "AI_AGG",
        "AI_EMBED",
        "AI_MULTI_EMBED",
        "AI_EXTRACT",
        "AI_SENTIMENT",
        "AI_SUMMARIZE",
        "AI_SUMMARIZE_AGG",
        "AI_SIMILARITY",
        "AI_TRANSCRIBE",
        "AI_PARSE_DOCUMENT",
        "AI_REDACT",
        "AI_TRANSLATE",
        "AI_COUNT_TOKENS",
        "AI_FUNCTION_EVALUATION",
        "AI_FUNCTION_OPTIMIZATION",
        // a function the vendor adds later
        "AI_SOME_FUTURE_FUNCTION"
    )

    // Background: names marked "(SNOWFLAKE.CORTEX)" in the function index.
    private val CortexNames = Seq(
        "COMPLETE",
        "TRY_COMPLETE",
        "CLASSIFY_TEXT",
        "EXTRACT_ANSWER",
        "SENTIMENT",
        "ENTITY_SENTIMENT",
        "TRANSLATE",
        "EMBED_TEXT_768",
        "EMBED_TEXT_1024",
        "PARSE_DOCUMENT",
        "SEARCH_PREVIEW",
        "AGENT_RUN",
        "DATA_AGENT_RUN",
        "THREAD_MESSAGES",
        "FINETUNE",
        "COUNT_TOKENS",
        "SPLIT_TEXT_RECURSIVE_CHARACTER",
        "SPLIT_TEXT_MARKDOWN_HEADER",
        "SOME_FUTURE_FUNCTION"
    )

    test("each AI_ function is refused in any letter case with the function named") {
        AiNames.foreach { name =>
            cases(name + "('m', a)").foreach(c => placements(c).foreach(sql => refusedAs(sql, name)))
        }
        // a quoted name, a qualified one (a user function beginning AI_ too) and a literal holding a call
        cases("\"AI_COMPLETE\"('m', 'p')").foreach(c => placements(c).foreach(sql => refusedAs(sql, "AI_COMPLETE")))
        refusedAs("SELECT db.s.ai_my_function(a) FROM t", "AI_MY_FUNCTION")
        refusedAs("SELECT * FROM TABLE(AI_SOME_TABLE_FUNCTION(a))", "AI_SOME_TABLE_FUNCTION")
        refusedAs("SELECT a FROM t WHERE note = 'ai_complete(x)'", "AI_COMPLETE")
    }

    test("anything in the SNOWFLAKE.CORTEX schema is refused in any letter case, named as SNOWFLAKE.CORTEX.<NAME>") {
        CortexNames.foreach { name =>
            val full = "SNOWFLAKE.CORTEX." + name
            cases(full + "('m', a)").foreach(c => placements(c).foreach(sql => refusedAs(sql, full)))
            cases("\"SNOWFLAKE\".\"CORTEX\".\"" + name + "\"('m', a)").foreach(c => placements(c).foreach(sql => refusedAs(sql, full)))
            cases("SELECT * FROM TABLE(" + full + "('m', a))").foreach(sql => refusedAs(sql, full))
        }
        // one part quoted, the others not
        refusedAs("SELECT \"SNOWFLAKE\".cortex.complete('m', a) FROM t", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT snowflake.\"CORTEX\".\"COMPLETE\"('m', a) FROM t", "SNOWFLAKE.CORTEX.COMPLETE")
        // anywhere, call or not, string literals included
        refusedAs("SELECT SNOWFLAKE.CORTEX.COMPLETE AS f", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT a FROM t WHERE note = 'snowflake.cortex.complete'", "SNOWFLAKE.CORTEX.COMPLETE")
    }

    // The wide gap set the story names, plus line comments holding a control
    // or separator character (a reading in which the comment runs past it is taken).
    private val Gaps = Seq(" ", "\t", "\n", "\u00A0", "\u3000", "\u2003", "\u200B", "\u0001", "//x\n", "//x\u2028")
    private val CommentInnerEnds = Seq("\t", "\r", "\u000B", "\u000C", "\u0001", "\u0000", "\u0085", "\u2028", "\u2029")
    private val AllGaps = Gaps ++ CommentInnerEnds.map(c => "//x" + c + "y\n") ++ Seq("  //a\n //b\n  ", "\u00A0//x\u0001y\n\u200B")

    test("whitespace of the wide set or a line comment around the dots or before the parenthesis does not hide a call") {
        AllGaps.foreach { g =>
            val esc = g.flatMap(c => if (c < ' ' || c > '~') "\\u%04X".format(c.toInt) else c.toString)
            withClue("gap \"" + esc + "\": ") {
                // A gap after the last dot that holds a comment with a possible
                // end inside it (`//x<end>y`) has a reading in which `y` is the
                // name after the prefix; the prefix is refused call or not, so
                // there either name may be reported.
                val afterLastDot: (String, String) => Unit =
                    if (g.contains("y")) (sql, _) => refusedStartingWith(sql, "SNOWFLAKE.CORTEX.") else refusedAs
                // every gap position at once, then each position alone
                afterLastDot("SELECT SNOWFLAKE" + g + "." + g + "CORTEX" + g + "." + g + "COMPLETE" + g + "('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                refusedAs("SELECT snowflake" + g + ".cortex.complete('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                refusedAs("SELECT snowflake." + g + "cortex.complete('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                refusedAs("SELECT snowflake.cortex" + g + ".complete('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                afterLastDot("SELECT snowflake.cortex." + g + "complete('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                afterLastDot("SELECT \"SNOWFLAKE\"" + g + "." + g + "\"CORTEX\"" + g + "." + g + "\"COMPLETE\"('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
                // before the parenthesis of an AI_ call, quoted or not
                refusedAs("SELECT AI_COMPLETE" + g + "('m', 'p')", "AI_COMPLETE")
                refusedAs("SELECT a FROM t WHERE ai_filter" + g + "(a)", "AI_FILTER")
                refusedAs("SELECT \"AI_COMPLETE\"" + g + "('m', 'p')", "AI_COMPLETE")
                // CORTEX_SEARCH_BATCH and a method call
                refusedAs("SELECT * FROM TABLE(CORTEX_SEARCH_BATCH" + g + "(service_name => 's'))", "CORTEX_SEARCH_BATCH")
                refusedAs("SELECT m!" + g + "PREDICT" + g + "(INPUT_DATA => {*}) FROM t", "!PREDICT")
                refusedAs("SELECT m" + g + "!" + g + "predict" + g + "(INPUT_DATA => {*}) FROM t", "!PREDICT")
            }
        }
        // a `(` inside a comment is not the call's: a column whose name begins AI_ still reads
        accepted("SELECT ai_score //x (y\n FROM t")
        accepted("SELECT cortex_search_batch //x (y\n FROM t")
    }

    test("a letter that only Unicode case rules fold to ASCII does not hide a name") {
        // the long s, U+017F, in place of S
        refusedAs("SELECT \u017FNOWFLAKE.CORTEX.COMPLETE('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT \u017Fnowflake.cortex.complete('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT snowflake.cortex.\u017Fentiment(a) FROM t", "SNOWFLAKE.CORTEX.SENTIMENT")
        refusedAs("SELECT AI_CLA\u017F\u017FIFY(a, ['x', 'y'])", "AI_CLASSIFY")
        refusedAs("SELECT ai_cla\u017F\u017Fify(a, ['x', 'y'])", "AI_CLASSIFY")
        refusedAs("SELECT * FROM TABLE(CORTEX_\u017FEARCH_BATCH(service_name => 's'))", "CORTEX_SEARCH_BATCH")
        refusedAs("SELECT m!FORECA\u017FT(FORECASTING_PERIODS => 3)", "!FORECAST")
        // the dotless i, U+0131, in place of I
        refusedAs("SELECT A\u0131_COMPLETE('m', 'p')", "AI_COMPLETE")
        refusedAs("SELECT a\u0131_complete('m', 'p')", "AI_COMPLETE")
        refusedAs("SELECT m!PRED\u0131CT(INPUT_DATA => {*}) FROM t", "!PREDICT")
        // ligatures upper-casing folds to two ASCII letters (U+FB02 to FL, U+FB01 to FI)
        refusedAs("SELECT SNOW\uFB02AKE.CORTEX.COMPLETE('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT AI_\uFB01LTER(a)", "AI_FILTER")
        // the same letters in a column name that is not refused stay accepted
        accepted("SELECT a\u0131_score, \u017Fnowflake_note FROM t")
    }

    test("CORTEX_SEARCH_BATCH is refused as a call; a column named cortex_search_batch_id is accepted") {
        cases("SELECT * FROM TABLE(CORTEX_SEARCH_BATCH(service_name => 'db.s.svc', query => q))").foreach(sql =>
            refusedAs(sql, "CORTEX_SEARCH_BATCH")
        )
        cases("SELECT r.* FROM t, TABLE(CORTEX_SEARCH_BATCH(service_name => 's', query => t.q)) r").foreach(sql =>
            refusedAs(sql, "CORTEX_SEARCH_BATCH")
        )
        refusedAs("SELECT * FROM TABLE(\"CORTEX_SEARCH_BATCH\"(service_name => 's'))", "CORTEX_SEARCH_BATCH")
        refusedAs("SELECT * FROM TABLE(db.s.cortex_search_batch(service_name => 's'))", "CORTEX_SEARCH_BATCH")
        accepted("SELECT cortex_search_batch_id FROM t")
        accepted("SELECT cortex_search_batch_id, cortex_search_batch FROM t WHERE cortex_search_batch_id IN (1, 2)")
        accepted("SELECT my_cortex_search_batch(a) FROM t")
    }

    test("a method call on an object is refused and named") {
        cases("SELECT m!PREDICT(INPUT_DATA => {*}) FROM t").foreach(sql => refusedAs(sql, "!PREDICT"))
        cases("SELECT db.s.m ! forecast (FORECASTING_PERIODS => 3)").foreach(sql => refusedAs(sql, "!FORECAST"))
        cases("SELECT * FROM TABLE(m!FORECAST(FORECASTING_PERIODS => 3))").foreach(sql => refusedAs(sql, "!FORECAST"))
        cases("SELECT a, m!DETECT_ANOMALIES(INPUT_DATA => {*}) FROM t").foreach(sql => refusedAs(sql, "!DETECT_ANOMALIES"))
        // a method added later, in WHERE and inside a CTE
        refusedAs("SELECT a FROM t WHERE m!SOME_FUTURE_METHOD(a) > 1", "!SOME_FUTURE_METHOD")
        refusedAs("WITH x AS (SELECT m!predict(INPUT_DATA => {*}) AS p FROM t) SELECT p FROM x", "!PREDICT")
        // a quoted object and a quoted method name
        refusedAs("SELECT \"My Model\"!PREDICT(INPUT_DATA => {*}) FROM t", "!PREDICT")
        refusedAs("SELECT m!\"PREDICT\"(INPUT_DATA => {*}) FROM t", "!PREDICT")
    }

    test("names that only begin like a refused one are accepted") {
        Seq(
            "SELECT * FROM db.s.ai_results",
            "SELECT ai_score, ai_, cortex_notes FROM ai_results",
            "SELECT my_ai_complete(a) FROM t",
            "SELECT a FROM snowflake_cortex.t",
            "SELECT a FROM cortex.t",
            "SHOW FUNCTIONS IN SCHEMA SNOWFLAKE.CORTEX",
            "SHOW GRANTS OF DATABASE ROLE SNOWFLAKE.CORTEX_USER",
            "SELECT * FROM SNOWFLAKE.ACCOUNT_USAGE.QUERY_HISTORY",
            // more of the same shapes
            "SELECT \"ai_score\", ai_results.a FROM ai_results WHERE ai_score IN (1, 2)",
            "SELECT COUNT(ai_score), MAX(ai_results.ai_score) FROM ai_results",
            "SELECT a FROM t WHERE note = 'ai_complete'",
            "SELECT a FROM snowflake.cortex_user_notes.t",
            "SHOW FUNCTIONS LIKE 'COMPLETE' IN SCHEMA SNOWFLAKE.CORTEX"
        ).foreach(accepted)
    }

    test("comparisons and vector arithmetic are accepted") {
        Seq(
            "SELECT a FROM t WHERE a != (b)",
            "SELECT a FROM t WHERE a != b",
            "SELECT a FROM t WHERE a <> f(b)",
            "SELECT a FROM t WHERE a!=(b) AND NOT c",
            "SELECT a FROM t WHERE a !=  coalesce(b, 0)",
            "SELECT VECTOR_COSINE_SIMILARITY(v, w) FROM t",
            "SELECT VECTOR_L2_DISTANCE(v, w) FROM t",
            "SELECT VECTOR_INNER_PRODUCT(v, w) FROM t",
            "SELECT VECTOR_L1_DISTANCE(v, w) FROM t",
            "SELECT [1, 2, 3]::VECTOR(FLOAT, 3) AS v",
            "SELECT v::VECTOR(FLOAT, 3) FROM t",
            "SELECT a FROM t ORDER BY vector_cosine_similarity(embedding, [0.1, 0.2, 0.3]::VECTOR(FLOAT, 3)) DESC",
            // Decision 4: left accepted
            "SELECT TO_FILE('@st', 'a.pdf'), TRY_TO_FILE('@st', 'b.pdf'), PROMPT('{0}', a), FL_GET_SIZE(f) FROM t",
            "SELECT * FROM TABLE(CORTEX_SEARCH_DATA_SCAN(SERVICE_NAME => 's'))",
            "SELECT * FROM TABLE(CORTEX_SEARCH_REFRESH_HISTORY(SERVICE_NAME => 's'))",
            "SELECT * FROM TABLE(SNOWFLAKE.LOCAL.GET_AI_EVENTS('db', 's', 'a'))",
            // unqualified Cortex names (not reachable in one statement)
            "SELECT SENTIMENT(a), COMPLETE('m', a), EMBED_TEXT_768('m', a) FROM t"
        ).foreach(accepted)
    }

    test("the allowlisted SYSTEM$ names and every statement in the docs-examples test of SnowflakeQueryValidationSpec are still accepted") {
        Seq(
            "SELECT SYSTEM$TYPEOF(1)",
            "select system$typeof(a) from t",
            "SELECT SYSTEM$CLUSTERING_INFORMATION('db.s.t')",
            "SELECT system$clustering_depth('db.s.t', '(a)')",
            // the docs-examples test, statement for statement
            "SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000",
            "SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000 LIMIT 10",
            "WITH big AS (SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000) SELECT * FROM big",
            "SHOW DATABASES",
            "SHOW SCHEMAS IN DATABASE SALES_DB",
            "SHOW TABLES IN SCHEMA SALES_DB.PUBLIC",
            "DESCRIBE TABLE SALES_DB.PUBLIC.STOCK_PRICE",
            "SELECT column_name, data_type FROM SALES_DB.information_schema.columns WHERE table_name = 'STOCK_PRICE'",
            "DESC USER DATRIS_SVC"
        ).foreach(accepted)
    }

    // ---- timing ----
    //
    // `blockedFunction` called directly, so the text-length limit cannot turn
    // a case into a refusal test. Many starts for each new rule, one warm-up,
    // one bound of 5 seconds, each call on its own daemon thread that is
    // stopped at the bound.

    private val BoundMillis = 5000L
    private val Length = 200000

    private def blockedFunction(sql: String): AnyRef = QuerySafetyReflect.call(SnowflakeQueryUtil, "blockedFunction", sql)

    /** Returns within the bound, or fails the test there. */
    private def bounded(label: String, sql: String): Unit = {
        @volatile var error: Throwable = null
        val t = new Thread(
            new Runnable {
                def run(): Unit =
                    try blockedFunction(sql)
                    catch { case e: Throwable => error = e }
            },
            "bounded-ai-function-check"
        )
        t.setDaemon(true)
        val started = System.nanoTime()
        t.start()
        t.join(BoundMillis)
        if (t.isAlive) {
            val ms = (System.nanoTime() - started) / 1000000L
            t.setPriority(Thread.MIN_PRIORITY)
            try classOf[Thread].getMethod("stop").invoke(t)
            catch { case _: Throwable => () }
            t.join(2000L)
            val state = if (t.isAlive) "still running at minimum priority" else "stopped"
            fail(label + " (" + sql.length + " characters): did not return within " + BoundMillis + " ms (" + state + " after " + ms + " ms)")
        }
        if (error != null) throw error
    }

    /** "SELECT " then `unit` repeated to at least [[Length]] characters. */
    private def repeated(unit: String): String = {
        val sb = new StringBuilder("SELECT ")
        while (sb.length < Length) sb.append(unit)
        sb.toString
    }

    test("the new checks stay fast on crafted input") {
        bounded("warm-up", "SELECT a FROM t WHERE b = 1")
        Seq(
            // the story's five runs
            "AI_",
            "SNOWFLAKE.",
            "SNOWFLAKE . CORTEX //",
            "!a ",
            "AI_X //",
            // each new rule's starts, with quotes, wide gaps and comments holding possible ends
            "\"AI_X\" ",
            "AI_X //\t",
            "\"SNOWFLAKE\" . ",
            "SNOWFLAKE . CORTEX //\t.",
            "SNOWFLAKE\u00A0.\u200BCORTEX\u3000",
            "SNOW\uFB02AKE.",
            "\u017FNOWFLAKE . CORTEX //",
            "CORTEX_SEARCH_BATCH ",
            "CORTEX_SEARCH_BATCH //\t",
            "! a //\t",
            "!\u017F ",
            "!",
            "m != ",
            "A\u0131_X //"
        ).foreach(unit => bounded("repeated \"" + unit + "\"", repeated(unit)))
        // a long name after each kind of start, read once
        bounded("one long AI_ name", "SELECT AI_" + ("X" * Length) + " (")
        bounded("one long Cortex name", "SELECT SNOWFLAKE.CORTEX." + ("X" * Length))
        bounded("one long method name", "SELECT m!" + ("X" * Length) + " //\t")
        // one comment line holding many possible ends after each start
        bounded("AI_ call before a comment holding many tabs", "SELECT AI_X //" + ("\tx" * (Length / 2)))
        bounded("Cortex prefix before a comment holding many tabs", "SELECT SNOWFLAKE //" + ("\t." * (Length / 2)))
        bounded("method before a comment holding many tabs", "SELECT m! //" + ("\ta" * (Length / 2)))
    }
}
