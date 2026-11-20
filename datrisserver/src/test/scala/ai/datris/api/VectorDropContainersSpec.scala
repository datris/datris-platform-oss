package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{ChromaConfig, ChunkingConfig, Destination, MilvusConfig, PipelineConfig, QdrantConfig, WeaviateConfig}
import ai.datris.util.{VectorContainers, VectorStoreDrop}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** Story: Pipeline delete: a vector collection that could not be removed is
  * reported, never logged as deleted
  * (plans/stories/vector-store-delete-reports-failure.md), review follow-up:
  * the delete's vector wiring driven for real. `dropVectorContainers` gets
  * its store secrets through the `secretOf` seam (no Vault) and drops
  * against an in-process HTTP server on 127.0.0.1 standing in for all four
  * stores; no store, Vault or database is reached. */
class VectorDropContainersSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort

    /** (store, raw path) of each request, in arrival order. */
    private val seen = new ConcurrentLinkedQueue[(String, String)]()
    @volatile private var answers: Map[String, (Int, String)] = Map.empty

    private def storeOf(path: String): String =
        if (path.startsWith("/collections/")) "qdrant"
        else if (path.startsWith("/v1/schema/")) "weaviate"
        else if (path.startsWith("/v2/vectordb/")) "milvus"
        else if (path.startsWith("/api/v2/")) "chroma"
        else "unknown"

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                val path = ex.getRequestURI.getRawPath
                val store = storeOf(path)
                seen.add(store -> path)
                ex.getRequestBody.readAllBytes()
                val (status, body) = answers.getOrElse(store, (500, ""))
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

    private val Success200 = Map(
        "qdrant" -> (200, "{\"result\":true,\"status\":\"ok\",\"time\":0.001}"),
        "weaviate" -> (200, ""),
        "milvus" -> (200, "{\"code\":0,\"data\":{}}"),
        "chroma" -> (200, "{}")
    )

    private def config(q: String = "vd_q", w: String = "VdW", m: String = "vd_m", c: String = "vd_c"): PipelineConfig =
        PipelineConfig(
            name = "vd_all",
            destination = Destination(
                qdrant = QdrantConfig(q, ChunkingConfig(), null, null, null),
                weaviate = WeaviateConfig(w, ChunkingConfig(), null, null, null),
                milvus = MilvusConfig(m, ChunkingConfig(), null, null, null),
                chroma = ChromaConfig(c, ChunkingConfig(), null, null, null)
            )
        )

    private def loopSecret(store: String): Try[Option[java.util.Map[String, String]]] = {
        val m = new java.util.HashMap[String, String]()
        m.put("host", "127.0.0.1")
        m.put("port", (if (store == "qdrant") port + 1 else port).toString)
        Success(Some(m))
    }

    /** Runs the drop with `answers`, returning (failures, log lines, store order of requests). */
    private def run(
        cfg: PipelineConfig,
        ans: Map[String, (Int, String)],
        keep: Set[VectorContainers.Key] = Set.empty,
        secretOf: String => Try[Option[java.util.Map[String, String]]] = loopSecret
    ): (Seq[(VectorContainers.Key, String)], Seq[(String, String)], Seq[String]) = {
        answers = ans
        seen.clear()
        val logs = new ConcurrentLinkedQueue[(String, String)]()
        val failures = PipelineAPIController.dropVectorContainers(cfg, keep, secretOf, (l, msg) => logs.add(l -> msg))
        (failures, logs.asScala.toList, seen.asScala.toList.map(_._1))
    }

    private val StoreOrder = Seq("qdrant", "weaviate", "milvus", "chroma")

    test("Removed for every store: no failure, one request per store in store order, and today's 'Deleted' / 'Dropped' info lines") {
        val (failures, logs, order) = run(config(), Success200)
        assert(failures.isEmpty, failures)
        assert(order == StoreOrder, order)
        assert(
            logs == Seq(
                "info" -> "Deleted Qdrant collection: vd_q",
                "info" -> "Deleted Weaviate class: VdW",
                "info" -> "Dropped Milvus collection: vd_m",
                "info" -> "Deleted Chroma collection: vd_c"
            ),
            logs
        )
    }

    test("Absent (qdrant result false, chroma NotFoundError) is not a failure and is logged as already absent, never as deleted") {
        val ans = Success200 ++ Map(
            "qdrant" -> (200, "{\"result\":false,\"status\":\"ok\",\"time\":0.001}"),
            "chroma" -> (404, "{\"error\":\"NotFoundError\",\"message\":\"Collection [vd_c] does not exists\"}")
        )
        val (failures, logs, order) = run(config(), ans)
        assert(failures.isEmpty, failures)
        assert(order == StoreOrder, order)
        assert(logs.contains("info" -> "Qdrant collection vd_q was already absent"), logs)
        assert(logs.contains("info" -> "Chroma collection vd_c was already absent"), logs)
        assert(!logs.exists(_._2.startsWith("Deleted Qdrant")) && !logs.exists(_._2.startsWith("Deleted Chroma")), logs)
    }

    test("Failed for every store: one failure each, in store order, identity keys, the closed reason and a warn line; no 'Deleted' line") {
        val ans = StoreOrder.map(_ -> (503, "upstream unavailable")).toMap
        val (failures, logs, order) = run(config(), ans)
        assert(order == StoreOrder, order)
        val reason = "the store answered HTTP 503"
        assert(
            failures == Seq(
                VectorContainers.Key("qdrant", "", "vd_q") -> reason,
                VectorContainers.Key("weaviate", "", "vdw") -> reason,
                VectorContainers.Key("milvus", "", "vd_m") -> reason,
                VectorContainers.Key("chroma", "", "vd_c") -> reason
            ),
            failures
        )
        assert(logs.count(_._1 == "warn") == 4, logs)
        assert(logs.contains("warn" -> ("Failed to delete Weaviate class VdW for pipeline 'vd_all': " + reason)), logs)
        assert(!logs.exists { case (_, m) => m.startsWith("Deleted ") || m.startsWith("Dropped ") }, logs)
    }

    test("a milvus 200 with a non-zero code is a failure; the other stores' removals are not") {
        val ans = Success200 + ("milvus" -> (200, "{\"code\":1100,\"message\":\"x\"}"))
        val (failures, _, _) = run(config(), ans)
        assert(failures == Seq(VectorContainers.Key("milvus", "", "vd_m") -> "the store reported an error"), failures)
    }

    test("NotSent (blank names): no request, no failure, a debug line per store") {
        val (failures, logs, order) = run(config("", "  ", "", " "), Success200)
        assert(failures.isEmpty, failures)
        assert(order.isEmpty, order)
        assert(logs.map(_._1) == Seq("debug", "debug", "debug", "debug"), logs)
    }

    test("a kept (shared) container is not requested; the others are") {
        val keep = Set(VectorContainers.Key("qdrant", "", "vd_q"), VectorContainers.Key("weaviate", "", "vdw"))
        val (failures, _, order) = run(config(), Success200, keep)
        assert(failures.isEmpty && order == Seq("milvus", "chroma"), order)
    }

    test("a failed secret read is 'could not be reached'; an absent secret is 'not configured'; neither sends a request") {
        val readFails: String => Try[Option[java.util.Map[String, String]]] = _ => Failure(new RuntimeException("vault down at vault.internal"))
        val (f1, logs1, o1) = run(config(), Success200, secretOf = readFails)
        assert(o1.isEmpty, o1)
        assert(f1.map(_._2) == Seq.fill(4)("the store could not be reached"), f1)
        assert(f1.map(_._1.store) == StoreOrder, f1)
        assert(logs1.count(_._1 == "warn") == 8, logs1)

        val (f2, _, o2) = run(config(), Success200, secretOf = _ => Success(None))
        assert(o2.isEmpty, o2)
        assert(f2.map(_._2) == Seq.fill(4)("the store is not configured"), f2)

        // A seam that throws is treated as a failed read; the delete never throws.
        val (f3, _, _) = run(config(), Success200, secretOf = _ => throw new IllegalStateException("boom"))
        assert(f3.map(_._2) == Seq.fill(4)("the store could not be reached"), f3)
    }

    test("the failure warning built from a weaviate failure names the class as configured") {
        val cfg = config()
        val (failures, _, _) = run(cfg, StoreOrder.map(_ -> (503, "")).toMap)
        val w = failures.collectFirst {
            case (k, r) if k.store == "weaviate" => VectorStoreDrop.warning(VectorContainers.displayKey(k, cfg), r, configDeleted = true)
        }
        assert(w.exists(_.startsWith("Could not remove weaviate class VdW: the store answered HTTP 503. ")), w)
    }
}
