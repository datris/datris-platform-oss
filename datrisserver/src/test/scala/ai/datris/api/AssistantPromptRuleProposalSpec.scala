package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Assistant checkpoint-2 cards: propose and confirm data-quality checks
  * and transformations (plans/stories/assistant-rule-proposal-cards.md),
  * Steps 3-4, Acceptance bullet 3.
  *
  * `AssistantAPIController.SchemaCheckpointRule` gains bullets after
  * "Checkpoint 2": call `propose_pipeline_rules` (kind data_quality, then
  * transformation), end the turn, read the confirmed list back in a fixed
  * shape, then `create_pipeline`. No domains or proper nouns
  * (feedback_prompt_no_domain_bias), in the rule or the tool def. */
class AssistantPromptRuleProposalSpec extends AnyFunSuite {

    private val rule: String = AssistantAPIController.SchemaCheckpointRule

    private def idx(token: String, from: Int = 0): Int = {
        val i = rule.indexOf(token, from)
        assert(i >= 0, s"SchemaCheckpointRule must name `$token` (after offset $from):\n$rule")
        i
    }

    /** `kind: "transformation"` (quoted or backticked), not just the word. */
    private val TransformationKind = """["`]transformation["`]""".r

    test("rule names propose_pipeline_rules and both kind values") {
        idx("propose_pipeline_rules")
        idx("data_quality")
        assert(TransformationKind.findFirstIn(rule).isDefined, s"rule must name kind `transformation`:\n$rule")
    }

    test("rule gives both confirmed-reply shapes") {
        idx("Confirmed data-quality checks for pipeline `")
        idx("Confirmed transformations")
    }

    test("rule gives both skip replies") {
        idx("No data-quality checks.")
        idx("No transformations.")
    }

    test("the proposal bullets come after Checkpoint 2 and before create_pipeline") {
        val cp2 = idx("Checkpoint 2")
        val propose = idx("propose_pipeline_rules")
        assert(propose > cp2, s"propose_pipeline_rules must come after Checkpoint 2:\n$rule")
        idx("create_pipeline", propose)
    }

    test("data quality is proposed before transformations") {
        val cp2 = idx("Checkpoint 2")
        val dq = idx("data_quality", cp2)
        val tr = TransformationKind.findFirstMatchIn(rule.substring(cp2)).map(_.start + cp2)
        assert(tr.isDefined, s"kind `transformation` must appear after Checkpoint 2:\n$rule")
        assert(dq < tr.get, s"data_quality must come before transformation:\n$rule")
    }

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|sqlserver|sql server|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock|ticker|customer|patient|invoice|order_id)\b""".r

    test("rule carries no vendor/domain proper noun") {
        val hits = vendorOrDomain.findAllIn(rule).toList
        assert(hits.isEmpty, s"domain bias in SchemaCheckpointRule: $hits")
    }

    test("the propose_pipeline_rules tool def carries no vendor/domain proper noun") {
        val text = AssistantAPIController.syntheticRuleProposalToolDef().toString
        val hits = vendorOrDomain.findAllIn(text).toList
        assert(hits.isEmpty, s"domain bias in propose_pipeline_rules tool def: $hits")
    }

    // Story: Assistant data-quality card: the user chooses warn-only vs reject
    // on failure (plans/stories/assistant-dq-card-on-failure.md), Acceptance
    // bullet 3. The DQ bullet reads the severity from the confirmed reply and
    // passes it as `codegen_rule_on_failure`; all of it before the
    // transformation `kind`.

    /** The data-quality bullet: from `data_quality` after Checkpoint 2 up to the
      * first `kind` transformation after it. */
    private def dqBullet: String = {
        val cp2 = idx("Checkpoint 2")
        val dq = idx("data_quality", cp2)
        val tr = TransformationKind.findFirstMatchIn(rule.substring(dq)).map(_.start + dq).getOrElse(rule.length)
        rule.substring(dq, tr)
    }

    test("the data-quality bullet names codegen_rule_on_failure with both \"warn\" and \"error\"") {
        val b = dqBullet
        assert(b.contains("codegen_rule_on_failure"), s"DQ bullet must name codegen_rule_on_failure:\n$b")
        assert(b.contains("\"warn\""), s"DQ bullet must name the quoted value warn:\n$b")
        assert(b.contains("\"error\""), s"DQ bullet must name the quoted value error:\n$b")
    }

    test("the data-quality bullet gives both on-failure reply shapes") {
        val b = dqBullet
        assert(b.contains("Confirmed data-quality checks for pipeline `<name>` (on failure: reject the run):"),
            s"DQ bullet must give the reject reply shape:\n$b")
        assert(b.contains("(on failure: warn only)"), s"DQ bullet must give the warn-only reply shape:\n$b")
    }
}
