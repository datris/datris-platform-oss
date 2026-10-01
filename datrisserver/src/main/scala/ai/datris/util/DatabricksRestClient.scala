package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import ai.datris.util.aiutil.AIHttp
import com.google.gson.{JsonObject, JsonParser}
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpGet, HttpPatch, HttpPost, HttpRequestBase}
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLContext

/** One HTTP exchange: `(method, url, headers, body) => (status, body)`.
  * `body` is null for a GET. The seam the REST client is tested through. */
trait HttpTransport {
    def apply(method: String, url: String, headers: Map[String, String], body: String): (Int, String)
}

/** Default transport: one pooled TLS client, same pool limits as the shared
  * AI client (AIHttp), 30 s connect / 60 s socket timeouts. */
object ApacheTransport extends HttpTransport {
    private val requestConfig: RequestConfig = RequestConfig
        .custom()
        .setConnectTimeout(30000)
        .setConnectionRequestTimeout(30000)
        .setSocketTimeout(60000)
        .build()

    private lazy val client: CloseableHttpClient = {
        val sslsf = new SSLConnectionSocketFactory(
            SSLContext.getDefault,
            Array("TLSv1.2"),
            null,
            SSLConnectionSocketFactory.getDefaultHostnameVerifier
        )
        HttpClients
            .custom()
            .setSSLSocketFactory(sslsf)
            .setMaxConnPerRoute(AIHttp.maxConnPerRoute)
            .setMaxConnTotal(AIHttp.maxConnTotal)
            .setDefaultRequestConfig(requestConfig)
            .build()
    }

    def apply(method: String, url: String, headers: Map[String, String], body: String): (Int, String) = {
        val req: HttpRequestBase = method.toUpperCase match {
            case "GET" => new HttpGet(url)
            case "POST" =>
                val p = new HttpPost(url)
                if (body != null) p.setEntity(new StringEntity(body, StandardCharsets.UTF_8))
                p
            case "PATCH" =>
                val p = new HttpPatch(url)
                if (body != null) p.setEntity(new StringEntity(body, StandardCharsets.UTF_8))
                p
            case other => throw new IllegalArgumentException("Unsupported HTTP method " + other)
        }
        headers.foreach { case (k, v) => req.setHeader(k, v) }
        val resp = client.execute(req)
        try {
            val entity = resp.getEntity
            val text = if (entity == null) "" else EntityUtils.toString(entity, StandardCharsets.UTF_8)
            (resp.getStatusLine.getStatusCode, text)
        } finally resp.close()
    }
}

/** A non-2xx answer from the workspace REST API. `status` lets callers treat
  * an expected 404 (object not created yet) differently from a failure;
  * `body` is the raw response body when the translator kept it (null
  * otherwise), for callers that word their own message. */
class DatabricksHttpException(val status: Int, message: String, val body: String = null) extends DatrisException(message)

/** Minimal Databricks workspace REST client (lineage tracking, Iceberg REST
  * register). Auth: OAuth M2M (client-credentials against
  * `{host}/oidc/v1/token`, token cached until 60 s before expiry) when the
  * secret carries clientId and clientSecret, otherwise the personal access
  * token as a Bearer. With neither (host-only credentials, used only by the
  * Iceberg register against an unauthenticated catalog) requests carry no
  * Authorization header; `token()` still throws. `translate` turns a non-2xx
  * answer into the exception thrown (default: lineage-worded). */
