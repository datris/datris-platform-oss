package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonElement, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Configuration chat, server side (plans/stories/config-chat-server-seam.md),
  * Steps 4-6 and 10.
  *
  * Seams exercised:
  * {{{
  * object ConfigAgentTools { val readTools: List[JsonObject]; val mutatingTools: List[JsonObject] }
  * object ConfigToolFilter {
  *     def apply(tools: List[JsonObject], useUserAuth: Boolean, useApiKeys: Boolean,
  *               hostedOrTrial: Boolean, policyEnabled: Boolean): List[JsonObject]
  * }
  * class ConfigToolExecutor(scope: String, username: String, uiKey: String,
  *                          registry: ConfirmationRegistry, restCall: (method, path, body) => String = <loopback>) {
  *     def execute(name: String, input: JsonObject): String
  *     def redactForModel(result: String): String
  * }
  * }}}
  * `restCall` is injected so the spec proves no stub in this story reaches the
  * loopback REST hop (Out of scope: "Do not make restCall reachable").
  */
class ConfigAgentToolsSpec extends AnyFunSuite {

    private val ExpectedRead: Set[String] = Set(
        "get_ai_providers",
        "list_secrets",
        "get_secret_fields",
        "get_data_sources_fragment",
        "get_code_repo",
        "test_code_repo_connection",
        "list_users",
        "list_api_keys",
        "list_key_templates",
        "get_capability_catalog",
        "get_agent_policy",
        "query_audit_log",
        "get_audit_facets",
        "run_doctor"
    )

    private val ExpectedMutating: Set[String] = Set(
        "set_ai_provider_slot",
        "set_provider_credentials",
        "put_secret",
        "delete_secret",
        "set_data_sources_fragment",
        "set_code_repo",
        "create_repo_token",
        "delete_repo_token",
        "create_user",
        "set_user_role",
        "reset_user_password",
        "delete_user",
        "issue_api_key",
        "rotate_api_key",
        "revoke_api_key",
        "set_agent_policy",
        "use_recommended_policy"
    )

    private val UserTools = Set("list_users", "create_user", "set_user_role", "reset_user_password", "delete_user")
    private val KeyTools = Set("list_api_keys", "issue_api_key", "rotate_api_key", "revoke_api_key", "list_key_templates", "get_capability_catalog")
    private val PolicyTools = Set("get_agent_policy", "set_agent_policy", "use_recommended_policy")

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject
    private def name(t: JsonObject): String = t.get("name").getAsString
    private def names(ts: List[JsonObject]): Set[String] = ts.map(name).toSet
    private def all: List[JsonObject] = ConfigAgentTools.readTools ++ ConfigAgentTools.mutatingTools

    private def schemaOf(t: JsonObject): JsonObject =
        if (t.has("inputSchema")) t.getAsJsonObject("inputSchema") else t.getAsJsonObject("input_schema")

    /** Executor whose loopback REST hop fails the test if anything reaches it. */
    private def executor(username: String = "admin", registry: ConfirmationRegistry = new ConfirmationRegistry()): ConfigToolExecutor =
        new ConfigToolExecutor(
            "user:" + username,
            username,
            "ui-key",
            registry,
            restCall = (_, _, _) => fail("restCall must not be reachable from any tool in this story")
        )

    /** A plausible argument set per tool; nothing here is `admin`. */
    private def sampleInput(tool: String): JsonObject = tool match {
        case "create_user" => obj("""{"username":"bob","role":"viewer"}""")
        case "set_user_role" => obj("""{"username":"bob","role":"editor"}""")
        case "reset_user_password" | "delete_user" => obj("""{"username":"bob"}""")
        case "issue_api_key" => obj("""{"label":"ci","template":"read-only"}""")
        case "rotate_api_key" | "revoke_api_key" => obj("""{"label":"ci"}""")
        case "put_secret" => obj("""{"name":"my-secret","fields":{"user":"u","password":"hunter2-do-not-echo"}}""")
        case "delete_secret" | "create_repo_token" | "delete_repo_token" => obj("""{"name":"my-secret"}""")
        case "set_ai_provider_slot" => obj("""{"slot":"ai-primary","provider":"openai"}""")
        case "set_provider_credentials" => obj("""{"provider":"anthropic"}""")
        case "set_data_sources_fragment" => obj("""{"content":"some text","enabled":true}""")
        case "set_code_repo" => obj("""{"config":{"branch":"main"}}""")
        case _ => new JsonObject()
    }

