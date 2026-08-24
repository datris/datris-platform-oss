package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.auth.MCPToolRoutes
import ai.datris.config.TenantInterceptor
import ai.datris.model.ResolvedKey
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** Answers "which MCP tools may this key see" for the MCP server's
  * `list_tools`, from the same capability tables the CapabilityInterceptor
  * enforces at call time. The route sits in CapabilityRoutes' skip list —
  * any valid key may ask about itself — but a request that resolves to no
  * key at all (keys on, header missing or invalid) is rejected here. */
@RestController
@RequestMapping(Array("/api/v1"))
class MCPToolsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MCPToolsAPIController])
    private val gson = new Gson

    @GetMapping(path = Array("/mcp/tools"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listMcpTools(request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /api/v1/mcp/tools called")
            request.getAttribute(TenantInterceptor.ResolvedKeyAttr) match {
                case rk: ResolvedKey =>
                    // Legacy `*:*` — includes the anonymous identity when
                    // useApiKeys=false — sees the unfiltered catalog.
                    val filtered = !rk.isLegacyFullAccess
                    val tools = if (filtered) MCPToolRoutes.allowedTools(rk) else MCPToolRoutes.allToolNames
                    val body = Map(
                        "filtered" -> java.lang.Boolean.valueOf(filtered),
                        "tools" -> tools.asJava
                    ).asJava
                    new ResponseEntity[String](gson.toJson(body), HttpStatus.OK)
                case _ =>
                    // TenantInterceptor attaches a ResolvedKey whenever the key
                    // resolves (including anonymous mode); absence means keys
                    // are required and this one is missing or invalid.
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body[String]("""{"error":"x-api-key does not exist or is invalid"}""")
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
