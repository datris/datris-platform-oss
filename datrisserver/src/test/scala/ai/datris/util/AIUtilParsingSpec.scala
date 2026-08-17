package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

class AIUtilParsingSpec extends AnyFunSuite {

    private val anthropic = AIConfig("anthropic", "https://api.anthropic.com/v1/messages", "claude-opus-4-8", "key")
    private val openaiChat = AIConfig("openai", "https://api.openai.com/v1/chat/completions", "gpt-4o", "key")
    private val openaiResponses = AIConfig("openai", "https://api.openai.com/v1/responses", "gpt-5", "key")

    // ---- extractText ----

    test("Anthropic: single text block") {
        val json = """{"content":[{"type":"text","text":"Hello"}]}"""
        assert(AIUtil.extractText(json, anthropic) == "Hello")
    }

    test("Anthropic: interleaved tool blocks — all text blocks concatenated in order") {
        val json =
            """{"content":[
              |  {"type":"text","text":"Searching..."},
              |  {"type":"server_tool_use","id":"t1"},
              |  {"type":"web_search_tool_result","content":[]},
              |  {"type":"text","text":"Found it."}
              |]}""".stripMargin
        assert(AIUtil.extractText(json, anthropic) == "Searching...\nFound it.")
    }

    test("Anthropic: no content throws") {
        val e = intercept[DatrisException] { AIUtil.extractText("""{"content":[]}""", anthropic) }
        assert(e.getMessage.contains("no content"))
    }

    test("Anthropic: content with no text blocks throws") {
        val json = """{"content":[{"type":"server_tool_use","id":"t1"}]}"""
        val e = intercept[DatrisException] { AIUtil.extractText(json, anthropic) }
        assert(e.getMessage.contains("no text blocks"))
    }

    test("OpenAI chat completions: message content") {
        val json = """{"choices":[{"message":{"content":"Hi there"}}]}"""
        assert(AIUtil.extractText(json, openaiChat) == "Hi there")
    }

    test("OpenAI chat completions: empty choices throws") {
        val e = intercept[DatrisException] { AIUtil.extractText("""{"choices":[]}""", openaiChat) }
        assert(e.getMessage.contains("no choices"))
    }

    test("Grok chat completions: message content parses on the OpenAI wire, never the Anthropic arm") {
        val grok = AIConfig("grok", "https://api.x.ai/v1/chat/completions", "grok-4.6", "key")
        val json = """{"choices":[{"message":{"content":"Grok says hi"}}]}"""
        assert(AIUtil.extractText(json, grok) == "Grok says hi")
    }

    test("OpenAI Responses API: routed via endpoint; skips reasoning items") {
        val json =
            """{"output":[
              |  {"type":"reasoning","summary":[]},
              |  {"type":"message","content":[{"type":"output_text","text":"Answer"}]}
              |]}""".stripMargin
        assert(AIUtil.extractText(json, openaiResponses) == "Answer")
    }

    test("OpenAI Responses API: routed via codex model name even on chat endpoint") {
        val codex = AIConfig("openai", "https://api.openai.com/v1/chat/completions", "gpt-5-codex", "key")
        val json = """{"output":[{"type":"message","content":[{"type":"output_text","text":"Yo"}]}]}"""
        assert(AIUtil.extractText(json, codex) == "Yo")
    }

    test("whitespace-only text throws") {
        val json = """{"content":[{"type":"text","text":"   "}]}"""
        val e = intercept[DatrisException] { AIUtil.extractText(json, anthropic) }
        assert(e.getMessage.contains("empty"))
    }

    test("result is trimmed") {
        val json = """{"content":[{"type":"text","text":"  padded  "}]}"""
        assert(AIUtil.extractText(json, anthropic) == "padded")
    }

    // ---- extractCitations ----

    test("Anthropic citations: collected across blocks, deduped by URL, order preserved") {
        val json =
            """{"content":[
              |  {"type":"text","text":"a","citations":[
              |    {"url":"https://one.example","title":"One"},
              |    {"url":"https://two.example","title":"Two"}
              |  ]},
              |  {"type":"text","text":"b","citations":[
              |    {"url":"https://one.example","title":"One again"}
              |  ]}
              |]}""".stripMargin
        assert(AIUtil.extractCitations(json, anthropic) ==
            List(("https://one.example", "One"), ("https://two.example", "Two")))
    }

    test("Anthropic citations: title falls back to URL when missing") {
        val json = """{"content":[{"type":"text","citations":[{"url":"https://x.example"}]}]}"""
        assert(AIUtil.extractCitations(json, anthropic) == List(("https://x.example", "https://x.example")))
    }

    test("Responses API citations: url_citation annotations only") {
        val json =
            """{"output":[{"type":"message","content":[{
              |  "type":"output_text","text":"t",
              |  "annotations":[
              |    {"type":"url_citation","url":"https://a.example","title":"A"},
              |    {"type":"file_citation","file_id":"f1"}
              |  ]}]}]}""".stripMargin
        assert(AIUtil.extractCitations(json, openaiResponses) == List(("https://a.example", "A")))
    }

    test("citations: non-web-search response returns empty") {
        assert(AIUtil.extractCitations("""{"content":[{"type":"text","text":"x"}]}""", anthropic) == Nil)
    }

    test("citations: malformed JSON returns empty rather than throwing") {
        assert(AIUtil.extractCitations("not json at all", anthropic) == Nil)
    }

    test("citations: OpenAI chat completions provider yields empty") {
        assert(AIUtil.extractCitations("""{"choices":[]}""", openaiChat) == Nil)
    }
}
