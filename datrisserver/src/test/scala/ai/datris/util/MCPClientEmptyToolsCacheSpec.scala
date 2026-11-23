package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

/** Story: the tool list is never wider than what the server said. The MCP
  * server answers an empty tool list when the platform refused the key or
  * user; the in-platform chats must not reuse that empty list for the full
  * minute a real list is reused, or one refusal or blip leaves every chat for
  * that key and user without tools for a minute. */
class MCPClientEmptyToolsCacheSpec extends AnyFunSuite {

    private def tool(name: String): JsonObject = {
        val o = new JsonObject
        o.addProperty("name", name)
        o
    }

    private class Fetcher(answers: List[List[JsonObject]]) {
        var calls = 0
        def apply(): List[JsonObject] = {
            val a = answers(math.min(calls, answers.size - 1))
            calls += 1
            a
        }
    }

    private def freshKey(): String = MCPClient.cacheKey(java.util.UUID.randomUUID().toString, MCPClient.toolsSuffix(None))

    test("a non-empty tool list is reused for 60 seconds") {
        val key = freshKey()
        val f = new Fetcher(List(List(tool("get_pipeline")), List(tool("list_pipelines"))))
        val t0 = 1000000L
        assert(MCPClient.cachedTools(key, () => f(), t0).map(_.get("name").getAsString) == List("get_pipeline"))
        assert(MCPClient.cachedTools(key, () => f(), t0 + 59 * 1000L).map(_.get("name").getAsString) == List("get_pipeline"))
        assert(f.calls == 1)
        assert(MCPClient.cachedTools(key, () => f(), t0 + 61 * 1000L).map(_.get("name").getAsString) == List("list_pipelines"))
        assert(f.calls == 2)
    }

    test("an empty tool list is reused for at most a few seconds") {
        val key = freshKey()
        val f = new Fetcher(List(Nil, List(tool("get_pipeline"))))
        val t0 = 1000000L
        assert(MCPClient.cachedTools(key, () => f(), t0).isEmpty)
        assert(MCPClient.emptyToolsTtlMillis <= 5 * 1000L)
        assert(MCPClient.cachedTools(key, () => f(), t0 + MCPClient.emptyToolsTtlMillis + 1).map(_.get("name").getAsString) == List("get_pipeline"))
        assert(f.calls == 2)
    }

    test("a fetch that fails is not cached") {
        val key = freshKey()
        var calls = 0
        val failing = () => { calls += 1; throw new RuntimeException("MCP server returned 503") }
        intercept[RuntimeException](MCPClient.cachedTools(key, failing, 1000000L))
        intercept[RuntimeException](MCPClient.cachedTools(key, failing, 1000001L))
        assert(calls == 2)
    }
}
