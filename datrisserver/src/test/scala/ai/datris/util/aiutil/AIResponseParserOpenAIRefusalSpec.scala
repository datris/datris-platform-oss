package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, AIRefusalException, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

/** Story ai-refusal-fallback, resolved detail (a): the OpenAI-style arms raise
  * `AIRefusalException` for their own documented decline shapes, and nothing
  * else is a decline.
  *
  * Chat completions (OpenAI, Azure OpenAI, Grok): `finish_reason:
  * "content_filter"` or a non-empty `message.refusal`. Responses API (OpenAI
  * Responses models): an output item or message content part of type
  * `refusal`, or `incomplete_details.reason: "content_filter"`. */
class AIResponseParserOpenAIRefusalSpec extends AnyFunSuite {

    private val openai = AIConfig("openai", "https://api.openai.com/v1/chat/completions", "gpt-5", "key")
    private val azure = AIConfig("azure", "https://x.openai.azure.com/openai/deployments/d/chat/completions", "gpt-5", "key")
    private val grok = AIConfig("grok", "https://api.x.ai/v1/chat/completions", "grok-4.7", "key")
    private val ollama = AIConfig("ollama", "http://ollama:11434/v1/chat/completions", "llama3", "")
    private val responses = AIConfig("openai", "https://api.openai.com/v1/responses", "gpt-6-astra", "key")

    private def declines(body: String, cfg: AIConfig): AIRefusalException = {
        val e = intercept[DatrisException](AIResponseParser.extractText(body, cfg))
        assert(e.isInstanceOf[AIRefusalException], cfg.provider + ": expected AIRefusalException, got " + e.getClass.getName + ": " + e.getMessage)
        assert(e.getMessage.startsWith("The model declined this request ("), e.getMessage)
        e.asInstanceOf[AIRefusalException]
    }

    private def notADecline(body: String, cfg: AIConfig): Unit = {
        val e = intercept[DatrisException](AIResponseParser.extractText(body, cfg))
        assert(!e.isInstanceOf[AIRefusalException], cfg.provider + ": " + e.getMessage)
    }

    test("chat completions: finish_reason content_filter is a decline on OpenAI, Azure OpenAI and Grok") {
        val body = """{"choices":[{"index":0,"finish_reason":"content_filter","message":{"role":"assistant","content":null}}]}"""
        Seq(openai, azure, grok).foreach(cfg => assert(declines(body, cfg).getMessage.contains("finish_reason: content_filter")))
    }

    test("chat completions: content_filter with partial content is still a decline") {
        val body = """{"choices":[{"finish_reason":"content_filter","message":{"role":"assistant","content":"partial"}}]}"""
        declines(body, azure)
    }

    test("chat completions: a non-empty message.refusal is a decline, and the provider's text is not echoed") {
        val body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"refusal":"I can't help with ZQX-REFUSAL-TEXT"}}]}"""
        Seq(openai, azure, grok).foreach(cfg => assert(!declines(body, cfg).getMessage.contains("ZQX-REFUSAL-TEXT")))
    }

    test("chat completions: an empty or null refusal field and ordinary failures are not declines") {
        assert(
            AIResponseParser.extractText("""{"choices":[{"finish_reason":"stop","message":{"content":"ok","refusal":null}}]}""", openai) == "ok"
        )
        assert(AIResponseParser.extractText("""{"choices":[{"finish_reason":"stop","message":{"content":"ok","refusal":""}}]}""", grok) == "ok")
        notADecline("""{"choices":[]}""", openai)
        notADecline("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""", azure)
        notADecline("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}""", ollama)
    }

    test("Responses API: a refusal content part in the message is a decline") {
        val body =
            """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"ZQX-REFUSAL-TEXT"}]}]}"""
        assert(!declines(body, responses).getMessage.contains("ZQX-REFUSAL-TEXT"))
    }

    test("Responses API: a top-level output item of type refusal is a decline") {
        declines("""{"output":[{"type":"reasoning"},{"type":"refusal","refusal":"no"}]}""", responses)
    }

    test("Responses API: incomplete_details.reason content_filter is a decline") {
        val body = """{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}"""
        assert(declines(body, responses).getMessage.contains("incomplete_details.reason: content_filter"))
    }

    test("Responses API: max_output_tokens incompleteness and missing output are not declines") {
        notADecline("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""", responses)
        notADecline("""{"output":[{"type":"reasoning"}]}""", responses)
        assert(
            AIResponseParser.extractText("""{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""", responses) == "ok"
        )
    }
}
