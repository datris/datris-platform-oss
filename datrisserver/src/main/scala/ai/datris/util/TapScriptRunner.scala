package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, TapConfig}
import org.slf4j.{Logger, LoggerFactory}
import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.apache.http.HttpHeaders
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

case class TapScriptResult(
    records: String,
    recordCount: Int,
    error: String,
    logs: String = null,
    dataType: String = "json",
    columns: java.util.List[String] = null,
    publisherToken: String = null,
    pipelineTokens: java.util.List[String] = null,
    missingSecretFields: Seq[String] = Nil,
    // Incremental-sync state the script emitted (raw JSON), or null. Committed by
    // TapRunner only after a successful real run — never on failure or test mode.
    newState: String = null
)

object TapScriptRunner {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private def scriptTimeoutSeconds: Int = DatrisEnvironment.current.tapScriptTimeoutSeconds

    // private[util] so TapWrapperStateSpec can execute the real wrapper against
    // fixture scripts — the wrapper is the wire format, and a drift here breaks
    // every tap shape at once.
    private[util] val WRAPPER_TEMPLATE =
        """import json, sys, os, time, importlib.util
          |# Redirect script's print() output to stderr so only JSON goes to stdout.
          |# Wrapper-emitted lifecycle lines (prefixed [wrapper]) also go to stderr so
          |# every run has some log content even when the user's script is silent.
          |_real_stdout = sys.stdout
          |sys.stdout = sys.stderr
          |print("[wrapper] loading tap script", flush=True)
          |spec = importlib.util.spec_from_file_location("tap", sys.argv[1])
          |mod = importlib.util.module_from_spec(spec)
          |spec.loader.exec_module(mod)
          |print("[wrapper] calling fetch()", flush=True)
          |_t0 = time.time()
          |result = mod.fetch()
          |_elapsed = time.time() - _t0
          |sys.stdout = _real_stdout
          |# Normalize the {"records": [...], "state": {...}} shape. The documented
          |# contract is "return the record list, set global DATRIS_STATE" — but code
          |# generators naturally produce this envelope instead, and without this
          |# normalization it silently counts as 0 records (a dict is "1 json
          |# payload"). Accept it, honor its state, and nudge toward the canonical
          |# form on stderr.
          |if isinstance(result, dict) and isinstance(result.get("records"), list):
          |    print("[wrapper] fetch() returned {'records': ..., 'state': ...} — normalized. Prefer returning the list and setting the DATRIS_STATE global.", file=sys.stderr, flush=True)
          |    if isinstance(result.get("state"), dict) and getattr(mod, "DATRIS_STATE", None) is None:
          |        mod.DATRIS_STATE = result["state"]
          |    result = result["records"]
          |# Detect data type from result
          |if isinstance(result, list) and len(result) > 0 and isinstance(result[0], dict) and 'uri' in result[0] and 'content' in result[0]:
          |    # Document tap: list of {uri, filename, content (base64), ...}
          |    data_type = "document"
          |    data = json.dumps(result, default=str)
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], dict):
          |    # Normalize dict keys to strings (handles Timestamp, numpy keys)
          |    result = [{str(k): v for k, v in row.items()} for row in result if isinstance(row, dict)]
          |    data = json.dumps(result, default=str)
          |    data_type = "json"
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], (list, tuple)):
          |    data_type = "csv"
          |    data = json.dumps(result)
          |elif isinstance(result, str):
          |    trimmed = result.strip()
          |    if trimmed.startswith("<?xml") or trimmed.startswith("<"):
          |        data_type = "xml"
          |    elif trimmed.startswith("{") or trimmed.startswith("["):
          |        data_type = "json"
          |    else:
          |        data_type = "text"
          |    data = json.dumps(result)
          |elif isinstance(result, list):
          |    data_type = "json"
          |    data = json.dumps(result)
          |else:
          |    data_type = "json"
          |    data = json.dumps(result)
          |# Lifecycle summary on stderr — visible in tap-run logs whether the script
          |# printed anything or not.
          |if data_type in ("json", "csv", "document") and isinstance(json.loads(data), list):
          |    _count = len(json.loads(data))
          |    print(f"[wrapper] fetch() returned {_count} {data_type} record(s) in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |else:
          |    print(f"[wrapper] fetch() returned 1 {data_type} payload in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |envelope = {"type": data_type, "data": json.loads(data) if data_type in ("json", "csv", "document") else data}
          |# Incremental-sync state: a script that wants the platform to remember its
          |# position sets a module-global dict DATRIS_STATE inside fetch(). Absent or
          |# non-dict → no state key, and the previously committed state stays put.
          |_new_state = getattr(mod, "DATRIS_STATE", None)
          |if _new_state is not None and not isinstance(_new_state, dict):
          |    print(f"[wrapper] DATRIS_STATE ignored: expected dict, got {type(_new_state).__name__}", file=sys.stderr, flush=True)
          |    _new_state = None
          |if _new_state is not None:
          |    envelope["state"] = json.loads(json.dumps(_new_state, default=str))
          |    print("[wrapper] fetch() emitted state: " + json.dumps(envelope["state"], default=str)[:200], file=sys.stderr, flush=True)
          |print(json.dumps(envelope))
          |""".stripMargin

    // State blobs are cursors, not data stores. Anything near this size is a
    // script bug (e.g. stuffing records into DATRIS_STATE) — fail loudly rather
    // than silently persisting an ever-growing document to Mongo.
    private val MaxStateBytes: Int = 64 * 1024

    // Per-run params are validated against this pattern so they cleanly map onto
    // env var names (DATRIS_TAP_PARAM_<key>). Reject anything else so we never
    // silently drop a param or generate an invalid env var.
    private val ParamKeyPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

