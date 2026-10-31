package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.AssistantAPIController
import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Assistant structured question cards (plans/stories/assistant-question-cards.md),
  * Steps 1-2 and 8.
  *
  *  `AgentLoop.askUserQuestionsOf(input)` validates and normalises an `ask_user`
  *  call: 1-4 questions, 2-4 options each, non-empty header/question/label,
  *  header clipped to 12 chars, `multiSelect` defaulting to false. A malformed
  *  call is a `Left` (error tool result, no card).
  *
  *  The tool definition is read through the companion object so the spec can
  *  reach it, same as `KeepOrScratchRule`:
  *
  *  {{{
  *  object AssistantAPIController {
  *      private[datris] def syntheticAskUserToolDef(): JsonObject
  *  }
  *  }}}
  */
class AgentLoopAskUserSpec extends AnyFunSuite {

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private def opt(label: String, desc: String = "d"): String =
        s"""{"label":"$label","description":"$desc"}"""

    private def q(header: String, nOpts: Int = 2, multi: Option[Boolean] = None): String = {
        val opts = (1 to nOpts).map(i => opt(s"Option $i")).mkString(",")
        val m = multi.map(b => s""","multiSelect":$b""").getOrElse("")
        s"""{"header":"$header","question":"Which $header?","options":[$opts]$m}"""
    }

    private def input(qs: String*): JsonObject = obj(s"""{"questions":[${qs.mkString(",")}]}""")

    private def questionsOf(a: JsonArray): List[JsonObject] = a.asScala.map(_.getAsJsonObject).toList

    test("the synthetic tool name is ask_user") {
        assert(AgentLoop.SyntheticAskUserTool == "ask_user")
    }

    test("a valid input is Right with multiSelect defaulted to false") {
        val r = AgentLoop.askUserQuestionsOf(input(q("Source"), q("Schedule", 4, Some(true))))
        assert(r.isRight, s"expected Right, got $r")
        val qs = questionsOf(r.toOption.get)
        assert(qs.size == 2)
        assert(qs.head.get("header").getAsString == "Source")
        assert(qs.head.get("question").getAsString == "Which Source?")
        assert(qs.head.has("multiSelect"), "multiSelect must be present after normalisation")
        assert(!qs.head.get("multiSelect").getAsBoolean, "multiSelect defaults to false")
        assert(qs(1).get("multiSelect").getAsBoolean)
        val opts = qs.head.getAsJsonArray("options").asScala.map(_.getAsJsonObject).toList
        assert(opts.map(_.get("label").getAsString) == List("Option 1", "Option 2"))
        assert(opts.forall(_.get("description").getAsString == "d"))
        assert(qs(1).getAsJsonArray("options").size == 4)
    }

    test("one question and four questions are both accepted") {
        assert(AgentLoop.askUserQuestionsOf(input(q("A"))).isRight)
        assert(AgentLoop.askUserQuestionsOf(input(q("A"), q("B"), q("C"), q("D"))).isRight)
    }

    test("five questions is Left") {
        val r = AgentLoop.askUserQuestionsOf(input(q("A"), q("B"), q("C"), q("D"), q("E")))
        assert(r.isLeft, s"expected Left, got $r")
        assert(r.left.toOption.get.nonEmpty, "the validation message must not be empty")
    }

    test("zero questions or a missing questions array is Left") {
        assert(AgentLoop.askUserQuestionsOf(input()).isLeft)
        assert(AgentLoop.askUserQuestionsOf(obj("{}")).isLeft)
        assert(AgentLoop.askUserQuestionsOf(obj("""{"questions":"pick one"}""")).isLeft)
    }

    test("one option is Left") {
        assert(AgentLoop.askUserQuestionsOf(input(q("Source", nOpts = 1))).isLeft)
    }

