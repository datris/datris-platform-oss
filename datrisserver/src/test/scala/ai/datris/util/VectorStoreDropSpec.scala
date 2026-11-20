package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{ChunkingConfig, Destination, PipelineConfig, WeaviateConfig}
import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.{InetSocketAddress, ServerSocket, URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Story: Pipeline delete: a vector collection that could not be removed is
  * reported, never logged as deleted
  * (plans/stories/vector-store-delete-reports-failure.md), Acceptance,
  * `VectorStoreDropSpec`.
  *
  * Pinned seam (no Spring, no DatrisEnvironment):
  * {{{
  * object VectorStoreDrop {
  *     case class Request(method: String, url: String, headers: Seq[(String, String)], body: Option[String])
  *     sealed trait Outcome; Removed, Absent, NotSent, Failed(reason: String)
  *     def segment(name: String): String
  *     def request(store: String, secret: java.util.Map[String, String], name: String): Either[Outcome, Request]
  *     def classify(store: String, status: Int, body: String): Outcome
  *     def send(r: Request): (Int, String)
  *     def drop(store: String, secret: java.util.Map[String, String], name: String): Outcome
  *     def warning(key: VectorContainers.Key, reason: String, configDeleted: Boolean): String
  * }
  * }}}
  *
  * The stores' "missing collection" answers used for `Absent` below are the
  * documented ones (qdrant: 200 with `"result": false`; chroma v2: 404 with
  * `"error": "NotFoundError"`); Step 3 of the story records the live answers.
  * Weaviate answers a delete of a missing class with the same 200 as a
  * removal, so it has no separate Absent case here. The loopback half runs a
  * real in-process HTTP server on 127.0.0.1 (as TapHttpEndpointSpec); no
  * store, Vault or database is reached. */
class VectorStoreDropSpec extends AnyFunSuite with BeforeAndAfterAll {

    import VectorStoreDrop._

    private val Stores = Seq("qdrant", "weaviate", "milvus", "chroma")

    private val ClosedReasons = Set(
        "the store is not configured",
        "the store could not be reached",
        "the store did not answer in time",
        "the store reported an error"
    )
    private def closedReason(r: String): Boolean = ClosedReasons.contains(r) || r.matches("the store answered HTTP \\d{3}")

    private def secret(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def req(store: String, s: java.util.Map[String, String], name: String): Request =
        request(store, s, name) match {
            case Right(r) => r
            case Left(o) => fail(s"$store '$name': expected a request, got $o")
        }

    private def hdrs(r: Request): Map[String, String] = r.headers.map { case (k, v) => k.toLowerCase(java.util.Locale.ROOT) -> v }.toMap

    private def failedReason(o: Any): String = o match {
        case Failed(r) => r
        case Left(Failed(r)) => r
        case other => fail(s"expected Failed, got $other")
    }

    // ------------------------------------------------------------------ pure

    test(
        "request per store for a plain name: method, URL, port (qdrant gRPC port minus 1; weaviate 8079, milvus 19530, chroma 8000 by default), scheme, auth header and body equal what the delete sent before"
    ) {
        // Qdrant: REST port is the configured gRPC port minus one (default gRPC 6334).
        val q = req("qdrant", secret("host" -> "qd.store", "port" -> "6334", "apiKey" -> "qk"), "docs_1")
        assert(q.method == "DELETE" && q.url == "http://qd.store:6333/collections/docs_1" && q.body.isEmpty, q)
        assert(hdrs(q) == Map("api-key" -> "qk"), q)
        val qDefault = req("qdrant", secret("host" -> "qd.store"), "docs_1")
        assert(qDefault.url == "http://qd.store:6333/collections/docs_1" && qDefault.headers.isEmpty, qDefault)
        assert(req("qdrant", secret("host" -> "qd.store", "port" -> "7334"), "docs_1").url == "http://qd.store:7333/collections/docs_1")
        assert(req("qdrant", secret("host" -> "qd.store", "apiKey" -> ""), "docs_1").headers.isEmpty)

        // Weaviate: port 8079 and scheme http by default; Bearer auth.
        val w = req("weaviate", secret("host" -> "wv.store"), "Docs_1")
        assert(w.method == "DELETE" && w.url == "http://wv.store:8079/v1/schema/Docs_1" && w.body.isEmpty && w.headers.isEmpty, w)
        val wFull = req("weaviate", secret("host" -> "wv.store", "port" -> "8080", "scheme" -> "https", "apiKey" -> "wk"), "Docs_1")
        assert(wFull.url == "https://wv.store:8080/v1/schema/Docs_1", wFull)
        assert(hdrs(wFull) == Map("authorization" -> "Bearer wk"), wFull)

        // Milvus: POST to the drop endpoint, JSON body, port 19530 by default.
        val m = req("milvus", secret("host" -> "mv.store", "apiKey" -> "mk"), "docs_1")
        assert(m.method == "POST" && m.url == "http://mv.store:19530/v2/vectordb/collections/drop", m)
        assert(hdrs(m).get("content-type").exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")), m)
        assert(hdrs(m).get("authorization").contains("Bearer mk"), m)
        assert(hdrs(m).keySet == Set("content-type", "authorization"), m)
        assert(m.body.map(b => JsonParser.parseString(b)).contains(JsonParser.parseString("{\"collectionName\": \"docs_1\"}")), m)
        val mNoKey = req("milvus", secret("host" -> "mv.store", "port" -> "19531"), "docs_1")
        assert(mNoKey.url == "http://mv.store:19531/v2/vectordb/collections/drop" && hdrs(mNoKey).keySet == Set("content-type"), mNoKey)

        // Chroma: port 8000 by default, default tenant and database, no auth.
        val c = req("chroma", secret("host" -> "ch.store", "apiKey" -> "ck"), "docs_1")
        assert(c.method == "DELETE" && c.body.isEmpty && c.headers.isEmpty, c)
        assert(c.url == "http://ch.store:8000/api/v2/tenants/default_tenant/databases/default_database/collections/docs_1", c)
        assert(
            req("chroma", secret("host" -> "ch.store", "port" -> "8001"), "a-B_9").url ==
                "http://ch.store:8001/api/v2/tenants/default_tenant/databases/default_database/collections/a-B_9"
        )
    }

    test(
        "segment: slash, question mark, hash, percent, double quote, space, plus and a non-ASCII letter are percent-encoded; the URL has exactly one more path segment than its prefix"
    ) {
        val cases = Seq(
            "a/b" -> "a%2Fb",
            "x?y" -> "x%3Fy",
            "x#y" -> "x%23y",
            "50%" -> "50%25",
            "q\"q" -> "q%22q",
            "two words" -> "two%20words",
            "a+b" -> "a%2Bb",
            "café" -> "caf%C3%A9"
        )
        cases.foreach { case (in, out) => assert(segment(in).equalsIgnoreCase(out), s"segment($in) = ${segment(in)}, expected $out") }
        // Unreserved characters pass through unchanged (the backward-compatible case).
        assert(segment("Docs-1_a.b~c") == "Docs-1_a.b~c")

        val nasty = "a/b?c#d%e\"f g+hé"
        val prefixes = Map(
            "qdrant" -> "/collections",
            "weaviate" -> "/v1/schema",
            "chroma" -> "/api/v2/tenants/default_tenant/databases/default_database/collections"
        )
        prefixes.foreach { case (store, prefix) =>
            val r = req(store, secret("host" -> "h.store"), nasty)
            val u = new URI(r.url)
            assert(u.getRawQuery == null && u.getRawFragment == null, s"$store: ${r.url}")
            val raw = u.getRawPath
            assert(raw.startsWith(prefix + "/"), s"$store: $raw")
            val segs = raw.split("/", -1)
            assert(segs.length == prefix.split("/", -1).length + 1, s"$store: $raw")
            assert(URLDecoder.decode(segs.last.replace("+", "%2B"), "UTF-8") == nasty, s"$store: ${segs.last}")
        }
    }

    test("milvus body is valid JSON whose collectionName round-trips a name with a quote, a backslash, a newline and a non-ASCII letter") {
        val name = "a\"b\\c\ndé"
        val r = req("milvus", secret("host" -> "mv.store"), name)
        val o = JsonParser.parseString(r.body.get).getAsJsonObject
        assert(o.keySet.size == 1 && o.get("collectionName").getAsString == name, r.body)
    }

    test("a null or blank name is NotSent for every store; '.' and '..' are Failed and nothing is sent") {
        val s = secret("host" -> "127.0.0.1", "port" -> "1")
        Stores.foreach { store =>
            Seq(null, "", "   ").foreach(n => assert(request(store, s, n) == Left(NotSent), s"$store '$n'"))
            Seq(".", "..").foreach(n => assert(request(store, s, n).left.exists(_.isInstanceOf[Failed]), s"$store '$n'"))
        }
        // Through drop against a live loopback server: no request arrives.
        Stores.foreach { store =>
            val before = hits.get()
            Seq(null, "", "   ").foreach(n => assert(drop(store, loopSecret(store), n) == NotSent, s"$store '$n'"))
            Seq(".", "..").foreach(n => assert(drop(store, loopSecret(store), n).isInstanceOf[Failed], s"$store '$n'"))
            assert(hits.get() == before, s"$store: a request was sent")
        }
    }

    test("a null secret or blank host is Failed 'the store is not configured'; a non-numeric port is Failed, not thrown") {
        Stores.foreach { store =>
            assert(request(store, null, "docs") == Left(Failed("the store is not configured")), store)
            assert(request(store, secret(), "docs") == Left(Failed("the store is not configured")), store)
            assert(request(store, secret("host" -> ""), "docs") == Left(Failed("the store is not configured")), store)
            assert(request(store, secret("host" -> "   "), "docs") == Left(Failed("the store is not configured")), store)
            assert(drop(store, null, "docs") == Failed("the store is not configured"), store)
            val badPort = request(store, secret("host" -> "h.store", "port" -> "abc"), "docs")
            assert(badPort.isLeft && closedReason(failedReason(badPort)), s"$store: $badPort")
            val dropped = drop(store, secret("host" -> "h.store", "port" -> "abc"), "docs")
            assert(closedReason(failedReason(dropped)), s"$store: $dropped")
        }
    }

    test(
        "classify: the success answer is Removed; the store's missing-collection answer is Absent; a bare 404, 401, 403, 500, a 3xx, milvus 200 with a non-zero code or a non-JSON body are Failed"
    ) {
        assert(classify("qdrant", 200, "{\"result\":true,\"status\":\"ok\",\"time\":0.0012}") == Removed)
        assert(classify("weaviate", 200, "") == Removed)
        assert(classify("milvus", 200, "{\"code\":0,\"data\":{}}") == Removed)
        assert(classify("chroma", 200, "{}") == Removed)

        assert(classify("qdrant", 200, "{\"result\":false,\"status\":\"ok\",\"time\":0.0003}") == Absent)
        assert(classify("chroma", 404, "{\"error\":\"NotFoundError\",\"message\":\"Collection [docs] does not exists\"}") == Absent)

        Stores.foreach { store =>
            Seq("", "404 page not found", "<html><body>Not Found</body></html>").foreach { b =>
                assert(classify(store, 404, b) == Failed("the store answered HTTP 404"), s"$store bare 404 '$b'")
            }
            Seq(401, 403, 500).foreach(code => assert(classify(store, code, "") == Failed("the store answered HTTP " + code), s"$store $code"))
            Seq(301, 302, 307, 308).foreach(code => assert(classify(store, code, "").isInstanceOf[Failed], s"$store $code"))
        }
        assert(classify("milvus", 200, "{\"code\":1100,\"message\":\"invalid parameter\"}") == Failed("the store reported an error"))
        assert(classify("milvus", 200, "{\"code\":65535,\"message\":\"x\"}") == Failed("the store reported an error"))
        Seq("", "ok", "<html>").foreach { b =>
            val o = classify("milvus", 200, b)
            assert(o.isInstanceOf[Failed] && closedReason(failedReason(o)), s"milvus 200 '$b' -> $o")
        }
    }

    // ------------------------------------------------------------- loopback

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort

    private val hits = new AtomicInteger(0)
    private val redirectTargetHits = new AtomicInteger(0)
    private val lastRawPath = new AtomicReference[String](null)
    private val lastHeaders = new AtomicReference[Map[String, String]](Map.empty)
    private val lastBody = new AtomicReference[Array[Byte]](Array.emptyByteArray)
    // (status, body, extra headers, delay ms) the next request is answered with
    private val answer = new AtomicReference[(Int, String, Map[String, String], Long)]((200, "", Map.empty, 0L))

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/elsewhere",
            (ex: HttpExchange) => {
                redirectTargetHits.incrementAndGet()
                ex.sendResponseHeaders(200, -1)
                ex.close()
            }
        )
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                hits.incrementAndGet()
                lastRawPath.set(ex.getRequestURI.getRawPath)
                val h = ex.getRequestHeaders
                val keys = h.keySet().toArray(Array.empty[String])
                lastHeaders.set(keys.map(k => k.toLowerCase(java.util.Locale.ROOT) -> h.getFirst(k)).toMap)
                lastBody.set(ex.getRequestBody.readAllBytes())
                val (status, body, extra, delay) = answer.get()
                if (delay > 0) Thread.sleep(delay)
                extra.foreach { case (k, v) => ex.getResponseHeaders.set(k, v) }
                val bytes = body.getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
                if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
                ex.close()
            }
        )
        // A stalled handler must not block the next test's request.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def respond(status: Int, body: String = "", extra: Map[String, String] = Map.empty, delayMs: Long = 0L): Unit =
        answer.set((status, body, extra, delayMs))

    /** A secret pointing the store at the loopback server (qdrant: the gRPC
      * port is one above the REST port it calls). */
    private def loopSecret(store: String, apiKey: String = null): java.util.Map[String, String] = {
        val p = if (store == "qdrant") port + 1 else port
        val s = secret("host" -> "127.0.0.1", "port" -> p.toString)
        if (apiKey != null) s.put("apiKey", apiKey)
        s
    }

    private def closedPort(): Int = {
        val ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val p = ss.getLocalPort
        ss.close()
        p
    }

    test(
        "drop against a loopback server: 200 is Removed and the server saw the encoded path and auth header; 503 is Failed 'the store answered HTTP 503'; a redirect is not followed; a closed port is 'could not be reached'; a stalled response is 'did not answer in time' within the read timeout"
    ) {
        val name = "vd q/é"
        respond(200, "{\"result\":true,\"status\":\"ok\",\"time\":0.001}")
        assert(drop("qdrant", loopSecret("qdrant", "qk-1"), name) == Removed)
        assert(lastRawPath.get().equalsIgnoreCase("/collections/vd%20q%2F%C3%A9"), lastRawPath.get())
        assert(lastHeaders.get().get("api-key").contains("qk-1"), lastHeaders.get())

        respond(200, "")
        assert(drop("weaviate", loopSecret("weaviate", "wk-1"), "VdW") == Removed)
        assert(lastRawPath.get() == "/v1/schema/VdW" && lastHeaders.get().get("authorization").contains("Bearer wk-1"), lastHeaders.get())

        respond(200, "{}")
        assert(drop("chroma", loopSecret("chroma"), "vd_c") == Removed)
        assert(lastRawPath.get() == "/api/v2/tenants/default_tenant/databases/default_database/collections/vd_c")

        // Milvus: the body arrives as UTF-8 JSON naming exactly the collection.
        respond(200, "{\"code\":0,\"data\":{}}")
        val mName = "m\"né"
        assert(drop("milvus", loopSecret("milvus", "mk-1"), mName) == Removed)
        assert(lastRawPath.get() == "/v2/vectordb/collections/drop")
        val posted = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8)).getAsJsonObject
        assert(posted.get("collectionName").getAsString == mName, posted)
        assert(lastHeaders.get().get("authorization").contains("Bearer mk-1"), lastHeaders.get())

        respond(503, "upstream unavailable")
        Stores.foreach(store => assert(drop(store, loopSecret(store), "vd_x") == Failed("the store answered HTTP 503"), store))

        // A redirect is answered as a failure, and its target is never requested.
        respond(302, "", Map("Location" -> ("http://127.0.0.1:" + port + "/elsewhere")))
        Stores.foreach { store =>
            val o = drop(store, loopSecret(store), "vd_x")
            assert(o.isInstanceOf[Failed], s"$store: $o")
        }
        assert(redirectTargetHits.get() == 0, "a redirect was followed")

        // Closed port: refused.
        val cp = closedPort()
        Stores.foreach { store =>
            // qdrant calls the configured gRPC port minus one.
            val p = if (store == "qdrant") cp + 1 else cp
            assert(drop(store, secret("host" -> "127.0.0.1", "port" -> p.toString), "vd_x") == Failed("the store could not be reached"), store)
        }

        // Stalled: the server answers after 8 s; the 5 s read timeout wins.
        respond(200, "{}", delayMs = 8000L)
        val t0 = System.nanoTime()
        val stalled = drop("chroma", loopSecret("chroma"), "vd_slow")
        val ms = (System.nanoTime() - t0) / 1000000L
        assert(stalled == Failed("the store did not answer in time"), stalled)
        assert(ms < 7500L, s"took $ms ms")
        respond(200, "")
    }

    test("no Failed reason and no warning contains the host, the port, the secret name, the api key or the response body") {
        val apiKey = "k-SECRET-9f3a"
        val leakBody = "BODY-LEAK other_collection at store-host.internal"
        val reasons = Seq.newBuilder[String]

        // Unparsable port on a named host.
        Stores.foreach { store =>
            reasons += failedReason(drop(store, secret("host" -> "store-host.internal", "port" -> "p0rt-xyz", "apiKey" -> apiKey), "docs"))
            reasons += failedReason(drop(store, secret("apiKey" -> apiKey), "docs"))
        }
        // Error answers carrying a revealing body.
        respond(500, leakBody)
        Stores.foreach(store => reasons += failedReason(drop(store, loopSecret(store, apiKey), "docs")))
        respond(200, "{\"code\":1100,\"message\":\"" + leakBody + "\"}")
        reasons += failedReason(drop("milvus", loopSecret("milvus", apiKey), "docs"))
        reasons += failedReason(classify("chroma", 400, "{\"error\":\"InvalidArgumentError\",\"message\":\"" + leakBody + "\"}"))
        // Refused.
        val cp = closedPort()
        reasons += failedReason(drop("chroma", secret("host" -> "127.0.0.1", "port" -> cp.toString, "apiKey" -> apiKey), "docs"))

        val all = reasons.result()
        val forbidden = Seq(
            "127.0.0.1",
            "store-host",
            "p0rt-xyz",
            port.toString,
            (port + 1).toString,
            cp.toString,
            apiKey,
            "BODY-LEAK",
            "other_collection",
            "/qdrant",
            "/chroma",
            "secret"
        )
        all.foreach { r =>
            assert(closedReason(r), s"not a closed-set reason: $r")
            forbidden.foreach(f => assert(!r.contains(f), s"reason '$r' contains '$f'"))
            Seq(true, false).foreach { cd =>
                val w = warning(VectorContainers.Key("qdrant", "", "docs"), r, cd)
                forbidden.foreach(f => assert(!w.contains(f), s"warning '$w' contains '$f'"))
            }
        }
    }

    test("warning text for a config delete and for a reset; it names only the store and the pipeline's own collection (weaviate: the class as configured)") {
        val q = VectorContainers.key("qdrant", "vd_q", None).get
        assert(
            warning(q, "the store could not be reached", configDeleted = true) ==
                "Could not remove qdrant collection vd_q: the store could not be reached. Its pipeline configuration was deleted; remove it in the store."
        )
        assert(
            warning(q, "the store could not be reached", configDeleted = false) ==
                "Could not remove qdrant collection vd_q: the store could not be reached. Run the reset again once the store is available."
        )
        val m = VectorContainers.key("milvus", "vd_m", None).get
        assert(
            warning(m, "the store reported an error", configDeleted = true).startsWith("Could not remove milvus collection vd_m: the store reported an error. ")
        )
        val c = VectorContainers.key("chroma", "vd_c", None).get
        assert(warning(c, "the store answered HTTP 503", configDeleted = false).startsWith(
            "Could not remove chroma collection vd_c: the store answered HTTP 503. "
        ))

        // Weaviate: the key is lowercased for identity; the message carries the class as written.
        val cfg = PipelineConfig(name = "vd_w", destination = Destination(weaviate = WeaviateConfig("VdW", ChunkingConfig(), null, null, null)))
        val wk = VectorContainers.keysOf(cfg).head
        val ww = warning(VectorContainers.displayKey(wk, cfg), "the store did not answer in time", configDeleted = false)
        assert(ww == "Could not remove weaviate class VdW: the store did not answer in time. Run the reset again once the store is available.", ww)
    }
}
