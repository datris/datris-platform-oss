package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, HiddenTapScrub}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, TapConfig}
import ai.datris.util.{PipelineConfigIO, ProvenanceResolver, TapConfigIO}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Resolves a stamped `_datris_run_id` back to the run, tap run, script commit,
  * config version and source that produced it (see [[ProvenanceResolver]]).
  * Read-only; wrapped by the `get_provenance` MCP tool. */
@RestController
@RequestMapping(Array("/api/v1"))
class ProvenanceAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ProvenanceAPIController])
    private val gson = new Gson()

    @GetMapping(path = Array("/provenance"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def provenance(
        @RequestParam(name = "runId") runId: String,
        @RequestParam(name = "pipeline", required = false) pipeline: String,
        @RequestParam(name = "tapRun", required = false) tapRun: String,
        @RequestParam(name = "configVersion", required = false) configVersion: java.lang.Integer,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            if (runId == null || runId.trim.isEmpty)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"runId is required\"}")
            // A restricted session's hints are ignored: the run's own records
            // name its pipeline and tap run, so a visible pipeline name cannot
            // vouch for a hidden run.
            val restricted = CapabilityCheck.isRestrictedSession(request)
            val resolved = ProvenanceResolver.resolve(
                if (pipeline != null && !restricted) pipeline.trim else null,
                runId.trim,
                if (tapRun != null && !restricted) tapRun.trim else null,
                configVersion
            )
            val refused = ProvenanceAPIController.provenanceRefusal(
                request,
                runId.trim,
                resolved,
                n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
            )
            if (refused.isDefined) return refused.get
            val shown =
                if (!restricted) resolved
                else {
                    val env = DatrisEnvironment.current
                    val pipelineName = resolved.get("pipeline").getAsString // present: provenanceRefusal passed
                    val feeding = HiddenTapScrub.hiddenFeedingTaps(request, TapConfigIO.readAll(env.tapTableName), Set(pipelineName))
                    ProvenanceAPIController.scrubProvenance(request, resolved, n => TapConfigIO.read(env.tapTableName, n), feeding)
                }
            new ResponseEntity[String](gson.toJson(shown), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error resolving provenance: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
        }
    }
}

object ProvenanceAPIController {

    /** For a restricted user's session, after [[ProvenanceResolver.resolve]]:
      * 404 `no such run: <runId>` when the resolved run names no pipeline, or
      * one that is missing or outside the grants (`read` loads it; null =
      * missing). The body never names the pipeline. None for everyone else. */
    def provenanceRefusal(
        request: HttpServletRequest,
        runId: String,
        resolved: JsonObject,
        read: String => PipelineConfig
    ): Option[ResponseEntity[String]] = {
        if (!CapabilityCheck.isRestrictedSession(request)) return None
        val pipeline =
            if (resolved != null && resolved.has("pipeline") && resolved.get("pipeline").isJsonPrimitive) resolved.get("pipeline").getAsString
            else null
        val hidden = pipeline == null || pipeline.isEmpty || PipelineAPIController.hiddenConfig(request, read(pipeline), "read")
        if (!hidden) None
        else
            Some(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body[String]("{\"error\":\"no such run: " + Option(runId).getOrElse("").replace("\"", "'").replace("\\", "") + "\"}")
            )
    }

    /** For a restricted user's session, after [[provenanceRefusal]] passed:
      * when the run's tap (from `input.tapName`, `tapRun.tapName` or
      * `tap.name`) is one the session cannot read (`readTap` loads it; null =
      * missing), the `tapRun` and `tap` blocks and the top-level `source` go,
      * and the input loses the tap identity fields. Filenames (`input`,
      * `job`) read `tap-[hidden]` for that tap and for `hiddenFeeding`
      * (hidden taps feeding the run's pipeline, for runs that predate the
      * recorded tap identity). Everyone else: `resolved` unchanged. Scrubs in
      * place; returns `resolved`. */
    def scrubProvenance(
        request: HttpServletRequest,
        resolved: JsonObject,
        readTap: String => TapConfig,
        hiddenFeeding: Set[String]
    ): JsonObject = {
        if (resolved == null || !CapabilityCheck.isRestrictedSession(request)) return resolved
        def str(o: JsonObject, block: String, field: String): Option[String] =
            if (o.has(block) && o.get(block).isJsonObject) {
                val b = o.getAsJsonObject(block)
                if (b.has(field) && b.get(field).isJsonPrimitive) Some(b.get(field).getAsString) else None
            } else None
        val named = Seq(str(resolved, "input", "tapName"), str(resolved, "tapRun", "tapName"), str(resolved, "tap", "name")).flatten.distinct
        val hiddenNamed = named.filter(n => HiddenTapScrub.tapHidden(request, n, readTap)).toSet
        if (hiddenNamed.nonEmpty) {
            resolved.remove("tapRun")
            resolved.remove("tap")
            resolved.remove("source")
            if (resolved.has("input") && resolved.get("input").isJsonObject)
                HiddenTapScrub.TapIdentityFields.foreach(resolved.getAsJsonObject("input").remove)
        }
        val scrub = hiddenNamed ++ hiddenFeeding
        if (scrub.nonEmpty) Seq("input", "job").foreach { block =>
            str(resolved, block, "filename").foreach(f => resolved.getAsJsonObject(block).addProperty("filename", HiddenTapScrub.scrubFilename(f, scrub)))
        }
        resolved
    }
}
