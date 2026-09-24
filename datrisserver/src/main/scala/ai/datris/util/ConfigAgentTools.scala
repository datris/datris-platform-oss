package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import com.google.gson.{JsonArray, JsonElement, JsonNull, JsonObject, JsonParser, JsonPrimitive}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** Tool definitions for the Configuration chat.
  *
  * Datris-defined, not the MCP catalog: these tools map to the settings the
  * Configuration sub-tabs edit. Each definition is MCP-shaped
  * (`{name, description, inputSchema}`); AgentLoop renames `inputSchema` to
  * Anthropic's `input_schema` in `mcpToAnthropicTool`.
  *
  * Every mutating tool except the [[FormTools]] carries an optional
  * `confirmation_token`. Called without one, the executor performs nothing
  * and answers `needs_confirmation` (see [[ConfigToolExecutor]]). */
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

    val Slots: Seq[String] = Seq("ai-primary", "codegen", "embedding", "web-search")
    val Providers: Seq[String] = Seq("anthropic", "openai", "azure", "bedrock", "grok", "ollama")
    val Roles: Seq[String] = Seq("admin", "editor", "viewer")

    val readTools: List[JsonObject] = List(
        tool(
            "get_ai_providers",
            "Show the AI provider slots (primary, code generation, embedding, web search) and which provider credentials are set. Credential values are masked."
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
        tool(
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
        tool(
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

    /** Mutating tools that only open the inline secret form: they perform
      * nothing server-side, so they carry no `confirmation_token` and issue
      * none. The submitted form is the confirmation. */
    val FormTools: Set[String] = Set("set_provider_credentials", "create_repo_token")
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
  * AgentLoop turns into a `confirm_request` SSE event. A confirmed mutation
  * makes one loopback REST call as the signed-in admin (the policy tools and
  * `issue_api_key` with a template read first, then write once).
  *
  * The [[ConfigAgentTools.FormTools]], and `put_secret` with an empty field
  * value, answer `{"status":"secret_request",…}` instead: the UI shows the
  * inline secret form and the user's submission is the confirmation.
  *
  * `execute` returns the text for the model: mutating-tool results pass
  * through [[redactForModel]] so show-once values never reach it. The full
  * text of the most recent call is kept in [[lastFullResult]]; the controller
  * puts that on the `tool_result` SSE event for the UI.
  *
  * `restCall(method, path, body)` is the loopback REST hop; it answers the
  * server body on 2xx and an `{"error":…}` object otherwise (see
  * [[ConfigToolExecutor.loopbackText]]). */
class ConfigToolExecutor(
    scope: String,
    username: String,
    uiKey: String,
    registry: ConfirmationRegistry,
    restCall: (String, String, Option[String]) => String = null
) {
    import ConfigToolExecutor._

    private val rest: (String, String, Option[String]) => String =
        if (restCall != null) restCall
        else
            (m: String, p: String, b: Option[String]) => {
                val (status, body) = loopback(m, p, b, uiKey, username)
                loopbackText(status, body)
            }

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
            if (name == "delete_user" && stringArg(input, "username").exists(_.trim.toLowerCase == "admin"))
                return error("The 'admin' user cannot be deleted")
            secretForm(name, input) match {
                case Some(answer) => return answer
                case None =>
            }
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

    // ------------------------------------------------------------ secret forms ---

    /** The inline secret form for the form tools and for `put_secret` with an
      * empty field value; None for every other call. */
    private def secretForm(name: String, input: JsonObject): Option[String] = name match {
        case "set_provider_credentials" =>
            val provider = stringArg(input, "provider").map(_.trim.toLowerCase).getOrElse("")
            Some(ProviderCredentialFields.get(provider) match {
                case Some(Nil) => error("needs no credentials")
                case Some(fields) =>
                    secretRequest("ai-keys", fields, "Enter the " + provider + " credentials. They are stored in the shared AI provider key store.")
                case None => error("unknown provider: " + provider)
            })
        case "create_repo_token" =>
            Some(stringArg(input, "name").map(_.trim).filter(_.nonEmpty) match {
                case Some(n) => secretRequest(n, List("token"), "Enter the code repository access token to store as " + n + ".")
                case None => error("name is required")
            })
        case "put_secret" =>
            val fields = objArg(input, "fields")
            val hasEmpty = fields.exists(_.entrySet().asScala.exists { e =>
                e.getValue.isJsonPrimitive && e.getValue.getAsString.isEmpty
            })
            val secretName = stringArg(input, "name").map(_.trim).filter(_.nonEmpty)
            if (hasEmpty && secretName.isDefined)
                Some(secretRequest(secretName.get, fields.get.keySet().asScala.toList, "Enter the values for secret " + secretName.get + "."))
            else None
        case _ => None
    }

    private def secretRequest(secretName: String, fieldNames: List[String], reason: String): String = {
        val o = new JsonObject()
        o.addProperty("status", "secret_request")
        o.addProperty("secretName", secretName)
        val arr = new JsonArray()
        fieldNames.foreach(f => arr.add(f))
        o.add("fieldNames", arr)
        o.addProperty("reason", reason)
        o.toString
    }

    // ------------------------------------------------------------------ reads ---

    private def get(path: String): String = rest("GET", path, None)

    private def readBody(name: String, input: JsonObject): String = name match {
        case "get_ai_providers" => aiProviders()
        case "list_secrets" =>
            get(Api + "/secrets" + stringArg(input, "type").map(_.trim).filter(_.nonEmpty).map(t => "?type=" + query(t)).getOrElse(""))
        case "get_secret_fields" => withArg(input, "name")(n => get(Api + "/secrets/" + seg(n)))
        case "get_data_sources_fragment" =>
            val r = get(Api + "/tap-prompts/" + DataSourcesKey)
            if (isNotFound(r)) {
                val o = new JsonObject()
                o.addProperty("key", DataSourcesKey)
                o.addProperty("content", "")
                o.addProperty("enabled", false)
                o.addProperty("exists", false)
                o.toString
            } else r
        case "get_code_repo" => get(Api + "/code-repo")
        case "test_code_repo_connection" =>
            val doc = get(Api + "/code-repo")
            parseObj(doc) match {
                case Some(o) if !o.has("error") => rest("POST", Api + "/code-repo/test", Some(o.toString))
                case Some(_) => doc
                case None => error("could not read the code repository settings")
            }
        case "list_users" => get(Api + "/auth/users")
        case "list_api_keys" => get(Api + "/keys")
        case "list_key_templates" => get(Api + "/keys/templates")
        case "get_capability_catalog" => get(Api + "/keys/capabilities/catalog")
        case "get_agent_policy" => get(Api + "/policy")
        case "get_audit_facets" => get(Api + "/audit-log/facets")
        case "query_audit_log" =>
            val filters = AuditFilters.flatMap(k => stringArg(input, k).map(_.trim).filter(_.nonEmpty).map(v => k + "=" + query(v)))
            val limit = intArg(input, "limit").map(l => math.max(1, math.min(l, AuditLimitCap))).getOrElse(AuditLimitDefault)
            get(Api + "/audit-log?" + (filters :+ ("limit=" + limit)).mkString("&"))
        case "run_doctor" =>
            val probes = boolArg(input, "include_ai_probes").contains(true)
            get(Api + "/doctor?mode=full" + (if (probes) "&probes=ai" else ""))
        case _ => error("unknown tool")
    }

    /** The four slots (provider, model, endpoint and the web-search settings;
      * never an apiKey) and which provider credentials are stored. */
    private def aiProviders(): String = {
        val slots = new JsonObject()
        ConfigAgentTools.Slots.foreach { s =>
            secretFields(get(Api + "/secrets/" + s)) match {
                case Some(f) =>
                    val v = new JsonObject()
                    SlotViewKeys.foreach(k => if (f.has(k) && f.get(k).isJsonPrimitive) v.add(k, f.get(k)))
                    slots.add(s, v)
                case None => slots.add(s, JsonNull.INSTANCE)
            }
        }
        val keys = secretFields(get(Api + "/secrets/ai-keys")).getOrElse(new JsonObject())
        val credentials = new JsonObject()
        CredentialFlagFields.foreach { case (provider, field) =>
            credentials.addProperty(provider, keys.has(field) && keys.get(field).isJsonPrimitive && keys.get(field).getAsString.nonEmpty)
        }
        val o = new JsonObject()
        o.add("slots", slots)
        o.add("credentials", credentials)
        o.toString
    }

    /** A secret's field map from `GET /secrets/{name}` (`{"name","fields"}`,
      * or a flat field map); None when the secret is absent or unreadable. */
    private def secretFields(raw: String): Option[JsonObject] =
        parseObj(raw).filterNot(_.has("error")).map { o =>
            if (o.has("fields") && o.get("fields").isJsonObject) o.getAsJsonObject("fields") else o
        }

    // --------------------------------------------------------------- mutations ---

    /** Mutating tool bodies, reached only after a confirmed token. */
    private def mutatingBody(name: String, input: JsonObject): String = name match {
        case "set_ai_provider_slot" =>
            val slot = stringArg(input, "slot").map(_.trim).getOrElse("")
            if (!ConfigAgentTools.Slots.contains(slot)) return error("slot must be one of " + ConfigAgentTools.Slots.mkString(", "))
            val provider = stringArg(input, "provider").map(_.trim.toLowerCase).getOrElse("")
            if (!ConfigAgentTools.Providers.contains(provider))
                return error("provider must be one of " + ConfigAgentTools.Providers.mkString(", "))
            AiProviderSlotBody.build(
                slot,
                provider,
                stringArg(input, "model"),
                stringArg(input, "endpoint"),
                boolArg(input, "enabled"),
                intArg(input, "max_uses")
            ) match {
                case Left(msg) => error(msg)
                case Right(body) => rest("PUT", Api + "/secrets/" + seg(slot), Some(body.toString))
            }
        case "put_secret" =>
            withArg(input, "name") { n =>
                objArg(input, "fields") match {
                    case None => error("fields is required")
                    case Some(fields) =>
                        val body = fields.deepCopy()
                        stringArg(input, "type").map(_.trim).filter(_.nonEmpty).foreach(t => body.addProperty("_type", t))
                        rest("PUT", Api + "/secrets/" + seg(n), Some(body.toString))
                }
            }
        case "delete_secret" | "delete_repo_token" => withArg(input, "name")(n => rest("DELETE", Api + "/secrets/" + seg(n), None))
        case "set_data_sources_fragment" =>
            val body = new JsonObject()
            body.addProperty("key", DataSourcesKey)
            body.add("aliases", new JsonArray())
            body.addProperty("content", stringArg(input, "content").getOrElse(""))
            body.addProperty("enabled", boolArg(input, "enabled").getOrElse(false))
            rest("POST", Api + "/tap-prompts", Some(body.toString))
        case "set_code_repo" =>
            objArg(input, "config") match {
                case Some(c) => rest("PUT", Api + "/code-repo", Some(c.toString))
                case None => error("config is required")
            }
        case "create_user" =>
            withArg(input, "username") { u =>
                val body = new JsonObject()
                body.addProperty("username", u)
                body.addProperty("role", stringArg(input, "role").getOrElse(""))
                rest("POST", Api + "/auth/users", Some(body.toString))
            }
        case "set_user_role" =>
            withArg(input, "username") { u =>
                val body = new JsonObject()
                body.addProperty("role", stringArg(input, "role").getOrElse(""))
                rest("PATCH", Api + "/auth/users/" + seg(u), Some(body.toString))
            }
        case "reset_user_password" =>
            withArg(input, "username") { u =>
                val body = new JsonObject()
                body.addProperty("resetPassword", true)
                rest("PATCH", Api + "/auth/users/" + seg(u), Some(body.toString))
            }
        case "delete_user" => withArg(input, "username")(u => rest("DELETE", Api + "/auth/users/" + seg(u), None))
        case "issue_api_key" => withArg(input, "label")(issueApiKey(_, input))
        case "rotate_api_key" => withArg(input, "label")(l => rest("POST", Api + "/keys/" + seg(l) + "/rotate", None))
        case "revoke_api_key" => withArg(input, "label")(l => rest("DELETE", Api + "/keys/" + seg(l), None))
        case "set_agent_policy" => withPolicy(doc => mergePolicy(doc, input))
        case "use_recommended_policy" =>
            withPolicy { doc =>
                if (doc.has("recommended") && doc.get("recommended").isJsonObject) Right(doc.getAsJsonObject("recommended"))
                else Left("the policy document has no recommended policy")
            }
        case _ => error("unknown tool")
    }

    private def issueApiKey(label: String, input: JsonObject): String = {
        val template = stringArg(input, "template").map(_.trim).filter(_.nonEmpty)
        val capabilities: Either[String, JsonArray] = template match {
            case Some(t) =>
                val raw = get(Api + "/keys/templates")
                parseObj(raw) match {
                    case Some(o) if o.has("error") => return raw
                    case Some(o) if o.has("templates") && o.get("templates").isJsonArray =>
                        o.getAsJsonArray("templates").asScala.collectFirst {
                            case el if el.isJsonObject && stringArg(el.getAsJsonObject, "name").contains(t) =>
                                val tpl = el.getAsJsonObject
                                if (tpl.has("capabilities") && tpl.get("capabilities").isJsonArray) tpl.getAsJsonArray("capabilities")
                                else new JsonArray()
                        } match {
                            case Some(caps) => Right(caps)
                            case None => Left("unknown key template: " + t)
                        }
                    case _ => Left("could not read the key templates")
                }
            case None =>
                if (input.has("capabilities") && input.get("capabilities").isJsonArray) Right(input.getAsJsonArray("capabilities"))
                else Left("give a template or a capabilities list")
        }
        capabilities match {
            case Left(msg) => error(msg)
            case Right(caps) =>
                val body = new JsonObject()
                body.addProperty("label", label)
                body.add("capabilities", caps.deepCopy())
                rest("POST", Api + "/keys", Some(body.toString))
        }
    }

    /** GET the policy document, build the PUT body from it, PUT it. A read
      * error (or the server's disabled answer) is returned as is. */
    private def withPolicy(build: JsonObject => Either[String, JsonObject]): String = {
        val raw = get(Api + "/policy")
        parseObj(raw) match {
            case Some(o) if o.has("error") => raw
            case Some(o) =>
                build(o) match {
                    case Left(msg) => error(msg)
                    case Right(body) => rest("PUT", Api + "/policy", Some(body.toString))
                }
            case None => error("could not read the agent policy")
        }
    }

    /** `actions` and `limits` merge key by key; each `overrides` entry replaces
      * that resource's map (JSON null removes it). Every other key of the
      * current policy (recovery, recovery overrides) is kept. */
    private def mergePolicy(doc: JsonObject, input: JsonObject): Either[String, JsonObject] = {
        if (!doc.has("policy") || !doc.get("policy").isJsonObject) return Left("the policy document has no current policy")
        val policy = doc.getAsJsonObject("policy").deepCopy()
        def section(k: String): JsonObject = {
            if (!policy.has(k) || !policy.get(k).isJsonObject) policy.add(k, new JsonObject())
            policy.getAsJsonObject(k)
        }
        Seq("actions", "limits").foreach { k =>
            objArg(input, k).foreach { changes =>
                val target = section(k)
                changes.entrySet().asScala.foreach(e => target.add(e.getKey, e.getValue.deepCopy()))
            }
        }
        objArg(input, "overrides").foreach { changes =>
            val target = section("overrides")
            changes.entrySet().asScala.foreach { e =>
                if (e.getValue == null || e.getValue.isJsonNull) target.remove(e.getKey)
                else {
                    val replacement = e.getValue.deepCopy()
                    // Keep the resource's recovery override unless the new map
                    // names one: a PUT carrying any recovery override replaces
                    // them all (PolicyAPIController), so dropping it here would
                    // reset this resource's recovery mode.
                    if (
                        replacement.isJsonObject && !replacement.getAsJsonObject.has(RecoveryKey) && target.has(e.getKey) &&
                        target.get(e.getKey).isJsonObject
                    ) {
                        val old = target.getAsJsonObject(e.getKey)
                        if (old.has(RecoveryKey) && old.get(RecoveryKey).isJsonPrimitive)
                            replacement.getAsJsonObject.add(RecoveryKey, old.get(RecoveryKey).deepCopy())
                    }
                    target.add(e.getKey, replacement)
                }
            }
        }
        Right(policy)
    }

    // ---------------------------------------------------------------- redaction ---

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

    // ---------------------------------------------------------------- summaries ---

    /** A per-tool phrase, then the tool's non-secret arguments. Object
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
        val args = if (parts.isEmpty) "" else " with " + parts.mkString(", ")
        describe(name, input) match {
            case Some(phrase) => phrase + (if (parts.isEmpty) "" else " (" + name + args + ")") + "."
            case None => "Run " + name + args + "."
        }
    }

    /** A short phrase naming what a mutating tool will do; None falls back to
      * `Run <tool> with …`. */
    private def describe(name: String, input: JsonObject): Option[String] = {
        def a(k: String): String = stringArg(input, k).map(s => clip(s.trim)).getOrElse("")
        name match {
            case "set_ai_provider_slot" =>
                val model = a("model")
                Some("Point " + a("slot") + " at " + a("provider") + (if (model.nonEmpty) " / " + model else ""))
            case "put_secret" => Some("Save secret " + a("name"))
            case "delete_secret" => Some("Delete secret " + a("name"))
            case "delete_repo_token" => Some("Delete repository token " + a("name"))
            case "set_data_sources_fragment" =>
                Some("Replace the data-sources prompt fragment (" + (if (boolArg(input, "enabled").contains(true)) "enabled" else "disabled") + ")")
            case "set_code_repo" => Some("Update the code repository settings")
            case "create_user" => Some("Create user " + a("username") + " as " + a("role"))
            case "set_user_role" => Some("Change user " + a("username") + " to " + a("role"))
            case "reset_user_password" => Some("Reset the password of user " + a("username"))
            case "delete_user" => Some("Delete user " + a("username"))
            case "issue_api_key" =>
                val t = a("template")
                Some("Issue API key " + a("label") + (if (t.nonEmpty) " from template " + t else ""))
            case "rotate_api_key" => Some("Rotate API key " + a("label"))
            case "revoke_api_key" => Some("Revoke API key " + a("label"))
            case "set_agent_policy" => Some("Change the agent policy")
            case "use_recommended_policy" => Some("Replace the agent policy with the recommended policy")
            case _ => None
        }
    }

    private def clip(s: String): String = if (s.length <= 80) s else s.take(80) + "…"
}

object ConfigToolExecutor {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val ViaConfigChat = "config-chat"
    val Redacted = "[redacted]"
    val RedactedKeys: Set[String] = Set("key", "value", "temporaryPassword", "token")

    private val Api = "/api/v1"
    private val DataSourcesKey = "data-sources"
    private val RecoveryKey = "recovery"
    private val AuditFilters: Seq[String] = Seq("since", "until", "category", "action", "actor", "actorType", "outcome", "resource")
    private val AuditLimitDefault = 50
    private val AuditLimitCap = 200
    private val ErrorClip = 300

    /** Slot fields `get_ai_providers` passes through. Never `apiKey`. */
    private val SlotViewKeys: Seq[String] = Seq("provider", "model", "endpoint", "enabled", "maxUses", "version")

    /** Stored `ai-keys` field whose presence means the provider's credentials
      * are set (AIProviders.providerKeyFromStore; bedrock: the access key id). */
    private val CredentialFlagFields: Seq[(String, String)] = Seq(
        "anthropic" -> "anthropicApiKey",
        "openai" -> "openaiApiKey",
        "azure" -> "azureApiKey",
        "grok" -> "grokApiKey",
        "bedrock" -> "awsAccessKeyId"
    )

    /** `set_provider_credentials` form fields in the shared `ai-keys` store;
      * an empty list means the provider needs none. */
    private val ProviderCredentialFields: Map[String, List[String]] = Map(
        "anthropic" -> List("anthropicApiKey"),
        "openai" -> List("openaiApiKey"),
        "grok" -> List("grokApiKey"),
        "azure" -> List("azureApiKey"),
        "bedrock" -> List("awsAccessKeyId", "awsSecretAccessKey", "awsRegion"),
        "ollama" -> Nil
    )

    /** Argument names never echoed into a confirmation summary. */
    private val SensitiveArgKeys: Set[String] = Set("password", "secret", "key", "value", "token", "apikey", "temporarypassword")

    private def stringArg(o: JsonObject, k: String): Option[String] =
        if (o.has(k) && o.get(k).isJsonPrimitive) Some(o.get(k).getAsString) else None

    private def objArg(o: JsonObject, k: String): Option[JsonObject] =
        if (o.has(k) && o.get(k).isJsonObject) Some(o.getAsJsonObject(k)) else None

    private def boolArg(o: JsonObject, k: String): Option[Boolean] =
        stringArg(o, k).map(_.trim.toLowerCase).collect { case "true" => true; case "false" => false }

    private def intArg(o: JsonObject, k: String): Option[Int] =
        stringArg(o, k).flatMap(s => scala.util.Try(BigDecimal(s.trim).toInt).toOption)

    /** Run `f` with a required non-empty string argument, else an error. */
    private def withArg(o: JsonObject, k: String)(f: String => String): String =
        stringArg(o, k).map(_.trim).filter(_.nonEmpty) match {
            case Some(v) => f(v)
            case None => error(k + " is required")
        }

    private def parseObj(raw: String): Option[JsonObject] =
        try {
            val el = JsonParser.parseString(raw)
            if (el != null && el.isJsonObject) Some(el.getAsJsonObject) else None
        } catch { case _: Exception => None }

    /** The server's 404 for a missing secret or fragment (`… not found: …`). */
    private def isNotFound(raw: String): Boolean =
        parseObj(raw).exists(o => stringArg(o, "error").exists(_.toLowerCase.contains("not found")))

    /** One URL path segment. */
    private def seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** One URL query value. */
    private def query(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private def error(msg: String): String = {
        val o = new JsonObject()
        o.addProperty("error", msg)
        o.toString
    }

    /** The rest seam's text for one loopback answer: a 2xx body as is; a
      * non-2xx JSON object as is (the server's own `{"error":…}`); anything
      * else `{"error":"HTTP <status>: <body clipped to 300 chars>"}`. */
    private[util] def loopbackText(status: Int, body: String): String = {
        val b = Option(body).getOrElse("")
        if (status >= 200 && status < 300) b
        else if (parseObj(b).isDefined) b
        else {
            val t = b.trim
            error("HTTP " + status + ": " + (if (t.length <= ErrorClip) t else t.take(ErrorClip) + "…"))
        }
    }

    /** One direct platform call through the normal interceptor chain, on
      * behalf of the admin whose chat this is (copied from IncidentRunner).
      * Returns the HTTP status and the response body. */
    private def loopback(method: String, path: String, body: Option[String], apiKey: String, onBehalfOf: String): (Int, String) = {
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
            val text = Option(resp.getEntity).map(e => org.apache.http.util.EntityUtils.toString(e, java.nio.charset.StandardCharsets.UTF_8)).getOrElse("")
            (resp.getStatusLine.getStatusCode, text)
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
