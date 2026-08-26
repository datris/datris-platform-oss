package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

case class TapGenerateResult(
    script: String,
    packages: java.util.List[String],
    scriptPath: String,
    injectedPrompts: java.util.List[String] = new java.util.ArrayList[String]()
)

object TapScriptGenerator {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val SYSTEM_PROMPT =
        """You are a code generator. Return a JSON object with two fields:
          |- "script": a valid Python 3 script that defines a function called `fetch()`
          |  that takes no arguments and returns a list of dictionaries (records).
          |- "packages": a list of any pip packages needed beyond the pre-installed set
          |  (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl,
          |  python-dateutil, pytz, google-cloud-storage, azure-storage-blob).
          |  Pre-installed packages do not need to be listed. Use an empty list if none needed.
          |
          |The script must:
          |- Be completely self-contained
          |- Include 30-second timeouts for network requests
          |- If authentication is needed, use os.environ.get('KEY_NAME') to access credentials
          |- NEVER hardcode API keys, tokens, or passwords in the script
          |
          |Error handling — IMPORTANT:
          |- Let exceptions propagate from `fetch()`. Do NOT wrap the body of `fetch()` in `try/except: return []`. Do NOT swallow exceptions silently. The platform runs your script in a wrapper that captures the full traceback when `fetch()` raises, and the traceback is the only signal the user (and the AI diagnosis tool) have for debugging.
          |- Only catch an exception if you can actually recover from it AND the recovery does something more useful than `return []`. For example, retrying once with backoff is fine; falling back to an alternate endpoint is fine; suppressing the error and returning empty is NOT fine.
          |- If you must catch an exception in a partial-failure scenario (e.g. one row out of many fails to parse), use `print(f"...", file=sys.stderr)` to log the issue and `continue`. Never suppress without logging.
          |- Never use bare `except:` or `except Exception: pass`. Catch the specific exception type you expect.
          |
          |HTTP requests — IMPORTANT:
          |- Always set a `User-Agent` header on HTTP requests. Many sites (Wikipedia, GitHub raw, etc.) return 403 Forbidden to default Python `requests` user-agents. Use something like `headers={'User-Agent': 'Mozilla/5.0 (compatible; datris-tap/1.0)'}`.
          |- Always check `resp.status_code` or call `resp.raise_for_status()` before parsing the body.
          |
          |Performance hygiene (low-risk only — do NOT restructure for speed at generation time):
          |- If the script will make more than one HTTP call to the same host, create a single `session = requests.Session()` at the top of `fetch()` and use `session.get(...)` / `session.post(...)` for every call. Apply default headers via `session.headers.update({...})`. Connection reuse avoids a TCP/TLS handshake per call.
          |- Do NOT insert defensive `time.sleep(...)` between requests. Only add a sleep when the API's documentation specifies a rate limit AND the script could plausibly hit it. If the script reads an API key from `os.environ.get(...)` (an authenticated/paid-tier call), do NOT add a precautionary sleep — assume normal quotas. If the API returns HTTP 429, honor `Retry-After` instead of pre-throttling.
          |- Do NOT introduce `concurrent.futures.ThreadPoolExecutor`, `asyncio`, or any other concurrency at generation time. Keep `fetch()` simple and serial. A separate optimization pass runs after a successful test with measured timing data and decides where parallelism is actually warranted.
          |
          |Pandas — IMPORTANT (modern API):
          |- The platform runs pandas 2.x. When parsing HTML you have already fetched (e.g. from `requests.get(...).text`), you MUST wrap the string in `io.StringIO`: `pd.read_html(io.StringIO(resp.text), ...)`. Passing the raw string directly was deprecated in pandas 2.1 and now raises a parser error because lxml treats it as a file path. Add `import io` at the top of the script when you do this.
          |- The same rule applies to `pd.read_csv` and `pd.read_json` when given a string of content rather than a path or URL — wrap in `io.StringIO`.
          |- When extracting integer columns from a pandas DataFrame, be aware that any NaN in a numeric column promotes the entire column to `float64`. If you cast a value with `int(x)`, it will produce a Python int — but if you let JSON serialize a `numpy.float64` directly it will emit `2880264.0`. Always cast numeric values to Python `int`/`float`/`str` before adding to the record dict.
          |
          |Column naming for tabular results:
          |- When returning a list of dicts (CSV-shaped data), prefer snake_case keys composed of [a-z0-9_] only.
          |- The platform automatically normalizes column names at runtime (e.g. "EPS Estimate" → "eps_estimate", "Surprise(%)" → "surprise_percent"), but generating clean keys directly is preferred so the user sees them faithfully in the test preview and in the pipeline schema.
          |- If the source returns columns with spaces, parens, or punctuation (common with pandas DataFrames), rename them in the script before adding to the record dict.
          |
          |If the script needs to query or discover data from the Datris platform:
          |- Use os.environ.get('DATRIS_PLATFORM_HOST') for the host (always injected by the platform)
          |- Use os.environ.get('DATRIS_PLATFORM_PORT') for the port (always injected by the platform)
          |- Use os.environ.get('DATRIS_POSTGRES_DATABASE') for the PostgreSQL database name (always injected by the platform)
          |- Use os.environ.get('DATRIS_MONGODB_DATABASE') for the MongoDB database name (always injected by the platform)
          |- These two database names may differ in single-tenant deployments; in multi-tenant mode they resolve to the same tenant name. Always use the variable that matches the backend you are querying.
          |- DO NOT provide fallback defaults for DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT, DATRIS_POSTGRES_DATABASE, or DATRIS_MONGODB_DATABASE — the platform always injects them. Use os.environ['NAME'] or os.environ.get('NAME') with NO second argument.
          |- Base URL: http://{host}:{port}/api/v1
          |
          |Metadata discovery (GET requests). Response shapes are EXACT and STABLE — trust them, do not probe alternate shapes or keys:
          |- GET /api/v1/metadata/postgres/databases → JSON array of strings (database names)
          |- GET /api/v1/metadata/postgres/schemas?database={pg_db} → JSON array of strings (schema names)
          |- GET /api/v1/metadata/postgres/tables?database={pg_db}&schema=public → JSON array of strings (table names)
          |- GET /api/v1/metadata/postgres/columns?database={pg_db}&schema=public&table=TABLE → JSON array of {name, type} objects
          |- GET /api/v1/metadata/mongodb/databases → JSON array of strings (database names)
          |- GET /api/v1/metadata/mongodb/collections?database={mongo_db} → JSON array of strings (collection names)
          |
          |Query endpoints (POST requests). Response shapes are EXACT:
          |- PostgreSQL: POST /api/v1/query/postgres
          |  Body: {"sql": "SELECT * FROM public.table_name", "database": "{pg_db}", "limit": -1}
          |  Response: object with keys `results` (array of row dicts) and `count` (int)
          |- MongoDB: POST /api/v1/query/mongodb
          |  Body: {"query": "...", "database": "{mongo_db}", "collection": "collection_name", "limit": -1}
          |  Response: object with keys `results` (array of document dicts) and `count` (int)
          |
          |Tap scripts MUST always pass `"limit": -1` on both endpoints (but honor the test-sample env var below):
          |- `-1` tells the server to return every matching row. It is the correct value for real cron/manual runs.
          |- Omitting `limit` makes the server apply a preview default (20 for Mongo, 100 for Postgres) and the tap will silently read a tiny slice. Do not rely on the default.
          |- There is no pagination on these endpoints. A single call returns the full result set; design the rest of the script accordingly (if a source is very large, iterate as you go rather than accumulating every intermediate value).
          |
          |Test-sample environment variable `DATRIS_TAP_TEST_LIMIT`:
          |- When the user enables "Limit test sample" in the Create Tap test UI, the runner injects `DATRIS_TAP_TEST_LIMIT` (an integer string, e.g. "20") into the script process. Cron and manual runs never set this variable.
          |- If `DATRIS_TAP_TEST_LIMIT` is set, the script MUST cap its work at that many records: use it as the `limit` on `/query/*` bodies (instead of -1) AND break out of any per-item iteration (per-record, per-user, per-page, etc.) after that many items. If unset or empty, pass `limit: -1` and iterate unbounded.
          |- Required pattern at the top of `fetch()`:
          |    _tl = os.environ.get('DATRIS_TAP_TEST_LIMIT')
          |    sample_cap = int(_tl) if _tl else None            # None = unlimited
          |    source_limit = sample_cap if sample_cap is not None else -1
          |  Use `source_limit` in /query/* request bodies. Use `sample_cap` to bound per-item loops (e.g. `for i, x in enumerate(items): if sample_cap is not None and i >= sample_cap: break`).
          |
          |Incremental sync — persistent state (`DATRIS_TAP_STATE` in, `DATRIS_STATE` out):
          |- The platform persists a small JSON state object between runs so a recurring tap fetches only what is new. The last committed state is injected as the env var `DATRIS_TAP_STATE` (absent on the very first run). To save new state, assign a dict to the module-global `DATRIS_STATE` inside `fetch()` (declare `global DATRIS_STATE` first). The platform commits it ONLY after a successful run — a failed run automatically re-fetches the same window, so never advance state defensively.
          |- CRITICAL: `fetch()` must STILL return the plain list of record dicts. Do NOT change the return shape to carry state — never `return {"records": rows, "state": ...}`. State travels ONLY through the `DATRIS_STATE` module-global; the return value stays exactly the record list it would be without state.
          |- Derive the cursor from the DATA, not the clock: save the max modified-timestamp / id seen in the fetched records, not "now" — wall-clock cursors silently skip records when clocks disagree with the source.
          |- PREFER an incremental design whenever the tap will run on a schedule and the source supports one of these, in order of preference:
          |  1. Modified-since filtering (an updated-after/modified-since query parameter): state = the newest modification timestamp seen.
          |  2. Append-only ordering (monotonic id or creation time): state = the highest id seen; stop paging once a page crosses that bookmark. Note this misses edits to old records — acceptable for event/log-style data.
          |  3. An opaque continuation or cursor token from the source: store the token verbatim and resume from it.
          |  4. Full-fetch sources: compute a hash of the serialized payload; if it equals the hash in state, return [] (nothing new); otherwise save the new hash and return everything.
          |  If none of these fit (small, unordered source), do not use state at all — a plain full fetch every run is correct, and the destination's upsert handles re-loads.
          |- Required read pattern at the top of `fetch()` when using state (import json at the top):
          |    state = json.loads(os.environ.get('DATRIS_TAP_STATE') or '{}')
          |- The cursor must be MONOTONIC: advance with max(previous_value, newest_seen) so re-processing old data can never move the bookmark backwards.
          |- If a `DATRIS_TAP_PARAM_*` value explicitly overrides the fetch window (a backfill run), do NOT set `DATRIS_STATE` for that run — backfills must not move the bookmark.
          |- Keep state tiny: a timestamp, an id, a token, or a hash — the platform rejects state over 64 KB. NEVER put records, large lists, or credentials in state; it is stored and displayed unmasked.
          |
          |Where {pg_db} = os.environ.get('DATRIS_POSTGRES_DATABASE') and {mongo_db} = os.environ.get('DATRIS_MONGODB_DATABASE').
          |
          |Do NOT write defensive shape-probing code for platform endpoints:
          |- Do NOT branch on `isinstance(resp.json(), list)` vs dict. The shapes above are contractual.
          |- Do NOT iterate candidate response keys (e.g. trying 'results', then 'data', then 'items'). The documented key is the only key.
          |- Do NOT probe alternate names for objects the user told you exist. If discovery returns nothing matching, raise a clear error that lists what was searched and what was found.
          |- Do NOT guess field names across multiple candidate keys in source documents. If the source's document shape is genuinely unknown, fetch one sample, log its keys to stderr with `print(..., file=sys.stderr)`, and raise with that context so the user can adjust. The AI diagnosis tool reads stderr.
          |- When a response shape surprises you, raise the exception. A clear traceback is better than silent wrong data.
          |
          |Use metadata endpoints to discover tables and columns dynamically when the user
          |describes data by name rather than providing exact table names.
          |
          |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

    private val DOCUMENT_SYSTEM_PROMPT =
        """You are a code generator for a DOCUMENT TAP. Return a JSON object with two fields:
          |- "script": a valid Python 3 script that defines a function called `fetch()`
          |  that takes no arguments and returns a list of dictionaries, where each dictionary
          |  describes ONE source document to be ingested.
          |- "packages": a list of any pip packages needed beyond the pre-installed set
          |  (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl,
          |  python-dateutil, pytz, google-cloud-storage, azure-storage-blob).
          |  Pre-installed packages do not need to be listed. Use an empty list if none needed.
          |
          |SCOPE — what the tap does (and does NOT do):
          |- The tap's ONLY job is to DISCOVER source documents and return their RAW BYTES.
          |- The tap MUST NOT chunk, split, or segment documents. Return one entry per source file.
          |- The tap MUST NOT extract text, parse PDFs, strip HTML, or transform the content in any way. Always return the original file bytes.
          |- The tap MUST NOT generate embeddings, call embedding models, or write to a vector store.
          |- The tap MUST NOT create tables, run SQL, or touch the destination database.
          |- Text extraction, chunking, embedding, and loading into the vector store are handled automatically by the downstream pipeline — the tap feeds it, nothing else.
          |- If the user's instruction asks for chunking, embeddings, a specific chunk size, a specific embedding model, or destination table creation, IGNORE those parts of the instruction. Those are pipeline configuration, not tap logic. Just return the raw source documents.
          |
          |Each document dict MUST contain:
          |- "uri" (required, string): a unique source identifier for the document (a URL, S3 key,
          |  file path, etc.). One URI per source file — never synthesize per-chunk URIs like "file#chunk-0".
          |- "filename" (required, string): the original filename including extension
          |  (e.g. "quarterly-report.pdf", "contract-2024.docx"). The extension drives the
          |  text-extraction path downstream — do not strip or alter it.
          |- "content" (required, string): the COMPLETE raw file bytes encoded as base64
          |  (use `base64.b64encode(raw_bytes).decode('ascii')`). Never a slice, never decoded text, never a chunk.
          |- "content_hash" (optional, string): a pre-computed ETag or hash for change detection.
          |  If omitted, the platform will compute a SHA-256 of the decoded bytes.
          |- "metadata" (optional, dict of string->string): arbitrary key-value metadata about the
          |  document (author, source system, tags, etc.). Stored in the ledger alongside the document. Do NOT invent metadata fields that describe pipeline behavior (chunk_size, embedding_model, target_table, etc.) — those belong on the pipeline, not the tap.
          |
          |The script must:
          |- Be completely self-contained
          |- Include 30-second timeouts for network requests
          |- If authentication is needed, use os.environ.get('KEY_NAME') to access credentials
          |- NEVER hardcode API keys, tokens, or passwords in the script
          |- `import base64` at the top and encode every document's bytes before returning
          |
          |Error handling — IMPORTANT:
          |- Let exceptions propagate from `fetch()`. Do NOT wrap the body of `fetch()` in `try/except: return []`. The platform's wrapper captures the traceback for diagnosis.
          |- If one document fails to fetch mid-run, log with `print(..., file=sys.stderr)` and `continue`. Never suppress silently.
          |- Never use bare `except:` or `except Exception: pass`. Catch the specific exception type you expect.
          |
          |Local paths — IMPORTANT:
          |- The tap runs inside the Datris server container, not on the user's workstation. An absolute path from the user's machine (e.g. `/Users/foo/...`, `C:\\...`) will almost certainly not exist.
          |- If the user gave a host-side path that isn't mounted into the container, raise a clear error telling them the path is unreachable. Do NOT walk the filesystem looking for a substitute directory. Do NOT fall back to `/`, `/tmp`, `/data`, or anywhere else — a silent fallback causes the tap to ingest arbitrary system files (shell scripts, config, etc.) and poisons the vector store.
          |- Only accept a local path when it has been explicitly mounted into the container. Prefer remote sources (HTTP, S3, SharePoint) over local paths whenever possible for document taps.
          |
          |HTTP requests — IMPORTANT:
          |- Always set a `User-Agent` header on HTTP requests.
          |- Always check `resp.status_code` or call `resp.raise_for_status()` before reading bytes.
          |- For binary downloads use `resp.content` (not `resp.text`) to get raw bytes.
          |
          |Performance hygiene:
          |- Reuse a single `requests.Session()` for all HTTP calls to the same host. Apply default headers via `session.headers.update({...})`.
          |- Do NOT insert defensive `time.sleep(...)` between requests.
          |- Do NOT introduce concurrency (threads, asyncio) at generation time. Keep `fetch()` serial.
          |
          |Test-sample environment variable `DATRIS_TAP_TEST_LIMIT`:
          |- When set, the script MUST cap the number of documents returned at that many.
          |- Required pattern at the top of `fetch()`:
          |    _tl = os.environ.get('DATRIS_TAP_TEST_LIMIT')
          |    sample_cap = int(_tl) if _tl else None  # None = unlimited
          |  Break out of the discovery loop once `len(documents) >= sample_cap`.
          |
          |Typical shape:
          |    import os, sys, base64, requests
          |    def fetch():
          |        _tl = os.environ.get('DATRIS_TAP_TEST_LIMIT')
          |        sample_cap = int(_tl) if _tl else None
          |        session = requests.Session()
          |        session.headers.update({'User-Agent': 'Mozilla/5.0 (compatible; datris-tap/1.0)'})
          |        documents = []
          |        for item in discover(session):
          |            if sample_cap is not None and len(documents) >= sample_cap:
          |                break
          |            resp = session.get(item['url'], timeout=30)
          |            resp.raise_for_status()
          |            documents.append({
          |                'uri': item['url'],
          |                'filename': item['name'],
          |                'content': base64.b64encode(resp.content).decode('ascii'),
          |                'content_hash': resp.headers.get('ETag'),
          |                'metadata': {'source': item.get('source', '')}
          |            })
          |        return documents
          |
          |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

