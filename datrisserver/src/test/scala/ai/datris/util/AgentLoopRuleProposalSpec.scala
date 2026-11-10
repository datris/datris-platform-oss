package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.AssistantAPIController
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Assistant checkpoint-2 cards: propose and confirm data-quality checks
  * and transformations (plans/stories/assistant-rule-proposal-cards.md),
  * Steps 1 and 3, Acceptance bullet 1.
  *
  *  `AgentLoop.ruleProposalOf(input)` validates a `propose_pipeline_rules`
  *  call: `kind` in {data_quality, transformation}, non-empty `pipeline`,
  *  `items` 1-20 objects each with non-empty `text` and an optional string
  *  `column`. A malformed call is a `Left` naming the bad field.
  *
  *  Symbols assumed (the story's names):
  *  {{{
  *  object AgentLoop {
  *      val SyntheticRuleProposalTool: String            // "propose_pipeline_rules"
  *      private[datris] val RuleProposalWaitingText: String
  *      private[datris] def ruleProposalOf(input: JsonObject): Either[String, LoopEvent.RuleProposal]
  *  }
  *  LoopEvent.RuleProposal(id: String, kind: String, pipeline: String, items: JsonArray)
  *  object AssistantAPIController {
  *      private[datris] def syntheticRuleProposalToolDef(): JsonObject
  *  }
  *  }}}
  *  The `id` of the event `ruleProposalOf` returns is not asserted: the loop
  *  owns the tool_use id. */
class AgentLoopRuleProposalSpec extends AnyFunSuite {

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private def item(text: String, column: Option[String] = None): String =
        column.map(c => s"""{"text":"$text","column":"$c"}""").getOrElse(s"""{"text":"$text"}""")

    private def input(kind: String = "data_quality", pipeline: Option[String] = Some("sales_daily"), items: Seq[String]): JsonObject = {
        val p = pipeline.map(n => s""","pipeline":"$n"""").getOrElse("")
        obj(s"""{"kind":"$kind"$p,"items":[${items.mkString(",")}]}""")
    }

    private val three = Seq(
        item("amount is never negative", Some("amount")),
        item("created_at parses as a date", Some("created_at")),
        item("no two rows share the same id")
    )

    private def leftOf(in: JsonObject): String = {
        val r = AgentLoop.ruleProposalOf(in)
        assert(r.isLeft, s"expected Left, got $r")
        val msg = r.left.toOption.get
        assert(msg.nonEmpty, "the validation message must not be empty")
        msg
    }

    test("the synthetic tool name is propose_pipeline_rules") {
        assert(AgentLoop.SyntheticRuleProposalTool == "propose_pipeline_rules")
    }

    test("a valid data_quality input is Right with kind, pipeline and items in order") {
        val r = AgentLoop.ruleProposalOf(input(items = three))
        assert(r.isRight, s"expected Right, got $r")
        val p = r.toOption.get
        assert(p.kind == "data_quality")
        assert(p.pipeline == "sales_daily")
        val got = p.items.asScala.map(_.getAsJsonObject).toList
        assert(got.map(_.get("text").getAsString) ==
            List("amount is never negative", "created_at parses as a date", "no two rows share the same id"))
        assert(got(0).get("column").getAsString == "amount")
        assert(got(1).get("column").getAsString == "created_at")
        assert(!got(2).has("column") || got(2).get("column").isJsonNull || got(2).get("column").getAsString.isEmpty,
            s"an item with no column stays without one: ${got(2)}")
    }

    test("a valid transformation input is Right") {
        val r = AgentLoop.ruleProposalOf(input(kind = "transformation", items = Seq(item("trim whitespace", Some("name")))))
        assert(r.isRight, s"expected Right, got $r")
        assert(r.toOption.get.kind == "transformation")
        assert(r.toOption.get.items.size == 1)
    }

    test("twenty items is Right") {
        val twenty = (1 to 20).map(i => item(s"check $i"))
        val r = AgentLoop.ruleProposalOf(input(items = twenty))
        assert(r.isRight, s"20 items is the maximum and allowed: $r")
        assert(r.toOption.get.items.size == 20)
    }

    test("a wrong kind is Left naming kind") {
        val msg = leftOf(input(kind = "protection", items = three))
        assert(msg.contains("kind"), s"message must name `kind`: $msg")
        val missing = leftOf(obj(s"""{"pipeline":"p","items":[${three.mkString(",")}]}"""))
        assert(missing.contains("kind"), s"message must name `kind`: $missing")
    }

    test("empty items is Left naming items") {
        val msg = leftOf(input(items = Nil))
        assert(msg.contains("items"), s"message must name `items`: $msg")
        val missing = leftOf(obj("""{"kind":"data_quality","pipeline":"p"}"""))
        assert(missing.contains("items"), s"message must name `items`: $missing")
        val notArray = leftOf(obj("""{"kind":"data_quality","pipeline":"p","items":"check everything"}"""))
        assert(notArray.contains("items"), s"message must name `items`: $notArray")
    }

    test("twenty-one items is Left naming items") {
        val msg = leftOf(input(items = (1 to 21).map(i => item(s"check $i"))))
        assert(msg.contains("items"), s"message must name `items`: $msg")
    }

    test("a blank item text is Left naming text") {
        val blank = leftOf(input(items = Seq(item("amount is never negative"), item("   "))))
        assert(blank.contains("text"), s"message must name `text`: $blank")
        val missing = leftOf(input(items = Seq("""{"column":"amount"}""")))
        assert(missing.contains("text"), s"message must name `text`: $missing")
    }

    test("a missing or blank pipeline is Left naming pipeline") {
        val missing = leftOf(input(pipeline = None, items = three))
        assert(missing.contains("pipeline"), s"message must name `pipeline`: $missing")
        val blank = leftOf(input(pipeline = Some(""), items = three))
        assert(blank.contains("pipeline"), s"message must name `pipeline`: $blank")
    }

    test("the waiting text is non-empty and says the reply is the next user message") {
        assert(AgentLoop.RuleProposalWaitingText.nonEmpty)
        assert(AgentLoop.RuleProposalWaitingText.contains("next user message"), AgentLoop.RuleProposalWaitingText)
    }

    // ---------------------------------------------------------- tool def ---

    private lazy val toolDef: JsonObject = AssistantAPIController.syntheticRuleProposalToolDef()

    test("tool def is named propose_pipeline_rules and has a description") {
        assert(toolDef.get("name").getAsString == "propose_pipeline_rules")
        assert(toolDef.has("description") && toolDef.get("description").getAsString.nonEmpty)
    }

    test("tool def input_schema requires kind, pipeline and items") {
        val schema = toolDef.getAsJsonObject("input_schema")
        assert(schema.get("type").getAsString == "object")
        val req = schema.getAsJsonArray("required").asScala.map(_.getAsString).toSet
        assert(req == Set("kind", "pipeline", "items"), s"required: $req")
        val props = schema.getAsJsonObject("properties")
        assert(props.has("kind") && props.has("pipeline") && props.has("items"))
        assert(props.getAsJsonObject("items").get("type").getAsString == "array")
        val itemProps = props.getAsJsonObject("items").getAsJsonObject("items").getAsJsonObject("properties")
        assert(itemProps.has("text") && itemProps.has("column"))
    }
}
