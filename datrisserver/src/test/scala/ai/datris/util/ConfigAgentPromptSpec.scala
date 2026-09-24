package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Configuration chat, server side: system prompt
  * (plans/stories/config-chat-prompt.md), Step 7.
  *
  * Seam exercised (signature unchanged from the Story 1 placeholder):
  * {{{
  * object ConfigAgentPrompt { def build(tenantEnv: String, tab: Option[String]): String }
  * }}}
  *
  * Wording discipline mirrors `ai.datris.api.AssistantPromptBudgetsSpec`.
  */
class ConfigAgentPromptSpec extends AnyFunSuite {

    private lazy val noTab: String = ConfigAgentPrompt.build("test", None)
    private lazy val usersTab: String = ConfigAgentPrompt.build("test", Some("users"))
    private def both: Seq[(String, String)] = Seq("None" -> noTab, "Some(users)" -> usersTab)

    private val headings = Seq(
        "## Mission",
        "## What you can see",
        "## Behaviour rules",
        "## Stay in your lane",
        "## Finish"
    )

    test("the five ## headings are present in order") {
        for ((label, p) <- both) {
            val at = headings.map(h => h -> p.indexOf("\n" + h))
            at.foreach { case (h, i) => assert(i >= 0, s"[$label] missing heading `$h`") }
            val idx = at.map(_._2)
            assert(idx == idx.sorted, s"[$label] headings out of order: $at")
        }
    }

    // ------------------------------------------------ wording discipline ---

    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b""".r

    private val clockTime = """(?i)\b\d{1,2}:\d{2}\b""".r

    private val exampleKeyPrefixes = Seq("sk-", "xai-", "ghp_", "dk_")

    test("no vendor or domain proper noun (vendorOrDomain regex, includes github)") {
        for ((label, p) <- both) {
            val hits = vendorOrDomain.findAllIn(p).toList
            assert(hits.isEmpty, s"[$label] domain bias in ConfigAgentPrompt: $hits")
        }
    }

    test("no clock time") {
        for ((label, p) <- both) {
            val hits = clockTime.findAllIn(p).toList
            assert(hits.isEmpty, s"[$label] clock time in ConfigAgentPrompt: $hits")
        }
    }

    test("no example key values (sk-, xai-, ghp_, dk_)") {
        for ((label, p) <- both; pre <- exampleKeyPrefixes)
            assert(!p.contains(pre), s"[$label] example key value prefix `$pre` in ConfigAgentPrompt")
    }

    test("Anthropic and OpenAI occur the same number of times (zero allowed)") {
        def count(s: String, w: String): Int = w.r.findAllMatchIn(s).length
        for ((label, p) <- both) {
            val a = count(p, "Anthropic")
            val o = count(p, "OpenAI")
            assert(a == o, s"[$label] provider parity broken: Anthropic=$a OpenAI=$o")
        }
    }

    // ------------------------------------------------ protocol vocabulary ---

    private val requiredTerms = Seq(
        "confirmation_token",
        "needs_confirmation",
        "[confirm ",
        "[cancel ",
        "secret_request",
        "pending_approval"
    )

    test("contains confirmation_token, needs_confirmation, [confirm , [cancel , secret_request, pending_approval") {
        for ((label, p) <- both) {
            val missing = requiredTerms.filterNot(p.contains)
            assert(missing.isEmpty, s"[$label] prompt is missing: $missing")
        }
    }

    private val allowedProtocolIds =
        Set("confirmation_token", "needs_confirmation", "secret_request", "pending_approval", "policy_denied")

    test("every backticked snake_case identifier is a Configuration tool or an allowed protocol word") {
        val known = ConfigAgentTools.readToolNames ++ ConfigAgentTools.mutatingToolNames ++ allowedProtocolIds
        val ident = """`([a-z_]+)`""".r
        for ((label, p) <- both) {
            val ids = ident.findAllMatchIn(p).map(_.group(1)).filter(_.contains("_")).toSet
            val unknown = ids -- known
            assert(unknown.isEmpty, s"[$label] backticked identifiers that are neither tools nor allow-listed: $unknown")
        }
    }

    // ------------------------------------------------------- active tab ---

    test("the Some(\"users\") build names the users sub-tab") {
        assert(usersTab.contains("users"), "the reported sub-tab must appear in the prompt")
    }

    test("the None build says no sub-tab was reported") {
        assert(noTab.contains("no sub-tab"), "the None build must say \"no sub-tab\" (Step 3 wording)")
    }

    // ------------------------------------------------------- own forms ---

    test("the prompt does not mention request_tap_secret_from_user") {
        for ((label, p) <- both)
            assert(!p.contains("request_tap_secret_from_user"),
                s"[$label] the Configuration chat must use its own form tools, not the Assistant's")
    }
}