    def run(
        tapConfig: TapConfig,
        testLimit: Int = 0,
        params: Map[String, String] = Map.empty,
        previousState: String = null
    ): TapScriptResult = {
        // HTTP taps run zero code on the platform: the "script" is a user-hosted
        // endpoint speaking the same envelope contract, so the entire Python lane
        // (storage read, wrapper, venv/pip, sidecar, secret-field inference) is
        // skipped and everything downstream of the envelope is shared.
        if (tapConfig.isHttp) return runHttp(tapConfig, testLimit, params, previousState)

        logger.info("TapScriptRunner: executing tap: " + tapConfig.name)

        // Step 1: Read script from its storage backend (MinIO or code repo)
        val scriptContent = TapCodeStore.forTap(tapConfig).readScript(tapConfig).getOrElse(
            throw new DatrisException(
                "Tap script is missing from " +
                    (if (tapConfig.scriptStorage == "github") "the code repository (path: " + tapConfig.scriptRepoPath + ")"
                     else "object storage (path: " + tapConfig.scriptPath + ")") +
                    ". Open Edit Tap and regenerate the script, or paste a new one."
            )
        )

        // Step 2: Write script and wrapper to temp files
        val scriptFile: Path = Files.createTempFile("tap_script_", ".py")
        val wrapperFile: Path = Files.createTempFile("tap_wrapper_", ".py")

        // Tracks secret values across the try block so the catch handler can mask
        // them out of any exception message before storing it on TapScriptResult.
        // Populated once secretEnvVars is computed inside the try.
        var secretValuesForMasking: Seq[String] = Seq.empty

        // venv holding any tap-declared extra packages (Phase 5); cleaned up in finally.
        // None when the tap declares no extras and the run uses the system python3.
        var venvDir: Option[Path] = None

        // Secret field(s) the script requires (reads from the env with no fallback)
        // that the referenced secret does NOT provide. Carried out on TapScriptResult
        // so TapRunner can turn an otherwise-graceful 0-record run into a failure with
        // a precise cause. We do NOT fail before running on this — a successful run
        // proves the script had what it needed, so the signal is only acted on when
        // the run also produced no records (see TapRunner).
        var detectedMissingSecretFields: Seq[String] = Nil

        try {
            // Step 4: Load secrets as env vars if configured.
            // A tap that DECLARES a secret but whose secret is missing or empty is a
            // misconfiguration, not a no-op: the script would run unauthenticated and
            // typically returns 0 rows and exits cleanly, which would otherwise be
            // recorded as a graceful `no_records` success — hiding the real cause (a
            // deleted/empty credential). Fail loudly instead, mirroring how every other
            // subsystem (StartupRunner, the vector loaders, etc.) treats a missing secret.
            // Only the secret NAME appears in the message — never a value.
            val secretEnvVars: Seq[(String, String)] = if (tapConfig.secretName != null && tapConfig.secretName.nonEmpty) {
                val secretPath = DatrisEnvironment.current.environment + "/" + tapConfig.secretName
                logger.info("TapScriptRunner: loading secrets from: " + secretPath)
                val fields = SecretsUtil.getSecretMap(secretPath)
                    .map(_.asScala.filterNot(_._1 == "_type").toSeq)
                    .getOrElse(Seq.empty)
                if (fields.isEmpty)
                    throw new DatrisException(
                        "Tap references secret '" + tapConfig.secretName + "' but no credentials were injected — " +
                            "the secret is missing or empty in the vault. The script would run unauthenticated and silently " +
                            "return no data. Recreate the secret under Configuration → Secrets with the field(s) the tap " +
                            "expects, or update the tap to reference an existing secret."
                    )
                // The secret exists but may be missing a SPECIFIC field the script reads
                // (e.g. it has FOO but the script needs POLYGON_API_KEY). We can't know
                // per-tap required fields from a schema, so we infer them from the script's
                // own no-fallback env reads. To stay false-positive-free we only flag when
                // the secret provides NONE of those fields — an unambiguous wrong/stale
                // secret. If it provides at least one, the rest are likely optional, so we
                // stay quiet rather than risk failing a legitimate no-data run. Recorded
                // here, acted on by TapRunner only if the run also returns 0 records.
                val required = requiredSecretFields(scriptContent)
                if (required.nonEmpty && required.intersect(fields.map(_._1).toSet).isEmpty)
                    detectedMissingSecretFields = required.toSeq.sorted
                fields
            } else Seq.empty

            // Always inject Datris platform env vars so scripts can call back into the platform.
            // Mongo db follows the same multi-tenant rule as MetadataAPIController.scala (line 226):
            // in multi-tenant mode the tenant name IS the mongo database name.
            val mongoDatabase = if (DatrisEnvironment.current.multiTenant)
                DatrisEnvironment.current.environment
            else
                DatrisEnvironment.current.mongoDbConfig.database
            // Platform callback host: in-process the tap runs in the datris container, so
            // localhost reaches the server. In the sidecar runner the tap is in a different
            // container, so localhost is the runner itself — use the datris service name
            // (reachable on tap-net), overridable via TAP_RUNNER_CALLBACK_HOST.
            val platformHost = if (useTapRunner) tapRunnerCallbackHost else "localhost"
            val platformEnvVars = Seq(
                "DATRIS_POSTGRES_DATABASE" -> DatrisEnvironment.current.postgresDatabase,
                "DATRIS_MONGODB_DATABASE" -> mongoDatabase,
                "DATRIS_PLATFORM_HOST" -> platformHost,
                "DATRIS_PLATFORM_PORT" -> "8080"
            )
            // Test-only sample cap. Only set when the UI Test Script checkbox is
            // enabled. Cron/manual runs never get this env var, so the script's
            // fetch() reads everything.
            val testLimitEnvVars: Seq[(String, String)] =
                if (testLimit > 0) Seq("DATRIS_TAP_TEST_LIMIT" -> testLimit.toString) else Seq.empty

            // Per-run params from run_tap(params={...}). Surfaced to the script
            // as DATRIS_TAP_PARAM_<key> env vars — agent can drive parameterized
            // runs (date range, id list, page cursor) without rewriting the
            // tap secret on every call. Scheduled cron runs supply no params, so
            // scripts must apply sensible defaults when the env var is absent.
            val paramEnvVars: Seq[(String, String)] = params.toSeq.flatMap { case (k, v) =>
                val key = if (k == null) "" else k.trim
                if (key.isEmpty) None
                else if (ParamKeyPattern.findFirstIn(key).isEmpty)
                    throw new DatrisException(
                        "Invalid tap param key '" + key + "'. Keys must match [A-Za-z_][A-Za-z0-9_]* " +
                            "so they map cleanly onto env var names. Got: " + key
                    )
                else Some("DATRIS_TAP_PARAM_" + key -> (if (v == null) "" else v))
            }
            // Incremental-sync state committed by the last successful run. Injected in
            // test mode too (a test should exercise the same window a real run would),
            // but only real runs ever COMMIT new state (TapRunner gates on push).
            val stateEnvVars: Seq[(String, String)] =
                if (previousState != null && previousState.nonEmpty) Seq("DATRIS_TAP_STATE" -> previousState)
                else Seq.empty
            val allEnvVars = platformEnvVars ++ testLimitEnvVars ++ paramEnvVars ++ stateEnvVars ++ secretEnvVars

            // Step 5: Execute the wrapper — either in the isolated datris-tap-runner sidecar
            // (Phase 3, when USE_TAP_RUNNER is set) or in-process (default). Both receive the
            // same allEnvVars and return (stdout, stderr); the wrapper/envelope protocol is
            // identical, so everything downstream is unchanged.
            val secretValues = secretEnvVars.map(_._2)
            secretValuesForMasking = secretValues
            val (rawOutput, rawLogs) =
                if (useTapRunner) {
                    executeViaRunner(scriptContent, allEnvVars, tapConfig.packages, scriptTimeoutSeconds, secretValues)
                } else {
                    // In-process path: materialize the script/wrapper, install any extra
                    // packages into a throwaway venv, and run with the chosen interpreter.
                    Files.write(scriptFile, scriptContent.getBytes("UTF-8"))
                    Files.write(wrapperFile, WRAPPER_TEMPLATE.getBytes("UTF-8"))
                    venvDir = installPackages(tapConfig)
                    val python = venvDir.map(_.resolve("bin").resolve("python3").toString).getOrElse("python3")
                    executeWithTimeout(python, wrapperFile.toString, scriptFile.toString, scriptTimeoutSeconds, allEnvVars, secretValues)
                }
            // Mask secret values in logs before they're persisted (TapRunLog), surfaced via
            // get_tap_logs / the run-history UI, or echoed to the platform's own logger.
            // A script's print() that incidentally includes an API key would otherwise leak
            // through the run history to anyone with tap access.
            val logs = if (rawLogs.nonEmpty) maskSecrets(rawLogs, secretValues) else rawLogs
            logger.info("TapScriptRunner: script executed, output length: " + rawOutput.length + " chars")
            if (logs.nonEmpty) logger.info("TapScriptRunner: script logs:\n" + logs)

            // Guard the JVM from buffering huge tap outputs. The runner holds the
            // entire script stdout as a String, then JSON-parses it into a Map —
            // both copies live in heap simultaneously. A 200MB script output can
            // OOM-kill the server before the agent finds out the chunk was too big.
            // Fail fast with an actionable message the agent can act on.
            val maxBytes: Long = DatrisEnvironment.current.tapMaxOutputMB.toLong * 1024L * 1024L
            if (rawOutput.length.toLong > maxBytes) {
                val actualMB = rawOutput.length / (1024 * 1024)
                throw new DatrisException(
                    "Tap script output exceeded the " + DatrisEnvironment.current.tapMaxOutputMB +
                        " MB limit (got ~" + actualMB + " MB). The whole batch is buffered in memory before " +
                        "loading to the pipeline, so very large fetches risk OOM-ing the server. " +
                        "Reduce the source range — e.g., a shorter date window, smaller page size, " +
                        "or per-record/per-day chunks — and call run_tap again. " +
                        "Multiple smaller runs all land in the same destination pipeline."
                )
            }

            // Step 5: Parse envelope to extract data type and records
            val gson = new com.google.gson.Gson
            val envelope = gson.fromJson(rawOutput, classOf[java.util.Map[String, Any]])
            val dataType = Option(envelope.get("type")).map(_.toString).getOrElse("json")
            val data = envelope.get("data")

            // Optional incremental-sync state emitted by the script (wrapper puts it on
            // the envelope only when the script set a dict DATRIS_STATE). Extracted with
            // JsonParser — NOT via the gson Map above — so number literals survive
            // verbatim: the Map path turns every JSON number into a Double and would
            // re-serialize an integer cursor 1785850779844 as 1785850779844.0, which a
            // script interpolating it into a source URL would send malformed. Oversized
            // state is a script bug — a cursor should be bytes, not a payload.
            val newStateJson: String = extractStateJson(rawOutput)
            if (newStateJson != null && newStateJson.length > MaxStateBytes) {
                throw new DatrisException(
                    "Tap script emitted a DATRIS_STATE blob of ~" + (newStateJson.length / 1024) +
                        " KB (limit " + (MaxStateBytes / 1024) + " KB). State is a cursor for the next run — " +
                        "a timestamp, an id watermark, a page token — not a place to store records. " +
                        "Reduce DATRIS_STATE to the minimal position marker the next run needs."
                )
            }
            val dataJson = gson.toJson(data)
            val recordCount = if (dataType == "json" || dataType == "csv" || dataType == "document") countRecords(dataJson) else 1

            // For CSV-shaped data: normalize column names so they pass PipelineValidatorUtil
            // (which only allows [A-Za-z0-9_]+) and so downstream SQL doesn't need quoting.
            // Rewrites BOTH the records (key by key) and the extracted columns array.
            // No-op for json/xml/text — those go to mongo destinations as raw blobs.
            val (normalizedDataJson, columns): (String, java.util.List[String]) =
                if (dataType == "csv" && recordCount > 0) normalizeCsvColumns(dataJson, gson)
                else (dataJson, null)

            logger.info("TapScriptRunner: dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(
                normalizedDataJson,
                recordCount,
                null,
                if (logs.nonEmpty) logs else null,
                dataType,
                columns,
                missingSecretFields = detectedMissingSecretFields,
                newState = newStateJson
            )
        } catch {
            case e: DatrisException =>
                // DatrisException messages constructed inside this method are already
                // masked at throw time (executeWithTimeout). Re-mask defensively in case a
                // future code path forgets — maskSecrets is a no-op when the input has no
                // secret substrings.
                val masked = if (secretValuesForMasking.nonEmpty) maskSecrets(e.getMessage, secretValuesForMasking) else e.getMessage
                logger.error("TapScriptRunner failed: " + masked)
                TapScriptResult(null, 0, masked)
            case e: Exception =>
                logger.error("TapScriptRunner failed", e)
                val masked = if (secretValuesForMasking.nonEmpty) maskSecrets(e.getMessage, secretValuesForMasking) else e.getMessage
                TapScriptResult(null, 0, masked)
        } finally {
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
            venvDir.foreach(deleteRecursively)
        }
    }

