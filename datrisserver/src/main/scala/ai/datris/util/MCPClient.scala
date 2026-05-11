package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import ai.datris.model.DatrisException
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

/** HTTP/JSON-RPC client for the Datris MCP server.
  *
  * The MCP server (mcp-server/server.py) runs in --streamable-http mode with
  * stateless=True, so every POST to /mcp is an independent JSON-RPC request.
  * No initialize handshake or session ID required.
  *
  * The server can respond with either application/json (one-shot reply) or
  * text/event-stream (SSE with one or more events). We accept both and parse
  * whichever arrives. tools/list, resources/list, resources/read and tools/call
  * are all request/response — we just look for the first JSON-RPC envelope
  * matching our request id.
  *
  * The tenant's x-api-key is forwarded verbatim so the MCP server's per-session
  * key resolution works the same as for external clients (Claude Desktop / Cursor).
  */
object MCPClient {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val mcpBaseUrl: String = Option(System.getenv("MCP_SERVER_URL"))
        .filter(_.nonEmpty)
        .getOrElse("http://mcp-server:3000")

    private lazy val httpClient: CloseableHttpClient = HttpClients.createDefault()

    // Tool / resource catalogs change only when mcp-server redeploys. Cache for 60s
    // so the Assistant /init endpoint is effectively free after the first hit.
    private case class CachedEntry[T](value: T, expiresAt: Long)
    private val toolsCache:     ConcurrentHashMap[String, CachedEntry[List[JsonObject]]] = new ConcurrentHashMap()
    private val resourcesCache: ConcurrentHashMap[String, CachedEntry[List[JsonObject]]] = new ConcurrentHashMap()
    private val resourceCache:  ConcurrentHashMap[String, CachedEntry[String]]            = new ConcurrentHashMap()
    private val ttlMillis: Long = 60 * 1000L

    private def cacheKey(apiKey: String, suffix: String): String =
        (if (apiKey == null) "" else apiKey.take(6)) + "|" + suffix

    private def now(): Long = System.currentTimeMillis()

    /** Fetch `tools/list` from the MCP server. Each tool is a JsonObject with
      * `name`, `description`, `inputSchema`. Cached ~60s per api-key prefix. */
    def listTools(apiKey: String): List[JsonObject] = {
        val key = cacheKey(apiKey, "tools")
        Option(toolsCache.get(key)).filter(_.expiresAt > now()).map(_.value) match {
            case Some(cached) => cached
            case None =>
                val tools = doListTools(apiKey)
                toolsCache.put(key, CachedEntry(tools, now() + ttlMillis))
                tools
        }
    }

    /** Fetch `resources/list`. Each resource has `uri`, `name`, `description`, `mimeType`. */
    def listResources(apiKey: String): List[JsonObject] = {
        val key = cacheKey(apiKey, "resources")
        Option(resourcesCache.get(key)).filter(_.expiresAt > now()).map(_.value) match {
            case Some(cached) => cached
            case None =>
                val resources = doListResources(apiKey)
                resourcesCache.put(key, CachedEntry(resources, now() + ttlMillis))
                resources
        }
    }

    /** Read a resource by URI. Returns the concatenated text content of all
      * content blocks. For the canonical Datris workflow reference, this is
      * `datris://pipeline-config-reference`. */
    def readResource(uri: String, apiKey: String): String = {
        val key = cacheKey(apiKey, "resource:" + uri)
        Option(resourceCache.get(key)).filter(_.expiresAt > now()).map(_.value) match {
            case Some(cached) => cached
            case None =>
                val text = doReadResource(uri, apiKey)
                resourceCache.put(key, CachedEntry(text, now() + ttlMillis))
                text
        }
    }

    /** Invoke a tool. `args` is the tool's input as a JsonObject; the return is
      * the tool's `result.content` (typically one or more text blocks
      * concatenated). On a tool error (`isError: true`), throws a
      * DatrisException with the error text so the agent loop can feed the
      * failure back to the model. */
    def callTool(name: String, args: JsonObject, apiKey: String): String = {
        val params = new JsonObject()
        params.addProperty("name", name)
        params.add("arguments", if (args == null) new JsonObject() else args)
        val result = rpcCall("tools/call", params, apiKey)

        val contentArr = result.getAsJsonArray("content")
        val text =
            if (contentArr == null) ""
            else {
                val sb = new StringBuilder
                contentArr.asScala.foreach { el =>
                    val obj = el.getAsJsonObject
                    val t = if (obj.has("type")) obj.get("type").getAsString else ""
                    if (t == "text" && obj.has("text")) {
                        if (sb.nonEmpty) sb.append("\n")
                        sb.append(obj.get("text").getAsString)
                    }
                }
                sb.toString
            }

        val isError = result.has("isError") && result.get("isError").getAsBoolean
        if (isError)
            throw new DatrisException("MCP tool '" + name + "' returned error: " +
                (if (text.nonEmpty) text else "(no detail)"))
        text
    }