    private def parse(s: String): JsonObject = {
        val el = JsonParser.parseString(s)
        assert(el.isJsonObject, s"executor result is not a JSON object: $s")
        el.getAsJsonObject
    }

    private def str(o: JsonObject, k: String): Option[String] =
        if (o.has(k) && o.get(k).isJsonPrimitive) Some(o.get(k).getAsString) else None

    // ------------------------------------------------------- tool definitions ---

    test("read and mutating tool lists name exactly the plan's Tool set") {
        assert(names(ConfigAgentTools.readTools) == ExpectedRead)
        assert(names(ConfigAgentTools.mutatingTools) == ExpectedMutating)
        assert(all.size == (ExpectedRead ++ ExpectedMutating).size, "duplicate tool names")
    }

    test("every tool has a description and an object input schema") {
        all.foreach { t =>
            assert(t.has("description") && t.get("description").getAsString.trim.nonEmpty, s"${name(t)} has no description")
            val s = schemaOf(t)
            assert(s != null, s"${name(t)} has no input schema")
            assert(str(s, "type").contains("object"), s"${name(t)} schema is not an object")
        }
    }

    test("every mutating tool has an optional string confirmation_token; read tools do not") {
        ConfigAgentTools.mutatingTools.foreach { t =>
            val s = schemaOf(t)
            val props = s.getAsJsonObject("properties")
            assert(props != null && props.has("confirmation_token"), s"${name(t)} lacks confirmation_token")
            assert(str(props.getAsJsonObject("confirmation_token"), "type").contains("string"))
            val required = if (s.has("required")) s.getAsJsonArray("required").asScala.map(_.getAsString).toSet else Set.empty[String]
            assert(!required.contains("confirmation_token"), s"${name(t)} makes confirmation_token required")
        }
        ConfigAgentTools.readTools.foreach { t =>
            val props = Option(schemaOf(t).getAsJsonObject("properties"))
            assert(!props.exists(_.has("confirmation_token")), s"read tool ${name(t)} has confirmation_token")
        }
    }

    test("set_ai_provider_slot provider enum lists anthropic and openai first, then the rest") {
        val t = ConfigAgentTools.mutatingTools.find(name(_) == "set_ai_provider_slot").get
        val e = schemaOf(t).getAsJsonObject("properties").getAsJsonObject("provider").getAsJsonArray("enum")
            .asScala.map(_.getAsString).toList
        assert(e == List("anthropic", "openai", "azure", "bedrock", "grok", "ollama"))
        val slots = schemaOf(t).getAsJsonObject("properties").getAsJsonObject("slot").getAsJsonArray("enum")
            .asScala.map(_.getAsString).toList
        assert(slots == List("ai-primary", "codegen", "embedding", "web-search"))
    }

    // Same list as AssistantPromptBudgetsSpec / AssistantPromptScratchSpec
    // (feedback_prompt_no_domain_bias).
    private val vendorOrDomain =
        """(?i)\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b""".r

    private def descriptions(el: JsonElement): Seq[String] =
        if (el.isJsonObject) el.getAsJsonObject.entrySet().asScala.toSeq.flatMap { e =>
            if (e.getKey == "description" && e.getValue.isJsonPrimitive) Seq(e.getValue.getAsString)
            else descriptions(e.getValue)
        }
        else if (el.isJsonArray) el.getAsJsonArray.asScala.toSeq.flatMap(descriptions)
        else Nil

    test("no tool or parameter description carries a vendor/domain proper noun") {
        all.foreach { t =>
            descriptions(t).foreach { d =>
                val hits = vendorOrDomain.findAllIn(d).toList
                assert(hits.isEmpty, s"domain bias in ${name(t)}: $hits in: $d")
            }
        }
    }

    // --------------------------------------------------------------- filter ---

