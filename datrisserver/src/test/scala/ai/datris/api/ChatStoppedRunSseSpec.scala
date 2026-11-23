package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.AIUtil.{AIContentBlock, AIStreamEvent, AIToolResponse}
import ai.datris.util.AgentLoop
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import scala.collection.JavaConverters._

/** Story: A chat stops when its user can no longer be verified. Every chat
  * controller forwards loop events through `AssistantSseSupport.emitLoopEvent`
  * and completes the emitter once `AgentLoop.run` returns; a run stopped for
  * an unverified user reaches the wire as the failing tool's `tool_result`
  * then one `error` frame carrying the plain message, with no `done`. */
class ChatStoppedRunSseSpec extends AnyFunSuite {

    private class CapturingEmitter extends SseEmitter {
        val frames = scala.collection.mutable.ListBuffer.empty[(String, String)]
        override def send(builder: SseEmitter.SseEventBuilder): Unit = {
            val parts = builder.build().asScala.map(_.getData.toString).mkString
            val json = parts.substring(parts.indexOf('{'), parts.lastIndexOf('}') + 1)
            val name = JsonParser.parseString(json).getAsJsonObject.get("type").getAsString
            frames += (name -> json)
        }
    }

    test("a run stopped for an unverified user ends the stream with tool_result then one error frame and no done") {
        val em = new CapturingEmitter
        val cfg = AIConfig("openai", "https://example.invalid/v1/chat/completions", "gpt-test", "key")
        AgentLoop.run(
            aiConfig = cfg,
            system = "",
            userMessages = Seq("user" -> "go"),
            toolDefs = Nil,
            apiKey = "",
            enableThinking = false,
            maxIterations = 10,
            maxTokensPerCall = 1000,
            cancelled = () => false,
            sink = (e: AgentLoop.LoopEvent) => AssistantSseSupport.emitLoopEvent(em, e),
            execute = (name: String, _: JsonObject) =>
                throw new DatrisException("MCP tool '" + name + "' returned error: " + TenantInterceptor.UnverifiedUserBody),
            callModel = (_: List[(String, List[AIContentBlock])], _: AIStreamEvent => Unit) =>
                AIToolResponse(List(AIContentBlock.ToolUseBlock("t1", "list_pipelines", new JsonObject)), wantsToolUse = true, stopReason = "tool_use")
        )
        val names = em.frames.map(_._1).toList
        assert(names == List("iteration_start", "tool_result", "error"), names)
        val result = JsonParser.parseString(em.frames(1)._2).getAsJsonObject
        assert(result.get("id").getAsString == "t1")
        assert(result.get("isError").getAsBoolean)
        val err = JsonParser.parseString(em.frames(2)._2).getAsJsonObject
        assert(err.get("message").getAsString ==
            "This chat was stopped: the user it was acting for could not be verified. Sign in again to continue.")
    }
}
