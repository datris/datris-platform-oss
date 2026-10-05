package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{AIConfig, AIRefusalException, DatrisEnvironment, DatrisException}
import ai.datris.util.aiutil.AIProviders.usesResponsesApi

import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters._

/** Response parsing: extract text and web-search citations from Anthropic
  * Messages, OpenAI Chat Completions, and OpenAI Responses API payloads.
  * Extracted verbatim from AIUtil — AIUtil remains the public facade.
  */
object AIResponseParser {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Pull the web-search URLs the model consulted for this response, if any.
      * Returns an empty list when the request didn't use web search or the provider
      * didn't surface citations. Both Anthropic and OpenAI surface URLs differently;
      * we normalize to (url, title) tuples. */
    def extractCitations(apiResponse: String, aiConfig: AIConfig): List[(String, String)] = {
        try {
            val gson = new Gson()
            val responseMap = gson.fromJson(apiResponse, classOf[java.util.Map[String, Any]])
            if (usesResponsesApi(aiConfig)) extractResponsesApiCitations(responseMap)
            else aiConfig.provider.toLowerCase match {
                case "anthropic" => extractAnthropicCitations(responseMap)
                case _ => Nil
            }
        } catch {
            case e: Exception =>
                logger.debug("Could not extract web-search citations from " + aiConfig.provider + " response — returning none", e)
                Nil
        }
    }

    private def extractAnthropicCitations(responseMap: java.util.Map[String, Any]): List[(String, String)] = {
        // Anthropic surfaces citations on `text` content blocks via a `citations` array,
        // each entry having `url` and `title`. Multiple text blocks may carry citations;
        // dedupe by URL while preserving order.
        val contentList = responseMap.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (contentList == null) return Nil
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
        contentList.asScala.foreach { block =>
            val cites = block.get("citations").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
            if (cites != null) cites.asScala.foreach { c =>
                val url = Option(c.get("url")).map(_.toString).getOrElse("")
                val title = Option(c.get("title")).map(_.toString).getOrElse(url)
                if (url.nonEmpty && !seen.contains(url)) seen.put(url, title)
            }
        }
        seen.toList
    }

    private def extractResponsesApiCitations(responseMap: java.util.Map[String, Any]): List[(String, String)] = {
        // OpenAI Responses surfaces citations as `url_citation` annotations on the
        // message's text content. Same dedupe-by-URL.
        val output = responseMap.get("output").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (output == null) return Nil
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
        output.asScala.foreach { item =>
            val content = item.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
            if (content != null) content.asScala.foreach { c =>
                val annotations = c.get("annotations").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                if (annotations != null) annotations.asScala.foreach { a =>
                    val t = Option(a.get("type")).map(_.toString).getOrElse("")
                    if (t == "url_citation") {
                        val url = Option(a.get("url")).map(_.toString).getOrElse("")
                        val title = Option(a.get("title")).map(_.toString).getOrElse(url)
                        if (url.nonEmpty && !seen.contains(url)) seen.put(url, title)
                    }
                }
            }
        }
        seen.toList
    }

    def extractText(apiResponse: String): String =
        extractText(apiResponse, DatrisEnvironment.current.aiConfig)

