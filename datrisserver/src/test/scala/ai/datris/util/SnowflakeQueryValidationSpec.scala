package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.util.Locale

/** Story: SQL Server, Snowflake and Databricks queries: one statement, no
  * functions that act, and a time limit on every query
  * (plans/stories/query-sqlserver-snowflake-databricks-side-effects.md), the
  * Snowflake half of Acceptance. Pure: no account, no network.
  *
  * The story makes `SnowflakeQueryUtil.validateQuery` `private[util]` and adds
  * `BLOCKED_FUNCTIONS`: every `SYSTEM$...` name except the read-only allowlist
  * (`SYSTEM$TYPEOF`, `SYSTEM$CLUSTERING_INFORMATION`, `SYSTEM$CLUSTERING_DEPTH`)
  * and `NEXTVAL`, refused as `Query contains blocked function: <name>`
  * (Decisions 4 and 6). `limit: -1` runs under 300 s, a bounded query under
  * 30 s (Decision 5); the choice is read through a `timeoutSeconds(limit)`
  * seam shaped like `PostgresQueryUtil.timeoutSeconds`. Members are reached by
  * name (QuerySafetyReflect) so this spec compiles before they exist. */
class SnowflakeQueryValidationSpec extends AnyFunSuite {

    private def validate(sql: String): Unit = QuerySafetyReflect.call(SnowflakeQueryUtil, "validateQuery", sql)

    private def refusedNaming(sql: String, name: String): Unit = {
        val e = intercept[DatrisException](validate(sql))
        assert(e.getMessage.startsWith("Query contains blocked function: "), sql + " -> " + e.getMessage)
        assert(e.getMessage.toUpperCase(Locale.ROOT).contains(name.toUpperCase(Locale.ROOT)), sql + " -> " + e.getMessage)
    }

    private def cases(s: String): Seq[String] = Seq(
        s.toUpperCase(Locale.ROOT),
        s.toLowerCase(Locale.ROOT),
        s.zipWithIndex.map {
            case (c, i) => if (i % 2 == 0) c.toUpper else c.toLower
        }.mkString
    ).distinct

    // (call, name the refusal must carry)
    private val Listed = Seq(
        "SYSTEM$CANCEL_QUERY('01b2c3d4-0000-0000-0000-000000000000')" -> "SYSTEM$CANCEL_QUERY",
        "SYSTEM$CANCEL_ALL_QUERIES(123)" -> "SYSTEM$CANCEL_ALL_QUERIES",
        "SYSTEM$ABORT_SESSION(123)" -> "SYSTEM$ABORT_SESSION",
        "SYSTEM$ABORT_TRANSACTION(123)" -> "SYSTEM$ABORT_TRANSACTION",
        "SYSTEM$PIPE_FORCE_RESUME('db.s.p')" -> "SYSTEM$PIPE_FORCE_RESUME",
        "SYSTEM$USER_TASK_CANCEL_ONGOING_EXECUTIONS('db.s.t')" -> "SYSTEM$USER_TASK_CANCEL_ONGOING_EXECUTIONS",
        "SYSTEM$TASK_DEPENDENTS_ENABLE('db.s.t')" -> "SYSTEM$TASK_DEPENDENTS_ENABLE",
        "SYSTEM$WAIT(10)" -> "SYSTEM$WAIT",
        // Decision 4: every SYSTEM$ name outside the allowlist, not only the ones known today
        "SYSTEM$SOME_FUTURE_FUNCTION()" -> "SYSTEM$SOME_FUTURE_FUNCTION",
        "my_seq.NEXTVAL" -> "NEXTVAL",
        "db.s.order_seq.nextval" -> "NEXTVAL"
    )

    test("each listed function is refused in any letter case with the function named") {
        Listed.foreach {
            case (call, name) =>
                cases(call).foreach { c =>
                    refusedNaming("SELECT " + c, name)
                    refusedNaming("SELECT a FROM t WHERE b = " + c, name)
                    refusedNaming("WITH x AS (SELECT " + c + " AS v) SELECT v FROM x", name)
                }
        }
        refusedNaming("SELECT SYSTEM$WAIT (10)", "SYSTEM$WAIT")
    }

