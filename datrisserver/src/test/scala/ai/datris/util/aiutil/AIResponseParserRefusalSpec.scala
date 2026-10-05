package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, AIRefusalException, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

/** Story: when the model declines, schema generation and profiling fall back
  * instead of failing (plans/stories/ai-refusal-fallback.md), step 1.
  *
  * Pinned type:
  * {{{
  * package ai.datris.model
  * class AIRefusalException(message: String) extends DatrisException(message)
  * }}}
  * Entry point: `AIResponseParser.extractText(apiResponse, aiConfig)`. On the
  * Anthropic wire (direct or Bedrock) an HTTP-200 body with
  * `stop_reason: "refusal"` throws `AIRefusalException` carrying today's
  * message text, unchanged, so callers with no fallback (JSON Schema, XSD,
  * CodeGen) still surface the same error. It is still a `DatrisException`.
  * Other failures on the same arm stay plain `DatrisException`s. */
class AIResponseParserRefusalSpec extends AnyFunSuite {

    private val anthropic = AIConfig("anthropic", "https://api.anthropic.com/v1/messages", "claude-fable-5-1", "key")

    private val existingMessage =
        "The model declined this request (stop_reason: refusal). This can be a safety-classifier " +
            "false positive — rephrase the request or switch the slot to a different model."

    test("a stop_reason refusal raises AIRefusalException with the existing message") {
        val body = """{"type":"message","role":"assistant","content":[],"stop_reason":"refusal"}"""
        val e = intercept[DatrisException](AIResponseParser.extractText(body, anthropic))
        assert(e.isInstanceOf[AIRefusalException], "expected AIRefusalException, got " + e.getClass.getName + ": " + e.getMessage)
        assert(e.getMessage == existingMessage, e.getMessage)
    }

    test("a refusal with partial text content still raises AIRefusalException") {
        val body = """{"content":[{"type":"text","text":"I can"}],"stop_reason":"refusal"}"""
        val e = intercept[DatrisException](AIResponseParser.extractText(body, anthropic))
        assert(e.isInstanceOf[AIRefusalException], e.getClass.getName)
    }

    test("AIRefusalException is a DatrisException carrying its message") {
        val e: DatrisException = new AIRefusalException("declined")
        assert(e.getMessage == "declined")
    }

    test("other Anthropic failures are not refusals") {
        val e = intercept[DatrisException](AIResponseParser.extractText("""{"content":[],"stop_reason":"end_turn"}""", anthropic))
        assert(!e.isInstanceOf[AIRefusalException], e.getClass.getName)
        assert(e.getMessage.contains("no content"), e.getMessage)
    }
}
