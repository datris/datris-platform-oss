package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, VersionActor}
import ai.datris.build.sbt.BuildInfo
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, TapConfig}
import ai.datris.util.{
    APIKeyValidator,
    CatalogArchive,
    CatalogOps,
    CodeRepoConfigIO,
    GithubClient,
    GithubCodeStore,
    MinioCodeStore,
    NoSQLDbUtil,
    ObjectStoreUtil,
    PipelineConfigIO,
    PipelineScripts,
    SecretsUtil,
    StoredScript,
    TapCodeStore,
    TapConfigIO,
    TapDependencies,
    TapState,
    TapStateIO
}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpHeaders, HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._
import scala.collection.mutable

/** First-class catalog operations. A catalog is the `catalog` field on taps
  * and pipelines plus optional hidden placeholder taps `__catalog__*` (a
  * placeholder belongs to the catalog in its `catalog` field, falling back to
  * its name suffix when that field is empty);
  * these endpoints rewrite every member server-side instead of a client
  * fan-out, and report per-member failures (not transactional).
  *
  *   - `PUT /api/v1/catalog/{name}` body `{"newName": "..."}` renames;
  *     refused with 409 when `newName` already exists (never a merge; moving
  *     items is how catalogs are combined).
  *   - `DELETE /api/v1/catalog/{name}?mode=detach|cascade&confirm=<name>`
  *     detaches members to Uncataloged (default) or deletes them with their
  *     data (cascade, needs `confirm` equal to the name).
  *   - `GET /api/v1/catalog/{name}/export` downloads a zip backup of the
  *     catalog (configs, tap scripts and states, validation schemas, CodeGen
  *     reference copies; secret names only, never values). See CatalogArchive.
  *   - `GET /api/v1/catalog/{name}/enable-order` lists the catalog's taps in
  *     dependency levels (producers first) with per-tap readiness. Read only.
  *   - `POST /api/v1/catalog/{name}/restore` (multipart `file`) restores an
  *     archive as catalog `name`; refused with 409 when the catalog or any
  *     archived name already exists (`mode=overwrite&confirm=<name>` may
  *     replace members of `name` only). Restored taps are disabled.
  *
  * Capability gate: `CapabilityRoutes` maps PUT to pipeline:update and DELETE
  * to pipeline:delete; each member is additionally scope-checked (owner and
  * catalog) with its own resource type, and refused members land in
  * `failed`. When any member is refused the `__catalog__` placeholders are
  * left untouched; a placeholder-only catalog checks the placeholder itself
  * (403 on denial). Cascade skips its pipeline phase if a tap delete failed. */