    test("Snowflake: SYSTEM$TYPEOF and the other allowlisted names are accepted; a column named system_status is accepted") {
        Seq(
            "SELECT SYSTEM$TYPEOF(1)",
            "select system$typeof(a) from t",
            "SELECT SYSTEM$CLUSTERING_INFORMATION('db.s.t')",
            "SELECT system$clustering_depth('db.s.t', '(a)')",
            "SELECT system_status FROM t",
            "SELECT system_status, nextval_hint FROM t WHERE system_status = 'ok'"
        ).foreach(validate)
        // and the allowlist is an allowlist: the same shape with another SYSTEM$ name is refused
        refusedNaming("SELECT SYSTEM$WAIT(1)", "SYSTEM$WAIT")
    }

    test("SELECT, WITH, SHOW and DESCRIBE reads from the existing docs examples are accepted") {
        Seq(
            // docs/api-reference/query-api.mdx
            "SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000",
            "SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000 LIMIT 10",
            "WITH big AS (SELECT symbol, close FROM SALES_DB.PUBLIC.STOCK_PRICE WHERE volume > 1000000) SELECT * FROM big",
            // query_snowflake tool description / docs/mcp-server.mdx
            "SHOW DATABASES",
            "SHOW SCHEMAS IN DATABASE SALES_DB",
            "SHOW TABLES IN SCHEMA SALES_DB.PUBLIC",
            "DESCRIBE TABLE SALES_DB.PUBLIC.STOCK_PRICE",
            "SELECT column_name, data_type FROM SALES_DB.information_schema.columns WHERE table_name = 'STOCK_PRICE'",
            // docs/destinations/snowflake.mdx
            "DESC USER DATRIS_SVC"
        ).foreach(validate)
    }

    test("limit -1 sets the 300 s timeout; a positive limit keeps 30 s") {
        assert(QuerySafetyReflect.timeoutFor(SnowflakeQueryUtil, -1) == 300)
        assert(QuerySafetyReflect.timeoutFor(SnowflakeQueryUtil, 7) == 30)
        assert(QuerySafetyReflect.timeoutFor(SnowflakeQueryUtil, 100) == 30)
        assert(QuerySafetyReflect.timeoutFor(SnowflakeQueryUtil, 0) == 30)
    }

    // ---- review round 1 ----

    test("a SYSTEM$ name outside the allowlist is refused anywhere, not only before a parenthesis") {
        refusedNaming("SELECT 'SYSTEM$WAIT' AS f", "SYSTEM$WAIT")
        refusedNaming("SELECT \"SYSTEM$WAIT\" FROM t", "SYSTEM$WAIT")
        // the allowlist still reads, and a non-allowlisted name inside it is refused
        validate("SELECT SYSTEM$TYPEOF(a) FROM t")
        refusedNaming("SELECT SYSTEM$TYPEOF(SYSTEM$WAIT(1))", "SYSTEM$WAIT")
    }

