package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * Acceptance, `DestinationProbeVectorContainerSpec`.
  *
  * Pinned seams (assumed shape; the story names `vectorContainerRequest` and
  * `vectorContainerAnswer` but a single (status, body) cannot carry the
  * qdrant alias list or the chroma control list it requires, so both carry a
  * sequence). Requests reuse the sibling story's `VectorStoreDrop.Request`
  * and are sent with `VectorStoreDrop.send` (5 s connect and read, no
  * redirects, whole-call deadline):
  * {{{
  * object DestinationProbe {
  *     // The requests the probe sends, in order: qdrant GET /collections then GET /aliases;
  *     // weaviate GET /v1/schema; milvus POST /v2/vectordb/collections/has; chroma GET the
  *     // collection then the control GET .../collections?limit=1. Throws for a null secret,
  *     // a blank host, an unusable port or an unknown store.
  *     def vectorContainerRequest(store: String, secret: java.util.Map[String, String], exact: String): Seq[VectorStoreDrop.Request]
  *     // The answers to those requests (a prefix of them, in order: the probe may stop once
  *     // definite). True = exists, false = definitely absent; throws for anything else.
  *     def vectorContainerAnswer(store: String, exact: String, answers: Seq[(Int, String)]): Boolean
  *     // The probe over a secret read (keyed by store, the shape of the delete's `secretOf`)
  *     // and a transport. Success(None) = no secret: throws.
  *     def vectorContainerExistsWith(
  *         t: DestinationTargets.VectorContainerTarget,
  *         secretOf: String => scala.util.Try[Option[java.util.Map[String, String]]],
  *         send: VectorStoreDrop.Request => (Int, String)
  *     ): Boolean
  *     // Production: the environment's <store>SecretName through SecretsUtil (not called here).
  *     def vectorContainerExists(t: DestinationTargets.VectorContainerTarget): Boolean
  * }
  * }}}
  * The loopback half runs a real in-process HTTP server on 127.0.0.1; no
  * store, Vault or database is reached. */
class DestinationProbeVectorContainerSpec extends AnyFunSuite with BeforeAndAfterAll {

    import DestinationProbe._

    private val Stores = Seq("qdrant", "weaviate", "milvus", "chroma")
    private val ChromaBase = "/api/v2/tenants/default_tenant/databases/default_database/collections"