    /**
     * Generate a Python fetch() script from a plain-English description.
     * Stores the script in MinIO and returns the result with script path.
     *
     * @param description what data to fetch
     * @param tapName     the tap name (used for the MinIO key)
     * @param tapType     "structured" (default) or "document" — chooses the system prompt
     * @return TapGenerateResult with script content, packages, and MinIO path
     */
    def generate(
        description: String,
        tapName: String,
        oldScriptPath: String = null,
        secretName: String = null,
        tapType: String = "structured"
    ): TapGenerateResult = {
        logger.info("TapScriptGenerator: generating script for tap: " + tapName)

        if (!DatrisEnvironment.current.aiEnabled)
            throw new DatrisException("AI is not enabled. Set 'ai.enabled: true' in application.yaml")

        TapGenerationProgress.start(tapName)
        try {
            generateInternal(description, tapName, oldScriptPath, secretName, tapType)
        } finally {
            TapGenerationProgress.finish(tapName)
        }
    }

    private def generateInternal(
        description: String,
        tapName: String,
        oldScriptPath: String,
        secretName: String,
        tapType: String
    ): TapGenerateResult = {

        // Build user prompt with available secret keys if configured
        val secretKeysHint = if (secretName != null && secretName.nonEmpty) {
            val secretPath = DatrisEnvironment.current.environment + "/" + secretName
            val keys = SecretsUtil.getSecretMap(secretPath).map(_.keySet().asScala.filterNot(_ == "_type").toList).getOrElse(List.empty)
            if (keys.nonEmpty)
                "\n\nThe following environment variables are available for authentication: " +
                    keys.mkString(", ") + ". Access them with os.environ.get('KEY_NAME')."
            else ""
        } else ""

        val userPrompt = "Generate a Python script to: " + description + secretKeysHint

        // Call AI to generate the script — use codegen config (falls back to main aiConfig when unset).
        // First-pass generation does NOT use web search by design — it's the happy path that
        // typically targets common APIs the model already knows. Web search is reserved for the
        // recovery paths (/tap/diagnose and /tap/fix) where the model has demonstrably gotten
        // something wrong and current docs add real value.
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        logger.info("TapScriptGenerator: generating with provider '" + codegenCfg.provider +
            "', model '" + codegenCfg.model + "' for tap: " + tapName)
        val baseSystemPrompt = if (tapType == "document") DOCUMENT_SYSTEM_PROMPT else SYSTEM_PROMPT
        val systemPrompt = TapPromptInjector.augment(baseSystemPrompt, description)
        val responseText = AIUtil.callAIWithSystem(systemPrompt, userPrompt, codegenCfg)
        val extracted = AIUtil.extractText(responseText, codegenCfg)
        val cleaned = cleanResponse(extracted)

        logger.info("TapScriptGenerator: AI response length: " + cleaned.length + " chars")

        val gson = new Gson

        // Try to parse an LLM response as a {script, packages} JSON object.
        // Returns Some((script, packages)) on success, None on any failure.
        // Handles preamble/suffix text around the JSON and common LLM formatting quirks.
        //
        // Robustness: with web search enabled the response often interleaves narrative
        // ("I searched for X, found Y") with the final JSON, and that narrative may
        // contain stray braces (e.g. `{example}` placeholders, `{site:pypi.org ...}`
        // search-syntax mentions). A naive `text.indexOf('{') .. text.lastIndexOf('}')`
        // would span those stray braces and produce invalid JSON. We instead scan
        // every `{` position, find its balanced `}` using string-aware nesting, and
        // accept the first one that parses with the expected `script` field.
        def tryParseAsJsonObject(text: String): Option[(String, java.util.List[String])] = {
            findBalancedJsonObjects(text).iterator.flatMap { candidate =>
                try {
                    val result = gson.fromJson(candidate, classOf[java.util.Map[String, Any]])
                    Option(result).flatMap { r =>
                        Option(r.get("script")).map(_.toString).filter(_.trim.nonEmpty).map { s =>
                            val p: java.util.List[String] = r.get("packages") match {
                                case null => new java.util.ArrayList[String]()
                                case list: java.util.List[_] =>
                                    val stringList = new java.util.ArrayList[String]()
                                    val it = list.iterator()
                                    while (it.hasNext) stringList.add(it.next().toString)
                                    stringList
                                case _ => new java.util.ArrayList[String]()
                            }
                            (s, p)
                        }
                    }
                } catch {
                    case e: Exception =>
                        logger.debug("Candidate JSON object in AI response did not parse — trying next candidate", e)
                        None
                }
            }.toIterable.headOption
        }

        // Attempt 1: parse the original response as a JSON object.
        //
        // Attempt 2 (retry): if attempt 1 failed, the LLM probably returned a raw script or a
        // JSON string literal. Call the AI again with a short format-only prompt that shows
        // the bad response back to the model and asks for the same content reformulated as a
        // valid JSON object. This is cheap on the happy path (never fires) and turns a hard
        // failure into a one-extra-call inconvenience on the unhappy path.
        //
        // Attempt 3 (fallback): if the retry still fails, treat the cleaned response as a
        // raw Python script with no package list — better than crashing, user can add
        // packages manually in Edit & Test.
        val (script, packages): (String, java.util.List[String]) =
            tryParseAsJsonObject(cleaned).orElse {
                // Log the head of the unparseable response: without it a systematic
                // envelope break (a model preamble, a new fence style) is invisible
                // in the logs and costs a full extra generation on every tap.
                logger.warn("TapScriptGenerator: first response did not parse as JSON — retrying with format reminder. " +
                    "Response head: " + cleaned.take(400).replace("\n", "\\n"))
                TapGenerationProgress.phase(tapName, "retrying-format", attempt = 2)
                val preview = if (cleaned.length > 2000) cleaned.take(2000) + "\n... (truncated)" else cleaned
                val retrySystemPrompt =
                    """Return ONLY a JSON object with exactly two fields:
                      |  "script": the complete Python 3 script as a string
                      |  "packages": an array of pip package names (empty array if none)
                      |No markdown fences, no string literals, no commentary — a JSON object.""".stripMargin
                val retryUserPrompt =
                    s"""Your previous response for the task below was not a valid JSON object. Here is what you returned:
                       |
                       |$preview
                       |
                       |Return the same Python script reformulated as a valid JSON object of the form {"script": "...", "packages": [...]}.
                       |
                       |Original task: $userPrompt""".stripMargin
                try {
                    val retryText = AIUtil.extractText(AIUtil.callAIWithSystem(retrySystemPrompt, retryUserPrompt, codegenCfg), codegenCfg)
                    val retryCleaned = cleanResponse(retryText)
                    logger.info("TapScriptGenerator: retry response length: " + retryCleaned.length + " chars")
                    tryParseAsJsonObject(retryCleaned)
                } catch {
                    case e: Exception =>
                        logger.warn("TapScriptGenerator: retry call failed: " + e.getMessage)
                        None
                }
            }.getOrElse {
                // Final fallback: treat the original cleaned response as a raw script.
                // Try to unwrap a JSON string literal first in case the LLM returned "..." form.
                val unwrapped =
                    try {
                        val s = gson.fromJson(cleaned, classOf[String])
                        if (s != null && s.nonEmpty) s else cleaned
                    } catch {
                        case e: Exception =>
                            logger.debug("AI response is not a JSON string literal — using cleaned response as-is", e)
                            cleaned
                    }
                logger.warn("TapScriptGenerator: retry also failed — treating as raw script (length: " + unwrapped.length + ")")
                (unwrapped, new java.util.ArrayList[String]())
            }

        if (script == null || script.trim.isEmpty)
            throw new DatrisException("AI returned an empty script")

        // Sanity check — every tap script must define a top-level `fetch()` function;
        // the runner imports the module and calls `mod.fetch()`. Without this guard a
        // bad-shape generation (e.g. JSON parser fell through to raw-text fallback and
        // we kept the model's narrative) would still be stored to MinIO and only fail
        // at run time with a confusing `AttributeError: module 'tap' has no attribute 'fetch'`.
        // Catching it here gives the user a clearer error and avoids storing junk.
        if (!hasFetchFunction(script))
            throw new DatrisException(
                "AI returned a script that doesn't define a `fetch()` function. " +
                    "This usually means the model returned narrative text instead of code. " +
                    "Try regenerating, or paste your own script via 'I Have My Own Code'."
            )

        // Store script in MinIO (cleanup old)
        TapGenerationProgress.phase(tapName, "storing")
        val scriptPath = storeScript(tapName, script, oldScriptPath)

        logger.info("TapScriptGenerator: script stored at: " + scriptPath + ", packages: " + packages)
        TapGenerateResult(script, packages, scriptPath, TapPromptInjector.matchKeys(description))
    }