    test("an IDENTIFIER call is refused, line comments between it and its parenthesis included; GETNEXTVAL is refused") {
        cases("IDENTIFIER('SYSTEM$TYPEOF')(1)").foreach(c => refusedNaming("SELECT " + c, "IDENTIFIER"))
        refusedNaming("SELECT * FROM IDENTIFIER ('db.s.t')", "IDENTIFIER")
        cases("TABLE(GETNEXTVAL(seq1))").foreach(c => refusedNaming("SELECT s.v FROM t, " + c + " s", "GETNEXTVAL"))
        // a URL literal holds `//` and still reads
        validate("SELECT a FROM t WHERE u = 'https://example.com/x'")
        // a `//` line comment between IDENTIFIER and its parenthesis does not hide the call
        Seq("SELECT IDENTIFIER//x\n('SYSTEM$TYPEOF')(1)", "SELECT identifier //a\n //b\n  ('t')").foreach { sql =>
            val e = intercept[DatrisException](validate(sql))
            assert(e.getMessage == "Query contains blocked function: IDENTIFIER", sql + " -> " + e.getMessage)
        }
        // a `(` inside the comment is not the call's, and a long run of `//` stays quick
        validate("SELECT identifier //x (y\n FROM t")
        validate("SELECT identifier " + ("// " * 2000) + "\n FROM t")
        // a SYSTEM$ name followed by a `//` comment is still refused (whole word)
        refusedNaming("SELECT SYSTEM$WAIT//x\n(10)", "SYSTEM$WAIT")
        // a column whose name only contains the word is not the call
        validate("SELECT identifier_type, getnextval_count FROM t")
    }

    // ---- review round 2: Unicode spaces and case ----

    test("a Unicode space, control character or line separator before the parenthesis does not hide an IDENTIFIER call") {
        Seq("\u00A0", "\u3000", "\u2003", "\u0001", "\u200B", "//x\u2028", "//x\u2029", "//x\u0085", "// a\u000B").foreach { gap =>
            val sql = "SELECT IDENTIFIER" + gap + "('t')"
            val e = intercept[DatrisException](validate(sql))
            assert(e.getMessage == "Query contains blocked function: IDENTIFIER", sql + " -> " + e.getMessage)
        }
    }

    test("a letter that only Unicode case rules fold to ASCII does not hide a name") {
        refusedNaming("SELECT \u017FYSTEM$WAIT(1)", "SYSTEM$WAIT")
        refusedNaming("SELECT \u0131dentifier('t')", "IDENTIFIER")
        refusedNaming("SELECT \u017Fystem$wait(1)", "SYSTEM$WAIT")
        // an allowlisted name with more letters after it is not the allowlisted one
        refusedNaming("SELECT SYSTEM$TYPEOF\u00E9(1)", "SYSTEM$TYPEOF")
        refusedNaming("SELECT SYSTEM$TYPEOF\u017F(1)", "SYSTEM$TYPEOFS")
        validate("SELECT SYSTEM$TYPEOF(a), system_status FROM t WHERE u = 'https://example.com/x'")
    }

    // ---- chained statements (plans/stories/snowflake-chained-statements-refused.md) ----
    //
    // Snowflake's flow operator (`-`, `>`, `>`) runs a further statement after
    // the first. The story refuses it anywhere in the text, string literals and
    // quoted identifiers included (Decisions 1 and 2), also with a gap between
    // its characters (Decision 5), with the Decision 4 text. The gap is the one
    // a called name and its parenthesis may have: whitespace, Unicode
    // separators, control and format characters, `//` line comments. Chains
    // in this section are read-to-read only.

    private val Chained = "Chained statements are not allowed in queries (the ->> operator)"

    private def refusedChained(sql: String): Unit = {
        val e = intercept[DatrisException](validate(sql))
        assert(e.getMessage == Chained, sql + " -> " + e.getMessage)
    }

    test("statements chained with the flow operator are refused with the rule named") {
        val firsts = Seq(
            "SELECT a FROM t",
            "select a from t where b > 1",
            "WITH x AS (SELECT a FROM t) SELECT a FROM x",
            "SHOW TABLES IN SCHEMA SALES_DB.PUBLIC",
            "DESCRIBE TABLE SALES_DB.PUBLIC.STOCK_PRICE",
            "DESC TABLE t"
        )
        firsts.foreach { first =>
            refusedChained(first + " ->> SELECT \"name\" FROM $1")
            refusedChained(first + "->>SELECT \"name\" FROM $1")
            refusedChained(first + " ->>SELECT 1")
            refusedChained(first + "->> SELECT 1")
        }
        // a chain of three
        refusedChained("SHOW TABLES ->> SELECT \"name\" FROM $1 ->> SELECT COUNT(*) FROM $2")
        refusedChained("SELECT 1->>SELECT 2->>SELECT 3")
        // a trailing semicolon after the chain does not change the rule named
        refusedChained("SELECT a FROM t ->> SELECT 1;")
    }

