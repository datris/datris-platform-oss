package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonElement, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Stories: Configuration chat, server side (plans/stories/config-chat-server-seam.md,
  * Steps 4-6 and 10) and tool implementations (plans/stories/config-chat-tools-and-prompt.md,
  * Steps 1-6).
  *
  * Seams exercised:
  * {{{
  * object ConfigAgentTools { val readTools, mutatingTools: List[JsonObject]; val FormTools: Set[String] }
  * object ConfigToolFilter {
  *     def apply(tools: List[JsonObject], useUserAuth: Boolean, useApiKeys: Boolean,
  *               hostedOrTrial: Boolean, policyEnabled: Boolean): List[JsonObject]
  * }
  * class ConfigToolExecutor(scope: String, username: String, uiKey: String,
  *                          registry: ConfirmationRegistry, restCall: (method, path, body) => String = <loopback>) {
  *     def execute(name: String, input: JsonObject): String
  *     def redactForModel(result: String): String
  *     def lastFullResult: String
  * }
  * object ConfigToolExecutor {
  *     // Step 1 wrapping of the loopback's (status, body) into the rest seam's String.
  *     // Name pinned by this spec; the story leaves it open.
  *     private[util] def loopbackText(status: Int, body: String): String
  * }
  * object AgentLoop { private[util] def secretRequestOf(id: String, tool: String, raw: String): Option[LoopEvent.SecretRequest] }
  * }}}
  * `restCall` is injected with a recorder holding canned server bodies, so
  * each tool's REST calls (method, path, body) are asserted without a server.
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

    /** Records every loopback REST call and answers with a canned body.
      * `answers` is keyed by (method, path); anything else gets `default`. */
    private class Recorder(answers: Map[(String, String), String] = Map.empty, default: String = """{"ok":true}""") {
        val calls = scala.collection.mutable.ListBuffer.empty[(String, String, Option[String])]
        def apply(method: String, path: String, body: Option[String]): String = {
            calls += ((method, path, body))
            answers.getOrElse((method, path), default)
        }
    }

    private def executor(
        username: String = "admin",
        registry: ConfirmationRegistry = new ConfirmationRegistry(),
        rec: Recorder = new Recorder(),
        scope: String = null
    ): ConfigToolExecutor =
        new ConfigToolExecutor(
            if (scope != null) scope else username + ":s1",
            username,
            "ui-key",
            registry,
            restCall = (m, p, b) => rec(m, p, b)
        )

    /** Propose, then confirm with the issued token. The proposal must make no
      * REST call. Returns the model-facing result of the confirmed call. */
    private def confirm(ex: ConfigToolExecutor, rec: Recorder, tool: String, input: JsonObject): String = {
        val proposed = parse(ex.execute(tool, input.deepCopy()))
        assert(str(proposed, "status").contains("needs_confirmation"), s"$tool: $proposed")
        assert(rec.calls.isEmpty, s"$tool proposal made REST calls: ${rec.calls}")
        val confirmed = input.deepCopy()
        confirmed.addProperty("confirmation_token", str(proposed, "token").get)
        ex.execute(tool, confirmed)
    }

    private def bodyJson(call: (String, String, Option[String])): JsonElement = {
        assert(call._3.exists(_.trim.nonEmpty), s"${call._1} ${call._2} has no body")
        JsonParser.parseString(call._3.get)
    }

    private def noBody(call: (String, String, Option[String])): Boolean = call._3.forall(_.trim.isEmpty)

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

    test("every mutating tool except the form tools has an optional string confirmation_token; read tools do not") {
        ConfigAgentTools.mutatingTools.filterNot(t => ConfigAgentTools.FormTools.contains(name(t))).foreach { t =>
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

    test("FormTools are set_provider_credentials and create_repo_token, stay mutating, and carry no confirmation_token") {
        assert(ConfigAgentTools.FormTools == Set("set_provider_credentials", "create_repo_token"))
        assert(ConfigAgentTools.FormTools.subsetOf(ConfigAgentTools.mutatingToolNames))
        ConfigAgentTools.mutatingTools.filter(t => ConfigAgentTools.FormTools.contains(name(t))).foreach { t =>
            val props = Option(schemaOf(t).getAsJsonObject("properties"))
            assert(!props.exists(_.has("confirmation_token")), s"form tool ${name(t)} has confirmation_token")
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

    test("every mutating tool except the form tools called without a token yields needs_confirmation and no REST call") {
        (ConfigAgentTools.mutatingToolNames -- ConfigAgentTools.FormTools).foreach { tool =>
            val rec = new Recorder()
            val r = parse(executor(rec = rec).execute(tool, sampleInput(tool)))
            assert(rec.calls.isEmpty, s"$tool made REST calls before confirmation: ${rec.calls}")
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

    test("every read tool yields no needs_confirmation and reaches the REST hop") {
        ExpectedRead.foreach { tool =>
            val rec = new Recorder()
            val raw = executor(rec = rec).execute(tool, sampleInput(tool))
            assert(!raw.contains("needs_confirmation"), s"$tool asked for confirmation: $raw")
            assert(rec.calls.nonEmpty, s"$tool made no REST call")
        }
    }

    // Replaces Story 1's "stub bodies in this story return not implemented":
    // Story 2 removes the NotImplemented stubs.
    test("no read tool or confirmed mutating tool answers not implemented") {
        ExpectedRead.foreach { tool =>
            val raw = executor().execute(tool, sampleInput(tool))
            assert(!raw.contains("not implemented"), s"$tool is still a stub: $raw")
        }
        (ConfigAgentTools.mutatingToolNames -- ConfigAgentTools.FormTools).foreach { tool =>
            val rec = new Recorder(answers = Map(("GET", "/api/v1/keys/templates") -> Templates, ("GET", "/api/v1/policy") -> PolicyDoc))
            val raw = confirm(executor(rec = rec), rec, tool, sampleInput(tool))
            assert(!raw.contains("not implemented"), s"$tool is still a stub: $raw")
        }
    }

    test("unknown tool is an error") {
        assert(parse(executor().execute("drop_everything", new JsonObject())) == obj("""{"error":"unknown tool"}"""))
    }

    test("delete_user(admin) is refused and issues no token") {
        val rec = new Recorder()
        val r = parse(executor(rec = rec).execute("delete_user", obj("""{"username":"admin"}""")))
        assert(r == obj("""{"error":"The 'admin' user cannot be deleted"}"""))
        assert(!r.has("token"))
        assert(rec.calls.isEmpty)
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

    test("a confirmed call reaches the body once; the second confirmation is an error and makes no call") {
        val rec = new Recorder(answers = Map(("DELETE", "/api/v1/auth/users/bob") -> """{"deleted":"bob"}"""))
        val ex = executor(rec = rec)
        val proposed = parse(ex.execute("delete_user", obj("""{"username":"bob"}""")))
        val token = str(proposed, "token").get
        val confirmed = obj("""{"username":"bob"}""")
        confirmed.addProperty("confirmation_token", token)
        assert(parse(ex.execute("delete_user", confirmed)) == obj("""{"deleted":"bob"}"""))
        assert(rec.calls.size == 1)
        assert(parse(ex.execute("delete_user", confirmed)) == obj("""{"error":"unknown or expired confirmation token"}"""))
        assert(rec.calls.size == 1, s"replayed token reached the REST hop: ${rec.calls}")
    }

    test("a token confirmed against altered input is a mismatch error") {
        val rec = new Recorder()
        val ex = executor(rec = rec)
        val token = str(parse(ex.execute("delete_user", obj("""{"username":"bob"}"""))), "token").get
        val altered = obj("""{"username":"carol"}""")
        altered.addProperty("confirmation_token", token)
        assert(parse(ex.execute("delete_user", altered)) == obj("""{"error":"confirmation token does not match this request"}"""))
        assert(rec.calls.isEmpty)
    }

    test("a token issued in the previous turn is consumed by a new executor with the same scope and registry") {
        val reg = new ConfirmationRegistry()
        val token = str(parse(executor(username = "alice", registry = reg).execute("revoke_api_key", obj("""{"label":"ci"}"""))), "token").get
        val confirmed = obj("""{"label":"ci"}""")
        confirmed.addProperty("confirmation_token", token)
        val rec = new Recorder(answers = Map(("DELETE", "/api/v1/keys/ci") -> """{"revoked":"ci"}"""))
        assert(parse(executor(username = "alice", registry = reg, rec = rec).execute("revoke_api_key", confirmed)) == obj("""{"revoked":"ci"}"""))
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("DELETE", "/api/v1/keys/ci")))
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
    // ======================================================================
    // Story 2 (config-chat-tools-and-prompt.md): tool bodies
    // ======================================================================

    private val Mask = "••••••••"

    private val Templates: String =
        """{"templates":[
          | {"name":"read-only","description":"observer","capabilities":["pipeline:read","tap:read"]},
          | {"name":"rag-builder","description":"rag","capabilities":["pipeline:write","search:vector"]}]}""".stripMargin

    private val PolicyInner: String =
        """{"version":3,
          | "actions":{"pipeline.delete":"approve","tap.run":"allow"},
          | "overrides":{"p1":{"pipeline.delete":"allow"},"p2":{"tap.run":"deny","recovery":"off"}},
          | "limits":{"pendingTtlHours":24,"maxPendingPerActor":10},
          | "recovery":{"mode":"propose","maxAiCallsPerIncident":5,"maxActionsPerIncident":3,"maxRuntimeMinutes":30,"maxOpenIncidents":2,"cooldownHours":6},
          | "updatedAt":"2026-09-01T00:00:00Z","updatedBy":"session:admin"}""".stripMargin

    private val RecommendedInner: String =
        """{"version":0,"actions":{"pipeline.delete":"approve","secret.write":"deny"},"overrides":{},
          | "limits":{"pendingTtlHours":72,"maxPendingPerActor":20},
          | "recovery":{"mode":"off","maxAiCallsPerIncident":10,"maxActionsPerIncident":5,"maxRuntimeMinutes":60,"maxOpenIncidents":3,"cooldownHours":24}}""".stripMargin

    private val PolicyDoc: String =
        s"""{"enabled":true,"policy":$PolicyInner,"pendingCount":0,"recommended":$RecommendedInner,"actions":["pipeline.delete","tap.run","secret.write"]}"""

    /** One confirmed mutation: exactly the expected (method, path) calls in
      * order, and the last call's body (when `expectBody` is given) equals it
      * as JSON. Returns the recorder for further checks. */
    private def assertOneCall(tool: String, input: String, method: String, path: String, expectBody: Option[String]): Recorder = {
        val rec = new Recorder()
        confirm(executor(rec = rec), rec, tool, obj(input))
        assert(rec.calls.map(c => (c._1, c._2)).toList == List((method, path)), s"$tool calls: ${rec.calls}")
        expectBody match {
            case Some(b) => assert(bodyJson(rec.calls.head) == JsonParser.parseString(b), s"$tool body: ${rec.calls.head._3}")
            case None => assert(noBody(rec.calls.head), s"$tool should send no body: ${rec.calls.head._3}")
        }
        rec
    }

    // ------------------------------------------------ one call per mutation ---

    test("set_ai_provider_slot codegen/anthropic: one PUT with the masked apiKey and version") {
        assertOneCall(
            "set_ai_provider_slot",
            """{"slot":"codegen","provider":"anthropic","model":"claude-opus-5-5"}""",
            "PUT",
            "/api/v1/secrets/codegen",
            Some(
                s"""{"provider":"anthropic","model":"claude-opus-5-5","endpoint":"https://api.anthropic.com/v1/messages","apiKey":"$Mask","version":"2023-06-01"}"""
            )
        )
    }

    test("set_ai_provider_slot web-search/openai with no model: enabled/maxUses strings and the default model") {
        val rec = assertOneCall(
            "set_ai_provider_slot",
            """{"slot":"web-search","provider":"openai"}""",
            "PUT",
            "/api/v1/secrets/web-search",
            Some(
                s"""{"provider":"openai","model":"gpt-5.5","endpoint":"https://api.openai.com/v1/responses","apiKey":"$Mask","enabled":"true","maxUses":"3"}"""
            )
        )
        val b = bodyJson(rec.calls.head).getAsJsonObject
        assert(b.get("enabled").getAsJsonPrimitive.isString && b.get("maxUses").getAsJsonPrimitive.isString)
    }

    test("set_ai_provider_slot with azure and no endpoint, or a chat slot with no model, errors before any call") {
        Seq(
            """{"slot":"ai-primary","provider":"azure","model":"gpt-5.5"}""",
            """{"slot":"ai-primary","provider":"openai"}"""
        ).foreach { in =>
            val rec = new Recorder()
            val r = parse(confirm(executor(rec = rec), rec, "set_ai_provider_slot", obj(in)))
            assert(r.has("error"), s"$in: $r")
            assert(rec.calls.isEmpty, s"$in reached the REST hop: ${rec.calls}")
        }
    }

    test("put_secret: one PUT of the fields plus _type") {
        assertOneCall(
            "put_secret",
            """{"name":"my-secret","fields":{"user":"u","password":"p"},"type":"custom"}""",
            "PUT",
            "/api/v1/secrets/my-secret",
            Some("""{"user":"u","password":"p","_type":"custom"}""")
        )
        assertOneCall("put_secret", """{"name":"my-secret","fields":{"user":"u"}}""", "PUT", "/api/v1/secrets/my-secret", Some("""{"user":"u"}"""))
    }

    test("delete_secret and delete_repo_token: one DELETE of the secret") {
        assertOneCall("delete_secret", """{"name":"my-secret"}""", "DELETE", "/api/v1/secrets/my-secret", None)
        assertOneCall("delete_repo_token", """{"name":"gh-token"}""", "DELETE", "/api/v1/secrets/gh-token", None)
    }

    test("set_data_sources_fragment: one POST to tap-prompts") {
        assertOneCall(
            "set_data_sources_fragment",
            """{"content":"Prefer official documentation.","enabled":true}""",
            "POST",
            "/api/v1/tap-prompts",
            Some("""{"key":"data-sources","aliases":[],"content":"Prefer official documentation.","enabled":true}""")
        )
    }

    test("set_code_repo: one PUT of the config as sent") {
        assertOneCall(
            "set_code_repo",
            """{"config":{"provider":"git","branch":"main","tokenSecret":"gh-token"}}""",
            "PUT",
            "/api/v1/code-repo",
            Some("""{"provider":"git","branch":"main","tokenSecret":"gh-token"}""")
        )
    }

    test("create_user: one POST with username and role, never a password") {
        val rec = assertOneCall(
            "create_user",
            """{"username":"bob","role":"viewer","generate_password":true}""",
            "POST",
            "/api/v1/auth/users",
            Some("""{"username":"bob","role":"viewer"}""")
        )
        assert(!rec.calls.head._3.get.toLowerCase.contains("password"))
    }

    test("set_user_role, reset_user_password, delete_user: one call each") {
        assertOneCall("set_user_role", """{"username":"bob","role":"editor"}""", "PATCH", "/api/v1/auth/users/bob", Some("""{"role":"editor"}"""))
        assertOneCall("reset_user_password", """{"username":"bob"}""", "PATCH", "/api/v1/auth/users/bob", Some("""{"resetPassword":true}"""))
        assertOneCall("delete_user", """{"username":"bob"}""", "DELETE", "/api/v1/auth/users/bob", None)
    }

    test("issue_api_key with explicit capabilities: one POST") {
        assertOneCall(
            "issue_api_key",
            """{"label":"ci","capabilities":["pipeline:read","job:read"]}""",
            "POST",
            "/api/v1/keys",
            Some("""{"label":"ci","capabilities":["pipeline:read","job:read"]}""")
        )
    }

    test("rotate_api_key and revoke_api_key: one call each") {
        val rec = new Recorder()
        confirm(executor(rec = rec), rec, "rotate_api_key", obj("""{"label":"ci"}"""))
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("POST", "/api/v1/keys/ci/rotate")))
        assertOneCall("revoke_api_key", """{"label":"ci"}""", "DELETE", "/api/v1/keys/ci", None)
    }

    // --------------------------------------------- two-call tools, in order ---

    test("issue_api_key with a template: GET templates then POST with that template's capabilities") {
        val rec = new Recorder(answers = Map(("GET", "/api/v1/keys/templates") -> Templates))
        confirm(executor(rec = rec), rec, "issue_api_key", obj("""{"label":"ci","template":"read-only"}"""))
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/keys/templates"), ("POST", "/api/v1/keys")))
        assert(bodyJson(rec.calls(1)) == JsonParser.parseString("""{"label":"ci","capabilities":["pipeline:read","tap:read"]}"""))
    }

    test("issue_api_key with an unknown template: error and no POST") {
        val rec = new Recorder(answers = Map(("GET", "/api/v1/keys/templates") -> Templates))
        val r = parse(confirm(executor(rec = rec), rec, "issue_api_key", obj("""{"label":"ci","template":"no-such-template"}""")))
        assert(r.has("error"), s"$r")
        assert(!rec.calls.exists(_._1 == "POST"), s"POST made for an unknown template: ${rec.calls}")
    }

    test("set_agent_policy: GET then PUT of the merged policy, keeping recovery and untouched keys") {
        val rec = new Recorder(answers = Map(("GET", "/api/v1/policy") -> PolicyDoc))
        confirm(
            executor(rec = rec),
            rec,
            "set_agent_policy",
            obj("""{"actions":{"tap.run":"approve"},"overrides":{"p1":null,"p3":{"tap.run":"deny"}},"limits":{"pendingTtlHours":48}}""")
        )
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/policy"), ("PUT", "/api/v1/policy")))
        val expected = obj(PolicyInner)
        expected.getAsJsonObject("actions").addProperty("tap.run", "approve")
        expected.getAsJsonObject("overrides").remove("p1")
        expected.getAsJsonObject("overrides").add("p3", obj("""{"tap.run":"deny"}"""))
        expected.getAsJsonObject("limits").addProperty("pendingTtlHours", 48)
        val put = bodyJson(rec.calls(1)).getAsJsonObject
        assert(put == expected, s"PUT body: $put")
        assert(put.getAsJsonObject("recovery") == obj(PolicyInner).getAsJsonObject("recovery"))
    }

    test("use_recommended_policy: GET then PUT of the recommended policy") {
        val rec = new Recorder(answers = Map(("GET", "/api/v1/policy") -> PolicyDoc))
        confirm(executor(rec = rec), rec, "use_recommended_policy", new JsonObject())
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/policy"), ("PUT", "/api/v1/policy")))
        assert(bodyJson(rec.calls(1)) == JsonParser.parseString(RecommendedInner))
    }

    test("policy tools surface the server's disabled error") {
        val disabled = """{"error":"agent policy is disabled","hint":"set USE_AGENT_POLICY=true and recreate the datris container"}"""
        val rec = new Recorder(answers = Map(("GET", "/api/v1/policy") -> PolicyDoc, ("PUT", "/api/v1/policy") -> disabled))
        val r = parse(confirm(executor(rec = rec), rec, "use_recommended_policy", new JsonObject()))
        assert(str(r, "error").contains("agent policy is disabled"), s"$r")
    }

    // ------------------------------------------------ show-once redaction ---

    test("create_user: temporaryPassword is redacted for the model and intact in lastFullResult") {
        val server = """{"username":"bob","role":"viewer","temporaryPassword":"Tmp-Show-Once-123"}"""
        val rec = new Recorder(answers = Map(("POST", "/api/v1/auth/users") -> server))
        val ex = executor(rec = rec)
        val model = confirm(ex, rec, "create_user", obj("""{"username":"bob","role":"viewer"}"""))
        assert(!model.contains("Tmp-Show-Once-123"), s"temporaryPassword reached the model: $model")
        assert(str(parse(model), "temporaryPassword").contains(Redacted))
        assert(str(parse(model), "username").contains("bob"))
        assert(ex.lastFullResult.contains("Tmp-Show-Once-123"))
        assert(parse(ex.lastFullResult) == obj(server))
    }

    test("issue_api_key and rotate_api_key: value is redacted for the model and intact in lastFullResult") {
        val issued = """{"label":"ci","value":"dk_show_once_abc","keyId":"k1","capabilities":["pipeline:read"],"createdAt":"t","createdBy":"session:admin"}"""
        val rec = new Recorder(answers = Map(("POST", "/api/v1/keys") -> issued, ("POST", "/api/v1/keys/ci/rotate") -> issued))
        val ex = executor(rec = rec)
        val model = confirm(ex, rec, "issue_api_key", obj("""{"label":"ci","capabilities":["pipeline:read"]}"""))
        assert(!model.contains("dk_show_once_abc"), s"key value reached the model: $model")
        assert(str(parse(model), "keyId").contains("k1"))
        assert(parse(ex.lastFullResult) == obj(issued))

        val rec2 = new Recorder(answers = Map(("POST", "/api/v1/keys/ci/rotate") -> issued))
        val ex2 = executor(rec = rec2)
        val model2 = confirm(ex2, rec2, "rotate_api_key", obj("""{"label":"ci"}"""))
        assert(!model2.contains("dk_show_once_abc"))
        assert(ex2.lastFullResult.contains("dk_show_once_abc"))
    }

    private val Redacted = "[redacted]"

    // ------------------------------------------------------ scope binding ---

    test("a token issued under alice:s1 is refused under bob:s1 and makes no call") {
        val reg = new ConfirmationRegistry()
        val token =
            str(parse(executor(username = "alice", scope = "alice:s1", registry = reg).execute("delete_user", obj("""{"username":"carol"}"""))), "token").get
        val confirmed = obj("""{"username":"carol"}""")
        confirmed.addProperty("confirmation_token", token)
        val rec = new Recorder()
        val r = parse(executor(username = "bob", scope = "bob:s1", registry = reg, rec = rec).execute("delete_user", confirmed))
        assert(str(r, "error").exists(_.nonEmpty), s"bob consumed alice's token: $r")
        assert(rec.calls.isEmpty, s"cross-user token reached the REST hop: ${rec.calls}")
    }

    // ------------------------------------------------------- secret forms ---

    private def secretRequest(r: JsonObject): (String, List[String]) = {
        assert(str(r, "status").contains("secret_request"), s"not a secret_request: $r")
        assert(!r.has("token"), s"form tool issued a token: $r")
        (str(r, "secretName").get, r.getAsJsonArray("fieldNames").asScala.map(_.getAsString).toList)
    }

    test("set_provider_credentials: secret_request for ai-keys with the provider's fields, no token, no call") {
        val expected = Map(
            "anthropic" -> List("anthropicApiKey"),
            "openai" -> List("openaiApiKey"),
            "grok" -> List("grokApiKey"),
            "azure" -> List("azureApiKey"),
            "bedrock" -> List("awsAccessKeyId", "awsSecretAccessKey", "awsRegion")
        )
        expected.foreach { case (provider, fields) =>
            val rec = new Recorder()
            val r = parse(executor(rec = rec).execute("set_provider_credentials", obj(s"""{"provider":"$provider"}""")))
            assert(secretRequest(r) == ("ai-keys", fields), s"$provider: $r")
            assert(r.has("reason"), s"$provider: no reason: $r")
            assert(rec.calls.isEmpty, s"$provider made REST calls: ${rec.calls}")
        }
    }

    test("set_provider_credentials(ollama) needs no credentials") {
        val rec = new Recorder()
        assert(parse(executor(rec = rec).execute("set_provider_credentials", obj("""{"provider":"ollama"}"""))) == obj("""{"error":"needs no credentials"}"""))
        assert(rec.calls.isEmpty)
    }

    test("create_repo_token: secret_request for the named secret with a token field, no token, no call") {
        val rec = new Recorder()
        val r = parse(executor(rec = rec).execute("create_repo_token", obj("""{"name":"gh-token"}""")))
        assert(secretRequest(r) == ("gh-token", List("token")))
        assert(rec.calls.isEmpty)
    }

    test("put_secret with an empty field value: secret_request with every field name, no token, no call") {
        val rec = new Recorder()
        val r = parse(executor(rec = rec).execute("put_secret", obj("""{"name":"db-creds","fields":{"user":"u","password":""},"type":"postgres"}""")))
        val (secretName, fields) = secretRequest(r)
        assert(secretName == "db-creds")
        assert(fields.sorted == List("password", "user"))
        assert(rec.calls.isEmpty)
    }

    test("AgentLoop.secretRequestOf parses only the secret_request envelope") {
        val env = """{"status":"secret_request","secretName":"ai-keys","fieldNames":["anthropicApiKey"],"reason":"Enter the key"}"""
        assert(AgentLoop.secretRequestOf("toolu_1", "set_provider_credentials", env) ==
            Some(AgentLoop.LoopEvent.SecretRequest("toolu_1", "ai-keys", List("anthropicApiKey"), "Enter the key")))
        assert(AgentLoop.secretRequestOf("toolu_1", "delete_user", """{"status":"needs_confirmation","summary":"x","token":"t"}""").isEmpty)
        assert(AgentLoop.secretRequestOf("toolu_1", "list_users", """{"secretName":"ai-keys","fieldNames":["x"]}""").isEmpty)
        assert(AgentLoop.secretRequestOf("toolu_1", "list_users", "plain text secret_request").isEmpty)
        assert(AgentLoop.confirmRequestOf("toolu_1", "set_provider_credentials", env).isEmpty)
    }

    // ---------------------------------------------------- loopback wrapping ---

    test("loopbackText: 2xx is the body; non-2xx JSON object is kept; anything else becomes HTTP <status>, clipped to 300 chars") {
        assert(ConfigToolExecutor.loopbackText(200, """[{"a":1}]""") == """[{"a":1}]""")
        assert(ConfigToolExecutor.loopbackText(201, "created") == "created")
        assert(parse(ConfigToolExecutor.loopbackText(404, """{"error":"not found"}""")) == obj("""{"error":"not found"}"""))
        assert(parse(ConfigToolExecutor.loopbackText(500, "<html>boom</html>")) == obj("""{"error":"HTTP 500: <html>boom</html>"}"""))
        val long = "x" * 1000
        val e = str(parse(ConfigToolExecutor.loopbackText(502, long)), "error").get
        assert(e.startsWith("HTTP 502: "))
        assert(e.stripPrefix("HTTP 502: ").length <= 301, s"not clipped: ${e.length}")
        assert(!e.contains("x" * 301))
    }

    // ------------------------------------------------------ confirm summary ---

    test("confirmation summaries lead with a per-tool phrase") {
        val d = str(parse(executor().execute("delete_user", obj("""{"username":"bob"}"""))), "summary").get
        assert(d.contains("Delete user bob"), d)
        val s = str(parse(executor().execute("set_ai_provider_slot", obj("""{"slot":"codegen","provider":"openai","model":"gpt-5.5"}"""))), "summary").get
        assert(s.contains("Point codegen at openai / gpt-5.5"), s)
    }

    // ------------------------------------------------------------ reads ---

    test("plain read tools GET their endpoint and return the server body verbatim") {
        val cases = Seq(
            ("list_secrets", "{}", "/api/v1/secrets"),
            ("get_secret_fields", """{"name":"my-secret"}""", "/api/v1/secrets/my-secret"),
            ("get_code_repo", "{}", "/api/v1/code-repo"),
            ("list_users", "{}", "/api/v1/auth/users"),
            ("list_api_keys", "{}", "/api/v1/keys"),
            ("list_key_templates", "{}", "/api/v1/keys/templates"),
            ("get_capability_catalog", "{}", "/api/v1/keys/capabilities/catalog"),
            ("get_agent_policy", "{}", "/api/v1/policy"),
            ("get_audit_facets", "{}", "/api/v1/audit-log/facets"),
            ("get_data_sources_fragment", "{}", "/api/v1/tap-prompts/data-sources")
        )
        cases.foreach { case (tool, in, path) =>
            val body = s"""{"from":"$tool"}"""
            val rec = new Recorder(answers = Map(("GET", path) -> body))
            val r = executor(rec = rec).execute(tool, obj(in))
            assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", path)), s"$tool calls: ${rec.calls}")
            assert(parse(r) == obj(body), s"$tool result: $r")
        }
        val rec = new Recorder()
        executor(rec = rec).execute("list_secrets", obj("""{"type":"repo_token"}"""))
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/secrets?type=repo_token")))
    }

    test("get_ai_providers: slots and credential flags, never an apiKey") {
        val slot =
            s"""{"provider":"anthropic","model":"claude-fable-5-1","endpoint":"https://api.anthropic.com/v1/messages","apiKey":"$Mask","version":"2023-06-01"}"""
        val keys = s"""{"anthropicApiKey":"$Mask","openaiApiKey":"","awsAccessKeyId":"$Mask"}"""
        val answers: Map[(String, String), String] =
            ConfigAgentTools.Slots.map(s => ("GET", "/api/v1/secrets/" + s) -> slot).toMap + (("GET", "/api/v1/secrets/ai-keys") -> keys)
        val rec = new Recorder(answers = answers)
        val r = parse(executor(rec = rec).execute("get_ai_providers", new JsonObject()))
        assert(rec.calls.forall(_._1 == "GET"))
        val paths = rec.calls.map(_._2).toSet
        (ConfigAgentTools.Slots.map("/api/v1/secrets/" + _) :+ "/api/v1/secrets/ai-keys").foreach(p => assert(paths.contains(p), s"no GET $p"))
        val ap = r.getAsJsonObject("slots").getAsJsonObject("ai-primary")
        assert(str(ap, "provider").contains("anthropic") && str(ap, "model").contains("claude-fable-5-1"))
        val c = r.getAsJsonObject("credentials")
        assert(c.get("anthropic").getAsBoolean)
        assert(!c.get("openai").getAsBoolean)
        assert(!c.get("azure").getAsBoolean)
        assert(!c.get("grok").getAsBoolean)
        assert(c.get("bedrock").getAsBoolean)
        assert(!r.toString.contains("apiKey"), s"apiKey copied into the answer: $r")
        assert(!r.toString.contains(Mask), s"masked key copied into the answer: $r")
    }

    test("test_code_repo_connection: GET code-repo then POST that document to code-repo/test") {
        val doc = """{"provider":"git","branch":"main"}"""
        val rec = new Recorder(answers = Map(("GET", "/api/v1/code-repo") -> doc))
        executor(rec = rec).execute("test_code_repo_connection", new JsonObject())
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/code-repo"), ("POST", "/api/v1/code-repo/test")))
        assert(bodyJson(rec.calls(1)) == JsonParser.parseString(doc))
    }

    private def queryOf(path: String): Map[String, String] =
        path.dropWhile(_ != '?').drop(1).split('&').filter(_.nonEmpty).map { kv =>
            val i = kv.indexOf('=')
            java.net.URLDecoder.decode(kv.take(i), "UTF-8") -> java.net.URLDecoder.decode(kv.drop(i + 1), "UTF-8")
        }.toMap

    test("query_audit_log: URL-encoded filters, limit default 50 and cap 200") {
        val rec = new Recorder()
        executor(rec = rec).execute("query_audit_log", obj("""{"category":"config & keys","actor":"session:admin"}"""))
        assert(rec.calls.size == 1 && rec.calls.head._1 == "GET")
        val p = rec.calls.head._2
        assert(p.startsWith("/api/v1/audit-log?"), p)
        assert(!p.contains(" "), s"not URL-encoded: $p")
        val q = queryOf(p)
        assert(q.get("category").contains("config & keys"), s"$q")
        assert(q.get("actor").contains("session:admin"))
        assert(q.get("limit").contains("50"))

        val rec2 = new Recorder()
        executor(rec = rec2).execute("query_audit_log", obj("""{"limit":500}"""))
        assert(queryOf(rec2.calls.head._2).get("limit").contains("200"), rec2.calls.head._2)
    }

    test("run_doctor: full mode, AI probes only when asked") {
        val rec = new Recorder()
        executor(rec = rec).execute("run_doctor", new JsonObject())
        assert(rec.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/doctor?mode=full")))
        val rec2 = new Recorder()
        executor(rec = rec2).execute("run_doctor", obj("""{"include_ai_probes":true}"""))
        assert(rec2.calls.map(c => (c._1, c._2)).toList == List(("GET", "/api/v1/doctor?mode=full&probes=ai")))
    }
}
