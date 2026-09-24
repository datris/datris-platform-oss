package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonParser, JsonPrimitive}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** Tool definitions for the Configuration chat.
  *
  * Datris-defined, not the MCP catalog: these tools map to the settings the
  * Configuration sub-tabs edit. Each definition is MCP-shaped
  * (`{name, description, inputSchema}`); AgentLoop renames `inputSchema` to
  * Anthropic's `input_schema` in `mcpToAnthropicTool`.
  *
  * Every mutating tool carries an optional `confirmation_token`. Called
  * without one, the executor performs nothing and answers
  * `needs_confirmation` (see [[ConfigToolExecutor]]). */
object ConfigAgentTools {

    private case class P(name: String, schema: JsonObject, required: Boolean)

    private def prop(tpe: String, description: String): JsonObject = {
        val o = new JsonObject()
        o.addProperty("type", tpe)
        o.addProperty("description", description)
        o
    }

    private def str(name: String, description: String, required: Boolean = false): P =
        P(name, prop("string", description), required)

    private def enumStr(name: String, description: String, values: Seq[String], required: Boolean = false): P = {
        val o = prop("string", description)
        val arr = new JsonArray()
        values.foreach(v => arr.add(v))
        o.add("enum", arr)
        P(name, o, required)
    }

    private def bool(name: String, description: String, required: Boolean = false): P =
        P(name, prop("boolean", description), required)

    private def int(name: String, description: String, required: Boolean = false): P =
        P(name, prop("integer", description), required)

    private def obj(name: String, description: String, required: Boolean = false): P =
        P(name, prop("object", description), required)

    private def strArray(name: String, description: String, required: Boolean = false): P = {
        val o = prop("array", description)
        val items = new JsonObject()
        items.addProperty("type", "string")
        o.add("items", items)
        P(name, o, required)
    }

    private val confirmationToken: P = str(
        ConfirmationRegistry.TokenKey,
        "One-shot token from this tool's earlier needs_confirmation answer. Pass it only after the user has confirmed, with the arguments unchanged."
    )

    private def tool(name: String, description: String, params: P*): JsonObject = {
        val schema = new JsonObject()
        schema.addProperty("type", "object")
        val props = new JsonObject()
        params.foreach(p => props.add(p.name, p.schema))
        schema.add("properties", props)
        val req = params.filter(_.required)
        if (req.nonEmpty) {
            val arr = new JsonArray()
            req.foreach(p => arr.add(p.name))
            schema.add("required", arr)
        }
        val t = new JsonObject()
        t.addProperty("name", name)
        t.addProperty("description", description)
        t.add("inputSchema", schema)
        t
    }

    private def mutating(name: String, description: String, params: P*): JsonObject =
        tool(name, description, (params :+ confirmationToken): _*)

    val Slots: Seq[String] = Seq("ai-primary", "ai-secondary", "codegen", "embedding")
    val Providers: Seq[String] = Seq("anthropic", "openai", "azure", "bedrock", "grok", "ollama")
    val Roles: Seq[String] = Seq("admin", "editor", "viewer")

    val readTools: List[JsonObject] = List(
        tool(
            "get_ai_providers",
            "Show the AI provider slots (primary, secondary, code generation, embedding) and which provider credentials are set. Credential values are masked."
        ),
        tool("list_secrets", "List stored secrets by name and type. Values are never returned.", str("type", "Only list secrets of this type.")),
        tool("get_secret_fields", "Show the field names of one secret. Values are masked.", str("name", "Secret name.", required = true)),
        tool("get_data_sources_fragment", "Show the data-sources prompt fragment and whether it is enabled."),
        tool("get_code_repo", "Show the code repository settings used to store generated scripts."),
        tool("test_code_repo_connection", "Test the connection to the configured code repository."),
        tool("list_users", "List users and their roles."),
        tool("list_api_keys", "List API keys by label with their capabilities. Key values are never returned."),
        tool("list_key_templates", "List the capability templates available when issuing an API key."),
        tool("get_capability_catalog", "List every capability an API key can be granted."),
        tool("get_agent_policy", "Show the agent policy: whether it is enabled, the current policy, the recommended policy and the known actions."),
        tool(
            "query_audit_log",
            "Search the audit log. All filters are optional.",
            str("since", "Start time, ISO-8601."),
            str("until", "End time, ISO-8601."),
            str("category", "Audit category."),
            str("action", "Audit action."),
            str("actor", "Actor name."),
            str("actorType", "Actor type."),
            str("outcome", "success, failure or denied."),
            str("resource", "Resource name."),
            int("limit", "Maximum number of entries to return.")
        ),
        tool("get_audit_facets", "List the values present in the audit log for each filter (categories, actions, actors, outcomes)."),
        tool(
            "run_doctor",
            "Run the platform health checks and report the results.",
            bool("include_ai_probes", "Also send a small test request to each configured AI provider.")
        )
    )

