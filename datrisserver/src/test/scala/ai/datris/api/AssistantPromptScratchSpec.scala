package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Scratch results over MCP and the CLI, and the KEEP-OR-SCRATCH rule
  * (plans/stories/scratch-mcp-cli-prompts.md).
  *
  *  `buildSystemPrompt` is private and needs a workflow reference plus tenant
  *  env, so the story lifts the rule's text into a companion the spec can
  *  read directly:
  *
  *  {{{
  *  object AssistantAPIController {
  *      private[datris] val KeepOrScratchRule: String
  *  }
  *  }}}
  *
  *  Same wording discipline as the MCP instructions (test_scratch_tools.py):
  *  no vendor or domain proper nouns, no concrete cron schedule — those leak
  *  as domain bias (feedback_prompt_no_domain_bias).
  */
class AssistantPromptScratchSpec extends AnyFunSuite {

    private val rule: String = AssistantAPIController.KeepOrScratchRule

    test("rule names scratch and the paging tool") {
        assert(rule.nonEmpty)
        assert(rule.contains("scratch"))
        assert(rule.contains("get_pipeline_result"))
        assert(rule.contains("resultPreview"))
    }

    test("rule says the keep / query later / schedule cases still go to a real destination") {
        val low = rule.toLowerCase
        assert(low.contains("schedule"))
        assert(low.contains("never create a table") || low.contains("never create a throwaway table"),
            "rule must forbid creating a table just to read rows back once")
    }

    test("rule mentions promotion via update_pipeline") {
        assert(rule.contains("update_pipeline"))
        assert(rule.toLowerCase.contains("promot"))
    }

    test("rule does not join the destination offering list") {
        assert(!rule.contains("DESTINATION OFFERING RULE"))
    }

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b""".r
    private val cron = """(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)""".r

    test("rule carries no vendor/domain proper noun or cron expression") {
        val hits = vendorOrDomain.findAllIn(rule).toList
        assert(hits.isEmpty, s"domain bias in KeepOrScratchRule: $hits")
        assert(cron.findFirstIn(rule).isEmpty, "cron expression in KeepOrScratchRule")
    }
}
