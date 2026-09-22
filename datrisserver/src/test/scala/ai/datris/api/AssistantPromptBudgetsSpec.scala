package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.TapBudgets
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/** Story: agents size a tap run against the budgets actually in force
  * (plans/stories/tap-sizing-effective-budgets.md), Step 4.
  *
  * The Assistant's two sizing bullets quote the documented defaults
  * ("PIPELINE_MAX_PAYLOAD_MB (default 4096 MB, 0 = unlimited)",
  * "TAP_RUN_TIMEOUT_SECONDS (default 3600 s)"), so on an install that raised
  * the variable it declares a breach against a number that is not in force.
  * The story lifts both bullets into a companion seam the prompt builds per
  * request, so the live value is interpolated:
  *
  * {{{
  * object AssistantAPIController {
  *     private[datris] def sizingRule(b: TapBudgets.Effective): String
  * }
  * }}}
  *
  * Same seam and the same wording discipline as `KeepOrScratchRule`
  * (AssistantPromptScratchSpec): no vendor or domain proper nouns — they leak
  * as domain bias (feedback_prompt_no_domain_bias). The worked example stays
  * budget-free so it cannot be mimicked as a budget
  * (feedback_ai_prompt_examples).
  */
class AssistantPromptBudgetsSpec extends AnyFunSuite {

    private def env(
        payloadMB: Int = -1,
        scriptTimeout: Int = 300,
        scriptSet: Boolean = false,
        runTimeout: Int = 3600,
        runSet: Boolean = false
    ): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tapScriptTimeoutSeconds = scriptTimeout,
        tapScriptTimeoutSecondsSet = scriptSet,
        tapRunTimeoutSeconds = runTimeout,
        tapRunTimeoutSecondsSet = runSet,
        pipelineMaxPayloadMB = payloadMB
    )

    private def rule(e: DatrisEnvironment): String =
        AssistantAPIController.sizingRule(TapBudgets.effective(e))

    // ------------------------------------------- the value in force wins ---

    test("a raised disk budget is quoted as the value in force, with 4096 kept as the default") {
        val text = rule(env(payloadMB = 16384))
        assert(text.contains("currently 16384 MB on this install"))
        assert(text.contains("default 4096"))
    }

    test("a default install still says 4096 is what it runs with") {
        val text = rule(env())
        assert(text.contains("currently 4096 MB on this install"))
    }

    test("an unlimited disk budget is described as unlimited, not as 0 MB") {
        assert(rule(env(payloadMB = 0)).toLowerCase.contains("unlimited"))
    }

    test("a raised run timeout is quoted as the value in force") {
        val text = rule(env(runTimeout = 7200, runSet = true))
        assert(text.contains("currently 7200 s on this install"))
        assert(text.contains("default 3600"))
    }

    test("the rule tells the agent to compare against the value in force, not the default") {
        val low = rule(env(payloadMB = 16384)).toLowerCase
        assert(low.contains("in force"), "the rule must say these are the values in force")
        assert(low.contains("never against the defaults") || low.contains("not the defaults"))
    }

    // ------------------------------------ the existing rules are intact ---

    test("the rule still names all three budget variables") {
        val text = rule(env())
        assert(text.contains("PIPELINE_MAX_PAYLOAD_MB"))
        assert(text.contains("TAP_RUN_TIMEOUT_SECONDS"))
        assert(text.contains("TAP_SCRIPT_TIMEOUT_SECONDS"))
    }

    test("the rule still carries both measured rates and the bytes-per-column rule") {
        val text = rule(env())
        assert("""26\s?(bytes|B)\s?(×|x|\*)\s?(column|col)""".r.findFirstIn(text).isDefined, text)
        assert(text.contains("15,000"), "the per-row rate must stay")
        assert(text.contains("90,000"), "the batch rate must stay")
        assert(
            """rows,? bytes/record,? GB.{0,20}minutes""".r.findFirstIn(text).isDefined,
            "the four-line estimate table must stay"
        )
        assert(text.contains("three choices"), "the three-choice fork must stay")
    }

    test("the no-splitting rule stays in buildSystemPrompt, outside the lifted seam") {
        val source = new String(
            Files.readAllBytes(repoRoot.resolve("datrisserver/src/main/scala/ai/datris/api/AssistantAPIController.scala")),
            java.nio.charset.StandardCharsets.UTF_8
        )
        assert(source.contains("Never split a source for size"))
        assert(source.contains("def sizingRule"), "the sizing bullets must be lifted into a readable seam")
        assert(source.contains("sizingRule("), "buildSystemPrompt must call the seam")
    }

    // ------------------------------------------------ wording discipline ---

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b""".r

    test("the rule carries no vendor/domain proper noun at any budget value") {
        for (e <- Seq(env(), env(payloadMB = 16384), env(payloadMB = 0))) {
            val hits = vendorOrDomain.findAllIn(rule(e)).toList
            assert(hits.isEmpty, s"domain bias in sizingRule: $hits")
        }
    }

    test("the worked example carries no budget number that could be mimicked as one") {
        val text = rule(env(payloadMB = 16384))
        val at = text.indexOf("20,000,000")
        assert(at >= 0, "the worked example must stay in the rule")
        val example = text.substring(at)
        assert(!example.take(160).contains("4096"), "the worked example must stay budget-free: " + example.take(160))
    }

    private def repoRoot: Path = {
        var p = Paths.get("").toAbsolutePath
        while (p != null && !Files.isRegularFile(p.resolve("docker-compose.yml"))) p = p.getParent
        assert(p != null, "could not locate the repository root from " + Paths.get("").toAbsolutePath)
        p
    }
}