    val mutatingTools: List[JsonObject] = List(
        mutating(
            "set_ai_provider_slot",
            "Point an AI slot at a provider and model.",
            enumStr("slot", "Which AI slot to set.", Slots, required = true),
            enumStr("provider", "Provider for this slot.", Providers, required = true),
            str("model", "Model name."),
            str("endpoint", "Endpoint URL, for providers that need one."),
            bool("enabled", "Whether the slot is enabled."),
            int("max_uses", "Maximum web-search uses per request, where the provider supports it.")
        ),
        mutating(
            "set_provider_credentials",
            "Ask the user to enter a provider's credentials in a secure form. Credential values are never passed as arguments.",
            enumStr("provider", "Provider whose credentials to set.", Providers, required = true)
        ),
        mutating(
            "put_secret",
            "Create or replace a secret.",
            str("name", "Secret name.", required = true),
            obj("fields", "Field names and values of the secret.", required = true),
            str("type", "Secret type.")
        ),
        mutating("delete_secret", "Delete a secret.", str("name", "Secret name.", required = true)),
        mutating(
            "set_data_sources_fragment",
            "Replace the data-sources prompt fragment and turn it on or off.",
            str("content", "Fragment text.", required = true),
            bool("enabled", "Whether the fragment is included in prompts.", required = true)
        ),
        mutating(
            "set_code_repo",
            "Update the code repository settings used to store generated scripts.",
            obj("config", "Repository settings to save.", required = true)
        ),
        mutating(
            "create_repo_token",
            "Ask the user to enter a code repository access token in a secure form and store it under a name.",
            str("name", "Name to store the token under.", required = true)
        ),
        mutating("delete_repo_token", "Delete a stored code repository access token.", str("name", "Token name.", required = true)),
        mutating(
            "create_user",
            "Create a user with a role.",
            str("username", "New username.", required = true),
            enumStr("role", "Role for the user.", Roles, required = true),
            bool("generate_password", "Generate a temporary password for the user.")
        ),
        mutating("set_user_role", "Change a user's role.", str("username", "Username.", required = true), enumStr("role", "New role.", Roles, required = true)),
        mutating("reset_user_password", "Reset a user's password to a new temporary password.", str("username", "Username.", required = true)),
        mutating("delete_user", "Delete a user. The admin user cannot be deleted.", str("username", "Username.", required = true)),
        mutating(
            "issue_api_key",
            "Issue a new API key from a template or an explicit capability list.",
            str("label", "Label for the key.", required = true),
            str("template", "Capability template name."),
            strArray("capabilities", "Explicit capabilities, instead of a template.")
        ),
        mutating("rotate_api_key", "Replace an API key's value, keeping its label and capabilities.", str("label", "Key label.", required = true)),
        mutating("revoke_api_key", "Revoke an API key.", str("label", "Key label.", required = true)),
        mutating(
            "set_agent_policy",
            "Change the agent policy. Given fields are merged into the current policy.",
            obj("actions", "Per-action settings to change."),
            obj("overrides", "Per-agent overrides to change."),
            obj("limits", "Limits to change.")
        ),
        mutating("use_recommended_policy", "Replace the agent policy with the recommended policy.")
    )

    val readToolNames: Set[String] = readTools.map(_.get("name").getAsString).toSet
    val mutatingToolNames: Set[String] = mutatingTools.map(_.get("name").getAsString).toSet

    val UserTools: Set[String] = Set("list_users", "create_user", "set_user_role", "reset_user_password", "delete_user")
    val KeyTools: Set[String] = Set("list_api_keys", "issue_api_key", "rotate_api_key", "revoke_api_key", "list_key_templates", "get_capability_catalog")
    val ProviderEditTools: Set[String] = Set("set_ai_provider_slot", "set_provider_credentials")
}

/** Show only the tools whose Configuration sub-tab the UI would show. */
object ConfigToolFilter {