    // Gaps the story lists: space, tab, newline, a control character, no-break
    // space, ideographic space, em space, zero-width space, line separator,
    // and a `//` comment ended by a newline.
    private val OperatorGaps = Seq(" ", "\t", "\n", "\u0001", "\u00A0", "\u3000", "\u2003", "\u200B", "\u2028", "//x\n")

    private def splitOperators(gap: String): Seq[String] = Seq("-" + gap + ">>", "->" + gap + ">", "-" + gap + ">" + gap + ">")

    test("the flow operator is refused with whitespace, control or format characters or a line comment between its characters") {
        OperatorGaps.foreach { gap =>
            splitOperators(gap).foreach { op =>
                refusedChained("SELECT a FROM t " + op + " SELECT b FROM u")
                refusedChained("SHOW TABLES" + op + "SELECT 1")
            }
        }
    }

    test("the gap inside the flow operator is the same gap a called name and its parenthesis may have") {
        // U+E0001 LANGUAGE TAG: a format character outside the Basic Multilingual Plane
        val nonBmpFormat = new String(Character.toChars(0xe0001))
        Seq(
            "  \t ",
            "\u000B",
            "\u0085",
            "\u2029",
            "\uFEFF",
            nonBmpFormat,
            // comments ended by a line separator, a paragraph separator, NEL, a control character
            "//x\u2028",
            "//x\u2029",
            "//x\u0085",
            "// a\u000B",
            // runs mixing them
            " //a\n //b\n ",
            "\u00A0//x\n\u200B",
            "//x\n" + nonBmpFormat + "//y\n",
            // the comment text may hold anything up to its end
            "// > - ( x\n"
        ).foreach { gap =>
            splitOperators(gap).foreach(op => refusedChained("SELECT a FROM t " + op + " SELECT b FROM u"))
        }
    }

    test("the flow operator inside a string literal or a quoted identifier is refused too") {
        refusedChained("SELECT a FROM t WHERE col = 'x ->> y'")
        refusedChained("SELECT a FROM t WHERE col = '->>'")
        refusedChained("SELECT \"a->>b\" FROM t")
        refusedChained("SELECT a AS \"->>\" FROM t")
        refusedChained("SELECT a FROM t WHERE col = $$ ->> $$")
        // split forms inside a literal too
        refusedChained("SELECT a FROM t WHERE col = '- >>'")
        refusedChained("SELECT a FROM t WHERE col = '-> >'")
    }

    test("a lambda arrow, a subtraction beside a comparison and a literal holding part of the operator are accepted") {
        Seq(
            // the story's list
            "SELECT FILTER(arr, x -> x > 1) FROM t",
            "SELECT TRANSFORM(arr, x -> x - 1) FROM t",
            "SELECT a - b FROM t WHERE c > d",
            "SELECT a FROM t WHERE a >= 1 AND b - 1 > 2",
            "SELECT a FROM t WHERE note = 'a -> b'",
            "SELECT a FROM t WHERE note = '>>'",
            "SELECT a FROM t WHERE u = 'https://example.com/x'",
            // the single arrow next to a comparison, with and without spaces
            "SELECT FILTER(arr, x->x>1) FROM t",
            "SELECT FILTER(arr, x -> x >= 1), TRANSFORM(arr, y -> y - 1) FROM t",
            // `>` and `>>` without the minus in front
            "SELECT a FROM t WHERE a > 1 AND b > 2",
            "SELECT a FROM t WHERE a >> 1 > 0",
            "SELECT '>>' AS m, '>' AS n FROM t",
            // a minus, then other tokens, then `>>`
            "SELECT a - b AS d FROM t WHERE note = '>>'",
            "SELECT a -1 FROM t WHERE c > -1 AND note = '>>'",
            "SELECT a - //x\n b FROM t WHERE note = '>>'",
            "SELECT a - 1 //x\n FROM t WHERE b > 2",
            "SELECT a FROM t WHERE note = '- > x'",
            "SELECT a FROM t WHERE x = '->' AND y = '>'",
            // the workaround the story gives for a literal holding the operator
            "SELECT a FROM t WHERE note = '-' || '>>'",
            "SELECT a FROM t WHERE note LIKE '%' || '-' || '>>' || '%'"
        ).foreach(sql => withClue(sql + " -> ")(validate(sql)))
    }

