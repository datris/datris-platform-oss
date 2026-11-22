package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Story: Pipeline delete: a vector collection that could not be removed is
  * reported, never logged as deleted
  * (plans/stories/vector-store-delete-reports-failure.md), review follow-up:
  * one drop has a whole-call deadline. The read timeout is per read, so a
  * store that trickles its answer (a byte at a time, each inside the read
  * timeout) must still be cut off. In-process HTTP server on 127.0.0.1 only. */
class VectorStoreDropDeadlineSpec extends AnyFunSuite with BeforeAndAfterAll {

    import VectorStoreDrop._

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        // Chunked 200 that sends one space every 100 ms for up to 20 s.
        server.createContext(
            "/api/v2/tenants/default_tenant/databases/default_database/collections/trickle",
            (ex: HttpExchange) => {
                ex.sendResponseHeaders(200, 0)
                val out = ex.getResponseBody
                try {
                    var i = 0
                    while (i < 200) {
                        out.write(" ".getBytes(StandardCharsets.UTF_8))
                        out.flush()
                        Thread.sleep(100)
                        i += 1
                    }
                } catch { case _: Exception => () }
                finally ex.close()
            }
        )
        server.createContext(
            "/api/v2/tenants/default_tenant/databases/default_database/collections/quick",
            (ex: HttpExchange) => {
                val b = "{}".getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, b.length.toLong)
                ex.getResponseBody.write(b)
                ex.close()
            }
        )
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def secret: java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        m.put("host", "127.0.0.1")
        m.put("port", port.toString)
        m
    }

    test("the default whole-call deadline is 10 s") {
        assert(TotalDeadlineMs == 10000L)
    }

    test("a trickled answer ends as 'did not answer in time' within the deadline") {
        val t0 = System.nanoTime()
        val o = drop("chroma", secret, "trickle", deadlineMs = 1000L)
        val ms = (System.nanoTime() - t0) / 1000000L
        assert(o == Failed("the store did not answer in time"), o)
        assert(ms >= 900L && ms < 3000L, s"took $ms ms")
    }

    test("an answer inside the deadline is classified as usual") {
        assert(drop("chroma", secret, "quick", deadlineMs = 1000L) == Removed)
    }
}
