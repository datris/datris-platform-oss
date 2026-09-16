package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.ObjectStoreQueryUtil.QueryResult
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.util.{ArrayList => JArrayList, HashMap => JHashMap, List => JList, Map => JMap}

/** Story: Iceberg in MCP tools, wizard and prompt wording — story-3 handoff.
  *
  *  `QueryAPIController.queryObjectStore` hand-builds its response map, so the
  *  `snapshotId` / `snapshotTimestamp` that `ObjectStoreQueryUtil.QueryResult`
  *  already carries never leave the server. The controller needs a live config
  *  DB and an API-key validator to reach that code, so the map + Gson step must
  *  be lifted into a seam this spec drives directly:
  *
  *  {{{
  *  object QueryAPIController {
  *      // Exactly the JSON body POST /api/v1/query/objectstore returns for a
  *      // successful read: 8 keys (pipeline, path, format, columns, results,
  *      // count, snapshotId, snapshotTimestamp), serializeNulls so parquet and
  *      // ORC results emit an explicit null for both snapshot fields rather
  *      // than omitting them. snapshotId is emitted as a JSON STRING of decimal
  *      // digits (Long.toString), never a JSON number: real Iceberg ids exceed
  *      // 2^53 and a browser cannot round-trip them as numbers. QueryResult
  *      // keeps the Long internally.
  *      private[api] def objectStoreResponseJson(pipeline: String, result: QueryResult): String
  *  }
  *  }}}
  *
  *  and `queryObjectStore` must return that string as its 200 body.
  */
class QueryAPIControllerObjectStoreResponseSpec extends AnyFunSuite {

    private def cols(names: String*): JList[String] = {
        val l = new JArrayList[String](); names.foreach(l.add); l
    }

    private def rows(n: Int): JList[JMap[String, Any]] = {
        val l = new JArrayList[JMap[String, Any]]()
        (1 to n).foreach { i =>
            val m = new JHashMap[String, Any](); m.put("id", Integer.valueOf(i)); m.put("name", "row" + i); l.add(m)
        }
        l
    }

    private def parse(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    test("iceberg result: snapshotId is a JSON string of decimal digits and snapshotTimestamp an ISO instant") {
        val result = QueryResult(
            cols("id", "name"),
            rows(2),
            "s3a://bucket/orders",
            "iceberg",
            snapshotId = java.lang.Long.valueOf(8533883885102256461L),
            snapshotTimestamp = "2026-09-16T12:34:56Z"
        )
        val body = parse(QueryAPIController.objectStoreResponseJson("orders", result))

        assert(body.has("snapshotId"), body.toString)
        val id = body.get("snapshotId")
        assert(id.isJsonPrimitive && id.getAsJsonPrimitive.isString, "snapshotId must be a JSON string, got " + id.toString)
        assert(id.getAsString.matches("^\\d+$"), id.getAsString)
        assert(id.getAsString == java.lang.Long.valueOf(8533883885102256461L).toString)
        assert(body.get("snapshotTimestamp").getAsJsonPrimitive.isString)
        assert(body.get("snapshotTimestamp").getAsString == "2026-09-16T12:34:56Z")
    }

    test("parquet result: both snapshot fields are present as explicit JSON null") {
        val result = QueryResult(cols("id", "name"), rows(1), "s3a://bucket/orders", "parquet")
        val json = QueryAPIController.objectStoreResponseJson("orders", result)
        val body = parse(json)

        assert(body.has("snapshotId"), "snapshotId key must be present (serializeNulls): " + json)
        assert(body.get("snapshotId").isJsonNull, json)
        assert(body.has("snapshotTimestamp"), "snapshotTimestamp key must be present (serializeNulls): " + json)
        assert(body.get("snapshotTimestamp").isJsonNull, json)
    }

    test("orc result: same explicit nulls as parquet") {
        val body = parse(QueryAPIController.objectStoreResponseJson("orders", QueryResult(cols("id"), rows(0), "s3a://b/p", "orc")))
        assert(body.get("snapshotId").isJsonNull && body.get("snapshotTimestamp").isJsonNull, body.toString)
    }

    test("the six existing fields are unchanged around the two new ones") {
        val result = QueryResult(
            cols("id", "name"),
            rows(3),
            "s3a://bucket/orders",
            "iceberg",
            snapshotId = java.lang.Long.valueOf(42L),
            snapshotTimestamp = "2026-09-16T00:00:00Z"
        )
        val body = parse(QueryAPIController.objectStoreResponseJson("orders", result))

        assert(body.get("pipeline").getAsString == "orders")
        assert(body.get("path").getAsString == "s3a://bucket/orders")
        assert(body.get("format").getAsString == "iceberg")
        assert(body.getAsJsonArray("columns").size() == 2)
        assert(body.getAsJsonArray("results").size() == 3)
        assert(body.get("count").getAsInt == 3)
        assert(
            body.keySet().size() == 8,
            "expected exactly pipeline, path, format, columns, results, count, snapshotId, snapshotTimestamp; got " + body.keySet()
        )
    }
}
