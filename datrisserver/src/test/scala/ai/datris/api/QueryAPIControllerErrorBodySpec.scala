package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** Story: objectstore-endpoint-ssrf-and-error-bodies (B3).
  *
  *  The catch-all 500 paths in `QueryAPIController` (8 sites) and
  *  `PipelineAPIController` (4 sites) return
  *  `Throwables.getStackTraceAsString(e)` as the response body, which the MCP
  *  server's `_call` passes straight through to agents. The body must become a
  *  one-line JSON object built from the message only. Both controllers need a
  *  live config DB and an API-key validator to reach those catch blocks, so
  *  the body builder is lifted into ONE seam that this spec drives directly.
  *
  *  Pinned seam (shared by both controllers — PipelineAPIController calls the
  *  same `QueryAPIController.errorBody`; there is no second copy):
  *
  *  {{{
  *  object QueryAPIController {
  *      // Exactly the 500 body: {"error": "<e.getMessage>"}. Null-safe
  *      // (a null message still yields a string value, never "null" as JSON
  *      // null and never an empty body), quotes/backslashes/newlines escaped
  *      // so the result is always a single valid JSON line, and never a
  *      // stack frame. logger.error(getStackTraceAsString) stays as-is.
  *      private[api] def errorBody(e: Throwable): String
  *  }
  *  }}}
  */
class QueryAPIControllerErrorBodySpec extends AnyFunSuite {

    private def parse(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private def thrownFromDatris(message: String): Throwable =
        // A real throw so the trace carries `at ai.datris.` frames.
        try { throw new DatrisException(message) }
        catch { case e: DatrisException => e }

    test("errorBody returns a JSON object with a single 'error' key holding the message") {
        val body = QueryAPIController.errorBody(thrownFromDatris("Iceberg read failed for pipeline orders"))
        val obj = parse(body)
        assert(obj.keySet().size() == 1, s"expected exactly one key 'error', got ${obj.keySet()}: $body")
        assert(obj.get("error").getAsString == "Iceberg read failed for pipeline orders", body)
    }

    test("errorBody never contains stack frames or the exception class name") {
        val e = thrownFromDatris("boom")
        val body = QueryAPIController.errorBody(e)
        assert(!body.contains("at ai.datris."), s"stack frames leaked into the body: $body")
        assert(!body.contains("\tat "), s"stack frames leaked into the body: $body")
        assert(!body.contains(classOf[DatrisException].getName), s"exception class name leaked into the body: $body")
        assert(!body.contains("\n"), s"body must be a single line: $body")
    }

    test("errorBody handles a null message without emitting JSON null or crashing") {
        val body = QueryAPIController.errorBody(new RuntimeException(null: String))
        val obj = parse(body)
        assert(obj.has("error"), body)
        assert(obj.get("error").isJsonPrimitive && obj.get("error").getAsJsonPrimitive.isString, s"'error' must be a JSON string: $body")
        assert(!body.contains("at ai.datris."), body)
    }

    test("errorBody escapes embedded double quotes and backslashes so the body stays valid JSON") {
        val message = """secret "prod/s3" not found at path C:\vault\secrets"""
        val body = QueryAPIController.errorBody(new RuntimeException(message))
        val obj = parse(body) // throws if the quotes were not escaped
        assert(obj.get("error").getAsString == message, body)
    }

    test("errorBody escapes embedded newlines so the body is a single line") {
        val body = QueryAPIController.errorBody(new RuntimeException("line one\nline two"))
        assert(!body.contains("\n"), s"raw newline leaked: $body")
        assert(parse(body).get("error").getAsString == "line one\nline two", body)
    }

    test("errorBody is message-only even when the exception has a cause with its own trace") {
        val cause = thrownFromDatris("root cause")
        val body = QueryAPIController.errorBody(new RuntimeException("wrapper", cause))
        assert(parse(body).get("error").getAsString == "wrapper", body)
        assert(!body.contains("at ai.datris."), body)
        assert(!body.contains("Caused by"), body)
    }
}
