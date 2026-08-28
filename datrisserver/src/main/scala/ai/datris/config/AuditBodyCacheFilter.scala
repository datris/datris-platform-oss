package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import jakarta.servlet.FilterChain
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper

/** Wraps small JSON write requests so [[AuditInterceptor]] can read the entity
  * name (`name` / `username` / `label`) from the body after the controller has
  * consumed it. Most Datris write routes carry the entity name in the body
  * rather than the path.
  *
  * The wrapper only caches what the controller actually reads and never
  * changes what the controller sees. Multipart uploads and large bodies are
  * left untouched. Inert while `useAuditLog` is off. */
@Component
class AuditBodyCacheFilter extends OncePerRequestFilter {

    private val MaxBodyBytes = 64 * 1024

    override def shouldNotFilter(request: HttpServletRequest): Boolean = {
        if (!AuditLog.enabled) return true
        val method = request.getMethod
        val isWrite = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
        val uri = request.getRequestURI
        val ct = Option(request.getContentType).getOrElse("").toLowerCase
        val len = request.getContentLengthLong
        !(isWrite && uri != null && uri.startsWith("/api/") && ct.contains("application/json") && len >= 0 && len <= MaxBodyBytes)
    }

    override def doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain): Unit = {
        chain.doFilter(new ContentCachingRequestWrapper(request, MaxBodyBytes), response)
    }
}
