package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.JsonArray

import scala.jdk.CollectionConverters._

/** Shared validation of the `messages` array the chat endpoints receive.
  * Providers reject a message with empty content with an opaque HTTP 400, so
  * blank messages are stopped here: a blank final user message is refused with
  * a readable error, and a blank message earlier in the history is dropped. */
private[datris] object ChatMessages {

    val EmptyMessageError: String = "The message is empty. Type a message and send it again."

    private def roleAndContent(el: com.google.gson.JsonElement): (String, String) = {
        val m = el.getAsJsonObject
        val role = if (m.has("role") && !m.get("role").isJsonNull) m.get("role").getAsString else "user"
        val content = if (m.has("content") && !m.get("content").isJsonNull) m.get("content").getAsString else ""
        (role, content)
    }

    /** Throws when the last message is a user message with blank content. */
    def requireLastUserText(messagesArr: JsonArray): Unit = {
        if (messagesArr == null || messagesArr.size() == 0) return
        val (role, content) = roleAndContent(messagesArr.get(messagesArr.size() - 1))
        if (role != "assistant" && content.trim.isEmpty) throw new DatrisException(EmptyMessageError)
    }

    /** The history as (role, text) pairs for the text-only chat endpoints. A
      * blank final user message is refused; any other blank message is dropped
      * and the same-role messages left next to each other are merged, so no
      * empty message reaches the provider and roles keep alternating. */
    def textHistory(messagesArr: JsonArray): List[(String, String)] = {
        requireLastUserText(messagesArr)
        val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
        messagesArr.asScala.map(roleAndContent).filter(_._2.trim.nonEmpty).foreach { case (role, content) =>
            if (out.nonEmpty && out.last._1 == role) out(out.size - 1) = (role, out.last._2 + "\n\n" + content)
            else out += ((role, content))
        }
        if (out.isEmpty) throw new DatrisException(EmptyMessageError)
        out.toList
    }
}
