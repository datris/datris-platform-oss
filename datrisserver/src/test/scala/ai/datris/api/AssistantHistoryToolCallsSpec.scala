package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AgentLoop
import ai.datris.util.aiutil.AIStreaming.AIContentBlock
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

/** Story: Assistant tool-call continuity across turns
  * (plans/stories/assistant-tool-call-continuity.md), Acceptance 1-5.
  *
  * The UI now sends each prior assistant turn's finished tool cards as
  * `toolCalls: [{id, name, input, result, isError, truncated?, synthetic?}]`
  * and `AssistantAPIController.historyToBlocks` replays them as the
  * tool_use / tool_result pairs the model saw live: ToolUseBlocks on the
  * assistant message (after its text, before any `ask_user`), ToolResultBlocks
  * in the same order prefixed to the next user message. Ids are prefixed
  * `hist_<i>_` (i = message index) so they never clash with live ids.
  *
  * Symbols assumed beyond the existing spec:
  * {{{
  * AgentLoop.SchemaProposalWaitingText   // private[datris], exists
  * AgentLoop.SecretRequestWaitingText    // private -> private[datris]
  * }}}
  */
class AssistantHistoryToolCallsSpec extends AnyFunSuite {

    private val ShorteningNote = " [result shortened for history; call the tool again if you need the full output]"

    private val questions =
        """[{"header":"Source","question":"Which source?","multiSelect":false,
          |  "options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]""".stripMargin

    private def history(json: String) =
        AssistantAPIController.historyToBlocks(JsonParser.parseString(json).getAsJsonArray)

    private def toolUses(blocks: List[AIContentBlock]) = blocks.collect { case b: AIContentBlock.ToolUseBlock => b }
    private def toolResults(blocks: List[AIContentBlock]) = blocks.collect { case b: AIContentBlock.ToolResultBlock => b }

    private val twoCalls =
        """[{"id":"toolu_a","name":"list_pipelines","input":{},"result":"[{\"name\":\"p1\"}]","isError":false},
          | {"id":"toolu_b","name":"list_taps","input":{"filter":"x"},"result":"[]","isError":true}]""".stripMargin