    /** The policy tools stay regardless of `policyEnabled`: the Agent Policy
      * sub-tab is shown even when the policy is off, and the executor returns
      * the server's own disabled error. The flag is accepted so the controller
      * passes every flag it reads and the call stays uniform. */
    def apply(tools: List[JsonObject], useUserAuth: Boolean, useApiKeys: Boolean, hostedOrTrial: Boolean, policyEnabled: Boolean): List[JsonObject] = {
        val drop: Set[String] =
            (if (!useUserAuth) ConfigAgentTools.UserTools else Set.empty[String]) ++
                (if (!useApiKeys) ConfigAgentTools.KeyTools else Set.empty[String]) ++
                (if (hostedOrTrial) ConfigAgentTools.ProviderEditTools else Set.empty[String])
        tools.filterNot(t => drop.contains(t.get("name").getAsString))
    }
}

/** Runs the Configuration chat's tools for one chat turn.
  *
  * Mutating tools require a one-shot confirmation token (see
  * [[ConfirmationRegistry]]); without one the call is not performed and the
  * answer is `{"status":"needs_confirmation","summary":…,"token":…}`, which
  * AgentLoop turns into a `confirm_request` SSE event.
  *
  * `execute` returns the text for the model: mutating-tool results pass
  * through [[redactForModel]] so show-once values never reach it. The full
  * text of the most recent call is kept in [[lastFullResult]]; the controller
  * puts that on the `tool_result` SSE event for the UI.
  *
  * `restCall(method, path, body)` is the loopback REST hop the tool bodies
  * will use. No tool body calls it yet. */
class ConfigToolExecutor(
    scope: String,
    username: String,
    uiKey: String,
    registry: ConfirmationRegistry,
    restCall: (String, String, Option[String]) => String = null
) {
    import ConfigToolExecutor._

    private val rest: (String, String, Option[String]) => String =
        if (restCall != null) restCall else (m: String, p: String, b: Option[String]) => loopback(m, p, b, uiKey, username)

    @volatile private var _lastFullResult: String = ""

    /** Unredacted result of the most recent `execute` call. */
    def lastFullResult: String = _lastFullResult

    def execute(name: String, input: JsonObject): String = {
        val in = if (input == null) new JsonObject() else input
        val full = run(name, in)
        _lastFullResult = full
        if (ConfigAgentTools.mutatingToolNames.contains(name)) redactForModel(full) else full
    }

    private def run(name: String, input: JsonObject): String = {
        if (ConfigAgentTools.readToolNames.contains(name)) readBody(name, input)
        else if (ConfigAgentTools.mutatingToolNames.contains(name)) {
            if (name == "delete_user" && stringArg(input, "username").exists(_.trim == "admin"))
                return error("The 'admin' user cannot be deleted")
            stringArg(input, ConfirmationRegistry.TokenKey).filter(_.nonEmpty) match {
                case None =>
                    val token = registry.issue(scope, name, input)
                    val o = new JsonObject()
                    o.addProperty("status", "needs_confirmation")
                    o.addProperty("summary", summarize(name, input))
                    o.addProperty("token", token)
                    o.toString
                case Some(token) =>
                    registry.consume(scope, name, input, token) match {
                        case Left(msg) => error(msg)
                        case Right(()) => mutatingBody(name, input)
                    }
            }
        } else error("unknown tool")
    }

    /** Read tool bodies. Stubs until Story 2 maps each to its REST call. */
    private def readBody(name: String, input: JsonObject): String = NotImplemented

    /** Mutating tool bodies, reached only after a confirmed token. Stubs until
      * Story 2. The policy tools will surface the server's disabled error when
      * the agent policy is off. */
    private def mutatingBody(name: String, input: JsonObject): String = NotImplemented

    /** Replace show-once values (`key`, `value`, `temporaryPassword`,
      * `token`) anywhere in a JSON result with "[redacted]". The
      * needs_confirmation envelope is returned as is: its token is what the
      * model passes back. Non-JSON text is returned unchanged. */
    def redactForModel(result: String): String = {
        if (result == null) return result
        val el =
            try JsonParser.parseString(result)
            catch { case _: Exception => return result }
        if (el == null || !(el.isJsonObject || el.isJsonArray)) return result
        if (el.isJsonObject) {
            val o = el.getAsJsonObject
            if (o.has("status") && o.get("status").isJsonPrimitive && o.get("status").getAsString == "needs_confirmation")
                return result
        }
        redact(el)
        el.toString
    }

    private def redact(el: JsonElement): Unit =
        if (el.isJsonObject) {
            val o = el.getAsJsonObject
            o.entrySet().asScala.toList.foreach { e =>
                if (RedactedKeys.contains(e.getKey)) o.add(e.getKey, new JsonPrimitive(Redacted))
                else redact(e.getValue)
            }
        } else if (el.isJsonArray) el.getAsJsonArray.asScala.foreach(redact)

    /** One sentence naming the tool and its non-secret arguments. Object
      * arguments show their field names only; sensitive keys are omitted. */
    private def summarize(name: String, input: JsonObject): String = {
        val parts = input.entrySet().asScala.toList.flatMap { e =>
            val k = e.getKey
            val v = e.getValue
            if (k == ConfirmationRegistry.TokenKey || SensitiveArgKeys.contains(k.toLowerCase) || v == null || v.isJsonNull) None
            else if (v.isJsonPrimitive) Some(k + "=" + clip(v.getAsString))
            else if (v.isJsonObject) Some(k + " (" + v.getAsJsonObject.keySet().asScala.mkString(", ") + ")")
            else if (v.isJsonArray) {
                val items = v.getAsJsonArray.asScala.toList
                if (items.forall(_.isJsonPrimitive)) Some(k + "=[" + items.map(i => clip(i.getAsString)).mkString(", ") + "]")
                else Some(k + " (" + items.size + " items)")
            } else None
        }
        if (parts.isEmpty) "Run " + name + "."
        else "Run " + name + " with " + parts.mkString(", ") + "."
    }

    private def clip(s: String): String = if (s.length <= 80) s else s.take(80) + "…"
}

