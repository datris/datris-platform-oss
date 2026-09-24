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
        assert(
            low.contains("never create a table") || low.contains("never create a throwaway table"),
            "rule must forbid creating a table just to read rows back once"
        )
    }

    test("rule mentions promotion via update_pipeline") {
        assert(rule.contains("update_pipeline"))
        assert(rule.toLowerCase.contains("promot"))
    }

    // --- Story: live-read-naming (plans/stories/live-read-naming.md) ----------------
    //
    // The rule is renamed KEEP-OR-READ-LIVE and names Live Read; the
    // destination-defaults block is lifted into a seam
    //
    //   object AssistantAPIController {
    //       private[datris] def destinationDefaultsRule(structuredDests: Seq[String]): String
    //   }
    //
    // which names Live Read only when objectstore is available (mirrors the UI's
    // isDestAvailable('scratch')). scratch is never a structured destination,
    // never the default.

    test("rule is headed KEEP-OR-READ-LIVE and names Live Read") {
        assert(rule.contains("KEEP-OR-READ-LIVE"), "heading must be KEEP-OR-READ-LIVE")
        assert(!rule.contains("KEEP-OR-SCRATCH"), "old heading must be gone")
        assert(rule.contains("Live Read"))
        // The JSON key is still what the model must emit.
        assert(rule.contains("destination: {\"scratch\": {}}"))
    }

    private val allFive = Seq("mongodb", "postgres", "objectstore", "snowflake", "databricks")
    private val withObjectStore = Seq("mongodb", "postgres", "objectstore")
    private val withoutObjectStore = Seq("mongodb", "postgres")

    test("destinationDefaultsRule names Live Read when objectstore is available") {
        val text = AssistantAPIController.destinationDefaultsRule(withObjectStore)
        assert(text.contains("Live Read"), s"Live Read missing with objectstore present:\n$text")
        assert(
            text.contains("or Live Read if you only need the rows back now and nothing kept. Which do you prefer?"),
            s"literal example must end with the Live Read option:\n$text"
        )
        assert(
            text.contains("Do not offer Live Read when the user has asked for a schedule."),
            s"schedule exclusion missing:\n$text"
        )
        assert(AssistantAPIController.destinationDefaultsRule(allFive).contains("Live Read"))
    }

    test("destinationDefaultsRule does not name Live Read without objectstore") {
        val text = AssistantAPIController.destinationDefaultsRule(withoutObjectStore)
        assert(text.nonEmpty)
        assert(!text.contains("Live Read"), s"Live Read offered without objectstore:\n$text")
        assert(!text.toLowerCase.contains("scratch"), s"scratch leaked into the block without objectstore:\n$text")
    }

    test("Live Read never becomes the default destination") {
        val text = AssistantAPIController.destinationDefaultsRule(withObjectStore)
        // MongoDB stays the structured default when available.
        assert(text.contains("**MongoDB** (flexible schema, tolerates shape drift across runs) by default."), text)
        assert(
            !text.contains(
                "**Live Read** (hands the rows back once through data quality and transformation; nothing is landed or catalogued, the result expires; promote to a real destination later without touching the tap or its schedule) by default"
            ),
            text
        )
        assert(!text.contains("Live Read by default"), text)
        // Only objectstore available: object store is the default, Live Read is the extra option.
        val osOnly = AssistantAPIController.destinationDefaultsRule(Seq("objectstore"))
        assert(osOnly.contains("**object store** (Parquet, ORC, or an Iceberg table) by default."), osOnly)
        assert(osOnly.contains("Live Read"), osOnly)
    }

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b""".r
    private val cron = """(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)""".r

    test("rule carries no vendor/domain proper noun or cron expression") {
        val hits = vendorOrDomain.findAllIn(rule).toList
        assert(hits.isEmpty, s"domain bias in KeepOrScratchRule: $hits")
        assert(cron.findFirstIn(rule).isEmpty, "cron expression in KeepOrScratchRule")
    }

    test("destinationDefaultsRule carries no vendor/domain proper noun or cron expression") {
        // Snowflake / Databricks are real destination names, so sweep sets without them.
        for (dests <- Seq(withObjectStore, withoutObjectStore, Seq("objectstore"))) {
            val text = AssistantAPIController.destinationDefaultsRule(dests)
            val hits = vendorOrDomain.findAllIn(text).toList
            assert(hits.isEmpty, s"domain bias in destinationDefaultsRule($dests): $hits")
            assert(cron.findFirstIn(text).isEmpty, s"cron expression in destinationDefaultsRule($dests)")
        }
    }
}