    /** For CSV-shaped data: normalize column names so they pass PipelineValidatorUtil
      * (which only allows [A-Za-z0-9_]+) and so downstream SQL doesn't need quoting.
      * Rewrites BOTH the records (key by key) and the extracted columns array.
      * Shared by the script and HTTP paths; no-op fallback on any parse trouble. */
    private def normalizeCsvColumns(dataJson: String, gson: com.google.gson.Gson): (String, java.util.List[String]) = {
        try {
            val list = gson.fromJson(dataJson, classOf[java.util.List[java.util.Map[String, Any]]])
            if (list != null && !list.isEmpty) {
                // Compute the union of keys across ALL records (first-seen order).
                // Some sources emit variable-shape rows; using only the first
                // record's keys silently drops columns that appear in later records and
                // breaks downstream pipeline schemas built from this list.
                val seen = scala.collection.mutable.LinkedHashSet[String]()
                list.asScala.foreach(row => row.keySet().asScala.foreach(seen.add))
                val allKeys = seen.toList
                val keyMap: Map[String, String] = allKeys.map(k => k -> normalizeColumnName(k)).toMap
                val normalizedCols = new java.util.ArrayList[String](allKeys.map(keyMap).asJava)
                val rewrittenList = new java.util.ArrayList[java.util.Map[String, Any]](list.size())
                list.asScala.foreach { row =>
                    val newRow = new java.util.LinkedHashMap[String, Any]()
                    // Insert in union order so every record has the same key order;
                    // missing keys become null.
                    allKeys.foreach { k =>
                        val normalized = keyMap(k)
                        if (row.containsKey(k)) newRow.put(normalized, row.get(k))
                        else newRow.put(normalized, null)
                    }
                    rewrittenList.add(newRow)
                }
                (gson.toJson(rewrittenList), normalizedCols)
            } else (dataJson, null)
        } catch {
            case e: Exception =>
                logger.warn("Column-name normalization of tap output failed — passing data through unnormalized", e)
                (dataJson, null)
        }
    }

