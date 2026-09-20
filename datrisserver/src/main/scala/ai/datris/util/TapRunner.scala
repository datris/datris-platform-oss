package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, GlobalJobContext, StagedFormat, StagedPayload, TapConfig, TapDocumentLedger, TapFeedInfo, TapRunLog}
import ai.datris.controller.{JobRunner, StreamNotifier}
import com.google.gson.{Gson, JsonArray, JsonElement, JsonParser, JsonPrimitive}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedInputStream, ByteArrayInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone, UUID}

object TapRunner {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /**
     * Execute a tap: run the script, feed results to the target pipeline.
     *
     * @param tapConfig the tap to run
     * @param mode "run" persists to the pipeline and updates tap status; "test" just executes and returns without persisting
     * @param params per-run params injected as DATRIS_TAP_PARAM_<key> env vars (date range, id list, etc.)
     * @param trigger what initiated the run: "cron" (TapScheduler) or "manual" (UI/API/MCP).
     *                Persisted on the tap so the scheduler only auto-retries cron failures.
     * @return TapScriptResult with fetched records
     */
    def run(
        tapConfig: TapConfig,
        mode: String = "run",
        testLimit: Int = 0,
        params: Map[String, String] = Map.empty,
        trigger: String = "manual"
    ): TapScriptResult = {
        val push = mode == "run"
        val publisherToken = if (push) UUID.randomUUID().toString else null
        val sdf = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        sdf.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        val now = sdf.format(new Date())
        val startMs = System.currentTimeMillis()
        // Flipped just before the first pipeline submission. A failure with this
        // still false provably fed nothing downstream, so an automatic re-run
        // cannot double-write (the retry-safety gate).
        var feedStarted = false
        // The script result, so the finally can drop the tap run's own staging
        // directory once the last StreamNotifier hand-off is done (real runs).
        // Test runs keep it: the API layer previews the staged file, then releases.
        var scriptResult: TapScriptResult = null

        // Only update status in DB for real runs, not tests
        if (push) {
            val runningConfig = tapConfig.copy(lastRunStatus = "running", lastRunTime = now, lastRunError = null, lastRunTrigger = trigger)
            TapConfigIO.write(runningConfig)
        }

        try {
            // Incremental-sync state from the last committed run (null on first run /
            // non-incremental taps). Read for test runs too — a test should exercise
            // the same window a real run would — but only real successful runs commit.
            val previousState: String =
                try TapStateIO.readStateJson(tapConfig.name)
                catch {
                    case e: Exception =>
                        logger.warn("TapRunner: state read failed for tap: " + tapConfig.name + " — running without state: " + e.getMessage)
                        null
                }

            val result = TapScriptRunner.run(tapConfig, testLimit, params, previousState)
            scriptResult = result
            val durationMs = System.currentTimeMillis() - startMs

            if (result.error != null) {
                // Script errored. This is a real failure — write it as such.
                // Script failures happen before any pipeline submission → retry-safe.
                if (push) {
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = result.error,
                        lastRunTrigger = trigger,
                        lastRunRetrySafe = true
                    )
                    TapConfigIO.write(failedConfig)
                }
                writeRunLog(
                    tapConfig.name,
                    now,
                    "failure",
                    result.recordCount,
                    result.dataType,
                    result.logs,
                    result.error,
                    mode,
                    durationMs,
                    scriptCommitSha = tapConfig.scriptCommitSha
                )
                return result
            }

            if (result.recordCount == 0) {
                // 0 records can be legitimate (nothing new since last run) OR it can be
                // a silent misconfiguration: the secret exists but is missing a field the
                // script reads, so the script ran unauthenticated and returned nothing.
                // We only treat the latter as a failure — i.e. when the script requires a
                // credential field the secret does not provide. A run that produced records
                // never reaches here, so this can't second-guess a working tap.
                if (result.missingSecretFields.nonEmpty) {
                    val missingMsg =
                        "Tap returned 0 records: its script reads credential field(s) that secret '" +
                            tapConfig.secretName + "' does not provide — " + result.missingSecretFields.mkString(", ") +
                            ". The script ran without those credentials and returned no data. Add the missing field(s) to " +
                            "the secret (Configuration → Secrets), or update the script to match the secret."
                    if (push) {
                        // Structural (secret/script mismatch) — a retry hits the
                        // same wall, so it is not marked retry-safe.
                        val failedConfig = tapConfig.copy(
                            lastRunStatus = "failure",
                            lastRunTime = now,
                            lastRunRecordCount = 0,
                            lastRunError = missingMsg,
                            lastRunTrigger = trigger,
                            lastRunRetrySafe = false
                        )
                        TapConfigIO.write(failedConfig)
                    }
                    writeRunLog(
                        tapConfig.name,
                        now,
                        "failure",
                        0,
                        result.dataType,
                        result.logs,
                        missingMsg,
                        mode,
                        durationMs,
                        scriptCommitSha = tapConfig.scriptCommitSha
                    )
                    return result.copy(error = missingMsg)
                }
                // Script ran cleanly but returned nothing. This is a legitimate
                // outcome for polling taps (no new data since last run), incremental
                // taps that have caught up, weekend/holiday market data, filters
                // that found nothing today. Treating it as a failure inflates the
                // Failures tile in Ops Activity, fires bogus "recovered" badges,
                // and trains agents to interpret "no new data" as "platform broken."
                // Record it as `no_records` instead — distinct status, not counted
                // as a failure, agent-visible via get_tap_logs.
                if (push) {
                    val noRecordsConfig = tapConfig.copy(
                        lastRunStatus = "no_records",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = null,
                        lastRunTrigger = trigger,
                        lastRunRetrySafe = false,
                        retryCount = 0
                    )
                    TapConfigIO.write(noRecordsConfig)
                    // "Caught up, nothing new" is a legitimate cursor advance (e.g. a
                    // checked-through timestamp). A script that emitted no state simply
                    // leaves the bookmark where it was.
                    commitState(tapConfig, result, now)
                }
                writeRunLog(
                    tapConfig.name,
                    now,
                    "no_records",
                    0,
                    result.dataType,
                    result.logs,
                    null,
                    mode,
                    durationMs,
                    scriptCommitSha = tapConfig.scriptCommitSha
                )
                return result
            }

            // Push to pipeline if requested, records exist, and a target pipeline is configured
            val (processedCount, pipelineTokens) =
                if (
                    push && result.staged != null && !result.staged.isEmpty && result.recordCount > 0 &&
                    tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty
                ) {
                    feedStarted = true
                    feedPipeline(tapConfig, result, publisherToken, TapFeedInfo(tapConfig.name, now, tapConfig.scriptCommitSha, declaredSource(tapConfig)))
                } else (result.recordCount, new java.util.ArrayList[String]())

            if (push) {
                val successConfig = tapConfig.copy(
                    lastRunStatus = "success",
                    lastRunTime = now,
                    lastRunRecordCount = processedCount,
                    lastRunError = null,
                    lastRunDataType = result.dataType,
                    lastRunColumns = result.columns,
                    lastRunTrigger = trigger,
                    lastRunRetrySafe = false,
                    retryCount = 0
                )
                TapConfigIO.write(successConfig)
                // Commit point for incremental state: the feed was accepted. Failure
                // paths never reach here, so a failed run leaves the old cursor and
                // the retry ladder re-fetches the same window (at-least-once; upsert
                // destinations absorb the overlap).
                commitState(tapConfig, result, now)
            }

            val tokensOut = if (pipelineTokens.isEmpty) null else pipelineTokens
            val pubOut = if (tokensOut == null) null else publisherToken
            writeRunLog(
                tapConfig.name,
                now,
                "success",
                processedCount,
                result.dataType,
                result.logs,
                null,
                mode,
                durationMs,
                pubOut,
                scriptCommitSha = tapConfig.scriptCommitSha
            )
            result.copy(publisherToken = pubOut, pipelineTokens = tokensOut)
        } catch {
            case e: Exception =>
                val durationMs = System.currentTimeMillis() - startMs
                logger.error("TapRunner failed for tap: " + tapConfig.name, e)
                if (push) {
                    // Retry-safe only if the exception fired before the first
                    // pipeline submission; a mid-feed failure (e.g. a document
                    // batch that partially fed) must not be re-run blindly.
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = e.getMessage,
                        lastRunTrigger = trigger,
                        lastRunRetrySafe = !feedStarted
                    )
                    TapConfigIO.write(failedConfig)
                }
                writeRunLog(tapConfig.name, now, "failure", 0, null, null, e.getMessage, mode, durationMs, scriptCommitSha = tapConfig.scriptCommitSha)
                TapScriptResult(null, 0, e.getMessage)
        } finally {
            // Real runs: every StreamNotifier hand-off has adopted (moved) what it
            // needs out of the tap's staging directory, so drop the directory now.
            if (push) TapScriptRunner.release(scriptResult)
        }
    }

    /** Release the tap run's staging directory (test-mode callers, after the preview). */
    def release(result: TapScriptResult): Unit = TapScriptRunner.release(result)

    /** A preview of a test run's records read off the staged file: the first
      * `limit` records of an NDJSON record list as a JSON array (with a
      * truncated flag when there were more), the single value of a one-object
      * payload, or the verbatim text of an xml / text payload (capped at 1 MB).
      * Returns (null, false) when there is nothing staged. */
    def preview(staged: StagedPayload, limit: Int): (JsonElement, Boolean) = {
        if (staged == null || staged.isEmpty) return (null, false)
        staged.format match {
            case StagedFormat.NdJson =>
                val it = StagedRows.lines(staged.path)
                try {
                    if (!staged.arraySource) {
                        (if (it.hasNext) JsonParser.parseString(it.next()) else null, false)
                    } else {
                        val arr = new JsonArray()
                        var n = 0
                        while (it.hasNext && n < limit) { arr.add(JsonParser.parseString(it.next())); n += 1 }
                        (arr, it.hasNext)
                    }
                } finally it.close()
            case StagedFormat.Xml | StagedFormat.Text =>
                val cap = 1024L * 1024L
                val in = Files.newInputStream(Paths.get(staged.path))
                try {
                    val bytes = in.readNBytes(cap.toInt)
                    (new JsonPrimitive(new String(bytes, StandardCharsets.UTF_8)), staged.bytes > cap)
                } finally in.close()
            case _ => (null, false)
        }
    }

    /** Persist the state blob a run emitted. Only called from the success and
      * no_records paths of real runs (push) — the commit-on-success rule that
      * makes incremental sync hole-free. Best-effort: the data already fed, so a
      * failed commit must not fail the run; the next run just re-fetches. */
    private def commitState(tapConfig: TapConfig, result: TapScriptResult, runTime: String): Unit = {
        if (result.newState == null) return
        try {
            TapStateIO.write(TapState(tapConfig.name, result.newState, runTime, tapConfig.name + "|" + runTime))
            logger.info("TapRunner: committed incremental state for tap: " + tapConfig.name)
        } catch {
            case e: Exception =>
                logger.warn("TapRunner: state commit failed for tap: " + tapConfig.name +
                    " — next run re-fetches the same window: " + e.getMessage)
        }
    }

    private def writeRunLog(
        tapName: String,
        runTime: String,
        status: String,
        recordCount: Int,
        dataType: String,
        logs: String,
        error: String,
        mode: String,
        durationMs: Long,
        publisherToken: String = null,
        scriptCommitSha: String = null
    ): Unit = {
        try {
            val log =
                TapRunLog(tapName, runTime, status, recordCount, dataType, logs, error, mode, durationMs, publisherToken, scriptCommitSha = scriptCommitSha)
            val gson = new Gson
            val key = tapName + "|" + runTime
            // Stamp top-level created_at so the Ops activity dashboard can do an
            // indexed time-range scan across all taps without per-tap fan-out.
            // Old rows without created_at are simply absent from the new endpoint;
            // they'll roll off the dashboard window naturally.
            val nowMs: java.lang.Long = System.currentTimeMillis()
            NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.tapLogTableName, "key", key, "value", gson.toJson(log), "created_at", nowMs)
        } catch {
            case e: Exception =>
                logger.warn("Failed to write tap run log: " + e.getMessage)
        }
    }

    /** The tap's source identity for provenance: declared `source`, else the
      * endpoint host (HTTP taps), else the host its script references most,
      * else `tap:<name>`. See [[TapSourceResolver]]. Never credentials. */
    private[util] def declaredSource(tapConfig: TapConfig): String = TapSourceResolver.resolve(tapConfig)

    private def feedPipeline(tapConfig: TapConfig, result: TapScriptResult, publisherToken: String, tapFeed: TapFeedInfo): (Int, java.util.List[String]) = {
        if (result.dataType == "document") {
            return feedDocumentPipeline(tapConfig, result, publisherToken, tapFeed)
        }

        logger.info("TapRunner: feeding " + result.recordCount + " records to pipeline: " + tapConfig.targetPipeline)

        // Check what format the pipeline expects
        val pipelineConfig = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tapConfig.targetPipeline)
        val pipelineExpectsCsv = pipelineConfig != null &&
            pipelineConfig.source != null &&
            pipelineConfig.source.fileAttributes != null &&
            pipelineConfig.source.fileAttributes.csvAttributes != null

        // CSV pipelines get a streamed projection of the records into a delimited
        // staged file, fed through the InputStream overload so schema evolution
        // and the CSV parser run exactly as for an upload. JSON / XML / text
        // pipelines adopt the staged file as-is — no bytes through the heap.
        val jobContext = if (pipelineExpectsCsv) {
            val delimiter = if (pipelineConfig.source.fileAttributes.csvAttributes.delimiter != null)
                pipelineConfig.source.fileAttributes.csvAttributes.delimiter
            else ","
            val (feed, filename) = projectForCsv(result, delimiter, "tap-" + tapConfig.name)
            val source = new BufferedInputStream(Files.newInputStream(Paths.get(feed.path)))
            new StreamNotifier().process(source, feed.bytes, filename, tapConfig.targetPipeline, publisherToken, tapFeed)
        } else
            new StreamNotifier().process(
                result.staged,
                result.staged.bytes,
                "tap-" + tapConfig.name + ".json",
                tapConfig.targetPipeline,
                publisherToken,
                tapFeed
            )
        GlobalJobContext.addJobContext(jobContext)
        logger.info("TapRunner: submitted job for pipeline: " + tapConfig.targetPipeline + ", token: " + jobContext.pipelineToken)
        val tokens = new java.util.ArrayList[String]()
        tokens.add(jobContext.pipelineToken)
        (result.recordCount, tokens)
    }

    /**
     * Route a document-tap result through the unstructured pipeline path.
     *
     * Each record is a document dict with {uri, filename, content (base64), content_hash?, metadata?}.
     * For each document we:
     *   1. Decode base64 → raw bytes
     *   2. Compute SHA-256 when the script didn't provide a content_hash
     *   3. Skip if the ledger already has this uri at the same hash (already processed)
     *   4. Stage the bytes to MinIO under {env}-config/tap-docs/{tapName}/{uuid}_{filename}
     *   5. Record a "staged" ledger entry
     *   6. Submit bytes to the target pipeline (routes through StreamNotifier's
     *      unstructuredAttributes branch into the vector store loader)
     *   7. Mark the ledger entry "processed" (or "failed" with the error)
     *
     * Returns the number of documents actually submitted to the pipeline (skipped docs
     * and failed docs are not counted).
     */
    private def feedDocumentPipeline(
        tapConfig: TapConfig,
        result: TapScriptResult,
        publisherToken: String,
        tapFeed: TapFeedInfo
    ): (Int, java.util.List[String]) = {
        import scala.collection.JavaConverters._
        logger.info("TapRunner: document tap '" + tapConfig.name + "' returned " + result.recordCount + " documents")
        val tokens = new java.util.ArrayList[String]()

        val env = DatrisEnvironment.current

        // Belt-and-suspenders compatibility check: the save-time guard in TapAPIController
        // already rejects incompatible combinations, but the pipeline config could have
        // been reshaped after the tap was saved. Re-check before feeding.
        val pipelineConfig = PipelineConfigIO.read(env.pipelineTableName, tapConfig.targetPipeline)
        DocumentTapValidator.incompatibilityReason(pipelineConfig) match {
            case Some(reason) =>
                val msg = "Target pipeline '" + tapConfig.targetPipeline + "' is not compatible with document tap: " + reason
                logger.error("TapRunner: " + msg)
                throw new DatrisException(msg)
            case None => // compatible, proceed
        }

        val bucket = env.environment + "-config"
        val ledgerTable = env.tapLedgerTableName
        val known = TapDocumentLedgerIO.getKnownHashes(ledgerTable, tapConfig.name)

        val sdf = new SimpleDateFormat(env.dateFormat)
        sdf.setTimeZone(TimeZone.getTimeZone(env.dateTimezone))

        val gson = new Gson

        var processed = 0
        var skipped = 0
        var failed = 0

        // One document per NDJSON line off the staged file; each document still
        // materializes on its own (base64 decode) — that is per-document, not per-run.
        val it = StagedRows.lines(result.staged.path)
        try while (it.hasNext) {
                val obj = JsonParser.parseString(it.next()).getAsJsonObject
                val uri = Option(obj.get("uri")).filter(!_.isJsonNull).map(_.getAsString).getOrElse("")
                val filename = Option(obj.get("filename")).filter(!_.isJsonNull).map(_.getAsString).getOrElse("document.bin")
                val contentB64 = Option(obj.get("content")).filter(!_.isJsonNull).map(_.getAsString).getOrElse("")

                if (uri.isEmpty || contentB64.isEmpty) {
                    logger.warn("TapRunner: document missing uri or content, skipping")
                    failed += 1
                } else {
                    try {
                        val rawBytes = java.util.Base64.getDecoder.decode(contentB64)
                        val providedHash = Option(obj.get("content_hash")).filter(!_.isJsonNull).map(_.getAsString).orNull
                        val contentHash = if (providedHash != null && providedHash.nonEmpty) providedHash else sha256(rawBytes)

                        val metadata: java.util.Map[String, String] = Option(obj.get("metadata"))
                            .filter(e => !e.isJsonNull && e.isJsonObject)
                            .map { elem =>
                                val m = new java.util.LinkedHashMap[String, String]()
                                elem.getAsJsonObject.entrySet().asScala.foreach { e =>
                                    val v = e.getValue
                                    m.put(e.getKey, if (v.isJsonNull) null else if (v.isJsonPrimitive) v.getAsString else v.toString)
                                }
                                m
                            }.orNull

                        val now = sdf.format(new Date())

                        if (known.get(uri).contains(contentHash)) {
                            skipped += 1
                            // Refresh lastSeenAt so operators can see the tap is still finding the doc
                            val existing = TapDocumentLedgerIO.read(ledgerTable, tapConfig.name, uri)
                            if (existing != null) {
                                TapDocumentLedgerIO.write(ledgerTable, existing.copy(lastSeenAt = now))
                            }
                        } else {
                            val stagedKey = "tap-docs/" + tapConfig.name + "/" +
                                UUID.randomUUID().toString.substring(0, 8) + "_" + filename
                            ObjectStoreUtil.writeBucketObjectFromStream(bucket, stagedKey, new ByteArrayInputStream(rawBytes), rawBytes.length.toLong)

                            val firstSeen = TapDocumentLedgerIO.read(ledgerTable, tapConfig.name, uri) match {
                                case null => now
                                case prev => prev.firstSeenAt
                            }

                            TapDocumentLedgerIO.write(
                                ledgerTable,
                                TapDocumentLedger(
                                    uri = uri,
                                    tapName = tapConfig.name,
                                    stagedPath = stagedKey,
                                    filename = filename,
                                    contentHash = contentHash,
                                    firstSeenAt = firstSeen,
                                    lastSeenAt = now,
                                    status = "staged",
                                    metadata = metadata
                                )
                            )

                            try {
                                val jobContext = new StreamNotifier().process(rawBytes, filename, tapConfig.targetPipeline, publisherToken, tapFeed)
                                GlobalJobContext.addJobContext(jobContext)
                                tokens.add(jobContext.pipelineToken)
                                TapDocumentLedgerIO.write(
                                    ledgerTable,
                                    TapDocumentLedger(
                                        uri = uri,
                                        tapName = tapConfig.name,
                                        stagedPath = stagedKey,
                                        filename = filename,
                                        contentHash = contentHash,
                                        firstSeenAt = firstSeen,
                                        lastSeenAt = now,
                                        status = "processed",
                                        metadata = metadata
                                    )
                                )
                                processed += 1
                            } catch {
                                case e: Exception =>
                                    logger.warn("TapRunner: pipeline submission failed for uri=" + uri + ": " + e.getMessage)
                                    TapDocumentLedgerIO.write(
                                        ledgerTable,
                                        TapDocumentLedger(
                                            uri = uri,
                                            tapName = tapConfig.name,
                                            stagedPath = stagedKey,
                                            filename = filename,
                                            contentHash = contentHash,
                                            firstSeenAt = firstSeen,
                                            lastSeenAt = now,
                                            status = "failed",
                                            metadata = metadata
                                        )
                                    )
                                    failed += 1
                            }
                        }
                    } catch {
                        case e: Exception =>
                            logger.warn("TapRunner: document handling failed for uri=" + uri + ": " + e.getMessage)
                            failed += 1
                    }
                }
            }
        finally it.close()

        logger.info("TapRunner: document tap '" + tapConfig.name + "' processed=" + processed +
            ", skipped=" + skipped + ", failed=" + failed)
        (processed, tokens)
    }

    private def sha256(bytes: Array[Byte]): String = {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.map("%02x".format(_)).mkString
    }

    /** The CSV projection a CSV pipeline is fed, written INSIDE the tap run's own
      * staging directory (feedPipeline runs after TapScriptRunner.run has left its
      * token scope, so an unbound `newWriter` would land in `_unscoped/` and
      * outlive the run — the InputStream overload copies rather than moves). It
      * is reclaimed with the tap token dir by `release`. Every payload either
      * projects to `<baseName>.csv` or throws a `DatrisException` naming the
      * shape and the reason — the raw NDJSON is never fed to the CSV lane (that
      * is what put garbage columns on a pipeline schema in the field). */
    private[util] def projectForCsv(result: TapScriptResult, delimiter: String, baseName: String): (StagedPayload, String) =
        StagingArea.withToken(TapScriptRunner.stagingTokenOf(result.staged)) {
            try { (jsonToCsv(result.staged, delimiter), baseName + ".csv") }
            catch {
                case e: Exception =>
                    val shape = "a " + String.valueOf(result.dataType) + " payload of " + result.recordCount + " record(s)"
                    val msg = "tap returned " + shape + ", which cannot be projected into CSV pipeline " + baseName + ": " + e.getMessage
                    logger.error("TapRunner: " + msg)
                    throw new DatrisException(msg)
            }
        }

    /** Streaming projection of a staged NDJSON record list into a Delimited
      * staged file. Keyed on the SHAPE of line 1, not the tap's dataType label:
      *  - JSON objects: header = union of keys across ALL records (first-seen
      *    order — variable-shape sources would otherwise lose later-only
      *    columns), then one row per record.
      *  - JSON arrays (a list of lists): line 1 is the header, each cell through
      *    `TapScriptRunner.normalizeColumnName`; the remaining arrays are rows —
      *    short rows pad with empty, a long row is an error. rowCount excludes
      *    the header.
      * Same quoting either way (a value holding the delimiter, a quote or a line
      * break is quoted, quotes doubled); null / absent → empty. Mixed shapes,
      * scalars throw; an empty list yields a 0-byte file. */
    private[util] def jsonToCsv(staged: StagedPayload, delimiter: String = ","): StagedPayload = {
        import scala.collection.JavaConverters._
        val format = StagedFormat.Delimited(delimiter)
        if (staged == null || staged.isEmpty || staged.format != StagedFormat.NdJson)
            throw new DatrisException("the tap payload is not a JSON record list")

        def cell(elem: JsonElement): String =
            if (elem == null || elem.isJsonNull) ""
            else {
                val s = if (elem.isJsonPrimitive) elem.getAsJsonPrimitive.getAsString // raw number string: "1782800", "254.2"
                else elem.toString
                if (s.contains(delimiter) || s.contains("\"") || s.contains("\n") || s.contains("\r"))
                    "\"" + s.replace("\"", "\"\"") + "\""
                else s
            }
        def kind(e: JsonElement): String =
            if (e.isJsonObject) "a JSON object" else if (e.isJsonArray) "a JSON array" else if (e.isJsonNull) "null" else "a JSON scalar"

        val first = {
            val it = StagedRows.lines(staged.path)
            try if (it.hasNext) JsonParser.parseString(it.next()) else null
            finally it.close()
        }
        val (path, writer) = StagingArea.newWriter("tap-csv", format)
        var rowCount = staged.rowCount
        try {
            if (first == null) {
                // An empty record list projects to an empty file, as before.
            } else if (first.isJsonObject) {
                // Pass 1: the key union.
                val seen = scala.collection.mutable.LinkedHashSet[String]()
                val pass1 = StagedRows.lines(staged.path)
                var n = 0L
                try while (pass1.hasNext) {
                        n += 1
                        val e = JsonParser.parseString(pass1.next())
                        if (!e.isJsonObject) throw new DatrisException("line " + n + " is " + kind(e) + ", not a JSON object like line 1")
                        e.getAsJsonObject.keySet().asScala.foreach(seen.add)
                    }
                finally pass1.close()
                val columns = seen.toList
                if (columns.nonEmpty) {
                    writer.write(columns.mkString(delimiter))
                    val pass2 = StagedRows.lines(staged.path)
                    try while (pass2.hasNext) {
                            val obj = JsonParser.parseString(pass2.next()).getAsJsonObject
                            writer.write("\n")
                            writer.write(columns.map(col => cell(obj.get(col))).mkString(delimiter))
                        }
                    finally pass2.close()
                }
            } else if (first.isJsonArray) {
                val header = first.getAsJsonArray.asScala.map(h => TapScriptRunner.normalizeColumnName(cell(h))).toList
                if (header.isEmpty) throw new DatrisException("line 1 (the header row) is an empty array")
                if (header.distinct.size != header.size)
                    throw new DatrisException(
                        "the header row has duplicate column names after normalization: " +
                            header.groupBy(identity).collect { case (k, v) if v.size > 1 => k }.mkString(", ")
                    )
                writer.write(header.mkString(delimiter))
                val width = header.size
                val it = StagedRows.lines(staged.path)
                var n = 1L
                var dataRows = 0L
                try {
                    it.next() // the header
                    while (it.hasNext) {
                        n += 1
                        val e = JsonParser.parseString(it.next())
                        if (!e.isJsonArray) throw new DatrisException("line " + n + " is " + kind(e) + ", not a JSON array like the header row")
                        val arr = e.getAsJsonArray
                        if (arr.size() > width)
                            throw new DatrisException("row " + n + " has " + arr.size() + " cells but the header row has " + width)
                        val cells = arr.asScala.map(cell).toList ++ List.fill(width - arr.size())("")
                        writer.write("\n")
                        writer.write(cells.mkString(delimiter))
                        dataRows += 1
                    }
                } finally it.close()
                rowCount = dataRows
            } else
                throw new DatrisException("line 1 is " + kind(first) + "; a CSV pipeline needs JSON objects or arrays (rows)")
        } catch {
            case e: Exception =>
                writer.close()
                Files.deleteIfExists(path)
                throw e
        } finally writer.close()
        StagedPayload(path.toString, format, rowCount, Files.size(path))
    }
}