    private def filtered(useUserAuth: Boolean = true, useApiKeys: Boolean = true, hostedOrTrial: Boolean = false, policyEnabled: Boolean = true): Set[String] =
        names(ConfigToolFilter(all, useUserAuth, useApiKeys, hostedOrTrial, policyEnabled))

    test("all flags on: nothing is filtered") {
        assert(filtered() == ExpectedRead ++ ExpectedMutating)
    }

    test("useUserAuth=false drops every users tool and nothing else") {
        val f = filtered(useUserAuth = false)
        assert(f.intersect(UserTools).isEmpty, s"users tools left: ${f.intersect(UserTools)}")
        assert(!f.exists(_.contains("_user")), s"a *_user* tool survived: $f")
        assert(f == (ExpectedRead ++ ExpectedMutating) -- UserTools)
    }

    test("useApiKeys=false drops every keys tool and nothing else") {
        val f = filtered(useApiKeys = false)
        assert(!f.exists(_.contains("_api_key")), s"a *_api_key* tool survived: $f")
        assert(!f.contains("list_key_templates"))
        assert(!f.contains("get_capability_catalog"))
        assert(f == (ExpectedRead ++ ExpectedMutating) -- KeyTools)
    }

    test("hostedOrTrial=true drops the provider slot and credentials tools") {
        val f = filtered(hostedOrTrial = true)
        assert(!f.contains("set_ai_provider_slot"))
        assert(!f.contains("set_provider_credentials"))
        assert(f == (ExpectedRead ++ ExpectedMutating) -- Set("set_ai_provider_slot", "set_provider_credentials"))
    }

    test("policyEnabled=false keeps the policy tools") {
        assert(PolicyTools.subsetOf(filtered(policyEnabled = false)))
    }

    // ------------------------------------------------------------- executor ---

    test("every mutating tool called without a token yields needs_confirmation and no REST call") {
        ExpectedMutating.foreach { tool =>
            val r = parse(executor().execute(tool, sampleInput(tool)))
            assert(str(r, "status").contains("needs_confirmation"), s"$tool: $r")
            assert(str(r, "summary").exists(_.trim.nonEmpty), s"$tool has no summary: $r")
            assert(str(r, "token").exists(_.nonEmpty), s"$tool has no token: $r")
        }
    }

    test("the confirmation summary names the tool and carries no secret values") {
        val r = parse(executor().execute("put_secret", sampleInput("put_secret")))
        val summary = str(r, "summary").get
        assert(!summary.contains("hunter2-do-not-echo"), s"secret value leaked into summary: $summary")
        val d = parse(executor().execute("delete_user", obj("""{"username":"bob"}""")))
        assert(str(d, "summary").get.contains("bob"), "summary should carry the non-secret arguments")
    }

    test("every read tool yields no needs_confirmation and no REST call") {
        ExpectedRead.foreach { tool =>
            val raw = executor().execute(tool, sampleInput(tool))
            assert(!raw.contains("needs_confirmation"), s"$tool asked for confirmation: $raw")
        }
    }

    test("stub bodies in this story return not implemented") {
        assert(parse(executor().execute("list_users", new JsonObject())) == obj("""{"error":"not implemented"}"""))
    }

    test("unknown tool is an error") {
        assert(parse(executor().execute("drop_everything", new JsonObject())) == obj("""{"error":"unknown tool"}"""))
    }

    test("delete_user(admin) is refused and issues no token") {
        val r = parse(executor().execute("delete_user", obj("""{"username":"admin"}""")))
        assert(r == obj("""{"error":"The 'admin' user cannot be deleted"}"""))
        assert(!r.has("token"))
    }

    test("delete_user(Admin) is refused regardless of case or padding, with no token") {
        val r = parse(executor().execute("delete_user", obj("""{"username":" Admin "}""")))
        assert(r == obj("""{"error":"The 'admin' user cannot be deleted"}"""))
    }

    test("delete_user(admin) is refused even when a confirmation_token is supplied") {
        val reg = new ConfirmationRegistry()
        val ex = executor(registry = reg)
        val withToken = obj("""{"username":"admin","confirmation_token":"anything"}""")
        assert(parse(ex.execute("delete_user", withToken)) == obj("""{"error":"The 'admin' user cannot be deleted"}"""))
    }

