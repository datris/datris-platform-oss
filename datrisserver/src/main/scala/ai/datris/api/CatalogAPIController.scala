package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, VersionActor}
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, TapConfig}
import ai.datris.util.{APIKeyValidator, CatalogOps, NoSQLDbUtil, PipelineConfigIO, SecretsUtil, TapConfigIO}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._
import scala.collection.mutable

/** First-class catalog operations. A catalog is the `catalog` field on taps
  * and pipelines plus optional hidden placeholder taps `__catalog__*` (a
  * placeholder belongs to the catalog in its `catalog` field, falling back to
  * its name suffix when that field is empty);
  * these endpoints rewrite every member server-side instead of a client
  * fan-out, and report per-member failures (not transactional).
  *
  *   - `PUT /api/v1/catalog/{name}` body `{"newName": "..."}` renames (a merge
  *     when the target exists, refused with 409 on any member-name clash).
  *   - `DELETE /api/v1/catalog/{name}?mode=detach|cascade&confirm=<name>`
  *     detaches members to Uncataloged (default) or deletes them with their
  *     data (cascade, needs `confirm` equal to the name).
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

                val target = CatalogOps.members(taps, pipelines, newName)
                val clashes = CatalogOps.clashes(members, target.taps, target.pipelines)
                if (clashes.nonEmpty) {
                    val out = new JsonObject
                    out.addProperty(
                        "error",
                        "Cannot rename '" + name + "' to '" + newName + "': " + clashes.size +
                            " item name(s) already exist in the target catalog"
                    )
                    out.add("clashes", toArray(clashes))
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
                val hasNewPlaceholder = CatalogOps.Placeholder.of(taps, newName).nonEmpty
                val newPlaceholderName = CatalogOps.Placeholder.freshName(newName, taps.map(_.name).toSet -- removedPlaceholders)
                val placeholder =
                    if (!touchPlaceholders) "skipped"
                    else if (hasNewPlaceholder) "kept"
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
                                CatalogOps.execute(members.pipelines.map(_.name)) { n =>
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
                respond(out, failures)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

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
    private def trackDenied(denied: mutable.ArrayBuffer[String], n: String)(f: => Unit): Unit =
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