    /**
     * Store a script (user-provided or AI-generated) in MinIO.
     */
    def storeScript(tapName: String, script: String, oldScriptPath: String = null): String = {
        // Do NOT delete oldScriptPath. The UI's auto-revert (regression detect
        // or user-initiated "Revert to original") restores the prior scriptPath
        // and expects the file to still exist in MinIO. Deleting on every
        // storeScript silently breaks revert — the tap saves with a path that
        // points at a missing file, run_tap fails with "scriptMissing", and the
        // user has no way to recover without regenerating. Cleanup happens on
        // tap delete (deleteTap → deleteScript on the current scriptPath);
        // orphaned older versions are tiny .py files and accumulate slowly.
        val env = DatrisEnvironment.current.environment
        val bucketName = env + "-config"
        val uuid = UUID.randomUUID().toString.substring(0, 8)
        val key = "tap-scripts/" + tapName + "_" + uuid + ".py"

        ObjectStoreUtil.writeBucketObject(bucketName, key, script)
        key
    }

    /**
     * Delete a script from MinIO.
     */
    def deleteScript(scriptPath: String): Unit = {
        if (scriptPath != null) {
            val env = DatrisEnvironment.current.environment
            val bucketName = env + "-config"
            try {
                ObjectStoreUtil.deleteBucketObject(bucketName, scriptPath)
            } catch {
                case e: Exception =>
                    logger.warn("Failed to delete tap script from object store: " + scriptPath + ", error: " + e.getMessage)
            }
        }
    }