    // ---- HTTP taps: user-hosted endpoint speaking the tap envelope contract -------------------

    /** Envelope `type` values a tap may declare. The Python wrapper's type-sniffing
      * doesn't exist for HTTP taps — the endpoint declares its type explicitly. */
    private val HttpEnvelopeTypes: Set[String] = Set("json", "csv", "xml", "text", "document")

    /** The one secret field forwarded to an HTTP tap endpoint (as a Bearer token).
      * Upstream source credentials belong to the endpoint, never to Datris. */
    private[util] val EndpointTokenField = "endpoint_token"

    private val ContractPointer =
        "See the tap HTTP contract (docs.datris.ai → Taps → HTTP tap contract)."

    /** Execute an HTTP tap: POST the run context to the tap's endpoint and feed the
      * response through the same envelope handling as script taps. No retries at
      * this layer — the cron retry ladder owns retry semantics, and a failed HTTP
      * call provably fed nothing downstream (TapRunner marks script-phase failures
      * retry-safe). */
    private def runHttp(
        tapConfig: TapConfig,
        testLimit: Int,
        params: Map[String, String],
        previousState: String
    ): TapScriptResult = {
        logger.info("TapScriptRunner: executing HTTP tap: " + tapConfig.name + " → " + tapConfig.endpointUrl)
        // The endpoint auth token is the only secret in play; used for masking below.
        var tokenForMasking: Seq[String] = Seq.empty
        try {
            // Endpoint auth. Mirrors the script-tap rule: a tap that DECLARES a secret
            // whose credentials can't be injected is a loud misconfiguration, not a
            // silent unauthenticated call. Only endpoint_token is ever forwarded.
            val endpointToken: Option[String] = if (tapConfig.secretName != null && tapConfig.secretName.nonEmpty) {
                val secretPath = DatrisEnvironment.current.environment + "/" + tapConfig.secretName
                val fields = SecretsUtil.getSecretMap(secretPath)
                    .map(_.asScala.filterNot(_._1 == "_type").toMap)
                    .getOrElse(Map.empty[String, String])
                if (fields.isEmpty)
                    throw new DatrisException(
                        "Tap references secret '" + tapConfig.secretName + "' but no credentials were injected — " +
                            "the secret is missing or empty in the vault. Recreate the secret under Configuration → Secrets, " +
                            "or update the tap to reference an existing secret."
                    )
                val token = fields.get(EndpointTokenField).map(_.trim).filter(_.nonEmpty)
                if (token.isEmpty)
                    throw new DatrisException(
                        "Tap references secret '" + tapConfig.secretName + "' but it has no '" + EndpointTokenField +
                            "' field. HTTP taps send exactly one credential — the secret's '" + EndpointTokenField +
                            "' value, as an Authorization: Bearer header. Add that field to the secret, or remove the " +
                            "secret from the tap if the endpoint needs no auth."
                    )
                token
            } else None
            tokenForMasking = endpointToken.toSeq

            // Request body: the same run context script taps get via env vars.
            val body = new JsonObject()
            body.addProperty("tap", tapConfig.name)
            val paramsObj = new JsonObject()
            params.foreach { case (k, v) =>
                val key = if (k == null) "" else k.trim
                if (key.nonEmpty) {
                    if (ParamKeyPattern.findFirstIn(key).isEmpty)
                        throw new DatrisException(
                            "Invalid tap param key '" + key + "'. Keys must match [A-Za-z_][A-Za-z0-9_]* " +
                                "so they map cleanly onto env var names. Got: " + key
                        )
                    paramsObj.addProperty(key, if (v == null) "" else v)
                }
            }
            body.add("params", paramsObj)
            val stateElem: com.google.gson.JsonElement =
                if (previousState != null && previousState.nonEmpty)
                    try JsonParser.parseString(previousState)
                    catch { case _: Exception => com.google.gson.JsonNull.INSTANCE }
                else com.google.gson.JsonNull.INSTANCE
            body.add("state", stateElem)
            if (testLimit > 0) body.addProperty("testLimit", Integer.valueOf(testLimit))
            else body.add("testLimit", com.google.gson.JsonNull.INSTANCE)

            // SECURITY: reject endpoints that resolve to internal/metadata
            // addresses before connecting, so a tap can't be used to read cloud
            // metadata (169.254.169.254) or internal services. followRedirects
            // is already NEVER, so a redirect can't bypass this check.
            SsrfGuard.assertAllowed(tapConfig.endpointUrl)

            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build()
            val requestBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(tapConfig.endpointUrl))
                .timeout(java.time.Duration.ofSeconds(scriptTimeoutSeconds.toLong))
                .header("Content-Type", "application/json")
                .header("X-Datris-Tap", tapConfig.name)
                .header("User-Agent", "datris-tap/1")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString, java.nio.charset.StandardCharsets.UTF_8))
            endpointToken.foreach(t => requestBuilder.header("Authorization", "Bearer " + t))

            val response =
                try client.send(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                catch {
                    case _: java.net.http.HttpTimeoutException =>
                        throw new DatrisException(
                            "Tap endpoint did not respond within " + scriptTimeoutSeconds + " seconds. " +
                                "Long fetches should be chunked: return one page plus a state cursor and let " +
                                "the next run continue. " + ContractPointer
                        )
                    case e: java.net.ConnectException =>
                        // ConnectException frequently carries a null message; and the
                        // most common cause on a fresh setup is "localhost" meaning
                        // the datris container rather than the user's machine.
                        val host =
                            try java.net.URI.create(tapConfig.endpointUrl).getHost
                            catch { case _: Exception => null }
                        val localhostHint =
                            if (host == "localhost" || host == "127.0.0.1")
                                " Note: Datris runs inside a container, where localhost is the container itself — " +
                                    "if your endpoint runs on the machine hosting Docker, use " +
                                    "http://host.docker.internal:<port>/... instead."
                            else ""
                        val reason = Option(e.getMessage).getOrElse("connection refused")
                        throw new DatrisException(
                            "Could not connect to tap endpoint " + tapConfig.endpointUrl + ": " + reason + "." + localhostHint
                        )
                }

            // Response bodies are untrusted input: stream up to the same output cap
            // script taps get, then abort — never buffer an unbounded body.
            val maxBytes: Long = DatrisEnvironment.current.tapMaxOutputMB.toLong * 1024L * 1024L
            val rawBody: String = {
                val in = response.body()
                try {
                    val buf = new java.io.ByteArrayOutputStream()
                    val chunk = new Array[Byte](64 * 1024)
                    var n = in.read(chunk)
                    while (n >= 0) {
                        buf.write(chunk, 0, n)
                        if (buf.size().toLong > maxBytes)
                            throw new DatrisException(
                                "Tap endpoint response exceeded the " + DatrisEnvironment.current.tapMaxOutputMB +
                                    " MB limit. The whole batch is buffered in memory before loading to the " +
                                    "pipeline, so very large fetches risk OOM-ing the server. Return a smaller " +
                                    "page plus a state cursor and let the next run continue."
                            )
                        n = in.read(chunk)
                    }
                    new String(buf.toByteArray, java.nio.charset.StandardCharsets.UTF_8)
                } finally in.close()
            }

            if (response.statusCode() != 200) {
                // 405 almost always means a GET-only route — the single most
                // common first-contact mistake. Lead with the fix, not the
                // endpoint's HTML error page.
                val methodHint =
                    if (response.statusCode() == 405)
                        "Datris sends the run context as a POST — make sure your endpoint route accepts " +
                            "POST requests (e.g. methods=[\"POST\"] in Flask, @app.post in FastAPI). "
                    else ""
                throw new DatrisException(
                    "Tap endpoint returned HTTP " + response.statusCode() + ". " + methodHint +
                        "Response: " + maskSecrets(rawBody.take(1000), tokenForMasking)
                )
            }

            // Parse the envelope with JsonParser — NOT via a gson Map — so number
            // literals survive verbatim (same rationale as extractStateJson).
            val envelope =
                try JsonParser.parseString(rawBody).getAsJsonObject
                catch {
                    case _: Exception =>
                        throw new DatrisException(
                            "Tap endpoint response is not a JSON envelope. Expected " +
                                "{\"type\": \"json|csv|xml|text|document\", \"data\": ...}. Got: " +
                                maskSecrets(rawBody.take(500), tokenForMasking) + " " + ContractPointer
                        )
                }
            val dataType = Option(envelope.get("type"))
                .filter(e => !e.isJsonNull && e.isJsonPrimitive)
                .map(_.getAsString)
                .getOrElse("")
            if (!HttpEnvelopeTypes.contains(dataType))
                throw new DatrisException(
                    "Tap endpoint envelope is missing a valid \"type\" field (got: " +
                        (if (dataType.isEmpty) "absent" else "'" + dataType + "'") +
                        "). HTTP taps must declare one of json|csv|xml|text|document — there is no " +
                        "type-sniffing on this path. " + ContractPointer
                )
            if (!envelope.has("data") || envelope.get("data").isJsonNull)
                throw new DatrisException(
                    "Tap endpoint envelope has no \"data\" field. Return {\"type\": \"" + dataType +
                        "\", \"data\": [...]} — an empty array is the correct way to report no records. " +
                        ContractPointer
                )

            // Optional logs — the endpoint's stderr equivalent, carried into the run
            // log and masked exactly like script stderr.
            val logs: String = Option(envelope.get("logs"))
                .filter(e => !e.isJsonNull && e.isJsonPrimitive)
                .map(e => maskSecrets(e.getAsString, tokenForMasking))
                .orNull
            if (logs != null && logs.nonEmpty) logger.info("TapScriptRunner: HTTP tap logs:\n" + logs)

            // element.toString preserves the endpoint's exact JSON (integers stay
            // integers); for xml/text the data element is a JSON string and toString
            // yields its quoted form — byte-identical to what the wrapper path stores.
            val dataJson = envelope.get("data").toString
            val recordCount =
                if (dataType == "json" || dataType == "csv" || dataType == "document") countRecords(dataJson) else 1

            val newStateJson: String = extractStateJson(rawBody)
            if (newStateJson != null && newStateJson.length > MaxStateBytes)
                throw new DatrisException(
                    "Tap endpoint emitted a state blob of ~" + (newStateJson.length / 1024) +
                        " KB (limit " + (MaxStateBytes / 1024) + " KB). State is a cursor for the next run — " +
                        "a timestamp, an id watermark, a page token — not a place to store records. " +
                        "Reduce the envelope's \"state\" to the minimal position marker the next run needs."
                )

            val gson = new com.google.gson.Gson
            val (normalizedDataJson, columns): (String, java.util.List[String]) =
                if (dataType == "csv" && recordCount > 0) normalizeCsvColumns(dataJson, gson)
                else (dataJson, null)

            logger.info("TapScriptRunner: HTTP tap dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(
                normalizedDataJson,
                recordCount,
                null,
                logs,
                dataType,
                columns,
                newState = newStateJson
            )
        } catch {
            case e: DatrisException =>
                val masked = maskSecrets(e.getMessage, tokenForMasking)
                logger.error("TapScriptRunner (HTTP tap) failed: " + masked)
                TapScriptResult(null, 0, masked)
            case e: Exception =>
                logger.error("TapScriptRunner (HTTP tap) failed", e)
                val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                TapScriptResult(null, 0, maskSecrets("Tap endpoint request failed: " + reason, tokenForMasking))
        }
    }

    /** Pull the optional `state` object out of the wrapper envelope, preserving the
      * exact JSON representation the script emitted (integer cursors stay integers).
      * Returns null when absent or not an object. private[util] for direct testing. */
    private[util] def extractStateJson(rawOutput: String): String = {
        try {
            val obj = JsonParser.parseString(rawOutput).getAsJsonObject
            if (obj.has("state") && obj.get("state").isJsonObject) obj.get("state").toString else null
        } catch {
            case _: Exception => null
        }
    }

    /** Host/system environment variables a tap script might legitimately read that
      * are NOT meant to come from its secret — never flagged as missing secret
      * fields. Datris-injected vars (DATRIS_* prefix) are excluded separately. */
    private val NonSecretEnvVars: Set[String] = Set(
        "PATH",
        "HOME",
        "USER",
        "LOGNAME",
        "SHELL",
        "PWD",
        "OLDPWD",
        "LANG",
        "TERM",
        "TZ",
        "TMPDIR",
        "TMP",
        "TEMP",
        "HOSTNAME",
        "PYTHONPATH",
        "PYTHONHOME",
        "PYTHONUNBUFFERED",
        "VIRTUAL_ENV",
        "LD_LIBRARY_PATH",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
        "REQUESTS_CA_BUNDLE",
        "CURL_CA_BUNDLE"
    )

    // A read is "required" only when the script provides no fallback: a subscript
    // `os.environ["X"]` (raises KeyError if absent) or a single-argument
    // `os.environ.get("X")` / `os.getenv("X")` (returns None — no default). A
    // two-argument `.get("X", default)` supplies its own fallback and is treated
    // as optional (the trailing `\s*\)` won't match a call that has a comma).
    // Only literal string keys are detected; dynamically built names are left alone.
    private val EnvSubscriptPattern = """os\.environ\[\s*["']([A-Za-z_][A-Za-z0-9_]*)["']\s*\]""".r
    private val EnvGetPattern = """os\.(?:environ\.get|getenv)\(\s*["']([A-Za-z_][A-Za-z0-9_]*)["']\s*\)""".r

    /** Secret field names a tap script requires: every no-fallback env read in the
      * script, minus Datris-injected (DATRIS_*) and host/system variables. Used to
      * catch a secret that EXISTS but lacks a field the script depends on, which
      * would otherwise let the script run and return no data silently. Conservative
      * by design — anything it can't see (computed names, defaulted reads) is not
      * flagged, so it never invents a requirement. */
    private[util] def requiredSecretFields(scriptContent: String): Set[String] = {
        if (scriptContent == null) return Set.empty
        val names =
            EnvSubscriptPattern.findAllMatchIn(scriptContent).map(_.group(1)).toSet ++
                EnvGetPattern.findAllMatchIn(scriptContent).map(_.group(1)).toSet
        names.filterNot(n => n.startsWith("DATRIS_") || NonSecretEnvVars.contains(n))
    }

    /** Install any tap-declared extra packages into a throwaway venv and return its
      * directory (the interpreter is <dir>/bin/python3). Returns None when the tap declares
      * no extra packages — the run then uses the system python3, which already carries the
      * pre-baked package set (see Dockerfile).
      *
      * SECURITY (tap-execution-isolation Phase 5): never `pip install --break-system-packages`
      * into the shared, externally-managed system environment. The runtime user is non-root
      * (USER datris) and cannot write system site-packages anyway; a venv is writable, isolates
      * one tap's dependencies from the system and from other taps, and needs no
      * --break-system-packages (a venv is not externally-managed). --system-site-packages keeps
      * the pre-baked packages importable without reinstalling them. The venv-create and pip steps
      * run with a SCRUBBED environment (runScrubbed) because a package's setup.py is third-party
      * code that must not see platform secrets either. */
    private def installPackages(tapConfig: TapConfig): Option[Path] = {
        if (tapConfig.packages == null || tapConfig.packages.isEmpty) return None

        val packages = tapConfig.packages.asScala.toSeq
        logger.info("TapScriptRunner: installing packages into venv: " + packages.mkString(" "))

        val venvDir = Files.createTempDirectory("tap_venv_")
        try {
            val (venvCode, venvOut) = runScrubbed(Seq("python3", "-m", "venv", "--system-site-packages", venvDir.toString))
            if (venvCode != 0)
                throw new DatrisException("Failed to create package venv: " + venvOut.take(500))

            val pip = venvDir.resolve("bin").resolve("pip").toString
            val (pipCode, pipOut) = runScrubbed(Seq(pip, "install", "--quiet") ++ packages)
            if (pipCode != 0)
                throw new DatrisException("Failed to install pip packages: " + pipOut.take(500))

            Some(venvDir)
        } catch {
            case e: Throwable =>
                deleteRecursively(venvDir)
                throw e
        }
    }

    /** Run a build helper (venv create, pip install) with the SAME scrubbed environment a tap
      * gets — start from empty, restore only the benign system-var allowlist (NonSecretEnvVars),
      * inject no platform secrets. Returns (exitCode, combined stdout+stderr). */
    private def runScrubbed(cmd: Seq[String]): (Int, String) = {
        val pb = new java.lang.ProcessBuilder(cmd: _*)
        val childEnv = pb.environment()
        childEnv.clear()
        NonSecretEnvVars.foreach { k => sys.env.get(k).foreach(v => childEnv.put(k, v)) }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.getOutputStream.close()
        val out = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        val code = proc.waitFor()
        (code, out)
    }

    /** Recursively delete a directory tree (venv), best-effort. */
    private def deleteRecursively(dir: Path): Unit = {
        try {
            if (dir != null && Files.exists(dir)) {
                Files.walk(dir).sorted(java.util.Comparator.reverseOrder[Path]())
                    .forEach(p => { Files.deleteIfExists(p); () })
            }
        } catch {
            case e: Exception =>
                logger.debug("Best-effort cleanup of venv directory " + dir + " failed", e)
                ()
        }
    }

    // ---- Phase 3: isolated sidecar execution -------------------------------------------------
    // When USE_TAP_RUNNER is set, tap code runs in the datris-tap-runner container, which holds
    // no platform secrets and has no route to Vault. Default off so the OSS quick-start keeps
    // in-process execution with zero extra dependencies.
    private def useTapRunner: Boolean =
        sys.env.getOrElse("USE_TAP_RUNNER", "false").equalsIgnoreCase("true")
    private def tapRunnerUrl: String =
        sys.env.getOrElse("TAP_RUNNER_URL", "http://datris-tap-runner:8090")
    private def tapRunnerToken: String = sys.env.getOrElse("TAP_RUNNER_TOKEN", "")
    // Host a tap should use to call back into the platform when running in the sidecar
    // (the datris server's name on tap-net). Replaces "localhost", which in the runner
    // would point at the runner itself.
    private def tapRunnerCallbackHost: String = sys.env.getOrElse("TAP_RUNNER_CALLBACK_HOST", "datris")

    /** Execute a tap in the datris-tap-runner sidecar instead of in-process. Sends the script,
      * wrapper, per-run env (allEnvVars: platform DATRIS_*, params, the tap's own secret) and any
      * declared packages; returns (stdout, stderr) with the same contract as executeWithTimeout.
      * The runner installs packages and runs the wrapper itself, so no local venv/temp files. */
    private def executeViaRunner(
        script: String,
        envVars: Seq[(String, String)],
        packages: java.util.List[String],
        timeoutSec: Int,
        secretValues: Seq[String]
    ): (String, String) = {
        val payload = new JsonObject()
        payload.addProperty("script", script)
        payload.addProperty("wrapper", WRAPPER_TEMPLATE)
        payload.addProperty("timeoutSec", Integer.valueOf(timeoutSec))
        val envObj = new JsonObject()
        envVars.foreach { case (k, v) => envObj.addProperty(k, if (v == null) "" else v) }
        payload.add("env", envObj)
        val pkgArr = new JsonArray()
        if (packages != null) packages.asScala.foreach(p => pkgArr.add(p))
        payload.add("packages", pkgArr)

        // Give the HTTP call headroom beyond the tap's wall-clock timeout so the runner's own
        // timeout (not the socket) is what fires on a slow tap.
        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setConnectionRequestTimeout(10000)
            .setSocketTimeout((timeoutSec + 30) * 1000)
            .build()
        val httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()
        try {
            val post = new HttpPost(tapRunnerUrl + "/execute")
            post.addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            if (tapRunnerToken.nonEmpty) post.addHeader("Authorization", "Bearer " + tapRunnerToken)
            post.setEntity(new StringEntity(payload.toString, java.nio.charset.StandardCharsets.UTF_8))

            val response = httpClient.execute(post)
            try {
                val status = response.getStatusLine.getStatusCode
                val raw = EntityUtils.toString(response.getEntity, java.nio.charset.StandardCharsets.UTF_8)
                if (status != 200)
                    throw new DatrisException("Tap runner returned " + status + ": " + maskSecrets(raw.take(500), secretValues))
                val obj = JsonParser.parseString(raw).getAsJsonObject
                if (obj.has("timedOut") && obj.get("timedOut").getAsBoolean)
                    throw new DatrisException("Tap script timed out after " + timeoutSec + " seconds")
                val exitCode = if (obj.has("exitCode")) obj.get("exitCode").getAsInt else -1
                def str(k: String) = if (obj.has(k) && !obj.get(k).isJsonNull) obj.get(k).getAsString else ""
                val stdout = str("stdout")
                val stderr = str("stderr")
                if (exitCode != 0) {
                    val errOutput = maskSecrets(stderr.take(1000), secretValues)
                    logger.error("Tap script (runner) exited with code " + exitCode + ": " + errOutput)
                    throw new DatrisException("Tap script failed (exit code " + exitCode + "): " + errOutput)
                }
                (stdout.trim, stderr.trim)
            } finally response.close()
        } catch {
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException("Tap runner request failed: " + maskSecrets(e.getMessage, secretValues))
        } finally {
            httpClient.close()
        }
    }

    private def executeWithTimeout(
        python: String,
        wrapperPath: String,
        scriptPath: String,
        timeoutSec: Int,
        envVars: Seq[(String, String)] = Seq.empty,
        secretValues: Seq[String] = Seq.empty
    ): (String, String) = {
        val stdout = new StringBuilder
        val stderr = new StringBuilder

        // SECURITY (tap-execution-isolation Phase 1): run the tap with java.lang.ProcessBuilder
        // rather than scala.sys.process.Process. scala.sys.process ADDS its extra env onto the
        // inherited JVM environment, so a tap's os.environ would otherwise contain every var the
        // server container holds — VAULT_TOKEN, ANTHROPIC_API_KEY / OPENAI_API_KEY, DB passwords,
        // etc. ProcessBuilder lets us start from an empty environment and add back only:
        //   (a) a minimal allowlist of benign system vars the Python runtime + TLS need
        //       (PATH, HOME, LANG, SSL cert paths, PYTHON*, … — reusing NonSecretEnvVars), and
        //   (b) the explicitly-built per-run vars (platform DATRIS_*, tap params, the tap's own
        //       secret) passed in as envVars.
        // Anything not on those two lists — every platform secret — is absent from os.environ.
        // Back-compat: a tap that (incorrectly) relied on an ambient host var outside the
        // allowlist will no longer see it; that is exactly the surface being closed, and
        // requiredSecretFields() already steers such reads toward the tap's secret instead.
        val pb = new java.lang.ProcessBuilder(python, wrapperPath, scriptPath)
        val childEnv = pb.environment()
        childEnv.clear()
        NonSecretEnvVars.foreach { k => sys.env.get(k).foreach(v => childEnv.put(k, v)) }
        envVars.foreach { case (k, v) => if (k != null && v != null) childEnv.put(k, v) }

        val process = pb.start()
        process.getOutputStream.close() // tap reads no stdin; signal EOF

        // Drain stdout and stderr on separate threads — a full pipe buffer would otherwise
        // deadlock the child. Each thread owns its own buffer; the main thread reads them only
        // after join(), which establishes the happens-before, so no extra synchronization.
        def pump(in: java.io.InputStream, sb: StringBuilder): Thread = {
            val t = new Thread(new Runnable {
                override def run(): Unit = {
                    val reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)
                    )
                    try {
                        var line = reader.readLine()
                        while (line != null) { sb.append(line).append("\n"); line = reader.readLine() }
                    } catch { case _: java.io.IOException => () }
                    finally reader.close()
                }
            })
            t.setDaemon(true)
            t.start()
            t
        }
        val outThread = pump(process.getInputStream, stdout)
        val errThread = pump(process.getErrorStream, stderr)

        val future = Future { process.waitFor() }
        try {
            val exitCode = Await.result(future, timeoutSec.seconds)
            outThread.join(5000)
            errThread.join(5000)
            if (exitCode != 0) {
                val errOutput = maskSecrets(stderr.toString.take(1000), secretValues)
                logger.error("Tap script exited with code " + exitCode + ": " + errOutput)
                throw new DatrisException("Tap script failed (exit code " + exitCode + "): " + errOutput)
            }
            (stdout.toString.trim, stderr.toString.trim)
        } catch {
            case _: java.util.concurrent.TimeoutException =>
                process.destroyForcibly()
                throw new DatrisException("Tap script timed out after " + timeoutSec + " seconds")
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException("Tap script execution error: " + maskSecrets(e.getMessage, secretValues))
        }
    }

    /** Spell-out mapping for special characters that have semantic meaning in
      * column names. Each spell-out is wrapped in underscores so word
      * boundaries are preserved regardless of surrounding context (e.g.
      * `Surprise%` and `Surprise(%)` both yield `surprise_percent`). The
      * collapse + strip steps in `normalizeColumnName` clean up duplicates.
      *
      * Order doesn't matter functionally, but is grouped here by semantic theme.
      */
    private val SPELL_OUT_CHARS: Seq[(String, String)] = Seq(
        // Math / comparison
        "%" -> "_percent_",
        "+" -> "_plus_",
        "*" -> "_star_",
        "^" -> "_pow_",
        "=" -> "_equals_",
        "<" -> "_lt_",
        ">" -> "_gt_",
        "/" -> "_per_",
        // Currency / units
        "$" -> "_dollars_",
        "#" -> "_num_",
        // Logical / connector
        "&" -> "_and_",
        "@" -> "_at_",
        "~" -> "_approx_",
        "!" -> "_bang_"
    )

    /** Convert a source column name into a SQL-safe identifier matching
      * PipelineValidatorUtil's `[A-Za-z0-9_]+` regex.
      *
      * Steps:
      *   1. Spell out semantically meaningful special characters (see
      *      SPELL_OUT_CHARS) into `_word_` tokens.
      *   2. Replace any remaining run of non-`[A-Za-z0-9_]` characters with `_`.
      *   3. Collapse runs of `_` into a single `_`.
      *   4. Strip leading/trailing `_`.
      *   5. Lowercase.
      *   6. Fall back to `"col"` if the result is empty (e.g. input was `___`).
      *
      * Examples:
      *   "EPS Estimate"  -> "eps_estimate"
      *   "Reported EPS"  -> "reported_eps"
      *   "Surprise(%)"   -> "surprise_percent"
      *   "Surprise%"     -> "surprise_percent"   (no-paren form)
      *   "Order#"        -> "order_num"
      *   "miles/hour"    -> "miles_per_hour"
      *   "R&D Spending"  -> "r_and_d_spending"
      *   "id"            -> "id"                 (no-op for already-clean names)
      */
    private[util] def normalizeColumnName(name: String): String = {
        if (name == null || name.isEmpty) return name
        var s = name
        SPELL_OUT_CHARS.foreach { case (ch, word) => s = s.replace(ch, word) }
        s = s.replaceAll("[^A-Za-z0-9_]+", "_")
        s = s.replaceAll("_+", "_")
        s = s.stripPrefix("_").stripSuffix("_")
        s = s.toLowerCase
        if (s.isEmpty) "col" else s
    }

    private def maskSecrets(text: String, secretValues: Seq[String]): String = {
        if (text == null || text.isEmpty) return text
        secretValues.foldLeft(text) { (acc, secret) =>
            if (secret == null || secret.length < 4) acc
            else acc.replace(secret, "••••••••")
        }
    }

    private def countRecords(json: String): Int = {
        if (json == null || json.isEmpty) return 0
        val trimmed = json.trim
        if (!trimmed.startsWith("[")) return 0
        val gson = new com.google.gson.Gson
        val list = gson.fromJson(trimmed, classOf[java.util.List[Any]])
        if (list == null) 0 else list.size()
    }
}