    // Timing: many starts, one warm-up, one bound (the story's two seconds),
    // each call on its own daemon thread that is stopped at the bound.
    private val ChainBoundMillis = 2000L
    private val ChainStarts = 40000

    /** None when validation accepts, Some(message) when it refuses with a
      * DatrisException. Fails when it does not return within the bound. */
    private def chainOutcome(label: String, sql: String): Option[String] = {
        @volatile var result: Either[Throwable, Option[String]] = null
        val t = new Thread(
            new Runnable {
                def run(): Unit =
                    result =
                        try { validate(sql); Right(None) }
                        catch {
                            case e: DatrisException => Right(Some(e.getMessage))
                            case e: Throwable => Left(e)
                        }
            },
            "bounded-chain-validation"
        )
        t.setDaemon(true)
        val started = System.nanoTime()
        t.start()
        t.join(ChainBoundMillis)
        if (t.isAlive) {
            val ms = (System.nanoTime() - started) / 1000000L
            t.setPriority(Thread.MIN_PRIORITY)
            try classOf[Thread].getMethod("stop").invoke(t)
            catch { case _: Throwable => () }
            t.join(2000L)
            val state = if (t.isAlive) "still running at minimum priority" else "stopped"
            fail(label + " (" + sql.length + " characters): did not return within " + ChainBoundMillis + " ms (" + state + " after " + ms + " ms)")
        }
        result match {
            case Left(e) => throw e
            case Right(r) => r
        }
    }

    private def warmedOutcome(label: String, sql: String): Option[String] = {
        assert(sql.length < 1000000, label + ": the text must be under the length limit")
        chainOutcome("warm-up", "SELECT a FROM t WHERE b = 1")
        chainOutcome(label, sql)
    }

    test("validation of a long run of operator characters and line comments returns promptly") {
        // the story's three runs, inside an otherwise valid SELECT: accept or refuse, within the bound
        warmedOutcome("20,000 minus signs in a literal", "SELECT a FROM t WHERE b = '" + ("-" * 20000) + "'")
        warmedOutcome("20,000 single arrows in a literal", "SELECT a FROM t WHERE b = '" + ("->" * 20000) + "'")
        warmedOutcome("20,000 line comments", "SELECT a " + ("//x\n" * 20000) + "FROM t")

        def accepted(label: String, sql: String): Unit = {
            val r = warmedOutcome(label, sql)
            assert(r.isEmpty, label + " -> " + r)
        }
        def chained(label: String, sql: String): Unit = {
            val r = warmedOutcome(label, sql)
            assert(r.contains(Chained), label + " -> " + r)
        }

        // the same runs, ending in the operator
        chained("single arrows, the last one completed", "SELECT a FROM t WHERE b = '" + ("->" * 20000) + ">'")
        chained("a minus, 20,000 line comments, then the rest", "SELECT a FROM t -" + ("//x\n" * 20000) + ">" + ("//x\n" * 20000) + "> SELECT 1")
        accepted("a minus, 20,000 line comments, then one comparison", "SELECT a FROM t WHERE b = 1 -" + ("//x\n" * 20000) + "> 1")

        // many starts, each followed by a long run of what may sit between the characters
        accepted("minus signs separated by spaces", "SELECT a " + ("- " * ChainStarts) + "1 FROM t")
        chained("minus signs separated by spaces, then the rest", "SELECT a " + ("- " * ChainStarts) + ">> 1")
        accepted("arrows separated by format and space characters", "SELECT a " + ("->\u200B\u00A0" * ChainStarts) + "1 FROM t")
        // one comment line holding every start: each start's gap runs to the end of the line
        accepted("minus signs each opening a comment on one line", "SELECT a FROM t " + ("-//" * ChainStarts) + "\n1")
        accepted("arrows each opening a comment on one line", "SELECT a FROM t " + ("->//" * ChainStarts) + "\n1")
        chained("arrows each opening a comment on one line, then the last character", "SELECT a FROM t " + ("->//" * ChainStarts) + "\n> SELECT 1")
        accepted("arrows then one long comment line per arrow", "SELECT a FROM t " + ("->//x\n" * ChainStarts) + "1")
    }

