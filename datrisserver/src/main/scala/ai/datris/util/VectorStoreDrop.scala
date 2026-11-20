package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{HttpURLConnection, SocketTimeoutException, URL}
import java.nio.charset.StandardCharsets
import java.util.Locale
import scala.util.control.NonFatal

/** The pipeline delete's drop of one vector collection (qdrant, milvus,
  * chroma) or class (weaviate): one bounded HTTP call, its answer
  * classified so only a real removal is logged as one, and a closed-set
  * reason for anything else. No Spring, no DatrisEnvironment: the caller
  * reads the store secret and passes it in.
  *
  * Requests are the ones the delete sent before (host, port, scheme, auth
  * header, path), with the name percent-encoded as one path segment
  * (qdrant, weaviate, chroma) or carried in a Gson-built UTF-8 JSON body
  * (milvus), so a delete only ever addresses the collection the pipeline
  * names.
  *
  * The stores' "missing collection" answers (Absent) are their documented
  * ones; they were not recorded against live stores:
  *  - qdrant: DELETE /collections/{name} answers 200 with `"result": false`
  *    when the collection does not exist (`"result": true` when removed).
  *  - weaviate: DELETE /v1/schema/{class} answers 200 whether or not the
  *    class existed, so it has no separate Absent case.
  *  - milvus (REST v2 reference only; no local Milvus): HTTP 200 with
  *    `code` 0 is success; any non-zero `code` is a reported error.
  *  - chroma (v2 API): a missing collection is 404 with
  *    `"error": "NotFoundError"`. A bare 404 (another API version, a proxy)
  *    stays a failure: a false warning is the safe direction.
  *
  * Reasons never carry the host, port, secret, api key, response body or
  * exception text (restricted users read delete warnings); that detail goes
  * to the server log only. */
object VectorStoreDrop {

    private val logger: Logger = LoggerFactory.getLogger(getClass)

    case class Request(method: String, url: String, headers: Seq[(String, String)], body: Option[String])

    sealed trait Outcome
    case object Removed extends Outcome
    case object Absent extends Outcome
    case object NotSent extends Outcome
    case class Failed(reason: String) extends Outcome

    val NotConfigured = "the store is not configured"
    val Unreachable = "the store could not be reached"
    val TimedOut = "the store did not answer in time"
    val ReportedError = "the store reported an error"
    def answeredHttp(status: Int): String = "the store answered HTTP " + status

    val ConnectTimeoutMs = 5000
    val ReadTimeoutMs = 5000
    val MaxBodyBytes: Int = 64 * 1024

    private val ChromaCollections = "/api/v2/tenants/default_tenant/databases/default_database/collections/"

    private def blank(s: String): Boolean = s == null || s.trim.isEmpty

    private val Hex = "0123456789ABCDEF"