    private def cleanResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json"))
            cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```"))
            cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```"))
            cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    /** Check whether a Python script defines a top-level `fetch()` function.
      * Matches `def fetch(`, `async def fetch(`, with any whitespace, but only
      * when at the start of a line (so we don't match `# def fetch()` in a
      * comment). Cheap and precise enough for our purposes — full Python
      * parsing is overkill. */
    private def hasFetchFunction(script: String): Boolean = {
        if (script == null) return false
        val pattern = "(?m)^\\s*(?:async\\s+)?def\\s+fetch\\s*\\(".r
        pattern.findFirstIn(script).isDefined
    }

    /** Scan a string for every balanced `{...}` JSON object substring, in order
      * of appearance. Tracks string literals and escape sequences so braces
      * inside strings don't break nesting depth. Returns a list of substrings,
      * each beginning with `{` and ending with the matching `}`. The caller
      * tries each in turn (typically picking the first that parses with the
      * expected schema).
      *
      * Why this exists: the naive `text.indexOf('{') .. text.lastIndexOf('}')`
      * approach fails on responses that mention `{example}` or
      * `{site:pypi.org ...}` in narrative text alongside the actual JSON. With
      * web search enabled the model frequently produces such narrative. */
    private def findBalancedJsonObjects(text: String): List[String] = {
        if (text == null || text.isEmpty) return Nil
        val out = scala.collection.mutable.ListBuffer.empty[String]
        var i = 0
        val n = text.length
        while (i < n) {
            if (text.charAt(i) == '{') {
                val end = matchingBrace(text, i)
                if (end > i) {
                    out += text.substring(i, end + 1)
                    i = end + 1
                } else {
                    i += 1
                }
            } else {
                i += 1
            }
        }
        out.toList
    }

    /** Find the matching `}` for the `{` at startIdx, respecting JSON string
      * literals (`"..."` with `\` escapes) so braces inside strings are
      * ignored. Returns -1 if no balanced match is found. */
    private def matchingBrace(text: String, startIdx: Int): Int = {
        if (startIdx < 0 || startIdx >= text.length || text.charAt(startIdx) != '{') return -1
        var depth = 0
        var inString = false
        var escaped = false
        var i = startIdx
        while (i < text.length) {
            val c = text.charAt(i)
            if (escaped) {
                escaped = false
            } else if (inString) {
                if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                c match {
                    case '"' => inString = true
                    case '{' => depth += 1
                    case '}' =>
                        depth -= 1
                        if (depth == 0) return i
                    case _ => ()
                }
            }
            i += 1
        }
        -1
    }
}
