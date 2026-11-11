package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import ai.datris.util.aiutil.AIStreaming.AIContentBlock
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** A chat message with empty content used to reach the provider and come back
  * as an opaque HTTP 400. The chat endpoints now refuse a blank new message
  * with a readable error and never emit an empty message from history. */
class ChatMessagesSpec extends AnyFunSuite {

    private def arr(json: String) = JsonParser.parseString(json).getAsJsonArray

    test("textHistory keeps a normal history unchanged") {
        val out = ChatMessages.textHistory(
            arr("""[{"role":"user","content":"a"},{"role":"assistant","content":"b"},{"role":"user","content":"c"}]""")
        )
        assert(out == List(("user", "a"), ("assistant", "b"), ("user", "c")))
    }

    test("a blank final user message is refused with a readable error") {
        Seq("""""""", """"  \n """", "null").foreach { content =>
            val e = intercept[DatrisException] {
                ChatMessages.textHistory(arr(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"b"},{"role":"user","content":$content}]"""))
            }
            assert(e.getMessage == ChatMessages.EmptyMessageError)
        }
        intercept[DatrisException](ChatMessages.textHistory(arr("""[{"role":"user"}]""")))
    }

    test("a blank message earlier in the history is dropped and its neighbours merge") {
        val out = ChatMessages.textHistory(
            arr("""[{"role":"user","content":"a"},{"role":"assistant","content":" "},{"role":"user","content":"b"}]""")
        )
        assert(out == List(("user", "a\n\nb")))
        val out2 = ChatMessages.textHistory(
            arr("""[{"role":"user","content":""},{"role":"assistant","content":"x"},{"role":"user","content":"b"}]""")
        )
        assert(out2 == List(("assistant", "x"), ("user", "b")))
    }

    test("Assistant history never emits an empty text block for a blank user message") {
        val out = AssistantAPIController.historyToBlocks(
            arr("""[{"role":"user","content":"a"},{"role":"assistant","content":"b"},{"role":"user","content":""}]""")
        )
        assert(out == List(("user", List(AIContentBlock.TextBlock("a"))), ("assistant", List(AIContentBlock.TextBlock("b")))))
    }

    test("requireLastUserText accepts text and refuses blank") {
        ChatMessages.requireLastUserText(arr("""[{"role":"user","content":"hi"}]"""))
        intercept[DatrisException](ChatMessages.requireLastUserText(arr("""[{"role":"user","content":" "}]""")))
    }
}
