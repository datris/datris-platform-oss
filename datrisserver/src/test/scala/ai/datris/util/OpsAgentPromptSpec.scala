package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

/** Pins the cache-stability assumption behind Anthropic prompt caching
  * (plans/stories/self-healing-cost-loop.md): the tool list sent on every
  * turn must come out in the same order every time, so the reorder is a
  * stable partition, never a sort. */
class OpsAgentPromptSpec extends AnyFunSuite {

    private def tool(name: String): JsonObject = {
        val t = new JsonObject()
        t.addProperty("name", name)
        t
    }

    private def names(ts: List[JsonObject]): List[String] = ts.map(_.get("name").getAsString)

    test("reorderToolsOpsFirst keeps relative order inside each partition") {
        // Interleaved, and deliberately not alphabetical within each group.
        val in = List(
            "create_tap",
            "wait_seconds",
            "query_data",
            "run_tap",
            "delete_pipeline",
            "get_tap_logs",
            "add_pipeline",
            "get_pipeline"
        ).map(tool)
        val out = OpsAgentPrompt.reorderToolsOpsFirst(in)
        assert(names(out) == List(
            "wait_seconds",
            "run_tap",
            "get_tap_logs",
            "get_pipeline",
            "create_tap",
            "query_data",
            "delete_pipeline",
            "add_pipeline"
        ))
        // Deterministic: the same input yields the same output every call.
        assert(names(OpsAgentPrompt.reorderToolsOpsFirst(in)) == names(out))
        // Nothing dropped or duplicated.
        assert(out.size == in.size)
    }

    test("reorderToolsOpsFirst keeps a tool without a name in the non-ops partition, in place") {
        val unnamed = new JsonObject()
        val in = List(tool("query_data"), unnamed, tool("run_tap"), tool("create_tap"))
        val out = OpsAgentPrompt.reorderToolsOpsFirst(in)
        assert(out == List(tool("run_tap"), tool("query_data"), unnamed, tool("create_tap")))
    }
}