    // The keyword that restores a dropped object (Decision 3), built here so
    // this source does not spell out a statement word released checks let through.
    private val RestoreKeyword = "UN" + "DROP"

    test("the keyword that restores a dropped object is refused as a blocked keyword in any letter case; a column whose name begins with it is accepted") {
        cases(RestoreKeyword).foreach { w =>
            Seq(
                "SELECT a FROM t WHERE note = '" + w + "'",
                "SELECT \"" + w + "\" FROM t",
                "SELECT a AS " + w + " FROM t",
                "WITH x AS (SELECT 1 AS " + w + ") SELECT * FROM x"
            ).foreach { sql =>
                val e = intercept[DatrisException](validate(sql))
                assert(e.getMessage == "Query contains blocked keyword: " + RestoreKeyword, sql + " -> " + e.getMessage)
            }
        }
        val column = RestoreKeyword.toLowerCase(Locale.ROOT) + "ped_at"
        validate("SELECT " + column + " FROM t")
        validate("SELECT a FROM t ORDER BY " + column)
    }

    // ---- where a line comment ends (review round 1) ----
    //
    // Where a `//` comment ends is not known here: a tab, carriage return or
    // other control or separator character may be ordinary comment text. A
    // gap is seen when any reading of where each comment ends makes one.

    private val CommentInnerEnds = Seq("\t", "\r", "\u000B", "\u000C", "\u0001", "\u0000", "\u0085", "\u2028", "\u2029")

    test("the flow operator is refused when a line comment between its characters holds a control or separator character") {
        ("//x\ty\n" +: "//x\ry\n" +: CommentInnerEnds.map(c => "//x" + c + "y\n")).foreach { gap =>
            splitOperators(gap).foreach { op =>
                refusedChained("SELECT a FROM t " + op + " SELECT b FROM u")
                refusedChained("SHOW TABLES" + op + "SELECT 1")
            }
        }
        // a comment holding `//`, `-` or `>` itself, read every way
        refusedChained("SELECT 1 -//a//b\tc\n>> SELECT 2")
        refusedChained("SELECT 1 -//a\t>//b\n> SELECT 2")
        refusedChained("SELECT 1 -//a\t>> SELECT 2")
        // the same texts with the comment ended only by the line feed stay accepted when no reading makes the operator
        validate("SELECT a - //x\ty\n b FROM t WHERE note = '>>'")
        validate("SELECT a - 1 //x\ty\n FROM t WHERE b > 2")
        validate("SELECT a FROM t -//x\ty>")
    }

    test("an IDENTIFIER call is refused when a line comment before its parenthesis holds a control or separator character") {
        CommentInnerEnds.foreach { c =>
            refusedNaming("SELECT IDENTIFIER//x" + c + "y\n('f')(1)", "IDENTIFIER")
            refusedNaming("SELECT * FROM IDENTIFIER //x" + c + "y\n ('db.s.t')", "IDENTIFIER")
        }
        validate("SELECT identifier_col //x\ty\n FROM t")
    }
}
