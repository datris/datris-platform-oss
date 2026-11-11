package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Assistant schema-proposal card (plans/stories/assistant-preview-confirm-schema-ui-card.md),
  * Steps 1 and 7, Acceptance bullet 1.
  *
  *  `AgentLoop.schemaProposalOf(id, tool, raw)` turns a
  *  `{"status":"schema_proposal", ...}` result from the `propose_pipeline_schema`
  *  MCP tool into `LoopEvent.SchemaProposal(id, tap, typeGrid, sampleRowCount,
  *  fields, preview)`. Any other tool, an error result or malformed JSON is
  *  None (passed through untouched). */
class AgentLoopSchemaProposalSpec extends AnyFunSuite {

    private val Tool = "propose_pipeline_schema"

    private val fieldsJson: String =
        """[{"name":"id","type":"int","samples":["1","2"],"stringReason":null,"note":null},
          | {"name":"price","type":"string","samples":["9.99","N/A"],"stringReason":"stayed text — found 'N/A', would be double","note":null},
          | {"name":"when","type":"string","samples":["2026-01-01"],"stringReason":null,"note":"only 1 value"}]""".stripMargin

    private val previewJson: String =
        """[{"id":"1","price":"9.99","when":"2026-01-01"},{"id":"2","price":"N/A","when":""}]"""

    private val gridResult: String =
        s"""{"status":"schema_proposal","typeGrid":true,"tap":"prices_tap","destination":"postgres",
           |"sampleRowCount":20,"recordsTruncated":false,"fields":$fieldsJson,"preview":$previewJson,
           |"next":"Show fields as a table"}""".stripMargin

    private val vectorResult: String =
        s"""{"status":"schema_proposal","typeGrid":false,"tap":"docs_tap","destination":"pgvector",
           |"preview":$previewJson,"sampleRowCount":2}""".stripMargin

    test("a typed schema_proposal result from propose_pipeline_schema parses into a SchemaProposal") {
        val r = AgentLoop.schemaProposalOf("toolu_s1", Tool, gridResult)
        assert(r.isDefined, "a schema_proposal result must be intercepted")
        val p = r.get
        assert(p.id == "toolu_s1")
        assert(p.tap == "prices_tap")
        assert(p.typeGrid)
        assert(p.sampleRowCount == 20)
        assert(p.fields == JsonParser.parseString(fieldsJson).getAsJsonArray)
        assert(p.preview == JsonParser.parseString(previewJson).getAsJsonArray)
        val names = p.fields.asScala.map(_.getAsJsonObject.get("name").getAsString).toList
        assert(names == List("id", "price", "when"))
    }

    test("a vector-destination proposal (typeGrid false, no fields) parses with an empty fields array") {
        val r = AgentLoop.schemaProposalOf("toolu_v", Tool, vectorResult)
        assert(r.isDefined, "the vector preview is shown as a card too")
        val p = r.get
        assert(!p.typeGrid)
        assert(p.tap == "docs_tap")
        assert(p.sampleRowCount == 2)
        assert(p.fields != null && p.fields.size == 0)
        assert(p.preview.size == 2)
    }

