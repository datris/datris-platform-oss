package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, GsonBuilder, JsonElement, JsonNull, JsonParser}
import ai.datris.auth.{CapabilityCheck, ResolvedKeyAccess, VersionActor}
import ai.datris.model.{TapConfig, DatrisEnvironment, DatrisException, EntityVersion}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.time.Instant
import scala.collection.JavaConverters._

object TapAPIController {
    // mode=test response caps `records` to this many rows. The UI's preview already
    // slices to 20 (tap-run.component.ts), so this matches without losing display
    // fidelity, and prevents large taps from bloating agent context.
    private val testRecordSampleSize = 20

    // Server-side debounce for /tap/run with mode=run. Prevents duplicate pushes when
    // an agent emits parallel tool_use blocks, when a UI button is rapidly clicked,
    // or when MCP transport retries fire concurrent requests. Keyed by tenant + tap
    // name; mode=test bypasses the debounce since previewing has no side-effects.
    private val runDebounceWindowMs: Long = 5000L
    private val recentRunStarts: java.util.concurrent.ConcurrentHashMap[String, java.lang.Long] =
        new java.util.concurrent.ConcurrentHashMap[String, java.lang.Long]()
}

@RestController
@RequestMapping(Array("/api/v1"))
class TapAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[TapAPIController])

    @GetMapping(path = Array("/taps"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTaps(@RequestHeader(name = "x-api-key", required = false) apiKey: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /taps called")
            APIKeyValidator.validate(apiKey)

            val allTaps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
            // Scope-aware filter: a key whose only `tap:read` grant is
            // `tap:read:owner=self` sees only its own taps. Same pattern as
            // /pipelines above. Keys with unscoped `tap:read` or `*:*` see
            // everything.
            val filteredTaps =
                if (CapabilityCheck.hasOnlyOwnerSelfScope(request, "tap", "read")) {
                    val ownerLabel = ResolvedKeyAccess.keyLabel(request).orNull
                    allTaps.filter(t => t != null && t.createdByKeyLabel != null && t.createdByKeyLabel == ownerLabel)
                } else {
                    allTaps
                }
            val gson = new Gson
            val json = gson.toJson(filteredTaps.asJava)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            val config = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (config == null)
                throw new DatrisException("Tap: " + name + " not found")
            val gson = new Gson
            // Include script content from MinIO. Track "missing" separately from
            // "never generated" so the UI can tell the user the script was deleted
            // vs. not yet created.
            var scriptMissing = false
            val hasScriptRef =
                (config.scriptPath != null && config.scriptPath.nonEmpty) ||
                    (config.scriptRepoPath != null && config.scriptRepoPath.nonEmpty)
            val scriptContent: String = if (hasScriptRef) {
                try {
                    TapCodeStore.forTap(config).readScript(config) match {
                        case Some(content) => content
                        case None =>
                            scriptMissing = true
                            logger.warn("Tap '" + name + "' has a script reference but the object is missing from its storage backend")
                            null
                    }
                } catch {
                    case e: Exception =>
                        scriptMissing = true
                        logger.warn("Tap '" + name + "' script read failed: " + e.getMessage)
                        null
                }
            } else null
            val response = gson.fromJson(gson.toJson(config), classOf[java.util.Map[String, Any]])
            response.put("script", scriptContent)
            if (scriptMissing) response.put("scriptMissing", java.lang.Boolean.TRUE)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap/logs/all"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getAllTapLogs(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) since: java.lang.Long,
        @RequestParam(required = false) limit: java.lang.Integer
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            // Default: last 30 days, capped at 2000 rows. Both bounds protect the
            // dashboard from a runaway scan on a very chatty tenant.
            val sinceMs: Long = if (since != null) since.longValue() else System.currentTimeMillis() - 30L * 86400000L
            val maxItems: Int = {
                val raw = if (limit != null) limit.intValue() else 2000
                if (raw <= 0) 2000 else math.min(raw, 5000)
            }
            logger.info("API endpoint GET /tap/logs/all called since=" + sinceMs + " limit=" + maxItems)

            val rows = NoSQLDbUtil.getItemsSinceAsJSON(
                DatrisEnvironment.current.tapLogTableName,
                "created_at",
                sinceMs,
                maxItems
            )

            // Mongo rows have shape {"key": ..., "value": {...TapRunLog...}, "created_at": ...}.
            // Unwrap to just the embedded TapRunLog values so the response is a flat
            // array of run logs the UI can iterate. No pipeline-rollup enrichment
            // here — that's only needed by the per-tap detail view; this endpoint
            // exists to feed the Ops activity dashboard's tile/chart aggregation.
            val parser = new JsonParser()
            val out = new com.google.gson.JsonArray()
            rows.foreach { json =>
                try {
                    val el = parser.parse(json)
                    if (el.isJsonObject) {
                        val obj = el.getAsJsonObject
                        if (obj.has("value")) out.add(obj.get("value"))
                    }
                } catch {
                    case e: Exception =>
                        // skip malformed row, don't fail the whole request
                        logger.debug("Skipping malformed tap run log row", e)
                        ()
                }
            }
            new ResponseEntity[String](out.toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap/logs"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLogs(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/logs called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.current.tapLogTableName, "key")
            val tapKeys = allKeys.filter(_.startsWith(name + "|")).sorted.reverse.take(50)

            val gson = new Gson
            val logs = tapKeys.flatMap(key => {
                val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.tapLogTableName, "key", key, "value").orNull
                if (json != null) {
                    Some(gson.fromJson(json, classOf[ai.datris.model.TapRunLog]))
                } else None
            })

            // Enrich each run with its downstream pipeline rollup so the Run History
            // modal can show per-document job outcomes — not just the tap-script
            // fetch summary. Without this, a tap run that fetched 28 docs but had
            // 1 chunking/embedding failure shows up as "SUCCESS 28 records" with
            // no hint of the failure. Only runs that actually fed a pipeline
            // (mode=run with a publisherToken) get a rollup.
            val enriched = logs.map { log =>
                val rollupJson: JsonElement =
                    if (log.publisherToken != null && log.publisherToken.nonEmpty) {
                        try {
                            val response = PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(log.publisherToken)
                            if (response != null && response.rollup != null && !response.rollup.jobs.isEmpty)
                                gson.toJsonTree(response.rollup)
                            else JsonNull.INSTANCE
                        } catch {
                            case e: Exception =>
                                logger.warn("Could not load rollup for publisher token " + log.publisherToken + ": " + e.getMessage)
                                JsonNull.INSTANCE
                        }
                    } else JsonNull.INSTANCE
                val obj = gson.toJsonTree(log).getAsJsonObject
                obj.add("pipelineRollup", rollupJson)
                obj
            }

            val arr = new com.google.gson.JsonArray()
            enriched.foreach(arr.add)
            new ResponseEntity[String](arr.toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def createOrUpdateTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "changeNote", required = false) changeNote: String,
        @RequestBody tapConfig: TapConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap called with name: " + tapConfig.name)
            APIKeyValidator.validate(apiKey)

            if (tapConfig.name == null || tapConfig.name.isEmpty)
                throw new DatrisException("Tap name is required")

            // Document-tap pipeline compatibility guard: document taps push raw bytes,
            // so the target pipeline must be unstructured + vector-store or the bytes
            // will crash the destination loader (e.g. PDF into MongoDBLoader).
            if (tapConfig.tapType == "document" && tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty) {
                val pipeline = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tapConfig.targetPipeline)
                DocumentTapValidator.incompatibilityReason(pipeline) match {
                    case Some(reason) =>
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                            "{\"error\": \"Document tap '" + tapConfig.name + "' cannot target pipeline '" +
                                tapConfig.targetPipeline + "': " + reason.replace("\"", "'") + "\"}"
                        )
                    case None => // compatible
                }
            }

            // Script-existence guard: a tap save with a script reference that points
            // at a missing file (MinIO object or repo path) would silently succeed
            // here and then fail at run_tap with "scriptMissing". Reject the save
            // with a clear error so the UI can prompt the user to push the script
            // before retrying.
            val hasScriptRef =
                (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                    (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
            if (hasScriptRef) {
                val exists =
                    try {
                        TapCodeStore.forTap(tapConfig).scriptExists(tapConfig)
                    } catch {
                        case e: Exception =>
                            logger.warn("Tap script existence check failed for tap '" + tapConfig.name + "'", e)
                            false
                    }
                if (!exists) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        "{\"error\": \"Tap script not found in its storage backend. " +
                            "The script must be uploaded (via the tap wizard's script-store endpoints) before saving the tap config.\"}"
                    )
                }
            }

            // Script-reference preservation: clients (UI save, MCP create_tap)
            // rebuild the config body from scratch and may omit the script
            // fields entirely. If the body carries NO script reference but the
            // stored tap has one, carry it over — otherwise an unrelated edit
            // (description, cron) would silently orphan the script.
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapConfig.name)
            val bodyHasScriptRef =
                (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                    (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
            val tapConfigWithScript =
                if (!bodyHasScriptRef && existing != null)
                    tapConfig.copy(
                        scriptStorage = existing.scriptStorage,
                        scriptPath = existing.scriptPath,
                        scriptRepoPath = existing.scriptRepoPath,
                        scriptCommitSha = existing.scriptCommitSha
                    )
                else tapConfig

            // Set timestamps and stamp the issuing key's label on first create.
            // On update we preserve the original `createdByKeyLabel` — ownership
            // is a property of creation, not the last edit (otherwise an editor
            // key would silently claim ownership and `owner=self` would drift).
            val sdf2 = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf2.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            val now = sdf2.format(new java.util.Date())
            val configToSave = if (existing != null)
                tapConfigWithScript.copy(
                    createdAt = existing.createdAt,
                    updatedAt = now,
                    createdByKeyLabel = existing.createdByKeyLabel
                )
            else
                tapConfigWithScript.copy(
                    createdAt = now,
                    updatedAt = now,
                    createdByKeyLabel = ResolvedKeyAccess.keyLabel(request).orNull
                )

            // Definition-edit write → mints a new immutable version snapshot.
            // (Status churn from TapRunner stays on plain TapConfigIO.write.)
            val note = if (changeNote != null && changeNote.nonEmpty) changeNote
            else if (existing != null) "updated" else "created"
            val saved = TapConfigIO.writeVersioned(configToSave, note, VersionActor.resolve(request))

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(saved), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            // Delete script from MinIO if it exists
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (existing != null) {
                // Scope check: `tap:delete:owner=self` keys may only delete
                // taps they created. Loaded resource carries createdByKeyLabel.
                CapabilityCheck.assertOwnerScope(request, "tap", "delete", existing.createdByKeyLabel)
                // For repo-backed taps this commits a file removal (history is
                // preserved — that's the point of the repo). Non-fatal: a dead
                // repo connection must not block deleting the tap itself.
                try TapCodeStore.forTap(existing).deleteScript(existing)
                catch { case e: Exception => logger.warn("Tap script cleanup failed for '" + name + "': " + e.getMessage) }
            }

            // Document taps: also clean up staged files and ledger entries
            if (existing != null && existing.tapType == "document") {
                try {
                    val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
                    val bucket = DatrisEnvironment.current.environment + "-config"
                    val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                    entries.foreach { e =>
                        if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                            try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                            catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                        }
                    }
                    TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
                } catch {
                    case ex: Exception => logger.warn("Tap ledger cleanup failed for " + name + ": " + ex.getMessage)
                }
            }

            // Run history: every TapRunner.writeRunLog inserts a row keyed by
            // "<tapName>|<runTime>" into {env}-tap-log. Without this cleanup, deleting
            // and recreating a tap with the same name would silently surface the old
            // tap's run history under the new tap.
            try {
                val tapLogTable = DatrisEnvironment.current.tapLogTableName
                val prefix = name + "|"
                val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(tapLogTable, "key")
                allKeys.filter(_.startsWith(prefix)).foreach { k =>
                    NoSQLDbUtil.deleteItemJSON(tapLogTable, "key", k)
                }
            } catch {
                case ex: Exception => logger.warn("Tap run-log cleanup failed for " + name + ": " + ex.getMessage)
            }

            // Definition versions: hard-delete every snapshot for this tap AND
            // GC the script objects they pinned (now that we have the index to
            // do it cleanly). deleteScript is idempotent, so re-deleting the
            // current scriptPath removed above is harmless.
            try {
                EntityVersionIO.deleteAllForEntity(DatrisEnvironment.current.tapVersionTableName, name)
                    .foreach(TapScriptGenerator.deleteScript)
            } catch {
                case ex: Exception => logger.warn("Tap version cleanup failed for " + name + ": " + ex.getMessage)
            }

            TapConfigIO.delete(DatrisEnvironment.current.tapTableName, name)
            new ResponseEntity[String]("{\"message\": \"Tap deleted: " + name + "\"}", HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/script"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def storeScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val script = body.get("script")
            val requestedStorage = body.get("storage")
            val baseCommitSha = body.get("baseCommitSha")
            logger.info("API endpoint POST /tap/script called, tapName: " + tapName)
            APIKeyValidator.validate(apiKey)

            if (tapName == null || tapName.isEmpty)
                throw new DatrisException("tapName is required")
            if (script == null || script.isEmpty)
                throw new DatrisException("script is required")

            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)

            // Backend resolution: explicit request > the tap's current backend
            // (existing taps never switch silently) > tenant default.
            val store =
                if (requestedStorage != null && requestedStorage.nonEmpty) TapCodeStore.forStorage(requestedStorage)
                else if (existing != null) TapCodeStore.forTap(existing)
                else TapCodeStore.forStorage(null)

            // The editor sends the commit sha it opened against so a concurrent
            // external commit to the same file is a conflict, not an overwrite.
            val prior =
                if (existing != null && baseCommitSha != null && baseCommitSha.nonEmpty)
                    existing.copy(scriptCommitSha = baseCommitSha)
                else existing
            val stored = store.storeScript(tapName, script, prior, actorLabel(request))

            if (existing != null) {
                TapConfigIO.write(
                    existing.copy(
                        scriptStorage = stored.storage,
                        scriptPath = stored.scriptPath,
                        scriptRepoPath = stored.scriptRepoPath,
                        scriptCommitSha = stored.scriptCommitSha
                    )
                )
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("scriptPath", stored.scriptPath)
            response.put("storage", stored.storage)
            response.put("scriptRepoPath", stored.scriptRepoPath)
            response.put("scriptCommitSha", stored.scriptCommitSha)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: CodeRepoConflictException =>
                ResponseEntity.status(HttpStatus.CONFLICT).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def actorLabel(request: HttpServletRequest): String =
        ResolvedKeyAccess.keyLabel(request).orNull

    @PostMapping(path = Array("/tap/cron"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateCron(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String]
    ): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            logger.info("API endpoint POST /tap/cron called: " + description)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val prompt =
                s"""Convert this schedule description to a Quartz CRON expression (6 fields: second minute hour day-of-month month day-of-week).
                   |Return ONLY the CRON expression string, nothing else. No explanation, no quotes, no markdown.
                   |
                   |Examples:
                   |  "every hour" → 0 0 * * * ?
                   |  "every weekday at 4pm" → 0 0 16 ? * MON-FRI
                   |  "every 15 minutes" → 0 */15 * * * ?
                   |  "daily at midnight" → 0 0 0 * * ?
                   |  "twice a day at 8am and 6pm" → 0 0 8,18 * * ?
                   |
                   |Schedule: "$description"""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            // LLMs occasionally wrap the cron in quotes, backticks, brackets, or
            // markdown fences. Strip them all before handing the value back.
            val cron = AIUtil.extractText(responseText).trim
                .replaceAll("(?s)^```(?:\\w+)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .replaceAll("[\"'`\\[\\]]", "")
                .trim

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("cronExpression", cron)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/brainstorm"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def brainstorm(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any]
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/brainstorm called")
            APIKeyValidator.validate(apiKey)

            val messagesRaw = body.get("messages").asInstanceOf[java.util.List[java.util.Map[String, String]]]
            val currentDescription = Option(body.get("currentDescription")).map(_.toString).getOrElse("")
            val tapType = Option(body.get("tapType")).map(_.toString).getOrElse("structured")

            if (messagesRaw == null || messagesRaw.isEmpty)
                throw new DatrisException("messages array is required")

            val messages = messagesRaw.asScala.map { m =>
                (m.get("role"), m.get("content"))
            }.toSeq

            val response = TapBrainstormer.brainstorm(messages, currentDescription, tapType)

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/generate"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String]
    ): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            val tapName = Option(body.get("tapName")).getOrElse("tap-" + System.currentTimeMillis())
            val oldScriptPath = body.get("oldScriptPath")
            val secretName = body.get("secretName")
            val tapType = Option(body.get("tapType")).getOrElse("structured")
            logger.info("API endpoint POST /tap/generate called, tapName: " + tapName + ", tapType: " + tapType)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val result = TapScriptGenerator.generate(description, tapName, oldScriptPath, secretName, tapType)

            // Update scriptPath in MongoDB if tap already exists
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (existing != null) {
                TapConfigIO.write(existing.copy(scriptPath = result.scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("injectedPrompts", result.injectedPrompts)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/fix"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def fixScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String]
    ): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val script = body.get("script")
            val diagnosis = body.get("diagnosis")
            val logs = Option(body.get("logs")).getOrElse("")
            val error = Option(body.get("error")).getOrElse("")
            val oldScriptPath = body.get("oldScriptPath")
            val priorIterationsJson = Option(body.get("priorIterations")).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info("API endpoint POST /tap/fix called, tapName: " + tapName +
                ", priorIterations: " + priorIterations.size)
            APIKeyValidator.validate(apiKey)

            if (script == null || script.isEmpty)
                throw new DatrisException("Script is required")
            if (diagnosis == null || diagnosis.isEmpty)
                throw new DatrisException("Diagnosis is required")

            val result = TapScriptFixer.fix(tapName, script, diagnosis, logs, error, oldScriptPath, priorIterations)

            // Update scriptPath in MongoDB if tap already exists
            val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, Option(tapName).getOrElse(""))
            if (existingTap != null) {
                TapConfigIO.write(existingTap.copy(scriptPath = result.scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/review"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def reviewScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Object]
    ): ResponseEntity[String] = {
        try {
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info(s"API endpoint POST /tap/review called, tapName: $tapName, recordCount: $recordCount, " +
                s"priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            val result = TapScriptReviewer.review(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            // Persist the new scriptPath onto the TapConfig if the tap already exists.
            if (result.rewritten) {
                val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
                if (existingTap != null && result.scriptPath != null && result.scriptPath != oldScriptPath) {
                    TapConfigIO.write(existingTap.copy(scriptPath = result.scriptPath))
                }
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("changes", result.changes)
            response.put("rewritten", Boolean.box(result.rewritten))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/optimize"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def optimizeScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Object]
    ): ResponseEntity[String] = {
        try {
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info(s"API endpoint POST /tap/optimize called, tapName: $tapName, recordCount: $recordCount, " +
                s"durationMs: $durationMs, priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            val opt = TapScriptOptimizer.optimize(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (existingTap != null && opt.scriptPath != null && opt.scriptPath != oldScriptPath) {
                TapConfigIO.write(existingTap.copy(scriptPath = opt.scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", opt.script)
            response.put("packages", opt.packages)
            response.put("scriptPath", opt.scriptPath)
            response.put("changes", opt.changes)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/test"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def testTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) testLimit: Integer,
        @RequestBody tapConfig: TapConfig
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/test called for tap: " + tapConfig.name + (if (testLimit != null) s" (testLimit=$testLimit)" else ""))
            APIKeyValidator.validate(apiKey)

            val hasTestScriptRef =
                (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                    (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
            if (!hasTestScriptRef)
                throw new DatrisException("Script path is required for testing")

            // Run in test mode (no push to pipeline). testLimit > 0 tells the
            // runner to inject DATRIS_TAP_TEST_LIMIT into the script env so a
            // well-written tap script caps its source reads. Cron/manual runs
            // never set this.
            val testLimitInt: Int = if (testLimit != null && testLimit.intValue() > 0) testLimit.intValue() else 0
            val testStartMs = System.currentTimeMillis()
            val result = TapRunner.run(tapConfig, mode = "test", testLimit = testLimitInt)
            val testDurationMs = System.currentTimeMillis() - testStartMs
            val gson = new Gson
            val recordsJson = if (result.records != null) JsonParser.parseString(result.records) else null
            val response = new java.util.HashMap[String, Any]()
            response.put("records", recordsJson)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)
            response.put("durationMs", java.lang.Long.valueOf(testDurationMs))

            // AI explanation if there's an error, 0 records, or logs contain notable indicators.
            // "deprecat" and "warning" catch cases where the script "succeeded" but the runtime
            // output is trying to tell us something (e.g. DeprecationWarning, urllib3
            // warnings, pandas FutureWarning) — the user shouldn't have to manually ask for a
            // review when the logs are already shouting.
            val logsHaveIssues = result.logs != null && result.logs.nonEmpty && {
                val lower = result.logs.toLowerCase
                lower.contains("error") || lower.contains("exception") ||
                lower.contains("failed") || lower.contains("forbidden") ||
                lower.contains("traceback") || lower.contains("deprecat") ||
                lower.contains("warning")
            }
            val needsExplanation = result.error != null || result.recordCount == 0 || logsHaveIssues
            if (needsExplanation) {
                val script =
                    try {
                        val env = DatrisEnvironment.current.environment
                        ObjectStoreUtil.readBucketObject(env + "-config", tapConfig.scriptPath).getOrElse("")
                    } catch {
                        case e: Exception =>
                            logger.warn("Failed to read tap script '" + tapConfig.scriptPath + "' for AI run explanation", e)
                            ""
                    }
                val aiExplanation = TapRunDiagnoser.explain(tapConfig.description, script, result)
                // Swallow the "all clear" response so the UI doesn't show an empty diagnosis
                // panel just because the heuristic fired on a benign warning.
                val isAllClear = aiExplanation != null &&
                    aiExplanation.trim.toLowerCase.stripSuffix(".").stripSuffix("!") == "no issues detected"
                if (aiExplanation != null && !isAllClear)
                    response.put("aiExplanation", aiExplanation)
            }

            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/run"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def runTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val name = Option(body.get("name")).map(_.toString).orNull
            logger.info("API endpoint POST /tap/run called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            if (name == null || name.isEmpty)
                throw new DatrisException("Tap name is required")

            val tapConfig = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (tapConfig == null)
                throw new DatrisException("Tap: " + name + " not found")

            // Scope check: `tap:run:owner=self` keys (e.g. rag-builder) may
            // only run taps they created. The loaded tap's createdByKeyLabel
            // is matched against the caller's label.
            CapabilityCheck.assertOwnerScope(request, "tap", "run", tapConfig.createdByKeyLabel)

            val mode = Option(body.get("mode")).map(_.toString.toLowerCase).getOrElse("test")

            // Optional per-run params. Stringify each value so the script sees
            // env vars regardless of whether the agent sent {start_date: "2026-05-01"}
            // or {limit: 1000}. Nested objects/arrays get JSON-encoded so a script
            // that wants structured params can json.loads() them back.
            val params: Map[String, String] = Option(body.get("params")) match {
                case Some(m: java.util.Map[_, _]) =>
                    val gson = new Gson
                    m.asInstanceOf[java.util.Map[String, Any]].asScala.toMap.map { case (k, v) =>
                        val sv = v match {
                            case null => ""
                            case s: String => s
                            case n: java.lang.Number => n.toString
                            case b: java.lang.Boolean => b.toString
                            case other => gson.toJson(other)
                        }
                        k -> sv
                    }
                case _ => Map.empty[String, String]
            }

            // Debounce push runs to suppress accidental duplicates (parallel tool calls,
            // double-clicks, transport retries). mode=test is read-only, no need to debounce.
            // checkAndAcceptRunDebounce returns Some(ageMs) when the request should be
            // rejected (a recent run is still inside the window), or None to proceed.
            val debouncedAgeMs: Option[Long] =
                if (mode == "run") checkAndAcceptRunDebounce(name) else None

            if (debouncedAgeMs.isDefined) {
                val ageMs = debouncedAgeMs.get
                logger.info(
                    "Debounced /tap/run for tap: " + name + " — last run started " + ageMs + "ms ago (window=" + TapAPIController.runDebounceWindowMs + "ms)"
                )
                val gson = new Gson
                val response = new java.util.HashMap[String, Any]()
                response.put("tap", name)
                response.put("description", tapConfig.description)
                response.put("status", "skipped")
                response.put("mode", mode)
                response.put("targetPipeline", tapConfig.targetPipeline)
                response.put("persisted", java.lang.Boolean.FALSE)
                response.put("persistedReason", "debounced")
                response.put(
                    "error",
                    "Tap '" + name + "' was triggered " + ageMs + " ms ago; ignoring this duplicate request " +
                        "(debounce window: " + TapAPIController.runDebounceWindowMs + " ms). Wait for the in-flight " +
                        "run to finish, then check `get_pipeline_status` or `get_tap_logs` for the outcome."
                )
                response.put("recordCount", Integer.valueOf(0))
                new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            } else {

                val result = TapRunner.run(tapConfig, mode = mode, params = params)

                // Save test run status when not pushing to pipeline
                if (mode != "run") {
                    val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                    val now = sdf.format(new java.util.Date())
                    val updated = tapConfig.copy(
                        lastTestRunStatus = if (result.error == null) "success" else "failure",
                        lastTestRunTime = now,
                        lastTestRunRecordCount = result.recordCount,
                        lastTestRunError = result.error,
                        lastTestRunDataType = result.dataType,
                        lastTestRunColumns = result.columns
                    )
                    TapConfigIO.write(updated)
                }

                val gson = new Gson
                val rawRecords = if (result.records != null) JsonParser.parseString(result.records) else null

                // Records policy:
                //   - mode=run: omit records entirely. They are in transit to the destination;
                //     the agent must verify via get_pipeline_status, not from this body.
                //     `recordCount` is enough to summarize what was submitted.
                //   - mode=test: include records as a preview, capped at TapAPIController.testRecordSampleSize.
                //     Set `recordsTruncated=true` when we trimmed it.
                val (recordsToReturn, recordsTruncated) =
                    if (mode == "run") (null, false)
                    else if (rawRecords != null && rawRecords.isJsonArray) {
                        val arr = rawRecords.getAsJsonArray
                        if (arr.size > TapAPIController.testRecordSampleSize) {
                            val sample = new com.google.gson.JsonArray
                            var i = 0
                            while (i < TapAPIController.testRecordSampleSize) { sample.add(arr.get(i)); i += 1 }
                            (sample: com.google.gson.JsonElement, true)
                        } else (rawRecords: com.google.gson.JsonElement, false)
                    } else (rawRecords: com.google.gson.JsonElement, false)

                val hasTargetPipeline = tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty
                val persisted = mode == "run" && hasTargetPipeline && result.error == null && result.recordCount > 0
                val persistedReason: String =
                    if (persisted) null
                    else if (mode != "run") "test_mode"
                    else if (result.error != null) "run_error"
                    else if (result.recordCount == 0) "no_records"
                    else if (!hasTargetPipeline) "no_target_pipeline"
                    else "unknown"
                val response = new java.util.HashMap[String, Any]()
                response.put("tap", name)
                response.put("description", tapConfig.description)
                response.put("status", if (result.error == null) "success" else "failure")
                response.put("mode", mode)
                response.put("targetPipeline", tapConfig.targetPipeline)
                response.put("persisted", java.lang.Boolean.valueOf(persisted))
                if (persistedReason != null) response.put("persistedReason", persistedReason)
                if (result.publisherToken != null) response.put("publisherToken", result.publisherToken)
                if (result.pipelineTokens != null && !result.pipelineTokens.isEmpty) response.put("pipelineTokens", result.pipelineTokens)
                if (recordsToReturn != null) response.put("records", recordsToReturn)
                if (recordsTruncated) response.put("recordsTruncated", java.lang.Boolean.TRUE)
                response.put("recordCount", Integer.valueOf(result.recordCount))
                response.put("error", result.error)
                response.put("logs", result.logs)
                response.put("dataType", result.dataType)
                response.put("columns", result.columns)
                new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            } // end else (non-debounced path)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    /** Atomically check + accept the run-debounce window for a tap. Returns Some(ageMs)
     *  when a recent run is still inside the window (caller should reject), or None when
     *  the slot is now reserved for the caller to proceed. */
    private def checkAndAcceptRunDebounce(tapName: String): Option[Long] = {
        val tenant = if (DatrisEnvironment.values.multiTenant) DatrisEnvironment.current.environment else "global"
        val dedupKey = tenant + "::" + tapName
        val now = System.currentTimeMillis()
        val accepted = new java.util.concurrent.atomic.AtomicBoolean(false)
        TapAPIController.recentRunStarts.compute(
            dedupKey,
            (_, existing) => {
                if (existing != null && (now - existing.longValue()) < TapAPIController.runDebounceWindowMs)
                    existing
                else {
                    accepted.set(true)
                    java.lang.Long.valueOf(now)
                }
            }
        )
        if (accepted.get()) None
        else Some(now - TapAPIController.recentRunStarts.get(dedupKey).longValue())
    }

    @GetMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLedger(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/ledger called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            val entries = TapDocumentLedgerIO.readByTap(DatrisEnvironment.current.tapLedgerTableName, name).asJava
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(entries), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTapLedger(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam(required = false) uri: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap/ledger called for tap: " + name + (if (uri != null) ", uri: " + uri else ""))
            APIKeyValidator.validate(apiKey)

            val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
            val bucket = DatrisEnvironment.current.environment + "-config"

            if (uri != null && uri.nonEmpty) {
                // Delete one entry, plus its staged file
                val entry = TapDocumentLedgerIO.read(ledgerTable, name, uri)
                if (entry != null && entry.stagedPath != null && entry.stagedPath.nonEmpty) {
                    try { ObjectStoreUtil.deleteBucketObject(bucket, entry.stagedPath) }
                    catch { case ex: Exception => logger.warn("Failed to delete staged doc " + entry.stagedPath + ": " + ex.getMessage) }
                }
                TapDocumentLedgerIO.delete(ledgerTable, name, uri)
                new ResponseEntity[String]("{\"message\": \"Ledger entry deleted\"}", HttpStatus.OK)
            } else {
                // Clear the entire ledger for this tap + all staged files
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
                new ResponseEntity[String]("{\"message\": \"Ledger cleared: " + entries.size + " entries\"}", HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
