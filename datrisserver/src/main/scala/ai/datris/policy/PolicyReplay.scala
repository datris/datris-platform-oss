package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.model.DatrisEnvironment
import ai.datris.util.SecretsUtil
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpEntityEnclosingRequestBase, HttpPatch, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets

/** Executes an approved pending action by re-sending the original request
  * to this server through the normal interceptor chain, so capability,
  * policy and audit see it exactly like a fresh call.
  *
  * Identity on the replay: the platform's own `ui` key (when keys are on)
  * plus `X-Datris-On-Behalf-Of: <approver>`, which TenantInterceptor turns
  * into `session:<approver>` — the human who clicked Approve is the actor.
  * `X-Datris-Approval: <id>.<token>` lets PolicyInterceptor recognize the
  * replay and consume the single-use token instead of gating it again. */
object PolicyReplay {

    private val logger = LoggerFactory.getLogger(getClass)

    /** Set by StartupRunner from `server.port`. */
    @volatile var port: Int = 8080

    val HeaderApproval = "X-Datris-Approval"

    private class HttpDeleteWithBody(uri: String) extends HttpEntityEnclosingRequestBase {
        setURI(java.net.URI.create(uri))
        override def getMethod: String = "DELETE"
    }

    case class Result(status: Int, body: String)

    def execute(pa: PendingAction, token: String, approver: Option[String]): Result = {
        val url = "http://127.0.0.1:" + port + pa.path + pa.query.map("?" + _).getOrElse("")
        val req: HttpEntityEnclosingRequestBase = pa.method.toUpperCase match {
            case "POST" => new HttpPost(url)
            case "PUT" => new HttpPut(url)
            case "PATCH" => new HttpPatch(url)
            case "DELETE" => new HttpDeleteWithBody(url)
            case other => throw new IllegalArgumentException("Cannot replay HTTP method " + other)
        }
        pa.body.foreach { b =>
            req.setEntity(new StringEntity(b, StandardCharsets.UTF_8))
            req.setHeader("Content-Type", pa.contentType.getOrElse("application/json"))
        }
        req.setHeader(HeaderApproval, pa.id + "." + token)
        approver.foreach(req.setHeader(AuditActor.HeaderOnBehalfOf, _))
        uiApiKey().foreach(req.setHeader("x-api-key", _))
        val config = RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(10 * 60 * 1000).build()
        val client = HttpClients.custom().setDefaultRequestConfig(config).build()
        try {
            val resp = client.execute(req)
            val body = Option(resp.getEntity).map(e => EntityUtils.toString(e, StandardCharsets.UTF_8)).getOrElse("")
            Result(resp.getStatusLine.getStatusCode, body)
        } finally {
            try client.close()
            catch { case _: Exception => }
        }
    }

    /** The reserved `ui` key's value, from Vault. Only needed when API keys
      * are on; in keys-off mode the request is anonymous like any other. */
    private def uiApiKey(): Option[String] = {
        val env = DatrisEnvironment.values
        if (env == null || !env.useApiKeys) return None
        val path = DatrisEnvironment.current.environment + "/ui-api-key"
        val key = SecretsUtil.getSecretMap(path).flatMap(m => Option(m.get("apiKey"))).filter(_.nonEmpty)
        if (key.isEmpty)
            logger.error("Agent policy replay needs the ui API key at " + path + " but it is missing — the approved action cannot be executed")
        key
    }
}
