package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditClassifier, AuditEntry, AuditLog}
import ai.datris.model.DatrisEnvironment
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.{HandlerInterceptor, HandlerMapping}
import org.springframework.web.util.{ContentCachingRequestWrapper, WebUtils}

import java.time.Instant
import scala.collection.JavaConverters._

/** Records one audit entry per audited `/api/...` request.
  *
  * MUST be registered LAST in [[WebMvcConfig]]: `afterCompletion` runs in
  * reverse registration order, so being last means this runs BEFORE
  * [[SessionAuthenticator]] clears UserContext and [[TenantInterceptor]]
  * clears TenantContext — identity and tenant are still readable here.
  *
  * A request refused by an earlier interceptor never reaches this one (a
  * `false` preHandle stops the chain), which is why the denying interceptors
  * call [[AuditLog.denied]] themselves. */
@Component
class AuditInterceptor extends HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(getClass)
    private val StartAttr = "ai.datris.audit.startNanos"

    /** Body fields tried, in order, when the entity name is not in the path
      * or query string. Only the name is extracted; the body is never stored. */
    private val NameFields = Seq("name", "username", "label", "tapName", "pipelineName")
    private val PathVarNames = Seq("name", "username", "label")

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (AuditLog.enabled)
            request.setAttribute(StartAttr, java.lang.Long.valueOf(System.nanoTime()))
        true
    }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        if (!AuditLog.enabled) return
        if (request.getAttribute(AuditLog.RecordedAttr) != null) return
        try {
            val logReads = Option(DatrisEnvironment.values).exists(_.auditLogLogReads)
            AuditClassifier.classify(request.getMethod, request.getRequestURI, logReads).foreach { route =>
                val status = response.getStatus
                val outcome =
                    if (status == 401 || status == 403) "denied"
                    else if (status >= 200 && status < 400 && ex == null) "success"
                    else "failure"
                val start = Option(request.getAttribute(StartAttr)).collect { case l: java.lang.Long => l.longValue() }
                val durationMs = start.map(s => (System.nanoTime() - s) / 1000000L)
                val errorMessage =
                    Option(request.getAttribute(AuditLog.ErrorMessageAttr)).collect { case s: String => s }
                        .orElse(Option(ex).map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(500)))
                        .orElse(if (outcome == "success") None else Some("HTTP " + status))
                val metadata = Option(request.getAttribute(AuditLog.MetadataAttr)).collect { case o: JsonObject => AuditLog.redact(o) }
                // MCP session id forwarded by the MCP server — joins this entry
                // to the Agent Monitor's activity buffer while it still holds it.
                val withSession = Option(request.getHeader(AuditActor.HeaderAgentSession)).map(_.trim).filter(_.nonEmpty).map { sid =>
                    val o = metadata.getOrElse(new JsonObject())
                    o.addProperty("agentSession", sid.take(64))
                    o
                }.orElse(metadata)

                AuditLog.submit(AuditEntry(
                    ts = Instant.now(),
                    actor = AuditActor.resolve(request),
                    category = route.category,
                    action = route.action,
                    resourceType = Some(route.resourceType),
                    resourceName = resourceName(request),
                    outcome = outcome,
                    httpStatus = Some(status),
                    durationMs = durationMs,
                    errorMessage = errorMessage,
                    request = Some(AuditLog.requestInfo(request)),
                    metadata = withSession
                ))
            }
        } catch {
            case e: Exception =>
                logger.debug("Audit interceptor skipped a request: " + e.getMessage)
        }
    }

    /** Path variable → `?name=` → JSON body field. Bodies are only visible
      * when [[AuditBodyCacheFilter]] wrapped the request (JSON, small). */
    private def resourceName(request: HttpServletRequest): Option[String] = {
        val fromPath = Option(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
            .collect { case m: java.util.Map[_, _] => m.asInstanceOf[java.util.Map[String, String]].asScala }
            .flatMap(vars => PathVarNames.flatMap(vars.get).headOption)
        fromPath
            .orElse(Option(request.getParameter("name")).map(_.trim).filter(_.nonEmpty))
            .orElse(fromBody(request))
            .map(_.take(256))
    }

    private def fromBody(request: HttpServletRequest): Option[String] = {
        val wrapper = WebUtils.getNativeRequest(request, classOf[ContentCachingRequestWrapper])
        if (wrapper == null) return None
        val bytes = wrapper.getContentAsByteArray
        if (bytes == null || bytes.isEmpty) return None
        try {
            val el = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            if (!el.isJsonObject) return None
            val obj = el.getAsJsonObject
            NameFields.collectFirst {
                case f if obj.has(f) && obj.get(f).isJsonPrimitive => obj.get(f).getAsString
            }.map(_.trim).filter(_.nonEmpty)
        } catch {
            case _: Exception => None
        }
    }
}