    /** `name` as one URL path segment: UTF-8 percent-encoding of every byte
      * outside `[A-Za-z0-9_.~-]` (space is `%20`). */
    def segment(name: String): String = {
        val sb = new java.lang.StringBuilder
        name.getBytes(StandardCharsets.UTF_8).foreach { b =>
            val c = (b & 0xff).toChar
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '~' || c == '-')
                sb.append(c)
            else sb.append('%').append(Hex.charAt((b >> 4) & 0xf)).append(Hex.charAt(b & 0xf))
        }
        sb.toString
    }

    /** The configured port, or `default` when none is set; None when it is
      * not a port number. */
    private def port(secret: java.util.Map[String, String], default: Int): Option[Int] =
        Option(secret.get("port")).filter(!blank(_)) match {
            case None => Some(default)
            case Some(p) => scala.util.Try(p.trim.toInt).toOption.filter(n => n > 0 && n <= 65535)
        }

    private def apiKey(secret: java.util.Map[String, String]): Option[String] = Option(secret.get("apiKey")).filter(_.nonEmpty)

    /** The drop request for `name` in `store`, or the outcome without one:
      * NotSent for a null or blank name; Failed for `.` / `..` (no URL can
      * address them as a collection), a null secret, a blank host, an
      * unparsable port or an unknown store. */
    def request(store: String, secret: java.util.Map[String, String], name: String): Either[Outcome, Request] = {
        if (blank(name)) return Left(NotSent)
        if (name == "." || name == "..") return Left(Failed(Unreachable))
        if (secret == null) return Left(Failed(NotConfigured))
        val host = secret.get("host")
        if (blank(host)) return Left(Failed(NotConfigured))
        store match {
            case "qdrant" =>
                // The secret holds the gRPC port; REST is the port below it.
                port(secret, 6334).map(_ - 1).filter(_ > 0) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(rest) =>
                        Right(Request(
                            "DELETE",
                            "http://" + host + ":" + rest + "/collections/" + segment(name),
                            apiKey(secret).map("api-key" -> _).toSeq,
                            None
                        ))
                }
            case "weaviate" =>
                val scheme = Option(secret.get("scheme")).filter(!blank(_)).getOrElse("http")
                (port(secret, 8079), scheme.toLowerCase(Locale.ROOT)) match {
                    case (Some(p), "http" | "https") =>
                        Right(
                            Request(
                                "DELETE",
                                scheme + "://" + host + ":" + p + "/v1/schema/" + segment(name),
                                apiKey(secret).map(k => "Authorization" -> ("Bearer " + k)).toSeq,
                                None
                            )
                        )
                    case _ => Left(Failed(NotConfigured))
                }
            case "milvus" =>
                port(secret, 19530) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(p) =>
                        val body = new JsonObject
                        body.addProperty("collectionName", name)
                        Right(
                            Request(
                                "POST",
                                "http://" + host + ":" + p + "/v2/vectordb/collections/drop",
                                Seq("Content-Type" -> "application/json") ++ apiKey(secret).map(k => "Authorization" -> ("Bearer " + k)),
                                Some(body.toString)
                            )
                        )
                }
            case "chroma" =>
                port(secret, 8000) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(p) => Right(Request("DELETE", "http://" + host + ":" + p + ChromaCollections + segment(name), Nil, None))
                }
            case _ => Left(Failed(NotConfigured))
        }
    }

    private def json(body: String): Option[JsonObject] =
        try {
            val e = JsonParser.parseString(if (body == null) "" else body)
            if (e != null && e.isJsonObject) Some(e.getAsJsonObject) else None
        } catch { case NonFatal(_) => None }

    private def stringField(o: JsonObject, f: String): Option[String] =
        Option(o.get(f)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString)

    /** The store's answer to a drop. Any non-2xx status is Failed with the
      * status, except the store's own missing-collection answer (Absent). */
    def classify(store: String, status: Int, body: String): Outcome = {
        val ok = status >= 200 && status < 300
        store match {
            case "qdrant" if ok =>
                json(body).flatMap(o => Option(o.get("result"))).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isBoolean) match {
                    case Some(r) if r.getAsBoolean => Removed
                    case Some(_) => Absent
                    case None => Failed(ReportedError)
                }
            case "weaviate" if ok => Removed
            case "milvus" if ok =>
                json(body).flatMap(o => Option(o.get("code"))).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isNumber) match {
                    case Some(c) if scala.util.Try(c.getAsLong).toOption.contains(0L) => Removed
                    case _ => Failed(ReportedError)
                }
            case "chroma" if ok => Removed
            case "chroma" if status == 404 && json(body).flatMap(stringField(_, "error")).contains("NotFoundError") => Absent
            case _ if status >= 100 && status <= 999 && !ok => Failed(answeredHttp(status))
            case _ => Failed(ReportedError)
        }
    }

    private def readCapped(in: InputStream): String =
        if (in == null) ""
        else {
            val out = new ByteArrayOutputStream
            val buf = new Array[Byte](8192)
            var n = in.read(buf, 0, math.min(buf.length, MaxBodyBytes))
            while (n > 0 && out.size < MaxBodyBytes) {
                out.write(buf, 0, n)
                n = in.read(buf, 0, math.min(buf.length, MaxBodyBytes - out.size))
            }
            new String(out.toByteArray, StandardCharsets.UTF_8)
        }

    /** One bounded HTTP call: 5 s connect, 5 s read, redirects not
      * followed, at most 64 KB of the answer read. Streams are closed and
      * the connection disconnected whatever happens. Throws the I/O error. */
    def send(r: Request): (Int, String) = {
        val conn = new URL(r.url).openConnection().asInstanceOf[HttpURLConnection]
        try {
            conn.setInstanceFollowRedirects(false)
            conn.setConnectTimeout(ConnectTimeoutMs)
            conn.setReadTimeout(ReadTimeoutMs)
            conn.setUseCaches(false)
            conn.setRequestMethod(r.method)
            r.headers.foreach { case (k, v) => conn.setRequestProperty(k, v) }
            r.body.foreach { b =>
                conn.setDoOutput(true)
                val os = conn.getOutputStream
                try os.write(b.getBytes(StandardCharsets.UTF_8))
                finally os.close()
            }
            val status = conn.getResponseCode
            val in = if (status >= 400) conn.getErrorStream else scala.util.Try(conn.getInputStream).getOrElse(conn.getErrorStream)
            val body =
                try readCapped(in)
                finally if (in != null) in.close()
            (status, body)
        } finally conn.disconnect()
    }

    /** Request, send and classify; never throws. A timeout is "did not
      * answer in time", any other failure to talk to the store "could not
      * be reached". The detail is logged here, never returned. */
    def drop(store: String, secret: java.util.Map[String, String], name: String): Outcome =
        try {
            request(store, secret, name) match {
                case Left(o) => o
                case Right(r) =>
                    try {
                        val (status, body) = send(r)
                        val o = classify(store, status, body)
                        o match {
                            case Failed(_) =>
                                logger.warn(
                                    "Vector store drop: " + store + " answered HTTP " + status + " for '" + name + "': " +
                                        (if (body.length > 500) body.take(500) + "..." else body)
                                )
                            case _ =>
                        }
                        o
                    } catch {
                        case e: SocketTimeoutException =>
                            logger.warn("Vector store drop: " + store + " did not answer for '" + name + "': " + e.getMessage)
                            Failed(TimedOut)
                        case e: IOException =>
                            logger.warn("Vector store drop: " + store + " could not be reached for '" + name + "': " + e)
                            Failed(Unreachable)
                    }
            }
        } catch {
            case NonFatal(e) =>
                logger.warn("Vector store drop: " + store + " request for '" + name + "' failed: " + e)
                Failed(Unreachable)
        }

    /** The delete warning for a container that could not be removed. `key`
      * should be the display key (weaviate: the class as configured). */
    def warning(key: VectorContainers.Key, reason: String, configDeleted: Boolean): String =
        "Could not remove " + VectorContainers.label(key) + ": " + reason + ". " +
            (if (configDeleted) "Its pipeline configuration was deleted; remove it in the store."
             else "Run the reset again once the store is available.")
}
