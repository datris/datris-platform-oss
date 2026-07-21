package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable

/** Server-side diff helpers for the version-history UI / MCP tools. Returns
  * ready-to-render structures so the client renders, not computes
  * ([feedback_server_logic]).
  *
  *  - [[configDiff]]: structural field-by-field diff of two config JSON snapshots,
  *    flattened to dotted paths so nested pipeline configs diff at any depth.
  *  - [[scriptDiff]]: line-level LCS diff of two tap scripts. */
object VersionDiff {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** One changed/added/removed leaf field. `change` ∈ added | removed | changed. */
    case class FieldChange(path: String, before: String, after: String, change: String)

    /** Flatten a JSON tree to dotted leaf paths → string value. Objects recurse
      * by key, arrays by index; primitives/null become leaves. */
    private def flatten(prefix: String, el: JsonElement, acc: mutable.LinkedHashMap[String, String]): Unit = {
        if (el == null || el.isJsonNull) {
            acc(prefix) = "null"
        } else if (el.isJsonObject) {
            val obj = el.getAsJsonObject
            val it = obj.entrySet().iterator()
            if (!it.hasNext && prefix.nonEmpty) acc(prefix) = "{}"
            while (it.hasNext) {
                val e = it.next()
                val p = if (prefix.isEmpty) e.getKey else prefix + "." + e.getKey
                flatten(p, e.getValue, acc)
            }
        } else if (el.isJsonArray) {
            val arr: JsonArray = el.getAsJsonArray
            if (arr.size() == 0 && prefix.nonEmpty) acc(prefix) = "[]"
            var i = 0
            while (i < arr.size()) {
                flatten(prefix + "[" + i + "]", arr.get(i), acc)
                i += 1
            }
        } else {
            acc(prefix) = el.getAsJsonPrimitive.getAsString
        }
    }

    private def flattenConfig(json: String): mutable.LinkedHashMap[String, String] = {
        val acc = new mutable.LinkedHashMap[String, String]()
        try {
            val el = JsonParser.parseString(if (json == null) "{}" else json)
            flatten("", el, acc)
        } catch {
            case e: Exception =>
                logger.warn("VersionDiff: failed to parse config JSON snapshot, diffing it as empty", e)
                ()
        }
        acc
    }

    /** Field-by-field diff of two serialized configs. `before` = the older
      * (against) snapshot, `after` = the selected snapshot. */
    def configDiff(beforeJson: String, afterJson: String): List[FieldChange] = {
        val before = flattenConfig(beforeJson)
        val after = flattenConfig(afterJson)
        val keys = (before.keys ++ after.keys).toList.distinct.sorted
        keys.flatMap { k =>
            (before.get(k), after.get(k)) match {
                case (Some(b), Some(a)) if b != a => Some(FieldChange(k, b, a, "changed"))
                case (Some(_), Some(_)) => None
                case (None, Some(a)) => Some(FieldChange(k, null, a, "added"))
                case (Some(b), None) => Some(FieldChange(k, b, null, "removed"))
                case (None, None) => None
            }
        }
    }

    /** One line in a script diff. `type` ∈ ctx | add | del. */
    case class DiffLine(`type`: String, text: String)

    /** Line-level LCS diff of two scripts (before → after). */
    def scriptDiff(before: String, after: String): List[DiffLine] = {
        val a = if (before == null) Array.empty[String] else before.split("\n", -1)
        val b = if (after == null) Array.empty[String] else after.split("\n", -1)
        val n = a.length
        val m = b.length

        // LCS length table.
        val lcs = Array.ofDim[Int](n + 1, m + 1)
        var i = n - 1
        while (i >= 0) {
            var j = m - 1
            while (j >= 0) {
                lcs(i)(j) =
                    if (a(i) == b(j)) lcs(i + 1)(j + 1) + 1
                    else math.max(lcs(i + 1)(j), lcs(i)(j + 1))
                j -= 1
            }
            i -= 1
        }

        val out = mutable.ListBuffer[DiffLine]()
        i = 0; var j = 0
        while (i < n && j < m) {
            if (a(i) == b(j)) { out += DiffLine("ctx", a(i)); i += 1; j += 1 }
            else if (lcs(i + 1)(j) >= lcs(i)(j + 1)) { out += DiffLine("del", a(i)); i += 1 }
            else { out += DiffLine("add", b(j)); j += 1 }
        }
        while (i < n) { out += DiffLine("del", a(i)); i += 1 }
        while (j < m) { out += DiffLine("add", b(j)); j += 1 }
        out.toList
    }
}