    /** Captures WARN lines logged while `body` runs. */
    private def capturingWarnings[T](body: => T): (T, Seq[String]) = {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val saved = root.getLevel
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        root.addAppender(appender)
        root.setLevel(ch.qos.logback.classic.Level.INFO)
        try {
            val r = body
            (r, appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.WARN).map(_.getFormattedMessage).toSeq)
        } finally {
            root.detachAppender(appender)
            root.setLevel(saved)
        }
    }

    // ------------------------------------------------ Acceptance 1 ---

    test("toolCalls replay as ToolUseBlocks on the assistant message and ToolResultBlocks (same order, same ids) on the next user message") {
        val out = history(
            s"""[{"role":"user","content":"get me exchange rates"},
               | {"role":"assistant","content":"Checking what exists.","toolCalls":$twoCalls},
               | {"role":"user","content":"ok build it"}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user"))
        val assistant = out(1)._2
        assert(assistant.head == AIContentBlock.TextBlock("Checking what exists."))
        val uses = toolUses(assistant)
        assert(uses.size == 2)
        assert(assistant.size == 3, s"text + 2 tool_use, got $assistant")
        assert(uses.map(_.name) == List("list_pipelines", "list_taps"))
        assert(uses(1).input.get("filter").getAsString == "x")
        uses.foreach(u => assert(u.id.startsWith("hist_1_"), s"history ids are prefixed hist_<i>_: ${u.id}"))
        assert(uses.head.id.contains("toolu_a") && uses(1).id.contains("toolu_b"))

        val user = out(2)._2
        val results = toolResults(user)
        assert(results.map(_.toolUseId) == uses.map(_.id), "results pair with uses in the same order")
        assert(results.head.content == """[{"name":"p1"}]""")
        assert(!results.head.isError)
        assert(results(1).content == "[]")
        assert(results(1).isError, "isError travels to the replayed tool_result")
        assert(user.take(2) == results, "results come first in the next user message")
        assert(user.last == AIContentBlock.TextBlock("ok build it"))
        assert(user.size == 3)
    }

    test("tool_use ids from two different turns never collide even when the client ids match") {
        val one = """[{"id":"toolu_same","name":"list_taps","input":{},"result":"[]","isError":false}]"""
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"x","toolCalls":$one},
               | {"role":"user","content":"b"},
               | {"role":"assistant","content":"y","toolCalls":$one},
               | {"role":"user","content":"c"}]""".stripMargin
        )
        val ids = out.flatMap(o => toolUses(o._2)).map(_.id)
        assert(ids.size == 2 && ids.distinct.size == 2, ids)
        assert(out.flatMap(o => toolResults(o._2)).map(_.toolUseId) == ids)
    }

    // ------------------------------------------------ Acceptance 2 ---

    test("truncated: true appends the shortening note to the result") {
        val calls = """[{"id":"t1","name":"list_taps","input":{},"result":"[{\"name\":\"a\"","isError":false,"truncated":true}]"""
        val out = history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"b"}]""")
        val tr = toolResults(out(2)._2).head
        assert(tr.content == "[{\"name\":\"a\"" + ShorteningNote)
    }

    test("an empty truncated result (past the turn cap) still carries the note") {
        val calls = """[{"id":"t1","name":"list_taps","input":{},"result":"","isError":false,"truncated":true}]"""
        val out = history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"b"}]""")
        assert(toolResults(out(2)._2).head.content.contains(ShorteningNote.trim))
    }

    test("synthetic propose_pipeline_schema replays SchemaProposalWaitingText") {
        val calls = s"""[{"id":"t1","name":"${AgentLoop.SchemaProposalTool}","input":{"tap":"t"},"result":"","isError":false,"synthetic":true}]"""
        val out =
            history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"Confirmed schema"}]""")
        val uses = toolUses(out(1)._2)
        assert(uses.map(_.name) == List("propose_pipeline_schema"))
        val tr = toolResults(out(2)._2).head
        assert(tr.toolUseId == uses.head.id)
        assert(tr.content == AgentLoop.SchemaProposalWaitingText)
    }

    test("synthetic request_tap_secret_from_user replays SecretRequestWaitingText") {
        val calls =
            s"""[{"id":"t1","name":"${AgentLoop.SyntheticSecretTool}","input":{"secret_name":"s","field_names":["k"]},"result":"","isError":false,"synthetic":true}]"""
        val out = history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"I provided it"}]""")
        val tr = toolResults(out(2)._2).head
        assert(tr.content == AgentLoop.SecretRequestWaitingText)
    }

    test("synthetic on any other tool sends the result it was given") {
        val calls = """[{"id":"t1","name":"list_taps","input":{},"result":"[]","isError":false,"synthetic":true}]"""
        val out = history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"","toolCalls":$calls},{"role":"user","content":"b"}]""")
        assert(toolResults(out(2)._2).head.content == "[]")
    }

    // ------------------------------------------------ Acceptance 3 ---

    test("toolCalls + askUser: one assistant message (text, N tool_use, ask_user) and one user message (N results, ask_user result, text)") {
        val out = history(
            s"""[{"role":"user","content":"get me rates"},
               | {"role":"assistant","content":"Nothing covers it.","toolCalls":$twoCalls,"askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Source: A"}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user"))
        val assistant = out(1)._2
        assert(assistant.size == 4, assistant)
        assert(assistant.head == AIContentBlock.TextBlock("Nothing covers it."))
        val uses = toolUses(assistant)
        assert(uses.map(_.name) == List("list_pipelines", "list_taps", AgentLoop.SyntheticAskUserTool))
        assert(assistant.last.asInstanceOf[AIContentBlock.ToolUseBlock].name == AgentLoop.SyntheticAskUserTool)

        val user = out(2)._2
        assert(user.size == 4, user)
        val results = toolResults(user)
        assert(results.map(_.toolUseId) == uses.map(_.id))
        assert(results(2).content.startsWith(AgentLoop.AskUserWaitingText))
        assert(user.last == AIContentBlock.TextBlock("Source: A"))
    }

    // ------------------------------------------------ Acceptance 4 ---

    test("a message without toolCalls produces the same blocks as before") {
        val out = history(
            s"""[{"role":"user","content":"hi"},
               | {"role":"assistant","content":"hello"},
               | {"role":"user","content":"go"},
               | {"role":"assistant","content":"","askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Source: A"}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user", "assistant", "user"))
        assert(out(1)._2 == List(AIContentBlock.TextBlock("hello")))
        assert(out(2)._2 == List(AIContentBlock.TextBlock("go")))
        assert(out(3)._2.size == 1 && toolUses(out(3)._2).head.id == "ask_user_history_3")
        assert(out(4)._2.size == 2)
        assert(out(4)._2.head.asInstanceOf[AIContentBlock.ToolResultBlock].toolUseId == "ask_user_history_3")
    }

    test("a malformed entry is skipped with a warning naming the message index and never throws") {
        val calls =
            """[{"name":"list_taps","input":{},"result":"[]","isError":false},
              | {"id":"t2","input":{},"result":"[]","isError":false},
              | {"id":"t3","name":"list_taps","input":"not an object","result":"[]","isError":false},
              | {"id":"t4","name":"list_pipelines","input":{},"result":"[]","isError":false},
              | 42]""".stripMargin
        val (out, warnings) = capturingWarnings {
            history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"x","toolCalls":$calls},{"role":"user","content":"b"}]""")
        }
        val uses = toolUses(out(1)._2)
        assert(uses.map(_.name) == List("list_pipelines"), s"only the well-formed entry survives: $uses")
        assert(toolResults(out(2)._2).map(_.toolUseId) == uses.map(_.id))
        assert(out(1)._2.head == AIContentBlock.TextBlock("x"))
        assert(out(2)._2.last == AIContentBlock.TextBlock("b"))
        assert(warnings.nonEmpty, "a skipped entry must be logged at WARN")
        assert(warnings.exists(_.contains("1")), s"the warning names the message index: $warnings")
    }

    test("a non-array toolCalls is ignored and the message falls back to text") {
        for (bad <- Seq("\"oops\"", "{\"id\":\"t\"}", "7", "null", "true")) {
            val out = history(s"""[{"role":"user","content":"a"},{"role":"assistant","content":"hello","toolCalls":$bad},{"role":"user","content":"b"}]""")
            assert(out.map(_._1) == List("user", "assistant", "user"), bad)
            assert(out(1)._2 == List(AIContentBlock.TextBlock("hello")), bad)
            assert(out(2)._2 == List(AIContentBlock.TextBlock("b")), bad)
        }
    }

    test("a non-array toolCalls beside askUser still replays the card as today") {
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","toolCalls":"oops","askUser":{"questions":$questions,"submitted":true}},
               | {"role":"user","content":"Source: A"}]""".stripMargin
        )
        assert(toolUses(out(1)._2).map(_.name) == List(AgentLoop.SyntheticAskUserTool))
        assert(out(2)._2.size == 2)
    }

    // ------------------------------------------------ Acceptance 5 ---

    test("toolCalls with no text and no following user message still end with a user message holding the results") {
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","toolCalls":$twoCalls}]""".stripMargin
        )
        assert(out.map(_._1) == List("user", "assistant", "user"))
        val assistant = out(1)._2
        assert(assistant.forall(_.isInstanceOf[AIContentBlock.ToolUseBlock]), s"no empty text block: $assistant")
        assert(assistant.size == 2)
        val results = out(2)._2
        assert(results.size == 2 && results.forall(_.isInstanceOf[AIContentBlock.ToolResultBlock]))
        assert(toolResults(results).map(_.toolUseId) == toolUses(assistant).map(_.id))
    }

    test("a text-less toolCalls turn followed by a user message has no empty TextBlock") {
        val out = history(
            s"""[{"role":"user","content":"a"},
               | {"role":"assistant","content":"","toolCalls":$twoCalls},
               | {"role":"user","content":"b"}]""".stripMargin
        )
        assert(!out(1)._2.contains(AIContentBlock.TextBlock("")))
        assert(toolUses(out(1)._2).size == 2)
    }
}
