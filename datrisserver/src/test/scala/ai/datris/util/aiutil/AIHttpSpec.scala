package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class AIHttpSpec extends AnyFunSuite {

    private def assemble(sse: String): com.google.gson.JsonObject = {
        val raw = AIHttp.assembleChatCompletionsStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))
        JsonParser.parseString(raw).getAsJsonObject
    }

    test("assembleChatCompletionsStream folds text deltas into a non-streaming response") {
        val sse =
            """data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}
              |
              |data: {"choices":[{"delta":{"content":"lo"}}]}
              |
              |data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
              |
              |data: [DONE]
              |""".stripMargin
        val root = assemble(sse)
        val choice = root.getAsJsonArray("choices").get(0).getAsJsonObject
        assert(choice.getAsJsonObject("message").get("content").getAsString == "Hello")
        assert(choice.get("finish_reason").getAsString == "stop")
    }

    test("assembleChatCompletionsStream reassembles chunked tool_calls by index") {
        val sse =
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_pipelines","arguments":""}}]}}]}
              |
              |data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"na"}}]}}]}
              |
              |data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"me\":1}"}}]}}]}
              |
              |data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
              |
              |data: [DONE]
              |""".stripMargin
        val root = assemble(sse)
        val choice = root.getAsJsonArray("choices").get(0).getAsJsonObject
        val tc = choice.getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject
        assert(tc.get("id").getAsString == "call_1")
        assert(tc.getAsJsonObject("function").get("name").getAsString == "list_pipelines")
        assert(tc.getAsJsonObject("function").get("arguments").getAsString == "{\"na" + "me\":1}")
        assert(choice.get("finish_reason").getAsString == "tool_calls")
    }

    test("assembleChatCompletionsStream ignores empty-choice chunks (usage frames)") {
        val sse =
            """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}
              |
              |data: {"choices":[],"usage":{"total_tokens":10}}
              |
              |data: [DONE]
              |""".stripMargin
        val root = assemble(sse)
        val choice = root.getAsJsonArray("choices").get(0).getAsJsonObject
        assert(choice.getAsJsonObject("message").get("content").getAsString == "ok")
    }
}
