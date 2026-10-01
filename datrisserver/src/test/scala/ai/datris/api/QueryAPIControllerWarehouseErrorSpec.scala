package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** `POST /query/databricks` and `/query/snowflake` used to answer a driver
  *  SQLException with HTTP 500 and the raw ~16 KB driver dump. The handlers now
  *  route every non-DatrisException through these helpers. */
class QueryAPIControllerWarehouseErrorSpec extends AnyFunSuite {

    private def errorOf(body: String): String = JsonParser.parseString(body).getAsJsonObject.get("error").getAsString

    private def databricksDump(cls: String, sentence: String): java.sql.SQLException = {
        val frames = (1 to 110).map(i => "\n\tat org.apache.spark.sql.execution.SparkPlan.executeQuery" + i + "(SparkPlan.scala:" + i + ")").mkString
        val msg = "[Databricks][JDBCDriver](500051) ERROR processing query/statement. Error Code: 0, SQL state: 42P01, " +
            "Query: SELECT * FROM dat***, Error message from Server: org.apache.hive.service.cli.HiveSQLException: Error running query: [" + cls +
            "] org.apache.spark.sql.catalyst.ExtendedAnalysisException: [" + cls + "] " + sentence +
            " Verify the spelling and correctness of the schema and catalog. SQLSTATE: 42P01; line 1 pos 14" +
            frames + "\nTGetOperationStatusResp(status:TStatus(statusCode:ERROR_STATUS, infoMessages:[*org.apache.hive.service.cli.HiveSQLException:" +
            ("x" * 6000) + "))"
        new java.sql.SQLException(msg)
    }

    test("Databricks TABLE_OR_VIEW_NOT_FOUND dump -> 404 with the class and first sentence only") {
        val e = databricksDump("TABLE_OR_VIEW_NOT_FOUND", "The table or view `datris`.`default`.`nosuch` cannot be found.")
        assert(e.getMessage.length > 10000)
        val (status, body) = QueryAPIController.databricksQueryError(e)
        assert(status == 404, body)
        assert(errorOf(body) == "[TABLE_OR_VIEW_NOT_FOUND] The table or view `datris`.`default`.`nosuch` cannot be found.", body)
        assert(!body.contains("TGetOperationStatusResp") && !body.contains("\\tat "), body)
    }

    test("Databricks INVALID_PARAMETER_VALUE -> 400, PERMISSION_DENIED -> 403, other class -> 502") {
        assert(QueryAPIController.databricksQueryError(databricksDump("INVALID_PARAMETER_VALUE", "Bad value."))._1 == 400)
        assert(QueryAPIController.databricksQueryError(databricksDump("PERMISSION_DENIED", "User does not have SELECT on Table 't'."))._1 == 403)
        assert(QueryAPIController.databricksQueryError(databricksDump("INTERNAL_ERROR", "Something broke."))._1 == 502)
    }

    test("Databricks error without a class -> 502 with the first line, capped at 500 chars") {
        val (status, body) = QueryAPIController.databricksQueryError(new java.sql.SQLException("Connection reset " + ("y" * 2000) + "\n\tat a.b.C.d(C.java:1)"))
        assert(status == 502)
        assert(errorOf(body).length <= 500, body)
        assert(errorOf(body).startsWith("Connection reset"))
    }

    test("Snowflake SQLException -> 400 with the message lines joined and no stack text") {
        val e = new java.sql.SQLException(
            "SQL compilation error:\nObject 'ANALYTICS.PUBLIC.NOSUCH' does not exist or not authorized.\n\tat net.snowflake.client.jdbc.SnowflakeUtil.checkErrorAndThrowExceptionSub(SnowflakeUtil.java:127)"
        )
        val (status, body) = QueryAPIController.snowflakeQueryError(e)
        assert(status == 400, body)
        assert(errorOf(body) == "SQL compilation error: Object 'ANALYTICS.PUBLIC.NOSUCH' does not exist or not authorized.", body)
    }

