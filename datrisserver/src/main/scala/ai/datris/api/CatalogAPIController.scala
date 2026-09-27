package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.auth.{CapabilityCheck, VersionActor}
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, TapConfig}
import ai.datris.util.{APIKeyValidator, CatalogOps, NoSQLDbUtil, PipelineConfigIO, SecretsUtil, TapConfigIO}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** First-class catalog operations. A catalog is the `catalog` field on taps
  * and pipelines plus an optional hidden placeholder tap `__catalog__<name>`;
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
  * to pipeline:delete; each member is additionally owner-scope checked with
  * its own resource type, and refused members land in `failed`. */
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
                    "Invalid catalog name '" + newName + "': use lowercase letters, digits, '_' and '-' only"
                )
            if (newName == name)
                return badRequest("New name is the same as the current name")

            val (taps, pipelines) = loadAll()
            val members = CatalogOps.members(taps, pipelines, name)
            val hasOldPlaceholder = taps.exists(_.name == CatalogOps.Placeholder.name(name))
            if (members.isEmpty && !hasOldPlaceholder)
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
            val tapResult = CatalogOps.execute(members.taps.map(_.name)) { n =>
                val live = readTap(n)
                CapabilityCheck.assertOwnerScope(request, "tap", "update", live.createdByKeyLabel)
                TapConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)
            }
            val pipelineResult = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                val live = readPipeline(n)
                CapabilityCheck.assertOwnerScope(request, "pipeline", "update", live.createdByKeyLabel)
                PipelineConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)
            }

            val failed = Seq.newBuilder[(String, String)]
            failed ++= tapResult.failed
            failed ++= pipelineResult.failed

            // Placeholders are catalog bookkeeping, not members: the request
            // gate already authorised the catalog operation.
            if (hasOldPlaceholder)
                removePlaceholder(name, request).foreach(failed += _)
            val hasNewPlaceholder = taps.exists(_.name == CatalogOps.Placeholder.name(newName))
            val placeholder =
                if (hasNewPlaceholder) "kept"
                else
                    try {
                        TapConfigIO.writeVersioned(
                            TapConfig(
                                name = CatalogOps.Placeholder.name(newName),
                                description = "Catalog placeholder",
                                targetPipeline = null,
                                catalog = newName,
                                enabled = false
                            ),
                            "catalog placeholder created by rename " + name + " → " + newName + " by " + actor,
                            actor
                        )
                        "created"
                    } catch {
                        case e: Exception =>
                            failed += (CatalogOps.Placeholder.name(newName) -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                            "failed"
                    }

            val failures = failed.result()
            val out = new JsonObject
            out.add("renamed", toArray(tapResult.ok ++ pipelineResult.ok))
            out.add("failed", failedArray(failures))
            out.add("affectedKeys", toArray(affectedKeys(name)))
            out.addProperty("placeholder", placeholder)
            respond(out, failures)
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

            val (taps, pipelines) = loadAll()
            val members = CatalogOps.members(taps, pipelines, name)
            val hasPlaceholder = taps.exists(_.name == CatalogOps.Placeholder.name(name))
            if (members.isEmpty && !hasPlaceholder)
                return notFound(name)

            val actor = VersionActor.resolve(request)
            val (tapResult, pipelineResult) =
                if (m == "detach") {
                    val note = "catalog detached from " + name + " by " + actor
                    val t = CatalogOps.execute(members.taps.map(_.name)) { n =>
                        val live = readTap(n)
                        CapabilityCheck.assertOwnerScope(request, "tap", "delete", live.createdByKeyLabel)
                        TapConfigIO.writeVersioned(live.copy(catalog = null), note, actor)
                    }
                    val p = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                        val live = readPipeline(n)
                        CapabilityCheck.assertOwnerScope(request, "pipeline", "delete", live.createdByKeyLabel)
                        PipelineConfigIO.writeVersioned(live.copy(catalog = null), note, actor)
                    }
                    (t, p)
                } else {
                    // Taps first so nothing lands in a pipeline mid-delete.
                    // Both helpers run their own owner-scope check.
                    val tapController = new TapAPIController
                    val pipelineController = new PipelineAPIController
                    val t = CatalogOps.execute(members.taps.map(_.name)) { n =>
                        readTap(n)
                        tapController.deleteTapInternal(n, request)
                    }
                    val p = CatalogOps.execute(members.pipelines.map(_.name)) { n =>
                        pipelineController.deletePipelineInternal(readPipeline(n), request)
                    }
                    (t, p)
                }

            val failed = Seq.newBuilder[(String, String)]
            failed ++= tapResult.failed
            failed ++= pipelineResult.failed
            if (hasPlaceholder)
                removePlaceholder(name, request).foreach(failed += _)

            val failures = failed.result()
            val out = new JsonObject
            out.add(if (m == "detach") "detached" else "deleted", toArray(tapResult.ok ++ pipelineResult.ok))
            out.add("failed", failedArray(failures))
            respond(out, failures)
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

    /** Re-read right before writing so a concurrent status update is not lost. */
    private def readTap(n: String): TapConfig = {
        val t = TapConfigIO.read(DatrisEnvironment.current.tapTableName, n)
        if (t == null) throw new DatrisException("Tap " + n + " no longer exists")
        t
    }

    private def readPipeline(n: String): PipelineConfig = {
        val p = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
        if (p == null) throw new DatrisException("Pipeline " + n + " no longer exists")
        p
    }

    private def removePlaceholder(catalog: String, request: HttpServletRequest): Option[(String, String)] = {
        val placeholder = CatalogOps.Placeholder.name(catalog)
        try {
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
