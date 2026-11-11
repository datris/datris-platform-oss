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

/** Story: Assistant checkpoint-2 cards (plans/stories/assistant-rule-proposal-cards.md),
  * Step 2, Acceptance bullet 2: `LoopEvent.RuleProposal(id, kind, pipeline, items)`
  * goes onto the wire as SSE event `rule_proposal {type,id,kind,pipeline,items}`. */
class AssistantSseRuleProposalSpec extends AnyFunSuite {

    private class CapturingEmitter extends SseEmitter {
        val frames = scala.collection.mutable.ListBuffer.empty[String]
        override def send(builder: SseEmitter.SseEventBuilder): Unit =
            frames += builder.build().asScala.map(_.getData.toString).mkString
    }

    private val itemsJson: String =
        """[{"text":"amount is never negative","column":"amount"},
          | {"text":"created_at parses as a date","column":"created_at"},
          | {"text":"no two rows share the same id"}]""".stripMargin

    private def emit(kind: String): com.google.gson.JsonObject = {
        val items: JsonArray = JsonParser.parseString(itemsJson).getAsJsonArray
        val em = new CapturingEmitter
        val ok = AssistantSseSupport.emitLoopEvent(em, AgentLoop.LoopEvent.RuleProposal("toolu_r1", kind, "sales_daily", items))
        assert(ok)
        val frame = em.frames.mkString
        assert(frame.contains("event:rule_proposal"), s"no rule_proposal event name: $frame")
        JsonParser.parseString(frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)).getAsJsonObject
    }

    test("RuleProposal is emitted as a rule_proposal SSE event with id, kind, pipeline and items round-tripped") {
        val o = emit("data_quality")
        assert(o.get("type").getAsString == "rule_proposal")
        assert(o.get("id").getAsString == "toolu_r1")
        assert(o.get("kind").getAsString == "data_quality")
        assert(o.get("pipeline").getAsString == "sales_daily")
        assert(o.get("items").isJsonArray)
        val items = o.getAsJsonArray("items").asScala.map(_.getAsJsonObject).toList
        assert(items.map(_.get("text").getAsString) ==
            List("amount is never negative", "created_at parses as a date", "no two rows share the same id"))
        assert(items(0).get("column").getAsString == "amount")
        assert(items(1).get("column").getAsString == "created_at")
    }

    test("a transformation proposal carries its kind") {
        assert(emit("transformation").get("kind").getAsString == "transformation")
    }
}
