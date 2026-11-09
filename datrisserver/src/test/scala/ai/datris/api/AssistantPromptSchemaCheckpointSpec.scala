package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Assistant preview-and-confirm schema before create_pipeline
  * (plans/stories/assistant-preview-confirm-schema.md), Acceptance 2.
  *
  * The rule is lifted into a companion the spec reads directly (same seam as
  * [[AssistantAPIController.KeepOrScratchRule]]):
  *
  * {{{
  * object AssistantAPIController {
  *     private[datris] val SchemaCheckpointRule: String
  * }
  * }}}
  *
  * Order: create_tap -> test_tap -> propose_pipeline_schema -> confirm types ->
  * one DQ/transform/protection question -> create_pipeline(schema_fields) ->
  * update_tap(target_pipeline). No domain proper nouns (feedback_prompt_no_domain_bias).
  */
class AssistantPromptSchemaCheckpointSpec extends AnyFunSuite {

    private val rule: String = AssistantAPIController.SchemaCheckpointRule

    private def idx(token: String): Int = {
        val i = rule.indexOf(token)
        assert(i >= 0, s"SchemaCheckpointRule must name `$token`:\n$rule")
        i
    }

    test("rule names propose_pipeline_schema, schema_fields and update_tap") {
        assert(rule.nonEmpty)
        idx("propose_pipeline_schema")
        idx("schema_fields")
        idx("update_tap")
    }

    test("rule orders test_tap before propose_pipeline_schema before create_pipeline") {
        val test = idx("test_tap")
        val propose = idx("propose_pipeline_schema")
        val create = idx("create_pipeline")
        assert(test < create, s"test_tap must come before create_pipeline:\n$rule")
        assert(test < propose, s"test_tap must come before propose_pipeline_schema:\n$rule")
        assert(propose < create, s"propose_pipeline_schema must come before create_pipeline:\n$rule")
    }

    test("update_tap (target_pipeline) comes after create_pipeline") {
        val create = idx("create_pipeline")
        assert(rule.indexOf("update_tap", create) > create, s"update_tap must follow create_pipeline:\n$rule")
    }

    test("rule offers DQ, transformation and protection with 'none' accepted") {
        idx("codegen_rule")
        idx("codegen_transform")
        assert(rule.contains("protect"), rule)
        assert(rule.toLowerCase.contains("none"), s"rule must accept 'none' as an answer:\n$rule")
    }

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|sqlserver|sql server|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock|ticker|customer|patient|invoice|order_id)\b""".r

    test("rule carries no vendor/domain proper noun") {
        val hits = vendorOrDomain.findAllIn(rule).toList
        assert(hits.isEmpty, s"domain bias in SchemaCheckpointRule: $hits")
    }
}
