package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AgentLoop
import ai.datris.util.aiutil.AIStreaming.AIContentBlock
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** Story: Assistant tool-call continuity across turns
  * (plans/stories/assistant-tool-call-continuity.md), E2E finding: an
  * assistant history message with blank text and nothing left to replay (no
  * `toolCalls` entry survived, no `askUser`) used to become an empty
  * TextBlock, which Anthropic rejects with a 400. It is now dropped and the
  * user messages either side of it are merged, so roles still alternate and
  * no empty text block is ever emitted.
  */
class AssistantHistoryEmptyMessageSpec extends AnyFunSuite {

    private def history(json: String) =
        AssistantAPIController.historyToBlocks(JsonParser.parseString(json).getAsJsonArray)

    private def assertNoBlankText(out: List[(String, List[AIContentBlock])]): Unit =
        out.foreach { case (role, blocks) =>
            assert(blocks.nonEmpty, s"empty $role message: $out")
            blocks.foreach {
                case AIContentBlock.TextBlock(t) => assert(t.trim.nonEmpty, s"blank text block in $role message: $out")
                case _ =>
            }
        }

    private def assertMergedAround(out: List[(String, List[AIContentBlock])]): Unit = {
        assert(out.map(_._1) == List("user"), out)
        assert(out.head._2 == List(AIContentBlock.TextBlock("a\n\nb")), out)
        assertNoBlankText(out)
    }

    test("toolCalls: [] with empty content is dropped and the user messages around it merge") {
        assertMergedAround(
            history("""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":[]},{"role":"user","content":"b"}]""")
        )
    }

    test("all-malformed toolCalls with empty content is dropped") {
        val calls = """[{"name":"list_taps","input":{}},{"id":"t2","input":{}},{"id":"t3","name":"x","input":"s"},42]"""
        assertMergedAround(
            history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"b"}]""")
        )
    }

    test("a message without toolCalls and with empty content is dropped") {
        assertMergedAround(history("""[{"role":"user","content":"a"},{"role":"assistant","content":""},{"role":"user","content":"b"}]"""))
    }

    test("whitespace-only content counts as empty") {
        assertMergedAround(history("""[{"role":"user","content":"a"},{"role":"assistant","content":"  \n "},{"role":"user","content":"b"}]"""))
    }

    test("a blank assistant message last in the history is dropped") {
        val out = history("""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":[]}]""")
        assert(out == List(("user", List(AIContentBlock.TextBlock("a")))))
    }

    test("pending tool results before a dropped message still pair with their uses") {
        val one = """[{"id":"t1","name":"list_taps","input":{},"result":"[]","isError":false}]"""
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","toolCalls":$one},
               | {"role":"user","content":"b"},
               | {"role":"assistant","content":""},
               | {"role":"user","content":"c"}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user"), out)
        val use = out(1)._2.head.asInstanceOf[AIContentBlock.ToolUseBlock]
        assert(out(2)._2.head.asInstanceOf[AIContentBlock.ToolResultBlock].toolUseId == use.id)
        assert(out(2)._2.last == AIContentBlock.TextBlock("b\n\nc"))
        assertNoBlankText(out)
    }

    test("a blank message with a card still replays the card") {
        val questions =
            """[{"header":"Source","question":"Which?","multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]"""
        val out = history(
            s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":[],"askUser":{"questions":$questions,"submitted":true}},{"role":"user","content":"Source: A"}]"""
        )
        assert(out.map(_._1) == List("user", "assistant", "user"))
        assert(out(1)._2.map(_.asInstanceOf[AIContentBlock.ToolUseBlock].name) == List(AgentLoop.SyntheticAskUserTool))
        assertNoBlankText(out)
    }

    test("separate user messages are not merged when nothing was dropped") {
        val out = history("""[{"role":"user","content":"a"},{"role":"user","content":"b"}]""")
        assert(out.map(_._1) == List("user", "user"))
    }
}
