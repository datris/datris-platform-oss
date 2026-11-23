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

    test("an IDENTIFIER call is refused; GETNEXTVAL is refused; a // line comment is refused") {
        cases("IDENTIFIER('SYSTEM$TYPEOF')(1)").foreach(c => refusedNaming("SELECT " + c, "IDENTIFIER"))
        refusedNaming("SELECT * FROM IDENTIFIER ('db.s.t')", "IDENTIFIER")
        cases("TABLE(GETNEXTVAL(seq1))").foreach(c => refusedNaming("SELECT s.v FROM t, " + c + " s", "GETNEXTVAL"))
        val e = intercept[DatrisException](validate("SELECT a //x\n FROM t"))
        assert(e.getMessage == "SQL comments are not allowed in queries", e.getMessage)
        // a column whose name only contains the word is not the call
        validate("SELECT identifier_type, getnextval_count FROM t")
    }
}