@RestController
@RequestMapping(Array("/api/v1"))
class CatalogAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[CatalogAPIController])

    @PutMapping(path = Array("/catalog/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def renameCatalog(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @RequestBody(required = false) body: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint PUT /catalog/" + name + " called")
            APIKeyValidator.validate(apiKey)

            if (CatalogOps.isReserved(name))
                return badRequest("Catalog '" + Option(name).getOrElse("") + "' cannot be renamed: Uncataloged and blank names are reserved")
            val newName = readNewName(body)
            if (newName == null || newName.trim.isEmpty)
                return badRequest("Request body must include a non-empty \"newName\"")
            if (CatalogOps.isReserved(newName))
                return badRequest("Cannot rename a catalog to '" + newName + "': Uncataloged is reserved")
            if (!CatalogOps.isValidLabel(newName))
                return badRequest(
                    "Invalid catalog name '" + newName + "': use letters, digits, '_' and '-' only"
                )
            if (newName == name)
                return badRequest("New name is the same as the current name")

            // Serialized with every other catalog operation: the member
            // snapshot through the placeholder step must not interleave with a
            // concurrent rename/delete (which would move items twice).
            CatalogOps.withCatalogLock {
                val (taps, pipelines) = loadAll()
                val members = CatalogOps.members(taps, pipelines, name)
                val oldPlaceholders = CatalogOps.Placeholder.of(taps, name)
                if (members.isEmpty && oldPlaceholders.isEmpty)
                    return notFound(name)

                // A rename never merges: combining catalogs is done by moving
                // their items. An existing target is refused and nothing changes.
                if (CatalogOps.catalogExists(taps, pipelines, newName)) {
                    val out = new JsonObject
                    out.addProperty("error", "Catalog '" + newName + "' already exists. Use move to merge catalogs.")
                    return ResponseEntity.status(HttpStatus.CONFLICT).body[String](out.toString)
                }

                val actor = VersionActor.resolve(request)
                val note = "catalog renamed " + name + " → " + newName + " by " + actor
                placeholderScopeDenial(request, "update", name, members, oldPlaceholders).foreach(r => return r)
                val denied = mutable.ArrayBuffer.empty[String]
                val tapResult = CatalogOps.execute(members.taps.map(_.name)) { n =>
                    trackDenied(denied, n) {
                        val live = readTap(n, name)
                        CapabilityCheck.assertScope(request, "tap", "update", CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel))
                        TapConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)
                    }
                }
                val pipelineResult = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                    trackDenied(denied, n) {
                        val live = readPipeline(n, name)
                        CapabilityCheck.assertScope(
                            request,
                            "pipeline",
                            "update",
                            CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel)
                        )
                        PipelineConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)
                    }
                }

                val failed = Seq.newBuilder[(String, String)]
                failed ++= tapResult.failed
                failed ++= pipelineResult.failed

                // Placeholders are catalog bookkeeping, not members. They are left
                // alone when any member was refused by the caller's scope or when
                // no member moved; a placeholder-only catalog was scope-checked
                // above. Every placeholder of the old catalog is removed (legacy
                // ones may be named after another catalog).
                val okNames = tapResult.ok ++ pipelineResult.ok
                val touchPlaceholders =
                    CatalogOps.placeholderAction(members.isEmpty, denied.toSeq, okNames.nonEmpty) != CatalogOps.PlaceholderAction.Skip
                val removedPlaceholders =
                    if (!touchPlaceholders) Nil
                    else
                        oldPlaceholders.flatMap { ph =>
                            removePlaceholder(ph.name, name, request) match {
                                case Some(err) => failed += err; None
                                case None => Some(ph.name)
                            }
                        }
                val newPlaceholderName = CatalogOps.Placeholder.freshName(newName, taps.map(_.name).toSet -- removedPlaceholders)
                val placeholder =
                    if (!touchPlaceholders) "skipped"
                    else
                        try {
                            TapConfigIO.writeVersioned(
                                TapConfig(
                                    name = newPlaceholderName,
                                    description = "Catalog placeholder",
                                    targetPipeline = null,
                                    catalog = newName,
                                    enabled = false,
                                    createdByKeyLabel = ResolvedKeyAccess.keyLabel(request).orNull
                                ),
                                "catalog placeholder created by rename " + name + " → " + newName + " by " + actor,
                                actor
                            )
                            "created"
                        } catch {
                            case e: Exception =>
                                failed += (newPlaceholderName -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                                "failed"
                        }

                val failures = failed.result()
                val out = new JsonObject
                out.add("renamed", toArray(okNames))
                out.add("failed", failedArray(failures))
                out.add("affectedKeys", toArray(affectedKeys(name)))
                out.addProperty("placeholder", placeholder)
                respond(out, failures)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @DeleteMapping(path = Array("/catalog/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteCatalog(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @RequestParam(name = "mode", defaultValue = "detach") mode: String,
        @RequestParam(name = "confirm", required = false) confirm: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /catalog/" + name + " called with mode: " + mode)
            APIKeyValidator.validate(apiKey)

            if (CatalogOps.isReserved(name))
                return badRequest("Catalog '" + Option(name).getOrElse("") + "' cannot be deleted: Uncataloged and blank names are reserved")
            val m = Option(mode).map(_.trim.toLowerCase).getOrElse("detach")
            if (m != "detach" && m != "cascade")
                return badRequest("Invalid mode '" + mode + "': use 'detach' or 'cascade'")
            if (m == "cascade" && confirm != name)
                return badRequest(
                    "Cascade delete removes every tap and pipeline in '" + name +
                        "' and their data; pass confirm=<catalog name> to proceed"
                )

            // Serialized with every other catalog operation: the member
            // snapshot through the placeholder step must not interleave with a
            // concurrent rename/delete (which would move items twice).
            CatalogOps.withCatalogLock {
                val (taps, pipelines) = loadAll()
                val members = CatalogOps.members(taps, pipelines, name)
                val placeholders = CatalogOps.Placeholder.of(taps, name)
                if (members.isEmpty && placeholders.isEmpty)
                    return notFound(name)

                placeholderScopeDenial(request, "delete", name, members, placeholders).foreach(r => return r)
                val actor = VersionActor.resolve(request)
                val denied = mutable.ArrayBuffer.empty[String]
                val (tapResult, pipelineResult) =
                    if (m == "detach") {
                        val note = "catalog detached from " + name + " by " + actor
                        val t = CatalogOps.execute(members.taps.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readTap(n, name)
                                CapabilityCheck.assertScope(
                                    request,
                                    "tap",
                                    "delete",
                                    CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel)
                                )
                                TapConfigIO.writeVersioned(live.copy(catalog = null), note, actor)
                            }
                        }
                        val p = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readPipeline(n, name)
                                CapabilityCheck.assertScope(
                                    request,
                                    "pipeline",
                                    "delete",
                                    CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel)
                                )
                                PipelineConfigIO.writeVersioned(live.copy(catalog = null), note, actor)
                            }
                        }
                        (t, p)
                    } else {
                        // Taps first so nothing lands in a pipeline mid-delete.
                        // Scope is checked here with owner AND catalog context (as
                        // detach does), so the helpers skip their owner-only check.
                        val tapController = new TapAPIController
                        val pipelineController = new PipelineAPIController
                        val t = CatalogOps.execute(members.taps.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readTap(n, name)
                                CapabilityCheck.assertScope(
                                    request,
                                    "tap",
                                    "delete",
                                    CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel)
                                )
                                tapController.deleteTapInternal(n, request, checkScope = false)
                            }
                        }
                        // A surviving tap must not be left pointing at a deleted
                        // pipeline: if any tap delete failed, keep the pipelines.
                        val p =
                            if (t.failed.nonEmpty)
                                CatalogOps.skipped(members.pipelines.map(_.name), "skipped: tap deletions in this catalog failed")
                            else
                                // deletePipelineInternal's advice (e.g. a Unity Catalog
                                // table Datris could not drop) becomes a warning.
                                CatalogOps.executeCollecting(members.pipelines.map(_.name)) { n =>
                                    trackDenied(denied, n) {
                                        val live = readPipeline(n, name)
                                        CapabilityCheck.assertScope(
                                            request,
                                            "pipeline",
                                            "delete",
                                            CatalogOps.scopeContext(live.catalog, live.createdByKeyLabel)
                                        )
                                        pipelineController.deletePipelineInternal(live, request, checkScope = false)
                                    }
                                }
                        (t, p)
                    }

                val failed = Seq.newBuilder[(String, String)]
                failed ++= tapResult.failed
                failed ++= pipelineResult.failed
                val okNames = tapResult.ok ++ pipelineResult.ok
                if (CatalogOps.placeholderAction(members.isEmpty, denied.toSeq, okNames.nonEmpty) != CatalogOps.PlaceholderAction.Skip)
                    placeholders.foreach(ph => removePlaceholder(ph.name, name, request).foreach(failed += _))

                val failures = failed.result()
                val out = new JsonObject
                out.add(if (m == "detach") "detached" else "deleted", toArray(okNames))
                out.add("failed", failedArray(failures))
                if (m == "cascade") out.add("warnings", warningsArray(pipelineResult.warnings))
                respond(out, failures)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @GetMapping(path = Array("/catalog/{name}/export"), produces = Array("application/zip", MediaType.APPLICATION_JSON_VALUE))
    def exportCatalog(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[Array[Byte]] = {
        try {
            logger.info("API endpoint GET /catalog/" + name + "/export called")
            APIKeyValidator.validate(apiKey)

            if (CatalogOps.isReserved(name))
                return jsonBytes(
                    badRequest("Catalog '" + Option(name).getOrElse("") + "' cannot be exported: Uncataloged and blank names are reserved")
                )

            val (manifest, entries) = CatalogOps.withCatalogLock {
                val (taps, pipelines) = loadAll()
                val members = CatalogOps.members(taps, pipelines, name)
                if (members.isEmpty && CatalogOps.Placeholder.of(taps, name).isEmpty)
                    return jsonBytes(notFound(name))
                exportSnapshot(name, members, request)
            }
            val bytes = CatalogArchive.pack(entries)
            logger.info(
                "Catalog '" + name + "' exported: " + manifest.taps.size + " taps, " + manifest.pipelines.size + " pipelines, " +
                    manifest.warnings.size + " warnings, " + bytes.length + " bytes"
            )
            val stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(new java.util.Date())
            ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + stamp + ".zip\"")
                .body[Array[Byte]](bytes)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                jsonBytes(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e)))
        }
    }

    /** Enable order of a catalog's taps: `{catalog, levels: [{level, taps:
      * [readiness]}], cycles, warnings}`. Levels are 1-based (1 = taps with no
      * dependencies). Uses each tap's stored `dependsOn` / `inferredDependsOn`
      * (no script reads). Members the caller may not read are omitted and
      * named in `warnings`, as export does. */
    @GetMapping(path = Array("/catalog/{name}/enable-order"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def enableOrder(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /catalog/" + name + "/enable-order called")
            APIKeyValidator.validate(apiKey)

            if (CatalogOps.isReserved(name))
                return badRequest("Catalog '" + Option(name).getOrElse("") + "' has no enable order: Uncataloged and blank names are reserved")
            val (taps, pipelines) = loadAll()
            val members = CatalogOps.members(taps, pipelines, name)
            if (members.isEmpty && CatalogOps.Placeholder.of(taps, name).isEmpty)
                return notFound(name)
            val warnings = mutable.ArrayBuffer.empty[String]
            val readable = members.taps.filter { t =>
                try {
                    CapabilityCheck.assertScope(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel))
                    true
                } catch {
                    case _: CapabilityDeniedException =>
                        warnings += (t.name + ": omitted, this API key may not read this tap")
                        false
                }
            }
            val plan = TapDependencies.order(readable)
            val out = new JsonObject
            out.addProperty("catalog", name)
            val levels = new JsonArray
            plan.levels.zipWithIndex.foreach { case (lvl, i) =>
                val o = new JsonObject
                o.addProperty("level", i + 1)
                val arr = new JsonArray
                lvl.foreach(r => arr.add(readinessJson(r)))
                o.add("taps", arr)
                levels.add(o)
            }
            out.add("levels", levels)
            val cycles = new JsonArray
            plan.cycles.foreach(c => cycles.add(toArray(c)))
            out.add("cycles", cycles)
            out.add("warnings", toArray(warnings.toList))
            new ResponseEntity[String](new Gson().toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    private def readinessJson(r: TapDependencies.Readiness): JsonObject = {
        val o = new JsonObject
        o.addProperty("name", r.name)
        o.addProperty("enabled", r.enabled)
        o.addProperty("cronExpression", r.cronExpression)
        if (r.dependsOn != null) o.add("dependsOn", toArray(r.dependsOn))
        if (r.inferredDependsOn != null) o.add("inferredDependsOn", toArray(r.inferredDependsOn))
        o.addProperty("explicit", r.explicit)
        o.addProperty("hasPassingTest", r.hasPassingTest)
        o.addProperty("lastRealRunLanded", r.lastRealRunLanded)
        o.add("blockedBy", toArray(r.blockedBy))
        o.addProperty("ready", r.ready)
        o
    }

    @PostMapping(
        path = Array("/catalog/{name}/restore"),
        consumes = Array(MediaType.MULTIPART_FORM_DATA_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def restoreCatalog(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        @RequestPart(name = "file", required = false) file: MultipartFile,
        @RequestParam(name = "mode", defaultValue = "new") mode: String,
        @RequestParam(name = "confirm", required = false) confirm: String,
        @RequestParam(name = "restoreState", defaultValue = "true") restoreState: Boolean,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /catalog/" + name + "/restore called with mode: " + mode + ", restoreState: " + restoreState)
            APIKeyValidator.validate(apiKey)

            if (CatalogOps.isReserved(name))
                return badRequest("Cannot restore into '" + Option(name).getOrElse("") + "': Uncataloged and blank names are reserved")
            if (!CatalogOps.isValidLabel(name))
                return badRequest("Invalid catalog name '" + name + "': use letters, digits, '_' and '-' only")
            val m = Option(mode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("new")
            if (m != "new" && m != "overwrite")
                return badRequest("Invalid mode '" + mode + "': use 'new' or 'overwrite'")
            if (m == "overwrite" && confirm != name)
                return badRequest(
                    "Overwrite replaces the taps and pipelines of '" + name + "' that are in the archive; pass confirm=<catalog name> to proceed"
                )
            if (file == null || file.isEmpty)
                return badRequest("Request must include the archive as multipart field 'file'")

            val entries = CatalogArchive.unpack(file.getBytes) match {
                case Left(err) => return badRequest(err)
                case Right(e) => e
            }
            val manifest = CatalogArchive.parseManifest(entries(CatalogArchive.ManifestPath)) match {
                case Left(err) => return badRequest(err)
                case Right(mf) => mf
            }
            val contents = CatalogArchive.readContents(manifest, entries) match {
                case Left(err) => return badRequest(err)
                case Right(c) => c
            }

            CatalogOps.withCatalogLock {
                val (taps, pipelines) = loadAll()

                // Never a merge: nothing is written unless every check passes.
                if (m == "new" && CatalogOps.catalogExists(taps, pipelines, name))
                    return conflict(
                        "Catalog '" + name + "' already exists. Restore under a new name, or pass mode=overwrite&confirm=" + name +
                            " to replace its members.",
                        Seq("catalog '" + name + "' already exists")
                    )
                val nameClashes = CatalogArchive.clashes(manifest, taps, pipelines, name, m).map(_.toString)

                // Tap scripts go to the tenant default store; a repository
                // target is checked at branch head before anything is written.
                val store = TapCodeStore.forStorage(null)
                val repoCfg = if (store eq GithubCodeStore) CodeRepoConfigIO.readEnabled() else None
                val pythonTaps = contents.taps.filterNot(_.isHttp).map(_.name).toSet
                val scripts = contents.scripts.filter { case (n, _) => pythonTaps.contains(n) }
                val targetTapMembers = CatalogOps.members(taps, pipelines, name).taps.map(_.name).toSet
                val repoPlan = repoCfg match {
                    case Some(cfg) =>
                        CatalogArchive.planRepoScripts(scripts, cfg, m, targetTapMembers, (c, p, r) => GithubClient.getFile(c, p, r))
                    case None => CatalogArchive.RepoScriptPlan(Nil, Set.empty, scripts.keySet)
                }
                val allClashes = nameClashes ++ repoPlan.clashes
                if (allClashes.nonEmpty)
                    return conflict(
                        "The archive cannot be restored as '" + name + "': these names already exist on this install. " +
                            "A restore never merges or replaces items outside the target catalog.",
                        allClashes
                    )

                val preexistingTaps = taps.map(_.name).toSet.intersect(manifest.taps.toSet)
                val actor = VersionActor.resolve(request)
                val note = "restored from catalog archive " + Option(manifest.catalog).getOrElse("?") + " by " + actor
                val caller = ResolvedKeyAccess.keyLabel(request).orNull
                val warnings = mutable.ArrayBuffer.empty[String]
                val skipped = mutable.ArrayBuffer.empty[String]

                // 0. Scope-check every member before anything is written: a
                //    refused member lands in `failed` and nothing of it (not
                //    even its validation schema) is written.
                def denial(resource: String, live: Option[(String, String)]): Option[String] =
                    CatalogArchive.restoreScopeChecks(name, caller, live).view.flatMap { case (action, ctx) =>
                        try { CapabilityCheck.assertScope(request, resource, action, ctx); None }
                        catch { case e: CapabilityDeniedException => Some(Option(e.getMessage).getOrElse("capability denied")) }
                    }.headOption
                val pipelineDenied = contents.pipelines.flatMap { p =>
                    denial("pipeline", pipelines.find(_.name == p.name).map(l => (l.catalog, l.createdByKeyLabel))).map(p.name -> _)
                }
                val tapDenied = contents.taps.flatMap { t =>
                    denial("tap", taps.find(_.name == t.name).map(l => (l.catalog, l.createdByKeyLabel))).map(t.name -> _)
                }
                val allowedPipelines = contents.pipelines.filterNot(p => pipelineDenied.exists(_._1 == p.name))
                val allowedTaps = contents.taps.filterNot(t => tapDenied.exists(_._1 == t.name))

                // 1. Validation-schema objects (an existing different object is kept).
                writeValidationSchemas(contents.copy(pipelines = allowedPipelines), warnings, skipped)

                // 2. Pipelines, through the same save path as POST /pipeline.
                val pipelineController = new PipelineAPIController
                val pipelineResult = CatalogOps.execute(allowedPipelines.map(_.name)) { n =>
                    val archived = contents.pipelines.find(_.name == n).get
                    val toSave = CatalogArchive.stripPipelineForExport(archived).copy(catalog = name)
                    pipelineController.savePipelineInternal(toSave, note, request) match {
                        case Left(refused) => throw new DatrisException(errorText(refused.getBody))
                        case Right(_) =>
                    }
                }
                contents.pipelines.filter(p => pipelineResult.ok.contains(p.name)).foreach { p =>
                    warnings ++= CatalogArchive.installWarnings(p)
                    warnings ++= manifest.warnings.filter(w => w.startsWith(p.name + ": ") && w.contains(CatalogArchive.RedactionMarker))
                }

                // 3. Taps: script first (tenant default store), then the config,
                //    disabled, with run status cleared.
                val tapController = new TapAPIController
                val tapResult = CatalogOps.execute(allowedTaps.map(_.name)) { n =>
                    val archived = contents.taps.find(_.name == n).get
                    val base = CatalogArchive.stripTapForExport(archived).copy(enabled = false, catalog = name)
                    val toSave =
                        if (archived.isHttp) base.copy(lastTestRunScriptId = null)
                        else
                            scripts.get(n) match {
                                case None =>
                                    warnings += (n + ": the archive has no script for this tap; it was restored without one")
                                    base.copy(lastTestRunScriptId = null)
                                case Some(script) =>
                                    val live = TapConfigIO.read(DatrisEnvironment.current.tapTableName, n)
                                    val stored = storeRestoredScript(store, repoCfg, repoPlan, n, script, live, actor)
                                    base.copy(
                                        scriptStorage = stored.storage,
                                        scriptPath = stored.scriptPath,
                                        scriptRepoPath = stored.scriptRepoPath,
                                        scriptCommitSha = stored.scriptCommitSha,
                                        // Same bytes on built-in storage give the same id;
                                        // a repository pin is a new commit.
                                        lastTestRunScriptId = if (stored.storage == MinioCodeStore.storage) base.lastTestRunScriptId else null
                                    )
                            }
                    tapController.saveTapInternal(toSave, note, request, restored = true) match {
                        case Left(refused) => throw new DatrisException(errorText(refused.getBody))
                        case Right(_) =>
                    }
                }

                // 3b. Dependencies: each tap was saved before all of its
                //     producers existed, so re-derive the inference now that
                //     every restored tap is in (no new version, like status
                //     writes), then count the taps that read another
                //     restored tap's tables.
                val dependentTaps =
                    try {
                        val (tapsNow, pipelinesNow) = loadAll()
                        val restoredNames = tapResult.ok.toSet
                        tapsNow.filter(t => restoredNames.contains(t.name)).count { t =>
                            val restamped = scripts.get(t.name) match {
                                case Some(script) if !t.isHttp =>
                                    val s = TapAPIController.stampInferredDependsOn(t, Some(script), pipelinesNow, tapsNow)
                                    if (s.inferredDependsOn != t.inferredDependsOn) TapConfigIO.write(s)
                                    s
                                case _ => t
                            }
                            TapDependencies.effective(restamped).exists(d => d != t.name && restoredNames.contains(d))
                        }
                    } catch {
                        case e: Exception =>
                            logger.warn("Catalog restore '" + name + "': dependency inference failed: " + e.getMessage)
                            0
                    }

                // 4. Incremental state, only for restored taps that are new here.
                val states = CatalogArchive
                    .statesToRestore(entries, restoreState, preexistingTaps)
                    .filter { case (n, _) => tapResult.ok.contains(n) }
                val now = {
                    val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                    sdf.format(new java.util.Date())
                }
                val stateFailures = mutable.ArrayBuffer.empty[(String, String)]
                states.toSeq.sortBy(_._1).foreach { case (n, json) =>
                    try TapStateIO.write(TapState(n, json, now, "restore"))
                    catch {
                        case e: Exception =>
                            stateFailures += (n -> ("tap restored but its incremental state was not: " + Option(e.getMessage).getOrElse(
                                e.getClass.getSimpleName
                            )))
                    }
                }

                // 5. An empty archive still creates the (empty) catalog.
                val failed = Seq.newBuilder[(String, String)]
                failed ++= pipelineDenied
                failed ++= pipelineResult.failed
                failed ++= tapDenied
                failed ++= tapResult.failed
                failed ++= stateFailures
                if (manifest.taps.isEmpty && manifest.pipelines.isEmpty && CatalogOps.Placeholder.of(taps, name).isEmpty) {
                    val phName = CatalogOps.Placeholder.freshName(name, taps.map(_.name).toSet)
                    try
                        TapConfigIO.writeVersioned(
                            TapConfig(
                                name = phName,
                                description = "Catalog placeholder",
                                targetPipeline = null,
                                catalog = name,
                                enabled = false,
                                createdByKeyLabel = ResolvedKeyAccess.keyLabel(request).orNull
                            ),
                            "catalog placeholder created by restore of " + name + " by " + actor,
                            actor
                        )
                    catch {
                        case e: Exception => failed += (phName -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                    }
                }

                val failures = failed.result()
                val out = new JsonObject
                val restored = new JsonObject
                restored.add("taps", toArray(tapResult.ok))
                restored.add("pipelines", toArray(pipelineResult.ok))
                out.add("restored", restored)
                out.add("skipped", toArray(skipped.toList))
                out.add("failed", failedArray(failures))
                // Probed from the archived items, never the caller-supplied
                // manifest list, and only reported where the caller may read
                // secrets, so a restore is no secret-existence oracle.
                out.add("missingSecrets", missingSecretsArray(CatalogArchive.requiredSecrets(contents.taps, contents.pipelines), warnings, request))
                out.add("warnings", toArray(warnings.distinct.toList))
                out.addProperty("tapsDisabled", true)
                out.addProperty("stateRestored", states.nonEmpty && stateFailures.isEmpty)
                out.addProperty("dependentTaps", dependentTaps)
                logger.info(
                    "Catalog archive restored as '" + name + "' (mode " + m + "): " + tapResult.ok.size + " taps, " +
                        pipelineResult.ok.size + " pipelines, " + failures.size + " failed"
                )
                respond(out, failures)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    // ------------------------------------------------------------- export/restore helpers

    /** Read everything one catalog's archive holds. Members the caller may
      * not read are omitted and named in the manifest warnings. */
    private def exportSnapshot(
        name: String,
        members: CatalogOps.Members,
        request: HttpServletRequest
    ): (CatalogArchive.Manifest, Seq[CatalogArchive.Entry]) = {
        val warnings = mutable.ArrayBuffer.empty[String]
        def readable(resource: String, n: String, catalog: String, owner: String): Boolean =
            try {
                CapabilityCheck.assertScope(request, resource, "read", CatalogOps.scopeContext(catalog, owner))
                true
            } catch {
                case _: CapabilityDeniedException =>
                    warnings += (n + ": omitted, this API key may not read this " + resource)
                    false
            }
        val taps = members.taps.filter(t => readable("tap", t.name, t.catalog, t.createdByKeyLabel)).sortBy(_.name)
        val pipelines = members.pipelines.filter(p => readable("pipeline", p.name, p.catalog, p.createdByKeyLabel)).sortBy(_.name)

        val scripts = taps.filterNot(_.isHttp).flatMap { t =>
            val text =
                try TapCodeStore.forTap(t).readScript(t)
                catch {
                    case e: Exception =>
                        logger.warn("Catalog export: script of tap '" + t.name + "' unreadable: " + e.getMessage)
                        None
                }
            if (text.isEmpty) warnings += (t.name + ": script could not be read; the tap was exported without it")
            text.map(t.name -> _)
        }.toMap

        val states = taps.flatMap { t =>
            val json =
                try Option(TapStateIO.readStateJson(t.name))
                catch { case e: Exception => logger.warn("Catalog export: state of tap '" + t.name + "' unreadable: " + e.getMessage); None }
            json.filter(_.nonEmpty).map { s =>
                warnings += (t.name + ": incremental state included; pass restoreState=false for a clone")
                t.name -> s
            }
        }.toMap

        val bucket = DatrisEnvironment.current.environment + "-config"
        val schemas = pipelines.flatMap { p =>
            Option(p.dataQuality).flatMap(dq => Option(dq.validationSchema)).filter(_.nonEmpty).flatMap { schema =>
                // Resolved exactly as DataQuality does at run time.
                val url = if (schema.startsWith("s3://")) schema else "s3://" + bucket + "/validation-schema/" + schema
                val file = if (schema.startsWith("s3://")) schema.substring(schema.lastIndexOf('/') + 1) else schema
                readObject(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url)) match {
                    case Some(bytes) if file.nonEmpty => Some(p.name -> Map(file -> bytes))
                    case _ =>
                        warnings += (p.name + ": validation schema " + url + " could not be read; the pipeline was exported without it")
                        None
                }
            }
        }.toMap

        val codegen = pipelines.flatMap { p =>
            val kinds = PipelineScripts.Kinds.flatMap { kind =>
                try PipelineScripts.readText(p.name, kind).map(kind -> _)
                catch { case _: Exception => None }
            }.toMap
            if (kinds.isEmpty) None else Some(p.name -> kinds)
        }.toMap

        pipelines.foreach(p => warnings ++= CatalogArchive.installWarnings(p))
        pipelines.foreach(p => warnings ++= CatalogArchive.redactionWarnings(p))

        // The stored `_type` only for a secret the caller may read; otherwise the hint stays.
        val required = CatalogArchive.requiredSecrets(taps, pipelines).map { r =>
            val storedType = checkSecret(r.name, request)._2.flatMap(m => Option(m.get("_type")))
            r.copy(kind = storedType.getOrElse(r.kind))
        }

        val exportedAt = {
            val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            sdf.format(new java.util.Date())
        }
        val manifest = CatalogArchive.Manifest(
            formatVersion = CatalogArchive.FormatVersion,
            catalog = name,
            exportedAt = exportedAt,
            datrisVersion = BuildInfo.version,
            taps = taps.map(_.name),
            pipelines = pipelines.map(_.name),
            requiredSecrets = required,
            warnings = warnings.toList
        )
        val entries = CatalogArchive.archiveEntries(
            manifest,
            taps.map(CatalogArchive.stripTapForExport),
            pipelines.map(CatalogArchive.stripPipelineForExport),
            scripts,
            states,
            schemas,
            codegen
        )
        (manifest, entries)
    }

    private def readObject(bucket: String, key: String): Option[Array[Byte]] =
        try {
            val in = ObjectStoreUtil.getInputStream(bucket, key)
            try Option(in.readAllBytes())
            finally in.close()
        } catch {
            case e: Exception =>
                logger.debug("Object " + bucket + "/" + key + " unreadable: " + e.getMessage)
                None
        }

    /** Write archived validation schemas into `<env>-config/validation-schema/`.
      * An existing object is never replaced: identical bytes are skipped,
      * different bytes are kept with a warning. Absolute `s3://` schemas are
      * referenced in place, so they are not written. */
    private def writeValidationSchemas(
        contents: CatalogArchive.Contents,
        warnings: mutable.ArrayBuffer[String],
        skipped: mutable.ArrayBuffer[String]
    ): Unit = {
        val bucket = DatrisEnvironment.current.environment + "-config"
        contents.pipelines.foreach { p =>
            val schema = Option(p.dataQuality).flatMap(dq => Option(dq.validationSchema)).filter(_.nonEmpty)
            contents.validationSchemas.getOrElse(p.name, Map.empty).toSeq.sortBy(_._1).foreach { case (file, bytes) =>
                if (schema.exists(_.startsWith("s3://")))
                    warnings += (p.name + ": validation schema " + schema.get + " is referenced in place; the archived copy was not written")
                else if (!schema.contains(file))
                    warnings += (p.name + ": archived validation schema " + file + " is not referenced by the pipeline; it was not written")
                else {
                    val key = "validation-schema/" + file
                    readObject(bucket, key) match {
                        case Some(existing) if java.util.Arrays.equals(existing, bytes) =>
                            skipped += key
                        case Some(_) =>
                            skipped += key
                            warnings += (p.name + ": " + key + " already exists with different content; the existing schema was kept")
                        case None =>
                            try ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new java.io.ByteArrayInputStream(bytes), bytes.length.toLong)
                            catch {
                                case e: Exception =>
                                    warnings += (p.name + ": validation schema " + key + " could not be written: " + e.getMessage)
                            }
                    }
                }
            }
        }
    }

    /** Store one restored tap script through the tenant default store. On a
      * code repository a file already identical at branch head is pinned
      * without a new commit. */
    private def storeRestoredScript(
        store: TapCodeStore,
        repoCfg: Option[ai.datris.model.CodeRepoConfig],
        plan: CatalogArchive.RepoScriptPlan,
        tap: String,
        script: String,
        live: TapConfig,
        actor: String
    ): StoredScript =
        repoCfg match {
            case Some(cfg) if plan.reuse.contains(tap) =>
                val path = GithubCodeStore.scriptRepoPath(tap, cfg)
                val head = GithubClient.branchHeadSha(cfg)
                GithubClient.getFile(cfg, path, head) match {
                    case Some(_) => StoredScript(GithubCodeStore.storage, scriptRepoPath = path, scriptCommitSha = head)
                    case None => throw new DatrisException("script file '" + path + "' disappeared from the code repository during restore")
                }
            case _ =>
                // A live tap on another backend is not a prior for this store.
                val prior = if (live != null && TapCodeStore.forTap(live).storage == store.storage) live else null
                store.storeScript(tap, script, prior, actor)
        }

    /** Check one secret name against this install, gated by the caller's
      * secret read scope (see CatalogArchive.secretCheck). Also returns the
      * stored fields when the caller may read them. */
    private def checkSecret(name: String, request: HttpServletRequest): (CatalogArchive.SecretCheck, Option[java.util.Map[String, String]]) = {
        val lookup = SecretsUtil.tryGetSecretMap(DatrisEnvironment.current.environment + "/" + name).toOption
        val check = CatalogArchive.secretCheck(
            lookup,
            f => UnityCatalogAPIController.canRead(request, f),
            CapabilityCheck.grants(request, "secret", "read", Map.empty)
        )
        (check, if (check == CatalogArchive.SecretCheck.Present) lookup.flatten else None)
    }

    private def missingSecretsArray(
        required: Seq[CatalogArchive.RequiredSecret],
        warnings: mutable.ArrayBuffer[String],
        request: HttpServletRequest
    ): JsonArray = {
        val arr = new JsonArray
        required.foreach { r =>
            checkSecret(r.name, request)._1 match {
                case CatalogArchive.SecretCheck.Present =>
                case CatalogArchive.SecretCheck.Missing =>
                    val o = new JsonObject
                    o.addProperty("name", r.name)
                    o.add("usedBy", toArray(r.usedBy))
                    arr.add(o)
                case CatalogArchive.SecretCheck.Unchecked =>
                    warnings += CatalogArchive.secretNotCheckedWarning(r.name)
            }
        }
        arr
    }

    /** The `error` text of a refusal body, or the body itself. */
    private def errorText(body: String): String =
        try {
            val o = JsonParser.parseString(body).getAsJsonObject
            if (o.has("error") && !o.get("error").isJsonNull) o.get("error").getAsString else body
        } catch { case _: Exception => Option(body).getOrElse("refused") }

    private def conflict(message: String, clashes: Seq[String]): ResponseEntity[String] = {
        val o = new JsonObject
        o.addProperty("error", message)
        o.add("clashes", toArray(clashes))
        ResponseEntity.status(HttpStatus.CONFLICT).body[String](o.toString)
    }

    /** A JSON error response on the export route (which otherwise returns zip bytes). */
    private def jsonBytes(r: ResponseEntity[String]): ResponseEntity[Array[Byte]] =
        ResponseEntity
            .status(r.getStatusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body[Array[Byte]](Option(r.getBody).getOrElse("").getBytes(StandardCharsets.UTF_8))

    // ------------------------------------------------------------------ helpers

    private def readNewName(body: String): String =
        if (body == null || body.trim.isEmpty) null
        else
            try {
                val obj = JsonParser.parseString(body).getAsJsonObject
                if (obj.has("newName") && !obj.get("newName").isJsonNull) obj.get("newName").getAsString else null
            } catch { case _: Exception => null }

    /** Every tap and pipeline, skipping any document that fails to parse so
      * one bad config cannot block a catalog operation on the others. */
    private def loadAll(): (Seq[TapConfig], Seq[PipelineConfig]) = {
        val env = DatrisEnvironment.current
        val taps = NoSQLDbUtil.getItemsKeysByKeyName(env.tapTableName, "name").flatMap { n =>
            try Option(TapConfigIO.read(env.tapTableName, n))
            catch { case e: Exception => logger.warn("Skipping unreadable tap '" + n + "': " + e.getMessage); None }
        }
        val pipelines = NoSQLDbUtil.getItemsKeysByKeyName(env.pipelineTableName, "name").flatMap { n =>
            try Option(PipelineConfigIO.read(env.pipelineTableName, n))
            catch { case e: Exception => logger.warn("Skipping unreadable pipeline '" + n + "': " + e.getMessage); None }
        }
        (taps, pipelines)
    }

    /** Re-read right before writing so a concurrent status update is not
      * lost, and refuse a member that a concurrent rename or `set_catalog`
      * has already moved out of `catalog`. */
    private def readTap(n: String, catalog: String): TapConfig = {
        val t = TapConfigIO.read(DatrisEnvironment.current.tapTableName, n)
        if (t == null) throw new DatrisException("Tap " + n + " no longer exists")
        CatalogOps.requireInCatalog(n, t.catalog, catalog)
        t
    }

    private def readPipeline(n: String, catalog: String): PipelineConfig = {
        val p = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
        if (p == null) throw new DatrisException("Pipeline " + n + " no longer exists")
        CatalogOps.requireInCatalog(n, p.catalog, catalog)
        p
    }

    /** Record a member refused by the caller's capability scope, then rethrow
      * so `CatalogOps.execute` lists it under `failed`. */
    private def trackDenied[T](denied: mutable.ArrayBuffer[String], n: String)(f: => T): T =
        try f
        catch {
            case e: CapabilityDeniedException =>
                denied += n
                throw e
        }

    /** For a placeholder-only catalog the placeholder is the only thing the
      * operation touches, so it must pass the caller's tap scope (owner and
      * catalog) before it is removed or replaced. Returns a 403 on denial. */
    private def placeholderScopeDenial(
        request: HttpServletRequest,
        action: String,
        catalog: String,
        members: CatalogOps.Members,
        placeholders: Seq[TapConfig]
    ): Option[ResponseEntity[String]] =
        CatalogOps.placeholderAction(members.isEmpty, Nil) match {
            case CatalogOps.PlaceholderAction.CheckPlaceholderScope =>
                placeholders.view.flatMap { ph =>
                    try {
                        CapabilityCheck.assertScope(request, "tap", action, CatalogOps.scopeContext(catalog, ph.createdByKeyLabel))
                        None
                    } catch {
                        case e: CapabilityDeniedException =>
                            logger.info("capability scope denial: " + e.getMessage)
                            Some(ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](CatalogOps.capabilityDeniedBody(e.getMessage)))
                    }
                }.headOption
            case _ => None
        }

    /** Delete placeholder tap `placeholder` of `catalog`, unless a concurrent
      * request already removed it or re-pointed it at another catalog. */
    private def removePlaceholder(placeholder: String, catalog: String, request: HttpServletRequest): Option[(String, String)] = {
        try {
            val live = TapConfigIO.read(DatrisEnvironment.current.tapTableName, placeholder)
            if (live != null && CatalogOps.Placeholder.belongsTo(live, catalog))
                new TapAPIController().deleteTapInternal(placeholder, request, checkScope = false)
            None
        } catch {
            case e: Exception =>
                logger.warn("Catalog placeholder cleanup failed for '" + placeholder + "': " + e.getMessage)
                Some(placeholder -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }
    }

    private def affectedKeys(old: String): Seq[String] =
        try {
            val metadata = SecretsUtil
                .getSecretMap(DatrisEnvironment.current.environment + "/api-key-metadata")
                .map(_.asScala.toMap)
                .getOrElse(Map.empty[String, String])
            CatalogOps.affectedKeys(metadata, old)
        } catch {
            case e: Exception =>
                logger.warn("Could not read API key metadata for affectedKeys: " + e.getMessage)
                Nil
        }

    private def toArray(values: Seq[String]): JsonArray = {
        val arr = new JsonArray
        values.foreach(v => arr.add(v))
        arr
    }

    private def failedArray(failures: Seq[(String, String)]): JsonArray = {
        val arr = new JsonArray
        failures.foreach { case (n, err) =>
            val o = new JsonObject
            o.addProperty("name", n)
            o.addProperty("error", err)
            arr.add(o)
        }
        arr
    }

    private def warningsArray(warnings: Seq[(String, String)]): JsonArray = {
        val arr = new JsonArray
        warnings.foreach { case (n, msg) =>
            val o = new JsonObject
            o.addProperty("pipeline", n)
            o.addProperty("message", msg)
            arr.add(o)
        }
        arr
    }

    private def respond(out: JsonObject, failures: Seq[(String, String)]): ResponseEntity[String] =
        ResponseEntity
            .status(if (failures.isEmpty) HttpStatus.OK else HttpStatus.MULTI_STATUS)
            .body[String](new Gson().toJson(out))

    private def badRequest(message: String): ResponseEntity[String] = {
        val o = new JsonObject
        o.addProperty("error", message)
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](o.toString)
    }

    private def notFound(name: String): ResponseEntity[String] = {
        val o = new JsonObject
        o.addProperty("error", "Catalog '" + name + "' not found")
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](o.toString)
    }
}
