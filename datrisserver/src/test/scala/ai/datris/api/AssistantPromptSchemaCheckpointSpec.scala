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

    // Story: Assistant: vector destinations skip checkpoint 2
    // (plans/stories/assistant-vector-skip-checkpoint-2.md), Acceptance bullet 1.

    /** Bullets ("- " lines) that start strictly after the card bullet and before "Checkpoint 2:". */
    private def vectorBullet: String = {
        val card = idx("In this chat the proposal is a card")
        val cp2 = idx("Checkpoint 2:")
        val bullets = rule.split("\n").toList
        var pos = 0
        val between = bullets.flatMap { line =>
            val start = pos
            pos += line.length + 1
            if (start > card && start + line.length < cp2 && line.startsWith("- ")) Some(line) else None
        }
        val hit = between.find(b => b.toLowerCase.contains("vector") && b.toLowerCase.contains("checkpoint 2"))
        assert(
            hit.isDefined,
            s"a bullet naming 'vector' and 'checkpoint 2' must sit between the card bullet and 'Checkpoint 2:'; between were: $between\n$rule"
        )
        hit.get
    }

    test("vector bullet naming checkpoint 2 sits between the card bullet and Checkpoint 2") {
        val b = vectorBullet
        assert(b.contains("create_pipeline"), s"vector bullet must name create_pipeline:\n$b")
        assert(b.contains("update_tap"), s"vector bullet must name update_tap:\n$b")
    }

    test("vector bullet says propose_pipeline_rules is never called") {
        val b = vectorBullet
        val i = b.indexOf("propose_pipeline_rules")
        assert(i >= 0, s"vector bullet must name propose_pipeline_rules:\n$b")
        val before = b.substring(math.max(0, i - 120), i).toLowerCase
        assert(
            before.contains("never") || before.contains("do not") || before.contains("don't"),
            s"vector bullet must say propose_pipeline_rules is never called:\n$b"
        )
    }

    test("vector bullet names mongodb as keeping checkpoint 2") {
        val b = vectorBullet
        val m = b.indexOf("mongodb")
        assert(m >= 0, s"vector bullet must name `mongodb`:\n$b")
        assert(
            b.toLowerCase.indexOf("keeps checkpoint 2", m) > m || b.toLowerCase.indexOf("keep checkpoint 2", m) > m,
            s"vector bullet must say mongodb keeps checkpoint 2:\n$b"
        )
    }

    test("'At most two checkpoints' replaces 'Two checkpoints, no more'") {
        assert(rule.contains("At most two checkpoints"), rule)
        assert(!rule.contains("Two checkpoints, no more"), rule)
    }

    // Story: Assistant: preview-only schema card is informational
    // (plans/stories/assistant-preview-card-informational.md), Acceptance bullet 2.
    // The typed card keeps its buttons and ends the turn; a `typeGrid: false`
    // card is a sample-data card with no buttons and the turn continues.

    /** The card bullet: the "- " line that describes the typed card's Confirm types button. */
    private def cardBullet: String = {
        val hit = rule.split("\n").toList.find(l => l.startsWith("- ") && l.contains("Confirm types"))
        assert(hit.isDefined, s"a bullet describing the Confirm types card must exist:\n$rule")
        hit.get
    }

    private def sentences(s: String): List[String] = s.split("(?<=[.!?])\\s+").toList

    test("rule no longer mentions a Looks right button or the 'Preview looks right.' reply") {
        assert(!rule.contains("Preview looks right"), s"'Preview looks right' must be gone:\n$rule")
        assert(!rule.contains("Looks right"), s"'Looks right' must be gone:\n$rule")
    }

    test("card bullet says a typeGrid false card has no buttons") {
        val b = cardBullet
        assert(b.contains("typeGrid"), s"card bullet must name typeGrid:\n$b")
        assert("(?i)no buttons".r.findFirstIn(b).isDefined, s"card bullet must say the sample-data card has no buttons:\n$b")
    }

    test("card bullet says the turn continues after a sample-data card") {
        val b = cardBullet
        assert("(?i)turn continues|do not wait".r.findFirstIn(b).isDefined, s"card bullet must say the turn continues:\n$b")
    }

    test("card bullet: mongodb goes to checkpoint 2 in the same sentence") {
        val hit = sentences(cardBullet).find(s => s.contains("mongodb") && s.toLowerCase.contains("checkpoint 2"))
        assert(hit.isDefined, s"a card-bullet sentence must name both `mongodb` and checkpoint 2:\n$cardBullet")
    }

    test("card bullet: a vector store goes to create_pipeline in the same sentence") {
        val hit = sentences(cardBullet).find(s => s.toLowerCase.contains("vector") && s.contains("create_pipeline"))
        assert(hit.isDefined, s"a card-bullet sentence must name both vector and create_pipeline:\n$cardBullet")
    }
}
