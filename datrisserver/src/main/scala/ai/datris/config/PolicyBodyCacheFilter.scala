package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.policy.{PolicyGate, PolicyIO}
import jakarta.servlet.{FilterChain, ReadListener, ServletInputStream}
import jakarta.servlet.http.{HttpServletRequest, HttpServletRequestWrapper, HttpServletResponse}
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.util.StreamUtils

import java.io.{BufferedReader, ByteArrayInputStream, InputStreamReader}
import java.nio.charset.StandardCharsets

/** Reads small JSON write bodies up front so PolicyInterceptor can park the
  * request for approval BEFORE the controller consumes the stream. Unlike
  * AuditBodyCacheFilter (which caches lazily as the controller reads), this
  * has to read eagerly — an approval decision needs the whole body while the
  * controller has not run yet. The controller then reads the same bytes
  * from the wrapper, unchanged. Inert while `useAgentPolicy` is off. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PolicyBodyCacheFilter extends OncePerRequestFilter {

    override def shouldNotFilter(request: HttpServletRequest): Boolean = {
        if (!PolicyIO.enabled) return true
        val method = request.getMethod
        val isWrite = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
        val uri = request.getRequestURI
        val ct = Option(request.getContentType).getOrElse("").toLowerCase
        val len = request.getContentLengthLong
        !(isWrite && uri != null && uri.startsWith("/api/") && ct.contains("application/json") && len != 0 && len <= PolicyGate.MaxBodyBytes)
    }

    override def doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain): Unit = {
        val bytes = StreamUtils.copyToByteArray(request.getInputStream)
        if (bytes.length > PolicyGate.MaxBodyBytes) {
            // Chunked body that turned out larger than the cap: hand the
            // bytes on without the attribute, so the request is not queueable.
            chain.doFilter(new CachedBodyRequest(request, bytes), response)
        } else {
            request.setAttribute(PolicyGate.BodyAttr, bytes)
            chain.doFilter(new CachedBodyRequest(request, bytes), response)
        }
    }
}

/** A request whose body is served from a byte array, re-readable. */
class CachedBodyRequest(request: HttpServletRequest, bytes: Array[Byte]) extends HttpServletRequestWrapper(request) {

    override def getInputStream: ServletInputStream = {
        val in = new ByteArrayInputStream(bytes)
        new ServletInputStream {
            override def isFinished: Boolean = in.available() == 0
            override def isReady: Boolean = true
            override def setReadListener(listener: ReadListener): Unit = {}
            override def read(): Int = in.read()
            override def read(b: Array[Byte], off: Int, len: Int): Int = in.read(b, off, len)
        }
    }

    override def getReader: BufferedReader =
        new BufferedReader(new InputStreamReader(getInputStream, Option(request.getCharacterEncoding).getOrElse(StandardCharsets.UTF_8.name())))

    override def getContentLength: Int = bytes.length
    override def getContentLengthLong: Long = bytes.length.toLong
}
