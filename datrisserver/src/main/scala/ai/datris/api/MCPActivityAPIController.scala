package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.APIKeyValidator
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class MCPActivityAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MCPActivityAPIController])

    private val mcpBaseUrl: String = Option(System.getenv("MCP_SERVER_URL"))
        .filter(_.nonEmpty)
        .getOrElse("http://mcp-server:3000")

    @GetMapping(path = Array("/mcp/activity"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getActivity(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                    @RequestParam(required = false) since: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val sinceParam = Option(since).filter(_.nonEmpty).getOrElse("0")
            val url = mcpBaseUrl + "/activity?since=" + sinceParam
            val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
            connection.setRequestMethod("GET")
            connection.setConnectTimeout(2000)
            connection.setReadTimeout(3000)

            val body = try {
                val code = connection.getResponseCode
                if(code >= 200 && code < 300) {
                    val stream = connection.getInputStream
                    try scala.io.Source.fromInputStream(stream).mkString
                    finally stream.close()
                } else {
                    "{\"server_time\":0,\"sessions\":[],\"calls\":[],\"error\":\"mcp-server returned " + code + "\"}"
                }
            } finally {
                connection.disconnect()
            }

            val formatted = formatTimestamps(body)
            val enriched = enrichWithTenantNames(formatted)
            new ResponseEntity[String](enriched, HttpStatus.OK)
        }
        catch {
            // Auth failure — surface honestly so the UI can differentiate
            // "your key is invalid" from "the MCP server is down." Both
            // previously returned the same "unreachable" payload, which made
            // an invalid localStorage key look like an infrastructure outage.
            case e: DatrisException =>
                logger.warn("mcp-server activity auth error: " + e.getMessage)
                val payload = "{\"server_time\":0,\"sessions\":[],\"calls\":[],\"error\":\"" +
                    e.getMessage.replace("\"", "'") + "\",\"errorKind\":\"auth\"}"
                new ResponseEntity[String](payload, HttpStatus.OK)
            case e: Exception =>
                logger.warn("mcp-server activity unreachable: " + e.getMessage)
                val empty = "{\"server_time\":0,\"sessions\":[],\"calls\":[],\"error\":\"" +
                    e.getMessage.replace("\"", "'") + "\",\"errorKind\":\"unreachable\"}"
                new ResponseEntity[String](empty, HttpStatus.OK)
        }
    }

    /** Clear the MCP server's activity buffer. The UI's "trash" icon hits this so
      * the cleared state survives page reloads — without this, the buffer replays
      * on the next /activity poll. Live sessions are left untouched. */
    @DeleteMapping(path = Array("/mcp/activity"))
    def clearActivity(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val url = mcpBaseUrl + "/activity"
            val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
            connection.setRequestMethod("DELETE")
            connection.setConnectTimeout(2000)
            connection.setReadTimeout(3000)

            try {
                val code = connection.getResponseCode
                if(code >= 200 && code < 300) {
                    new ResponseEntity[String]("", HttpStatus.NO_CONTENT)
                } else {
                    new ResponseEntity[String]("mcp-server returned " + code, HttpStatus.BAD_GATEWAY)
                }
            } finally {
                connection.disconnect()
            }
        } catch {
            case e: Exception =>
                logger.warn("mcp-server activity clear failed: " + e.getMessage)
                new ResponseEntity[String](e.getMessage, HttpStatus.BAD_GATEWAY)
        }
    }

    /** Attach human-readable identifiers to each call/session:
      *   - multi-tenant: api_key_hint → tenant (via the api-key-mappings secret)
      *   - single-tenant: api_key_hint → key_name (via the oss/api-keys secret, which maps
      *     friendly-name → key; reversed to key-prefix → friendly-name)
      * The UI chooses among these using its label priority chain. */
    private def enrichWithTenantNames(body: String): String = {
        try {
            val env = DatrisEnvironment.values
            val hintToTenant: Map[String, String] =
                if(env.multiTenant) loadHintMap("api-key-mappings") else Map.empty
            val hintToKeyName: Map[String, String] =
                if(!env.multiTenant && env.useApiKeys) {
                    loadHintMap(env.apiKeysSecretName, reverseNameKey = true)
                } else Map.empty

            if(hintToTenant.isEmpty && hintToKeyName.isEmpty) return body

            val json = JsonParser.parseString(body).getAsJsonObject
            addLookupField(json, "sessions", hintToTenant, "tenant")
            addLookupField(json, "calls", hintToTenant, "tenant")
            addLookupField(json, "sessions", hintToKeyName, "key_name")
            addLookupField(json, "calls", hintToKeyName, "key_name")
            new Gson().toJson(json)
        } catch {
            case e: Exception =>
                logger.warn("Failed to enrich activity with identity names: " + e.getMessage)
                body
        }
    }

    /** Load a secret map and index it by the first 6 chars of each api key.
      * `reverseNameKey=false` (default): secret is `apiKey → tenant`.
      * `reverseNameKey=true`:            secret is `name → apiKey` (api-keys convention),
      *                                   so we reverse each entry before indexing. */
    private def loadHintMap(secretName: String, reverseNameKey: Boolean = false): Map[String, String] = {
        val mappings = ai.datris.util.SecretsUtil.getSecretMap(secretName)
            .map(_.asScala.toMap).getOrElse(Map.empty[String, String])
        if(mappings.isEmpty) return Map.empty
        val asHintToLabel = mappings.map {
            case (k, v) =>
                val (apiKey, label) = if(reverseNameKey) (v, k) else (k, v)
                (apiKey.take(6), label)
        }
        asHintToLabel
    }

    /** Format the numeric `ts` field on each call (and `first_seen`/`last_seen` on each
      * session) into a human-readable `*_formatted` string using the server's configured
      * dateFormat and dateTimezone, so the UI displays timestamps in the same convention
      * as the rest of the platform. */
    private def formatTimestamps(body: String): String = {
        try {
            val env = DatrisEnvironment.current
            val sdf = new java.text.SimpleDateFormat(env.dateFormat)
            sdf.setTimeZone(java.util.TimeZone.getTimeZone(env.dateTimezone))

            val json = JsonParser.parseString(body).getAsJsonObject
            formatNumericTs(json, "calls", Seq("ts"), sdf)
            formatNumericTs(json, "sessions", Seq("first_seen", "last_seen"), sdf)
            new Gson().toJson(json)
        } catch {
            case e: Exception =>
                logger.warn("Failed to format activity timestamps: " + e.getMessage)
                body
        }
    }

    private def formatNumericTs(root: JsonObject, arrayField: String, tsFields: Seq[String],
                                sdf: java.text.SimpleDateFormat): Unit = {
        if(!root.has(arrayField)) return
        val arr = root.getAsJsonArray(arrayField)
        val iter = arr.iterator()
        while(iter.hasNext) {
            val el = iter.next().getAsJsonObject
            for(field <- tsFields) {
                if(el.has(field) && !el.get(field).isJsonNull) {
                    val seconds = el.get(field).getAsDouble
                    val millis = (seconds * 1000).toLong
                    if(millis > 0) {
                        el.addProperty(field + "_formatted", sdf.format(new java.util.Date(millis)))
                    }
                }
            }
        }
    }

    private def addLookupField(root: JsonObject, arrayField: String,
                               hintToLabel: Map[String, String], targetField: String): Unit = {
        if(hintToLabel.isEmpty) return
        if(!root.has(arrayField)) return
        val arr = root.getAsJsonArray(arrayField)
        val iter = arr.iterator()
        while(iter.hasNext) {
            val el = iter.next().getAsJsonObject
            val hint = if(el.has("api_key_hint")) el.get("api_key_hint").getAsString else ""
            hintToLabel.get(hint).foreach(t => el.addProperty(targetField, t))
        }
    }
}
