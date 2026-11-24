package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

/** Story: Snowflake query: a call to a model or search service is refused, as
  * on Databricks. Lead rulings on points the story leaves open, added beside
  * SnowflakeAiFunctionSpec so that spec stays as committed:
  *  - a name is caught when a letter in it folds to ASCII only by
  *    lower-casing or by the per-character fold of `equalsIgnoreCase` (the
  *    Kelvin sign, the dotted capital I) or by upper-casing to two letters
  *    (the sharp s); the refused name is reported in ASCII upper case;
  *  - the method-call rule covers string literals, with a gap allowed
  *    between `!`, the name and `(`; `!=`, `!<`, `!>` and a `!` followed by
  *    anything other than a name are not method calls;
  *  - a longer identifier that merely ends in the database name
  *    (`MY_SNOWFLAKE.CORTEX.`) is another database and is accepted; a quoted
  *    `"SNOWFLAKE"` part counts;
  *  - `SNOWFLAKE.ML` and other schemas are out of scope and stay accepted. */
class SnowflakeAiFunctionRulingsSpec extends AnyFunSuite {

    private def validate(sql: String): Unit = QuerySafetyReflect.call(SnowflakeQueryUtil, "validateQuery", sql)

    private val Prefix = "Query contains blocked function: "

    private def refusedAs(sql: String, name: String): Unit = {
        val e = withClue(sql + " was accepted: ")(intercept[DatrisException](validate(sql)))
        assert(e.getMessage == Prefix + name, sql + " -> " + e.getMessage)
    }

    private def accepted(sql: String): Unit = withClue(sql + " -> ")(validate(sql))

    test("a letter that folds to ASCII only by lower-casing or by the equalsIgnoreCase fold does not hide a name") {
        // the Kelvin sign, U+212A, lower-cases to k
        refusedAs("SELECT SNOWFLAKE.CORTEX.COMPLETE('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT \"SNOWFLAKE\".\"CORTEX\".\"COMPLETE\"('m', 'p')", "SNOWFLAKE.CORTEX.COMPLETE")
        refusedAs("SELECT m!KMEANS(a) FROM t", "!KMEANS")
        // the dotted capital I, U+0130: Character.toLowerCase gives i
        refusedAs("SELECT Aİ_COMPLETE('m', 'p')", "AI_COMPLETE")
        refusedAs("SELECT snowflake.cortex.İTEM_FUNCTION(a) FROM t", "SNOWFLAKE.CORTEX.ITEM_FUNCTION")
        // the sharp s upper-cases to SS
        refusedAs("SELECT AI_CLAßIFY(a, ['x', 'y'])", "AI_CLASSIFY")
        // the reported name is in ASCII upper case whatever letters spelled it
        refusedAs("SELECT ai_ﬁnd(a) FROM t", "AI_FIND")
        refusedAs("SELECT m!ﬆep(a) FROM t", "!STEP")
        refusedAs("SELECT snowflake.cortex.ſentiment(a) FROM t", "SNOWFLAKE.CORTEX.SENTIMENT")
        // the same letters in names that are not refused
        accepted("SELECT Key, Aİ_SCORE, my_snowflaKe FROM t")
    }

    test("an exclamation mark in a string literal followed by a word and a parenthesis is refused; the way round is accepted") {
        refusedAs("SELECT a FROM t WHERE note = 'Done! Next (soon)'", "!NEXT")
        refusedAs("SELECT a FROM t WHERE note = 'Done! Next　(soon)'", "!NEXT")
        // the way round: split the literal so no name and `(` follow the `!`
        accepted("SELECT a FROM t WHERE note = 'Done!' || ' Next (soon)'")
        // a `!` followed by anything other than a name is not a method call
        accepted("SELECT a FROM t WHERE note = 'Done!' AND b = f(c)")
        accepted("SELECT a FROM t WHERE note = 'wow!(x)'")
        accepted("SELECT a FROM t WHERE note = 'step ! 1 (x)'")
        accepted("SELECT a FROM t WHERE note = 'hey!!! (x)'")
        // comparison operators
        accepted("SELECT a FROM t WHERE a != b AND c !< d AND e !> f(g)")
        accepted("SELECT a FROM t WHERE a!=(b) OR c!<(d) OR e!>(f)")
    }

