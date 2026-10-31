package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AgentLoop
import com.google.gson.{JsonArray, JsonParser}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import scala.collection.JavaConverters._

/** Story: Assistant structured question cards (plans/stories/assistant-question-cards.md),
  * Step 3: `LoopEvent.AskUser(id, questions)` goes onto the wire as SSE event
  * `ask_user {type,id,questions}`. */
class AssistantSseAskUserSpec extends AnyFunSuite {

    private class CapturingEmitter extends SseEmitter {
        val frames = scala.collection.mutable.ListBuffer.empty[String]
        override def send(builder: SseEmitter.SseEventBuilder): Unit =
            frames += builder.build().asScala.map(_.getData.toString).mkString
    }

    private val questionsJson: String =
        """[{"header":"Source","question":"Which weather API?","multiSelect":false,
          |  "options":[{"label":"Open-Meteo","description":"Free, no key"},{"label":"NWS","description":"US only"}]},
          | {"header":"Schedule","question":"How often?","multiSelect":true,
          |  "options":[{"label":"Hourly","description":"Every hour"},{"label":"Daily","description":"Once a day"}]}]""".stripMargin

    test("AskUser is emitted as an ask_user SSE event with id and questions round-tripped") {
        val questions: JsonArray = JsonParser.parseString(questionsJson).getAsJsonArray
        val em = new CapturingEmitter
        val ok = AssistantSseSupport.emitLoopEvent(em, AgentLoop.LoopEvent.AskUser("toolu_9", questions))
        assert(ok)
        val frame = em.frames.mkString
        assert(frame.contains("event:ask_user"), s"no ask_user event name: $frame")
        val json = frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)
        val o = JsonParser.parseString(json).getAsJsonObject
        assert(o.get("type").getAsString == "ask_user")
        assert(o.get("id").getAsString == "toolu_9")
        assert(o.get("questions").isJsonArray)
        assert(o.getAsJsonArray("questions") == JsonParser.parseString(questionsJson).getAsJsonArray)
    }
}
