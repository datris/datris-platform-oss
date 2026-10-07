package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, VersionActor}
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException, EntityVersion, UnityCatalogSync, ValidationException}
import ai.datris.util.{PipelineConfigIO, NoSQLDbUtil}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.DriverManager
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class PipelineAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineAPIController])

    @GetMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestParam pipeline: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline called with pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
            val gson = new Gson
            val json = gson.toJson(config)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Unity Catalog sync state for one pipeline: the last sync doc (or
      * `state: "never"`), whether the pipeline opted in, lineage and Iceberg
      * register status, and the three-level table coordinates (Databricks
      * table, or the registered Iceberg table for an object store). 404 for
      * an unknown pipeline. */
    @GetMapping(path = Array("/pipelines/{name}/unity-catalog"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getUnityCatalogState(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines/" + name + "/unity-catalog called")
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))

            val out = new JsonObject
            out.addProperty("pipeline", config.name)
            // Block or install default (DATRIS_UNITY_CATALOG_DEFAULT): UnityCatalogSync.effective.
            val (ucEnabled, ucEnabledBy, lineageEnabled) = UnityCatalogSync.stateFields(config, UnityCatalogSync.defaultEnabledFromEnv)
            out.addProperty("enabled", ucEnabled)
            out.addProperty("enabledBy", ucEnabledBy)
            // DATRIS_UNITY_CATALOG_SYNC=false: enabled may be true, but nothing is written.
            out.addProperty("syncSwitchedOff", UnityCatalogMetadataSync.syncSwitchedOff)

            val db = if (config.destination != null) config.destination.database else null
            if (db != null && db.useDatabricks) {
                val coords = new JsonObject
                coords.addProperty("catalog", db.dbName)
                coords.addProperty("schema", db.schema)
                coords.addProperty("table", db.table)
                coords.addProperty("qualified", DatabricksConnectionUtil.qualifiedTable(db))
                out.add("coordinates", coords)
            }

            // Object-store Iceberg pipelines register as <catalog>.<schema>.<pipeline>.
            // Read the block directly: the install default never applies to Iceberg.
            val uc = config.unityCatalog
            val objectStore = if (config.destination != null) config.destination.objectStore else null
            val icebergStore = objectStore != null && objectStore.fileFormat != null && objectStore.fileFormat.trim.equalsIgnoreCase("iceberg")
            val state = UnityCatalogSyncIO.read(config.name)
            if (icebergStore && uc != null && uc.catalog != null && uc.catalog.trim.nonEmpty) {
                val coords = new JsonObject
                val table = IcebergCatalogRegistrar.tableName(config.name)
                coords.addProperty("catalog", uc.catalog)
                coords.addProperty("schema", uc.schemaOrDefault)
                coords.addProperty("table", table)
                coords.addProperty("qualified", uc.catalog + "." + uc.schemaOrDefault + "." + table)
                coords.addProperty("kind", "iceberg")
                // catalogMode managed: the table lives where the catalog put
                // it (known after the first commit), not under prefixKey.
                val location = UnityCatalogStaleState.reportedLocation(
                    state,
                    uc.managedMode,
                    try "s3a://" + ObjectStoreSpark.resolveBucket(objectStore) + "/" + objectStore.prefixKey
                    catch { case scala.util.control.NonFatal(_) => null }
                )
                coords.addProperty("location", location)
                out.add("coordinates", coords)
            }

            val registerEnabled = icebergStore && uc != null && uc.enabled && uc.registerOn
            out.addProperty("registerEnabled", registerEnabled)
            // Iceberg register status: off | never | registered | stale | error
            // | rest | refused. stale/error come from the uc-register: lines in
            // lastError; the stale warning carries the fixed "Unity Catalog still
            // points at" text. In catalogMode rest, `rest` = the last run
            // committed through the catalog (no stale pointer possible) and
            // `refused` = it wrote path-based (restRefusedReason says why).
            // In catalogMode managed, `managed` = the last run committed
            // through the catalog at the catalog-chosen location.
            // A doc recording a managed commit wins over the config's current
            // mode (UnityCatalogStaleState.registerStatus).
            val configMode = if (uc != null) uc.catalogModeOrDefault else null
            if (icebergStore && (uc != null || IcebergRestSession.managedCommitted(state)))
                out.addProperty("catalogMode", UnityCatalogStaleState.reportedCatalogMode(state, configMode))
            val registerStatus = UnityCatalogStaleState.registerStatus(state, registerEnabled, configMode)
            out.addProperty("register", registerStatus)
            if (state != null) {
                out.addProperty("registeredMetadataLocation", state.registeredMetadataLocation)
                out.addProperty("lastRegisterAt", state.lastRegisterAt)
                out.addProperty("restMetadataLocation", state.restMetadataLocation)
                out.addProperty("lastRestCommitAt", state.lastRestCommitAt)
                out.addProperty("restRefusedReason", state.restRefusedReason)
                out.addProperty("restCreatedTable", state.restCreatedTable)
            }
            out.addProperty("lineageEnabled", lineageEnabled)
            // Lineage publish status: off (knob/opt-in) | never | error | published.
            out.addProperty(
                "lineage",
                if (!lineageEnabled) "off"
                else if (state != null && state.lastError != null && state.lastError.split("\n").exists(_.startsWith(UnityCatalogLineagePublisher.ErrorPrefix)))
                    "error"
                else if (state != null && state.lastLineageAt != null) "published"
                else "never"
            )
            if (state == null) out.addProperty("state", "never")
            else {
                out.addProperty("state", UnityCatalogStaleState.topLevelState(state, registerStatus))
                out.addProperty("lastSyncAt", state.lastSyncAt)
                out.addProperty("lastRunId", state.lastRunId)
                out.addProperty("commentsHash", state.commentsHash)
                out.addProperty("tagsHash", state.tagsHash)
                out.addProperty("propertiesHash", state.propertiesHash)
                out.addProperty("lastError", state.lastError)
                out.addProperty("lineageHash", state.lineageHash)
                out.addProperty("lastLineageAt", state.lastLineageAt)
            }
            // enabledBy is an explicit JSON null when Unity Catalog is off; every
            // other null field (nested ones included) stays omitted, as before.
            new ResponseEntity[String](UnityCatalogStateJson.toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @GetMapping(path = Array("/pipelines"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelines(@RequestHeader(name = "x-api-key", required = false) apiKey: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines called")
            APIKeyValidator.validate(apiKey)

            val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.current.pipelineTableName, "name")
            val allConfigs = pipelineNames.map(name => {
                PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            })

            // Scope-aware filter for keys whose only `pipeline:read` grant is
            // `pipeline:read:owner=self` — return ONLY pipelines this key
            // created. A key with an unscoped `pipeline:read` or `*:*` skips
            // the filter entirely. Server-side filtering keeps the result
            // shape consistent with single-resource reads and prevents
            // leaking metadata (names, destinations) about pipelines the
            // caller has no business seeing.
            val filteredConfigs =
                if (CapabilityCheck.hasOnlyOwnerSelfScope(request, "pipeline", "read")) {
                    val ownerLabel = ResolvedKeyAccess.keyLabel(request).orNull
                    allConfigs.filter(c => c != null && c.createdByKeyLabel != null && c.createdByKeyLabel == ownerLabel)
                } else {
                    allConfigs
                }

            val gson = new Gson
            val json = gson.toJson(filteredConfigs.asJava)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @PostMapping(path = Array("/pipeline"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putPipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "changeNote", required = false) changeNote: String,
        @RequestBody config: PipelineConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline with pipeline name: " + config.name)
            APIKeyValidator.validate(apiKey)

            val withDefaults = PipelineValidatorUtil.applyDefaults(config)
            PipelineValidatorUtil.validate(withDefaults)

            // Postgres is install-time optional (POSTGRES_ENABLED=0 skips the
            // bundled container), so a postgres destination can be selected in a
            // deployment where no Postgres is reachable. Probe now — same probe
            // as /health and /destinations/available — and reject the save with
            // an actionable 400 instead of letting the first run die in the
            // loader. Postgres only: mongodb/minio are required core services,
            // and Snowflake/Databricks already fail with actionable
            // CredentialResolver errors when their credentials are missing.
            if (
                withDefaults.destination != null && withDefaults.destination.database != null &&
                withDefaults.destination.database.usePostgres
            ) {
                val probeFailure = PostgresQueryUtil.probeError()
                if (probeFailure.isDefined) {
                    logger.warn("POST /pipeline rejected: postgres destination selected but Postgres is unreachable: " + probeFailure.get)
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        "{\"error\": \"Postgres is not installed/reachable in this deployment. Enable it in .env (POSTGRES_ENABLED=1) or point POSTGRES_JDBC_URL at an external Postgres, then re-run 'docker compose up -d' — or choose another destination.\"}"
                    )
                }
            }

            val modifiedConfig = PipelineValidatorUtil.modify(withDefaults)

            // Stamp the issuing key's label so `owner=self` capabilities can
            // match resources this key created. Null when no key is present
            // (auth disabled or public endpoint) — that resource will not
            // match `owner=self` for anyone, which is intended.
            val tagged = ResolvedKeyAccess.keyLabel(request) match {
                case Some(label) => modifiedConfig.copy(createdByKeyLabel = label)
                case None => modifiedConfig
            }

            // Definition-edit write → mints a new immutable version snapshot.
            val existing = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tagged.name)
            // Tags/provenance preservation: clients rebuild the body from
            // scratch, so a body that omits these entirely must not wipe them
            // on an unrelated edit. An explicit empty list / stamp=false in the
            // body still clears/disables.
            val preserved = if (existing != null)
                tagged.copy(
                    tags = if (tagged.tags == null) existing.tags else tagged.tags,
                    provenance = if (tagged.provenance == null) existing.provenance else tagged.provenance,
                    // Authority declarations follow the same rule: omitted ⇒ keep.
                    authoritative = if (tagged.authoritative == null) existing.authoritative else tagged.authoritative,
                    destination =
                        if (tagged.destination != null && tagged.destination.authoritative == null && existing.destination != null)
                            tagged.destination.copy(authoritative = existing.destination.authoritative)
                        else tagged.destination
                )
            else tagged
            // Source-of-authority rules are validated against every other
            // pipeline: a declared kind must exist, and one dataset has at most
            // one authoritative writer.
            val others =
                try PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
                catch { case _: Exception => Nil }
            LineageService.authorityConflict(preserved, others).foreach { problem =>
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"" + problem.replace("\"", "'") + "\"}")
            }
            val note = if (changeNote != null && changeNote.nonEmpty) changeNote
            else if (existing != null) "updated" else "created"
            // A new pipeline must not inherit Unity Catalog sync state left by
            // an earlier pipeline of the same name (deleted out of band).
            if (existing == null) UnityCatalogStaleState.clearOnCreate(preserved)
            PipelineConfigIO.writeVersioned(preserved, note, VersionActor.resolve(request))

            // If the source is a database, initialize the pipeline pull table
            if (modifiedConfig.source.databaseAttributes != null)
                PipelinePullTableUtil.initialize(modifiedConfig.name, modifiedConfig.source.databaseAttributes.cronExpression)

            // Advisory only: the pipeline is saved either way. Unity Catalog on
            // Databricks cannot serve register/rest modes for an object-store
            // Iceberg table; say so now rather than at the first run.
            // The hint names the secret's workspace host, so it is only built
            // for a secret this caller may read (same predicate as
            // list_platform_secrets); otherwise it would be an existence and
            // host oracle for secrets behind a narrower read scope.
            val canReadSecret: String => Boolean = s =>
                scala.util.Try(
                    SecretsUtil.getSecretMap(DatrisEnvironment.current.environment + "/" + s)
                        .exists(f => UnityCatalogAPIController.canRead(request, f))
                ).getOrElse(false)
            val warnings = UnityCatalogSaveHints.forConfig(
                preserved,
                PipelineAPIController.hintHostOf(canReadSecret, UnityCatalogSaveHints.resolveHost)
            )
            warnings.foreach(w => logger.warn("POST /pipeline " + preserved.name + ": " + w))
            // CodeGen scripts: generate (or keep) the AI rule / AI
            // transformation script now. Never fails the save: a script that
            // could not be generated is pending and the first run generates it.
            val outcomes =
                try PipelineScripts.onSave(existing, preserved, ResolvedKeyAccess.keyLabel(request).orNull)
                catch {
                    case e: Exception =>
                        logger.warn("POST /pipeline " + preserved.name + ": CodeGen script save hook failed: " + e.getMessage)
                        Nil
                }
            val out = PipelineAPIController.saveResponseBody(warnings.toList, outcomes)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out.toString)
        } catch {
            case v: ValidationException =>
                // An invalid config is the caller's to fix: 400, same body
                // shape as the 500 below, no stack trace in the log.
                logger.warn("POST /pipeline rejected: " + v.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](QueryAPIController.errorBody(v))
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** The pipeline's stored CodeGen scripts (AI rule, AI transformation):
      * text, when and by which model each was generated, and whether it is
      * ready or pending. 404 for an unknown pipeline. */
    @GetMapping(path = Array("/pipelines/{name}/codegen-scripts"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getCodegenScripts(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines/" + name + "/codegen-scripts called")
            APIKeyValidator.validate(apiKey)
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            CapabilityCheck.assertOwnerScope(request, "pipeline", "read", config.createdByKeyLabel)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(PipelineAPIController.codegenScriptsBody(config, PipelineScripts).toString)
        } catch {
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Force a new CodeGen script for one kind (`dataQuality` |
      * `transformation`). Delimited pipelines generate now from the schema and
      * replace the stored script (on failure the error is returned with a 502
      * and the current script stays); JSON/XML pipelines are marked pending so
      * the next run generates. An unknown kind is 400 before any config read.
      * `storage` (`github` | `builtin`, default the script's current backend)
      * moves the script; `overwrite=true` replaces a repository file that was
      * edited since the recorded commit (otherwise that is a 409). */
    @PostMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/regenerate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def regenerateCodegenScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        request: HttpServletRequest,
        @RequestParam(name = "storage", required = false) storage: String = null,
        @RequestParam(name = "overwrite", required = false) overwrite: java.lang.Boolean = null
    ): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind))
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body[String](QueryAPIController.errorBody(new DatrisException(PipelineScripts.unknownKindMessage(kind))))
        if (storage != null && storage.nonEmpty && PipelineScripts.normalizeStorage(storage).isEmpty)
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body[String](QueryAPIController.errorBody(new DatrisException(PipelineScripts.unknownStorageMessage(storage))))
        try {
            logger.info("API endpoint POST /pipelines/" + name + "/codegen-scripts/" + kind + "/regenerate called")
            APIKeyValidator.validate(apiKey)
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            CapabilityCheck.assertOwnerScope(request, "pipeline", "create", config.createdByKeyLabel)
            if (PipelineScripts.instructionOf(config, kind).isEmpty)
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body[String](
                        QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " has no AI " + kind + " instruction to generate a script for"))
                    )
            val actor = ResolvedKeyAccess.keyLabel(request).orNull
            val force = overwrite != null && overwrite.booleanValue
            PipelineScripts.regenerate(config, kind, actor, Option(storage).filter(_.nonEmpty).orNull, force) match {
                case Right(rec) =>
                    auditCodegenScript(request, "regenerate", name, kind, "success", None)
                    val body = PipelineAPIController.codegenScriptsBody(config, PipelineScripts)
                    val entry = body.getAsJsonArray("scripts").asScala.map(_.getAsJsonObject).find(_.get("kind").getAsString == kind)
                    ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(entry.getOrElse(new JsonObject).toString)
                case Left(error) =>
                    auditCodegenScript(request, "regenerate", name, kind, "failure", Some(error))
                    val status =
                        if (error.contains(PipelineScripts.ConflictMarker)) HttpStatus.CONFLICT
                        else if (error.startsWith("Unknown script storage")) HttpStatus.BAD_REQUEST
                        else HttpStatus.BAD_GATEWAY
                    ResponseEntity
                        .status(status)
                        .body[String](
                            QueryAPIController.errorBody(new DatrisException("CodeGen script was not regenerated (the current script is unchanged): " + error))
                        )
            }
        } catch {
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Adopt the repository's branch-head version of a repository-backed
      * CodeGen script (an edit made in the repository): the script's record
      * pins the head commit; later runs execute it. 400 for an unknown kind or
      * a built-in script. */
    @PostMapping(path = Array("/pipelines/{name}/codegen-scripts/{kind}/pull"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def pullCodegenScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @PathVariable("kind") kind: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        if (!PipelineScripts.Kinds.contains(kind))
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body[String](QueryAPIController.errorBody(new DatrisException(PipelineScripts.unknownKindMessage(kind))))
        try {
            logger.info("API endpoint POST /pipelines/" + name + "/codegen-scripts/" + kind + "/pull called")
            APIKeyValidator.validate(apiKey)
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            CapabilityCheck.assertOwnerScope(request, "pipeline", "create", config.createdByKeyLabel)
            val response = PipelineAPIController.pullCodegenScriptWith(PipelineScripts, name, kind, ResolvedKeyAccess.keyLabel(request).orNull)
            if (response.getStatusCode.is2xxSuccessful) auditCodegenScript(request, "pull", name, kind, "success", None)
            else auditCodegenScript(request, "pull", name, kind, "failure", Some(String.valueOf(response.getBody)))
            response
        } catch {
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    private def auditCodegenScript(
        request: HttpServletRequest,
        operation: String,
        pipeline: String,
        kind: String,
        outcome: String,
        error: Option[String]
    ): Unit =
        try {
            if (ai.datris.audit.AuditLog.enabled)
                ai.datris.audit.AuditLog.submit(
                    ai.datris.audit.AuditEntry(
                        ts = java.time.Instant.now(),
                        actor = ai.datris.audit.AuditActor.resolve(request),
                        category = "pipeline",
                        action = "codegen-script-" + operation + ":" + kind,
                        resourceType = Some("pipeline"),
                        resourceName = Some(pipeline),
                        outcome = outcome,
                        errorMessage = error,
                        request = Some(ai.datris.audit.AuditLog.requestInfo(request))
                    )
                )
        } catch {
            case ex: Exception => logger.warn("Audit of CodeGen script " + operation + " failed for " + pipeline + ": " + ex.getMessage)
        }

    @DeleteMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deletePipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam pipeline: String,
        @RequestParam(defaultValue = "true") deleteData: String,
        @RequestParam(defaultValue = "true") deleteConfig: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /pipeline with pipeline name: " + pipeline + ", deleteData: " + deleteData + ", deleteConfig: " + deleteConfig)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            val warnings = deletePipelineInternal(config, request, deleteData, deleteConfig)

            val out = new JsonObject
            val arr = new com.google.gson.JsonArray
            warnings.foreach(arr.add(_))
            out.add("warnings", arr)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out.toString)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Body of DELETE /pipeline once the config is loaded: owner-scope check,
      * pull-table entry, destination data, tap ledgers, config and versions.
      * Shared with the catalog cascade delete (`CatalogAPIController`), which
      * deletes pipelines with their data as the UI does. Throws on failure.
      * `checkScope=false` only when the caller has already run a fuller
      * (owner + catalog) scope check on this config. Returns warnings for the
      * caller (e.g. a Unity Catalog table that still has to be dropped). */
    def deletePipelineInternal(
        config: PipelineConfig,
        request: HttpServletRequest,
        deleteData: String = "true",
        deleteConfig: String = "true",
        checkScope: Boolean = true
    ): Seq[String] = {
        val pipeline = config.name
        // Scope check: a key with `pipeline:delete:owner=self` may only
        // delete pipelines it created. Loaded resource provides the
        // `createdByKeyLabel` we compare against the caller's label.
        if (checkScope) CapabilityCheck.assertOwnerScope(request, "pipeline", "delete", config.createdByKeyLabel)

        // Deleting the config without also deleting the data is disallowed.
        // Leaving orphaned rows/collections/tables behind with no pipeline to
        // own them creates hard-to-debug ghost state; any caller asking for
        // config-only gets data-delete folded in implicitly.
        val deleteConfigBool = deleteConfig.equalsIgnoreCase("true")
        val deleteDataBool = deleteData.equalsIgnoreCase("true") || deleteConfigBool

        if (deleteConfigBool && config.source.databaseAttributes != null)
            PipelinePullTableUtil.deleteEntryIfExists(config.name)

        // Unity Catalog state, read before anything is deleted: a table
        // committed through the REST catalog is not dropped by Datris.
        val ucPrevious = unityCatalogStateForDelete(config)
        val warnings = Seq.newBuilder[String]
        // A managed commit's doc always survives (the table lives on in Unity
        // Catalog and a recreated pipeline continues it).
        var ucTableFilesKept = UnityCatalogDeleteAdvice.keepStateOnDelete(ucPrevious, dataDeleted = true, adviceGiven = false)

        // Clean up destination data
        if (deleteDataBool && config.destination != null) {
            val objectStoreDataDeleted = cleanupDestinationData(config)
            UnityCatalogDeleteAdvice.forPipeline(config, ucPrevious, objectStoreDataDeleted, deleteConfigBool).foreach { advice =>
                warnings += advice
                ucTableFilesKept = UnityCatalogDeleteAdvice.keepStateOnDelete(ucPrevious, objectStoreDataDeleted, adviceGiven = true)
                logger.warn("Pipeline delete: " + pipeline + ": " + advice)
                auditUnityCatalogDelete(request, pipeline, advice)
            }
            // Wipe document-tap ledgers/staged files for any tap targeting this
            // pipeline. The ledger records "already-processed URIs"; leaving it
            // intact after the destination is emptied would cause the next tap
            // run to skip every doc and land nothing in the now-empty pipeline.
            cleanupDocumentTapLedgers(pipeline)
        }

        // Delete the json configuration
        if (deleteConfigBool) {
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.current.pipelineTableName, "name", pipeline)
            // Hard-delete all definition-version snapshots for this pipeline
            // (snapshots pin no scripts; the stored CodeGen scripts are removed below).
            try {
                EntityVersionIO.deleteAllForEntity(DatrisEnvironment.current.pipelineVersionTableName, pipeline)
            } catch {
                case ex: Exception => logger.warn("Pipeline version cleanup failed for " + pipeline + ": " + ex.getMessage)
            }
            // Stored CodeGen scripts (objects under pipeline-scripts/<pipeline>/
            // and their index records). Non-fatal, as for tap scripts.
            try PipelineScripts.deleteAll(pipeline)
            catch {
                case ex: Exception => logger.warn("CodeGen script cleanup failed for " + pipeline + ": " + ex.getMessage)
            }
            // Unity Catalog sync state for this pipeline (<env>-uc-sync).
            // Kept while the catalog-committed table's files still exist, so
            // a pipeline recreated at the same name and prefix keeps the
            // guard against a path write forking the catalog's history, and
            // always for a catalogMode managed commit (keepStateOnDelete).
            if (ucPrevious != null && !ucTableFilesKept) {
                try UnityCatalogSyncIO.delete(pipeline)
                catch {
                    case ex: Exception => logger.warn("Unity Catalog state cleanup failed for " + pipeline + ": " + ex.getMessage)
                }
            }
        }
        warnings.result()
    }

    /** The pipeline's Unity Catalog sync state, or null (none, or unreadable:
      * the delete goes ahead without the advice). */
    private def unityCatalogStateForDelete(config: PipelineConfig): ai.datris.model.UnityCatalogSyncState =
        try UnityCatalogSyncIO.read(config.name)
        catch {
            case ex: Exception =>
                logger.warn("Could not read Unity Catalog state for pipeline " + config.name + " on delete: " + ex.getMessage)
                null
        }

    private def auditUnityCatalogDelete(request: HttpServletRequest, pipeline: String, advice: String): Unit =
        try {
            if (ai.datris.audit.AuditLog.enabled)
                ai.datris.audit.AuditLog.submit(
                    ai.datris.audit.AuditEntry(
                        ts = java.time.Instant.now(),
                        actor = ai.datris.audit.AuditActor.resolve(request),
                        category = "unity-catalog",
                        action = "pipeline-delete",
                        resourceType = Some("pipeline"),
                        resourceName = Some(pipeline),
                        outcome = "warning",
                        errorMessage = Some(advice),
                        request = Some(ai.datris.audit.AuditLog.requestInfo(request))
                    )
                )
        } catch {
            case ex: Exception => logger.warn("Audit of Unity Catalog delete advice failed for " + pipeline + ": " + ex.getMessage)
        }

    /**
     * Clear tap-ledger entries and MinIO-staged files for every document tap
     * targeting this pipeline. Called when the pipeline's destination data is
     * wiped so the next tap run re-ingests every document from source.
     * Swallows individual MinIO delete errors — the ledger row is authoritative,
     * so a missing staged file shouldn't block the rest of the cleanup.
     */
    private def cleanupDocumentTapLedgers(pipelineName: String): Unit = {
        try {
            val env = DatrisEnvironment.current
            val ledgerTable = env.tapLedgerTableName
            val bucket = env.environment + "-config"
            val taps = TapConfigIO.readAll(env.tapTableName)
            val affected = taps.filter(t =>
                t != null && "document" == t.tapType &&
                    t.targetPipeline != null && t.targetPipeline == pipelineName
            )
            if (affected.isEmpty) return
            affected.foreach { tap =>
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, tap.name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, tap.name)
                logger.info("Cleared document tap ledger for tap '" + tap.name + "' (" + entries.size + " entries) after pipeline data delete: " + pipelineName)
            }
        } catch {
            case e: Exception =>
                logger.warn("Failed to clean up document tap ledgers for pipeline '" + pipelineName + "': " + e.getMessage)
        }
    }

    /** Returns true when the object-store destination's files were deleted
      * (false when there is none, the prefix is shared, or the delete failed). */
    private def cleanupDestinationData(config: PipelineConfig): Boolean = {
        val dest = config.destination
        var objectStoreDataDeleted = false

        // PostgreSQL — DROP TABLE
        if (dest.database != null && dest.database.usePostgres) {
            try {
                val secrets = SecretsRetrieverUtil.postgresSecrets()
                Class.forName("org.postgresql.Driver")
                val properties = new Properties()
                properties.setProperty("user", secrets.username)
                properties.setProperty("password", secrets.password)
                val pgDbName = if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment else dest.database.dbName
                val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
                val jdbcUrl = if (afterProtocol.contains("/")) secrets.jdbcUrl else secrets.jdbcUrl + "/" + pgDbName
                val conn = DriverManager.getConnection(jdbcUrl, properties)
                try {
                    val schema = if (dest.database.schema != null) dest.database.schema else "public"
                    val stmt = conn.createStatement()
                    stmt.execute("DROP TABLE IF EXISTS \"" + schema + "\".\"" + dest.database.table + "\" CASCADE")
                    stmt.close()
                    logger.info("Dropped PostgreSQL table: " + schema + "." + dest.database.table)
                } finally {
                    conn.close()
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop PostgreSQL table: " + e.getMessage)
            }
        }

        // MongoDB — drop collection
        if (dest.database != null && dest.database.useMongoDB) {
            try {
                val secrets = SecretsRetrieverUtil.mongoDbSecrets()
                val connString = new com.mongodb.ConnectionString(secrets.connectionString)
                val settings = com.mongodb.MongoClientSettings.builder().applyConnectionString(connString).build()
                val client = com.mongodb.client.MongoClients.create(settings)
                try {
                    val dbName = if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment
                    else if (dest.database.dbName != null) dest.database.dbName else "datris"
                    client.getDatabase(dbName).getCollection(dest.database.table).drop()
                    logger.info("Dropped MongoDB collection: " + dbName + "." + dest.database.table)
                } finally {
                    client.close()
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop MongoDB collection: " + e.getMessage)
            }
        }

        // Snowflake — DROP TABLE in the external account, connecting with the
        // pipeline's own credentialsSecret/warehouse/role (the loader role owns
        // the tables it created, so it is allowed to drop them).
        if (dest.database != null && dest.database.useSnowflake) {
            try {
                SnowflakeConnectionUtil.withConnection(dest.database) { conn =>
                    val stmt = conn.createStatement()
                    try {
                        stmt.execute("DROP TABLE IF EXISTS " + SnowflakeConnectionUtil.qualifiedTable(dest.database))
                        logger.info("Dropped Snowflake table: " + dest.database.dbName + "." + dest.database.schema + "." + dest.database.table)
                    } finally {
                        stmt.close()
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop Snowflake table: " + e.getMessage)
            }
        }

        // Databricks — DROP TABLE in the external workspace, connecting with
        // the pipeline's own credentialsSecret/warehouse (the service principal
        // owns the tables it created, so it is allowed to drop them). The
        // shared datris_staging volume is left alone — other pipelines use it.
        if (dest.database != null && dest.database.useDatabricks) {
            try {
                DatabricksConnectionUtil.withConnection(dest.database, pipelineName = config.name) { conn =>
                    val stmt = conn.createStatement()
                    try {
                        stmt.execute("DROP TABLE IF EXISTS " + DatabricksConnectionUtil.qualifiedTable(dest.database))
                        logger.info("Dropped Databricks table: " + dest.database.dbName + "." + dest.database.schema + "." + dest.database.table)
                    } finally {
                        stmt.close()
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop Databricks table: " + e.getMessage)
            }
        }

        // pgvector — DROP TABLE
        if (dest.pgvector != null) {
            try {
                val secretName = DatrisEnvironment.current.pgvectorSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val jdbcUrl = secretMap.get("jdbcUrl")
                    val username = secretMap.get("username")
                    val password = secretMap.get("password")
                    if (jdbcUrl != null) {
                        val pgvJdbcUrl = if (DatrisEnvironment.current.multiTenant) {
                            jdbcUrl.replaceFirst("/[^/]*$", "/" + DatrisEnvironment.current.environment)
                        } else jdbcUrl
                        Class.forName("org.postgresql.Driver")
                        val properties = new Properties()
                        properties.setProperty("user", username)
                        properties.setProperty("password", password)
                        val conn = DriverManager.getConnection(pgvJdbcUrl, properties)
                        try {
                            val schema = if (dest.pgvector.schemaName != null) dest.pgvector.schemaName else "public"
                            val stmt = conn.createStatement()
                            stmt.execute("DROP TABLE IF EXISTS \"" + schema + "\".\"" + dest.pgvector.tableName + "\" CASCADE")
                            stmt.close()
                            logger.info("Dropped pgvector table: " + schema + "." + dest.pgvector.tableName)
                        } finally {
                            conn.close()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop pgvector table: " + e.getMessage)
            }
        }

        // Qdrant — delete collection
        if (dest.qdrant != null) {
            try {
                val secretName = DatrisEnvironment.current.qdrantSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val grpcPort = Option(secretMap.get("port")).map(_.toInt).getOrElse(6334)
                        val restPort = grpcPort - 1
                        val url = "http://" + host + ":" + restPort + "/collections/" + dest.qdrant.collectionName
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("api-key", k))
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Qdrant collection: " + dest.qdrant.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Qdrant collection: " + e.getMessage)
            }
        }

        // Weaviate — delete class
        if (dest.weaviate != null) {
            try {
                val secretName = DatrisEnvironment.current.weaviateSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("8079")
                        val scheme = Option(secretMap.get("scheme")).getOrElse("http")
                        val url = scheme + "://" + host + ":" + port + "/v1/schema/" + dest.weaviate.className
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("Authorization", "Bearer " + k))
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Weaviate class: " + dest.weaviate.className)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Weaviate class: " + e.getMessage)
            }
        }

        // Milvus — drop collection
        if (dest.milvus != null) {
            try {
                val secretName = DatrisEnvironment.current.milvusSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("19530")
                        val url = "http://" + host + ":" + port + "/v2/vectordb/collections/drop"
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("POST")
                        connection.setDoOutput(true)
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        connection.setRequestProperty("Content-Type", "application/json")
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("Authorization", "Bearer " + k))
                        try {
                            val payload = "{\"collectionName\": \"" + dest.milvus.collectionName + "\"}"
                            val os = connection.getOutputStream
                            os.write(payload.getBytes)
                            os.close()
                            connection.getResponseCode
                            logger.info("Dropped Milvus collection: " + dest.milvus.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop Milvus collection: " + e.getMessage)
            }
        }

        // Chroma — delete collection
        if (dest.chroma != null) {
            try {
                val secretName = DatrisEnvironment.current.chromaSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("8000")
                        val url =
                            "http://" + host + ":" + port + "/api/v2/tenants/default_tenant/databases/default_database/collections/" + dest.chroma.collectionName
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Chroma collection: " + dest.chroma.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Chroma collection: " + e.getMessage)
            }
        }

        // Object Store — recursively delete s3a://<bucket>/<prefixKey> through
        // the Hadoop FileSystem with per-bucket config applied (same route as
        // deleteBeforeWrite in SparkObjectStoreLoader), so it works for both
        // the built-in MinIO and provider=s3 buckets. prefixKey is not forced
        // to be unique across pipelines, so refuse to delete when another
        // pipeline's objectStore destination shares or nests with this prefix —
        // a blind prefix delete would take out data a live pipeline still reads.
        if (dest.objectStore != null) {
            try {
                val sharedWith = pipelinesSharingObjectStorePrefix(config)
                if (sharedWith.nonEmpty) {
                    logger.warn(
                        "Skipped object store data delete for pipeline '" + config.name + "': prefixKey '" +
                            dest.objectStore.prefixKey + "' overlaps with pipeline(s): " + sharedWith.mkString(", ")
                    )
                } else {
                    // Deletes only s3a://<bucket>/<prefixKey>. A catalogMode
                    // managed table lives where Unity Catalog put it (e.g.
                    // __unitystorage), never under prefixKey, so it is never
                    // touched here (UnityCatalogDeleteAdvice says so).
                    ObjectStoreSpark.deleteDestinationData(dest.objectStore)
                    objectStoreDataDeleted = true
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete object store data: " + e.getMessage)
            }
        }

        // Scratch — recursively delete `_scratch/<pipeline>/` from <env>-data and
        // nothing else. The prefix comes from ScratchPaths.prefix, which refuses
        // a blank or path-traversing pipeline segment; assert it here too before
        // handing it to the recursive delete.
        if (dest.scratch != null) {
            try {
                val prefix = ScratchPaths.prefix(config.name)
                if (!prefix.startsWith(ScratchPaths.Root) || prefix == ScratchPaths.Root)
                    throw new IllegalStateException("refusing to delete scratch data: prefix '" + prefix + "' is not under " + ScratchPaths.Root)
                ScratchLoader.deleteScratchData(config.name)
            } catch {
                case e: Exception => logger.warn("Failed to delete scratch data for pipeline '" + config.name + "': " + e.getMessage)
            }
        }
        objectStoreDataDeleted
    }

    /** Names of other pipelines whose objectStore destination would be hit by a
     * recursive delete of this pipeline's prefix (same effective bucket, equal
     * or nested prefix). Called before the config row is removed, so this
     * pipeline itself is still in the table and is excluded by name.
     */
    private def pipelinesSharingObjectStorePrefix(config: PipelineConfig): List[String] = {
        val table = DatrisEnvironment.current.pipelineTableName
        NoSQLDbUtil.getItemsKeysByKeyName(table, "name")
            .filter(_ != config.name)
            .map(name => PipelineConfigIO.read(table, name))
            .filter(other =>
                other != null && other.destination != null && other.destination.objectStore != null &&
                    ObjectStoreSpark.destinationsOverlap(config.destination.objectStore, other.destination.objectStore)
            )
            .map(_.name)
    }
}

object PipelineAPIController {

    /** POST /api/v1/pipeline body: `warnings` (the given ones plus one per
      * pending CodeGen script, naming its reason) and `codegenScripts`
      * ([{kind, status, pendingReason?, generatedAt?, model?, warning?}];
      * `warning` when a repository commit was rejected). */
    def saveResponseBody(warnings: List[String], outcomes: List[PipelineScripts.Outcome]): JsonObject = {
        val out = new JsonObject
        val arr = new com.google.gson.JsonArray
        warnings.foreach(arr.add(_))
        outcomes.filter(_.status == PipelineScripts.Pending).foreach { o =>
            arr.add(
                "CodeGen " + o.kind + " script is pending: " + Option(o.pendingReason).getOrElse("not generated") +
                    ". The pipeline is saved; its first run generates and stores the script."
            )
        }
        outcomes.filter(_.warning != null).foreach(o => arr.add(o.warning))
        out.add("warnings", arr)
        val scripts = new com.google.gson.JsonArray
        outcomes.foreach { o =>
            val e = new JsonObject
            e.addProperty("kind", o.kind)
            e.addProperty("status", o.status)
            if (o.pendingReason != null) e.addProperty("pendingReason", o.pendingReason)
            if (o.generatedAt != null) e.addProperty("generatedAt", o.generatedAt)
            if (o.model != null) e.addProperty("model", o.model)
            if (o.warning != null) e.addProperty("warning", o.warning)
            scripts.add(e)
        }
        out.add("codegenScripts", scripts)
        out
    }

    /** GET /api/v1/pipelines/{name}/codegen-scripts body: `scripts`, one entry
      * per AI kind the pipeline has (kind, instruction, script, generatedAt,
      * model, modelIsCurrent, status, pendingReason, origin, storage,
      * repoPath, commitSha, drift, and headSha when drift is true). A kind
      * with no record yet is pending. */
    def codegenScriptsBody(config: PipelineConfig, scripts: PipelineScripts): JsonObject = {
        val out = new JsonObject
        out.addProperty("pipeline", config.name)
        val arr = new com.google.gson.JsonArray
        val currentModel = scripts.modelNow
        PipelineScripts.Kinds.foreach { kind =>
            PipelineScripts.instructionOf(config, kind).foreach { instruction =>
                arr.add(codegenScriptEntry(scripts, config.name, kind, instruction, currentModel))
            }
        }
        out.add("scripts", arr)
        out
    }

    /** One entry of the GET body. */
    def codegenScriptEntry(scripts: PipelineScripts, pipeline: String, kind: String, instruction: String, currentModel: String): JsonObject = {
        val e = new JsonObject
        e.addProperty("kind", kind)
        e.addProperty("instruction", instruction)
        scripts.record(pipeline, kind) match {
            case Some(r) =>
                e.addProperty("script", scripts.readText(pipeline, kind).orNull)
                e.addProperty("generatedAt", r.generatedAt)
                e.addProperty("model", r.model)
                e.addProperty("modelIsCurrent", r.model != null && r.model == currentModel)
                e.addProperty("status", Option(r.status).getOrElse(PipelineScripts.Ready))
                e.addProperty("pendingReason", r.pendingReason)
                e.addProperty("origin", r.origin)
                e.addProperty("storage", r.storage)
                e.addProperty("repoPath", r.scriptRepoPath)
                e.addProperty("commitSha", r.scriptCommitSha)
                scripts.drift(pipeline, kind) match {
                    case Some(head) =>
                        e.addProperty("drift", true)
                        e.addProperty("headSha", head)
                    case None => e.addProperty("drift", false)
                }
            case None =>
                e.addProperty("status", PipelineScripts.Pending)
                e.addProperty("pendingReason", "No script is stored yet; the next run generates and stores it")
                e.addProperty("drift", false)
        }
        e
    }

    /** POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/pull after the key
      * and capability checks: 400 for an unknown kind, no stored script or a
      * built-in script; 502 when the repository cannot be read; 200 with the
      * kind's GET entry on success. */
    def pullCodegenScriptWith(scripts: PipelineScripts, name: String, kind: String, actor: String): ResponseEntity[String] = {
        def bad(status: HttpStatus, msg: String): ResponseEntity[String] =
            ResponseEntity.status(status).body[String](QueryAPIController.errorBody(new DatrisException(msg)))
        if (!PipelineScripts.Kinds.contains(kind)) return bad(HttpStatus.BAD_REQUEST, PipelineScripts.unknownKindMessage(kind))
        scripts.record(name, kind) match {
            case None => bad(HttpStatus.BAD_REQUEST, "Pipeline " + name + " has no stored " + kind + " script")
            case Some(r) if !PipelineScripts.isRepoBacked(r) => bad(HttpStatus.BAD_REQUEST, PipelineScripts.notRepositoryMessage(name, kind))
            case Some(_) =>
                scripts.pull(name, kind, actor) match {
                    case Left(error) => bad(HttpStatus.BAD_GATEWAY, "CodeGen script was not pulled (the recorded commit is unchanged): " + error)
                    case Right(rec) =>
                        val entry = codegenScriptEntry(scripts, name, kind, rec.instruction, scripts.modelNow)
                        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(entry.toString)
                }
        }
    }

    private[api] def capabilityDenied(e: Exception): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body[String](
                "{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":" +
                    new Gson().toJson(Option(e.getMessage).getOrElse("capability denied")) + "}"
            )

    /** Host lookup for the save-time Unity Catalog hint: the secret's host
      * only when the caller may read the secret, else None (no hint). The
      * readability check runs first so an unreadable secret is never resolved. */
    private[api] def hintHostOf(canRead: String => Boolean, resolve: String => Option[String]): String => Option[String] =
        s => if (canRead(s)) resolve(s) else None
}
