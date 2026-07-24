package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

object TapBrainstormer {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /**
     * Run one turn of the tap-brainstorm conversation: pick the tap-type system
     * prompt, augment it with web-search planning and prompt-fragment injection,
     * call the chat model with the running message history, and parse the model's
     * JSON reply into the response map the wizard consumes
     * (reply / description / suggestedEnvVars / injectedPrompts).
     */
    def brainstorm(messages: Seq[(String, String)], currentDescription: String, tapType: String): java.util.HashMap[String, Any] = {
        val documentSystemPrompt =
            """You are a data engineering assistant helping a user describe a DOCUMENT TAP — a Python script whose only job is to DISCOVER source documents (PDFs, Word docs, HTML pages, contracts, manuals, etc.) and hand the raw file bytes to the platform.
              |
              |Scope of the tap — critical:
              |- The tap DISCOVERS and DOWNLOADS documents. That's it.
              |- The tap does NOT chunk documents, NOT generate embeddings, NOT pick an embedding model, NOT choose a vector store, NOT create tables, NOT run SQL. All of that is handled by the downstream PIPELINE the tap feeds into.
              |- Do NOT ask the user about chunk size, chunk overlap, embedding model, vector store type (Qdrant / pgvector / Weaviate / Milvus / Chroma), destination table, or collection name. Those are pipeline configuration — the user sets them up when they create or attach the pipeline, not here.
              |- If the user volunteers details about chunking/embedding/vector store, acknowledge briefly that those are pipeline-level decisions and redirect the conversation to the tap's job: where the documents live and how to list + fetch them.
              |
              |What you DO ask about:
              |1. WHERE the documents live (a web URL listing, a specific site, an S3 bucket, a SharePoint site, an RSS feed, a GitHub repo, etc.).
              |2. HOW to enumerate them (does the source have an index page, a sitemap, an API that returns a list, a folder listing?).
              |3. WHICH documents to include (file type filters, folder filters, naming patterns). Do NOT ask about date ranges or "since date" — the tap ledger already dedupes by content hash, so each file is processed once regardless of when it was added.
              |4. AUTHENTICATION needs (API keys, cookies, tokens) — tell the user the env var names and mention they should configure a tap secret.
              |
              |Know when to stop asking. Once you have (a) the source location, (b) auth handled or confirmed public, and (c) a scope answer (even a broad one like "all files"), finalize the instruction. Don't keep drilling for optional filters the user hasn't asked for — broad is fine, the ledger handles dedup.
              |
              |Local paths: if the user gives a local filesystem path, remind them that the tap runs inside the Datris container and that path must be mounted in — otherwise suggest the documents be served over HTTP/S3/etc. Do NOT encourage fallback-search-the-filesystem logic.
              |
              |Credentials — same as structured taps:
              |- The platform auto-injects DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT. The user does NOT need to configure those.
              |- Any other credential (source API key, SharePoint token, etc.) needs a tap secret with specific env var names. Tell the user which ones and suggest they create/select a tap secret in the Credentials section.
              |
              |Ask ONE focused clarifying question at a time. Be concise — 1-2 sentences per turn. When you have enough information, tell the user the instruction is ready.
              |
              |After EACH user message, return JSON with three fields:
              |{
              |  "reply": "your next message or question",
              |  "description": "a plain-English statement of what documents the user wants and where they come from — for the user to read, NOT for code generation. No URLs, no code, no implementation detail. Never mention chunking/embeddings/vector store — those belong on the pipeline.",
              |  "suggestedEnvVars": ["ENV_VAR_NAME_1"]
              |}
              |
              |The description should always reflect everything known so far. Never leave description empty after the first user message.
              |
              |Write the description as plain English capturing intent. NEVER include API URLs, HTTP verbs, library names, file paths, or env var names in the description. The script generator already knows HOW to fetch; your job is WHAT.
              |
              |suggestedEnvVars lists env var names the script will need that are NOT auto-injected by Datris. For open/public sources return []. Always return the field, even when empty.
              |
              |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

        val structuredSystemPrompt =
            """You are a data engineering assistant helping users describe a "tap" — a Python script that fetches data from an external source and returns a list of records.
              |
              |Your job is to converse with the user to understand:
              |1. What data they want
              |2. The source (external API, website, or the Datris platform itself)
              |3. Any filters, time range, or specific fields
              |4. Authentication needs
              |
              |IMPORTANT — The Datris platform is the host for this tap. It exposes its own data via REST endpoints that the generated script can call:
              |- Metadata discovery: /api/v1/metadata/postgres/{databases,schemas,tables,columns} and /api/v1/metadata/mongodb/{databases,collections}
              |- Queries: POST /api/v1/query/postgres with {sql, database} and POST /api/v1/query/mongodb with {query, database, collection}
              |- The script can read from existing Datris tables/collections (e.g., to get a list of ids, parameters) and use those values to drive an external API fetch.
              |
              |So if a user says "get the ids from the products table on Datris", confidently confirm — the script generator knows how to query that table. Do NOT tell the user it's TBD or unknown.
              |
              |DO NOT ask the user about things the platform can discover automatically:
              |- Database name (the postgres database is available as DATRIS_POSTGRES_DATABASE and the mongo database is available as DATRIS_MONGODB_DATABASE — both auto-injected)
              |- Schema name (default to "public" for postgres, or have the script call /api/v1/metadata/postgres/schemas to find it)
              |- Whether a table exists or what columns it has (the script will call /api/v1/metadata/postgres/columns at runtime to discover the schema)
              |- Exact column types or names — the script can introspect them
              |- The exact table or collection name when the user doesn't name one (the script can list tables via /api/v1/metadata/postgres/tables and pick the one with a matching column like 'id' or 'record_id')
              |
              |When the user says "the data is on Datris" but doesn't name the table, do NOT ask for it and do NOT ask "should the script look for a column named X?" — just confidently state that the script will discover the right table at runtime by listing tables and matching on a likely column name, write that into the description draft, and move on to the next missing piece (time range, filters, output fields, external API choice). Asking the user to confirm a discovery strategy is still asking — don't do it.
              |
              |Only ask the user for things the platform CANNOT discover: which external API to use, time ranges, filters, business logic, or credentials. When the user mentions a Datris table by name, just confirm and write the instruction — the generated script will handle metadata discovery on its own.
              |
              |CREDENTIALS — Many external data sources need API keys, tokens, or other secrets. The Datris tap runner injects environment variables into the script at runtime. Common ones include:
              |- DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT (auto-injected for accessing the Datris platform itself)
              |- Custom API keys and tokens (must be configured by the user in a "tap secret")
              |
              |Whenever the data source needs authentication, you MUST:
              |1. Tell the user which environment variables the script will need (suggest specific names)
              |2. Mention that they should create or select a "tap secret" containing those keys in the Credentials section below the chat
              |3. Include the env var names in the instruction draft so the script generator references them via os.environ.get()
              |
              |For free, no-auth sources, no credentials are needed — say so explicitly.
              |
              |Ordering of clarifying questions — IMPORTANT:
              |1. If the user has not named a specific external data source and the data is NOT on Datris already, your FIRST follow-up MUST list 3-5 specific candidate sources (named APIs, datasets, or public services) so they can pick one. When an "Approved data sources" registry section is present below and covers the ask, draw the candidates from the registry FIRST — add candidates from your own knowledge only when the registry has no coverage, and say when a candidate is outside the registry. Mention whether each is free vs paid and whether each needs an API key. Do NOT ask about any other parameters yet — those questions are only useful once the model knows what API surface to write for.
              |2. Once a source IS picked (or the user pointed at a Datris table), THEN drill into the remaining parameters one at a time. Ask whatever is most relevant to the chosen source — typically the set of entities or items to fetch, any time range, the specific fields wanted, and any filters.
              |3. Ask ONE focused clarifying question at a time. Be concise — 1-2 sentences per turn.
              |4. When you have enough information, tell the user the instruction is ready and they can proceed.
              |
              |After EACH user message, return JSON with three fields:
              |{
              |  "reply": "your next message or question",
              |  "description": "a plain-English statement of what data the user wants and where it comes from — written for the user to read, NOT for code generation. No URLs, no API paths, no Python method names, no implementation detail.",
              |  "suggestedEnvVars": ["ENV_VAR_NAME_1", "ENV_VAR_NAME_2"]
              |}
              |
              |The description should always reflect everything known so far. If the user hasn't provided enough info yet, the description can be partial. Never leave description empty after the first user message — always provide your best guess.
              |
              |Write the description as plain English, the way you'd explain the task to a colleague. NEVER include:
              |- API URLs or paths (e.g., /api/v1/metadata/postgres/tables)
              |- HTTP verbs (POST, GET)
              |- Python library method names
              |- File paths or environment variable names
              |The script generator already knows how to call Datris APIs and which Python libraries to use — your job is to capture intent, not implementation. When the user references a Datris table/collection, name it in plain English. When the table is unknown, say so plainly.
              |
              |suggestedEnvVars should list any environment variable names the script will need that are NOT auto-injected by Datris (so do NOT include DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT). For free sources with no auth, return an empty array []. Always return the field, even when empty.
              |
              |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

        val baseSystemPrompt = if (tapType == "document") documentSystemPrompt else structuredSystemPrompt

        // Prepend a system-style note about current description if present
        val messagesWithContext: Seq[(String, String)] = if (currentDescription.nonEmpty) {
            val contextNote = (messages.head._1, "[Current instruction draft: " + currentDescription + "]\n\n" + messages.head._2)
            contextNote +: messages.tail
        } else {
            messages
        }

        val scanText = currentDescription + "\n" + messages.map(_._2).mkString("\n")
        val brainstormQuery = if (currentDescription.nonEmpty) currentDescription + "\n" + messages.lastOption.map(_._2).getOrElse("")
        else messages.lastOption.map(_._2).getOrElse("")
        val plan = AIUtil.planWebSearch(DatrisEnvironment.current.aiConfig, brainstormQuery)
        val nativeFragment = plan match {
            case AIUtil.WebSearchPlan.Native =>
                """
                  |
                  |Web search tool — IMPORTANT for source recommendations:
                  |- A `web_search` tool is available. Use it when recommending external data
                  |  sources (APIs, datasets, public services) to verify they still exist,
                  |  check current free-tier limits, surface alternatives the user may not
                  |  know, and confirm authentication requirements before suggesting them.
                  |- Do NOT search for things you already know reliably (well-known commercial
                  |  APIs, the Datris platform itself, common Python libraries) — only search
                  |  when current state matters or you're uncertain.""".stripMargin
            case _ => ""
        }
        val systemWithSearch = baseSystemPrompt + nativeFragment + AIUtil.renderInjectedContext(plan)
        val systemPrompt = TapPromptInjector.augment(systemWithSearch, scanText) + TapPromptInjector.approvedSourcesSection()
        val injectedPrompts = TapPromptInjector.matchKeys(scanText)

        val responseText = AIUtil.callAIWithMessages(
            systemPrompt,
            messagesWithContext,
            DatrisEnvironment.current.aiConfig,
            8192,
            -1.0,
            useWebSearch = AIUtil.useNative(plan)
        )
        if (AIUtil.useNative(plan)) {
            val citations = AIUtil.extractCitations(responseText, DatrisEnvironment.current.aiConfig)
            if (citations.nonEmpty)
                logger.info("/tap/brainstorm: native web search consulted " + citations.size + " source(s): " +
                    citations.map { case (url, title) => "[" + title + "](" + url + ")" }.mkString(", "))
        }
        val rawText = AIUtil.extractText(responseText).trim

        // Strip markdown code fences if present
        var cleaned = rawText
            .replaceAll("(?s)^```(?:json)?\\s*", "")
            .replaceAll("(?s)\\s*```$", "")
            .trim

        // Extract first JSON object substring if there's surrounding text
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1)
        }

        val gson = new Gson
        val response = new java.util.HashMap[String, Any]()

        try {
            val parsed = gson.fromJson(cleaned, classOf[java.util.Map[String, Any]])
            response.put("reply", Option(parsed.get("reply")).map(_.toString).getOrElse(""))
            response.put("description", Option(parsed.get("description")).map(_.toString).getOrElse(currentDescription))
            val envVars = parsed.get("suggestedEnvVars") match {
                case list: java.util.List[_] => list
                case _ => new java.util.ArrayList[String]()
            }
            response.put("suggestedEnvVars", envVars)
        } catch {
            case e: Exception =>
                // LLM didn't return JSON — use raw text as reply, keep current description
                logger.warn("Brainstorm AI did not return valid JSON, using raw text as reply", e)
                response.put("reply", rawText)
                response.put("description", currentDescription)
                response.put("suggestedEnvVars", new java.util.ArrayList[String]())
        }
        response.put("injectedPrompts", injectedPrompts)

        response
    }
}
