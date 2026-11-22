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
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * lead ruling 6: the production probe's wiring, driven for real.
  * `DestinationProbe.vectorContainerExists(t, readSecret)` reads the
  * environment's secret for the target's store (a mocked tenant
  * environment; `readSecret` instead of Vault) and asks a loopback server
  * standing in for the store. What cannot run without Vault (the one-line
  * production default and the controllers' default arguments) is pinned in
  * source. No store, Vault or database is reached. */
class VectorContainerProbeWiringSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort
    private val paths = new ConcurrentLinkedQueue[String]()
    private val ChromaBase = "/api/v2/tenants/default_tenant/databases/default_database/collections"

    private val answers: Map[String, (Int, String)] = Map(
        "/collections" -> (200 -> "{\"result\":{\"collections\":[{\"name\":\"vs_orphan\"}]},\"status\":\"ok\"}"),
        "/aliases" -> (200 -> "{\"result\":{\"aliases\":[]},\"status\":\"ok\"}"),
        "/v1/schema" -> (200 -> "{\"classes\":[{\"class\":\"VsOrphan\"}]}"),
        "/v2/vectordb/collections/has" -> (200 -> "{\"code\":0,\"data\":{\"has\":true}}"),
        (ChromaBase + "/vs_orphan") -> (200 -> "{\"id\":\"1\",\"name\":\"vs_orphan\"}"),
        (ChromaBase + "/vs_new") -> (404 -> "{\"error\":\"NotFoundError\"}"),
        (ChromaBase + "?limit=1") -> (200 -> "[]")
    )

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                val u = ex.getRequestURI
                val key = u.getRawPath + Option(u.getRawQuery).map("?" + _).getOrElse("")
                paths.add(key)
                ex.getRequestBody.readAllBytes()
                val (status, body) = answers.getOrElse(key, (500, ""))
                val bytes = body.getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
                if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
                ex.close()
            }
        )
        server.start()
    }

    override def afterAll(): Unit = if (server != null) server.stop(0)

    private def env: DatrisEnvironment = {
        val e = mock(classOf[DatrisEnvironment])
        when(e.qdrantSecretName).thenReturn("t1/qdrant")
        when(e.weaviateSecretName).thenReturn("t1/weaviate")
        when(e.milvusSecretName).thenReturn("t1/milvus")
        when(e.chromaSecretName).thenReturn("t1/chroma")
        e
    }

    private def target(store: String, exact: String): DestinationTargets.VectorContainerTarget =
        DestinationTargets.VectorContainerTarget(VectorContainers.Key(store, "", if (store == "weaviate") exact.toLowerCase else exact), exact)

    private def withEnv[T](body: => T): T =
        try { TenantContext.set(env); body }
        finally TenantContext.clear()

    /** A secret read answering every name with the loopback server. */
    private def loopRead(asked: ConcurrentLinkedQueue[String]): String => Try[Option[java.util.Map[String, String]]] = name => {
        asked.add(name)
        val m = new java.util.HashMap[String, String]()
        m.put("host", "127.0.0.1")
        m.put("port", (if (name.endsWith("/qdrant")) port + 1 else port).toString)
        Success(Some(m))
    }

    test("the production probe reads the environment's secret for the target's store and asks that store") {
        withEnv {
            for ((store, exact) <- Seq("qdrant" -> "vs_orphan", "weaviate" -> "VsOrphan", "milvus" -> "vs_orphan", "chroma" -> "vs_orphan")) {
                val asked = new ConcurrentLinkedQueue[String]()
                paths.clear()
                assert(DestinationProbe.vectorContainerExists(target(store, exact), loopRead(asked)), store)
                assert(asked.asScala.toList == List("t1/" + store), s"$store: ${asked.asScala.toList}")
                assert(paths.asScala.nonEmpty, store)
            }
            val asked = new ConcurrentLinkedQueue[String]()
            assert(!DestinationProbe.vectorContainerExists(target("chroma", "vs_new"), loopRead(asked)))
            assert(!DestinationProbe.vectorContainerExists(target("qdrant", "vs_new"), loopRead(asked)))
        }
    }

    test("an absent secret or a failed read throws and nothing is sent") {
        withEnv {
            paths.clear()
            intercept[Exception](DestinationProbe.vectorContainerExists(target("qdrant", "vs_orphan"), _ => Success(None)))
            intercept[Exception](DestinationProbe.vectorContainerExists(target("milvus", "vs_orphan"), _ => Failure(new RuntimeException("sealed"))))
            assert(paths.isEmpty, paths)
        }
    }

    test("vectorStoreSecretName maps each store to the environment's secret") {
        withEnv {
            assert(DestinationTargets.ProbedVectorStores.map(DestinationProbe.vectorStoreSecretName) == Seq(
                "t1/qdrant",
                "t1/weaviate",
                "t1/milvus",
                "t1/chroma"
            ))
            intercept[IllegalArgumentException](DestinationProbe.vectorStoreSecretName("pgvector"))
        }
    }

    private def source(rel: String): String =
        new String(
            java.nio.file.Files.readAllBytes(
                Seq(java.nio.file.Paths.get("src/main/scala/" + rel), java.nio.file.Paths.get("datrisserver/src/main/scala/" + rel))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            StandardCharsets.UTF_8
        )

    test("source pin: the production default reads the secret store, and both save and restore default to the production probe") {
        val probe = source("ai/datris/util/DestinationProbe.scala")
        assert(probe.contains("vectorContainerExists(t, name => SecretsUtil.tryGetSecretMap(name))"))
        val ctl = source("ai/datris/api/PipelineAPIController.scala")
        val restore = source("ai/datris/api/EntityVersionAPIController.scala")
        assert(ctl.contains("(t: DestinationTargets.VectorContainerTarget) => DestinationProbe.vectorContainerExists(t)"))
        assert(restore.contains("(t: DestinationTargets.VectorContainerTarget) => DestinationProbe.vectorContainerExists(t)"))
        // The save path still reaches the nine-argument form (it adds the container probe).
        assert(ctl.contains("(t: DestinationTargets.Target) => DestinationProbe.vectorTableExists(candidate, t)"))
    }
}
