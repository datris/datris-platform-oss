package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** The unity-catalog state body keeps `enabledBy: null` explicit and omits every
  * other null, nested ones included (plans/stories/uc-default-enabled.md). */
class UnityCatalogStateJsonSpec extends AnyFunSuite {

    private def managedPreCommit(): JsonObject = {
        val out = new JsonObject
        out.addProperty("pipeline", "orders_lake")
        out.addProperty("enabled", false)
        out.addProperty("enabledBy", null.asInstanceOf[String])
        val coords = new JsonObject
        coords.addProperty("catalog", "main")
        coords.addProperty("schema", "default")
        coords.addProperty("table", "orders_lake")
        coords.addProperty("kind", "iceberg")
        coords.addProperty("location", null.asInstanceOf[String]) // managed, no commit yet
        out.add("coordinates", coords)
        out.addProperty("registeredMetadataLocation", null.asInstanceOf[String])
        val arr = new JsonArray
        val item = new JsonObject
        item.addProperty("a", null.asInstanceOf[String])
        item.addProperty("b", "x")
        arr.add(item)
        out.add("items", arr)
        out
    }

    test("managed pre-commit: no nested \"location\":null, enabledBy:null stays explicit") {
        val json = UnityCatalogStateJson.toJson(managedPreCommit())
        assert(!json.contains("\"location\":null"), json)
        assert(json.contains("\"enabledBy\":null"), json)
        val parsed = JsonParser.parseString(json).getAsJsonObject
        assert(parsed.has("enabledBy") && parsed.get("enabledBy").isJsonNull, json)
        assert(!parsed.getAsJsonObject("coordinates").has("location"), json)
        assert(parsed.getAsJsonObject("coordinates").get("catalog").getAsString == "main", json)
        assert(!parsed.has("registeredMetadataLocation"), json)
        val item = parsed.getAsJsonArray("items").get(0).getAsJsonObject
        assert(!item.has("a") && item.get("b").getAsString == "x", json)
    }

    test("enabledBy set: serialized as its value") {
        val out = new JsonObject
        out.addProperty("enabledBy", "default")
        assert(UnityCatalogStateJson.toJson(out) == "{\"enabledBy\":\"default\"}")
    }

    test("enabledBy is only kept at the top level") {
        val out = new JsonObject
        val nested = new JsonObject
        nested.addProperty("enabledBy", null.asInstanceOf[String])
        out.add("nested", nested)
        assert(UnityCatalogStateJson.toJson(out) == "{\"nested\":{}}")
    }
}
