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

    /** Whole-call bound for one drop: the read timeout is per read, so a
      * store trickling its answer is cut off here. */
    val TotalDeadlineMs = 10000L

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

    /** Where and how `store` is reached, from its secret: the base URL
      * (scheme, host, port; qdrant's REST port is the secret's gRPC port
      * minus one) and the auth headers (qdrant `api-key`, weaviate and milvus
      * `Authorization: Bearer`, chroma none). Shared by the delete's drop and
      * the save's existence probe (`DestinationProbe.vectorContainerRequest`),
      * so both address the store exactly alike. Left `Failed` for a null
      * secret, a blank host, an unusable port or scheme, or an unknown store. */
    private[util] def endpoint(store: String, secret: java.util.Map[String, String]): Either[Failed, (String, Seq[(String, String)])] = {
        if (secret == null) return Left(Failed(NotConfigured))
        val host = secret.get("host")
        if (blank(host)) return Left(Failed(NotConfigured))
        def bearer: Seq[(String, String)] = apiKey(secret).map(k => "Authorization" -> ("Bearer " + k)).toSeq
        store match {
            case "qdrant" =>
                port(secret, 6334).map(_ - 1).filter(_ > 0) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(rest) => Right(("http://" + host + ":" + rest, apiKey(secret).map("api-key" -> _).toSeq))
                }
            case "weaviate" =>
                val scheme = Option(secret.get("scheme")).filter(!blank(_)).getOrElse("http")
                (port(secret, 8079), scheme.toLowerCase(Locale.ROOT)) match {
                    case (Some(p), "http" | "https") => Right((scheme + "://" + host + ":" + p, bearer))
                    case _ => Left(Failed(NotConfigured))
                }
            case "milvus" =>
                port(secret, 19530) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(p) => Right(("http://" + host + ":" + p, Seq("Content-Type" -> "application/json") ++ bearer))
                }
            case "chroma" =>
                port(secret, 8000) match {
                    case None => Left(Failed(NotConfigured))
                    case Some(p) => Right(("http://" + host + ":" + p, Nil))
                }
            case _ => Left(Failed(NotConfigured))
        }
    }

    /** The chroma collections path under the default tenant and database. */
    private[util] val ChromaCollectionsPath: String = ChromaCollections.stripSuffix("/")

    /** `{"collectionName": name}` as Gson builds it (escaped; UTF-8 when sent). */
    private[util] def milvusNameBody(name: String): String = {
        val body = new JsonObject
        body.addProperty("collectionName", name)
        body.toString
    }

    /** The drop request for `name` in `store`, or the outcome without one:
      * NotSent for a null or blank name; Failed for `.` / `..` (no URL can
      * address them as a collection), a null secret, a blank host, an
      * unparsable port or an unknown store. */
    def request(store: String, secret: java.util.Map[String, String], name: String): Either[Outcome, Request] = {
        if (blank(name)) return Left(NotSent)
        if (name == "." || name == "..") return Left(Failed(ReportedError))
        endpoint(store, secret).map { case (base, headers) =>
            store match {
                case "qdrant" => Request("DELETE", base + "/collections/" + segment(name), headers, None)
                case "weaviate" => Request("DELETE", base + "/v1/schema/" + segment(name), headers, None)
                case "milvus" => Request("POST", base + "/v2/vectordb/collections/drop", headers, Some(milvusNameBody(name)))
                case _ => Request("DELETE", base + ChromaCollections + segment(name), headers, None)
            }
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

    /** Past the deadline (System.nanoTime), or once the calling thread is
      * interrupted (an abandoned probe worker), a SocketTimeoutException. */
    private def checkDeadline(deadlineNanos: Long): Unit =
        if (System.nanoTime() - deadlineNanos > 0 || Thread.currentThread().isInterrupted)
            throw new SocketTimeoutException("the store's answer did not finish within the deadline")

    /** The answer was longer than the caller's cap and the caller asked
      * for that to fail (`send(..., failOverCap = true)`). */
    class AnswerTooLarge(cap: Int) extends IOException("answer too large (over " + cap + " bytes)")

    /** At most `cap` bytes of `in`. Over the cap: the first `cap` bytes, or
      * [[AnswerTooLarge]] when `failOverCap`. */
    private def readCapped(in: InputStream, deadlineNanos: Long, cap: Int, failOverCap: Boolean): String =
        if (in == null) ""
        else {
            val out = new ByteArrayOutputStream
            val buf = new Array[Byte](8192)
            checkDeadline(deadlineNanos)
            // One byte past the cap tells "exactly cap" from "more than cap".
            val limit = if (failOverCap) cap.toLong + 1 else cap.toLong
            var n = in.read(buf, 0, math.min(buf.length.toLong, limit).toInt)
            while (n > 0 && out.size < limit) {
                out.write(buf, 0, n)
                if (failOverCap && out.size > cap) throw new AnswerTooLarge(cap)
                checkDeadline(deadlineNanos)
                n = in.read(buf, 0, math.min(buf.length.toLong, limit - out.size).toInt)
            }
            new String(out.toByteArray, StandardCharsets.UTF_8)
        }

    /** One bounded HTTP call: 5 s connect, 5 s per read, `deadlineMs` for
      * the whole answer (checked between reads), redirects not followed, at
      * most `maxBodyBytes` of the answer read (64 KB for a drop, whose answer
      * is small; over it the rest is not read, or with `failOverCap` the call
      * throws [[AnswerTooLarge]]). Streams are closed and the connection
      * disconnected whatever happens. Throws the I/O error (a
      * SocketTimeoutException past the deadline). */
    def send(r: Request, deadlineMs: Long = TotalDeadlineMs, maxBodyBytes: Int = MaxBodyBytes, failOverCap: Boolean = false): (Int, String) = {
        val deadlineNanos = System.nanoTime() + deadlineMs * 1000000L
        if (deadlineMs <= 0) throw new SocketTimeoutException("the store's answer did not finish within the deadline")
        // Never wait on one connect or read past the whole-call deadline.
        val perStepMs = math.max(1L, math.min(deadlineMs, ConnectTimeoutMs.toLong)).toInt
        val conn = new URL(r.url).openConnection().asInstanceOf[HttpURLConnection]
        try {
            conn.setInstanceFollowRedirects(false)
            conn.setConnectTimeout(math.min(ConnectTimeoutMs, perStepMs))
            conn.setReadTimeout(math.min(ReadTimeoutMs, perStepMs))
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
            checkDeadline(deadlineNanos)
            val in = if (status >= 400) conn.getErrorStream else scala.util.Try(conn.getInputStream).getOrElse(conn.getErrorStream)
            val body =
                try readCapped(in, deadlineNanos, maxBodyBytes, failOverCap)
                finally if (in != null) in.close()
            (status, body)
        } finally conn.disconnect()
    }

    /** Request, send and classify; never throws. A timeout is "did not
      * answer in time", any other failure to talk to the store "could not
      * be reached". The detail is logged here, never returned. */
    def drop(store: String, secret: java.util.Map[String, String], name: String, deadlineMs: Long = TotalDeadlineMs): Outcome =
        try {
            request(store, secret, name) match {
                case Left(o) => o
                case Right(r) =>
                    try {
                        val (status, body) = send(r, deadlineMs)
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
            // A drop that outlasted the timeout may still finish in the store.
            (if (reason == TimedOut) "The removal may still complete in the store; check it there. " else "") +
            (if (configDeleted) "Its pipeline configuration was deleted; remove it in the store."
             else "Run the reset again once the store is available.")
}
