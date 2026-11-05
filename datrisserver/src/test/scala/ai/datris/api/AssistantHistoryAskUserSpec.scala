package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AgentLoop
import ai.datris.util.aiutil.AIStreaming.AIContentBlock
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** Follow-up to the Assistant question cards story: a past card used to go
  * into the chat history as a bracketed text record, and the model copied
  * that record back as prose on a later turn instead of calling `ask_user`.
  * The UI now sends the card as data (`askUser: {questions, submitted}`) and
  * `AssistantAPIController.historyToBlocks` replays it as the tool_use /
  * tool_result pair the model saw live.
  */
class AssistantHistoryAskUserSpec extends AnyFunSuite {

    private val questions =
        """[{"header":"Source","question":"Which source?","multiSelect":false,
          |  "options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]},
          | {"header":"Name","question":"What name?"}]""".stripMargin

    private def history(json: String) =
        AssistantAPIController.historyToBlocks(JsonParser.parseString(json).getAsJsonArray)

    test("plain messages become one text block each") {
        val out = history("""[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},{"role":"user","content":"go"}]""")
        assert(out.map(_._1) == List("user", "assistant", "user"))
        assert(out.map(_._2) == List(
            List(AIContentBlock.TextBlock("hi")),
            List(AIContentBlock.TextBlock("hello")),
            List(AIContentBlock.TextBlock("go"))
        ))
    }

    test("a submitted card replays as an ask_user tool_use and a tool_result merged into the next user message") {
        val out = history(
            s"""[{"role":"user","content":"make a tap"},
               | {"role":"assistant","content":"","askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Source: A\\nName: x"}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user"))
        val assistant = out(1)._2
        assert(assistant.size == 1, "a card-only turn has no text block, just the tool_use")
        val tu = assistant.head.asInstanceOf[AIContentBlock.ToolUseBlock]
        assert(tu.name == AgentLoop.SyntheticAskUserTool)
        assert(tu.input.getAsJsonArray("questions").size == 2)
        assert(tu.input.getAsJsonArray("questions").get(1).getAsJsonObject.get("header").getAsString == "Name")
        val user = out(2)._2
        assert(user.size == 2)
        val tr = user.head.asInstanceOf[AIContentBlock.ToolResultBlock]
        assert(tr.toolUseId == tu.id)
        assert(!tr.isError)
        assert(tr.content.startsWith(AgentLoop.AskUserWaitingText))
        assert(tr.content.contains("submitted the card"))
        assert(user(1) == AIContentBlock.TextBlock("Source: A\nName: x"))
        // The card never appears as prose anywhere in the replayed history.
        out.flatMap(_._2).collect { case AIContentBlock.TextBlock(t) => t }.foreach { t =>
            assert(!t.contains("Tool call"))
            assert(!t.contains("Which source?"))
        }
    }

    test("a card the user answered in chat keeps the assistant text and says the reply is prose") {
        val out = history(
            s"""[{"role":"user","content":"make a tap"},
               | {"role":"assistant","content":"Here is the plan.","askUser":{"questions":$questions,"submitted":false}},
               | {"role":"user","content":"just do a live read"}]""".stripMargin
        )
        val assistant = out(1)._2
        assert(assistant.head == AIContentBlock.TextBlock("Here is the plan."))
        assert(assistant(1).isInstanceOf[AIContentBlock.ToolUseBlock])
        val tr = out(2)._2.head.asInstanceOf[AIContentBlock.ToolResultBlock]
        assert(tr.content.contains("replied in chat"))
        assert(!tr.content.contains("submitted the card"))
    }

    test("two cards in one history get distinct tool_use ids") {
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Source: A"},
               | {"role":"assistant","content":"","askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Name: x"}]""".stripMargin
        )
        val ids = out.flatMap(_._2).collect { case AIContentBlock.ToolUseBlock(id, _, _) => id }
        assert(ids.size == 2 && ids.distinct.size == 2)
        val resultIds = out.flatMap(_._2).collect { case AIContentBlock.ToolResultBlock(id, _, _) => id }
        assert(resultIds == ids)
    }

    test("a malformed askUser falls back to the text alone") {
        val out = history(
            """[{"role":"user","content":"a"},
              | {"role":"assistant","content":"Which source?","askUser":{"questions":[],"submitted":true}},
              | {"role":"user","content":"A"}]""".stripMargin
        )
        assert(out(1)._2 == List(AIContentBlock.TextBlock("Which source?")))
        assert(out(2)._2 == List(AIContentBlock.TextBlock("A")))
    }

    test("a card at the end of the history still gets its tool_result so the pairing holds") {
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","askUser":{"questions":$questions,"submitted":false}}]""".stripMargin
        )
        assert(out.size == 3)
        assert(out(2)._1 == "user")
        assert(out(2)._2.head.isInstanceOf[AIContentBlock.ToolResultBlock])
    }
}