    test("an identifier that only ends in the database name is another database; a quoted SNOWFLAKE part counts") {
        accepted("SELECT a FROM MY_SNOWFLAKE.CORTEX.T")
        accepted("SELECT a FROM \"MY_SNOWFLAKE\".CORTEX.T")
        accepted("SELECT a FROM SNOWFLAKE1.CORTEX.T")
        accepted("SELECT a FROM SNOWFLAKE_DB.CORTEX.T")
        accepted("SELECT a FROM $SNOWFLAKE.CORTEX.T")
        accepted("SELECT a FROM SNOWFLAKE.MY_CORTEX.T")
        refusedAs("SELECT a FROM \"SNOWFLAKE\".CORTEX.T", "SNOWFLAKE.CORTEX.T")
        refusedAs("SELECT a FROM \"snowflake\".\"cortex\".t", "SNOWFLAKE.CORTEX.T")
        refusedAs("SELECT a FROM t, x.\"SNOWFLAKE\".\"CORTEX\".\"T\"", "SNOWFLAKE.CORTEX.T")
        refusedAs("SELECT (SNOWFLAKE.CORTEX.COMPLETE('m', a)) FROM t", "SNOWFLAKE.CORTEX.COMPLETE")
    }

    test("other SNOWFLAKE schemas are out of scope and stay accepted") {
        accepted("SELECT * FROM SNOWFLAKE.ML.SOME_VIEW")
        accepted("SELECT * FROM TABLE(SNOWFLAKE.ML.SOME_FUNCTION(a))")
        accepted("SELECT * FROM SNOWFLAKE.ACCOUNT_USAGE.QUERY_HISTORY WHERE query_text LIKE '%cortex%'")
    }

    test("the existing function rules are read first and are unchanged") {
        refusedAs("SELECT SYSTEM$WAIT(1), AI_COMPLETE('m', 'p')", "SYSTEM$WAIT")
        refusedAs("SELECT AI_COMPLETE('m', 'p'), s.NEXTVAL", "NEXTVAL")
        accepted("SELECT SYSTEM$TYPEOF(a), ai_score FROM t")
    }

    // ---- timing at the length limit ----

    private val BoundMillis = 10000L
    private val Length = 999000

    private def blockedFunction(sql: String): AnyRef = QuerySafetyReflect.call(SnowflakeQueryUtil, "blockedFunction", sql)

    private def bounded(label: String, sql: String): Unit = {
        @volatile var error: Throwable = null
        val t = new Thread(
            new Runnable {
                def run(): Unit =
                    try blockedFunction(sql)
                    catch { case e: Throwable => error = e }
            },
            "bounded-ai-function-ruling"
        )
        t.setDaemon(true)
        t.start()
        t.join(BoundMillis)
        if (t.isAlive) {
            t.setPriority(Thread.MIN_PRIORITY)
            try classOf[Thread].getMethod("stop").invoke(t)
            catch { case _: Throwable => () }
            t.join(2000L)
            fail(label + " (" + sql.length + " characters): did not return within " + BoundMillis + " ms")
        }
        if (error != null) throw error
    }

    private def repeated(unit: String): String = {
        val sb = new StringBuilder("SELECT ")
        while (sb.length + unit.length <= Length) sb.append(unit)
        sb.toString
    }

    test("the new checks stay fast at the length limit, letters that fold to several included") {
        bounded("warm-up", "SELECT a FROM t WHERE b = 1")
        Seq(
            "ﬃ",
            "AI_ﬃ",
            "\"AI_X\"\"",
            "\"",
            "SNOWFLAKE . \"CORTEX\" //\t.",
            "! \"a //\t",
            "!a //\t",
            "AI_X //\t",
            "x.\"SNOWFLAKE\".\"CORTEX\""
        ).foreach(unit => bounded("repeated \"" + unit + "\"", repeated(unit)))
    }
}