    test("a confirmed call reaches the body once; the second confirmation is an error") {
        val ex = executor()
        val proposed = parse(ex.execute("delete_user", obj("""{"username":"bob"}""")))
        val token = str(proposed, "token").get
        val confirmed = obj("""{"username":"bob"}""")
        confirmed.addProperty("confirmation_token", token)
        assert(parse(ex.execute("delete_user", confirmed)) == obj("""{"error":"not implemented"}"""))
        assert(parse(ex.execute("delete_user", confirmed)) == obj("""{"error":"unknown or expired confirmation token"}"""))
    }

    test("a token confirmed against altered input is a mismatch error") {
        val ex = executor()
        val token = str(parse(ex.execute("delete_user", obj("""{"username":"bob"}"""))), "token").get
        val altered = obj("""{"username":"carol"}""")
        altered.addProperty("confirmation_token", token)
        assert(parse(ex.execute("delete_user", altered)) == obj("""{"error":"confirmation token does not match this request"}"""))
    }

    test("a token issued in the previous turn is consumed by a new executor with the same scope and registry") {
        val reg = new ConfirmationRegistry()
        val token = str(parse(executor(username = "alice", registry = reg).execute("revoke_api_key", obj("""{"label":"ci"}"""))), "token").get
        val confirmed = obj("""{"label":"ci"}""")
        confirmed.addProperty("confirmation_token", token)
        assert(parse(executor(username = "alice", registry = reg).execute("revoke_api_key", confirmed)) == obj("""{"error":"not implemented"}"""))
    }

    // ------------------------------------------------------------ redaction ---

    test("redactForModel strips key/value/temporaryPassword/token at top level and depth 2") {
        val in =
            """{"label":"ci","key":"dk_top","token":"t_top",
              | "result":{"username":"bob","temporaryPassword":"pw_nested","value":"v_nested","key":"dk_nested","token":"t_nested"}}""".stripMargin
        val out = executor().redactForModel(in)
        Seq("dk_top", "t_top", "pw_nested", "v_nested", "dk_nested", "t_nested").foreach { s =>
            assert(!out.contains(s), s"$s survived redaction: $out")
        }
        val o = parse(out)
        assert(str(o, "label").contains("ci"))
        assert(str(o.getAsJsonObject("result"), "username").contains("bob"))
        assert(str(o, "key").contains("[redacted]"))
        assert(str(o.getAsJsonObject("result"), "temporaryPassword").contains("[redacted]"))
    }

    test("redactForModel leaves the needs_confirmation envelope's token intact") {
        val env = """{"status":"needs_confirmation","summary":"Delete user bob","token":"abc123"}"""
        val o = parse(executor().redactForModel(env))
        assert(str(o, "token").contains("abc123"))
    }

    test("execute's needs_confirmation result still carries the token for the model") {
        val r = parse(executor().execute("rotate_api_key", obj("""{"label":"ci"}""")))
        assert(str(r, "token").exists(t => t.nonEmpty && t != "[redacted]"))
    }

    // Step 6 choice: the executor exposes lastFullResult; the controller puts it
    // on the tool_result SSE event while the model gets execute's return value.
    test("lastFullResult holds the full text of the most recent call") {
        val ex = executor()
        val r = ex.execute("delete_user", obj("""{"username":"bob"}"""))
        assert(ex.lastFullResult == r)
        val u = ex.execute("drop_everything", new JsonObject())
        assert(ex.lastFullResult == u)
    }

    test("AgentLoop turns a needs_confirmation result into a ConfirmRequest; other results are ignored") {
        val env = """{"status":"needs_confirmation","summary":"Run delete_user with username=bob.","token":"abc"}"""
        assert(AgentLoop.confirmRequestOf("toolu_1", "delete_user", env) ==
            Some(AgentLoop.LoopEvent.ConfirmRequest("toolu_1", "delete_user", "Run delete_user with username=bob.", "abc")))
        assert(AgentLoop.confirmRequestOf("toolu_1", "list_users", """{"error":"not implemented"}""").isEmpty)
        assert(AgentLoop.confirmRequestOf("toolu_1", "list_users", "plain text needs_confirmation").isEmpty)
    }
}
