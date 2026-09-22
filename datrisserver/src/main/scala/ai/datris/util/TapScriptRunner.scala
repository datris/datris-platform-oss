package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, StagedFormat, StagedPayload, TapConfig}
import org.slf4j.{Logger, LoggerFactory}
import com.google.gson.stream.{JsonReader, JsonToken}
import com.google.gson.{GsonBuilder, JsonArray, JsonElement, JsonObject, JsonParser}
import org.apache.http.HttpHeaders
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** What a tap run produced. `staged` is the payload on disk (plans/stories/
  * streaming-pipeline-phase4.md): NDJSON with `arraySource = true` for a record
  * list, one line for a single object, XML / text verbatim with rowCount 1;
  * null on error. The file lives in the tap run's own StagingArea token
  * directory until TapRunner hands it to StreamNotifier (which adopts it into
  * the pipeline run) or releases it. */
case class TapScriptResult(
    staged: StagedPayload,
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

    /** The wall-clock ceiling in force for one execution, plus the knob that
      * raises it. A tap TEST is bounded by tapScriptTimeoutSeconds
      * (TAP_SCRIPT_TIMEOUT_SECONDS, default 300) so a bad script fails fast; a
      * real or cron run gets tapRunTimeoutSeconds (TAP_RUN_TIMEOUT_SECONDS,
      * default 3600) so a big streaming source can actually finish. */
    case class TapTimeout(seconds: Int, envVar: String, label: String)

    /** Only mode == "test" is bounded by the short test ceiling. Cron runs
      * arrive as mode = "run" from TapScheduler, and anything a caller has not
      * thought of (null, "", "manual") lands on the run ceiling too — never
      * silently on the short one. */
    private[util] def timeoutFor(mode: String): TapTimeout = {
        val env = DatrisEnvironment.current
        if (mode != null && mode.trim.equalsIgnoreCase("test"))
            TapTimeout(env.tapScriptTimeoutSeconds, "TAP_SCRIPT_TIMEOUT_SECONDS", "test")
        else
            TapTimeout(env.tapRunTimeoutSeconds, "TAP_RUN_TIMEOUT_SECONDS", "run")
    }

    /** The one place both timeout throws (the sidecar's `timedOut` trailer and
      * the in-process deadline) get their text from, so an isolated deployment
      * and a local run say exactly the same thing. */
    private[util] def timedOutMessage(t: TapTimeout): String =
        if (t.label == "test")
            s"Tap script timed out after ${t.seconds} seconds (${t.label} mode; raise ${t.envVar})"
        else
            s"Tap script timed out after ${t.seconds} seconds (${t.label} mode; raise ${t.envVar}, " +
                "or chunk the source range via run_tap params)"

    /** Boot-time resolution of the run ceiling. `rawRunTimeoutSeconds` is -1
      * when TAP_RUN_TIMEOUT_SECONDS / tapRunTimeoutSeconds is blank
      * (StartupRunner.optionalInt); an unset run ceiling is
      * max(3600, tapScriptTimeoutSeconds), so an install that raised the old
      * single knob to make long real runs work never gets a SHORTER one. */
    def resolveRunTimeoutSeconds(rawRunTimeoutSeconds: Int, scriptTimeoutSeconds: Int): Int =
        if (rawRunTimeoutSeconds >= 0) rawRunTimeoutSeconds else math.max(3600, scriptTimeoutSeconds)

    // private[util] so TapWrapperStateSpec can execute the real wrapper against
    // fixture scripts — the wrapper is the wire format, and a drift here breaks
    // every tap shape at once.
    private[util] val WRAPPER_TEMPLATE =
        """import json, sys, os, time, itertools, importlib.util
          |# Redirect script's print() output to stderr so only JSON goes to stdout.
          |# Wrapper-emitted lifecycle lines (prefixed [wrapper]) also go to stderr so
          |# every run has some log content even when the user's script is silent.
          |_real_stdout = sys.stdout
          |sys.stdout = sys.stderr
          |# Platform-callback auth. The platform mints a per-run token (DATRIS_PLATFORM_TOKEN)
          |# so a script can read platform data through DATRIS_PLATFORM_HOST with no credential
          |# of its own even when API keys are required. Attach it transparently to requests /
          |# urllib calls aimed at the platform host — and ONLY that host — so existing scripts
          |# keep working unchanged and the token never travels to a third-party API.
          |_dp_token = os.environ.get("DATRIS_PLATFORM_TOKEN", "")
          |_dp_host = os.environ.get("DATRIS_PLATFORM_HOST", "")
          |if _dp_token and _dp_host:
          |    from urllib.parse import urlsplit as _dp_urlsplit
          |    def _dp_is_platform(url):
          |        try:
          |            return (_dp_urlsplit(str(url)).hostname or "").lower() == _dp_host.lower()
          |        except Exception:
          |            return False
          |    try:
          |        import requests as _dp_requests
          |        _dp_orig_request = _dp_requests.Session.request
          |        def _dp_request(self, method, url, *args, **kwargs):
          |            if _dp_is_platform(url):
          |                headers = dict(kwargs.get("headers") or {})
          |                if not any(k.lower() == "x-api-key" for k in headers):
          |                    headers["x-api-key"] = _dp_token
          |                kwargs["headers"] = headers
          |            return _dp_orig_request(self, method, url, *args, **kwargs)
          |        _dp_requests.Session.request = _dp_request
          |    except ImportError:
          |        pass
          |    try:
          |        import urllib.request as _dp_urllib
          |        import socket as _dp_socket
          |        _dp_orig_open = _dp_urllib.OpenerDirector.open
          |        def _dp_open(self, fullurl, data=None, timeout=_dp_socket._GLOBAL_DEFAULT_TIMEOUT):
          |            req = fullurl if isinstance(fullurl, _dp_urllib.Request) else _dp_urllib.Request(fullurl)
          |            if _dp_is_platform(req.full_url) and not req.has_header("X-api-key"):
          |                req.add_unredirected_header("x-api-key", _dp_token)
          |            return _dp_orig_open(self, req, data, timeout)
          |        _dp_urllib.OpenerDirector.open = _dp_open
          |    except Exception:
          |        pass
          |print("[wrapper] loading tap script", flush=True)
          |spec = importlib.util.spec_from_file_location("tap", sys.argv[1])
          |mod = importlib.util.module_from_spec(spec)
          |spec.loader.exec_module(mod)
          |print("[wrapper] calling fetch()", flush=True)
          |_t0 = time.time()
          |result = mod.fetch()
          |_elapsed = time.time() - _t0
          |# stdout stays redirected for the WHOLE run: a generator's body (and its
          |# print() calls) executes during the lookahead and the write loop below,
          |# not inside mod.fetch(). Only the envelope goes to the real stdout.
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
          |# Detect data type from result. _records is the record sequence to serialize
          |# record-at-a-time on the file path (None for a single value / string).
          |# _dict_lane: the first record was a plain dict, so non-dict rows are
          |# dropped (today's list behaviour) and keys are stringified per row.
          |_records = None
          |_dict_lane = False
          |_iter_src = None
          |# Iterator lane (taps survive large sources): fetch() may `yield` records or
          |# return any iterator. A one-item lookahead sniffs the type exactly like the
          |# list branch; the rest streams in one pass. str / bytes / dict / list /
          |# tuple keep their own branches below — only real iterators come here.
          |if not isinstance(result, (list, tuple, str, bytes, dict)) and hasattr(result, "__next__"):
          |    _iter_src = result
          |    try:
          |        _first = next(_iter_src)
          |    except StopIteration:
          |        data_type = "json"
          |        _records = iter(())
          |        _dict_lane = True
          |    else:
          |        if isinstance(_first, dict) and 'uri' in _first and 'content' in _first:
          |            data_type = "document"
          |        elif isinstance(_first, dict):
          |            data_type = "json"
          |            _dict_lane = True
          |        elif isinstance(_first, (list, tuple)):
          |            data_type = "csv"
          |        else:
          |            data_type = "json"
          |        _records = itertools.chain((_first,), _iter_src)
          |    # Test mode caps the ITERATOR lane at N records (exactly N pulled, then
          |    # the generator is closed so its finally runs). A list is never truncated.
          |    _tl = os.environ.get("DATRIS_TAP_TEST_LIMIT", "").strip()
          |    if _tl.isdigit() and int(_tl) > 0:
          |        _records = itertools.islice(_records, int(_tl))
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], dict) and 'uri' in result[0] and 'content' in result[0]:
          |    # Document tap: list of {uri, filename, content (base64), ...}
          |    data_type = "document"
          |    _records = result
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], dict):
          |    data_type = "json"
          |    _records = result
          |    _dict_lane = True
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], (list, tuple)):
          |    data_type = "csv"
          |    _records = result
          |elif isinstance(result, str):
          |    trimmed = result.strip()
          |    if trimmed.startswith("<?xml") or trimmed.startswith("<"):
          |        data_type = "xml"
          |    elif trimmed.startswith("{") or trimmed.startswith("["):
          |        data_type = "json"
          |    else:
          |        data_type = "text"
          |elif isinstance(result, list):
          |    data_type = "json"
          |    _records = result
          |else:
          |    data_type = "json"
          |_out_path = os.environ.get("DATRIS_TAP_OUTPUT")
          |if _out_path:
          |    # File path (streaming-pipeline Phase 4): the records go to the file the
          |    # platform named, one compact JSON value per line, and stdout carries only
          |    # a small envelope {type, count, columns, state}. The whole-payload JSON
          |    # string is never built. The file is created even for 0 records. It is
          |    # opened for write only (a FIFO in the sidecar) and never removed here.
          |    # `columns` is the union of record keys in first-seen order for a list of
          |    # dicts, [] for any other list, and null for a single value — so the
          |    # platform can tell a record list from one object without re-reading.
          |    _count = 0
          |    _columns = None
          |    with open(_out_path, "w", encoding="utf-8", newline="") as _f:
          |        if _records is not None:
          |            # One pass, whether _records is a list or a streaming iterator:
          |            # write the row, count it, grow the columns union, stringify
          |            # dict keys per row (Timestamp / numpy keys). The whole result
          |            # is never held here.
          |            _columns = {}
          |            _dicts = True
          |            for _row in _records:
          |                if isinstance(_row, dict):
          |                    _row = {str(_k): _v for _k, _v in _row.items()}
          |                    if _dicts:
          |                        for _k in _row:
          |                            if _k not in _columns:
          |                                _columns[_k] = True
          |                elif _dict_lane:
          |                    continue
          |                else:
          |                    _dicts = False
          |                _f.write(json.dumps(_row, default=str, separators=(",", ":")))
          |                _f.write("\n")
          |                _count += 1
          |            _columns = list(_columns) if _dicts else []
          |            if _iter_src is not None:
          |                if hasattr(_iter_src, "close"):
          |                    _iter_src.close()
          |                _elapsed = time.time() - _t0
          |        elif data_type in ("xml", "text"):
          |            # Verbatim, not JSON-quoted. A raw 0x1E byte is stripped: it is the
          |            # sidecar's record/trailer separator and can never be payload.
          |            _f.write(result.replace("\x1e", ""))
          |            _count = 1
          |        else:
          |            # A single JSON value (dict, scalar) is one line, count 1. A string
          |            # that merely looks like JSON stays a string, as on the inline path,
          |            # where it counts as 0 records.
          |            _f.write(json.dumps(result, default=str, separators=(",", ":")))
          |            _f.write("\n")
          |            _count = 0 if isinstance(result, str) else 1
          |    if isinstance(_columns, list):
          |        print(f"[wrapper] fetch() returned {_count} {data_type} record(s) in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |    else:
          |        print(f"[wrapper] fetch() returned 1 {data_type} payload in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |    envelope = {"type": data_type, "count": _count, "columns": _columns}
          |else:
          |    # Inline path (no DATRIS_TAP_OUTPUT): the whole payload rides on stdout as
          |    # `data`, exactly as before the file path existed. It is heap-bound by
          |    # construction, so an iterator is materialised here (only the file path
          |    # streams); the stderr note says why.
          |    if _iter_src is not None:
          |        print("[wrapper] DATRIS_TAP_OUTPUT is not set: fetch() returned an iterator, materializing it in memory (set DATRIS_TAP_OUTPUT to stream it to a file)", file=sys.stderr, flush=True)
          |        result = list(_records)
          |        if hasattr(_iter_src, "close"):
          |            _iter_src.close()
          |        _records = result
          |        _elapsed = time.time() - _t0
          |    if _dict_lane:
          |        # Normalize dict keys to strings for the whole-payload json.dumps
          |        # (handles Timestamp, numpy keys); non-dict rows are dropped as before.
          |        result = [{str(k): v for k, v in row.items()} for row in result if isinstance(row, dict)]
          |        _records = result
          |    if data_type == "document":
          |        data = json.dumps(result, default=str)
          |    elif data_type == "json" and _records is not None and len(_records) > 0 and isinstance(_records[0], dict):
          |        data = json.dumps(result, default=str)
          |    else:
          |        data = json.dumps(result)
          |    # Lifecycle summary on stderr - visible in tap-run logs whether the script
          |    # printed anything or not.
          |    if data_type in ("json", "csv", "document") and isinstance(json.loads(data), list):
          |        _count = len(json.loads(data))
          |        print(f"[wrapper] fetch() returned {_count} {data_type} record(s) in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |    else:
          |        print(f"[wrapper] fetch() returned 1 {data_type} payload in {_elapsed:.2f}s", file=sys.stderr, flush=True)
          |    envelope = {"type": data_type, "data": json.loads(data) if data_type in ("json", "csv", "document") else data}
          |# Incremental-sync state: a script that wants the platform to remember its
          |# position sets a module-global dict DATRIS_STATE inside fetch(). Absent or
          |# non-dict -> no state key, and the previously committed state stays put.
          |_new_state = getattr(mod, "DATRIS_STATE", None)
          |if _new_state is not None and not isinstance(_new_state, dict):
          |    print(f"[wrapper] DATRIS_STATE ignored: expected dict, got {type(_new_state).__name__}", file=sys.stderr, flush=True)
          |    _new_state = None
          |if _new_state is not None:
          |    envelope["state"] = json.loads(json.dumps(_new_state, default=str))
          |    print("[wrapper] fetch() emitted state: " + json.dumps(envelope["state"], default=str)[:200], file=sys.stderr, flush=True)
          |print(json.dumps(envelope), file=_real_stdout, flush=True)
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
        previousState: String = null,
        mode: String = "run"
    ): TapScriptResult = {
        // HTTP taps run zero code on the platform: the "script" is a user-hosted
        // endpoint speaking the same envelope contract, so the entire Python lane
        // (storage read, wrapper, venv/pip, sidecar, secret-field inference) is
        // skipped and everything downstream of the envelope is shared.
        if (tapConfig.isHttp) return runHttp(tapConfig, testLimit, params, previousState, mode)

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
        runScript(tapConfig, scriptContent, testLimit, params, previousState, mode)
    }

    /** Element-at-a-time re-serialization must not alter the data (same
      * settings as PayloadStager): keep nulls, keep `<`/`>`/`&` unescaped. */
    private val stagingGson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    /** Token-directory name of a staged tap payload, or null. */
    private[util] def stagingTokenOf(staged: StagedPayload): String =
        if (staged == null || staged.path == null) null
        else Option(Paths.get(staged.path).getParent).map(_.getFileName.toString).orNull

    /** Drop the tap run's own staging directory (the one `runScript` / `runHttp`
      * bound). Called by TapRunner after the last StreamNotifier hand-off, and by
      * the API layer after a test-mode preview. Never throws. */
    def release(result: TapScriptResult): Unit =
        if (result != null) StagingArea.delete(stagingTokenOf(result.staged))

    /** The body of [[run]] after the storage read: secret/env assembly, execution
      * (sidecar or in-process), envelope parsing and staging. Binds its own
      * StagingArea token — a failed run leaves nothing under the staging root. */
    private[util] def runScript(
        tapConfig: TapConfig,
        scriptContent: String,
        testLimit: Int = 0,
        params: Map[String, String] = Map.empty,
        previousState: String = null,
        mode: String = "run"
    ): TapScriptResult = {
        val tapToken = "tap-" + java.util.UUID.randomUUID().toString
        val result = StagingArea.withToken(tapToken)(runScriptStaged(tapConfig, scriptContent, testLimit, params, previousState, mode))
        if (result.error != null) StagingArea.delete(tapToken)
        result
    }

    private def runScriptStaged(
        tapConfig: TapConfig,
        scriptContent: String,
        testLimit: Int,
        params: Map[String, String],
        previousState: String,
        mode: String
    ): TapScriptResult = {
        // Which ceiling this execution runs under, and the knob that raises it.
        val timeout = timeoutFor(mode)
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

        // Per-run credential for the platform callback (see TapRunTokens). Minted
        // here, revoked in finally — it must not outlive the run.
        var platformToken: String = null

        // Secret field(s) the script requires (reads from the env with no fallback)
        // that the referenced secret does NOT provide. Carried out on TapScriptResult
        // so TapRunner can turn an otherwise-graceful 0-record run into a failure with
        // a precise cause. We do NOT fail before running on this — a successful run
        // proves the script had what it needed, so the signal is only acted on when
        // the run also produced no records (see TapRunner).
        var detectedMissingSecretFields: Seq[String] = Nil

        // Where the wrapper writes its records (DATRIS_TAP_OUTPUT): a file in the
        // tap run's staging directory. The sidecar streams into it; in-process the
        // wrapper writes it directly.
        val outputPath: Path = StagingArea.newFile("tap", StagedFormat.NdJson)

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
            // Per-run token so the callback authenticates when API keys are on.
            // The wrapper attaches it to platform-host requests automatically;
            // it resolves server-side to a read-only `tap:<name>` identity.
            platformToken = ai.datris.auth.TapRunTokens.issue(
                tapConfig.name,
                if (DatrisEnvironment.current.multiTenant) Some(DatrisEnvironment.current.environment) else None,
                timeout.seconds + 60
            )
            val platformEnvVars = Seq(
                "DATRIS_POSTGRES_DATABASE" -> DatrisEnvironment.current.postgresDatabase,
                "DATRIS_MONGODB_DATABASE" -> mongoDatabase,
                "DATRIS_PLATFORM_HOST" -> platformHost,
                "DATRIS_PLATFORM_PORT" -> "8080",
                "DATRIS_PLATFORM_TOKEN" -> platformToken
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

            // Step 5: Execute the wrapper — isolated sidecar (compose/prod default) or
            // in-process (sbt/IDE, or USE_TAP_RUNNER=false). Same allEnvVars and envelope.
            // The platform token is masked like any other secret so it never
            // surfaces in run logs or error messages.
            val secretValues = secretEnvVars.map(_._2) :+ platformToken
            secretValuesForMasking = secretValues
            val (rawOutput, rawLogs) =
                if (useTapRunner) {
                    executeViaRunner(scriptContent, allEnvVars, tapConfig.packages, timeout, secretValues, outputPath)
                } else {
                    warnInProcess("tap " + tapConfig.name)
                    // In-process path: materialize the script/wrapper, install any extra
                    // packages into a throwaway venv, and run with the chosen interpreter.
                    Files.write(scriptFile, scriptContent.getBytes("UTF-8"))
                    Files.write(wrapperFile, WRAPPER_TEMPLATE.getBytes("UTF-8"))
                    venvDir = installPackages(tapConfig)
                    val python = venvDir.map(_.resolve("bin").resolve("python3").toString).getOrElse("python3")
                    executeWithTimeout(
                        python,
                        wrapperFile.toString,
                        scriptFile.toString,
                        timeout,
                        allEnvVars :+ ("DATRIS_TAP_OUTPUT" -> outputPath.toString),
                        secretValues,
                        outputPath
                    )
                }
            // Mask secret values in logs before they're persisted (TapRunLog), surfaced via
            // get_tap_logs / the run-history UI, or echoed to the platform's own logger.
            // A script's print() that incidentally includes an API key would otherwise leak
            // through the run history to anyone with tap access.
            val logs = if (rawLogs.nonEmpty) maskSecrets(rawLogs, secretValues) else rawLogs
            logger.info("TapScriptRunner: script executed, envelope length: " + rawOutput.length + " chars")
            if (logs.nonEmpty) logger.info("TapScriptRunner: script logs:\n" + logs)

            // Disk budget on what the wrapper wrote (the sidecar and in-process paths
            // already stop a run mid-write; this covers the final size).
            val writtenBytes = if (Files.exists(outputPath)) Files.size(outputPath) else 0L
            if (StagingArea.overBudget(writtenBytes)) throw new DatrisException(StagingArea.budgetExceededMessage(writtenBytes))

            // Step 5: Parse the envelope — only {type, count, columns, state} (the
            // records are in outputPath). An older runner still answers with the
            // inline `data` envelope, which is staged from the string instead.
            val envelope: JsonObject =
                try JsonParser.parseString(rawOutput).getAsJsonObject
                catch {
                    case _: Exception =>
                        throw new DatrisException(
                            "Tap script produced no result envelope on stdout (the wrapper exited before it could report). " +
                                "stdout: " + maskSecrets(rawOutput.take(300), secretValues)
                        )
                }
            val dataType = Option(envelope.get("type")).filter(e => e.isJsonPrimitive).map(_.getAsString).getOrElse("json")

            // Optional incremental-sync state emitted by the script (wrapper puts it on
            // the envelope only when the script set a dict DATRIS_STATE). Extracted with
            // JsonParser — NOT via a gson Map — so number literals survive verbatim: a
            // Map path turns every JSON number into a Double and would re-serialize an
            // integer cursor 1785850779844 as 1785850779844.0, which a script
            // interpolating it into a source URL would send malformed. Oversized state
            // is a script bug — a cursor should be bytes, not a payload.
            val newStateJson: String = extractStateJson(rawOutput)
            if (newStateJson != null && newStateJson.length > MaxStateBytes) {
                throw new DatrisException(
                    "Tap script emitted a DATRIS_STATE blob of ~" + (newStateJson.length / 1024) +
                        " KB (limit " + (MaxStateBytes / 1024) + " KB). State is a cursor for the next run — " +
                        "a timestamp, an id watermark, a page token — not a place to store records. " +
                        "Reduce DATRIS_STATE to the minimal position marker the next run needs."
                )
            }

            val (staged, columnsHint): (StagedPayload, Option[List[String]]) =
                if (envelope.has("data")) {
                    // Legacy inline envelope (older datris-tap-runner): stage the data element.
                    Files.deleteIfExists(outputPath)
                    (stageInlineData(envelope.get("data"), dataType), None)
                } else {
                    if (!Files.exists(outputPath))
                        throw new DatrisException("Tap script reported a result but wrote no output file (" + outputPath.getFileName + ")")
                    val count = Option(envelope.get("count")).filter(_.isJsonPrimitive).map(_.getAsLong).getOrElse(0L)
                    val columns: Option[List[String]] = Option(envelope.get("columns"))
                        .filter(_.isJsonArray)
                        .map(_.getAsJsonArray.asScala.map(_.getAsString).toList)
                    (describeStagedOutput(outputPath, dataType, count, isList = columns.isDefined), columns)
                }
            val recordCount = if (dataType == "json" || dataType == "csv" || dataType == "document") staged.rowCount.toInt else 1

            // For CSV-shaped data: normalize column names so they pass PipelineValidatorUtil
            // (which only allows [A-Za-z0-9_]+) and so downstream SQL doesn't need quoting.
            // Rewrites BOTH the records (key by key) and the extracted columns array.
            // No-op for json/xml/text — those go to mongo destinations as raw blobs.
            val (normalizedStaged, columns): (StagedPayload, java.util.List[String]) =
                if (dataType == "csv" && recordCount > 0) normalizeCsvColumns(staged, columnsHint)
                else (staged, null)

            logger.info("TapScriptRunner: dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(
                normalizedStaged,
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
            ai.datris.auth.TapRunTokens.revoke(platformToken)
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
            venvDir.foreach(deleteRecursively)
        }
    }

    /** Describe the file the wrapper wrote as a staged payload. Record lists are
      * NDJSON with `arraySource`; a single JSON value is one NDJSON line without
      * it; xml / text are verbatim with rowCount 1. The file is renamed to the
      * format's extension. */
    private def describeStagedOutput(outputPath: Path, dataType: String, count: Long, isList: Boolean): StagedPayload = {
        val bytes = Files.size(outputPath)
        val format: StagedFormat = dataType match {
            case "xml" => StagedFormat.Xml
            case "text" => StagedFormat.Text
            case _ => StagedFormat.NdJson
        }
        val path =
            if (format == StagedFormat.NdJson) outputPath
            else {
                val renamed = outputPath.resolveSibling(outputPath.getFileName.toString.stripSuffix(".ndjson") + "." + format.extension)
                Files.move(outputPath, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                renamed
            }
        format match {
            case StagedFormat.NdJson => StagedPayload(path.toString, format, count, bytes, arraySource = isList)
            case _ => StagedPayload(path.toString, format, if (bytes > 0) 1L else 0L, bytes)
        }
    }

    /** Stage the `data` element of a legacy inline envelope (older tap runner or
      * HTTP endpoint that answered with everything in one JSON body). */
    private def stageInlineData(data: JsonElement, dataType: String): StagedPayload = {
        if (data == null || data.isJsonNull) return StagedPayload.empty
        if (data.isJsonPrimitive && data.getAsJsonPrimitive.isString && (dataType == "xml" || dataType == "text"))
            PayloadStager.stageText("tap", if (dataType == "xml") StagedFormat.Xml else StagedFormat.Text, data.getAsString)
        else if (data.isJsonPrimitive && data.getAsJsonPrimitive.isString)
            // A string under a json/csv/document type counted as 0 records before (not an array).
            PayloadStager.stageJson("tap", new java.io.StringReader(data.toString)).copy(rowCount = 0L)
        else PayloadStager.stageJson("tap", new java.io.StringReader(data.toString))
    }

    /** For CSV-shaped data: normalize column names so they pass PipelineValidatorUtil
      * (which only allows [A-Za-z0-9_]+) and so downstream SQL doesn't need quoting.
      * Rewrites BOTH the records (key by key) and the extracted columns array, as a
      * streaming pass over the staged NDJSON file: the key union comes from the
      * wrapper envelope's `columns` when present, else from a first pass over the
      * lines. Every rewritten record carries every union key, in union order, JSON
      * null when absent; numbers are re-serialized verbatim. Shared by the script
      * and HTTP paths; no-op fallback (original file, null columns) on any parse
      * trouble — e.g. a csv payload whose rows are arrays, not objects. */
    private def normalizeCsvColumns(staged: StagedPayload, columnsHint: Option[List[String]]): (StagedPayload, java.util.List[String]) = {
        if (staged == null || staged.isEmpty || staged.rowCount == 0 || staged.format != StagedFormat.NdJson) return (staged, null)
        var rewrittenPath: Path = null
        try {
            // Compute the union of keys across ALL records (first-seen order).
            // Some sources emit variable-shape rows; using only the first
            // record's keys silently drops columns that appear in later records and
            // breaks downstream pipeline schemas built from this list.
            val allKeys: List[String] = columnsHint.getOrElse {
                val seen = scala.collection.mutable.LinkedHashSet[String]()
                val it = StagedRows.lines(staged.path)
                try while (it.hasNext) JsonParser.parseString(it.next()).getAsJsonObject.keySet().asScala.foreach(seen.add)
                finally it.close()
                seen.toList
            }
            if (allKeys.isEmpty) return (staged, null)
            val keyMap: Map[String, String] = allKeys.map(k => k -> normalizeColumnName(k)).toMap
            val normalizedCols = new java.util.ArrayList[String](allKeys.map(keyMap).asJava)

            val (path, writer) = StagingArea.newWriter("tap", StagedFormat.NdJson)
            rewrittenPath = path
            var count = 0L
            val it = StagedRows.lines(staged.path)
            try {
                while (it.hasNext) {
                    val row = JsonParser.parseString(it.next()).getAsJsonObject
                    val newRow = new JsonObject()
                    // Insert in union order so every record has the same key order;
                    // missing keys become null.
                    allKeys.foreach { k =>
                        val normalized = keyMap(k)
                        if (row.has(k)) newRow.add(normalized, row.get(k))
                        else newRow.add(normalized, com.google.gson.JsonNull.INSTANCE)
                    }
                    if (count > 0) writer.write("\n")
                    writer.write(stagingGson.toJson(newRow))
                    count += 1
                }
            } finally {
                it.close()
                writer.close()
            }
            Files.deleteIfExists(Paths.get(staged.path))
            (StagedPayload(path.toString, StagedFormat.NdJson, count, Files.size(path), arraySource = true), normalizedCols)
        } catch {
            case e: Exception =>
                logger.warn("Column-name normalization of tap output failed — passing data through unnormalized", e)
                if (rewrittenPath != null) Files.deleteIfExists(rewrittenPath)
                (staged, null)
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
        previousState: String,
        mode: String
    ): TapScriptResult = {
        // Own staging token, like runScript: a failed call leaves nothing on disk.
        val tapToken = "tap-" + java.util.UUID.randomUUID().toString
        val result = StagingArea.withToken(tapToken)(runHttpStaged(tapConfig, testLimit, params, previousState, mode))
        if (result.error != null) StagingArea.delete(tapToken)
        result
    }

    /** An InputStream that remembers its first bytes (so an error message can
      * quote an excerpt of a response that was otherwise streamed to disk) and
      * counts what it hands out against a bound: the response body is untrusted,
      * and whatever is NOT streamed element-by-element (a single-document `data`
      * string, `logs`, `state`) is materialized in heap by the JSON reader, so it
      * must never be unbounded. `bound` is the per-run materialization cap until
      * the caller sees `data` is an array and raises it to the disk budget. */
    private class BoundedPrefixInputStream(in: java.io.InputStream, prefixLimit: Int, onOverflow: Long => DatrisException)
        extends java.io.FilterInputStream(in) {
        private val prefix = new java.io.ByteArrayOutputStream()
        @volatile var bound: Long = Long.MaxValue
        private var count: Long = 0L
        private def account(n: Int): Unit = {
            count += n
            if (count > bound) throw onOverflow(count)
        }
        private def remember(b: Array[Byte], off: Int, len: Int): Unit =
            if (prefix.size() < prefixLimit) prefix.write(b, off, math.min(len, prefixLimit - prefix.size()))
        override def read(): Int = {
            val b = in.read()
            if (b >= 0) {
                if (prefix.size() < prefixLimit) prefix.write(b)
                account(1)
            }
            b
        }
        override def read(b: Array[Byte], off: Int, len: Int): Int = {
            val n = in.read(b, off, len)
            if (n > 0) {
                remember(b, off, n)
                account(n)
            }
            n
        }
        def excerpt: String = new String(prefix.toByteArray, StandardCharsets.UTF_8)
    }

    private def runHttpStaged(
        tapConfig: TapConfig,
        testLimit: Int,
        params: Map[String, String],
        previousState: String,
        mode: String
    ): TapScriptResult = {
        // Same two ceilings as the script lane: a test fails fast, a real run
        // gets the hour.
        val timeout = timeoutFor(mode)
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
                .timeout(java.time.Duration.ofSeconds(timeout.seconds.toLong))
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
                            "Tap endpoint did not respond within " + timeout.seconds + " seconds (" +
                                timeout.label + " mode; raise " + timeout.envVar + "). " +
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

            if (response.statusCode() != 200) {
                // Read a bounded excerpt only — the body is untrusted and unbounded.
                val excerpt = {
                    val in = response.body()
                    try {
                        val buf = new Array[Byte](1024)
                        var total = 0
                        var n = in.read(buf, 0, buf.length)
                        while (n > 0 && total < buf.length) {
                            total += n
                            n = if (total < buf.length) in.read(buf, total, buf.length - total) else 0
                        }
                        new String(buf, 0, total, StandardCharsets.UTF_8)
                    } finally in.close()
                }
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
                        "Response: " + maskSecrets(excerpt.take(1000), tokenForMasking)
                )
            }

            // Walk the envelope with a JsonReader: the `data` array is written to a
            // staged NDJSON file element by element (never held whole), a `data`
            // string is staged verbatim, `state` is captured with JsonParser so
            // number literals survive verbatim (same rationale as extractStateJson),
            // `type` / `logs` as before. The disk budget is enforced on bytes written.
            // Bound: the materialization cap while the body may be a single document
            // (PIPELINE_MATERIALIZE_MAX_MB, as any whole-payload read); raised to the
            // disk budget once `data` is seen to be an array, which streams.
            val materializeBytes: Long = StagingArea.materializeMaxMB.toLong * 1024L * 1024L
            var dataIsArray = false
            var dataSeen = false
            val responseBody = new BoundedPrefixInputStream(
                response.body(),
                512,
                bytes =>
                    if (dataIsArray) new DatrisException(StagingArea.budgetExceededMessage(bytes))
                    else
                        new DatrisException(
                            "Tap endpoint response " + (if (dataSeen) "is a single document" else "prefix before \"data\" is") + " of more than " + StagingArea.materializeMaxMB +
                                " MB, which is read whole into memory (" + StagingArea.MaterializeCapEnvVar + " = " +
                                StagingArea.materializeMaxMB + " MB). Raise it for this install, or return the payload as a " +
                                "\"data\" array of records, which streams to disk under " + StagingArea.PayloadBudgetEnvVar + "."
                        )
            )
            responseBody.bound = materializeBytes
            var dataType = ""
            var logs: String = null
            var newStateJson: String = null
            var dataNull = false
            var dataString: String = null
            var arrayStaged: StagedPayload = null
            def notAnEnvelope(): DatrisException =
                new DatrisException(
                    "Tap endpoint response is not a JSON envelope. Expected " +
                        "{\"type\": \"json|csv|xml|text|document\", \"data\": ...}. Got: " +
                        maskSecrets(responseBody.excerpt.take(500), tokenForMasking) + " " + ContractPointer
                )
            try {
                val reader = new JsonReader(new java.io.InputStreamReader(responseBody, StandardCharsets.UTF_8))
                reader.setLenient(true)
                try {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) throw notAnEnvelope()
                    reader.beginObject()
                    while (reader.hasNext) {
                        reader.nextName() match {
                            case "type" =>
                                if (reader.peek() == JsonToken.STRING) dataType = reader.nextString() else reader.skipValue()
                            case "logs" =>
                                if (reader.peek() == JsonToken.STRING) logs = maskSecrets(reader.nextString(), tokenForMasking) else reader.skipValue()
                            case "state" =>
                                val st = JsonParser.parseReader(reader)
                                newStateJson = if (st.isJsonObject) st.toString else null
                            case "data" =>
                                dataSeen = true
                                reader.peek() match {
                                    case JsonToken.NULL =>
                                        reader.nextNull()
                                        dataNull = true
                                    case JsonToken.BEGIN_ARRAY =>
                                        dataIsArray = true
                                        responseBody.bound = if (StagingArea.payloadBudgetBytes > 0) StagingArea.payloadBudgetBytes else Long.MaxValue
                                        arrayStaged = stageJsonArray(reader)
                                    case JsonToken.STRING =>
                                        dataString = reader.nextString()
                                    case _ =>
                                        // A single object / number / boolean: one NDJSON line.
                                        val element = JsonParser.parseReader(reader)
                                        arrayStaged = PayloadStager.stageJson("tap", new java.io.StringReader(stagingGson.toJson(element)))
                                }
                            case _ => reader.skipValue()
                        }
                    }
                    reader.endObject()
                } finally reader.close()
            } catch {
                case e: DatrisException => throw e
                case _: com.google.gson.JsonParseException | _: java.io.IOException | _: IllegalStateException | _: NumberFormatException =>
                    throw notAnEnvelope()
            }

            if (!HttpEnvelopeTypes.contains(dataType))
                throw new DatrisException(
                    "Tap endpoint envelope is missing a valid \"type\" field (got: " +
                        (if (dataType.isEmpty) "absent" else "'" + dataType + "'") +
                        "). HTTP taps must declare one of json|csv|xml|text|document — there is no " +
                        "type-sniffing on this path. " + ContractPointer
                )
            if (!dataSeen || dataNull)
                throw new DatrisException(
                    "Tap endpoint envelope has no \"data\" field. Return {\"type\": \"" + dataType +
                        "\", \"data\": [...]} — an empty array is the correct way to report no records. " +
                        ContractPointer
                )

            // Optional logs — the endpoint's stderr equivalent, carried into the run
            // log and masked exactly like script stderr.
            if (logs != null && logs.nonEmpty) logger.info("TapScriptRunner: HTTP tap logs:\n" + logs)

            val staged: StagedPayload =
                if (arrayStaged != null) arrayStaged
                else if (dataType == "xml" || dataType == "text")
                    PayloadStager.stageText("tap", if (dataType == "xml") StagedFormat.Xml else StagedFormat.Text, dataString)
                else
                    // A string under a json/csv/document type is not a record list: 0
                    // records, exactly as the wrapper and legacy lanes count it (and as
                    // countRecords did on main) — a double-encoded array is not parsed.
                    PayloadStager.stageJson("tap", new java.io.StringReader(stagingGson.toJson(dataString))).copy(rowCount = 0L)
            if (StagingArea.overBudget(staged.bytes)) throw new DatrisException(StagingArea.budgetExceededMessage(staged.bytes))
            val recordCount =
                if (dataType == "json" || dataType == "csv" || dataType == "document") staged.rowCount.toInt else 1

            if (newStateJson != null && newStateJson.length > MaxStateBytes)
                throw new DatrisException(
                    "Tap endpoint emitted a state blob of ~" + (newStateJson.length / 1024) +
                        " KB (limit " + (MaxStateBytes / 1024) + " KB). State is a cursor for the next run — " +
                        "a timestamp, an id watermark, a page token — not a place to store records. " +
                        "Reduce the envelope's \"state\" to the minimal position marker the next run needs."
                )

            val (normalizedStaged, columns): (StagedPayload, java.util.List[String]) =
                if (dataType == "csv" && recordCount > 0) normalizeCsvColumns(staged, None)
                else (staged, null)

            logger.info("TapScriptRunner: HTTP tap dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(
                normalizedStaged,
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

    /** Stream the JSON array the reader is positioned on into a staged NDJSON
      * file, one compact element per line, stopping the run when the bytes
      * written pass the disk budget. */
    private def stageJsonArray(reader: JsonReader): StagedPayload = {
        val (path, writer) = StagingArea.newWriter("tap", StagedFormat.NdJson)
        var count = 0L
        var written = 0L
        try {
            reader.beginArray()
            while (reader.hasNext) {
                val line = stagingGson.toJson(JsonParser.parseReader(reader))
                if (count > 0) { writer.write("\n"); written += 1 }
                writer.write(line)
                written += line.getBytes(StandardCharsets.UTF_8).length
                count += 1
                if (StagingArea.overBudget(written)) throw new DatrisException(StagingArea.budgetExceededMessage(written))
            }
            reader.endArray()
        } finally writer.close()
        StagedPayload(path.toString, StagedFormat.NdJson, count, Files.size(path), arraySource = true)
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
    // no platform secrets and has no route to Vault. Compose/prod defaults the flag on;
    // sbt/IDE without the sidecar leaves it unset (in-process).
    private val WeakTapRunnerTokens = Set("", "changeme-tap-runner-token", "change-me-to-a-long-random-string")
    private val TapRunnerTokenFile = sys.env.getOrElse("TAP_RUNNER_TOKEN_FILE", "/tap-runner-token/token")

    private[datris] def useTapRunner: Boolean =
        sys.env.getOrElse("USE_TAP_RUNNER", "false").equalsIgnoreCase("true")
    private def tapRunnerUrl: String =
        sys.env.getOrElse("TAP_RUNNER_URL", "http://datris-tap-runner:8090")
    private def envTapRunnerToken: String = sys.env.getOrElse("TAP_RUNNER_TOKEN", "")
    private def isWeakTapRunnerToken(t: String): Boolean =
        t == null || WeakTapRunnerTokens.contains(t.trim)
    private def readMintedTapRunnerToken(): Option[String] = {
        try {
            val p = java.nio.file.Paths.get(TapRunnerTokenFile)
            if (!java.nio.file.Files.isRegularFile(p)) None
            else {
                val s = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8).trim
                if (s.isEmpty) None else Some(s)
            }
        } catch { case _: Exception => None }
    }

    /** Env token if it is a real minted value; otherwise the vault-init file. */
    private[datris] def resolvedTapRunnerToken: String = {
        val envTok = envTapRunnerToken
        if (!isWeakTapRunnerToken(envTok)) envTok
        else readMintedTapRunnerToken().filterNot(isWeakTapRunnerToken).getOrElse(envTok)
    }
    private def tapRunnerToken: String = resolvedTapRunnerToken
    private[datris] def assertIsolationConfig(): Unit = {
        if (useTapRunner && isWeakTapRunnerToken(resolvedTapRunnerToken))
            throw new DatrisException(
                "USE_TAP_RUNNER=true but TAP_RUNNER_TOKEN is empty or the changeme default. " +
                    "scripts/install.sh and docker/vault-init.sh mint a token on first boot."
            )
    }
    private[datris] def warnInProcess(where: String): Unit = {
        logger.warn(
            "************************************************************************\n" +
                "TAP EXECUTION IS IN-PROCESS (" + where + "; USE_TAP_RUNNER is not true).\n" +
                "fetch() shares this JVM network and can reach DB / MinIO / Vault directly.\n" +
                "Compose/prod should isolate (USE_TAP_RUNNER=true). sbt/IDE without a sidecar is expected.\n" +
                "************************************************************************"
        )
    }
    // Host a tap should use to call back into the platform when running in the sidecar
    // (the datris server's name on tap-net). Replaces "localhost", which in the runner
    // would point at the runner itself.
    private def tapRunnerCallbackHost: String = sys.env.getOrElse("TAP_RUNNER_CALLBACK_HOST", "datris")

    /** Record/trailer separator of the sidecar's streaming response (see tap-runner/app.py). */
    private val RecordTrailerSentinel: Byte = 0x1e

    /** Execute a tap in the datris-tap-runner sidecar instead of in-process. Sends the script,
      * wrapper, per-run env (allEnvVars: platform DATRIS_*, params, the tap's own secret) and any
      * declared packages with `recordsStream: true`; the runner points DATRIS_TAP_OUTPUT at a FIFO
      * and streams the record bytes back in a chunked response followed by a 0x1E-prefixed trailer
      * {stdout, stderr, exitCode, timedOut}. The bytes are written to `outputPath` as they arrive
      * (disk budget enforced on the way); returns (stdout, stderr) with the same contract as
      * executeWithTimeout. An older runner answers `application/json` with everything inline —
      * that legacy body is returned as-is (its `data` envelope is staged by the caller). */
    private def executeViaRunner(
        script: String,
        envVars: Seq[(String, String)],
        packages: java.util.List[String],
        timeout: TapTimeout,
        secretValues: Seq[String],
        outputPath: Path
    ): (String, String) = {
        val timeoutSec = timeout.seconds
        val payload = new JsonObject()
        payload.addProperty("script", script)
        payload.addProperty("wrapper", WRAPPER_TEMPLATE)
        payload.addProperty("timeoutSec", Integer.valueOf(timeoutSec))
        payload.addProperty("recordsStream", java.lang.Boolean.TRUE)
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
            post.setEntity(new StringEntity(payload.toString, StandardCharsets.UTF_8))

            val response = httpClient.execute(post)
            try {
                val status = response.getStatusLine.getStatusCode
                val contentType = Option(response.getEntity).flatMap(e => Option(e.getContentType)).map(_.getValue.toLowerCase).getOrElse("")
                val streamed = status == 200 && !contentType.contains("application/json")
                val obj: JsonObject =
                    if (!streamed) {
                        val raw = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
                        if (status != 200)
                            throw new DatrisException("Tap runner returned " + status + ": " + maskSecrets(raw.take(500), secretValues))
                        JsonParser.parseString(raw).getAsJsonObject
                    } else {
                        // Streamed: record bytes up to the first 0x1E go to the staged file;
                        // the rest is the trailer.
                        val trailer = new java.io.ByteArrayOutputStream()
                        val in = response.getEntity.getContent
                        val out = new java.io.BufferedOutputStream(Files.newOutputStream(outputPath))
                        var written = 0L
                        try {
                            try streamRecords(
                                    in,
                                    out,
                                    trailer,
                                    b => {
                                        written += b; if (StagingArea.overBudget(written)) throw new DatrisException(StagingArea.budgetExceededMessage(written))
                                    }
                                )
                            catch {
                                case e: Exception =>
                                    // Drop the socket without draining what the runner is still
                                    // sending (in.close() would read the rest to EOF); the runner's
                                    // write then fails and it kills the tap process.
                                    post.abort()
                                    throw e
                            }
                        } finally {
                            out.close()
                            try in.close()
                            catch { case _: Exception => () }
                        }
                        JsonParser.parseString(new String(trailer.toByteArray, StandardCharsets.UTF_8).trim).getAsJsonObject
                    }
                if (obj.has("timedOut") && obj.get("timedOut").getAsBoolean)
                    throw new DatrisException(timedOutMessage(timeout))
                val exitCode = if (obj.has("exitCode")) obj.get("exitCode").getAsInt else -1
                def str(k: String) = if (obj.has(k) && !obj.get(k).isJsonNull) obj.get(k).getAsString else ""
                val stdout = str("stdout")
                val stderr = str("stderr")
                if (exitCode != 0) {
                    val errOutput = maskSecrets(stderr.take(1000), secretValues)
                    logger.error("Tap script (runner) exited with code " + exitCode + ": " + errOutput)
                    throw new DatrisException(scriptFailureMessage(exitCode, errOutput))
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

    /** Copy the streamed sidecar response: bytes before the first 0x1E go to
      * `out` (reporting each written count to `onWritten`), the rest to `trailer`. */
    private def streamRecords(in: java.io.InputStream, out: java.io.OutputStream, trailer: java.io.ByteArrayOutputStream, onWritten: Int => Unit): Unit = {
        val buf = new Array[Byte](64 * 1024)
        var inTrailer = false
        var n = in.read(buf)
        while (n >= 0) {
            var off = 0
            if (!inTrailer) {
                var i = 0
                while (i < n && buf(i) != RecordTrailerSentinel) i += 1
                if (i > 0) {
                    out.write(buf, 0, i)
                    onWritten(i)
                }
                if (i < n) { inTrailer = true; off = i + 1 }
                else off = n
            }
            if (inTrailer && off < n) trailer.write(buf, off, n - off)
            n = in.read(buf)
        }
        if (!inTrailer)
            throw new DatrisException("Tap runner response ended without a result trailer (the runner may have died mid-run)")
    }

    private def executeWithTimeout(
        python: String,
        wrapperPath: String,
        scriptPath: String,
        timeout: TapTimeout,
        envVars: Seq[(String, String)] = Seq.empty,
        secretValues: Seq[String] = Seq.empty,
        outputPath: Path = null
    ): (String, String) = {
        val timeoutSec = timeout.seconds
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
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8)
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

        try {
            // Wait in short steps so the run can be stopped as soon as the file the
            // wrapper is writing (DATRIS_TAP_OUTPUT) passes the disk budget, not
            // only when it exits.
            val deadline = System.currentTimeMillis() + timeoutSec.toLong * 1000L
            var exitCode: Option[Int] = None
            while (exitCode.isEmpty) {
                if (process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) exitCode = Some(process.exitValue())
                else if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly()
                    throw new DatrisException(timedOutMessage(timeout))
                } else if (outputPath != null && Files.exists(outputPath)) {
                    val written = Files.size(outputPath)
                    if (StagingArea.overBudget(written)) {
                        process.destroyForcibly()
                        throw new DatrisException(StagingArea.budgetExceededMessage(written))
                    }
                }
            }
            outThread.join(5000)
            errThread.join(5000)
            if (exitCode.get != 0) {
                val errOutput = maskSecrets(stderr.toString.take(1000), secretValues)
                logger.error("Tap script exited with code " + exitCode.get + ": " + errOutput)
                throw new DatrisException(scriptFailureMessage(exitCode.get, errOutput))
            }
            (stdout.toString.trim, stderr.toString.trim)
        } catch {
            case e: DatrisException => throw e
            case e: Exception =>
                process.destroyForcibly()
                throw new DatrisException("Tap script execution error: " + maskSecrets(e.getMessage, secretValues))
        }
    }

    /** The failure message for a non-zero exit that did not time out — one helper
      * for both lanes so the wording is identical. A SIGKILLed child arrives as
      * -9 from the sidecar (Python's negative signal number) or 137 from the
      * in-process ProcessBuilder lane (128 + 9); neither is a script traceback,
      * and with timeouts and the disk budget already handled upstream it is, in
      * practice, the kernel OOM killer taking a script that materialised its
      * whole result. Say so and name the fix, keeping the code and the stderr
      * tail visible. */
    private[util] def scriptFailureMessage(exitCode: Int, errOutput: String): String =
        if (exitCode == -9 || exitCode == 137)
            "Tap script was killed (exit code " + exitCode + "), almost certainly out of memory: fetch() must not build the whole " +
                "result in memory — yield records instead (see tap-workflow-reference). Last output: " + errOutput
        else "Tap script failed (exit code " + exitCode + "): " + errOutput

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
}
