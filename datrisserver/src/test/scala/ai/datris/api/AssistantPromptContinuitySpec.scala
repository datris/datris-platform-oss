package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.nio.file.{Files, Path, Paths}

import org.scalatest.funsuite.AnyFunSuite

/** Story: Assistant tool-call continuity across turns
  * (plans/stories/assistant-tool-call-continuity.md), Acceptance 6.
  *
  * The continuity bullet is lifted into a companion the spec reads directly
  * (same seam as [[AssistantAPIController.SchemaCheckpointRule]]):
  *
  * {{{
  * object AssistantAPIController {
  *     private[datris] val ContinuityRule: String
  * }
  * }}}
  *
  * `buildSystemPrompt` is private and needs a workflow reference plus the
  * approved-sources registry, so "appears in the built prompt" is checked on
  * its source: the method body appends `ContinuityRule`, and the first-turn
  * bullet is scoped to the first data-related ask of a conversation.
  */
class AssistantPromptContinuitySpec extends AnyFunSuite {

    private val rule: String = AssistantAPIController.ContinuityRule

    private def repoRoot: Path = {
        var p = Paths.get("").toAbsolutePath
        while (p != null && !Files.isRegularFile(p.resolve("docker-compose.yml"))) p = p.getParent
        assert(p != null, "could not locate the repository root from " + Paths.get("").toAbsolutePath)
        p
    }

    private lazy val source: String = new String(
        Files.readAllBytes(repoRoot.resolve("datrisserver/src/main/scala/ai/datris/api/AssistantAPIController.scala")),
        java.nio.charset.StandardCharsets.UTF_8
    )

    /** The body of `buildSystemPrompt`, up to the next method definition. */
    private lazy val buildSystemPromptBody: String = {
        val i = source.indexOf("def buildSystemPrompt(")
        assert(i >= 0, "AssistantAPIController has no buildSystemPrompt")
        val next = """\n    (private |private\[\w+\] |override )?def """.r.findFirstMatchIn(source.substring(i + 10))
        next.map(m => source.substring(i, i + 10 + m.start)).getOrElse(source.substring(i))
    }

    test("ContinuityRule names list_pipelines, list_taps and refresh") {
        assert(rule.nonEmpty)
        assert(rule.contains("list_pipelines"), rule)
        assert(rule.contains("list_taps"), rule)
        assert(rule.toLowerCase.contains("refresh"), rule)
    }

    test("ContinuityRule tells the model to reuse a tap or pipeline it already created") {
        assert(rule.toLowerCase.contains("reuse"), rule)
        assert(rule.contains("create_tap"), rule)
    }

    test("ContinuityRule appears in the built prompt") {
        assert(buildSystemPromptBody.contains("ContinuityRule"), "buildSystemPrompt must append ContinuityRule")
    }

    test("the first-turn discovery bullet says 'first data-related ask of a conversation'") {
        assert(
            buildSystemPromptBody.contains("first data-related ask of a conversation"),
            "the list_pipelines AND list_taps bullet must be scoped to the first data-related ask of a conversation"
        )
        assert(
            !buildSystemPromptBody.contains("On the first turn of any data-related request"),
            "the old per-turn wording must be gone"
        )
    }

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock|exchange rate)\b""".r

    test("ContinuityRule carries no vendor/domain proper noun") {
        val hits = vendorOrDomain.findAllIn(rule).toList
        assert(hits.isEmpty, s"domain bias in ContinuityRule: $hits")
    }
}
