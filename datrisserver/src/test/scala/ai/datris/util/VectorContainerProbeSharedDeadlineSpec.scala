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
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.util.{Success, Try}

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * review: one deadline is shared by every request of one probe. Weaviate
  * needs up to three requests; each must get only the time that is left,
  * so a slow store cannot hold the caller's check slot past the probe's
  * deadline. Production probe against a loopback server whose every answer
  * takes 700 ms; no store, Vault or database is reached. */
class VectorContainerProbeSharedDeadlineSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort
    private val seen = new ConcurrentLinkedQueue[String]()

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                seen.add(ex.getRequestURI.getRawPath)
                try {
                    Thread.sleep(700)
                    if (ex.getRequestURI.getRawPath == "/v1/schema") {
                        val b = "{\"classes\":[]}".getBytes("UTF-8")
                        ex.sendResponseHeaders(200, b.length.toLong)
                        ex.getResponseBody.write(b)
                    } else ex.sendResponseHeaders(404, -1)
                } catch { case _: Exception => () }
                finally ex.close()
            }
        )
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def probe(deadlineMs: Long): Boolean = {
        val e = mock(classOf[DatrisEnvironment])
        when(e.weaviateSecretName).thenReturn("t1/weaviate")
        val read: String => Try[Option[java.util.Map[String, String]]] = _ => {
            val m = new java.util.HashMap[String, String]()
            m.put("host", "127.0.0.1")
            m.put("port", port.toString)
            Success(Some(m))
        }
        try {
            TenantContext.set(e)
            DestinationProbe.vectorContainerExists(
                DestinationTargets.VectorContainerTarget(VectorContainers.Key("weaviate", "", "vsnew"), "VsNew"),
                read,
                deadlineMs
            )
        } finally TenantContext.clear()
    }

    test("three slow answers inside the shared deadline: the probe answers absent") {
        seen.clear()
        assert(!probe(5000L))
        assert(seen.asScala.toList == List("/v1/schema", "/v1/aliases/VsNew", "/v1/aliases"), seen)
    }

    test("the requests share one deadline: the second request gets only what is left, and the third is never sent") {
        seen.clear()
        val t0 = System.nanoTime()
        intercept[java.io.IOException](probe(1000L))
        val ms = (System.nanoTime() - t0) / 1000000L
        assert(ms < 1600L, s"the probe ran $ms ms past a 1000 ms deadline")
        Thread.sleep(800) // let any request in flight reach the server
        assert(!seen.asScala.toList.contains("/v1/aliases"), seen)
    }
}
