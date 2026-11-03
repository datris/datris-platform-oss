package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

class AIStreamingSpec extends AnyFunSuite {

    // The Responses request builder (openaiNonStreamingCallOnce) performs the
    // HTTP call inline, so it can't be exercised without a server. What is
    // testable is the retry matcher that decides whether a `reasoning` rejection
    // gets a second, reasoning-free attempt — now that we ask for
    // `reasoning.summary` as well as `reasoning.effort`, a model (or an
    // unverified org) that refuses only the summary must also be caught.

    test("isOpenAiReasoningError catches effort rejections") {
        assert(AIStreaming.isOpenAiReasoningError("Unknown parameter: 'reasoning'."))
        assert(AIStreaming.isOpenAiReasoningError("reasoning.effort is not supported on this model"))
        assert(AIStreaming.isOpenAiReasoningError("This model does not support reasoning"))
        assert(AIStreaming.isOpenAiReasoningError("Invalid value for 'reasoning.effort'"))
    }

    test("isOpenAiReasoningError catches summary rejections so the turn retries instead of failing") {
        assert(AIStreaming.isOpenAiReasoningError("Unknown parameter: 'reasoning.summary'."))
        assert(AIStreaming.isOpenAiReasoningError("Your organization must be verified to generate reasoning summaries."))
        assert(AIStreaming.isOpenAiReasoningError("summary is not supported with this model"))
    }

    test("isOpenAiReasoningError ignores null and unrelated errors") {
        assert(!AIStreaming.isOpenAiReasoningError(null))
        assert(!AIStreaming.isOpenAiReasoningError(""))
        assert(!AIStreaming.isOpenAiReasoningError("Rate limit reached for requests"))
        assert(!AIStreaming.isOpenAiReasoningError("Incorrect API key provided"))
        // Mentions reasoning but is not a parameter rejection — must not trigger
        // the reasoning-free retry.
        assert(!AIStreaming.isOpenAiReasoningError("The model produced a reasoning trace that was truncated"))
    }

    // ------------------------------------------------------------------
    // Anthropic prompt caching (plans/stories/self-healing-cost-loop.md)
    // ------------------------------------------------------------------

    private def parse(s: String): JsonObject = JsonParser.parseString(s).getAsJsonObject

    private def ephemeral: JsonObject = parse("""{"type":"ephemeral"}""")

    private def tool(name: String): JsonObject =
        parse(s"""{"name":"$name","description":"d","input_schema":{"type":"object","properties":{}}}""")

    test("applyPromptCaching turns a string system into one text block with ephemeral cache_control") {
        val req = parse("""{"model":"m","max_tokens":10,"system":"You are the ops agent.","messages":[{"role":"user","content":"hi"}]}""")
        val out = AIStreaming.applyPromptCaching(req)
        assert(out.get("system").isJsonArray, out)
        val sys = out.getAsJsonArray("system")
        assert(sys.size() == 1, sys)
        val block = sys.get(0).getAsJsonObject
        assert(block.get("type").getAsString == "text")
        assert(block.get("text").getAsString == "You are the ops agent.")
        assert(block.getAsJsonObject("cache_control") == ephemeral, block)
        // Nothing else in the request changes.
        assert(out.get("model").getAsString == "m")
        assert(out.get("max_tokens").getAsInt == 10)
        assert(out.getAsJsonArray("messages") == parse("""{"m":[{"role":"user","content":"hi"}]}""").getAsJsonArray("m"))
    }

    test("applyPromptCaching marks only the last tool") {
        val tools = new JsonArray()
        Seq("get_pipeline", "run_tap", "wait_seconds").foreach(n => tools.add(tool(n)))
        val req = new JsonObject()
        req.addProperty("model", "m")
        req.add("tools", tools)
        val out = AIStreaming.applyPromptCaching(req)
        val ts = out.getAsJsonArray("tools")
        assert(ts.size() == 3)
        // Order is preserved — the cache key depends on it.
        assert((0 until 3).map(i => ts.get(i).getAsJsonObject.get("name").getAsString) == Seq("get_pipeline", "run_tap", "wait_seconds"))
        assert(!ts.get(0).getAsJsonObject.has("cache_control"), ts)
        assert(!ts.get(1).getAsJsonObject.has("cache_control"), ts)
        assert(ts.get(2).getAsJsonObject.getAsJsonObject("cache_control") == ephemeral, ts)
        // The tool definition itself is otherwise intact.
        val last = ts.get(2).getAsJsonObject.deepCopy()
        last.remove("cache_control")
        assert(last == tool("wait_seconds"))
    }

    test("applyPromptCaching leaves a request with no system and no tools unchanged") {
        val req = parse("""{"model":"m","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""")
        val before = req.deepCopy()
        assert(AIStreaming.applyPromptCaching(req) == before)
        // An empty tools array is not a cache breakpoint either.
        val withEmptyTools = parse("""{"model":"m","tools":[],"messages":[]}""")
        val before2 = withEmptyTools.deepCopy()
        assert(AIStreaming.applyPromptCaching(withEmptyTools) == before2)
    }

    test("applyPromptCaching is idempotent") {
        val tools = new JsonArray()
        Seq("a", "b").foreach(n => tools.add(tool(n)))
        val req = parse("""{"model":"m","system":"sys","messages":[]}""")
        req.add("tools", tools)
        val once = AIStreaming.applyPromptCaching(req).deepCopy()
        val twice = AIStreaming.applyPromptCaching(once.deepCopy())
        assert(twice == once, twice)
        assert(twice.getAsJsonArray("system").size() == 1)

        // A system already sent as an array of blocks is left untouched.
        val arraySystem = parse("""{"model":"m","system":[{"type":"text","text":"one"},{"type":"text","text":"two"}],"messages":[]}""")
        val sysBefore = arraySystem.getAsJsonArray("system").deepCopy()
        val out = AIStreaming.applyPromptCaching(arraySystem)
        assert(out.getAsJsonArray("system") == sysBefore, out)
    }
}
