package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, VersionActor}
import ai.datris.build.sbt.BuildInfo
import ai.datris.model.{CodeGenScript, CodeRepoConfig, DatrisEnvironment, DatrisException, PipelineConfig, PipelineStatusRollup, TapConfig}
import ai.datris.util.{
    APIKeyValidator,
    CatalogArchive,
    CatalogEnableJob,
    CatalogOps,
    CodeRepoConfigIO,
    EnvironmentLabel,
    GithubClient,
    GithubCodeStore,
    MinioCodeStore,
    NoSQLDbUtil,
    ObjectStoreUtil,
    PipelineConfigIO,
    PipelineScripts,
    PipelineStatusUtil,
    SecretsUtil,
    StoredScript,
    TapCodeStore,
    TapConfigIO,
    TapDependencies,
    TapOwnerScopeReport,
    TapRunner,
    TapScriptOwnership,
    TapScriptResult,
    TapState,
    TapStateIO,
    TapTestPreviewCache,
    UserStore
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
  *     scripts with their records; secret names only, never values). See CatalogArchive.
  *   - `GET /api/v1/catalog/{name}/enable-order` lists the catalog's taps in
  *     dependency levels (producers first) with per-tap readiness. Read only.
  *   - `POST /api/v1/catalog/{name}/restore` (multipart `file`) restores an
  *     archive as catalog `name`; refused with 409 when the catalog or any
  *     archived name already exists (`mode=overwrite&confirm=<name>` may
  *     replace members of `name` only). Restored taps are disabled. Archived
  *     CodeGen scripts become the live scripts unless `regenerateScripts=true`.
  *
  * Capability gate: `CapabilityRoutes` maps PUT to pipeline:update and DELETE
  * to pipeline:delete; each member is additionally scope-checked (owner and
  * catalog) with its own resource type. Rename and detach move members, so
  * they are all or nothing on scope (`CatalogAPIController.moveRefusal`): a
  * member the caller may not move, or, for an API key, may not write where
  * it lands, refuses the whole operation with 403 before anything is
  * written. Cascade delete keeps the per-member result: refused members land
  * in `failed`, and when any member is refused the `__catalog__`
  * placeholders are left untouched. A placeholder-only catalog checks the
  * placeholder itself (403 on denial). Cascade skips its pipeline phase if a
  * tap delete failed. */
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
            // A restricted user renames only a catalog it owns (else hidden).
            RestrictedCatalogRules.catalogRefusal(request, name, "delete").foreach(r => return r)
            // Registry check only (no Mongo read): a running ordered enable
            // reads and writes this catalog's taps by name.
            if (CatalogEnableJob.activeFor(name))
                return enableJobConflict(name, "renamed")

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
                // All or nothing: every member (or, for a placeholder-only
                // catalog, every placeholder) must be the caller's to move,
                // and for an API key in scope where it lands, before anything
                // is written.
                CatalogAPIController.moveRefusal(request, "update", name, newName, members, oldPlaceholders).foreach(r => return r)
                val denied = mutable.ArrayBuffer.empty[String]
                val tapResult = CatalogOps.execute(members.taps.map(_.name)) { n =>
                    trackDenied(denied, n) {
                        val live = readTap(n, name)
                        CatalogAPIController.assertMemberMove(request, "tap", "update", live.catalog, newName, live.createdByKeyLabel)
                        TapConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)
                    }
                }
                val pipelineResult = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                    trackDenied(denied, n) {
                        val live = readPipeline(n, name)
                        CatalogAPIController.assertMemberMove(request, "pipeline", "update", live.catalog, newName, live.createdByKeyLabel)
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
                // Users' catalog grants follow the catalog, in the same locked
                // operation. A partial rename (some members left behind) keeps
                // the old grant too, so nobody loses access to the leftovers.
                val updatedUsers =
                    if (okNames.isEmpty && placeholder != "created") Nil
                    else
                        try {
                            if (failures.isEmpty) UserStore.renameCatalogInGrants(name, newName)
                            else UserStore.copyCatalogInGrants(name, newName)
                        } catch {
                            case e: Exception =>
                                logger.warn("Could not move user catalog grants " + name + " -> " + newName + ": " + e.getMessage)
                                Nil
                        }
                val out = new JsonObject
                out.add("renamed", toArray(okNames))
                out.add("failed", failedArray(failures))
                out.add("affectedKeys", toArray(affectedKeys(name)))
                out.add("updatedUsers", toArray(updatedUsers))
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
            // Restricted users never delete catalogs (detach would land the
            // members in Uncataloged, which they cannot see).
            RestrictedCatalogRules.catalogRefusal(request, name, "read").foreach(r => return r)
            if (CapabilityCheck.isRestrictedSession(request))
                return PipelineAPIController.capabilityDenied(new DatrisException("capability denied: only an admin can delete a catalog"))
            if (CatalogEnableJob.activeFor(name))
                return enableJobConflict(name, "deleted")

            // Serialized with every other catalog operation: the member
            // snapshot through the placeholder step must not interleave with a
            // concurrent rename/delete (which would move items twice).
            CatalogOps.withCatalogLock {
                val (taps, pipelines) = loadAll()
                val members = CatalogOps.members(taps, pipelines, name)
                val placeholders = CatalogOps.Placeholder.of(taps, name)
                if (members.isEmpty && placeholders.isEmpty)
                    return notFound(name)

                // Detach moves every member to Uncataloged: all or nothing, as
                // rename. Cascade keeps its per-member result.
                if (m == "detach") CatalogAPIController.moveRefusal(request, "delete", name, null, members, placeholders).foreach(r => return r)
                else placeholderScopeDenial(request, "delete", name, members, placeholders).foreach(r => return r)
                val actor = VersionActor.resolve(request)
                val denied = mutable.ArrayBuffer.empty[String]
                val (tapResult, pipelineResult) =
                    if (m == "detach") {
                        val note = "catalog detached from " + name + " by " + actor
                        val t = CatalogOps.execute(members.taps.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readTap(n, name)
                                CatalogAPIController.assertMemberMove(request, "tap", "delete", live.catalog, null, live.createdByKeyLabel)
                                TapConfigIO.writeVersioned(live.copy(catalog = null), note, actor)
                            }
                        }
                        val p = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readPipeline(n, name)
                                CatalogAPIController.assertMemberMove(request, "pipeline", "delete", live.catalog, null, live.createdByKeyLabel)
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
                        // deleteTapInternal's warnings (a hand-edited repository
                        // script kept, or a script file this tap does not
                        // alone own) become {tap, message} warnings. One tap
                        // listing, read at most once (a failed read is kept
                        // too, so each delete keeps its file), serves every tap
                        // delete; taps deleted so far no longer count as using a file.
                        val (remaining, markDeleted) = CatalogAPIController.cascadeListing(() => TapConfigIO.listing())
                        val t = CatalogOps.executeCollecting(members.taps.map(_.name)) { n =>
                            trackDenied(denied, n) {
                                val live = readTap(n, name)
                                CatalogAPIController.assertMemberWrite(request, "tap", "delete", live.catalog, live.createdByKeyLabel)
                                val out = tapController.deleteTapInternal(n, request, checkScope = false, allTaps = remaining).getOrElse(Nil)
                                markDeleted(n)
                                out
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
                                        CatalogAPIController.assertMemberWrite(request, "pipeline", "delete", live.catalog, live.createdByKeyLabel)
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
                // Grants on a catalog that no longer exists are dropped; a
                // partial delete keeps them for the members left behind.
                val updatedUsers =
                    if (failures.nonEmpty || denied.nonEmpty) Nil
                    else
                        try UserStore.removeCatalogFromGrants(name)
                        catch {
                            case e: Exception =>
                                logger.warn("Could not remove user catalog grants for " + name + ": " + e.getMessage)
                                Nil
                        }
                val out = new JsonObject
                out.add(if (m == "detach") "detached" else "deleted", toArray(okNames))
                out.add("failed", failedArray(failures))
                out.add("updatedUsers", toArray(updatedUsers))
                if (m == "cascade") out.add("warnings", CatalogAPIController.warningsArray(pipelineResult.warnings, tapResult.warnings))
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
            RestrictedCatalogRules.catalogRefusal(request, name, "read").foreach(r => return jsonBytes(r))

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
            RestrictedCatalogRules.catalogRefusal(request, name, "read").foreach(r => return r)

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
            val plan = TapDependencies.order(withBackfill(readable, taps, pipelines, warnings))
            val out = CatalogAPIController.planJson(plan)
            out.addProperty("catalog", name)
            out.add("warnings", toArray(warnings.toList))
            // Progress of an ordered enable of this catalog ({active:false} when none).
            out.add(
                "job",
                if (CatalogAPIController.enableJobScopeDenial(request, "read", name).isEmpty) CatalogEnableJob.statusBody(name)
                else { val inactive = new JsonObject; inactive.addProperty("active", false); inactive }
            )
            new ResponseEntity[String](new Gson().toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Most unstamped taps one enable-order call analyses. */
    private val BackfillCap = 100

    /** Lazy backfill: taps saved before dependencies existed are analysed
      * once from their stored script (capped per request; an unreadable
      * script is logged and retried on a later call). */
    private def withBackfill(
        members: Seq[TapConfig],
        taps: Seq[TapConfig],
        pipelines: Seq[PipelineConfig],
        warnings: mutable.ArrayBuffer[String]
    ): Seq[TapConfig] = {
        val pending = members.filter(TapDependencies.needsBackfill)
        val analysed = pending.take(BackfillCap).map { t =>
            t.name -> TapAPIController.backfillInferred(t, TapAPIController.readScriptForBackfill(t), taps, pipelines)
        }.toMap
        if (pending.size > BackfillCap)
            warnings += ((pending.size - BackfillCap) + " taps not yet analysed for dependencies; call again to finish")
        members.map(t => analysed.getOrElse(t.name, t))
    }

    /** Start one server-side job that walks the catalog's enable order (see
      * CatalogEnableJob). No body. 202 `{jobId, catalog, plan}`; 409 on a
      * dependency cycle or when a job for the catalog is already running;
      * 403 (nothing started) when the caller may not update AND run every
      * member tap. Progress: `GET .../enable-ordered` or the `job` block of
      * `GET .../enable-order`. */
    @PostMapping(path = Array("/catalog/{name}/enable-ordered"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def startEnableOrdered(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /catalog/" + name + "/enable-ordered called")
            APIKeyValidator.validate(apiKey)
            RestrictedCatalogRules.catalogRefusal(request, name, "run").foreach(r => return r)

            if (CatalogOps.isReserved(name))
                return badRequest("Catalog '" + Option(name).getOrElse("") + "' cannot be enabled in order: Uncataloged and blank names are reserved")
            if (CatalogEnableJob.activeFor(name))
                return enableJobConflict(name, "started again")
            val (taps, pipelines) = loadAll()
            val members = CatalogOps.members(taps, pipelines, name)
            if (members.isEmpty && CatalogOps.Placeholder.of(taps, name).isEmpty)
                return notFound(name)
            if (members.taps.isEmpty)
                return badRequest("Catalog '" + name + "' has no taps to enable")
            val warnings = mutable.ArrayBuffer.empty[String]
            val catalogTaps = withBackfill(members.taps, taps, pipelines, warnings)
            CatalogAPIController.enableOrderedRefusal(request, catalogTaps).foreach(r => return r)
            val plan = TapDependencies.order(catalogTaps)
            val actor = VersionActor.resolve(request)
            CatalogEnableJob.start(name, actor, plan, CatalogAPIController.EnableDeps) match {
                case Left(err) =>
                    val o = new JsonObject
                    o.addProperty("error", err)
                    ResponseEntity.status(HttpStatus.CONFLICT).body[String](o.toString)
                case Right(job) =>
                    val out = new JsonObject
                    out.addProperty("jobId", job.id)
                    out.addProperty("catalog", name)
                    out.add("plan", CatalogAPIController.planJson(plan))
                    if (warnings.nonEmpty) out.add("warnings", toArray(warnings.toList))
                    ResponseEntity.status(HttpStatus.ACCEPTED).body[String](new Gson().toJson(out))
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Status of the catalog's ordered enable: exactly `{active:false}` when
      * there is none; a finished job stays readable for an hour. */
    @GetMapping(path = Array("/catalog/{name}/enable-ordered"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def enableOrderedStatus(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            RestrictedCatalogRules.catalogRefusal(request, name, "read").foreach(r => return r)
            // No job: nothing to read (and no owner to scope against).
            if (CatalogEnableJob.status(name).isEmpty)
                return new ResponseEntity[String](CatalogEnableJob.statusBody(name).toString, HttpStatus.OK)
            CatalogAPIController.enableJobScopeDenial(request, "read", name).foreach(r => return r)
            new ResponseEntity[String](CatalogEnableJob.statusBody(name).toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Cancel the catalog's ordered enable. The in-flight test, run or load
      * wait finishes; taps not yet started are `cancelled`. Needs `tap:run`
      * on the catalog's taps, and for a key limited to a catalog or to what
      * it created `tap:read` too (403 otherwise, job or not); 404 when no job
      * is running. */
    @DeleteMapping(path = Array("/catalog/{name}/enable-ordered"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def cancelEnableOrdered(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable("name") name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /catalog/" + name + "/enable-ordered called")
            APIKeyValidator.validate(apiKey)
            RestrictedCatalogRules.catalogRefusal(request, name, "run").foreach(r => return r)
            CatalogAPIController.enableJobScopeDenial(request, "run", name).foreach(r => return r)
            val o = new JsonObject
            if (CatalogEnableJob.cancel(name)) {
                o.addProperty("cancelled", true)
                CatalogEnableJob.status(name).foreach(j => o.addProperty("jobId", j.id))
                new ResponseEntity[String](o.toString, HttpStatus.OK)
            } else {
                o.addProperty("error", "No enable job is running for catalog '" + name + "'")
                ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](o.toString)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    private def enableJobConflict(name: String, verb: String): ResponseEntity[String] = {
        val o = new JsonObject
        o.addProperty(
            "error",
            "Catalog '" + name + "' cannot be " + verb + ": an enable job in progress is walking its taps. " +
                "Wait for it to finish (or cancel it), then try again."
        )
        CatalogEnableJob.status(name).foreach(j => o.addProperty("jobId", j.id))
        ResponseEntity.status(HttpStatus.CONFLICT).body[String](o.toString)
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
        @RequestParam(name = "regenerateScripts", defaultValue = "false") regenerateScripts: Boolean,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info(
                "API endpoint POST /catalog/" + name + "/restore called with mode: " + mode + ", restoreState: " + restoreState +
                    ", regenerateScripts: " + regenerateScripts
            )
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
            // A restricted user restores only into a catalog it may edit.
            RestrictedCatalogRules.catalogRefusal(request, name, "create").foreach(r => return r)
            if (CatalogEnableJob.activeFor(name))
                return enableJobConflict(name, "restored")

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
                // Settings that cannot be read end the restore with a 500
                // before anything is written, for every caller.
                val repoCfg = if (store eq GithubCodeStore) CodeRepoConfigIO.readEnabled() else None
                val pythonTaps = contents.taps.filterNot(_.isHttp).map(_.name).toSet
                val scripts = contents.scripts.filter { case (n, _) => pythonTaps.contains(n) }
                val targetMembers = CatalogOps.members(taps, pipelines, name)
                val targetTapMembers = targetMembers.taps.map(_.name).toSet
                // AI scripts of a pipeline that is not yet a target member may
                // not land on a different file at branch head either.
                val targetPipelineMembers = targetMembers.pipelines.map(_.name).toSet
                val repoPlan = repoCfg match {
                    case Some(cfg) =>
                        CatalogArchive.planRepoScripts(
                            scripts,
                            cfg,
                            m,
                            targetTapMembers,
                            (c, p, r) => GithubClient.getFile(c, p, r),
                            contents.codegenScripts,
                            targetPipelineMembers
                        )
                    case None => CatalogArchive.RepoScriptPlan(Nil, Set.empty, scripts.keySet, Set.empty, Set.empty)
                }
                val caller = ResolvedKeyAccess.keyLabel(request).orNull
                // The per-member tap scope check runs once, here, so the
                // script checks below judge only taps this caller would
                // actually write; a refused tap lands in `failed` (step 0).
                val (tapDenied, scriptClashes) =
                    CatalogAPIController.restoreTapPreflight(request, name, caller, contents.taps.map(_.name), taps, repoPlan, repoCfg)
                val allClashes = nameClashes ++ repoPlan.clashes ++ scriptClashes
                if (allClashes.nonEmpty)
                    return conflict(
                        "The archive cannot be restored as '" + name + "': these names already exist on this install. " +
                            "A restore never merges or replaces items outside the target catalog.",
                        allClashes
                    )

                val preexistingTaps = taps.map(_.name).toSet.intersect(manifest.taps.toSet)
                val actor = VersionActor.resolve(request)
                val note = "restored from catalog archive " + Option(manifest.catalog).getOrElse("?") + " by " + actor
                val warnings = mutable.ArrayBuffer.empty[String]
                val skipped = mutable.ArrayBuffer.empty[String]

                // 0. Scope-check every member before anything is written: a
                //    refused member lands in `failed` and nothing of it (not
                //    even its validation schema) is written. Taps were
                //    checked above (tapDenied).
                def denial(resource: String, live: Option[(String, String)]): Option[String] =
                    CatalogAPIController.restoreMemberDenial(request, resource, name, caller, live)
                val pipelineDenied = contents.pipelines.flatMap { p =>
                    denial("pipeline", pipelines.find(_.name == p.name).map(l => (l.catalog, l.createdByKeyLabel))).map(p.name -> _)
                }
                val allowedPipelines = contents.pipelines.filterNot(p => pipelineDenied.exists(_._1 == p.name))
                val allowedTaps = contents.taps.filterNot(t => tapDenied.exists(_._1 == t.name))

                // 1. Validation-schema objects (an existing different object is kept).
                writeValidationSchemas(contents.copy(pipelines = allowedPipelines), warnings, skipped)

                // 2. Pipelines, through the same save path as POST /pipeline.
                //    The archived CodeGen scripts are imported first, so the
                //    save keeps them (fingerprint matches) with no model call.
                val pipelineController = new PipelineAPIController
                val scriptsImported = mutable.ArrayBuffer.empty[String]
                val scriptsRegenerated = mutable.ArrayBuffer.empty[(String, String)]
                val scriptsKept = mutable.ArrayBuffer.empty[(String, String)]
                val pipelineResult = CatalogOps.execute(allowedPipelines.map(_.name)) { n =>
                    val archived = contents.pipelines.find(_.name == n).get
                    val toSave = CatalogArchive.stripPipelineForExport(archived).copy(catalog = name)
                    val plan = CatalogAPIController.importCodegenScripts(contents, n, PipelineScripts, actor, warnings, regenerateScripts)
                    val beforeSave = PipelineScripts.Kinds.map(k => k -> PipelineScripts.record(n, k)).toMap
                    val saved = pipelineController.savePipelineInternal(toSave, note, request) match {
                        case Left(refused) => throw new DatrisException(errorText(refused.getBody))
                        case Right(c) => c
                    }
                    val report =
                        CatalogAPIController.classifyScripts(saved, plan, beforeSave, PipelineScripts, actor, warnings, regenerateScripts)
                    scriptsImported ++= report.imported
                    scriptsRegenerated ++= report.regenerated
                    scriptsKept ++= report.kept
                }
                contents.pipelines.filter(p => pipelineResult.ok.contains(p.name)).foreach { p =>
                    warnings ++= CatalogArchive.installWarnings(p)
                    warnings ++= manifest.warnings.filter(w => w.startsWith(p.name + ": ") && w.contains(CatalogArchive.RedactionMarker))
                }

                // 3. Taps: script first (tenant default store), then the config,
                //    disabled, with run status cleared.
                val tapController = new TapAPIController
                val heldStrict = CatalogAPIController.heldStrictlyToOwnScriptFile(request)
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
                                    val stored = storeRestoredScript(store, repoCfg, repoPlan, n, script, live, actor, heldStrict, request, taps)
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
                //     Read-modify-write without a version compare: an edit made
                //     between loadAll() and the write is overwritten (small window,
                //     same convention as run-status writes).
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
                // Environment labels (DATRIS_ENV): informational only — a
                // cross-environment restore is a promotion and is never refused.
                val targetEnvironment = EnvironmentLabel.current
                manifest.sourceEnvironment.foreach(e => out.addProperty("sourceEnvironment", e))
                targetEnvironment.foreach(e => out.addProperty("targetEnvironment", e))
                warnings ++= CatalogArchive.environmentWarning(manifest.sourceEnvironment, targetEnvironment)
                out.add("warnings", toArray(warnings.distinct.toList))
                out.addProperty("tapsDisabled", true)
                out.addProperty("stateRestored", states.nonEmpty && stateFailures.isEmpty)
                out.addProperty("dependentTaps", dependentTaps)
                out.add("scriptsImported", toArray(scriptsImported.toList))
                def itemsWithReason(rows: Seq[(String, String)]): JsonArray = {
                    val arr = new JsonArray
                    rows.foreach { case (item, reason) =>
                        val o = new JsonObject
                        o.addProperty("item", item)
                        o.addProperty("reason", reason)
                        arr.add(o)
                    }
                    arr
                }
                out.add("scriptsRegenerated", itemsWithReason(scriptsRegenerated.toList))
                out.add("scriptsKept", itemsWithReason(scriptsKept.toList))
                logger.info(
                    "Catalog archive restored as '" + name + "' (mode " + m + ", regenerateScripts " + regenerateScripts + "): " +
                        tapResult.ok.size + " taps, " + pipelineResult.ok.size + " pipelines, " + failures.size + " failed, " +
                        scriptsImported.size + " AI scripts imported, " + scriptsRegenerated.size + " AI scripts regenerated, " +
                        scriptsKept.size + " AI scripts kept"
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
        // The record sidecar travels for Ready records only (a pending record
        // has no script to carry); restore imports script + record together.
        val codegenRecords = codegen.map { case (p, kinds) =>
            p -> kinds.keys.flatMap { kind =>
                try PipelineScripts.record(p, kind).filter(_.status == PipelineScripts.Ready).map(kind -> _)
                catch { case _: Exception => None }
            }.toMap
        }.filter(_._2.nonEmpty)

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
            warnings = warnings.toList,
            sourceEnvironment = EnvironmentLabel.current
        )
        val entries = CatalogArchive.archiveEntries(
            manifest,
            taps.map(CatalogArchive.stripTapForExport),
            pipelines.map(CatalogArchive.stripPipelineForExport),
            scripts,
            states,
            schemas,
            codegen,
            codegenRecords
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
        actor: String,
        heldStrict: Boolean,
        request: HttpServletRequest,
        liveTaps: Seq[TapConfig]
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
                // Judged again on the tap as read now: the listing the clash
                // check used skips a tap that could not be read.
                if (store.storage == GithubCodeStore.storage) {
                    CatalogAPIController.restoreWriteNotOwn(heldStrict, repoCfg, tap, prior).foreach(msg => throw new DatrisException(msg))
                    CatalogAPIController
                        .restoreWriteSharedFile(request, heldStrict, repoCfg, tap, prior, liveTaps)
                        .foreach(msg => throw new DatrisException(msg))
                }
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
                new TapAPIController().deleteTapInternal(placeholder, request, checkScope = false).getOrElse(Nil)
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

object CatalogAPIController {

    /** The tap listing a cascade delete hands to every tap delete: `read`
      * is called at most once for the whole cascade (a failed read is kept,
      * so every later delete sees it fail and keeps its file), and a tap
      * marked deleted no longer counts as using a file. Returns the listing
      * each delete reads and the call that marks a tap deleted. */
    private[api] def cascadeListing(read: () => Seq[TapConfig]): (() => Seq[TapConfig], String => Unit) = {
        val once = TapAPIController.once(() => scala.util.Try(read()))
        val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
        (() => once().get.filter(t => t != null && !deleted.contains(t.name)), n => { deleted.add(n); () })
    }

    /** Rule S2 at a catalog restore onto a code repository: for each tap the
      * restore would commit (`plan.write`) whose live copy holds no
      * repository reference of its own to write over (missing, on built-in
      * storage, or with no repository path), one clash when its file
      * (`<prefix><tap>.py`) is, in any spelling, the current repository
      * script of a stored tap of another name
      * ([[TapScriptOwnership.uploadTargetTaken]]). The restore then refuses
      * as for any other clash, before anything is written. A clash names
      * only the restored tap and its own file; for a caller held to its own
      * tap's script file (`held`, [[TapAPIController.heldToOwnScriptFile]])
      * it says only that the file cannot be written by this restore, not why.
      * For a caller held to its own tap's script file outside log-only mode
      * (`request`, [[heldStrictlyToOwnScriptFile]]) a tap whose live copy
      * holds its own repository reference at its own file is judged too, on
      * that stored path: a clash with the held wording and one audit row
      * (rule S2, site "catalog-restore") when another stored tap runs from
      * the same file. `liveTaps`: every stored tap. */
    private[api] def restoreTargetClashes(
        plan: CatalogArchive.RepoScriptPlan,
        cfg: CodeRepoConfig,
        liveTaps: Seq[TapConfig],
        held: Boolean = false,
        request: HttpServletRequest = null
    ): Seq[String] = {
        val taps = Option(liveTaps).getOrElse(Nil).filter(_ != null)
        plan.write.toSeq.sorted.flatMap { tap =>
            val live = taps.find(_.name == tap)
            live.filter(l => l.scriptStorage == "github" && l.scriptRepoPath != null && l.scriptRepoPath.nonEmpty) match {
                case Some(own) =>
                    // Its own reference is written over: judged only for a
                    // caller held to its own tap's script file, on a stored
                    // path that is its own file (any other path is a rule S4
                    // clash, [[restoreNotOwnClashes]]).
                    if (!heldStrictlyToOwnScriptFile(request) || storedNotOwn(tap, own, cfg)) None
                    else if (!TapScriptOwnership.uploadTargetTaken(tap, own.scriptRepoPath, taps)) None
                    else {
                        sharedFileRestoreRefused(request, tap)
                        Some(restoreTargetHeldMessage(tap, own.scriptRepoPath, cfg))
                    }
                case None =>
                    val path = scala.util.Try(GithubCodeStore.scriptRepoPath(tap, cfg)).toOption
                    path.filter(p => TapScriptOwnership.uploadTargetTaken(tap, p, taps)).map { p =>
                        if (held) restoreTargetHeldMessage(tap, p, cfg)
                        else
                            "tap '" + tap + "': script file '" + p + "' in the code repository (branch " + cfg.branch +
                                ") is the script another tap runs from and would be replaced"
                    }
            }
        }
    }

    /** The clash of [[restoreTargetClashes]] for a caller held to its own
      * tap's script file: says only that the file cannot be written. */
    private def restoreTargetHeldMessage(tap: String, path: String, cfg: CodeRepoConfig): String =
        "tap '" + tap + "': script file '" + path + "' in the code repository (branch " + cfg.branch +
            ") cannot be written by this restore. Ask an administrator."

    /** The audit row of a rule S2 refusal at a catalog restore for a caller
      * held to its own tap's script file. */
    private def sharedFileRestoreRefused(request: HttpServletRequest, tap: String): Unit =
        TapOwnerScopeReport.scriptStoreRefused(
            request,
            tap,
            "S2",
            "catalog-restore",
            "a catalog restore by a caller whose tap access is limited to a catalog or to what it created was refused: " +
                "the tap's code repository script file is also the current script of another tap",
            "The restore was refused and nothing was written."
        )

    /** Rule S4 at a catalog restore onto a code repository, for a caller held
      * to its own tap's script file ([[TapAPIController.heldToOwnScriptFile]])
      * that the route gate did not pass only under log-only capability mode:
      * for each tap the restore would commit (`plan.write`) whose live copy
      * is a repository tap with a stored path that is not the tap's own file
      * ([[TapScriptOwnership.ownRepo]] under the current or a recorded
      * earlier prefix), one clash naming only that tap
      * ([[restoreNotOwnMessage]]) and one audit row
      * ([[TapOwnerScopeReport.scriptStoreRefused]], rule S4, site
      * "catalog-restore"). The restore then refuses as for any other clash, before
      * anything is written. Empty for every other caller. `liveTaps`: every
      * stored tap. */
    private[api] def restoreNotOwnClashes(
        request: HttpServletRequest,
        plan: CatalogArchive.RepoScriptPlan,
        cfg: CodeRepoConfig,
        liveTaps: Seq[TapConfig]
    ): Seq[String] =
        if (!heldStrictlyToOwnScriptFile(request)) Nil
        else {
            val taps = Option(liveTaps).getOrElse(Nil).filter(_ != null)
            plan.write.toSeq.sorted.flatMap { tap =>
                taps
                    .find(_.name == tap)
                    .filter(l => storedNotOwn(tap, l, cfg))
                    .map { _ =>
                        TapOwnerScopeReport.scriptStoreRefused(
                            request,
                            tap,
                            "S4",
                            "catalog-restore",
                            "a catalog restore by a caller whose tap access is limited to a catalog or to what it created was refused: " +
                                "the tap's stored code repository path is not the tap's own script file",
                            "The restore was refused and nothing was written."
                        )
                        restoreNotOwnMessage(tap)
                    }
            }
        }

    /** A caller held to its own tap's script file
      * ([[TapAPIController.heldToOwnScriptFile]]) whose request the route
      * gate did not pass only under log-only capability mode. */
    private[api] def heldStrictlyToOwnScriptFile(request: HttpServletRequest): Boolean =
        TapAPIController.heldToOwnScriptFile(request) && !TapAPIController.passedGateOnlyInLogOnly(request)

    /** `live` is a repository tap whose stored path is not `tap`'s own file
      * under the current or a recorded earlier prefix of `cfg`. */
    private def storedNotOwn(tap: String, live: TapConfig, cfg: CodeRepoConfig): Boolean =
        live != null && live.scriptStorage == "github" && live.scriptRepoPath != null && live.scriptRepoPath.nonEmpty &&
            !TapScriptOwnership.ownRepo(tap, live.scriptRepoPath, TapScriptOwnership.prefixesOf(cfg))

    /** The script checks of a catalog restore for the taps the caller would
      * actually write, after the per-member tap scope check: returns
      * (refused taps as (name, refusal message), script clashes). Each
      * archived tap (`archivedTaps`) goes once through [[restoreMemberDenial]]
      * (`tap`, into `target` as `caller`); a refused tap is left out of the
      * repository write set before [[restoreTargetClashes]] (every caller;
      * the held wording for a caller held to its own tap's script file) and
      * [[restoreNotOwnClashes]] (rule S4) judge it, so it is reported only
      * as refused and the rest of the restore proceeds. No repository
      * (`repoCfg` None): no script clashes. `liveTaps`: every stored tap. */
    private[api] def restoreTapPreflight(
        request: HttpServletRequest,
        target: String,
        caller: String,
        archivedTaps: Seq[String],
        liveTaps: Seq[TapConfig],
        plan: CatalogArchive.RepoScriptPlan,
        repoCfg: Option[CodeRepoConfig]
    ): (Seq[(String, String)], Seq[String]) = {
        val taps = Option(liveTaps).getOrElse(Nil).filter(_ != null)
        val denied = archivedTaps.flatMap { n =>
            restoreMemberDenial(request, "tap", target, caller, taps.find(_.name == n).map(l => (l.catalog, l.createdByKeyLabel))).map(n -> _)
        }
        val writable = plan.copy(write = plan.write -- denied.map(_._1))
        val clashes = repoCfg.toSeq.flatMap { cfg =>
            // Rule S2: a restored script never replaces, with no reference
            // of that tap behind it, the file another stored tap runs from.
            restoreTargetClashes(writable, cfg, taps, TapAPIController.heldToOwnScriptFile(request), request) ++
                // Rule S4 for a caller held to its own tap's script file: a
                // live repository tap whose stored file is not its own is
                // not written through.
                restoreNotOwnClashes(request, writable, cfg, taps)
        }
        (denied, clashes)
    }

    /** At the moment a restored tap script is written through the code
      * repository store with `prior` (the tap as read just before the write)
      * as the reference it replaces: for a caller held to its own tap's
      * script file (`heldStrict`, [[heldStrictlyToOwnScriptFile]]) whose
      * `prior` is a repository tap with a stored path that is not the tap's
      * own file, Some([[restoreNotOwnMessage]]) and that member is not
      * written. None for every other caller and every other `prior`. */
    private[api] def restoreWriteNotOwn(heldStrict: Boolean, repoCfg: Option[CodeRepoConfig], tap: String, prior: TapConfig): Option[String] =
        if (heldStrict && storedNotOwn(tap, prior, repoCfg.orNull)) Some(restoreNotOwnMessage(tap)) else None

    /** At the moment a restored tap script is written through the code
      * repository store with `prior` (the tap as read just before the write):
      * for a caller held to its own tap's script file (`heldStrict`) whose
      * write would land on a file (`prior`'s stored repository path, else
      * `<prefix><tap>.py`) that is, normalised, the current repository script
      * of a tap of another name in `liveTaps`, Some(the held clash wording of
      * [[restoreTargetClashes]]) with one audit row (rule S2, site
      * "catalog-restore"), and that member is not written. None for every
      * other caller and every other file. */
    private[api] def restoreWriteSharedFile(
        request: HttpServletRequest,
        heldStrict: Boolean,
        repoCfg: Option[CodeRepoConfig],
        tap: String,
        prior: TapConfig,
        liveTaps: Seq[TapConfig]
    ): Option[String] =
        if (!heldStrict) None
        else
            repoCfg.flatMap { cfg =>
                val path =
                    if (prior != null && prior.scriptStorage == "github" && prior.scriptRepoPath != null && prior.scriptRepoPath.nonEmpty)
                        Some(prior.scriptRepoPath)
                    else scala.util.Try(GithubCodeStore.scriptRepoPath(tap, cfg)).toOption
                path.filter(p => TapScriptOwnership.uploadTargetTaken(tap, p, Option(liveTaps).getOrElse(Nil))).map { p =>
                    sharedFileRestoreRefused(request, tap)
                    restoreTargetHeldMessage(tap, p, cfg)
                }
            }

    /** The clash of [[restoreNotOwnClashes]]: names only the restored tap. */
    def restoreNotOwnMessage(tapName: String): String =
        "tap '" + tapName + "': its script is stored in the code repository at a file that is not this tap's own script file, " +
            "so it cannot be restored by a user or key whose tap access is limited to a catalog or to what it created. " +
            "Ask an administrator to move the script to the tap's own path (for example by moving it to built-in storage and back)."

    /** The per-member decision of catalog rename (`update`), detach and
      * cascade delete (`delete`), before a member is written or deleted:
      * `<resource>:<action>` on the member's (catalog, owner)
      * ([[CapabilityCheck.assertScope]]) and, for an API key, read in scope
      * as well ([[CapabilityCheck.assertWriteReadable]], the shared
      * stored-item rule). Throws [[CapabilityDeniedException]], which the
      * member loop records as that member's failure (denied) and the other
      * members proceed. */
    def assertMemberWrite(request: HttpServletRequest, resource: String, action: String, catalog: String, owner: String): Unit = {
        val ctx = CatalogOps.scopeContext(catalog, owner)
        CapabilityCheck.assertScope(request, resource, action, ctx)
        CapabilityCheck.assertWriteReadable(request, resource, action, ctx)
    }

    /** The per-member decision of a catalog move (rename: `update`, the new
      * name; detach: `delete`, `toCatalog` null): [[assertMemberWrite]] on
      * the member where it is, then, for an API key (never a signed-in
      * session), the member as it would be stored, (`toCatalog`, `owner`),
      * through the rule `POST /tap` and `POST /pipeline` apply to the item as
      * saved: when the key's scope can refuse `create` or `update` there
      * ([[ai.datris.auth.CallerVisibility.keyOutOfScope]]) and neither is
      * granted there, refused on `update`. So a key moves an item only where
      * it may also write it, the same answer moving that one item gives. A
      * detach also accepts `delete` granted where the item lands, so a key
      * limited to what it created keeps detaching its own items whether or
      * not it holds `create` or `update`. Throws [[CapabilityDeniedException]]
      * (shared wording, audit note). */
    def assertMemberMove(request: HttpServletRequest, resource: String, action: String, fromCatalog: String, toCatalog: String, owner: String): Unit = {
        assertMemberWrite(request, resource, action, fromCatalog, owner)
        assertKeyMoveTarget(request, resource, action, toCatalog, owner)
    }

    /** The destination half of [[assertMemberMove]]: for an API key (no
      * session), `create` or `update` (or the operation's own `action`) on
      * (`toCatalog`, `owner`) when its scope can refuse them, refused on
      * `update` (shared wording). */
    private def assertKeyMoveTarget(request: HttpServletRequest, resource: String, action: String, toCatalog: String, owner: String): Unit =
        CapabilityCheck.resolvedKey(request).filter(rk => !rk.restrictedSession && !rk.label.startsWith("session:")).foreach { rk =>
            val ctx = CatalogOps.scopeContext(toCatalog, owner)
            val accepted = (ai.datris.auth.CallerVisibility.TapWriteActions :+ action).distinct
            if (accepted.exists(a => ai.datris.auth.CallerVisibility.keyOutOfScope(rk, resource, a, ctx)) && !accepted.exists(a => rk.grants(resource, a, ctx)))
                CapabilityCheck.assertScope(request, resource, "update", ctx)
        }

    /** The all-or-nothing pre-flight of a catalog rename (`update`,
      * `toCatalog` = the new name) and detach (`delete`, `toCatalog` null),
      * before anything is written: every member tap, then every member
      * pipeline, through [[assertMemberMove]]; for a catalog with no members,
      * every placeholder (tap scope on the placeholder where it is, then, for
      * a rename only, the same destination check). Some(403) on the first refusal; its message
      * names the refused member only when the caller may read it, otherwise
      * it is the shared scope-denial text alone. None: the whole move is
      * allowed. Pure apart from the audit note of the refusal. */
    def moveRefusal(
        request: HttpServletRequest,
        action: String,
        fromCatalog: String,
        toCatalog: String,
        members: CatalogOps.Members,
        placeholders: Seq[TapConfig]
    ): Option[ResponseEntity[String]] = {
        def forbidden(message: String): Option[ResponseEntity[String]] =
            Some(ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body[String](CatalogOps.capabilityDeniedBody(message)))
        def check(resource: String, name: String, owner: String): Option[ResponseEntity[String]] =
            try {
                assertMemberMove(request, resource, action, fromCatalog, toCatalog, owner)
                None
            } catch {
                case e: CapabilityDeniedException =>
                    val readable = CapabilityCheck.grants(request, resource, "read", CatalogOps.scopeContext(fromCatalog, owner))
                    forbidden(if (readable) e.getMessage + " (" + resource + " '" + name + "')" else e.getMessage)
            }
        if (members.isEmpty)
            placeholders.view.flatMap { ph =>
                try {
                    CapabilityCheck.assertScope(request, "tap", action, CatalogOps.scopeContext(fromCatalog, ph.createdByKeyLabel))
                    // A placeholder is bookkeeping, not an item: a detach lands
                    // nothing anywhere, so only a rename checks where it goes.
                    if (toCatalog != null) assertKeyMoveTarget(request, "tap", action, toCatalog, ph.createdByKeyLabel)
                    None
                } catch {
                    case e: CapabilityDeniedException => forbidden(e.getMessage)
                }
            }.headOption
        else
            members.taps.view.flatMap(t => check("tap", t.name, t.createdByKeyLabel)).headOption
                .orElse(members.pipelines.view.flatMap(p => check("pipeline", p.name, p.createdByKeyLabel)).headOption)
    }

    /** The per-member decision of a catalog restore before an archived item
      * is written into catalog `target` as `caller`
      * ([[CatalogArchive.restoreScopeChecks]]): `create` in the target, and
      * for an item that already exists (`live` = its catalog and owner)
      * `update` on it plus, for an API key, read in scope, so a member the
      * key cannot read is never overwritten. Some(refusal message) or None. */
    def restoreMemberDenial(
        request: HttpServletRequest,
        resource: String,
        target: String,
        caller: String,
        live: Option[(String, String)]
    ): Option[String] =
        CatalogArchive.restoreScopeChecks(target, caller, live).view.flatMap { case (action, ctx) =>
            try {
                CapabilityCheck.assertScope(request, resource, action, ctx)
                if (action == "update") CapabilityCheck.assertWriteReadable(request, resource, action, ctx)
                None
            } catch { case e: CapabilityDeniedException => Some(Option(e.getMessage).getOrElse("capability denied")) }
        }.headOption
    private val logger: Logger = LoggerFactory.getLogger(classOf[CatalogAPIController])

    /** The cascade delete's `warnings`: pipeline warnings as
      * {pipeline, message}, then tap warnings as {tap, message}, each list in
      * input order. */
    private[api] def warningsArray(pipelineWarnings: Seq[(String, String)], tapWarnings: Seq[(String, String)]): JsonArray = {
        val arr = new JsonArray
        def add(key: String, warnings: Seq[(String, String)]): Unit =
            warnings.foreach { case (n, msg) =>
                val o = new JsonObject
                o.addProperty(key, n)
                o.addProperty("message", msg)
                arr.add(o)
            }
        add("pipeline", pipelineWarnings)
        add("tap", tapWarnings)
        arr
    }

    /** What restore did with one pipeline's archived AI scripts.
      * `imported`: the records importScript wrote. `regenerated`:
      * ("<pipeline>/<kind>", reason) for every AI kind the archived pipeline
      * has that was not imported (its save regenerates it). */
    case class ScriptImport(imported: Seq[CodeGenScript], regenerated: Seq[(String, String)])

    /** Reason for an archived script exported before records travelled. */
    val NoRecordReason = "no script record in the archive; re-export to carry it"
    val NoScriptReason = "the archive has no script for it"
    val RegenerateRequestedReason = "regenerateScripts was requested"

    /** For each AI kind of `pipelineName`'s archived config: import the
      * archived .py with its .json sidecar through `scripts.importScript`.
      * A .py with no sidecar is reported with [[NoRecordReason]]; a sidecar
      * importScript refuses is reported with its reason (a repository
      * conflict is also added to `warnings`). With `regenerateScripts`
      * nothing is imported and every kind is reported under `regenerated`.
      * Never makes a model call. */
    private[api] def importCodegenScripts(
        contents: CatalogArchive.Contents,
        pipelineName: String,
        scripts: PipelineScripts,
        actor: String,
        warnings: mutable.ArrayBuffer[String],
        regenerateScripts: Boolean = false
    ): ScriptImport =
        contents.pipelines.find(_.name == pipelineName) match {
            case None => ScriptImport(Nil, Nil)
            case Some(cfg) =>
                val texts = contents.codegenScripts.getOrElse(pipelineName, Map.empty)
                val recs = contents.codegenRecords.getOrElse(pipelineName, Map.empty)
                val imported = mutable.ArrayBuffer.empty[CodeGenScript]
                val regenerated = mutable.ArrayBuffer.empty[(String, String)]
                PipelineScripts.Kinds.filter(k => PipelineScripts.instructionOf(cfg, k).isDefined).foreach { kind =>
                    val item = pipelineName + "/" + kind
                    if (regenerateScripts) regenerated += (item -> RegenerateRequestedReason)
                    else
                        (texts.get(kind), recs.get(kind)) match {
                            case (None, _) => regenerated += (item -> NoScriptReason)
                            case (Some(_), None) =>
                                val problem = contents.codegenRecordProblems.getOrElse(pipelineName, Map.empty).get(kind)
                                regenerated += (item -> problem.getOrElse(NoRecordReason))
                            case (Some(text), Some(rec)) =>
                                scripts.importScript(cfg, kind, text, rec, actor) match {
                                    case Right(r) => imported += r
                                    case Left(why) =>
                                        regenerated += (item -> why)
                                        if (why.contains(PipelineScripts.ConflictMarker)) warnings += (item + ": " + why)
                                }
                        }
                }
                ScriptImport(imported.toList, regenerated.toList)
        }

    /** What happened to one restored pipeline's AI scripts. `regenerated`
      * and `kept` carry (item, reason): the reason the archived script was
      * not imported. */
    case class ScriptReport(imported: Seq[String], regenerated: Seq[(String, String)], kept: Seq[(String, String)])

    /** After the pipeline save: the items whose imported record survived the
      * save (same generatedAt and fingerprint) are imported. A kind that was
      * not imported and whose live record the save left unchanged (a usable
      * script already on the target) is kept, with the reason the archived
      * one was not used; with `regenerateScripts` it is regenerated now
      * instead, so the caller gets the current model's script. Every other AI
      * kind was regenerated by the save, with the reason from `plan`. */
    private[api] def classifyScripts(
        saved: PipelineConfig,
        plan: ScriptImport,
        beforeSave: Map[String, Option[CodeGenScript]],
        scripts: PipelineScripts,
        actor: String,
        warnings: mutable.ArrayBuffer[String],
        regenerateScripts: Boolean
    ): ScriptReport = {
        val imported = mutable.ArrayBuffer.empty[String]
        val regenerated = mutable.ArrayBuffer.empty[(String, String)]
        val kept = mutable.ArrayBuffer.empty[(String, String)]
        PipelineScripts.Kinds.filter(k => PipelineScripts.instructionOf(saved, k).isDefined).foreach { kind =>
            val item = saved.name + "/" + kind
            try {
                val after = scripts.record(saved.name, kind)
                plan.imported.find(_.kind == kind) match {
                    case Some(imp) if after.exists(a => a.generatedAt == imp.generatedAt && a.fingerprint == imp.fingerprint) =>
                        imported += item
                    case Some(_) => regenerated += (item -> "the saved pipeline config differs from the archived one the script was written for")
                    case None =>
                        val reason = plan.regenerated.find(_._1 == item).map(_._2).getOrElse(NoScriptReason)
                        // Kept only when a usable (Ready) live script is still there,
                        // unchanged; an unchanged Pending record has no script to keep.
                        val untouched = after.exists(a => a.status == PipelineScripts.Ready && beforeSave.getOrElse(kind, None).contains(a))
                        if (untouched && regenerateScripts)
                            scripts.regenerate(saved, kind, actor) match {
                                case Right(_) => regenerated += (item -> reason)
                                case Left(why) =>
                                    warnings += (item + ": " + why)
                                    regenerated += (item -> (reason + "; regenerating failed: " + why))
                            }
                        else if (untouched) kept += (item -> reason)
                        else regenerated += (item -> reason)
                }
            } catch {
                case e: Exception =>
                    logger.warn("Catalog restore: could not report the " + item + " script: " + e.getMessage)
                    regenerated += (item -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            }
        }
        ScriptReport(imported.toList, regenerated.toList, kept.toList)
    }

    /** The refusal `POST /catalog/{name}/enable-ordered` returns before a job
      * starts, or None. `taps` = the catalog's member taps (no placeholders).
      *  - 409 {"error", "cycles"} when TapDependencies.order has cycles;
      *  - 403 when the caller's key fails CapabilityCheck.assertScope for
      *    `tap:update` or `tap:run` on any member (scope = catalog + owner). */
    def enableOrderedRefusal(
        request: HttpServletRequest,
        taps: Seq[TapConfig],
        readPipeline: String => PipelineConfig = n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
    ): Option[ResponseEntity[String]] = {
        val plan = TapDependencies.order(taps)
        if (plan.cycles.nonEmpty) {
            val o = new JsonObject
            o.addProperty(
                "error",
                "The taps of this catalog have a dependency cycle (" + plan.cycles.map(_.mkString(", ")).mkString("; ") +
                    "), so there is no enable order. Remove one dependency in each cycle (an explicit dependsOn of [] overrides the inferred list)."
            )
            val cycles = new JsonArray
            plan.cycles.foreach { c =>
                val a = new JsonArray
                c.foreach(a.add)
                cycles.add(a)
            }
            o.add("cycles", cycles)
            return Some(ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body[String](o.toString))
        }
        for (t <- taps; action <- Seq("update", "run")) {
            // The same per-member rule as rename, detach and delete: the
            // action in scope and, for an API key, tap read in scope, on every
            // member before any tap is tested, enabled or run.
            try CatalogAPIController.assertMemberWrite(request, "tap", action, t.catalog, t.createdByKeyLabel)
            catch {
                case e: CapabilityDeniedException =>
                    return Some(
                        ResponseEntity
                            .status(HttpStatus.FORBIDDEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body[String](CatalogOps.capabilityDeniedBody(e.getMessage + " (tap '" + t.name + "')"))
                    )
            }
        }
        // The job runs each tap in mode=run, feeding its target pipeline: same
        // rule as POST /tap/run for a restricted user (no-op for everyone else).
        for (t <- taps) TapAPIController.targetPipelineRefusal(request, t, readPipeline).foreach(r => return Some(r))
        None
    }

    /** 403 when the caller's key may not `tap:<action>` the catalog's
      * ordered-enable job: catalog scope = the catalog, owner scope = whoever
      * started the job (absent when there is none). Route capability alone
      * would let a `catalog=A` key read or cancel catalog B's job. For an
      * action other than `read` (cancel: `run`) a key limited to a catalog
      * or to what it created must also read the catalog's taps
      * ([[CapabilityCheck.assertWriteReadable]]), so such a key that cannot
      * gets the same 403 to a cancel whether or not a job is running. */
    def enableJobScopeDenial(request: HttpServletRequest, action: String, catalog: String): Option[ResponseEntity[String]] = {
        val owner = CatalogEnableJob.status(catalog).map(_.startedBy).orNull
        try {
            val ctx = CatalogOps.scopeContext(catalog, owner)
            CapabilityCheck.assertScope(request, "tap", action, ctx)
            CapabilityCheck.assertWriteReadable(request, "tap", action, ctx)
            None
        } catch {
            case e: CapabilityDeniedException =>
                Some(
                    ResponseEntity
                        .status(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body[String](CatalogOps.capabilityDeniedBody(e.getMessage))
                )
        }
    }

    /** `{levels: [{level, taps: [readiness]}], cycles}`; levels are 1-based. */
    def planJson(plan: TapDependencies.Plan): JsonObject = {
        val out = new JsonObject
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
        plan.cycles.foreach { c =>
            val a = new JsonArray
            c.foreach(a.add)
            cycles.add(a)
        }
        out.add("cycles", cycles)
        out
    }

    private def strings(xs: Seq[String]): JsonArray = {
        val a = new JsonArray
        xs.foreach(a.add)
        a
    }

    private def readinessJson(r: TapDependencies.Readiness): JsonObject = {
        val o = new JsonObject
        o.addProperty("name", r.name)
        o.addProperty("enabled", r.enabled)
        o.addProperty("cronExpression", r.cronExpression)
        if (r.dependsOn != null) o.add("dependsOn", strings(r.dependsOn))
        if (r.inferredDependsOn != null) o.add("inferredDependsOn", strings(r.inferredDependsOn))
        o.addProperty("explicit", r.explicit)
        o.addProperty("hasPassingTest", r.hasPassingTest)
        o.addProperty("lastRealRunLanded", r.lastRealRunLanded)
        o.add("blockedBy", strings(r.blockedBy))
        o.addProperty("ready", r.ready)
        o
    }

    /** Production side effects of an ordered enable (see CatalogEnableJob.Deps). */
    object EnableDeps extends CatalogEnableJob.Deps {
        override def read(name: String): TapConfig = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)

        override def test(tap: TapConfig): TapScriptResult = {
            val result = TapRunner.run(tap, mode = "test", testLimit = TapAPIController.resolveTestLimit("test", null))
            try {
                // Status churn: plain write, no version minted (as POST /tap/run);
                // the stored owner is kept (an owner changed during the test stays).
                TapConfigIO.writeRunState(TapAPIController.stampTestResult(tap, result, TapAPIController.nowStamp()))
                // This test returns no preview, so nothing is held: a failed test
                // drops any preview held for a later reuseLastTest request (and a
                // passing one is followed by the enable's versioned save, which
                // drops it too).
                TapAPIController.holdTestPreview(
                    TapTestPreviewCache,
                    tap,
                    result,
                    preview = null,
                    previewTruncated = false,
                    testLimit = TapAPIController.resolveTestLimit("test", null),
                    paramsSent = false,
                    scriptId = None
                )
            } finally {
                try TapRunner.release(result)
                catch { case e: Exception => logger.warn("Could not release test staging of tap '" + tap.name + "': " + e.getMessage) }
            }
            result
        }

        override def enable(tap: TapConfig, note: String, actor: String): Unit =
            CatalogOps.withCatalogLock { TapConfigIO.writeVersioned(tap, note, actor) }

        override def run(tap: TapConfig): TapScriptResult = TapRunner.run(tap, mode = "run", trigger = "manual")

        override def rollup(publisherToken: String): PipelineStatusRollup =
            PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(publisherToken).rollup

        override def pollIntervalMs: Long = 5000L

        override def waitTimeoutMs: Long = CatalogEnableJob.defaultWaitTimeoutMs
    }
}