    private def secret(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hdrs(r: VectorStoreDrop.Request): Map[String, String] =
        r.headers.map { case (k, v) => k.toLowerCase(java.util.Locale.ROOT) -> v }.toMap

    private def target(store: String, exact: String): DestinationTargets.VectorContainerTarget =
        DestinationTargets.VectorContainerTarget(
            VectorContainers.Key(store, "", if (store == "weaviate") exact.toLowerCase(java.util.Locale.ROOT) else exact),
            exact
        )

    // ------------------------------------------------------------------ answers

    private def qCollections(names: String*): String =
        "{\"result\":{\"collections\":[" + names.map(n => "{\"name\":\"" + n + "\"}").mkString(",") + "]},\"status\":\"ok\",\"time\":0.0001}"
    private def qAliases(pairs: (String, String)*): String =
        "{\"result\":{\"aliases\":[" + pairs.map { case (a, c) => "{\"alias_name\":\"" + a + "\",\"collection_name\":\"" + c + "\"}" }.mkString(",") +
            "]},\"status\":\"ok\",\"time\":0.0001}"
    private def wSchema(classes: String*): String =
        "{\"classes\":[" + classes.map(c => "{\"class\":\"" + c + "\",\"properties\":[]}").mkString(",") + "]}"
    private def mHas(has: Boolean, code: Int = 0): String = "{\"code\":" + code + ",\"data\":{\"has\":" + has + "}}"
    private def chCollection(name: String): String = "{\"id\":\"3f2a\",\"name\":\"" + name + "\",\"metadata\":null}"
    private val chNotFound = "{\"error\":\"NotFoundError\",\"message\":\"Collection [x] does not exists\"}"

    private def answer(store: String, exact: String, answers: (Int, String)*): Boolean = vectorContainerAnswer(store, exact, answers)

    private def throwsAnswer(store: String, exact: String, answers: (Int, String)*): Unit =
        intercept[Exception](vectorContainerAnswer(store, exact, answers))

    // ------------------------------------------------------------------ pure: requests

    test(
        "the request per store: URL, port (qdrant gRPC port minus 1), scheme, auth header and body as the delete addresses it; chroma default tenant and database; milvus no dbName and a JSON-escaped name"
    ) {
        // Qdrant: REST at the configured gRPC port minus one; api-key header; list collections, then aliases.
        val qs = secret("host" -> "qd.store", "port" -> "6334", "apiKey" -> "qk")
        val q = vectorContainerRequest("qdrant", qs, "docs_1")
        assert(q.map(r => r.method -> r.url) == Seq("GET" -> "http://qd.store:6333/collections", "GET" -> "http://qd.store:6333/aliases"), q)
        q.foreach(r => assert(hdrs(r) == Map("api-key" -> "qk") && r.body.isEmpty, r))
        assert(VectorStoreDrop.request("qdrant", qs, "docs_1") == Right(VectorStoreDrop.Request("DELETE", q.head.url + "/docs_1", q.head.headers, None)))
        assert(vectorContainerRequest("qdrant", secret("host" -> "qd.store"), "docs_1").head.url == "http://qd.store:6333/collections")
        assert(vectorContainerRequest("qdrant", secret("host" -> "qd.store", "port" -> "7334"), "docs_1").head.url == "http://qd.store:7333/collections")
        assert(vectorContainerRequest("qdrant", secret("host" -> "qd.store"), "docs_1").forall(_.headers.isEmpty))

        // Weaviate: the schema list; scheme http and port 8079 by default; Bearer auth.
        val w = vectorContainerRequest("weaviate", secret("host" -> "wv.store"), "Docs_1")
        assert(w.map(r => r.method -> r.url) == Seq("GET" -> "http://wv.store:8079/v1/schema", "GET" -> "http://wv.store:8079/v1/aliases"), w)
        assert(w.head.headers.isEmpty && w.head.body.isEmpty, w)
        val ws = secret("host" -> "wv.store", "port" -> "8080", "scheme" -> "https", "apiKey" -> "wk")
        val wFull = vectorContainerRequest("weaviate", ws, "Docs_1")
        assert(wFull.map(_.url) == Seq("https://wv.store:8080/v1/schema", "https://wv.store:8080/v1/aliases"), wFull)
        assert(hdrs(wFull.head) == Map("authorization" -> "Bearer wk"), wFull)
        assert(VectorStoreDrop.request("weaviate", ws, "Docs_1").map(_.url) == Right(wFull.head.url + "/Docs_1"))

        // Milvus: POST has, JSON body with collectionName only (no dbName: the default database), Bearer auth.
        val m = vectorContainerRequest("milvus", secret("host" -> "mv.store", "apiKey" -> "mk"), "docs_1")
        assert(m.map(r => r.method -> r.url) == Seq("POST" -> "http://mv.store:19530/v2/vectordb/collections/has"), m)
        assert(hdrs(m.head).get("content-type").exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")), m)
        assert(hdrs(m.head).get("authorization").contains("Bearer mk"), m)
        val mo = JsonParser.parseString(m.head.body.get).getAsJsonObject
        assert(mo.keySet.asScala == Set("collectionName") && mo.get("collectionName").getAsString == "docs_1", m.head.body)
        val tricky = "a\"b\\c\ndé"
        val mt = JsonParser.parseString(vectorContainerRequest("milvus", secret("host" -> "mv.store"), tricky).head.body.get).getAsJsonObject
        assert(mt.keySet.asScala == Set("collectionName") && mt.get("collectionName").getAsString == tricky)
        assert(
            vectorContainerRequest("milvus", secret("host" -> "mv.store", "port" -> "19531"), "docs_1").head.url ==
                "http://mv.store:19531/v2/vectordb/collections/has"
        )

        // Chroma: the collection under the default tenant and database, then the control list; no auth.
        val cs = secret("host" -> "ch.store", "apiKey" -> "ck")
        val c = vectorContainerRequest("chroma", cs, "docs_1")
        assert(
            c.map(r => r.method -> r.url) == Seq(
                "GET" -> ("http://ch.store:8000" + ChromaBase + "/docs_1"),
                "GET" -> ("http://ch.store:8000" + ChromaBase + "?limit=1")
            ),
            c
        )
        c.foreach(r => assert(r.headers.isEmpty && r.body.isEmpty, r))
        assert(VectorStoreDrop.request("chroma", cs, "docs_1").map(_.url) == Right(c.head.url))
        assert(
            vectorContainerRequest("chroma", secret("host" -> "ch.store", "port" -> "8001"), "a-B_9").head.url == "http://ch.store:8001" + ChromaBase + "/a-B_9"
        )
    }

    // ------------------------------------------------------------------ pure: answers

    test("qdrant: listed is exists, a 200 list without the name is absent, a same-named alias is exists") {
        assert(answer("qdrant", "docs", 200 -> qCollections("other", "docs"), 200 -> qAliases()))
        assert(!answer("qdrant", "docs", 200 -> qCollections("other", "Docs", "docs2"), 200 -> qAliases("x" -> "other")))
        assert(!answer("qdrant", "docs", 200 -> qCollections(), 200 -> qAliases()))
        assert(answer("qdrant", "docs", 200 -> qCollections("real"), 200 -> qAliases("docs" -> "real")))
        // An alias pointing at a collection called "docs" is not an alias named "docs".
        assert(!answer("qdrant", "docs", 200 -> qCollections("real"), 200 -> qAliases("al" -> "docs")))
    }

    test("weaviate: a class equal ignoring case is exists") {
        assert(answer("weaviate", "Docs", 200 -> wSchema("Docs")))
        assert(answer("weaviate", "Docs", 200 -> wSchema("Other", "DOCS")))
        assert(answer("weaviate", "MyDocs", 200 -> wSchema("Mydocs")))
        assert(!answer("weaviate", "Docs", 200 -> wSchema("Other", "Docs2"), 404 -> ""))
        assert(!answer("weaviate", "Docs", 200 -> wSchema(), 200 -> "{\"aliases\":[]}"))
    }

    test("weaviate aliases: a same-named alias (any case) is exists; 404 is no alias support; anything else throws") {
        val aliases = "{\"aliases\":[{\"alias\":\"DOCS\",\"class\":\"RealClass\"}]}"
        assert(answer("weaviate", "Docs", 200 -> wSchema("RealClass"), 200 -> aliases))
        assert(!answer("weaviate", "Other", 200 -> wSchema("RealClass"), 200 -> aliases))
        // An alias pointing at a class called Docs is not an alias named Docs.
        assert(!answer("weaviate", "Docs", 200 -> wSchema("Real"), 200 -> "{\"aliases\":[{\"alias\":\"x\",\"class\":\"Docs\"}]}"))
        assert(!answer("weaviate", "Docs", 200 -> wSchema("Real"), 404 -> "{\"error\":\"not found\"}"))
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"))
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"), 500 -> "")
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"), 401 -> "")
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"), 200 -> "{}")
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"), 200 -> "not json")
        throwsAnswer("weaviate", "Docs", 200 -> wSchema("Real"), 200 -> "{\"aliases\":[{\"class\":\"Docs\"}]}")
    }

    test("milvus: has true / false with code 0") {
        assert(answer("milvus", "docs", 200 -> mHas(true)))
        assert(!answer("milvus", "docs", 200 -> mHas(false)))
    }

    test("chroma: 200 is exists, 404 is absent only with the control list answering 200") {
        assert(answer("chroma", "docs", 200 -> chCollection("docs")))
        assert(!answer("chroma", "docs", 404 -> chNotFound, 200 -> "[]"))
        assert(!answer("chroma", "docs", 404 -> chNotFound, 200 -> "[{\"id\":\"1\",\"name\":\"other\"}]"))
    }

    test(
        "anything else throws: 401, 403, 500, a redirect, an empty or non-JSON body, a missing field, milvus code other than 0, chroma 404 without the control (pure answers)"
    ) {
        for (status <- Seq(401, 403, 500, 301, 302, 307, 404, 201, 204)) {
            if (status != 404) {
                throwsAnswer("qdrant", "docs", status -> qCollections("docs"), 200 -> qAliases())
                throwsAnswer("weaviate", "Docs", status -> wSchema("Docs"))
                throwsAnswer("milvus", "docs", status -> mHas(true))
            } else {
                throwsAnswer("qdrant", "docs", 404 -> "", 200 -> qAliases())
                throwsAnswer("weaviate", "Docs", 404 -> "")
                throwsAnswer("milvus", "docs", 404 -> "")
            }
            if (status != 200 && status != 404) throwsAnswer("chroma", "docs", status -> chCollection("docs"), 200 -> "[]")
        }
        // Empty and non-JSON bodies.
        for (body <- Seq("", "   ", "<html>gateway</html>", "not json", "[1,2")) {
            throwsAnswer("qdrant", "docs", 200 -> body, 200 -> qAliases())
            throwsAnswer("weaviate", "Docs", 200 -> body)
            throwsAnswer("milvus", "docs", 200 -> body)
            throwsAnswer("chroma", "docs", 200 -> body)
            throwsAnswer("chroma", "docs", 404 -> chNotFound, 200 -> body)
        }
        // Missing fields.
        throwsAnswer("qdrant", "docs", 200 -> "{\"status\":\"ok\"}", 200 -> qAliases())
        throwsAnswer("qdrant", "docs", 200 -> "{\"result\":{}}", 200 -> qAliases())
        throwsAnswer("weaviate", "Docs", 200 -> "{}")
        throwsAnswer("milvus", "docs", 200 -> "{\"code\":0}")
        throwsAnswer("milvus", "docs", 200 -> "{\"code\":0,\"data\":{}}")
        throwsAnswer("milvus", "docs", 200 -> "{\"code\":0,\"data\":{\"has\":\"yes\"}}")
        throwsAnswer("milvus", "docs", 200 -> "{\"data\":{\"has\":false}}")
        throwsAnswer("chroma", "docs", 200 -> "{\"id\":\"1\"}")
        // Qdrant: the collection is not listed and the alias list failed or is malformed.
        throwsAnswer("qdrant", "docs", 200 -> qCollections(), 500 -> "")
        throwsAnswer("qdrant", "docs", 200 -> qCollections(), 200 -> "{\"result\":{}}")
        throwsAnswer("qdrant", "docs", 200 -> qCollections())
        // Milvus: a reported error is not an answer, whatever data says.
        throwsAnswer("milvus", "docs", 200 -> mHas(false, code = 1100))
        throwsAnswer("milvus", "docs", 200 -> mHas(true, code = 1))
        // Chroma: 404 alone, or with a control that is not a 200 JSON array.
        throwsAnswer("chroma", "docs", 404 -> chNotFound)
        throwsAnswer("chroma", "docs", 404 -> "")
        throwsAnswer("chroma", "docs", 404 -> chNotFound, 404 -> chNotFound)
        throwsAnswer("chroma", "docs", 404 -> chNotFound, 500 -> "[]")
        throwsAnswer("chroma", "docs", 404 -> chNotFound, 200 -> "{}")
        // No answer at all.
        Stores.foreach(s => throwsAnswer(s, "docs"))
    }

    test("a missing secret or a blank host throws") {
        Stores.foreach { store =>
            intercept[Exception](vectorContainerRequest(store, null, "docs"))
            intercept[Exception](vectorContainerRequest(store, secret(), "docs"))
            intercept[Exception](vectorContainerRequest(store, secret("host" -> ""), "docs"))
            intercept[Exception](vectorContainerRequest(store, secret("host" -> "   "), "docs"))
            intercept[Exception](vectorContainerRequest(store, secret("host" -> "h.store", "port" -> "abc"), "docs"))

            val sent = new AtomicInteger(0)
            val send: VectorStoreDrop.Request => (Int, String) = _ => { sent.incrementAndGet(); (200, "{}") }
            val t = target(store, if (store == "weaviate") "Docs" else "docs")
            intercept[Exception](vectorContainerExistsWith(t, _ => Success(None), send))
            intercept[Exception](vectorContainerExistsWith(t, _ => Failure(new RuntimeException("vault sealed")), send))
            intercept[Exception](vectorContainerExistsWith(t, _ => Success(Some(secret("port" -> "1"))), send))
            intercept[Exception](vectorContainerExistsWith(t, _ => Success(Some(secret("host" -> " "))), send))
            assert(sent.get() == 0, s"$store: a request was sent without a usable secret")
        }
        // The secret read is keyed by the target's store.
        val asked = new ConcurrentLinkedQueue[String]()
        intercept[Exception](vectorContainerExistsWith(target("milvus", "docs"), s => { asked.add(s); Success(None) }, _ => (200, "{}")))
        assert(asked.asScala.toSeq == Seq("milvus"))
    }

    // ------------------------------------------------------------------ loopback

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort

    /** Route: raw path (plus `?query`) -> (status, body, extra headers, delay ms). Unrouted: 404, empty. */
    private val routes = new AtomicReference[Map[String, (Int, String, Seq[(String, String)], Long)]](Map.empty)
    private val seen = new ConcurrentLinkedQueue[(String, String, Map[String, String], String)]()
    private val elsewhereHits = new AtomicInteger(0)

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/elsewhere",
            (ex: HttpExchange) => {
                elsewhereHits.incrementAndGet()
                val bytes = qCollections("vs_orphan").getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.length.toLong)
                ex.getResponseBody.write(bytes)
                ex.close()
            }
        )
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                val u = ex.getRequestURI
                val key = u.getRawPath + Option(u.getRawQuery).map("?" + _).getOrElse("")
                val h = ex.getRequestHeaders
                val keys = h.keySet().toArray(Array.empty[String])
                seen.add(
                    (
                        ex.getRequestMethod,
                        key,
                        keys.map(k => k.toLowerCase(java.util.Locale.ROOT) -> h.getFirst(k)).toMap,
                        new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
                    )
                )
                val (status, body, extra, delay) = routes.get().getOrElse(key, (404, "", Nil, 0L))
                if (delay > 0) Thread.sleep(delay)
                ex.getResponseHeaders.set("Content-Type", "application/json")
                extra.foreach { case (k, v) => ex.getResponseHeaders.set(k, v) }
                val bytes = body.getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
                if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
                ex.close()
            }
        )
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def route(r: (String, (Int, String))*): Unit = {
        seen.clear()
        routes.set(r.map { case (k, (s, b)) => k -> ((s, b, Nil: Seq[(String, String)], 0L)) }.toMap)
    }

    private def loopSecret(store: String, apiKey: String = null, p: Int = -1): java.util.Map[String, String] = {
        val base = if (p > 0) p else port
        val s = secret("host" -> "127.0.0.1", "port" -> (if (store == "qdrant") base + 1 else base).toString)
        if (apiKey != null) s.put("apiKey", apiKey)
        s
    }

    private def probe(store: String, exact: String, s: java.util.Map[String, String]): Boolean =
        vectorContainerExistsWith(target(store, exact), _ => Try(Some(s)), r => VectorStoreDrop.send(r))

    private def closedPort(): Int = {
        val ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val p = ss.getLocalPort
        ss.close()
        p
    }

    test("end to end against a loopback server, per store: a present container is true, a missing one false, and the store saw the request as built") {
        // Qdrant
        route("/collections" -> (200 -> qCollections("vs_orphan")), "/aliases" -> (200 -> qAliases()))
        assert(probe("qdrant", "vs_orphan", loopSecret("qdrant", apiKey = "qk-1")))
        val qSeen = seen.asScala.toSeq
        assert(qSeen.nonEmpty && qSeen.head._1 == "GET" && qSeen.head._2 == "/collections", qSeen)
        assert(qSeen.forall(_._3.get("api-key").contains("qk-1")), qSeen)
        route("/collections" -> (200 -> qCollections("vs_orphan")), "/aliases" -> (200 -> qAliases()))
        assert(!probe("qdrant", "vs_new", loopSecret("qdrant")))
        route("/collections" -> (200 -> qCollections("real")), "/aliases" -> (200 -> qAliases("vs_orphan" -> "real")))
        assert(probe("qdrant", "vs_orphan", loopSecret("qdrant")))

        // Weaviate
        route("/v1/schema" -> (200 -> wSchema("VsOrphan")))
        assert(probe("weaviate", "VsOrphan", loopSecret("weaviate", apiKey = "wk-1")))
        assert(seen.asScala.toSeq.map(x => x._1 -> x._2) == Seq("GET" -> "/v1/schema"))
        assert(seen.asScala.head._3.get("authorization").contains("Bearer wk-1"))
        assert(!probe("weaviate", "VsNew", loopSecret("weaviate")))

        // Milvus
        route("/v2/vectordb/collections/has" -> (200 -> mHas(true)))
        assert(probe("milvus", "vs_orphan", loopSecret("milvus")))
        val mSeen = seen.asScala.toSeq
        assert(mSeen.map(x => x._1 -> x._2) == Seq("POST" -> "/v2/vectordb/collections/has"), mSeen)
        assert(JsonParser.parseString(mSeen.head._4).getAsJsonObject.get("collectionName").getAsString == "vs_orphan")
        route("/v2/vectordb/collections/has" -> (200 -> mHas(false)))
        assert(!probe("milvus", "vs_new", loopSecret("milvus")))

        // Chroma
        route((ChromaBase + "/vs_orphan") -> (200 -> chCollection("vs_orphan")))
        assert(probe("chroma", "vs_orphan", loopSecret("chroma")))
        assert(seen.asScala.head._2 == ChromaBase + "/vs_orphan" && !seen.asScala.head._3.contains("authorization"))
        route((ChromaBase + "/vs_new") -> (404 -> chNotFound), (ChromaBase + "?limit=1") -> (200 -> "[]"))
        assert(!probe("chroma", "vs_new", loopSecret("chroma")))
        assert(seen.asScala.map(_._2).toSeq == Seq(ChromaBase + "/vs_new", ChromaBase + "?limit=1"))
    }

    test("anything else throws over the wire: a redirect is not followed, chroma 404 without the control, connection refused, a read timeout") {
        // Redirect: not followed, and a failure.
        seen.clear()
        routes.set(
            Map(
                "/collections" -> ((302, "", Seq("Location" -> s"http://127.0.0.1:$port/elsewhere"), 0L)),
                "/aliases" -> ((200, qAliases(), Nil, 0L))
            )
        )
        val before = elsewhereHits.get()
        intercept[Exception](probe("qdrant", "vs_orphan", loopSecret("qdrant")))
        assert(elsewhereHits.get() == before, "the redirect was followed")

        // Chroma 404 with no control path answering.
        route((ChromaBase + "/vs_new") -> (404 -> chNotFound))
        intercept[Exception](probe("chroma", "vs_new", loopSecret("chroma")))

        // 401 / 500 over the wire.
        route("/v1/schema" -> (401 -> "{\"error\":[{\"message\":\"anonymous access not enabled\"}]}"))
        intercept[Exception](probe("weaviate", "VsOrphan", loopSecret("weaviate")))
        route("/v2/vectordb/collections/has" -> (500 -> ""))
        intercept[Exception](probe("milvus", "vs_orphan", loopSecret("milvus")))

        // Connection refused.
        val closed = closedPort()
        Stores.foreach(s => intercept[Exception](probe(s, if (s == "weaviate") "Docs" else "docs", loopSecret(s, p = closed))))

        // A read timeout: the store accepts and never answers within the 5 s read timeout.
        seen.clear()
        routes.set(Map("/v1/schema" -> ((200, wSchema("VsOrphan"), Nil, 7000L))))
        val t0 = System.nanoTime()
        intercept[Exception](probe("weaviate", "VsOrphan", loopSecret("weaviate")))
        val tookMs = (System.nanoTime() - t0) / 1000000L
        assert(tookMs < 6900L, s"the probe waited ${tookMs} ms for a stalled store")
    }
}
