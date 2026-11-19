package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, ResourceHiddenException, VersionActor}
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException, EntityVersion, GlobalJobContext, UnityCatalogSync, ValidationException}
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
    def getPipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam pipeline: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline called with pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            PipelineAPIController.pipelineResponse(config, pipeline, request)
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
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines/" + name + "/unity-catalog called")
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            if (config == null)
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            CapabilityCheck.assertVisible(request, "pipeline", "read", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))

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
            case _: ResourceHiddenException =>
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
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

            // Per-item scope filter (owner=self keys, catalog-scoped keys and
            // restricted users' catalog grants), so the list never names a
            // pipeline the caller could not read by name.
            val filteredConfigs = PipelineAPIController.visiblePipelines(request, allConfigs)

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

            savePipelineWithBody(config, changeNote, request) match {
                case Left(refused) => refused
                case Right((_, out)) => ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out)
            }
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

    /** Body of `POST /pipeline` after key validation: defaults, validation,
      * the Postgres probe, ownership stamp, tag/provenance/authority
      * preservation and authority check, versioned write, pull-table init and
      * the CodeGen save hook. Shared with the catalog restore
      * (`CatalogAPIController`) so a restored pipeline is saved exactly as a
      * UI save would be; a pipeline version restore runs the same path
      * through [[restorePipelineInternal]]. Left is the refusal response
      * (400); a `ValidationException` or any other failure is thrown. */
    def savePipelineInternal(
        config: PipelineConfig,
        changeNote: String,
        request: HttpServletRequest
    ): Either[ResponseEntity[String], PipelineConfig] =
        savePipelineWithBody(config, changeNote, request).right.map(_._1)

    /** [[savePipelineInternal]] plus the `POST /pipeline` 200 body (save
      * warnings and CodeGen outcomes). */
    private[api] def savePipelineWithBody(
        config: PipelineConfig,
        changeNote: String,
        request: HttpServletRequest
    ): Either[ResponseEntity[String], (PipelineConfig, String)] =
        savePipelineCore(config, changeNote, request, None)

    /** `POST /api/v1/pipeline/version/restore` after the version lookup:
      * `candidate` ([[EntityVersionAPIController.pipelineRestoreCandidate]])
      * is saved through the same path as `POST /pipeline`, so a version the
      * platform would refuse on save today is refused with the same answer.
      * `live` is the ONE stored-config read the restore decision uses (null
      * when the pipeline was deleted); `hidden` is the version 404 a hidden
      * history answers. Left is the refusal; a `ValidationException` or any
      * other failure is thrown. */
    private[api] def restorePipelineInternal(
        candidate: PipelineConfig,
        live: PipelineConfig,
        version: Int,
        hidden: => ResponseEntity[String],
        request: HttpServletRequest
    ): Either[ResponseEntity[String], PipelineConfig] =
        savePipelineCore(candidate, null, request, Some(PipelineAPIController.RestoreOf(live, version, () => hidden))).right.map(_._1)

    /** The save path. `restore` None is `POST /pipeline`; Some is a version
      * restore, which differs in four places only: (a) the guard is always
      * taken, decided on `restore.live` by
      * [[EntityVersionAPIController.restoreGuardDecision]]; (b) no owner
      * stamp and no preservation of omitted fields (the candidate already
      * carries the live owner, and the snapshot is taken verbatim); (c) the
      * version note is `restored from version N`; (d) the re-check under the
      * lock is [[EntityVersionAPIController.restoreRecheck]]. */
    private def savePipelineCore(
        config: PipelineConfig,
        changeNote: String,
        request: HttpServletRequest,
        restore: Option[PipelineAPIController.RestoreOf]
    ): Either[ResponseEntity[String], (PipelineConfig, String)] = {
        // Callers who may not see or write every pipeline (scoped API keys,
        // scoped catalog access): the stored config of this name is read
        // ONCE and that one value decides the scope check, the previous-name
        // check and the catalog rules (PipelineAPIController.saveGuardDecision)
        // and is the `existing` used below, so a concurrent rename or delete
        // cannot turn a refused save into a create. Everyone else reads
        // nothing here.
        val table = DatrisEnvironment.current.pipelineTableName
        // A version restore is always guarded and decided on the live config
        // it has already read (it reads nothing more here).
        val guarded = restore.isDefined || config != null && PipelineAPIController.saveGuardNeeded(request)
        val guardStored: PipelineConfig =
            if (restore.isDefined) restore.get.live
            else if (guarded) PipelineConfigIO.read(table, config.name)
            else null
        val scoped =
            if (!guarded) config
            else
                (restore match {
                    case Some(r) =>
                        EntityVersionAPIController.restoreGuardDecision(request, config, guardStored, () => PipelineConfigIO.readAll(table), r.hidden())
                    case None => PipelineAPIController.saveGuardDecision(request, config, guardStored, () => PipelineConfigIO.readAll(table))
                }) match {
                    case Left(refused) =>
                        logger.warn("POST /pipeline " + config.name + " rejected: HTTP " + refused.getStatusCode.value)
                        return Left(refused)
                    case Right(c) => c
                }
        val withDefaults = PipelineValidatorUtil.applyDefaults(scoped)
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
                return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                    "{\"error\": \"Postgres is not installed/reachable in this deployment. Enable it in .env (POSTGRES_ENABLED=1) or point POSTGRES_JDBC_URL at an external Postgres, then re-run 'docker compose up -d' — or choose another destination.\"}"
                ))
            }
        }

        val modifiedConfig = PipelineValidatorUtil.modify(withDefaults)

        // Stamp the issuing key's label so `owner=self` capabilities can
        // match resources this key created. Null when no key is present
        // (auth disabled or public endpoint) — that resource will not
        // match `owner=self` for anyone, which is intended.
        // A restore keeps the owner the candidate carries (the live one).
        val tagged = ResolvedKeyAccess.keyLabel(request) match {
            case Some(label) if restore.isEmpty => modifiedConfig.copy(createdByKeyLabel = label)
            case _ => modifiedConfig
        }

        // Definition-edit write → mints a new immutable version snapshot.
        val existing =
            if (guarded && tagged.name == config.name) guardStored
            else PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tagged.name)
        // Tags/provenance preservation: clients rebuild the body from
        // scratch, so a body that omits these entirely must not wipe them
        // on an unrelated edit. An explicit empty list / stamp=false in the
        // body still clears/disables.
        // `previousNames` is server-maintained: always the stored value.
        // A restore takes the snapshot verbatim: nothing omitted is kept.
        val preserved = PipelineAliases.carriedOver(
            if (existing != null && restore.isEmpty)
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
            else tagged,
            existing
        )
        // Source-of-authority rules are validated against every other
        // pipeline: a declared kind must exist, and one dataset has at most
        // one authoritative writer.
        // A failed read keeps the authority check fail-open, but a save that
        // adds a vector container is refused (500): it cannot be checked.
        // A failed read is recorded for the hidden-writer rule (D6).
        var configsUnreadable = false
        val others = PipelineAPIController.othersForSave(
            preserved,
            existing,
            () =>
                try PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
                catch {
                    case e: Exception =>
                        configsUnreadable = true
                        throw e
                }
        ) match {
            case Left(refused) =>
                logger.warn("POST /pipeline " + preserved.name + " rejected: pipeline configs unreadable for the vector container check")
                return Left(refused)
            case Right(o) => o
        }
        // A new or changed destination.database block must pass the
        // validator's database rules (and the Postgres 63-byte limit) on
        // every pipeline type, so an unstructured pipeline's block is checked
        // too. Every caller; a stored config with the same block keeps it.
        PipelineAPIController.databaseNameRefusal(preserved, existing).foreach { refused =>
            logger.warn("POST /pipeline " + preserved.name + " rejected: destination.database name")
            return Left(refused)
        }
        // A new or changed source or destination field type must be exactly
        // one of the documented forms (ColumnTypeText.safe), on every pipeline
        // type, so an unstructured pipeline's schema is checked too. Every
        // caller; a stored field with the same name and type keeps it.
        PipelineAPIController.columnTypeRefusal(preserved, existing).foreach { refused =>
            logger.warn("POST /pipeline " + preserved.name + " rejected: schema field type")
            return Left(refused)
        }
        // A new or changed pgvector schema or table must be one Postgres can
        // address as a single quoted identifier, and the table must be named.
        // Every caller; a stored config with the same names keeps them.
        PipelineAPIController.vectorTableNameRefusal(preserved, existing).foreach { refused =>
            logger.warn("POST /pipeline " + preserved.name + " rejected: pgvector schema or table name")
            return Left(refused)
        }
        // One vector container per pipeline: refuse a new or changed
        // container another pipeline already names. Runs before the
        // authority check, whose remedy (authoritative=false) does not apply
        // to a vector container.
        PipelineAPIController.vectorContainerRefusal(request, preserved, existing, others.filter(_ != null)).foreach { refused =>
            logger.warn("POST /pipeline " + preserved.name + " rejected: vector container in use by another pipeline")
            return Left(refused)
        }
        // A caller who cannot see every pipeline may not point this one at a
        // table, collection, prefix or topic a pipeline hidden from it
        // writes, whatever `authoritative` says. Runs before the authority
        // check, whose remedy (authoritative=false) would be the bypass. The
        // log line never names the hidden pipeline.
        if (PipelineAPIController.limitedVisibility(request)) {
            val dsEnv = PipelineAPIController.datasetEnv()
            PipelineAPIController.hiddenWriterCheckUnavailable(request, preserved, existing, configsUnreadable, dsEnv).foreach { refused =>
                logger.warn("POST /pipeline " + preserved.name + " rejected: pipeline configs unreadable for the shared destination check")
                return Left(refused)
            }
            PipelineAPIController.hiddenWriterRefusal(request, preserved, existing, others.filter(_ != null), dsEnv).foreach { refused =>
                logger.warn("POST /pipeline " + preserved.name + " rejected: destination in use by a pipeline the caller cannot see")
                return Left(refused)
            }
            // Restricted sessions: a new or changed table, collection or
            // object-store prefix must not exist yet (else the preview or the
            // destination-type sample would read, or a run overwrite, data no
            // pipeline of theirs wrote). Probed with the
            // candidate's own connection; a failed probe refuses (500).
            PipelineAPIController
                .existingDestinationRefusal(
                    request,
                    preserved,
                    existing,
                    others.filter(_ != null),
                    dsEnv,
                    t => DestinationProbe.exists(preserved, t),
                    DestinationProbe.catalogCommitted
                )
                .foreach { refused =>
                    logger.warn("POST /pipeline " + preserved.name + " rejected: new destination already exists or could not be checked (HTTP " +
                        refused.getStatusCode.value + ")")
                    return Left(refused)
                }
        }
        PipelineAPIController.authorityRefusal(request, preserved, others).foreach(refused => return Left(refused))
        val note = if (restore.isDefined) "restored from version " + restore.get.version
        else if (changeNote != null && changeNote.nonEmpty) changeNote
        else if (existing != null) "updated" else "created"
        // A new pipeline must not inherit Unity Catalog sync state left by
        // an earlier pipeline of the same name (deleted out of band).
        def write(): PipelineConfig = {
            if (existing == null) UnityCatalogStaleState.clearOnCreate(preserved)
            PipelineConfigIO.writeVersioned(preserved, note, VersionActor.resolve(request))
        }
        // Guarded callers: re-check and write under the lock a rename holds
        // from its first read to its last write, so a rename that completed
        // since the decision above (a hidden pipeline renamed into or out of
        // this name) is seen before anything is written. Only the re-read and
        // the write are held; validation, the Postgres probe and CodeGen are not.
        val saved =
            if (!guarded) write()
            else
                CatalogOps.withCatalogLock {
                    val fresh = PipelineConfigIO.read(table, config.name)
                    (restore match {
                        case Some(r) =>
                            EntityVersionAPIController.restoreRecheck(request, config, guardStored, fresh, () => PipelineConfigIO.readAll(table), r.hidden())
                        case None => PipelineAPIController.saveRecheck(request, config, guardStored, fresh, () => PipelineConfigIO.readAll(table))
                    }).foreach { refused =>
                        logger.warn("POST /pipeline " + config.name + " rejected at write: HTTP " + refused.getStatusCode.value)
                        return Left(refused)
                    }
                    write()
                }

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
        // A dropped instruction whose repository file was hand-edited keeps
        // the file; its warning joins the save warnings.
        val result =
            try PipelineScripts.onSaveResult(existing, preserved, ResolvedKeyAccess.keyLabel(request).orNull)
            catch {
                case e: Exception =>
                    logger.warn("POST /pipeline " + preserved.name + ": CodeGen script save hook failed: " + e.getMessage)
                    PipelineScripts.SaveResult(Nil, Nil)
            }
        val out = PipelineAPIController.saveResponseBody(warnings.toList ++ result.warnings, result.outcomes)
        Right((saved, out.toString))
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
            CapabilityCheck.assertVisible(request, "pipeline", "read", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(PipelineAPIController.codegenScriptsBody(config, PipelineScripts).toString)
        } catch {
            case _: ResourceHiddenException =>
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
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
            CapabilityCheck.assertVisible(request, "pipeline", "update", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
            if (PipelineScripts.instructionOf(config, kind).isEmpty)
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body[String](
                        QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " has no AI " + kind + " instruction to generate a script for"))
                    )
            if (storage == "github" && CodeRepoConfigIO.readEnabled().isEmpty)
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body[String](
                        QueryAPIController.errorBody(
                            new DatrisException(
                                "No code repository is configured. Set one up under Configuration > Code Repository before using GitHub storage."
                            )
                        )
                    )
            val actor = ResolvedKeyAccess.keyLabel(request).orNull
            val force = overwrite != null && overwrite.booleanValue
            PipelineScripts.regenerate(config, kind, actor, Option(storage).filter(_.nonEmpty).orNull, force) match {
                case Right(rec) =>
                    PipelineAPIController.auditCodegenScript(request, "regenerate", name, kind, "success", None)
                    val body = PipelineAPIController.codegenScriptsBody(config, PipelineScripts)
                    val entry = body.getAsJsonArray("scripts").asScala.map(_.getAsJsonObject).find(_.get("kind").getAsString == kind)
                    ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(entry.getOrElse(new JsonObject).toString)
                case Left(error) =>
                    PipelineAPIController.auditCodegenScript(request, "regenerate", name, kind, "failure", Some(error))
                    val status =
                        if (error.contains(PipelineScripts.ConflictMarker)) HttpStatus.CONFLICT
                        else HttpStatus.BAD_GATEWAY
                    ResponseEntity
                        .status(status)
                        .body[String](
                            QueryAPIController.errorBody(new DatrisException("CodeGen script was not regenerated (the current script is unchanged): " + error))
                        )
            }
        } catch {
            case _: ResourceHiddenException =>
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
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
            CapabilityCheck.assertVisible(request, "pipeline", "update", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
            val response = PipelineAPIController.pullCodegenScriptWith(PipelineScripts, name, kind, ResolvedKeyAccess.keyLabel(request).orNull, config)
            if (response.getStatusCode.is2xxSuccessful) PipelineAPIController.auditCodegenScript(request, "pull", name, kind, "success", None)
            else PipelineAPIController.auditCodegenScript(request, "pull", name, kind, "failure", Some(String.valueOf(response.getBody)))
            response
        } catch {
            case _: ResourceHiddenException =>
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured")))
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
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
                return PipelineAPIController.pipelineNotFound(pipeline)

            // Scope first (a pipeline hidden from the caller answers 404),
            // then the keep-data refusal; nothing has been deleted yet.
            val refused = PipelineAPIController.deleteRefusal(request, config, PipelineDeletePlan.of(deleteData, deleteConfig))
            if (refused.isDefined) {
                logger.warn("Pipeline delete refused: " + pipeline + " (HTTP " + refused.get.getStatusCode.value + ")")
                return refused.get
            }

            val (warnings, kept) = deletePipelineOutcome(config, request, deleteData, deleteConfig, checkScope = false)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(PipelineAPIController.deleteResponseBody(warnings, kept))
        } catch {
            case _: ResourceHiddenException => PipelineAPIController.pipelineNotFound(pipeline)
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Rename a pipeline: `POST /api/v1/pipelines/{name}/rename`, body
      * `{"newName": "..."}`. Changes the name and nothing else: destination
      * data is never touched (no save path, no delete path, so neither the
      * vector-container nor the authority rule can fire against the
      * pipeline's own old name), and definition versions, CodeGen scripts and
      * their history, the pull position and the taps that feed it follow the
      * new name. Needs own on the pipeline's catalog (`pipeline:delete`).
      *
      * Ordered so a failure leaves the data intact and a repeated rename
      * continues where it stopped (see [[PipelineRename.resumable]]): copy
      * scripts (a failed copy refuses, nothing renamed), re-key versions,
      * write the new config, move the pull-table entry, re-point taps (per-tap
      * failures: 207, `failed`), remove the old name's versions and scripts,
      * and remove the old config row last. */
    @PostMapping(path = Array("/pipelines/{name}/rename"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def renamePipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @RequestBody(required = false) body: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipelines/" + name + "/rename called")
            APIKeyValidator.validate(apiKey)
            val requested = PipelineAPIController.readNewName(body)
            val newName = Option(requested).map(_.trim).orNull
            PipelineAPIController.auditRename(request, newName)

            CatalogOps.withCatalogLock {
                val env = DatrisEnvironment.current
                val table = env.pipelineTableName
                val old = PipelineConfigIO.read(table, name)
                val existingNew =
                    if (old == null || newName == null || newName.isEmpty || newName == name) null
                    else PipelineConfigIO.read(table, newName)
                val ucStateExists = old != null && UnityCatalogSyncIO.read(name) != null
                val jobs = GlobalJobContext.synchronized(GlobalJobContext.getAll.toList).flatMap(jc => Option(jc.config).map(c => (c.name, jc.state)))
                // A taken name is only continued when its history shows this
                // rename wrote it (PipelineRename.priorAttempt), never for an
                // unrelated pipeline that happens to have the same config.
                val priorAttempt = existingNew != null && PipelineRename.priorAttempt(
                    name,
                    newName,
                    EntityVersionIO.listVersions(env.pipelineVersionTableName, newName),
                    EntityVersionIO.listVersions(env.pipelineVersionTableName, name)
                )
                // A new name a pipeline hidden from the caller lists in
                // previousNames reads as taken (checked inside, after the
                // earlier refusals; a failed read refuses, nothing renamed).
                def hiddenAlias: Boolean =
                    existingNew == null && PipelineAPIController.limitedVisibility(request) && {
                        val all =
                            try PipelineConfigIO.readAll(table)
                            catch {
                                case e: Exception =>
                                    throw new IllegalStateException(
                                        "Could not check whether the name '" + newName + "' is in use; nothing was renamed. Try again.",
                                        e
                                    )
                            }
                        PipelineAPIController.hiddenAliasClaimant(request, newName, all).isDefined
                    }
                PipelineAPIController.renameRefusal(
                    request,
                    name,
                    requested,
                    old,
                    existingNew,
                    jobs,
                    ucStateExists,
                    priorAttempt,
                    hiddenAlias,
                    n =>
                        DestinationProbe.withDeadline(
                            DestinationProbe.StateReadDeadline,
                            "Unity Catalog state read",
                            PipelineAPIController.probeOwner(request)
                        )(DestinationProbe.catalogCommitted(n))
                )
                    .foreach { refused =>
                        logger.warn("POST /pipelines/" + name + "/rename refused: HTTP " + refused.getStatusCode.value)
                        return refused
                    }

                val actor = VersionActor.resolve(request)
                val scriptActor = ResolvedKeyAccess.keyLabel(request).orNull
                // The old name joins previousNames (server-maintained) so stored
                // records filed under it stay with this pipeline.
                val renamedBase = PipelineRename.renamedConfig(old, newName).copy(previousNames = PipelineAliases.afterRename(old, newName))
                val resuming = existingNew != null
                val warnings = Seq.newBuilder[String]

                // Nothing written under the new name survives a refusal of a
                // fresh rename (a resumed one keeps what the first attempt wrote).
                def removeCopies(): Unit =
                    if (!resuming) {
                        try PipelineScripts.deleteAll(newName)
                        catch { case e: Exception => logger.warn("Rename " + name + " -> " + newName + ": could not remove copied scripts: " + e.getMessage) }
                        try EntityVersionIO.deleteAllForEntity(env.pipelineVersionTableName, newName)
                        catch { case e: Exception => logger.warn("Rename " + name + " -> " + newName + ": could not remove copied versions: " + e.getMessage) }
                    }

                // 1. CodeGen scripts (copy; the old ones are removed at the end).
                PipelineScripts.copyForRename(old, renamedBase, scriptActor) match {
                    case Left(reason) =>
                        logger.warn("POST /pipelines/" + name + "/rename refused: " + reason)
                        removeCopies()
                        return PipelineAPIController.renameError(HttpStatus.CONFLICT, "Pipeline " + name + " was not renamed: " + reason)
                    case Right(w) => warnings ++= w
                }
                // A history that could not be copied is not deleted under the old name either.
                val historyWarnings = PipelineScripts.copyHistoryForRename(name, newName)
                warnings ++= historyWarnings

                // 2. Definition versions re-keyed under the new name, then the
                // new config as the next version.
                val renamed =
                    try {
                        PipelineAPIController.copyVersionsForRename(env.pipelineVersionTableName, old, newName)
                        if (resuming) existingNew
                        else {
                            UnityCatalogStaleState.clearOnCreate(renamedBase)
                            PipelineConfigIO.writeVersioned(renamedBase, PipelineRename.changeNote(name, newName, actor), actor)
                        }
                    } catch {
                        case e: Exception =>
                            logger.error("POST /pipelines/" + name + "/rename failed while writing the new config: " + Throwables.getStackTraceAsString(e))
                            val written =
                                try PipelineConfigIO.read(table, newName) != null
                                catch { case _: Exception => true } // unknown: keep everything
                            if (written)
                                return PipelineAPIController.renameError(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "The rename of " + name + " to " + newName + " is incomplete: send the same rename again to finish it (" +
                                        Option(e.getMessage).getOrElse(e.getClass.getSimpleName) + ")"
                                )
                            removeCopies()
                            return PipelineAPIController.renameError(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Pipeline " + name + " was not renamed: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                            )
                    }

                // 3. Pull position.
                if (old.source != null && old.source.databaseAttributes != null)
                    try PipelinePullTableUtil.rename(name, newName, old.source.databaseAttributes.cronExpression)
                    catch {
                        case e: Exception =>
                            logger.warn("Rename " + name + " -> " + newName + ": pull-table entry not moved: " + e.getMessage)
                            warnings += "The incremental pull position could not be moved to " + newName + ": " + e.getMessage
                    }

                // 4. Taps that feed the pipeline, visible to the caller or not.
                val taps =
                    try TapConfigIO.readAll(env.tapTableName).filter(_ != null)
                    catch {
                        case e: Exception =>
                            logger.warn("Rename " + name + " -> " + newName + ": taps unreadable: " + e.getMessage)
                            warnings += "Taps could not be read, so none was re-pointed to " + newName + ": " + e.getMessage
                            Nil
                    }
                val toMove = PipelineRename.tapsToRepoint(taps, name)
                val tapNote = "target pipeline renamed " + name + " -> " + newName + " by " + actor
                val tapResult = CatalogOps.execute(toMove.map(_.name)) { n =>
                    val live = TapConfigIO.read(env.tapTableName, n)
                    if (live == null) throw new DatrisException("Tap " + n + " no longer exists")
                    if (live.targetPipeline == name) TapConfigIO.writeVersioned(PipelineRename.repointed(live, newName), tapNote, actor)
                }
                val moved = toMove.filter(t => tapResult.ok.contains(t.name))
                val visibleTap: String => Boolean = n =>
                    toMove.find(_.name == n).exists(t =>
                        CapabilityCheck.grants(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel))
                    )
                val (visibleFailed, hiddenFailed) = tapResult.failed.partition { case (n, _) => visibleTap(n) }
                tapResult.failed.foreach { case (n, err) => logger.warn("Rename " + name + " -> " + newName + ": tap " + n + " not re-pointed: " + err) }
                if (hiddenFailed.nonEmpty)
                    warnings += hiddenFailed.size + " tap(s) you cannot see could not be re-pointed to " + newName + "; an administrator can re-point them"

                // 5. Old name's versions and scripts, then the old config row last.
                try EntityVersionIO.deleteAllForEntity(env.pipelineVersionTableName, name)
                catch { case e: Exception => logger.warn("Rename " + name + " -> " + newName + ": old version cleanup failed: " + e.getMessage) }
                try warnings ++= PipelineScripts.deleteAll(name, keepHistory = historyWarnings.nonEmpty)
                catch { case e: Exception => logger.warn("Rename " + name + " -> " + newName + ": old CodeGen script cleanup failed: " + e.getMessage) }
                NoSQLDbUtil.deleteItemJSON(table, "name", name)

                logger.info("Pipeline renamed " + name + " -> " + newName + " (version " + renamed.version + ") by " + actor)
                val out = PipelineAPIController.renameResponseBody(request, renamed, name, moved, visibleFailed, warnings.result())
                ResponseEntity
                    .status(if (tapResult.failed.isEmpty) HttpStatus.OK else HttpStatus.MULTI_STATUS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body[String](out.toString)
            }
        } catch {
            case _: ResourceHiddenException => PipelineAPIController.pipelineNotFound(name)
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
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
    ): Seq[String] = deletePipelineOutcome(config, request, deleteData, deleteConfig, checkScope)._1

    /** [[deletePipelineInternal]] plus, for a keep-data delete (explicit
      * `deleteData=false` with the config delete), the labels of the tables,
      * collections and prefixes left in place (None in every other mode).
      * A keep-data delete of a vector pipeline throws before anything is
      * deleted; DELETE /pipeline answers it with 400 first. */
    def deletePipelineOutcome(
        config: PipelineConfig,
        request: HttpServletRequest,
        deleteData: String,
        deleteConfig: String,
        checkScope: Boolean
    ): (Seq[String], Option[Seq[String]]) = {
        val pipeline = config.name
        // Scope check: a key with `pipeline:delete:owner=self` may only
        // delete pipelines it created. Loaded resource provides the
        // `createdByKeyLabel` we compare against the caller's label.
        if (checkScope)
            CapabilityCheck.assertVisible(request, "pipeline", "delete", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))

        // By default the data is deleted with the config, so no table,
        // collection or files are left with no pipeline to own them. Only an
        // explicit deleteData=false with the config delete keeps the data
        // (PipelineDeletePlan); that caller has accepted unowned data. A
        // vector pipeline cannot keep its data (refused, nothing deleted).
        val plan = PipelineDeletePlan.of(deleteData, deleteConfig)
        PipelineDeletePlan.refusal(plan, config).foreach(msg => throw new ValidationException(msg))
        val deleteConfigBool = plan.deleteConfig
        val deleteDataBool = plan.deleteData

        // Read before anything is deleted (this config's own row is in the
        // list and excluded by name), and only when the config names a vector
        // container, table or collection. A read failure propagates (500,
        // nothing destroyed): a dropped container or table shared with another
        // pipeline cannot be undone.
        val dsEnv = PipelineAPIController.deleteDatasetEnv(config, deleteDataBool)
        val otherConfigs = PipelineAPIController.othersForDelete(
            config,
            deleteDataBool,
            dsEnv,
            () => PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
        )

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
            // A vector container another pipeline also names is kept and reported.
            val sharedVector = VectorContainers.sharedOnDelete(config, otherConfigs)
            // A table or collection another pipeline also writes (including a
            // pgvector / Postgres table the other kind names) is kept and reported.
            val sharedStructured = SharedDatasets.sharedOnDelete(config, otherConfigs, dsEnv)
            val objectStoreDataDeleted =
                cleanupDestinationData(config, sharedVector.map(_._1).toSet, sharedStructured.map(_._1).toSet, dsEnv.tenantDatabase)
            sharedVector.foreach { case (k, sharers) =>
                logger.warn(
                    "Skipped vector data delete for pipeline '" + pipeline + "': " + VectorContainers.label(VectorContainers.displayKey(k, config)) +
                        " is also named by pipeline(s): " + sharers.map(_.name).mkString(", ")
                )
            }
            sharedStructured.foreach { case (k, sharers) =>
                logger.warn(
                    "Skipped data delete for pipeline '" + pipeline + "': " + SharedDatasets.label(k) +
                        " is also written by pipeline(s): " + sharers.map(_.name).mkString(", ")
                )
            }
            warnings ++= PipelineAPIController.vectorDeleteWarnings(request, config, otherConfigs)
            warnings ++= PipelineAPIController.blankVectorTableDeleteWarning(config)
            warnings ++= PipelineAPIController.postgresNameDeleteWarning(config, dsEnv)
            warnings ++= PipelineAPIController.structuredDeleteWarnings(request, config, otherConfigs, dsEnv)
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
            // A data-only reset that kept a shared vector container keeps the
            // ledgers too: the chunks are still there, and a re-ingest would
            // duplicate them in a container that cannot be cleaned per pipeline.
            if (PipelineAPIController.clearTapLedgersForPlan(plan, sharedVector.nonEmpty))
                cleanupDocumentTapLedgers(pipeline)
            else
                logger.warn("Pipeline reset: " + pipeline + ": document-tap ledgers kept because a shared vector container was kept")
        }

        // Keep-data delete: no destination data is touched and no other
        // config is read. The scratch folder is platform working storage keyed
        // by name (a re-created pipeline would read it back), so it still goes.
        // Document-tap ledgers and staged files mirror the kept data and stay
        // (clearing them would re-ingest into it). A catalog-committed Iceberg
        // table's Unity Catalog state stays too: its files are still there.
        val kept =
            if (!plan.keepData) None
            else {
                if (config.destination != null) cleanupScratchData(config)
                UnityCatalogDeleteAdvice.forPipeline(config, ucPrevious, dataDeleted = false, configDeleted = true).foreach { advice =>
                    warnings += advice
                    logger.warn("Pipeline delete: " + pipeline + ": " + advice)
                    auditUnityCatalogDelete(request, pipeline, advice)
                }
                // Only a committed table's state survives; an uncommitted
                // (created-only) or register-mode doc goes, as on any delete.
                ucTableFilesKept = PipelineDeletePlan.keepUnityCatalogState(config, ucPrevious)
                val labels = PipelineDeletePlan.keptLabels(config, PipelineAPIController.keepDatasetEnv())
                logger.info(
                    "Pipeline delete: " + pipeline + ": config deleted, destination data kept" +
                        (if (labels.isEmpty) " (nothing to keep)" else ": " + labels.mkString(", "))
                )
                Some(labels)
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
            // and their index records). Non-fatal, as for tap scripts. A
            // repository file hand-edited since its recorded commit is kept
            // and named in a warning.
            try warnings ++= PipelineScripts.deleteAll(pipeline)
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
        (warnings.result(), kept)
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
    private def cleanupDestinationData(
        config: PipelineConfig,
        keepVector: Set[VectorContainers.Key],
        keepStructured: Set[SharedDatasets.Key],
        tenantDatabase: Option[String]
    ): Boolean = {
        val dest = config.destination
        var objectStoreDataDeleted = false
        // A vector container another pipeline also names is kept.
        def dropVector(store: String, name: String, schema: Option[String] = None): Boolean =
            !VectorContainers.key(store, name, schema).exists(keepVector.contains)
        // A table or collection another pipeline also writes is kept. The
        // keys are this config's own, at most one per destination kind.
        def dropStructured(kind: String): Boolean = !keepStructured.exists(_.kind == kind)

        // PostgreSQL — DROP TABLE. Never for a database, schema or table name
        // that fails the naming rule (DatabaseDestinationNames.postgresDropProblem):
        // no connection, no drop; postgresNameDeleteWarning reports it.
        val pgDropProblem =
            if (dest.database != null && dest.database.usePostgres) DatabaseDestinationNames.postgresDropProblem(dest.database, tenantDatabase)
            else None
        pgDropProblem.foreach(p => logger.warn("Skipped PostgreSQL table drop for pipeline '" + config.name + "': " + p))
        if (dest.database != null && dest.database.usePostgres && pgDropProblem.isEmpty && dropStructured("postgres")) {
            try {
                val secrets = SecretsRetrieverUtil.postgresSecrets()
                Class.forName("org.postgresql.Driver")
                val properties = new Properties()
                properties.setProperty("user", secrets.username)
                properties.setProperty("password", secrets.password)
                val pgDbName = tenantDatabase.getOrElse(dest.database.dbName)
                val jdbcUrl = PostgresSql.jdbcUrl(secrets.jdbcUrl, pgDbName)
                val conn = DriverManager.getConnection(jdbcUrl, properties)
                try {
                    val schema = if (dest.database.schema != null) dest.database.schema else "public"
                    val stmt = conn.createStatement()
                    stmt.execute(PostgresSql.dropTable(schema, dest.database.table))
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
        if (dest.database != null && dest.database.useMongoDB && dropStructured("mongodb")) {
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
        if (dest.database != null && dest.database.useSnowflake && dropStructured("snowflake")) {
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

        // SQL Server — DROP TABLE in the external database, connecting with the
        // pipeline's own credentialsSecret (the loader login created the table,
        // so it owns it and may drop it).
        if (dest.database != null && dest.database.useSqlServer && dropStructured("sqlserver")) {
            try {
                SqlServerConnectionUtil.withConnection(dest.database) { conn =>
                    val stmt = conn.createStatement()
                    try {
                        stmt.execute("DROP TABLE IF EXISTS " + SqlServerConnectionUtil.qualifiedTable(dest.database))
                        logger.info(
                            "Dropped SQL Server table: " + dest.database.dbName + "." + SqlServerConnectionUtil.schemaName(dest.database) + "." + dest.database.table
                        )
                    } finally {
                        stmt.close()
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop SQL Server table: " + e.getMessage)
            }
        }

        // Databricks — DROP TABLE in the external workspace, connecting with
        // the pipeline's own credentialsSecret/warehouse (the service principal
        // owns the tables it created, so it is allowed to drop them). The
        // shared datris_staging volume is left alone — other pipelines use it.
        if (dest.database != null && dest.database.useDatabricks && dropStructured("databricks")) {
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

        // pgvector — DROP TABLE. Never for a null or blank table name: no
        // shared-table guard has a key for it, so the table may be another
        // pipeline's (the delete warns instead, blankVectorTableDeleteWarning).
        if (
            dest.pgvector != null && !DestinationTargets.blankVectorTableName(config) &&
            dropVector("pgvector", dest.pgvector.tableName, Option(dest.pgvector.schemaName)) && dropStructured("pgvector")
        ) {
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
                            val schema = PGVectorSql.schemaOf(dest.pgvector)
                            val table = PGVectorSql.tableOf(dest.pgvector)
                            val stmt = conn.createStatement()
                            stmt.execute(PGVectorSql.dropTable(schema, table))
                            stmt.close()
                            logger.info("Dropped pgvector table: " + schema + "." + table)
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
        if (dest.qdrant != null && dropVector("qdrant", dest.qdrant.collectionName)) {
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
        if (dest.weaviate != null && dropVector("weaviate", dest.weaviate.className)) {
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
        if (dest.milvus != null && dropVector("milvus", dest.milvus.collectionName)) {
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
        if (dest.chroma != null && dropVector("chroma", dest.chroma.collectionName)) {
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

        cleanupScratchData(config)
        objectStoreDataDeleted
    }

    /** Scratch — recursively delete `_scratch/<pipeline>/` from <env>-data and
      * nothing else (no-op without a scratch destination). The prefix comes
      * from ScratchPaths.prefix, which refuses a blank or path-traversing
      * pipeline segment; asserted here too before the recursive delete. Run
      * by every delete that deletes the data, and by a keep-data delete.
      * Never throws. */
    private def cleanupScratchData(config: PipelineConfig): Unit = {
        val dest = config.destination
        if (dest != null && dest.scratch != null) {
            try {
                val prefix = ScratchPaths.prefix(config.name)
                if (!prefix.startsWith(ScratchPaths.Root) || prefix == ScratchPaths.Root)
                    throw new IllegalStateException("refusing to delete scratch data: prefix '" + prefix + "' is not under " + ScratchPaths.Root)
                ScratchLoader.deleteScratchData(config.name)
            } catch {
                case e: Exception => logger.warn("Failed to delete scratch data for pipeline '" + config.name + "': " + e.getMessage)
            }
        }
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

    private val auditLogger: Logger = LoggerFactory.getLogger(classOf[PipelineAPIController])

    /** Audit entry `codegen-script-<operation>:<kind>` for a CodeGen script
      * change (regenerate, pull, restore-version). Never throws. */
    private[api] def auditCodegenScript(
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
            case ex: Exception => auditLogger.warn("Audit of CodeGen script " + operation + " failed for " + pipeline + ": " + ex.getMessage)
        }

    /** GET /pipeline's response for the config read by name: 404 with the
      * "not configured in the NoSQL database" error body when it is null,
      * else 200 with its Gson JSON. */
    def pipelineResponse(config: PipelineConfig, name: String): ResponseEntity[String] =
        if (config == null) pipelineNotFound(name)
        else new ResponseEntity[String](new Gson().toJson(config), HttpStatus.OK)

    /** [[pipelineResponse]] with the caller's scope applied: a pipeline
      * outside a restricted user's catalog grants is the same 404 as a
      * missing one; an API key's scope miss is 403. */
    def pipelineResponse(config: PipelineConfig, name: String, request: HttpServletRequest): ResponseEntity[String] =
        if (config == null) pipelineNotFound(name)
        else
            try {
                CapabilityCheck.assertVisible(request, "pipeline", "read", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
                pipelineResponse(config, name)
            } catch {
                case _: ResourceHiddenException => pipelineNotFound(name)
                case e: CapabilityDeniedException => capabilityDenied(e)
            }

    /** For a restricted user's session only: true when `name` is missing or
      * outside the user's grants for `action` (callers answer exactly as for a
      * missing pipeline). Always false for everyone else, so API keys keep
      * today's behaviour on the route. */
    def restrictedHidden(request: HttpServletRequest, name: String, action: String): Boolean =
        CapabilityCheck.isRestrictedSession(request) &&
            hiddenConfig(request, PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name), action)

    /** Pure core of [[restrictedHidden]] on the loaded config (null = missing). */
    def hiddenConfig(request: HttpServletRequest, config: PipelineConfig, action: String): Boolean =
        CapabilityCheck.isRestrictedSession(request) &&
            (config == null || !CapabilityCheck.grants(request, "pipeline", action, CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel)))

    /** The refusal for a pipeline named in a request: a restricted user's
      * session gets 404 ([[pipelineNotFound]]) when `name` is missing or
      * outside the grants, 403 when visible but `action` is not granted; an
      * API key whose scope refuses `action` on the stored pipeline gets the
      * capability-denied 403 (plans/stories/scoped-key-read-routes.md). The
      * stored config is read only for those callers; None for everyone else
      * and for a key naming a missing pipeline (the route answers as today). */
    def restrictedRefusal(request: HttpServletRequest, name: String, action: String): Option[ResponseEntity[String]] =
        if (!CapabilityCheck.isRestrictedSession(request) && !CapabilityCheck.keyScopeApplies(request, "pipeline", action)) None
        else refusalFor(request, name, PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name), action)

    /** Pure core of [[restrictedRefusal]] on the loaded config (null = missing). */
    def refusalFor(request: HttpServletRequest, name: String, config: PipelineConfig, action: String): Option[ResponseEntity[String]] =
        if (!CapabilityCheck.isRestrictedSession(request))
            if (config == null) None
            else keyRefusal(request, "pipeline", action, CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
        else if (hiddenConfig(request, config, "read")) Some(pipelineNotFound(name))
        else if (hiddenConfig(request, config, action))
            Some(capabilityDenied(new DatrisException("capability denied: your catalog grant does not allow pipeline:" + action + " on " + name)))
        else None

    /** The 403 (capability-denied body: `error`, `errorKind`, `message`)
      * for a route that covers the whole installation and has nothing to
      * filter (plans/stories/scoped-key-read-routes.md, rows 7 and 10), for a
      * caller limited to a catalog or owner ([[CapabilityCheck.isLimited]]).
      * `what` says what the route does; the message names nothing else. A
      * key's refusal is noted for the audit log under `required`. */
    def installWideRefusal(request: HttpServletRequest, what: String, required: String): ResponseEntity[String] = {
        val message = "capability denied: " + what + " covers the whole installation and is not available to an API key or user " +
            "limited to a catalog or to what it created"
        if (!CapabilityCheck.isRestrictedSession(request))
            ai.datris.audit.AuditLog.noteScopeDenial(request, required, message + " (key '" + CapabilityCheck.resolvedKey(request).map(_.label).orNull + "')")
        capabilityDenied(new DatrisException(message))
    }

    /** [[ai.datris.auth.CallerVisibility.storedWriteDenial]] for the
      * request's API key as a response: the capability-denied 403 through
      * [[CapabilityCheck.assertScope]] on the action it names (shared
      * wording, denial noted for the audit log), or None. Always None for
      * sessions and no identity. */
    def storedWriteRefusal(
        request: HttpServletRequest,
        resource: String,
        context: Map[String, String],
        accepted: Seq[String],
        named: String
    ): Option[ResponseEntity[String]] =
        CapabilityCheck.resolvedKey(request).filter(!_.restrictedSession).flatMap { rk =>
            ai.datris.auth.CallerVisibility.storedWriteDenial(rk, resource, context, accepted, named).map { action =>
                try {
                    CapabilityCheck.assertScope(request, resource, action, context)
                    capabilityDenied(new CapabilityDeniedException("capability denied"))
                } catch {
                    case e: CapabilityDeniedException => capabilityDenied(e)
                }
            }
        }

    /** An API key's scope refusal on one item as a response: the
      * capability-denied 403 of [[CapabilityCheck.assertKeyScope]] (shared
      * wording, denial noted for the audit log), or None. Always None for
      * sessions and no identity. */
    def keyRefusal(request: HttpServletRequest, resource: String, action: String, context: Map[String, String]): Option[ResponseEntity[String]] =
        try {
            CapabilityCheck.assertKeyScope(request, resource, action, context)
            None
        } catch {
            case e: CapabilityDeniedException => Some(capabilityDenied(e))
        }

    /** The names of `colliding` for the caller, only when every one is
      * visible to it ([[visiblePipelines]]); None otherwise, so a message
      * never names a pipeline the caller could not list. */
    private def visibleNamesOf(request: HttpServletRequest, colliding: Seq[PipelineConfig]): Option[Seq[String]] =
        if (colliding.nonEmpty && visiblePipelines(request, colliding).size == colliding.size) Some(colliding.map(_.name))
        else None

    /** POST /pipeline: 400 `{"error": ...}` for a source-of-authority
      * problem ([[LineageService.authorityProblem]]). The other pipeline is
      * named only when the caller can see it ([[visiblePipelines]]); the
      * named text and body are unchanged from before. */
    def authorityRefusal(request: HttpServletRequest, candidate: PipelineConfig, others: Seq[PipelineConfig]): Option[ResponseEntity[String]] =
        LineageService.authorityProblem(candidate, others.toList).map { problem =>
            val visible = problem.other.forall(o => visiblePipelines(request, Seq(o)).nonEmpty)
            val text = problem.message(otherVisible = visible)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"" + text.replace("\"", "'") + "\"}")
        }

    /** POST /pipeline (and version restore, which saves through the same
      * path): 400 `{"error": ...}` when the candidate names a vector container that it did not already name in
      * `stored` (same name, or null) and another pipeline in `others` names.
      * One container belongs to one pipeline. */
    def vectorContainerRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig]
    ): Option[ResponseEntity[String]] =
        VectorContainers.saveConflicts(candidate, stored, others).headOption.map { case (k, colliding) =>
            val body = new JsonObject()
            body.addProperty("error", VectorContainers.refusalMessage(VectorContainers.displayKey(k, candidate), visibleNamesOf(request, colliding)))
            ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](body.toString)
        }

    /** POST /pipeline (every caller: sessions, API keys, auth off, MCP, CLI,
      * catalog restore) and pipeline version restore: 400 `{"error": ...}`
      * for a new or changed pgvector schema or table that can never be
      * right. `DestinationTargets.BlankVectorTableRefusal` for a null or
      * blank table, then `DestinationTargets.UnsafeVectorTableRefusal` for a
      * double quote or NUL in either name or an empty schema. Skipped when
      * `stored` (the stored config of this name, the live one for a restore;
      * null when there is none) has exactly the same schema and table
      * (`DestinationTargets.sameVectorTableNames`, nulls included): such a
      * pipeline keeps working with its table. None for a null candidate or a
      * non-pgvector config. No request, no I/O. Restricted sessions also get
      * their own branch in [[existingDestinationRefusal]]. */
    def vectorTableNameRefusal(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] = {
        if (candidate == null || candidate.destination == null || candidate.destination.pgvector == null) return None
        if (DestinationTargets.sameVectorTableNames(candidate, stored)) return None
        val text =
            if (DestinationTargets.blankVectorTableName(candidate)) Some(DestinationTargets.BlankVectorTableRefusal)
            else if (DestinationTargets.unsafeVectorTableName(candidate)) Some(DestinationTargets.UnsafeVectorTableRefusal)
            else None
        text.map(t => ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(t)))
    }

    /** POST /pipeline (every caller and entry point) and pipeline version
      * restore: 400 `{"error": ...}` with the save validator's message
      * ([[DatabaseDestinationNames.problem]]) for a `destination.database`
      * block that fails the validator's database rules or the Postgres
      * 63-byte limit, on every pipeline type. Skipped when `stored` (the
      * stored config of this name, the live one for a restore; null when
      * there is none) has the identical block
      * ([[DatabaseDestinationNames.same]]): such a pipeline keeps working
      * with it. None for a null candidate or one without a block. No
      * request, no I/O. */
    def databaseNameRefusal(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] = {
        if (candidate == null || candidate.destination == null || candidate.destination.database == null) return None
        if (DatabaseDestinationNames.same(candidate, stored)) return None
        DatabaseDestinationNames
            .problem(candidate.destination.database)
            .map(t => ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(t)))
    }

    /** POST /pipeline (every caller and entry point) and pipeline version
      * restore: 400 `{"error": ...}` with [[ColumnTypeText.changedProblem]]'s
      * text for the first source or destination field whose type is not
      * exactly one of the documented forms ([[ColumnTypeText.safe]]), on
      * every pipeline type. A field that `stored` (the stored config of this
      * name, the live one for a restore; null when there is none) has under
      * the same name in the same schema with the same type is kept. None for
      * a null candidate. No request, no I/O. */
    def columnTypeRefusal(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] =
        ColumnTypeText
            .changedProblem(candidate, stored)
            .map(t => ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(t)))

    /** DELETE /pipeline with data (and reset): the one warning line for a
      * Postgres destination whose effective database, schema or table fails
      * [[DatabaseDestinationNames.postgresDropProblem]] (the tenant database
      * from `env` replaces `dbName`). No connection is made and no table is
      * dropped for such a config. None for every other config. */
    def postgresNameDeleteWarning(config: PipelineConfig, env: SharedDatasets.Env): Option[String] = {
        val db = Option(config).flatMap(c => Option(c.destination)).flatMap(d => Option(d.database)).orNull
        if (db == null || !db.usePostgres) None
        else
            DatabaseDestinationNames.postgresDropProblem(db, env.tenantDatabase).map { p =>
                // One line, whatever the stored name holds.
                val reason = p.map(c => if (Character.isISOControl(c)) ' ' else c)
                "The PostgreSQL table was not dropped because its name does not meet the naming rule (" + reason +
                    "); drop it in PostgreSQL if it is no longer needed."
            }
    }

    /** DELETE /pipeline with data: the one warning line for a pgvector
      * destination whose `tableName` is null or blank. No table is dropped
      * for such a config (no shared-table guard can protect it). None for
      * every other config. */
    def blankVectorTableDeleteWarning(config: PipelineConfig): Option[String] =
        if (DestinationTargets.blankVectorTableName(config))
            Some("The pgvector table was not dropped because this pipeline has no table name; drop it in PostgreSQL if it is no longer needed.")
        else None

    /** POST /pipeline: the other configs for the authority and vector
      * container checks. A failed read is `Nil` (fail-open, as before)
      * unless the candidate adds a vector container its stored config does
      * not name: that save cannot be checked and is refused with 500. */
    def othersForSave(
        candidate: PipelineConfig,
        existing: PipelineConfig,
        read: () => Seq[PipelineConfig]
    ): Either[ResponseEntity[String], Seq[PipelineConfig]] =
        try Right(read())
        catch {
            case e: Exception =>
                val stored = VectorContainers.keysOf(existing).toSet
                if (VectorContainers.keysOf(candidate).exists(k => !stored.contains(k)))
                    Left(
                        ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body[String](
                                ApiErrors.errorBody(
                                    "Could not check whether the vector collection, class or table is used by another pipeline; nothing was saved: " +
                                        e.getMessage
                                )
                            )
                    )
                else Right(Nil)
        }

    /** DELETE /pipeline: the configs a vector delete is checked against.
      * Nil without reading when no data is deleted or the config names no
      * vector container; otherwise `read()` (nulls dropped), whose failure
      * propagates so the delete fails before anything is destroyed. */
    def vectorOthersForDelete(config: PipelineConfig, deleteData: Boolean, read: () => Seq[PipelineConfig]): Seq[PipelineConfig] =
        if (!deleteData || VectorContainers.keysOf(config).isEmpty) Nil
        else
            try read().filter(_ != null)
            catch {
                case e: Exception =>
                    throw new IllegalStateException(
                        "Could not read pipeline configs to check for shared vector containers; nothing was deleted: " + e.getMessage,
                        e
                    )
            }

    /** Whether a delete clears the pipeline's document-tap ledgers: always
      * when the config is deleted; for a data-only reset only when no shared
      * vector container was kept. */
    def clearTapLedgersOnDelete(deleteConfig: Boolean, keptSharedVector: Boolean): Boolean =
        deleteConfig || !keptSharedVector

    /** [[clearTapLedgersOnDelete]] for a delete plan: never for a keep-data
      * delete (the ledgers mirror the kept data; clearing them would
      * re-ingest into it). */
    def clearTapLedgersForPlan(plan: PipelineDeletePlan.Plan, keptSharedVector: Boolean): Boolean =
        !plan.keepData && clearTapLedgersOnDelete(plan.deleteConfig, keptSharedVector)

    /** DELETE /pipeline 200 body: `{"warnings":[...]}`, plus `"kept":[...]`
      * only for a keep-data delete (an empty array when nothing was kept). */
    def deleteResponseBody(warnings: Seq[String], kept: Option[Seq[String]]): String = {
        val out = new JsonObject
        val arr = new com.google.gson.JsonArray
        warnings.foreach(arr.add(_))
        out.add("warnings", arr)
        kept.foreach { ks =>
            val k = new com.google.gson.JsonArray
            ks.foreach(k.add(_))
            out.add("kept", k)
        }
        out.toString
    }

    /** DELETE /pipeline once the config is loaded, before anything is
      * deleted: the scope check first (a pipeline the caller cannot see is
      * `404`, a scope miss `403`, exactly as before), then [[keepDataRefusal]].
      * None = go ahead. */
    def deleteRefusal(request: HttpServletRequest, config: PipelineConfig, plan: PipelineDeletePlan.Plan): Option[ResponseEntity[String]] = {
        try CapabilityCheck.assertVisible(request, "pipeline", "delete", CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
        catch {
            case _: ResourceHiddenException => return Some(pipelineNotFound(config.name))
            case e: CapabilityDeniedException => return Some(capabilityDenied(e))
        }
        keepDataRefusal(plan, config)
    }

    /** DELETE /pipeline: 400 `{"error": KeepDataVectorRefusal}` for a
      * keep-data delete of a vector pipeline (nothing is deleted); None
      * otherwise. */
    def keepDataRefusal(plan: PipelineDeletePlan.Plan, config: PipelineConfig): Option[ResponseEntity[String]] =
        PipelineDeletePlan
            .refusal(plan, config)
            .map(msg => ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(msg)))

    /** Dataset env for the kept-data labels: the default bucket and tenant
      * database only. The pgvector database (a secret read) is never needed:
      * a vector pipeline cannot keep its data. */
    def keepDatasetEnv(): SharedDatasets.Env = {
        val env = scala.util.Try(DatrisEnvironment.current).toOption.flatMap(Option(_))
        val defaultBucket = env.flatMap(e => scala.util.Try(e.environment + "-data").toOption).getOrElse("")
        val multiTenant = env.exists(e => scala.util.Try(e.multiTenant).getOrElse(false))
        val tenantDatabase = if (multiTenant) env.flatMap(e => Option(e.environment)) else None
        SharedDatasets.Env(defaultBucket, tenantDatabase, None)
    }

    /** DELETE /pipeline: one warning per vector container of `config` that
      * another pipeline in `others` also names (the container is kept). */
    def vectorDeleteWarnings(request: HttpServletRequest, config: PipelineConfig, others: Seq[PipelineConfig]): Seq[String] =
        VectorContainers.sharedOnDelete(config, others).map { case (k, sharers) =>
            VectorContainers.keptOnDeleteMessage(VectorContainers.displayKey(k, config), visibleNamesOf(request, sharers))
        }

    /** The environment values shared-dataset keys depend on: the default
      * bucket, the tenant database (multiTenant) and the pgvector secret's
      * database. Never throws: an unreadable environment or pgvector secret
      * leaves that value unknown (an unknown pgvector database matches any
      * Postgres database, the safe direction). */
    def datasetEnv(): SharedDatasets.Env = {
        val env = scala.util.Try(DatrisEnvironment.current).toOption.flatMap(Option(_))
        val defaultBucket = env.flatMap(e => scala.util.Try(e.environment + "-data").toOption).getOrElse("")
        val multiTenant = env.exists(e => scala.util.Try(e.multiTenant).getOrElse(false))
        val tenantDatabase = if (multiTenant) env.flatMap(e => Option(e.environment)) else None
        val pgvectorDatabase =
            if (multiTenant) tenantDatabase
            else
                env.flatMap { e =>
                    scala.util
                        .Try(SecretsUtil.getSecretMap(e.pgvectorSecretName))
                        .toOption
                        .flatten
                        .flatMap(m => Option(m.get("jdbcUrl")))
                        .flatMap(SharedDatasets.databaseOfJdbcUrl)
                }
        SharedDatasets.Env(defaultBucket, tenantDatabase, pgvectorDatabase)
    }

    /** Capability actions that let an API key overwrite an existing
      * pipeline through POST /pipeline (a key may re-save its own in-scope
      * pipeline with either; the route gate is `pipeline:create`). */
    val SaveOverwriteActions: Seq[String] = Seq("create", "update")

    /** Actions for a version restore over a pipeline (route gate
      * `pipeline:update`). */
    val RestoreOverwriteActions: Seq[String] = Seq("update")

    /** Whether [[overwriteRefusal]] can refuse this caller at all, so the
      * stored config is read only then: an API key (or session) that holds
      * none of `actions` unscoped, and not a restricted user's session
      * (those get their 404/403 from `catalogForSave` /
      * `EntityVersionAPIController.versionRefusal`).
      * False for no key, full keys and unrestricted sessions. */
    def overwriteCheckNeeded(request: HttpServletRequest, actions: Seq[String]): Boolean =
        CapabilityCheck.resolvedKey(request).isDefined && !CapabilityCheck.isRestrictedSession(request) &&
            !actions.exists(a => CapabilityCheck.grants(request, "pipeline", a, Map.empty))

    /** POST /pipeline and version restore (its guard decision), API keys: 403 (capability-denied
      * shape) when a pipeline of that name exists and the key's scope
      * (`catalog=`, `owner=self`) grants none of `actions` on it, so a
      * scoped key cannot overwrite or restore a pipeline outside its scope.
      * Runs before anything is validated or written. None for a new name, a
      * restricted user's session (handled by its own 404/403 rules), no
      * key, full keys and unrestricted sessions. */
    def overwriteRefusal(request: HttpServletRequest, existing: PipelineConfig, actions: Seq[String]): Option[ResponseEntity[String]] =
        if (existing == null || !overwriteCheckNeeded(request, actions)) None
        else {
            val ctx = CatalogOps.scopeContext(existing.catalog, existing.createdByKeyLabel)
            if (actions.exists(a => CapabilityCheck.grants(request, "pipeline", a, ctx))) None
            else
                try {
                    CapabilityCheck.assertScope(request, "pipeline", actions.last, ctx)
                    None
                } catch {
                    case e: CapabilityDeniedException => Some(capabilityDenied(e))
                }
        }

    /** [[datasetEnv]] for a delete, read only when it can matter: data is
      * deleted and the destination has a `database` or `pgvector` section
      * (the only ones with a table or collection key). Otherwise an
      * all-unknown env, which yields no structured key for such a config,
      * so the pgvector secret is not read on every delete. */
    def deleteDatasetEnv(config: PipelineConfig, deleteData: Boolean): SharedDatasets.Env = {
        val d = Option(config).flatMap(c => Option(c.destination)).orNull
        if (deleteData && d != null && (d.database != null || d.pgvector != null)) datasetEnv()
        else SharedDatasets.Env("", None, None)
    }

    /** True when the caller cannot see every pipeline: a restricted user's
      * session, or an API key whose pipeline read is scoped (`catalog=`,
      * `owner=self`) or absent. False for unrestricted sessions, full keys
      * and no key (auth off). */
    def limitedVisibility(request: HttpServletRequest): Boolean =
        !CapabilityCheck.grants(request, "pipeline", "read", Map.empty)

    /** POST /pipeline (and version restore, which saves through the same
      * path), limited-visibility callers only: 400 `{"error": ...}` when the candidate adds a dataset (not
      * already written by `stored`) that a pipeline hidden from the caller
      * writes. The text is the same whatever `authoritative` says and never
      * names a pipeline or catalog. None for everyone else. */
    def hiddenWriterRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env
    ): Option[ResponseEntity[String]] =
        if (!limitedVisibility(request)) None
        else
            SharedDatasets
                .hiddenWriterConflicts(
                    candidate,
                    stored,
                    others,
                    o => visiblePipelines(request, Seq(o)).nonEmpty,
                    env,
                    // A stored config of the same name the caller cannot see
                    // (a scoped key reusing a hidden pipeline's name) is a
                    // hidden writer, not a grandfathered sharer.
                    storedVisible = stored == null || visiblePipelines(request, Seq(stored)).nonEmpty
                )
                .headOption
                .map { k =>
                    val name = LineageService
                        .datasets(candidate)
                        .find(_.kind == k.kind)
                        .map(_.name)
                        .getOrElse(k.kind + ":" + k.parts.filter(_.nonEmpty).mkString("."))
                    val body = new JsonObject()
                    body.addProperty("error", SharedDatasets.refusalMessage(name))
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](body.toString)
                }

    /** POST /pipeline and version restore, restricted users' sessions only:
      * each fresh target of the candidate in `DestinationTargets.ProbedFamilies`
      * (plus a SQL Server letter-case change) that no pipeline the caller can
      * see covers is checked with `exists` (the candidate's own connection).
      * 400 `ExistsRefusal` naming the caller's own coordinates on the first
      * one that exists; 500 `ProbeUnavailable` when `exists` throws (nothing
      * else in the body).
      *
      * Before any probe: 400 `UnsafePathRefusal` for a changed object-store
      * target whose path is not a plain prefix; 400 `TempTableRefusal` for a
      * changed SQL Server table named `#...`; then, when the candidate has an
      * object-store destination and either has no stored config the caller
      * can see (any format) or turns a stored config into Iceberg, 400
      * `NameCommittedRefusal` when Unity Catalog state kept for the name
      * records a catalog commit (an Iceberg read of the name
      * loads that table, wherever the catalog put it; a failed state read is
      * the 500). A stored Iceberg
      * config the caller can see keeps its own state unchecked; a flip back
      * to Iceberg cannot tell its own earlier commit from a deleted
      * pipeline's, so it is refused (fail closed). `others` is filtered to
      * the visible pipelines here. None for everyone else (unrestricted
      * sessions, every API key, auth off): no probe, no read.
      *
      * A pgvector table is checked on its own path after those probes
      * (`DestinationTargets.vectorTablesToProbe`, with `vectorTableExists`):
      * 400 `UnsafeVectorTableRefusal` first for a changed pgvector table whose
      * schema or name holds a double quote or NUL (or an empty schema), and
      * `BlankVectorTableRefusal` for a null or blank table, then the same 400 /
      * 500 / 429 answers as above. Every caller already gets those two
      * refusals from [[vectorTableNameRefusal]], which runs first on the save
      * path; this branch adds that a stored config the caller cannot see
      * grandfathers nothing. The pgvector SQL itself escapes every name
      * (`PGVectorSql`).
      *
      * This six-argument form is the spec seam for the probe alone: it skips
      * the Unity Catalog state check and the pgvector table probe. Production
      * paths call the seven-argument form below with
      * `DestinationProbe.catalogCommitted`. */
    private[api] def existingDestinationRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] =
        existingDestinationRefusal(request, candidate, stored, others, env, exists, _ => false, DestinationProbe.DefaultDeadline, _ => false)

    /** [[existingDestinationRefusal]] with the Unity Catalog state check
      * given, under `DestinationProbe.DefaultDeadline`. */
    def existingDestinationRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        catalogCommitted: String => Boolean
    ): Option[ResponseEntity[String]] =
        existingDestinationRefusal(request, candidate, stored, others, env, exists, catalogCommitted, DestinationProbe.DefaultDeadline)

    /** [[existingDestinationRefusal]] with the deadline given: the Unity
      * Catalog state read and every probe together get at most `deadline`
      * (each runs on a probe worker, `DestinationProbe.withDeadline`); past
      * it the save answers 500 `ProbeUnavailable` and nothing is saved. Each
      * read and probe takes one of the caller's `DestinationProbe.MaxPerOwner`
      * slots (owner `ResolvedKey.label`); over the limit the save answers 429
      * [[tooManyChecks]] and nothing is started or saved. The pgvector table
      * is probed with `DestinationProbe.vectorTableExists` on `candidate`. */
    def existingDestinationRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        catalogCommitted: String => Boolean,
        deadline: scala.concurrent.duration.FiniteDuration
    ): Option[ResponseEntity[String]] =
        existingDestinationRefusal(
            request,
            candidate,
            stored,
            others,
            env,
            exists,
            catalogCommitted,
            deadline,
            (t: DestinationTargets.Target) => DestinationProbe.vectorTableExists(candidate, t)
        )

    /** [[existingDestinationRefusal]] with the pgvector table probe given
      * (`vectorTableExists`, for `DestinationTargets.vectorTablesToProbe`
      * targets); it runs after `exists`'s targets, under the same deadline
      * and the same per-caller slots. */
    def existingDestinationRefusal(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env,
        exists: DestinationTargets.Target => Boolean,
        catalogCommitted: String => Boolean,
        deadline: scala.concurrent.duration.FiniteDuration,
        vectorTableExists: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] = {
        if (!CapabilityCheck.isRestrictedSession(request) || candidate == null) return None
        val deadlineAt = System.nanoTime() + deadline.toNanos
        val owner = probeOwner(request)
        def bounded[T](what: String)(body: => T): T =
            DestinationProbe.withDeadline(scala.concurrent.duration.Duration.fromNanos(deadlineAt - System.nanoTime()), what, owner)(body)
        // A stored config the caller cannot see grandfathers nothing.
        val keptFrom = if (stored != null && visiblePipelines(request, Seq(stored)).isEmpty) null else stored
        def badRequest(message: String): Option[ResponseEntity[String]] =
            Some(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](ApiErrors.errorBody(message)))
        val unavailable: Option[ResponseEntity[String]] =
            Some(
                ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body[String](ApiErrors.errorBody(DestinationTargets.ProbeUnavailable))
            )
        val changed = DestinationTargets.freshTargets(candidate, keptFrom, env) ++ DestinationTargets.recasedTargets(candidate, keptFrom, env)
        if (changed.exists(_.key.family == "objectstore") && DestinationTargets.unsafeObjectStorePath(candidate.destination.objectStore))
            return badRequest(DestinationTargets.UnsafePathRefusal)
        if (changed.exists(_.key.family == "sqlserver") && DestinationTargets.sqlServerTempTable(candidate))
            return badRequest(DestinationTargets.TempTableRefusal)
        val changedVectorTables = DestinationTargets.freshVectorTables(candidate, keptFrom, env)
        if (changedVectorTables.nonEmpty && DestinationTargets.unsafeVectorTableName(candidate))
            return badRequest(DestinationTargets.UnsafeVectorTableRefusal)
        // A null or blank pgvector table has no target to probe, but the
        // delete still drops "schema"."null" or the blank name: refused unless
        // the visible stored config carries the identical schema and table.
        if (DestinationTargets.blankVectorTableName(candidate) && !DestinationTargets.sameVectorTableNames(candidate, keptFrom))
            return badRequest(DestinationTargets.BlankVectorTableRefusal)
        val candidateTarget = DestinationTargets.targetsOf(candidate, env).find(_.key.family == "objectstore")
        def iceberg(c: PipelineConfig): Boolean =
            c != null && c.destination != null && c.destination.objectStore != null &&
                "iceberg".equalsIgnoreCase(Option(c.destination.objectStore.fileFormat).map(_.trim).orNull)
        if (candidateTarget.isDefined && (keptFrom == null || (iceberg(candidate) && !iceberg(keptFrom)))) {
            val committed =
                try Right(bounded("Unity Catalog state read")(catalogCommitted(candidate.name)))
                catch {
                    case _: DestinationProbe.TooManyChecks =>
                        auditLogger.warn("Destination check of pipeline " + candidate.name + " not started: the caller's other checks are still running")
                        return Some(tooManyChecks)
                    case scala.util.control.NonFatal(e) =>
                        auditLogger.warn(
                            "Unity Catalog state read failed for the destination check of pipeline " + candidate.name + ": " +
                                Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                        )
                        Left(e)
                }
            committed match {
                case Left(_) => return unavailable
                case Right(true) => return badRequest(DestinationTargets.NameCommittedRefusal)
                case Right(false) =>
            }
        }
        val visible = visiblePipelines(request, Option(others).getOrElse(Nil))
        // The pgvector table is probed after the other targets, with its own probe.
        val probe: Seq[(DestinationTargets.Target, DestinationTargets.Target => Boolean)] =
            DestinationTargets.toProbe(candidate, keptFrom, visible, env).map(_ -> exists) ++
                DestinationTargets.vectorTablesToProbe(candidate, keptFrom, visible, env).map(_ -> vectorTableExists)
        val it = probe.iterator
        while (it.hasNext) {
            val (t, check) = it.next()
            val found =
                try Right(bounded("destination probe (" + t.key.family + ")")(check(t)))
                catch {
                    case _: DestinationProbe.TooManyChecks =>
                        auditLogger.warn(
                            "Destination existence probe for pipeline " + candidate.name + " (" + t.key.family +
                                ") not started: the caller's other checks are still running"
                        )
                        return Some(tooManyChecks)
                    case scala.util.control.NonFatal(e) =>
                        auditLogger.warn(
                            "Destination existence probe failed for pipeline " + candidate.name + " (" + t.key.family + "): " +
                                Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                        )
                        Left(e)
                }
            found match {
                case Left(_) => return unavailable
                case Right(true) => return badRequest(DestinationTargets.ExistsRefusal(SharedDatasets.label(t.key)))
                case Right(false) =>
            }
        }
        None
    }

    /** Owner of the caller's destination-check slots: `ResolvedKey.label`
      * (`session:<user>` for a session and for on-behalf-of). */
    private[api] def probeOwner(request: HttpServletRequest): String =
        CapabilityCheck.resolvedKey(request).map(_.label).getOrElse("anonymous")

    /** 429: the caller already has `DestinationProbe.MaxPerOwner` destination
      * checks running; nothing was started or saved. */
    def tooManyChecks: ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", DestinationTargets.RetryAfterSeconds)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody(DestinationTargets.TooManyChecksRefusal))

    /** POST /pipeline: 500 "nothing was saved" when the config read failed
      * and a limited-visibility caller's candidate adds a dataset its stored
      * config does not write (the hidden-writer rule cannot be checked).
      * None for everyone else (fail-open, as before). */
    def hiddenWriterCheckUnavailable(
        request: HttpServletRequest,
        candidate: PipelineConfig,
        stored: PipelineConfig,
        readFailed: Boolean,
        env: SharedDatasets.Env
    ): Option[ResponseEntity[String]] =
        if (!readFailed || !limitedVisibility(request)) None
        else {
            val kept = SharedDatasets.keysOf(stored, env).toSet
            if (!SharedDatasets.keysOf(candidate, env).exists(k => !kept.contains(k))) None
            else
                Some(
                    ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body[String](
                            ApiErrors.errorBody(
                                "Could not check whether the destination is used by another pipeline; nothing was saved. Try again."
                            )
                        )
                )
        }

    /** DELETE /pipeline: the configs the vector and structured delete guards
      * are checked against. Nil without reading when no data is deleted or
      * the config names no vector container and no table or collection;
      * otherwise `read()` (nulls dropped), whose failure propagates so the
      * delete fails before anything is destroyed. */
    def othersForDelete(
        config: PipelineConfig,
        deleteData: Boolean,
        env: SharedDatasets.Env,
        read: () => Seq[PipelineConfig]
    ): Seq[PipelineConfig] = {
        val guarded = VectorContainers.keysOf(config).nonEmpty ||
            SharedDatasets.keysOf(config, env).exists(k => SharedDatasets.StructuredFamilies.contains(k.family))
        if (!deleteData || !guarded) Nil
        else
            try read().filter(_ != null)
            catch {
                case e: Exception =>
                    throw new IllegalStateException(
                        "Could not read pipeline configs to check for shared tables, collections or vector containers; nothing was deleted: " +
                            e.getMessage,
                        e
                    )
            }
    }

    /** DELETE /pipeline: one warning per table or collection of `config`
      * that another pipeline in `others` also writes (it is kept). Sharers
      * are named only when the caller can see all of them. */
    def structuredDeleteWarnings(
        request: HttpServletRequest,
        config: PipelineConfig,
        others: Seq[PipelineConfig],
        env: SharedDatasets.Env
    ): Seq[String] =
        SharedDatasets.sharedOnDelete(config, others, env).map { case (k, sharers) =>
            SharedDatasets.keptOnDeleteMessage(SharedDatasets.label(k), visibleNamesOf(request, sharers))
        }

    /** Limited-visibility callers only: a pipeline hidden from the caller
      * that lists `name` in `previousNames` (other than one currently named
      * `name`), or None. None for everyone else. Saving, restoring or
      * renaming into such a name would hand its stored records to the
      * caller ([[PipelineAliases]]). */
    def hiddenAliasClaimant(request: HttpServletRequest, name: String, all: Seq[PipelineConfig]): Option[PipelineConfig] =
        if (name == null || !limitedVisibility(request)) None
        else PipelineAliases.listersOf(name, Option(all).getOrElse(Nil).filter(_ != null)).find(p => visiblePipelines(request, Seq(p)).isEmpty)

    /** POST /pipeline for a name no stored config carries, limited-visibility
      * callers: when [[hiddenAliasClaimant]] finds a hidden pipeline listing
      * the name, the refusal a hidden pipeline of that name would get today
      * (no new oracle): for a restricted session the [[catalogForSave]]
      * refusal (its 404), for an API key the [[overwriteRefusal]] 403 on
      * `actions` (or, for a key whose write is unscoped but whose read is
      * not, the same capability-denied 403 on read). None for everyone else. */
    def hiddenAliasSaveRefusal(
        request: HttpServletRequest,
        config: PipelineConfig,
        all: Seq[PipelineConfig],
        actions: Seq[String]
    ): Option[ResponseEntity[String]] =
        if (config == null) None
        else
            hiddenAliasClaimant(request, config.name, all).map { hidden =>
                if (CapabilityCheck.isRestrictedSession(request))
                    catalogForSave(request, config, hidden).left.toOption.getOrElse(pipelineNotFound(config.name))
                else overwriteRefusal(request, hidden, actions).getOrElse(readScopeDenied(request, hidden))
            }

    /** POST /pipeline: callers whose save is decided from the stored config
      * of the same name (API keys whose create/update is scoped, callers who
      * cannot see every pipeline, restricted sessions). False for full keys,
      * unrestricted sessions and auth-off installs: they read nothing before
      * the write and take no lock. */
    def saveGuardNeeded(request: HttpServletRequest): Boolean =
        overwriteCheckNeeded(request, SaveOverwriteActions) || limitedVisibility(request) || CapabilityCheck.isRestrictedSession(request) ||
            CapabilityCheck.keyScopeApplies(request, "pipeline", "create")

    /** POST /pipeline, API keys (plans/stories/scoped-key-read-routes.md,
      * Design 4): the pipeline as it would be saved must be in the key's
      * scope, `pipeline:create` for a new name and `pipeline:create` or
      * `pipeline:update` over a stored one, on its target catalog (the body's,
      * so a blank catalog is no catalog: keys get no defaulting) and the
      * stored owner (the caller's label for a new name). The 403 names the
      * action attempted (`create` for a new name, `update` over a stored
      * one). None for sessions, no identity, full keys and keys whose create
      * is unscoped. */
    def keyTargetRefusal(request: HttpServletRequest, config: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] =
        CapabilityCheck.resolvedKey(request) match {
            case Some(rk) if config != null && !rk.restrictedSession =>
                val owner = if (stored != null) stored.createdByKeyLabel else rk.label
                val ctx = CatalogOps.scopeContext(config.catalog, owner)
                val acceptable = if (stored != null) SaveOverwriteActions else Seq("create")
                val attempted = if (stored != null) "update" else "create"
                if (!acceptable.exists(a => ai.datris.auth.CallerVisibility.keyOutOfScope(rk, "pipeline", a, ctx))) None
                else if (acceptable.exists(a => rk.grants("pipeline", a, ctx))) None
                else
                    try {
                        CapabilityCheck.assertScope(request, "pipeline", attempted, ctx)
                        None
                    } catch {
                        case e: CapabilityDeniedException => Some(capabilityDenied(e))
                    }
            case _ => None
        }

    /** POST /pipeline for a [[saveGuardNeeded]] caller, decided from ONE
      * stored-config read (`stored`, the config named `config.name` or null):
      * the scope check on `stored` ([[overwriteRefusal]], 403), for an API
      * key a stored config it cannot read (403 on `pipeline:read`, naming
      * nothing of it) and the pipeline as it would be saved outside its
      * scope ([[keyTargetRefusal]], 403; plans/stories/scoped-key-read-routes.md),
      * then for a
      * null `stored` the previous-name check ([[hiddenAliasSaveRefusal]];
      * `readAll` is called only then, and a failure is
      * [[aliasCheckUnavailable]], 500), then the restricted-session catalog
      * rules ([[catalogForSave]] against the same `stored`). Right is the
      * config to save. Reads nothing itself besides `readAll`. */
    def saveGuardDecision(
        request: HttpServletRequest,
        config: PipelineConfig,
        stored: PipelineConfig,
        readAll: () => Seq[PipelineConfig]
    ): Either[ResponseEntity[String], PipelineConfig] = {
        overwriteRefusal(request, stored, SaveOverwriteActions).foreach(refused => return Left(refused))
        // API keys (scoped-key-read-routes, Design 4): a stored pipeline the
        // key cannot read is never overwritten (the read refusal, naming
        // nothing of it), and the pipeline as it would be saved must be in
        // the key's scope (no move or create outside it).
        if (stored != null && !CapabilityCheck.isRestrictedSession(request))
            CapabilityCheck.resolvedKey(request).foreach { rk =>
                if (ai.datris.auth.CallerVisibility.keyOutOfScope(rk, "pipeline", "read", CatalogOps.scopeContext(stored.catalog, stored.createdByKeyLabel)))
                    return Left(readScopeDenied(request, stored))
            }
        keyTargetRefusal(request, config, stored).foreach(refused => return Left(refused))
        if (stored == null && config.name != null && limitedVisibility(request)) {
            val all =
                try readAll()
                catch { case _: Exception => return Left(aliasCheckUnavailable("saved")) }
            hiddenAliasSaveRefusal(request, config, all, SaveOverwriteActions).foreach(refused => return Left(refused))
        }
        if (CapabilityCheck.isRestrictedSession(request)) catalogForSave(request, config, stored)
        else Right(config)
    }

    /** POST /pipeline, [[saveGuardNeeded]] callers, under
      * `CatalogOps.withCatalogLock` just before the write: `fresh` is the
      * config of that name read under the lock. A save [[saveGuardDecision]]
      * refuses on `fresh` gets that refusal (the one a retry would get); a
      * save allowed on `fresh` whose stored config changed since the decision
      * (`decided`: appeared, disappeared, or moved catalog, owner or earlier
      * names) gets [[saveConflict]] (409). None: write. */
    def saveRecheck(
        request: HttpServletRequest,
        config: PipelineConfig,
        decided: PipelineConfig,
        fresh: PipelineConfig,
        readAll: () => Seq[PipelineConfig]
    ): Option[ResponseEntity[String]] =
        saveGuardDecision(request, config, fresh, readAll) match {
            case Left(refused) => Some(refused)
            case Right(_) if !sameGuardState(decided, fresh) => Some(saveConflict(config.name))
            case Right(_) => None
        }

    /** The stored fields [[saveGuardDecision]] and the save's preservation of
      * server-maintained state depend on. */
    private[api] def sameGuardState(a: PipelineConfig, b: PipelineConfig): Boolean =
        if (a == null || b == null) a == null && b == null
        else
            a.catalog == b.catalog && a.createdByKeyLabel == b.createdByKeyLabel &&
            Option(a.previousNames).map(_.asScala.toList).getOrElse(Nil) == Option(b.previousNames).map(_.asScala.toList).getOrElse(Nil)

    /** 409 when the pipeline changed while a save was being checked: nothing
      * was saved. */
    def saveConflict(name: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody("Pipeline '" + name + "' changed while it was being saved; nothing was saved. Try again."))

    /** 409 when the pipeline changed while a version restore was being
      * checked: nothing was restored. */
    def restoreConflict(name: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody("Pipeline '" + name + "' changed while it was being restored; nothing was restored. Try again."))

    /** A version restore through the save path: the live config the restore
      * decided on (null when deleted), the version restored and the 404 a
      * hidden history answers. */
    case class RestoreOf(live: PipelineConfig, version: Int, hidden: () => ResponseEntity[String])

    /** The capability-denied 403 for `pipeline:read` on `hidden`'s scope. */
    private[api] def readScopeDenied(request: HttpServletRequest, hidden: PipelineConfig): ResponseEntity[String] =
        try {
            CapabilityCheck.assertScope(request, "pipeline", "read", CatalogOps.scopeContext(hidden.catalog, hidden.createdByKeyLabel))
            capabilityDenied(new CapabilityDeniedException("capability denied"))
        } catch {
            case e: CapabilityDeniedException => capabilityDenied(e)
        }

    /** 500 when the configs needed for the previous-name check could not be
      * read: nothing was `done` (saved, restored). */
    def aliasCheckUnavailable(done: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody("Could not check whether the name is in use; nothing was " + done + ". Try again."))

    /** GET /pipelines: the configs the caller may read (per-item scope
      * check on catalog + owner); nulls dropped. */
    def visiblePipelines(request: HttpServletRequest, configs: Seq[PipelineConfig]): Seq[PipelineConfig] =
        configs.filter(c => c != null && CapabilityCheck.grants(request, "pipeline", "read", CatalogOps.scopeContext(c.catalog, c.createdByKeyLabel)))

    /** POST /pipeline catalog rules for a restricted user's session (no-op
      * for everyone else). `existing` is the stored config of the same name,
      * or null. Right is the config to save (a blank catalog filled in with
      * the one editable catalog); Left is the refusal. */
    def catalogForSave(
        request: HttpServletRequest,
        config: PipelineConfig,
        existing: PipelineConfig
    ): Either[ResponseEntity[String], PipelineConfig] =
        RestrictedCatalogRules.catalogForSave(
            request,
            "pipeline",
            config.catalog,
            existing != null,
            if (existing == null) null else existing.catalog,
            if (existing == null) null else existing.createdByKeyLabel,
            pipelineNotFound(config.name)
        ).right.map {
            case Some(c) if c != config.catalog => config.copy(catalog = c)
            case _ => config
        }

    /** `newName` from a rename body, or null (no body, not JSON, missing). */
    private[api] def readNewName(body: String): String =
        if (body == null || body.trim.isEmpty) null
        else
            try {
                val obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject
                if (obj.has("newName") && !obj.get("newName").isJsonNull) obj.get("newName").getAsString else null
            } catch { case _: Exception => null }

    private[api] def renameError(status: HttpStatus, message: String): ResponseEntity[String] = {
        val o = new JsonObject
        o.addProperty("error", message)
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body[String](o.toString)
    }

    /** POST /pipelines/{name}/rename, before anything is written, in this
      * order: missing (404, [[pipelineNotFound]]); scope, own on the
      * pipeline's catalog (`pipeline:delete`: a restricted session that
      * cannot see it gets the same 404, one that can see it but does not own
      * it 403; an API key's scope miss 403); the new name (400); Unity Catalog
      * sync (400); a job initialized or processing (409); a taken new name
      * (409) unless it holds this pipeline from a rename that stopped half
      * way (`resumable` and `priorAttempt`, [[PipelineRename.priorAttempt]]
      * on the stored version history); a free new name that a pipeline hidden
      * from the caller lists in `previousNames` (`hiddenAliasClaim`, the same
      * 409). None = go ahead. The vector-container and authority rules are
      * never consulted: the destination does not change. */
    def renameRefusal(
        request: HttpServletRequest,
        name: String,
        newName: String,
        old: PipelineConfig,
        existingNew: PipelineConfig,
        jobs: Seq[(String, ai.datris.model.JobState)],
        ucStateExists: Boolean,
        priorAttempt: Boolean = true,
        hiddenAliasClaim: => Boolean = false,
        catalogCommitted: String => Boolean = _ => false
    ): Option[ResponseEntity[String]] = {
        if (old == null) return Some(pipelineNotFound(name))
        refusalFor(request, name, old, "delete").foreach(r => return Some(r))
        try CapabilityCheck.assertVisible(request, "pipeline", "delete", CatalogOps.scopeContext(old.catalog, old.createdByKeyLabel))
        catch {
            case _: ResourceHiddenException => return Some(pipelineNotFound(name))
            case e: CapabilityDeniedException => return Some(capabilityDenied(e))
        }
        PipelineRename.nameProblem(name, newName).foreach(p => return Some(renameError(HttpStatus.BAD_REQUEST, p)))
        val target = newName.trim
        val ucEnabled = UnityCatalogSync.stateFields(old, UnityCatalogSync.defaultEnabledFromEnv)._1
        PipelineRename.unityCatalogBlock(ucEnabled, ucStateExists).foreach(p => return Some(renameError(HttpStatus.BAD_REQUEST, p)))
        if (PipelineRename.jobBlock(jobs, name))
            return Some(renameError(HttpStatus.CONFLICT, "Pipeline " + name + " has a job running; rename it once the job has finished"))
        // A name a pipeline hidden from the caller lists in previousNames:
        // the same 409 as a taken name (evaluated only here, for a free name).
        if (existingNew == null && hiddenAliasClaim)
            return Some(renameError(HttpStatus.CONFLICT, "Pipeline '" + target + "' already exists. Choose another name."))
        if (existingNew != null && !(PipelineRename.resumable(old, existingNew) && priorAttempt))
            return Some(renameError(HttpStatus.CONFLICT, "Pipeline '" + target + "' already exists. Choose another name."))
        // Restricted sessions, an object-store pipeline taking a free name
        // whose kept Unity Catalog state records a catalog commit: an Iceberg
        // read under that name would load the recorded table. The same 409
        // as a taken name; a failed state read refuses (500), and one not
        // started because the caller's other checks still run answers 429.
        if (
            existingNew == null && CapabilityCheck.isRestrictedSession(request) &&
            old.destination != null && old.destination.objectStore != null
        ) {
            val committed =
                try catalogCommitted(target)
                catch {
                    case _: DestinationProbe.TooManyChecks =>
                        auditLogger.warn(
                            "Unity Catalog state read for the rename check of " + name + " not started: the caller's other checks are still running"
                        )
                        return Some(tooManyChecks)
                    case scala.util.control.NonFatal(e) =>
                        auditLogger.warn("Unity Catalog state read failed for the rename check of " + name + ": " + e.getMessage)
                        return Some(aliasCheckUnavailable("renamed"))
                }
            if (committed) return Some(renameError(HttpStatus.CONFLICT, "Pipeline '" + target + "' already exists. Choose another name."))
        }
        None
    }

    /** The rename's 200/207 body: `name`, `previousName`, `version`,
      * `repointedTaps` (only the taps the caller may read: a hidden tap is
      * re-pointed but never named), `failed` ([{name, error}], as given) and
      * `warnings`. */
    def renameResponseBody(
        request: HttpServletRequest,
        renamed: PipelineConfig,
        previousName: String,
        repointed: Seq[ai.datris.model.TapConfig],
        failed: Seq[(String, String)],
        warnings: Seq[String]
    ): JsonObject = {
        val out = new JsonObject
        out.addProperty("name", renamed.name)
        out.addProperty("previousName", previousName)
        out.addProperty("version", renamed.version)
        val taps = new com.google.gson.JsonArray
        repointed
            .filter(t => t != null && CapabilityCheck.grants(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel)))
            .foreach(t => taps.add(t.name))
        out.add("repointedTaps", taps)
        val f = new com.google.gson.JsonArray
        failed.foreach { case (n, err) =>
            val o = new JsonObject
            o.addProperty("name", n)
            o.addProperty("error", err)
            f.add(o)
        }
        out.add("failed", f)
        val w = new com.google.gson.JsonArray
        warnings.foreach(w.add(_))
        out.add("warnings", w)
        out
    }

    /** Rename: the old name's definition versions appended under the new
      * name (re-keyed, snapshot `name` rewritten), skipping keys already
      * there (a resumed rename). A pipeline with no version rows (saved
      * before versioning) is seeded under the new name at its current
      * version, so the renamed config continues its numbering. Throws on a
      * read or write failure. */
    private[api] def copyVersionsForRename(versionTable: String, old: PipelineConfig, newName: String): Unit =
        copyVersionsForRenameWith(old, newName, n => EntityVersionIO.listVersions(versionTable, n), v => EntityVersionIO.append(versionTable, v))

    /** [[copyVersionsForRename]] on injected list and append. */
    private[api] def copyVersionsForRenameWith(
        old: PipelineConfig,
        newName: String,
        list: String => Seq[EntityVersion],
        append: EntityVersion => Unit
    ): Unit = {
        val have = list(newName).map(_.key).toSet
        val rows = list(old.name).map(PipelineRename.rekeyVersion(_, newName))
        rows.filterNot(v => have.contains(v.key)).foreach(append)
        if (rows.isEmpty && have.isEmpty) {
            val seedVersion = if (old.version > 0) old.version else 1
            append(
                EntityVersion(
                    key = EntityVersionIO.docKey(newName, seedVersion),
                    entityName = newName,
                    version = seedVersion,
                    config = new Gson().toJson(PipelineRename.renamedConfig(old, newName).copy(version = seedVersion)),
                    changeNote = PipelineRename.seedNote(old.name),
                    createdBy = "system"
                )
            )
        }
    }

    /** `existing` (the request's audit metadata attribute: a JsonObject, or
      * anything else = none) with `operation: rename` and `newName` added;
      * other keys are kept. */
    private[api] def renameAuditMetadata(existing: Any, newName: String): JsonObject = {
        val m = existing match {
            case o: JsonObject => o
            case _ => new JsonObject
        }
        m.addProperty("operation", "rename")
        if (newName != null) m.addProperty("newName", newName.take(256))
        m
    }

    /** The rename's audit row: `operation: rename` and `newName` merged into
      * any metadata already on the request (PolicyInterceptor's approval
      * replay sets some). Never throws. */
    private[api] def auditRename(request: HttpServletRequest, newName: String): Unit =
        try request.setAttribute(
                ai.datris.audit.AuditLog.MetadataAttr,
                renameAuditMetadata(request.getAttribute(ai.datris.audit.AuditLog.MetadataAttr), newName)
            )
        catch {
            case e: Exception => auditLogger.warn("Could not record rename audit metadata: " + e.getMessage)
        }

    /** 404 for a pipeline name with no config (GET and DELETE /pipeline),
      * with the "not configured in the NoSQL database" error body. */
    def pipelineNotFound(name: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body[String](QueryAPIController.errorBody(new DatrisException("Pipeline: " + name + " is not configured in the NoSQL database")))

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
                if (r.scriptVersion > 0) e.addProperty("version", r.scriptVersion)
                e.addProperty("fingerprint", r.fingerprint)
                e.addProperty("storage", r.storage)
                e.addProperty("repoPath", r.scriptRepoPath)
                e.addProperty("commitSha", r.scriptCommitSha)
                e.addProperty("conflict", r.conflictReason != null)
                if (r.conflictReason != null) e.addProperty("conflictReason", r.conflictReason)
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
      * kind's GET entry on success. With `config` (the pipeline as saved now)
      * the adopted script's fingerprint is set to the current instruction and
      * schema, so the next run or save does not regenerate over it. */
    def pullCodegenScriptWith(
        scripts: PipelineScripts,
        name: String,
        kind: String,
        actor: String,
        config: PipelineConfig = null
    ): ResponseEntity[String] = {
        def bad(status: HttpStatus, msg: String): ResponseEntity[String] =
            ResponseEntity.status(status).body[String](QueryAPIController.errorBody(new DatrisException(msg)))
        if (!PipelineScripts.Kinds.contains(kind)) return bad(HttpStatus.BAD_REQUEST, PipelineScripts.unknownKindMessage(kind))
        if (config != null && PipelineScripts.instructionOf(config, kind).isEmpty)
            return bad(HttpStatus.BAD_REQUEST, "Pipeline: " + name + " has no AI " + kind + " instruction")
        scripts.record(name, kind) match {
            case None => bad(HttpStatus.BAD_REQUEST, "Pipeline " + name + " has no stored " + kind + " script")
            case Some(r) if !PipelineScripts.isRepoBacked(r) => bad(HttpStatus.BAD_REQUEST, PipelineScripts.notRepositoryMessage(name, kind))
            case Some(_) =>
                scripts.pull(name, kind, actor, config) match {
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
