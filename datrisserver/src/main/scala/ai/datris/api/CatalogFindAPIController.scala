package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityCheck
import ai.datris.model.{DatrisEnvironment, PipelineConfig, TapConfig}
import ai.datris.util.{CatalogFind, PipelineConfigIO, TapConfigIO}
import com.google.common.base.Throwables
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Dataset discovery (`find_data`): rank the pipelines this caller may read by
  * relevance to a query and return location, freshness, provenance handles and
  * a pre-filled `howToQuery` hint. Discovery only — nothing is executed on the
  * caller's behalf; the query call is the agent's own, under its own
  * capabilities. */
@RestController
@RequestMapping(Array("/api/v1/catalog"))
class CatalogFindAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[CatalogFindAPIController])
    private val gson = new Gson()

    @GetMapping(path = Array("/find"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def find(
        @RequestParam(name = "query") query: String,
        @RequestParam(name = "limit", required = false) limit: java.lang.Integer,
        @RequestParam(name = "ai", required = false) ai: java.lang.Boolean,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            if (query == null || query.trim.isEmpty)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\":\"query is required\"}")

            val env = DatrisEnvironment.current
            val pipelines =
                try PipelineConfigIO.readAll(env.pipelineTableName)
                catch { case _: Exception => Nil }
            val taps =
                try TapConfigIO.readAll(env.tapTableName)
                catch { case _: Exception => Nil }

            // Per-item capability filter: a hit is visible only when the key
            // could read that pipeline (catalog/owner scopes included).
            val visible = pipelines.filter(p => p != null && CapabilityCheck.grants(request, "pipeline", "read", scopeContext(p)))

            val result = CatalogFind.find(
                query.trim,
                if (limit != null) limit.intValue() else CatalogFind.DefaultLimit,
                ai != null && ai.booleanValue(),
                visible,
                taps.filter(t => t != null && CapabilityCheck.grants(request, "tap", "read", tapScopeContext(t)))
            )
            new ResponseEntity[String](gson.toJson(result), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error in catalog find: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace("\"", "'") + "\"}")
        }
    }

    private def scopeContext(p: PipelineConfig): Map[String, String] = {
        var ctx = Map.empty[String, String]
        if (p.catalog != null && p.catalog.nonEmpty) ctx += ("catalog" -> p.catalog)
        if (p.createdByKeyLabel != null && p.createdByKeyLabel.nonEmpty) ctx += ("owner" -> p.createdByKeyLabel)
        ctx
    }

    private def tapScopeContext(t: TapConfig): Map[String, String] = {
        var ctx = Map.empty[String, String]
        if (t.catalog != null && t.catalog.nonEmpty) ctx += ("catalog" -> t.catalog)
        if (t.createdByKeyLabel != null && t.createdByKeyLabel.nonEmpty) ctx += ("owner" -> t.createdByKeyLabel)
        ctx
    }
}