object ConfigToolExecutor {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val ViaConfigChat = "config-chat"
    val NotImplemented = """{"error":"not implemented"}"""
    val Redacted = "[redacted]"
    val RedactedKeys: Set[String] = Set("key", "value", "temporaryPassword", "token")

    /** Argument names never echoed into a confirmation summary. */
    private val SensitiveArgKeys: Set[String] = Set("password", "secret", "key", "value", "token", "apikey", "temporarypassword")

    private def stringArg(o: JsonObject, k: String): Option[String] =
        if (o.has(k) && o.get(k).isJsonPrimitive) Some(o.get(k).getAsString) else None

    private def error(msg: String): String = {
        val o = new JsonObject()
        o.addProperty("error", msg)
        o.toString
    }

    /** One direct platform call through the normal interceptor chain, on
      * behalf of the admin whose chat this is (copied from IncidentRunner). */
    private def loopback(method: String, path: String, body: Option[String], apiKey: String, onBehalfOf: String): String = {
        val url = "http://127.0.0.1:" + ai.datris.policy.PolicyReplay.port + path
        def withBody(r: org.apache.http.client.methods.HttpEntityEnclosingRequestBase): org.apache.http.client.methods.HttpRequestBase = {
            body.foreach { b =>
                r.setEntity(new org.apache.http.entity.StringEntity(b, java.nio.charset.StandardCharsets.UTF_8))
                r.setHeader("Content-Type", "application/json")
            }
            r
        }
        val req: org.apache.http.client.methods.HttpRequestBase = method match {
            case "GET" => new org.apache.http.client.methods.HttpGet(url)
            case "DELETE" => new org.apache.http.client.methods.HttpDelete(url)
            case "PUT" => withBody(new org.apache.http.client.methods.HttpPut(url))
            case "PATCH" => withBody(new org.apache.http.client.methods.HttpPatch(url))
            case _ => withBody(new org.apache.http.client.methods.HttpPost(url))
        }
        if (apiKey != null && apiKey.nonEmpty) req.setHeader("x-api-key", apiKey)
        if (onBehalfOf != null && onBehalfOf.nonEmpty) req.setHeader(AuditActor.HeaderOnBehalfOf, onBehalfOf)
        req.setHeader(AuditActor.HeaderVia, ViaConfigChat)
        val config = org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(10 * 60 * 1000).build()
        val client = org.apache.http.impl.client.HttpClients.custom().setDefaultRequestConfig(config).build()
        try {
            val resp = client.execute(req)
            Option(resp.getEntity).map(e => org.apache.http.util.EntityUtils.toString(e, java.nio.charset.StandardCharsets.UTF_8)).getOrElse("")
        } catch {
            case e: Exception =>
                logger.warn("Configuration chat loopback call failed: " + method + " " + path + ": " + e.getMessage)
                throw e
        } finally {
            try client.close()
            catch { case _: Exception => }
        }
    }
}
