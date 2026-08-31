package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{PipelineConfigIO, TapConfigIO}
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.springframework.web.servlet.HandlerMapping

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

/** Request-shape helpers shared by PolicyInterceptor and the approvals
  * controller: which resource a request targets, its current definition
  * version, the cached body, and JSON responses. */
object PolicyGate {

    /** Request attribute holding the pre-read body bytes (see
      * PolicyBodyCacheFilter). Absent for multipart / oversize bodies. */
    val BodyAttr = "ai.datris.policy.body"

    /** Largest body that can be parked for replay. */
    val MaxBodyBytes: Int = 256 * 1024

    private val NameFields = Seq("name", "pipeline", "pipelineName", "tapName", "tap", "label")
    private val PathVarNames = Seq("name", "pipeline", "tapName", "label")

    def bodyBytes(request: HttpServletRequest): Option[Array[Byte]] =
        Option(request.getAttribute(BodyAttr)).collect { case b: Array[Byte] => b }

    def bodyString(request: HttpServletRequest): Option[String] =
        bodyBytes(request).filter(_.nonEmpty).map(b => new String(b, StandardCharsets.UTF_8))

    /** Can this request be stored and replayed verbatim? JSON (or empty)
      * bodies under the size cap only — multipart uploads are not. */
    def isQueueable(request: HttpServletRequest): Boolean = {
        val ct = Option(request.getContentType).getOrElse("").toLowerCase
        val len = request.getContentLengthLong
        val hasBody = len > 0 || (len < 0 && ct.nonEmpty)
        if (!hasBody) return true
        ct.contains("application/json") && len <= MaxBodyBytes && bodyBytes(request).isDefined
    }

    /** Path variable → `?name=` → JSON body field. */
    def resourceName(request: HttpServletRequest): Option[String] = {
        val fromPath = Option(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
            .collect { case m: java.util.Map[_, _] => m.asInstanceOf[java.util.Map[String, String]].asScala }
            .flatMap(vars => PathVarNames.flatMap(vars.get).headOption)
        fromPath
            .orElse(Option(request.getParameter("name")).map(_.trim).filter(_.nonEmpty))
            .orElse(fromBody(request))
            .map(_.take(256))
    }

    private def fromBody(request: HttpServletRequest): Option[String] =
        bodyString(request).flatMap { s =>
            try {
                val el = JsonParser.parseString(s)
                if (!el.isJsonObject) None
                else {
                    val obj = el.getAsJsonObject
                    NameFields.collectFirst {
                        case f if obj.has(f) && obj.get(f).isJsonPrimitive => obj.get(f).getAsString
                    }.map(_.trim).filter(_.nonEmpty)
                }
            } catch {
                case _: Exception => None
            }
        }

    /** The live definition version of a pipeline or tap, for the
      * approve-what-was-proposed check. None when the resource does not
      * exist yet (creates) or the type is not versioned. */
    def currentVersion(resourceType: String, name: String): Option[Int] =
        try {
            val env = DatrisEnvironment.current
            resourceType match {
                case "pipeline" => Option(PipelineConfigIO.read(env.pipelineTableName, name)).map(_.version)
                case "tap" => Option(TapConfigIO.read(env.tapTableName, name)).map(_.version)
                case _ => None
            }
        } catch {
            case _: Exception => None
        }

    def writeJson(response: HttpServletResponse, status: Int, body: JsonObject): Unit = {
        response.setStatus(status)
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.getWriter.write(body.toString)
        response.getWriter.flush()
    }
}
