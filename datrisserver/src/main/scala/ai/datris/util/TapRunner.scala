package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, GlobalJobContext, TapConfig, TapDocumentLedger, TapRunLog}
import ai.datris.controller.{JobRunner, StreamNotifier}
import com.google.gson.{Gson, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import java.io.ByteArrayInputStream
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
     * @return TapScriptResult with fetched records
     */
    def run(tapConfig: TapConfig, mode: String = "run", testLimit: Int = 0, params: Map[String, String] = Map.empty): TapScriptResult = {
        val push = mode == "run"
        val publisherToken = if (push) UUID.randomUUID().toString else null
        val sdf = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        sdf.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        val now = sdf.format(new Date())
        val startMs = System.currentTimeMillis()

        // Only update status in DB for real runs, not tests
        if (push) {
            val runningConfig = tapConfig.copy(lastRunStatus = "running", lastRunTime = now, lastRunError = null)
            TapConfigIO.write(runningConfig)
        }

        try {
            val result = TapScriptRunner.run(tapConfig, testLimit, params)
            val durationMs = System.currentTimeMillis() - startMs

            if (result.error != null) {
                // Script errored. This is a real failure — write it as such.
                if (push) {
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = result.error
                    )
                    TapConfigIO.write(failedConfig)
                }
                writeRunLog(tapConfig.name, now, "failure", result.recordCount, result.dataType, result.logs, result.error, mode, durationMs)
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
                        val failedConfig = tapConfig.copy(
                            lastRunStatus = "failure",
                            lastRunTime = now,
                            lastRunRecordCount = 0,
                            lastRunError = missingMsg
                        )
                        TapConfigIO.write(failedConfig)
                    }
                    writeRunLog(tapConfig.name, now, "failure", 0, result.dataType, result.logs, missingMsg, mode, durationMs)
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
                        lastRunError = null
                    )
                    TapConfigIO.write(noRecordsConfig)
                }
                writeRunLog(tapConfig.name, now, "no_records", 0, result.dataType, result.logs, null, mode, durationMs)
                return result
            }

            // Push to pipeline if requested, records exist, and a target pipeline is configured
            val (processedCount, pipelineTokens) =
                if (
                    push && result.records != null && result.recordCount > 0 &&
                    tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty
                ) {
                    feedPipeline(tapConfig, result, publisherToken)
                } else (result.recordCount, new java.util.ArrayList[String]())

            if (push) {
                val successConfig = tapConfig.copy(
                    lastRunStatus = "success",
                    lastRunTime = now,
                    lastRunRecordCount = processedCount,
                    lastRunError = null,
                    lastRunDataType = result.dataType,
                    lastRunColumns = result.columns
                )
                TapConfigIO.write(successConfig)
            }

            val tokensOut = if (pipelineTokens.isEmpty) null else pipelineTokens
            val pubOut = if (tokensOut == null) null else publisherToken
            writeRunLog(tapConfig.name, now, "success", processedCount, result.dataType, result.logs, null, mode, durationMs, pubOut)
            result.copy(publisherToken = pubOut, pipelineTokens = tokensOut)
        } catch {
            case e: Exception =>
                val durationMs = System.currentTimeMillis() - startMs
                logger.error("TapRunner failed for tap: " + tapConfig.name, e)
                if (push) {
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = e.getMessage
                    )
                    TapConfigIO.write(failedConfig)
                }
                writeRunLog(tapConfig.name, now, "failure", 0, null, null, e.getMessage, mode, durationMs)
                TapScriptResult(null, 0, e.getMessage)
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
        publisherToken: String = null
    ): Unit = {
        try {
            val log = TapRunLog(tapName, runTime, status, recordCount, dataType, logs, error, mode, durationMs, publisherToken)
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

    private def feedPipeline(tapConfig: TapConfig, result: TapScriptResult, publisherToken: String): (Int, java.util.List[String]) = {
        if (result.dataType == "document") {
            return feedDocumentPipeline(tapConfig, result, publisherToken)
        }

        logger.info("TapRunner: feeding " + result.recordCount + " records to pipeline: " + tapConfig.targetPipeline)

        // Check what format the pipeline expects
        val pipelineConfig = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tapConfig.targetPipeline)
        val pipelineExpectsCsv = pipelineConfig != null &&
            pipelineConfig.source != null &&
            pipelineConfig.source.fileAttributes != null &&
            pipelineConfig.source.fileAttributes.csvAttributes != null

        val (bytes, filename) = if (pipelineExpectsCsv) {
            val delimiter = if (pipelineConfig.source.fileAttributes.csvAttributes.delimiter != null)
                pipelineConfig.source.fileAttributes.csvAttributes.delimiter
            else ","
            try {
                val csv = jsonToCsv(result.records, delimiter)
                (csv.getBytes("UTF-8"), "tap-" + tapConfig.name + ".csv")
            } catch {
                case e: Exception =>
                    logger.error("TapRunner: jsonToCsv failed: " + e.getMessage)
                    (result.records.getBytes("UTF-8"), "tap-" + tapConfig.name + ".json")
            }
        } else {
            (result.records.getBytes("UTF-8"), "tap-" + tapConfig.name + ".json")
        }

        val jobContext = new StreamNotifier().process(bytes, filename, tapConfig.targetPipeline, publisherToken)
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
    private def feedDocumentPipeline(tapConfig: TapConfig, result: TapScriptResult, publisherToken: String): (Int, java.util.List[String]) = {
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
        val array = JsonParser.parseString(result.records).getAsJsonArray

        var processed = 0
        var skipped = 0
        var failed = 0

        val it = array.iterator()
        while (it.hasNext) {
            val obj = it.next().getAsJsonObject
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
                            val jobContext = new StreamNotifier().process(rawBytes, filename, tapConfig.targetPipeline, publisherToken)
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

        logger.info("TapRunner: document tap '" + tapConfig.name + "' processed=" + processed +
            ", skipped=" + skipped + ", failed=" + failed)
        (processed, tokens)
    }

    private def sha256(bytes: Array[Byte]): String = {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.map("%02x".format(_)).mkString
    }

    private def jsonToCsv(json: String, delimiter: String = ","): String = {
        import scala.collection.JavaConverters._
        val jsonArray = com.google.gson.JsonParser.parseString(json).getAsJsonArray
        if (jsonArray.size() == 0) return ""

        // Compute the union of keys across ALL records, preserving first-seen order.
        // Some sources emit records with variable shape,
        // and using only the first record's keys silently drops columns that appear later.
        val seen = scala.collection.mutable.LinkedHashSet[String]()
        (0 until jsonArray.size()).foreach { i =>
            val obj = jsonArray.get(i).getAsJsonObject
            obj.keySet().asScala.foreach(seen.add)
        }
        val columns = seen.toList
        val header = columns.mkString(delimiter)

        val rows = (0 until jsonArray.size()).map(i => {
            val obj = jsonArray.get(i).getAsJsonObject
            columns.map(col => {
                val elem = obj.get(col)
                if (elem == null || elem.isJsonNull) ""
                else {
                    val s = if (elem.isJsonPrimitive) {
                        val prim = elem.getAsJsonPrimitive
                        if (prim.isString) prim.getAsString
                        else prim.getAsString // returns raw number string: "1782800", "254.2"
                    } else elem.toString
                    if (s.contains(delimiter) || s.contains("\"") || s.contains("\n") || s.contains("\r"))
                        "\"" + s.replace("\"", "\"\"") + "\""
                    else s
                }
            }).mkString(delimiter)
        })

        (header +: rows).mkString("\n")
    }
}