class DatabricksRestClient(
    creds: ResolvedDatabricksCredentials,
    transport: HttpTransport = ApacheTransport,
    clock: () => Long = System.currentTimeMillis,
    translate: (Int, String) => DatabricksHttpException = DatabricksRestClient.translate
) {
    private val base: String = DatabricksRestClient.baseUrl(Option(creds.host).getOrElse(""))

    private var cachedToken: String = _
    private var cachedUntil: Long = 0L

    private def m2m: Boolean = creds.clientId.exists(_.nonEmpty) && creds.clientSecret.exists(_.nonEmpty)

    def token(): String = synchronized {
        if (!m2m)
            return creds.token.getOrElse(throw new DatrisException("Databricks credentials carry neither clientId/clientSecret nor a token"))
        if (cachedToken != null && clock() < cachedUntil) return cachedToken
        val basic = Base64.getEncoder.encodeToString((creds.clientId.get + ":" + creds.clientSecret.get).getBytes(StandardCharsets.UTF_8))
        val (status, body) = transport(
            "POST",
            base + "/oidc/v1/token",
            Map("Authorization" -> ("Basic " + basic), "Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json"),
            "grant_type=client_credentials&scope=all-apis"
        )
        if (status < 200 || status >= 300)
            throw new DatabricksHttpException(
                status,
                "Databricks OAuth token request failed (status " + status + "); check the service principal clientId/clientSecret and host. " +
                    DatabricksRestClient.head(body)
            )
        val json = JsonParser.parseString(body).getAsJsonObject
        val tok = json.get("access_token").getAsString
        val expiresIn = if (json.has("expires_in") && !json.get("expires_in").isJsonNull) json.get("expires_in").getAsLong else 3600L
        cachedToken = tok
        cachedUntil = clock() + (expiresIn - 60L) * 1000L
        tok
    }

    def get(path: String): JsonObject = call("GET", path, null)
    def post(path: String, json: JsonObject): JsonObject = call("POST", path, json)
    def patch(path: String, json: JsonObject): JsonObject = call("PATCH", path, json)

    /** Full URL for `path` (for messages naming what was called). */
    def url(path: String): String = base + path

    private def anonymous: Boolean = !m2m && creds.token.forall(_.isEmpty)

    private def call(method: String, path: String, json: JsonObject): JsonObject = {
        val auth = if (anonymous) Map.empty[String, String] else Map("Authorization" -> ("Bearer " + token()))
        val headers = auth ++ Map("Accept" -> "application/json") ++
            (if (json != null) Map("Content-Type" -> "application/json") else Map.empty)
        val (status, body) = transport(method, base + path, headers, if (json != null) json.toString else null)
        if (status < 200 || status >= 300) throw translate(status, body)
        if (body == null || body.trim.isEmpty) new JsonObject()
        else {
            val el = JsonParser.parseString(body)
            if (el.isJsonObject) el.getAsJsonObject else new JsonObject()
        }
    }
}

object DatabricksRestClient {
    private val BodyHead = 1000

    private val SchemeRe = "(?i)^(https?)://(.*)$".r

    /** Base URL for `raw` (the secret's `host`). An explicit `http://` or
      * `https://` keeps its scheme, host and a non-default port (a local
      * Iceberg REST catalog is plain http on its own port); path, query,
      * userinfo and the scheme's default port are dropped. A bare host (the
      * usual Databricks shape) becomes `https://` + the normalized host. */
    private[util] def baseUrl(raw: String): String =
        raw.trim match {
            case SchemeRe(scheme, rest) =>
                val sch = scheme.toLowerCase
                val hostPort = rest.replaceFirst("[/?#].*$", "").replaceFirst("^.*@", "")
                val defaultPort = if (sch == "https") ":443" else ":80"
                sch + "://" + (if (hostPort.endsWith(defaultPort)) hostPort.dropRight(defaultPort.length) else hostPort)
            case other =>
                "https://" + DatabricksConnectionUtil.normalizeHost(other).stripSuffix("/")
        }

    private[util] def head(body: String): String =
        Option(body).map(_.trim).filter(_.nonEmpty).map(b => "Response: " + b.take(BodyHead)).getOrElse("")

    /** Actionable message per status; the status is kept on the exception. */
    def translate(status: Int, body: String): DatabricksHttpException = {
        val msg = status match {
            case 401 | 403 =>
                "Databricks rejected the lineage request (status " + status + "): the service principal needs " +
                    "CREATE EXTERNAL METADATA on the metastore (a metastore admin grants it) and MODIFY on the external " +
                    "metadata objects; SELECT/MODIFY on the table. " + head(body)
            case 404 =>
                "Databricks returned 404: External Metadata API not enabled in this workspace or wrong host. " + head(body)
            case _ =>
                "Databricks REST call failed with status " + status + ". " + head(body)
        }
        new DatabricksHttpException(status, msg.trim, body)
    }
}
