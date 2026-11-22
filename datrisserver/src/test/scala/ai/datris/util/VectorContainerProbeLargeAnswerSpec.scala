package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Success, Try}

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * review finding 1: the probe's list answers are read whole. A healthy
  * store's list (the weaviate schema carries every class's config, qdrant
  * lists every collection) is far above the drop's 64 KB; it must still be
  * answered, and only an answer over the probe's own cap fails, explicitly.
  * Production probe (`vectorContainerExists(t, readSecret)`) against a
  * loopback server; no store, Vault or database is reached. */
class VectorContainerProbeLargeAnswerSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort
    private val routes = new AtomicReference[Map[String, String]](Map.empty)

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                ex.getRequestBody.readAllBytes()
                routes.get().get(ex.getRequestURI.getRawPath) match {
                    case Some(body) =>
                        val bytes = body.getBytes(StandardCharsets.UTF_8)
                        ex.sendResponseHeaders(200, bytes.length.toLong)
                        try ex.getResponseBody.write(bytes)
                        catch { case _: java.io.IOException => () }
                    case None => ex.sendResponseHeaders(404, -1)
                }
                ex.close()
            }
        )
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def env: DatrisEnvironment = {
        val e = mock(classOf[DatrisEnvironment])
        when(e.qdrantSecretName).thenReturn("t1/qdrant")
        when(e.weaviateSecretName).thenReturn("t1/weaviate")
        e
    }

    private def read(store: String): String => Try[Option[java.util.Map[String, String]]] = _ => {
        val m = new java.util.HashMap[String, String]()
        m.put("host", "127.0.0.1")
        m.put("port", (if (store == "qdrant") port + 1 else port).toString)
        Success(Some(m))
    }

    private def probe(store: String, exact: String): Boolean =
        try {
            TenantContext.set(env)
            DestinationProbe.vectorContainerExists(
                DestinationTargets.VectorContainerTarget(VectorContainers.Key(store, "", if (store == "weaviate") exact.toLowerCase else exact), exact),
                read(store)
            )
        } finally TenantContext.clear()

    /** A weaviate schema of `n` classes, each with a bulky config. */
    private def schema(n: Int, extra: String*): String = {
        val filler = "x" * 400
        val classes = (1 to n).map(i => "{\"class\":\"Class" + i + "\",\"description\":\"" + filler + "\",\"properties\":[]}") ++
            extra.map(c => "{\"class\":\"" + c + "\",\"properties\":[]}")
        "{\"classes\":[" + classes.mkString(",") + "]}"
    }

    private def qdrantList(n: Int, extra: String*): String =
        "{\"result\":{\"collections\":[" + ((1 to n).map(i => "collection_number_" + i) ++ extra).map(c => "{\"name\":\"" + c + "\"}").mkString(",") +
            "]},\"status\":\"ok\",\"time\":0.001}"

    test("a weaviate schema well over 64 KB is read whole: a class at its end is exists, a missing one absent") {
        val big = schema(200, "VsOrphan")
        assert(big.length > 64 * 1024, big.length)
        routes.set(Map("/v1/schema" -> big))
        assert(probe("weaviate", "VsOrphan"))
        assert(!probe("weaviate", "VsNew")) // /v1/aliases unrouted: 404, no alias support
    }

    test("a qdrant list of thousands of collections is read whole") {
        val big = qdrantList(4000, "vs_orphan")
        assert(big.length > 64 * 1024, big.length)
        routes.set(Map("/collections" -> big, "/aliases" -> "{\"result\":{\"aliases\":[]},\"status\":\"ok\"}"))
        assert(probe("qdrant", "vs_orphan"))
        assert(!probe("qdrant", "vs_new"))
    }

    test("an answer over the probe's cap throws 'answer too large' instead of parsing a truncated list") {
        assert(DestinationProbe.VectorProbeMaxBodyBytes == 8 * 1024 * 1024)
        val huge = schema(22000)
        assert(huge.length > DestinationProbe.VectorProbeMaxBodyBytes, huge.length)
        routes.set(Map("/v1/schema" -> huge))
        val e = intercept[VectorStoreDrop.AnswerTooLarge](probe("weaviate", "VsNew"))
        assert(e.getMessage.contains("answer too large"))
    }

    test("the probe's request deadline is the save's, not the drop's 10 s; the drop's cap is unchanged") {
        assert(DestinationProbe.VectorProbeRequestDeadlineMs == DestinationProbe.DefaultDeadline.toMillis)
        assert(VectorStoreDrop.MaxBodyBytes == 64 * 1024 && VectorStoreDrop.TotalDeadlineMs == 10000L)
    }
}
