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
  * Databricks half of Acceptance. Pure: no workspace, no network.
  *
  * The story makes `DatabricksQueryUtil.validateQuery` `private[util]` and adds
  * `BLOCKED_FUNCTIONS` (the Background list; the owner confirmed every `ai_*`
  * function and `vector_search` are refused), matched as whole words and
  * refused as `Query contains blocked function: <name>` (Decisions 4 and 6).
  * `limit: -1` runs under 300 s, a bounded query under 30 s (Decision 5); the
  * choice is read through a `timeoutSeconds(limit)` seam shaped like
  * `PostgresQueryUtil.timeoutSeconds`. Members are reached by name
  * (QuerySafetyReflect) so this spec compiles before they exist. */
class DatabricksQueryValidationSpec extends AnyFunSuite {

    private def validate(sql: String): Unit = QuerySafetyReflect.call(DatabricksQueryUtil, "validateQuery", sql)

    private def refusedNaming(sql: String, name: String): Unit = {
        val e = intercept[DatrisException](validate(sql))
        assert(e.getMessage.startsWith("Query contains blocked function: "), sql + " -> " + e.getMessage)
        assert(e.getMessage.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)), sql + " -> " + e.getMessage)
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
        "http_request(conn => 'c', method => 'GET', path => '/x')" -> "http_request",
        "remote_query('conn', database => 'd', query => 'q')" -> "remote_query",
        "ai_query('endpoint', 'prompt')" -> "ai_query",
        "ai_gen('prompt')" -> "ai_gen",
        "ai_classify(txt, ARRAY('a', 'b'))" -> "ai_classify",
        "ai_extract(txt, ARRAY('person'))" -> "ai_extract",
        "ai_fix_grammar(txt)" -> "ai_fix_grammar",
        "ai_mask(txt, ARRAY('email'))" -> "ai_mask",
        "ai_similarity(a, b)" -> "ai_similarity",
        "ai_summarize(txt)" -> "ai_summarize",
        "ai_translate(txt, 'es')" -> "ai_translate",
        "ai_analyze_sentiment(txt)" -> "ai_analyze_sentiment",
        "ai_parse_document(content)" -> "ai_parse_document",
        // owner decision: every ai_* function, not only the ones known today
        "ai_some_future_function(txt)" -> "ai_some_future_function",
        "vector_search(index => 'i', query => 'q', num_results => 5)" -> "vector_search",
        "secret('scope', 'key')" -> "secret",
        "try_secret('scope', 'key')" -> "try_secret",
        "read_files('s3://bucket/path')" -> "read_files",
        "read_kafka(bootstrapServers => 'h:9092', subscribe => 't')" -> "read_kafka",
        "read_kinesis(streamName => 's')" -> "read_kinesis",
        "read_pubsub(subscriptionId => 's', topicId => 't', projectId => 'p')" -> "read_pubsub",
        "read_pulsar(serviceUrl => 'u', topic => 't')" -> "read_pulsar",
        "java_method('java.lang.System', 'exit', 0)" -> "java_method",
        "reflect('java.lang.System', 'exit', 0)" -> "reflect"
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
        // table-valued forms
        refusedNaming("SELECT * FROM read_files('s3://bucket/path', format => 'csv')", "read_files")
        refusedNaming("SELECT * FROM VECTOR_SEARCH(index => 'i', query_text => 'q', num_results => 5)", "vector_search")
        refusedNaming("SELECT secret ('scope', 'key')", "secret")
    }

    test("Databricks: get(), a column named secret_id and a table named ai_results are accepted") {
        Seq(
            "SELECT get(arr, 0) FROM t",
            "SELECT GET(arr, 1) AS second FROM t",
            "SELECT secret_id FROM t",
            "SELECT * FROM main.default.ai_results",
            "SELECT secret_id, ai_score, reflection, http_requests FROM ai_results WHERE read_files_count > 0"
        ).foreach(validate)
        // whole-word matching is what lets these through: the list holds the bare names
        val listed = QuerySafetyReflect.stringList(DatabricksQueryUtil, "BLOCKED_FUNCTIONS").map(_.toLowerCase(Locale.ROOT))
        Seq("secret", "try_secret", "reflect", "http_request", "read_files", "vector_search").foreach { n =>
            assert(listed.contains(n), n + " in " + listed)
        }
        assert(!listed.contains("get"), listed)
    }

    test("SELECT, WITH, SHOW and DESCRIBE reads from the existing docs examples are accepted") {
        Seq(
            // docs/api-reference/query-api.mdx
            "SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000",
            "SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000 LIMIT 10",
            "WITH big AS (SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000) SELECT * FROM big",
            // query_databricks tool description / docs/destinations/databricks.mdx
            "SHOW CATALOGS",
            "SHOW SCHEMAS IN main",
            "SHOW TABLES IN main.default",
            "DESCRIBE TABLE main.default.stock_price",
            "SELECT column_name, data_type FROM main.information_schema.columns WHERE table_name = 'stock_price'"
        ).foreach(validate)
    }

    test("limit -1 sets the 300 s timeout; a positive limit keeps 30 s") {
        assert(QuerySafetyReflect.timeoutFor(DatabricksQueryUtil, -1) == 300)
        assert(QuerySafetyReflect.timeoutFor(DatabricksQueryUtil, 7) == 30)
        assert(QuerySafetyReflect.timeoutFor(DatabricksQueryUtil, 100) == 30)
        assert(QuerySafetyReflect.timeoutFor(DatabricksQueryUtil, 0) == 30)
    }

    // ---- review round 1 ----

    test("an IDENTIFIER call is refused, since it can name any function") {
        cases("IDENTIFIER('secret')('scope', 'key')").foreach { c =>
            val e = intercept[DatrisException](validate("SELECT " + c))
            assert(e.getMessage == "Query contains blocked function: IDENTIFIER", e.getMessage)
        }
        refusedNaming("SELECT IDENTIFIER ('sec' || 'ret')('scope', 'key')", "identifier")
        refusedNaming("SELECT * FROM identifier('main.default.t')", "identifier")
        validate("SELECT identifier_type, identifier FROM t")
        assert(QuerySafetyReflect.stringList(DatabricksQueryUtil, "BLOCKED_FUNCTIONS").map(_.toLowerCase(Locale.ROOT)).contains("identifier"))
    }
}