    test("the same JSON from any other tool is None") {
        assert(AgentLoop.schemaProposalOf("toolu_x", "test_tap", gridResult).isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_x", "create_pipeline", gridResult).isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_x", "", gridResult).isEmpty)
    }

    test("an error result from propose_pipeline_schema is None") {
        val err = """{"error":"The test of tap 'prices_tap' returned no rows to infer a schema from"}"""
        assert(AgentLoop.schemaProposalOf("toolu_e", Tool, err).isEmpty)
        val failedTest = """{"status":"failed","error":"script exited 1","logs":"Traceback ..."}"""
        assert(AgentLoop.schemaProposalOf("toolu_e", Tool, failedTest).isEmpty)
        val tooOld = """{"error":"propose-schema is not available: Datris server too old for propose_pipeline_schema"}"""
        assert(AgentLoop.schemaProposalOf("toolu_e", Tool, tooOld).isEmpty)
    }

    test("a different status is None, even when the text mentions schema_proposal") {
        val other = """{"status":"success","note":"schema_proposal comes next","records":[]}"""
        assert(AgentLoop.schemaProposalOf("toolu_o", Tool, other).isEmpty)
    }

    test("malformed JSON, non-object JSON, null and empty input are None") {
        assert(AgentLoop.schemaProposalOf("toolu_m", Tool, """{"status":"schema_proposal","tap":""").isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_m", Tool, """["schema_proposal"]""").isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_m", Tool, "status: schema_proposal").isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_m", Tool, "").isEmpty)
        assert(AgentLoop.schemaProposalOf("toolu_m", Tool, null).isEmpty)
    }

    test("the existing secret_request and needs_confirmation parsers are unaffected") {
        val secret = """{"status":"secret_request","secretName":"k","fieldNames":["a"],"reason":"r"}"""
        assert(AgentLoop.schemaProposalOf("toolu_q", Tool, secret).isEmpty)
        assert(AgentLoop.secretRequestOf("toolu_q", "x", secret).isDefined)
        assert(AgentLoop.secretRequestOf("toolu_q", Tool, gridResult).isEmpty)
        assert(AgentLoop.confirmRequestOf("toolu_q", Tool, gridResult).isEmpty)
    }

    test("a result with more than 5 preview rows reaches the SSE wire with at most 5") {
        val rows = (1 to 7).map(i => s"""{"id":"$i"}""").mkString("[", ",", "]")
        val raw = s"""{"status":"schema_proposal","typeGrid":true,"tap":"t","sampleRowCount":7,"fields":$fieldsJson,"preview":$rows}"""
        val evt = AgentLoop.schemaProposalOf("toolu_p", Tool, raw)
        assert(evt.isDefined)
        val frames = scala.collection.mutable.ListBuffer.empty[String]
        val em = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
            override def send(b: org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder): Unit =
                frames += b.build().asScala.map(_.getData.toString).mkString
        }
        assert(ai.datris.api.AssistantSseSupport.emitLoopEvent(em, evt.get))
        val frame = frames.mkString
        val o = JsonParser.parseString(frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)).getAsJsonObject
        val preview = o.getAsJsonArray("preview")
        assert(preview.size == 5, s"preview must be capped at 5 rows: ${preview.size}")
        assert(preview.get(0).getAsJsonObject.get("id").getAsString == "1")
        assert(o.get("sampleRowCount").getAsInt == 7)
    }

    // Story: Assistant: preview-only schema card is informational
    // (plans/stories/assistant-preview-card-informational.md), Acceptance bullet 1.
    //
    //  `AgentLoop.schemaProposalResult(p, tool, raw): (String, Boolean)` is the
    //  intercept's decision: the text the model sees for the tool_result and
    //  whether the loop stops after this batch. A typed grid waits for the user;
    //  a preview-only card (mongodb, vector stores) hands the model the trimmed
    //  raw result (with its `next` hint) and the turn continues.

    private val mongoResult: String =
        s"""{"status":"schema_proposal","typeGrid":false,"tap":"docs_tap","destination":"mongodb",
           |"preview":$previewJson,"sampleRowCount":2,
           |"next":"MongoDB stores each record as a document as-is. Ask the one follow-up question, then call create_pipeline."}""".stripMargin

    test("schemaProposalResult on a typeGrid true proposal returns the waiting text and stops the loop") {
        val p = AgentLoop.schemaProposalOf("toolu_g", Tool, gridResult).get
        assert(p.typeGrid)
        val (text, stop) = AgentLoop.schemaProposalResult(p, Tool, gridResult)
        assert(text == AgentLoop.SchemaProposalWaitingText)
        assert(stop, "a typed grid card ends the turn")
    }

    test("schemaProposalResult on a typeGrid false (mongodb) proposal returns the trimmed raw JSON and does not stop") {
        val p = AgentLoop.schemaProposalOf("toolu_m", Tool, mongoResult).get
        assert(!p.typeGrid)
        val (text, stop) = AgentLoop.schemaProposalResult(p, Tool, mongoResult)
        assert(!stop, "a preview-only card must not end the turn")
        assert(text != AgentLoop.SchemaProposalWaitingText, "the model must see the result, not the waiting text")
        assert(!text.contains("Wait for their reply"), text)
        assert(text.contains("\"next\""), s"the model must see the `next` hint: $text")
        assert(text.contains("\"typeGrid\":false"), text)
        assert(text == ToolResultTrim.trim(Tool, mongoResult), "the text is the trimmed raw result")
    }

    test("schemaProposalResult on a vector (typeGrid false) proposal returns the trimmed raw JSON and does not stop") {
        val raw =
            s"""{"status":"schema_proposal","typeGrid":false,"tap":"docs_tap","destination":"pgvector",
               |"preview":$previewJson,"sampleRowCount":2,"next":"call create_pipeline with name and destination"}""".stripMargin
        val p = AgentLoop.schemaProposalOf("toolu_v2", Tool, raw).get
        val (text, stop) = AgentLoop.schemaProposalResult(p, Tool, raw)
        assert(!stop)
        assert(text.contains("\"next\"") && text.contains("\"typeGrid\":false"), text)
        assert(text != AgentLoop.SchemaProposalWaitingText)
    }
}
