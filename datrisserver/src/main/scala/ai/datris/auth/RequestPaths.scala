package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.{PathContainer, RequestPath}
import org.springframework.web.util.ServletRequestPathUtils

import scala.collection.JavaConverters._

/** The one place an access decision reads a request's route from.
  *
  * Spring MVC (Framework 7, PathPatternParser) picks the handler by matching
  * the path within the application segment by segment, each segment
  * percent-decoded with its `;` path parameters removed
  * (`PathContainer.PathSegment.valueToMatch`), case-sensitively and with no
  * trailing-slash matching. The raw `getRequestURI` is not that string. Every
  * decision that depends on the route (the capability gate, the agent policy,
  * the role gate, audit classification, the body-caching filters) uses
  * [[path]] and [[method]], so a request is matched for access checks the same
  * way it is matched for routing.
  *
  * [[path]] reads the request path the DispatcherServlet parsed and cached
  * (present in every HandlerInterceptor); before dispatch (servlet filters) it
  * parses the request the same way. Segments are joined with `/`; a decoded
  * `/` inside one segment (an encoded slash, if the container lets it through)
  * is written back as `%2F` so the segment stays one segment, as the
  * dispatcher saw it. Nothing else is rewritten: mixed case, spaces, `;`
  * written as `%3B` and every other character of a pipeline or catalog name
  * are kept as decoded. */
object RequestPaths {

    /** The decoded, parameter-free path within the application that the
      * dispatcher matches handlers against. Null for a null request or one
      * with no request URI. */
    def path(request: HttpServletRequest): String = {
        if (request == null) return null
        val parsed: RequestPath =
            if (ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.getParsedRequestPath(request)
            else {
                val uri = request.getRequestURI
                if (uri == null) return null
                val ctx = Option(request.getContextPath).getOrElse("")
                try RequestPath.parse(uri, if (uri.startsWith(ctx)) ctx else "")
                catch { case _: IllegalArgumentException => RequestPath.parse(uri, "") }
            }
        canonical(parsed.pathWithinApplication())
    }

    /** `container` as the matched string: each segment's `valueToMatch`
      * (decoded, path parameters removed), separators kept. */
    def canonical(container: PathContainer): String =
        container.elements().asScala.map {
            case s: PathContainer.PathSegment => s.valueToMatch().replace("/", "%2F")
            case sep => sep.value()
        }.mkString

    /** The method an access decision uses: upper case, with `HEAD` decided as
      * `GET` (the dispatcher runs the GET handler for a HEAD request). Null
      * for a null request or method. */
    def method(request: HttpServletRequest): String =
        if (request == null) null else method(request.getMethod)

    /** [[method]] on a method name. */
    def method(name: String): String =
        if (name == null) null
        else {
            val m = name.toUpperCase(java.util.Locale.ROOT)
            if (m == "HEAD") "GET" else m
        }
}