    test("no options is Right: a free-text question") {
        val noOpts = s"""{"header":"Locations","question":"Which places?"}"""
        val r = AgentLoop.askUserQuestionsOf(input(noOpts))
        assert(r.isRight)
        val nq = r.toOption.get.get(0).getAsJsonObject
        assert(nq.getAsJsonArray("options").size == 0)
        assert(!nq.get("multiSelect").getAsBoolean)
        val emptyOpts = s"""{"header":"Locations","question":"Which places?","options":[]}"""
        assert(AgentLoop.askUserQuestionsOf(input(emptyOpts)).isRight)
    }

    test("five options is Left") {
        assert(AgentLoop.askUserQuestionsOf(input(q("Source", nOpts = 5))).isLeft)
    }

    test("a missing header is Left") {
        val noHeader = s"""{"question":"Which source?","options":[${opt("A")},${opt("B")}]}"""
        assert(AgentLoop.askUserQuestionsOf(input(noHeader)).isLeft)
    }

    test("an empty header, question or option label is Left") {
        assert(AgentLoop.askUserQuestionsOf(input(q("  "))).isLeft)
        val noQuestion = s"""{"header":"Source","question":"","options":[${opt("A")},${opt("B")}]}"""
        assert(AgentLoop.askUserQuestionsOf(input(noQuestion)).isLeft)
        val emptyLabel = s"""{"header":"Source","question":"Which?","options":[${opt("")},${opt("B")}]}"""
        assert(AgentLoop.askUserQuestionsOf(input(emptyLabel)).isLeft)
    }

    test("a header longer than 12 chars is clipped to 12") {
        val r = AgentLoop.askUserQuestionsOf(input(q("DestinationChoice")))
        assert(r.isRight, s"a long header is clipped, not rejected: $r")
        val h = questionsOf(r.toOption.get).head.get("header").getAsString
        assert(h.length == 12)
        assert(h == "DestinationC")
    }

    test("a header clipped at a space carries no trailing space") {
        val r = AgentLoop.askUserQuestionsOf(input(q("Destination database")))
        assert(r.isRight, s"a long header is clipped, not rejected: $r")
        assert(questionsOf(r.toOption.get).head.get("header").getAsString == "Destination")
    }

    // ---------------------------------------------------------- tool def ---

    private lazy val toolDef: JsonObject = AssistantAPIController.syntheticAskUserToolDef()

    test("tool def is named ask_user and has a description") {
        assert(toolDef.get("name").getAsString == "ask_user")
        assert(toolDef.has("description") && toolDef.get("description").getAsString.nonEmpty)
    }

    test("tool def input_schema requires exactly questions") {
        val schema = toolDef.getAsJsonObject("input_schema")
        assert(schema.get("type").getAsString == "object")
        val req = schema.getAsJsonArray("required").asScala.map(_.getAsString).toList
        assert(req == List("questions"))
        val questions = schema.getAsJsonObject("properties").getAsJsonObject("questions")
        assert(questions.get("type").getAsString == "array")
        assert(questions.get("minItems").getAsInt == 1)
        assert(questions.get("maxItems").getAsInt == 4)
    }

    test("tool def question items require header and question; options are optional (2-4 when given)") {
        val item = toolDef.getAsJsonObject("input_schema").getAsJsonObject("properties")
            .getAsJsonObject("questions").getAsJsonObject("items")
        val req = item.getAsJsonArray("required").asScala.map(_.getAsString).toSet
        assert(Set("header", "question").subsetOf(req))
        assert(!req.contains("options"), "options are optional: none means a free-text question")
        assert(!req.contains("multiSelect"), "multiSelect is optional (default false)")
        val props = item.getAsJsonObject("properties")
        assert(props.getAsJsonObject("multiSelect").get("type").getAsString == "boolean")
        val options = props.getAsJsonObject("options")
        assert(options.get("minItems").getAsInt == 2)
        assert(options.get("maxItems").getAsInt == 4)
        val optProps = options.getAsJsonObject("items").getAsJsonObject("properties")
        assert(optProps.has("label") && optProps.has("description"))
    }
}
