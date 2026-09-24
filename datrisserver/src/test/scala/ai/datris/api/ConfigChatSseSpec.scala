package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AgentLoop
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import scala.collection.JavaConverters._

/** Story: Configuration chat, server side (plans/stories/config-chat-server-seam.md),
  * Steps 1-2: `LoopEvent.ConfirmRequest(id, tool, summary, token)` goes onto the
  * wire as SSE event `confirm_request {type,id,tool,summary,token}`. */
class ConfigChatSseSpec extends AnyFunSuite {

    private class CapturingEmitter extends SseEmitter {
        val frames = scala.collection.mutable.ListBuffer.empty[String]
        override def send(builder: SseEmitter.SseEventBuilder): Unit =
            frames += builder.build().asScala.map(_.getData.toString).mkString
    }

    test("ConfirmRequest is emitted as a confirm_request SSE event with all fields") {
        val em = new CapturingEmitter
        val ok = AssistantSseSupport.emitLoopEvent(em,
            AgentLoop.LoopEvent.ConfirmRequest("toolu_1", "delete_user", "Delete user bob", "tok-123"))
        assert(ok)
        val frame = em.frames.mkString
        assert(frame.contains("event:confirm_request"), s"no confirm_request event name: $frame")
        val json = frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)
        val o = JsonParser.parseString(json).getAsJsonObject
        assert(o.get("type").getAsString == "confirm_request")
        assert(o.get("id").getAsString == "toolu_1")
        assert(o.get("tool").getAsString == "delete_user")
        assert(o.get("summary").getAsString == "Delete user bob")
        assert(o.get("token").getAsString == "tok-123")
    }
}
