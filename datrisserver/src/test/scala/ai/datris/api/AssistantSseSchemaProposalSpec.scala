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

/** Story: Assistant schema-proposal card (plans/stories/assistant-preview-confirm-schema-ui-card.md),
  * Step 2, Acceptance bullet 1: `LoopEvent.SchemaProposal(id, tap, typeGrid,
  * sampleRowCount, fields, preview)` goes onto the wire as SSE event
  * `schema_proposal {type,id,tap,typeGrid,sampleRowCount,fields,preview}`. The 5-row preview cap is covered end to end
  * (parse then emit) in AgentLoopSchemaProposalSpec. */
class AssistantSseSchemaProposalSpec extends AnyFunSuite {

    private class CapturingEmitter extends SseEmitter {
        val frames = scala.collection.mutable.ListBuffer.empty[String]
        override def send(builder: SseEmitter.SseEventBuilder): Unit =
            frames += builder.build().asScala.map(_.getData.toString).mkString
    }

    private val fieldsJson: String =
        """[{"name":"id","type":"int","samples":["1","2"],"stringReason":null,"note":null},
          | {"name":"price","type":"string","samples":["9.99","N/A"],"stringReason":"stayed text — found 'N/A', would be double","note":null},
          | {"name":"when","type":"string","samples":["2026-01-01"],"stringReason":null,"note":"only 1 value"}]""".stripMargin

    private def rows(n: Int): String =
        (1 to n).map(i => s"""{"id":"$i","price":"${i}.50","when":"2026-01-0$i"}""").mkString("[", ",", "]")

    private def emit(evt: AgentLoop.LoopEvent): com.google.gson.JsonObject = {
        val em = new CapturingEmitter
        assert(AssistantSseSupport.emitLoopEvent(em, evt))
        val frame = em.frames.mkString
        assert(frame.contains("event:schema_proposal"), s"no schema_proposal event name: $frame")
        val json = frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)
        JsonParser.parseString(json).getAsJsonObject
    }

    test("SchemaProposal is emitted as a schema_proposal SSE event with every field") {
        val fields: JsonArray = JsonParser.parseString(fieldsJson).getAsJsonArray
        val preview: JsonArray = JsonParser.parseString(rows(3)).getAsJsonArray
        val o = emit(AgentLoop.LoopEvent.SchemaProposal("toolu_7", "prices_tap", true, 20, fields, preview))
        assert(o.get("type").getAsString == "schema_proposal")
        assert(o.get("id").getAsString == "toolu_7")
        assert(o.get("tap").getAsString == "prices_tap")
        assert(o.get("typeGrid").getAsBoolean)
        assert(o.get("sampleRowCount").getAsInt == 20)
        assert(o.get("fields").isJsonArray)
        assert(o.getAsJsonArray("fields") == JsonParser.parseString(fieldsJson).getAsJsonArray)
        val f1 = o.getAsJsonArray("fields").get(1).getAsJsonObject
        for (k <- Seq("name", "type", "samples", "stringReason", "note"))
            assert(f1.has(k), s"field object must carry '$k': $f1")
        assert(o.getAsJsonArray("preview") == JsonParser.parseString(rows(3)).getAsJsonArray)
    }

    test("a vector proposal goes out with typeGrid false and an empty fields array") {
        val o = emit(AgentLoop.LoopEvent.SchemaProposal("toolu_v", "docs_tap", false, 2, new JsonArray(), JsonParser.parseString(rows(2)).getAsJsonArray))
        assert(!o.get("typeGrid").getAsBoolean)
        assert(o.getAsJsonArray("fields").size == 0)
        assert(o.getAsJsonArray("preview").size == 2)
    }

    test("recordsTruncated is parsed from the tool result and carried on the wire (default false)") {
        val raw =
            s"""{"status":"schema_proposal","typeGrid":true,"tap":"t","sampleRowCount":20,"recordsTruncated":true,"fields":$fieldsJson,"preview":${rows(2)}}"""
        val evt = AgentLoop.schemaProposalOf("toolu_t", "propose_pipeline_schema", raw)
        assert(evt.exists(_.recordsTruncated))
        assert(emit(evt.get).get("recordsTruncated").getAsBoolean)
        val plain = emit(AgentLoop.LoopEvent.SchemaProposal("toolu_f", "t", true, 5, new JsonArray(), JsonParser.parseString(rows(2)).getAsJsonArray))
        assert(!plain.get("recordsTruncated").getAsBoolean)
    }
}