    private def doListTools(apiKey: String): List[JsonObject] = {
        val result = rpcCall("tools/list", new JsonObject(), apiKey)
        val arr = result.getAsJsonArray("tools")
        if (arr == null) Nil
        else arr.asScala.map(_.getAsJsonObject).toList
    }

    private def doListResources(apiKey: String): List[JsonObject] = {
        val result = rpcCall("resources/list", new JsonObject(), apiKey)
        val arr = result.getAsJsonArray("resources")
        if (arr == null) Nil
        else arr.asScala.map(_.getAsJsonObject).toList
    }

    private def doReadResource(uri: String, apiKey: String): String = {
        val params = new JsonObject()
        params.addProperty("uri", uri)
        val result = rpcCall("resources/read", params, apiKey)
        val contents = result.getAsJsonArray("contents")
        if (contents == null) return ""
        val sb = new StringBuilder
        contents.asScala.foreach { el =>
            val obj = el.getAsJsonObject
            if (obj.has("text")) {
                if (sb.nonEmpty) sb.append("\n")
                sb.append(obj.get("text").getAsString)
            }
        }
        sb.toString
    }

    /** Send a JSON-RPC request and return the `result` object (not the full envelope).
      * Handles both application/json and text/event-stream responses transparently. */
    private def rpcCall(method: String, params: JsonObject, apiKey: String): JsonObject = {
        val id = java.util.UUID.randomUUID().toString
        val envelope = new JsonObject()
        envelope.addProperty("jsonrpc", "2.0")
        envelope.addProperty("id", id)
        envelope.addProperty("method", method)
        envelope.add("params", params)
        val body = envelope.toString

        val url = mcpBaseUrl + "/mcp"
        val httpPost = new HttpPost(url)
        httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
        httpPost.addHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream")
        if (apiKey != null && apiKey.nonEmpty)
            httpPost.addHeader("x-api-key", apiKey)
        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8))

        val response = httpClient.execute(httpPost)
        try {
            val status = response.getStatusLine.getStatusCode
            val contentType = Option(response.getFirstHeader(HttpHeaders.CONTENT_TYPE)).map(_.getValue).getOrElse("")
            val raw = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
            if (status != 200)
                throw new DatrisException("MCP server returned " + status + " for method=" + method + ", body: " + raw.take(800))

            val rpcResponse =
                if (contentType.toLowerCase.contains("event-stream")) parseSseEnvelope(raw, id, method)
                else JsonParser.parseString(raw).getAsJsonObject

            if (rpcResponse.has("error")) {
                val err = rpcResponse.getAsJsonObject("error")
                val msg = if (err.has("message")) err.get("message").getAsString else err.toString
                throw new DatrisException("MCP JSON-RPC error from method=" + method + ": " + msg)
            }
            val resObj = rpcResponse.getAsJsonObject("result")
            if (resObj == null)
                throw new DatrisException("MCP JSON-RPC response had no result for method=" + method)
            resObj
        } finally {
            response.close()
        }
    }

    /** The MCP streamable HTTP transport may return responses as SSE events. We just
      * need the first `data: {...}` line whose JSON-RPC id matches our request.
      * Ignore any other events (e.g. server-initiated notifications). */
    private def parseSseEnvelope(raw: String, expectedId: String, method: String): JsonObject = {
        val lines = raw.split("\n")
        var i = 0
        while (i < lines.length) {
            val line = lines(i)
            if (line.startsWith("data:")) {
                val payload = line.substring(5).trim
                if (payload.nonEmpty && payload != "[DONE]") {
                    try {
                        val obj = JsonParser.parseString(payload).getAsJsonObject
                        if (obj.has("id") && obj.get("id").getAsString == expectedId)
                            return obj
                    } catch {
                        case _: Exception => // skip malformed event
                    }
                }
            }
            i += 1
        }
        throw new DatrisException("MCP SSE response had no JSON-RPC envelope for id=" + expectedId + " method=" + method +
            ", raw body (truncated): " + raw.take(500))
    }
}