    test("Snowflake exception with no message -> class name, long message capped") {
        assert(errorOf(QueryAPIController.snowflakeQueryError(new RuntimeException())._2) == "RuntimeException")
        assert(errorOf(QueryAPIController.snowflakeQueryError(new java.sql.SQLException("z" * 3000))._2).length <= 500)
    }

    test("only SQLException failures (anywhere in the cause chain) are translated; others stay 500") {
        assert(!QueryAPIController.isSqlFailure(new RuntimeException("boom")))
        assert(!QueryAPIController.isSqlFailure(new NumberFormatException("For input string: \"abc\"")))
        assert(!QueryAPIController.isSqlFailure(new NullPointerException()))
        val dump = databricksDump("TABLE_OR_VIEW_NOT_FOUND", "The table or view `datris`.`default`.`nosuch` cannot be found.")
        assert(QueryAPIController.isSqlFailure(dump))
        val wrapped = new RuntimeException("query failed", dump)
        assert(QueryAPIController.isSqlFailure(wrapped))
        val (status, body) = QueryAPIController.databricksQueryError(wrapped)
        assert(status == 404, body)
        assert(errorOf(body) == "[TABLE_OR_VIEW_NOT_FOUND] The table or view `datris`.`default`.`nosuch` cannot be found.", body)
    }

    test("Snowflake transport / warehouse failures -> 502") {
        val e = new java.sql.SQLException("JDBC driver encountered communication error. Message: HTTP status=503.")
        assert(QueryAPIController.snowflakeQueryError(e)._1 == 502)
        val missing = new java.sql.SQLException("Object 'X' does not exist or not authorized.")
        assert(QueryAPIController.snowflakeQueryError(missing)._1 == 400)
        val access = new java.sql.SQLException("SQL access control error:\nInsufficient privileges to operate on table 'T'")
        assert(QueryAPIController.snowflakeQueryError(access)._1 == 400)
    }

    test("Databricks SQL errors in the caller's query -> 400") {
        val syntax = QueryAPIController.databricksQueryError(databricksDump("PARSE_SYNTAX_ERROR", "Syntax error at or near 'FORM'."))
        assert(syntax._1 == 400, syntax._2)
        assert(errorOf(syntax._2) == "[PARSE_SYNTAX_ERROR] Syntax error at or near 'FORM'.", syntax._2)
        val col = QueryAPIController.databricksQueryError(
            databricksDump("UNRESOLVED_COLUMN.WITH_SUGGESTION", "A column, variable, or function parameter with name `nme` cannot be resolved.")
        )
        assert(col._1 == 400, col._2)
        assert(
            errorOf(col._2).startsWith("[UNRESOLVED_COLUMN.WITH_SUGGESTION] A column, variable, or function parameter with name `nme` cannot be resolved."),
            col._2
        )
        Seq("AMBIGUOUS_REFERENCE", "DATATYPE_MISMATCH.BINARY_OP_DIFF_TYPES", "CAST_INVALID_INPUT", "UNRESOLVED_ROUTINE")
            .foreach(cls => assert(QueryAPIController.databricksQueryError(databricksDump(cls, "Bad SQL."))._1 == 400, cls))
        // Unknown classes stay upstream failures.
        assert(QueryAPIController.databricksQueryError(databricksDump("SOME_NEW_CLASS", "Something."))._1 == 502)
    }

    test("parseLimit: numbers and numeric strings parse, absent uses the default, junk is a 400 InvalidLimitException") {
        assert(QueryAPIController.parseLimit(null, 100) == 100)
        assert(QueryAPIController.parseLimit(java.lang.Integer.valueOf(25), 100) == 25)
        assert(QueryAPIController.parseLimit(java.lang.Double.valueOf(-1.0), 100) == -1)
        assert(QueryAPIController.parseLimit("50", 20) == 50)
        val e = intercept[QueryAPIController.InvalidLimitException](QueryAPIController.parseLimit("abc", 100))
        assert(e.getMessage == "limit must be an integer (got \"abc\")")
        assert(errorOf(QueryAPIController.jsonError(e.getMessage)) == "limit must be an integer (got \"abc\")")
    }
}