    def extractText(apiResponse: String, aiConfig: AIConfig): String = {
        val gson = new Gson()
        val responseMap = gson.fromJson(apiResponse, classOf[java.util.Map[String, Any]])

        val text =
            if (usesResponsesApi(aiConfig)) extractResponsesApiText(responseMap)
            else aiConfig.provider.toLowerCase match {
                case "openai" | "ollama" | "azure" | "grok" =>
                    val choices = responseMap.get("choices").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                    if (choices == null || choices.isEmpty)
                        throw new DatrisException("OpenAI/Ollama response contained no choices")
                    val choice = choices.get(0)
                    val message = choice.get("message").asInstanceOf[java.util.Map[String, Any]]
                    // Chat-completions decline shapes (OpenAI, Azure OpenAI, Grok):
                    // finish_reason "content_filter" or a non-empty message.refusal.
                    if (Option(choice.get("finish_reason")).map(_.toString).contains("content_filter"))
                        throw refusal("finish_reason: content_filter")
                    if (message != null && Option(message.get("refusal")).map(_.toString).exists(_.trim.nonEmpty))
                        throw refusal("refusal")
                    if (message == null)
                        throw new DatrisException("OpenAI/Ollama response choice had no message")
                    message.get("content").asInstanceOf[String]
                case _ =>
                    // Anthropic wire (direct or via Bedrock). Claude Fable 5's safety
                    // classifiers can decline a request with HTTP 200 + stop_reason
                    // "refusal" and empty/partial content — surface that legibly
                    // instead of the generic "no content" error.
                    val stopReason = Option(responseMap.get("stop_reason")).map(_.toString).getOrElse("")
                    if (stopReason == "refusal") throw refusal("stop_reason: refusal")
                    val contentList = responseMap.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                    if (contentList == null || contentList.isEmpty)
                        throw new DatrisException("Anthropic response contained no content")
                    // Anthropic returns a list of content blocks. Without tools the first
                    // block is always `text`. With server tools enabled (web_search), the
                    // list interleaves `text`, `server_tool_use`, and `web_search_tool_result`
                    // blocks — we want every text block concatenated, in order, so the
                    // model's narrative around tool calls is preserved for the caller.
                    val texts = contentList.asScala.flatMap { block =>
                        val t = Option(block.get("type")).map(_.toString).getOrElse("text")
                        if (t == "text") Option(block.get("text")).map(_.toString) else None
                    }
                    if (texts.isEmpty)
                        throw new DatrisException("Anthropic response had no text blocks")
                    texts.mkString("\n")
            }

        if (text == null || text.trim.isEmpty)
            throw new DatrisException("AI response text was empty")

        text.trim
    }

    /** A safety decline: the provider answered (HTTP 200) but declined. The
      * message names the provider's signal and nothing the provider wrote. */
    private def refusal(signal: String): AIRefusalException =
        new AIRefusalException(
            "The model declined this request (" + signal + "). This can be a safety-classifier " +
                "false positive — rephrase the request or switch the slot to a different model."
        )

    private def typeOf(item: java.util.Map[String, Any]): String =
        Option(item.get("type")).map(_.toString).getOrElse("")

    // Responses API shape: { output: [ { type: "message", content: [ { type: "output_text", text: "..." } ] }, ... ] }.
    // Reasoning models may also include "reasoning" items in output — we want the first message's first output_text.
    private def extractResponsesApiText(responseMap: java.util.Map[String, Any]): String = {
        // Responses decline shapes: incomplete_details.reason "content_filter",
        // or a `refusal` item (top-level output item or a message content part).
        val incomplete = responseMap.get("incomplete_details") match {
            case m: java.util.Map[_, _] => Option(m.get("reason")).map(_.toString).getOrElse("")
            case _ => ""
        }
        if (incomplete == "content_filter") throw refusal("incomplete_details.reason: content_filter")
        val output = responseMap.get("output").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (output != null) output.asScala.foreach { item =>
            if (typeOf(item) == "refusal") throw refusal("refusal")
            val parts = item.get("content") match {
                case l: java.util.List[_] => l.asScala.collect { case m: java.util.Map[_, _] => m.asInstanceOf[java.util.Map[String, Any]] }
                case _ => Nil
            }
            if (typeOf(item) == "message" && parts.exists(p => typeOf(p) == "refusal")) throw refusal("refusal")
        }
        if (output == null || output.isEmpty)
            throw new DatrisException("OpenAI Responses API response contained no output")
        val message = output.asScala.find { item =>
            val t = Option(item.get("type")).map(_.toString).getOrElse("")
            t == "message"
        }.getOrElse(throw new DatrisException("OpenAI Responses API output contained no message item"))
        val contentList = message.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (contentList == null || contentList.isEmpty)
            throw new DatrisException("OpenAI Responses API message had no content")
        val textItem = contentList.asScala.find { c =>
            val t = Option(c.get("type")).map(_.toString).getOrElse("")
            t == "output_text" || t == "text"
        }.getOrElse(throw new DatrisException("OpenAI Responses API content had no output_text"))
        textItem.get("text").asInstanceOf[String]
    }
}
