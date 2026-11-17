package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.auth.MCPToolRoutes
import ai.datris.config.TenantInterceptor
import ai.datris.model.{ResolvedKey, User, UserContext}
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

object MCPToolsAPIController {

    /** The identity whose tools are listed. A ResolvedKey already attached
      * by the interceptors (a valid key, anonymous mode, a restricted
      * session, or the `ui` key + on-behalf-of) wins as is. Otherwise a
      * presented-but-rejected x-api-key yields no identity, even with a
      * session. Otherwise a logged-in session counts as identity, with its
      * role bundle (the same one CapabilityInterceptor enforces for it). */
    private[api] def identity(attr: AnyRef, keyRejected: Boolean, sessionUser: Option[User]): Option[ResolvedKey] =
        attr match {
            case rk: ResolvedKey => Some(rk)
            case _ if keyRejected => None
            case _ => sessionUser.map(APIKeyValidator.resolveFromSession)
        }
}

/** Answers "which MCP tools may this caller see" for the MCP server's
  * `list_tools`, from the same capability tables the CapabilityInterceptor
  * enforces at call time. The route sits in CapabilityRoutes' skip list —
  * any valid key or logged-in session may ask about itself — so the
  * interceptor's lazy session fallback never runs here; the controller
  * resolves a cookie-only session itself. A request with no key and no
  * session, or with a rejected key, is rejected here. */
@RestController
@RequestMapping(Array("/api/v1"))
class MCPToolsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MCPToolsAPIController])
    private val gson = new Gson

    @GetMapping(path = Array("/mcp/tools"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listMcpTools(request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /api/v1/mcp/tools called")
            val who = MCPToolsAPIController.identity(
                request.getAttribute(TenantInterceptor.ResolvedKeyAttr),
                request.getAttribute(TenantInterceptor.ApiKeyRejectedAttr) != null,
                UserContext.get()
            )
            who match {
                case Some(rk) =>
                    // Legacy `*:*` — includes the anonymous identity when
                    // useApiKeys=false — sees the unfiltered catalog.
                    val filtered = !rk.isLegacyFullAccess
                    val tools = if (filtered) MCPToolRoutes.allowedTools(rk) else MCPToolRoutes.allToolNames
                    val body = Map(
                        "filtered" -> java.lang.Boolean.valueOf(filtered),
                        "tools" -> tools.asJava
                    ).asJava
                    new ResponseEntity[String](gson.toJson(body), HttpStatus.OK)
                case None =>
                    // No attached ResolvedKey (keys required and the header is
                    // missing or invalid) and no usable session: either no one
                    // is logged in, or the presented key was rejected.
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body[String](ApiErrors.AuthenticationRequiredBody)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}
