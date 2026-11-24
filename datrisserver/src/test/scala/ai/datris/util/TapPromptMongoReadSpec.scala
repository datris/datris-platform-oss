package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.AssistantAPIController
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/** Story: MongoDB query: every query has a time limit, and an unlimited read
  * cannot exhaust the server's memory (plans/stories/mongodb-query-bounds.md),
  * review round 2. The server-side tap prompts (script generator,
  * brainstormer, Assistant) show the body `POST /api/v1/query/mongodb`
  * actually reads (`filter`, never `query`) and state the MongoDB bounds and
  * the status check. The brainstormer's prompt is a local value, so it is read
  * on its source (same approach as AssistantPromptContinuitySpec). */
class TapPromptMongoReadSpec extends AnyFunSuite {

    private def repoRoot: Path = {
        var p = Paths.get("").toAbsolutePath
        while (p != null && !Files.isRegularFile(p.resolve("docker-compose.yml"))) p = p.getParent
        assert(p != null, "could not locate the repository root from " + Paths.get("").toAbsolutePath)
        p
    }

    private def source(rel: String): String =
        new String(Files.readAllBytes(repoRoot.resolve(rel)), java.nio.charset.StandardCharsets.UTF_8)

    /** A `query` key shown for the MongoDB route: `"query":`, `{query,` or `\"query\":`. */
    private val QueryField = """query/mongodb[^\n]{0,120}?(\\?"query\\?"\s*:|\{\s*query\s*,)""".r

    private val rules = TapPlatformQueryGuidance.MongoReadRules

    test("the shared MongoDB read rules state the limits, the ceiling, range splitting, extended JSON and the status check") {
        assert(rules.contains("30 seconds"), rules)
        assert(rules.contains("300 seconds"), rules)
        assert(rules.contains("result-size ceiling"), rules)
        assert(rules.contains("32 MB"), rules)
        assert(rules.contains("disjoint ranges with `filter`"), rules)
        assert(rules.contains("\"limit\": -1"), rules)
        assert(rules.contains("$oid") && rules.contains("$date"), rules)
        assert(rules.contains("HTTP 400"), rules)
        assert(rules.contains("must check the status"), rules)
        assert(rules.contains("fails the run"), rules)
    }

    test("the script generator prompt shows filter for the MongoDB route and carries the read rules") {
        val p = TapScriptGenerator.SYSTEM_PROMPT
        assert(p.contains("POST /api/v1/query/mongodb"), "generator prompt no longer names the MongoDB route")
        assert(QueryField.findFirstIn(p).isEmpty, "generator prompt shows a query field for /query/mongodb")
        assert(p.contains("{\"filter\": {...}, \"database\": \"{mongo_db}\""), "generator prompt does not show the filter body")
        assert(p.contains(rules), "generator prompt does not carry the MongoDB read rules")
        assert(!p.contains("There is no pagination on these endpoints"), "the no-pagination sentence is unqualified")
        assert(p.contains("when it finishes within the limits below"), "the full-result sentence is not qualified by the limits")
    }

    test("the brainstormer prompt shows filter for the MongoDB route and carries the read rules") {
        val src = source("datrisserver/src/main/scala/ai/datris/util/TapBrainstormer.scala")
        val start = src.indexOf("val structuredSystemPrompt =")
        assert(start >= 0, "TapBrainstormer has no structuredSystemPrompt")
        val body = src.substring(start, src.indexOf(".stripMargin", start))
        assert(body.startsWith("val structuredSystemPrompt =\n            s\"\"\""), "structuredSystemPrompt is not interpolated")
        assert(QueryField.findFirstIn(body).isEmpty, "brainstormer prompt shows a query field for /query/mongodb")
        assert(body.contains("POST /api/v1/query/mongodb with {filter, database, collection}"), body)
        assert(body.contains("$" + "{TapPlatformQueryGuidance.MongoReadRules}"), "brainstormer prompt does not carry the MongoDB read rules")
    }

    test("the Assistant's platform-data rule shows filter for the MongoDB route, carries the read rules and is in the prompt") {
        val rule = AssistantAPIController.PlatformDataReadRule
        assert(rule.contains("POST /api/v1/query/mongodb"), rule)
        assert(QueryField.findFirstIn(rule).isEmpty, "Assistant rule shows a query field for /query/mongodb")
        assert(rule.contains("{\"filter\": {...}"), rule)
        assert(rule.contains(rules), "Assistant rule does not carry the MongoDB read rules")
        val src = source("datrisserver/src/main/scala/ai/datris/api/AssistantAPIController.scala")
        assert(src.contains("sb.append(AssistantAPIController.PlatformDataReadRule)"), "buildSystemPrompt must append PlatformDataReadRule")
    }
}
