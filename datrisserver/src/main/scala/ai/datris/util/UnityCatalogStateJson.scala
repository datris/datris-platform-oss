package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{GsonBuilder, JsonElement, JsonObject}

import scala.collection.JavaConverters._

/** Serializes the `GET /pipelines/{name}/unity-catalog` body. Null fields are
  * omitted at every depth (as plain Gson always did), except the top-level
  * keys in `keepTopLevel` (`enabledBy`), which stay as an explicit JSON null. */
object UnityCatalogStateJson {

    val KeepTopLevel: Set[String] = Set("enabledBy")

    /** Remove JsonNull members in place, recursing into nested objects and
      * arrays; top-level keys in `keepTopLevel` keep an explicit null. */
    def stripNulls(obj: JsonObject, keepTopLevel: Set[String] = KeepTopLevel): JsonObject = {
        obj.entrySet.asScala.filter(e => e.getValue.isJsonNull && !keepTopLevel.contains(e.getKey)).map(_.getKey).toList.foreach(obj.remove)
        obj.entrySet.asScala.foreach(e => strip(e.getValue))
        obj
    }

    private def strip(el: JsonElement): Unit =
        if (el.isJsonObject) stripNulls(el.getAsJsonObject, Set.empty)
        else if (el.isJsonArray) el.getAsJsonArray.asScala.foreach(strip)

    def toJson(obj: JsonObject): String =
        new GsonBuilder().serializeNulls().create().toJson(stripNulls(obj))
}
