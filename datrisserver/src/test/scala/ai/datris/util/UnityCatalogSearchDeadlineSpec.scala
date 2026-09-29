package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}

/** Story: Unity Catalog 2: discovery — per-secret deadline for
  * `find_data includeUnityCatalog` (review follow-up 2). */
class UnityCatalogSearchDeadlineSpec extends AnyFunSuite {

    test("a task inside the deadline returns its value") {
        assert(UnityCatalogDiscovery.withDeadline(5)(42) == Right(42))
    }

    test("a task past the deadline yields Left(timed out after Ns)") {
        val started = System.currentTimeMillis()
        val out = UnityCatalogDiscovery.withDeadline(1)({ Thread.sleep(10000); 1 })
        assert(out == Left("timed out after 1s"))
        assert(System.currentTimeMillis() - started < 5000)
    }

    test("an exception from the task is rethrown unwrapped") {
        val e = intercept[IllegalStateException](UnityCatalogDiscovery.withDeadline(5)(throw new IllegalStateException("boom")))
        assert(e.getMessage == "boom")
    }

    test("the caller's tenant environment is visible on the task thread") {
        val env = DatrisEnvironment(
            initialized = true,
            environment = "deadline-spec",
            fileNotifierQueue = null,
            ttlFileNotifierQueueMessages = 0,
            pipelineTopic = null,
            pipelineTableName = null,
            archivedMetadataTableName = null,
            pipelineStatusTableName = null,
            fileNotifierMessageTableName = null,
            dataPullTableName = null,
            useApiKeys = false,
            apiKeysSecretName = null,
            postgresSecretName = null,
            mongoDbSecretName = null,
            kafkaProducerSecretName = null,
            kafkaConsumerConfig = null,
            mongoDbConfig = null,
            minIOConfig = null,
            activeMQConfig = null,
            aiConfig = null,
            aiEnabled = false,
            embeddingSecretName = null,
            qdrantSecretName = null,
            weaviateSecretName = null,
            milvusSecretName = null,
            chromaSecretName = null,
            pgvectorSecretName = null,
            multiTenant = false,
            tapTableName = null
        )
        TenantContext.set(env)
        try assert(UnityCatalogDiscovery.withDeadline(5)(TenantContext.get().map(_.environment)) == Right(Some("deadline-spec")))
        finally TenantContext.clear()
    }

    test("search timeout defaults to 45 s and honours a positive override") {
        val key = "datris.unityCatalogSearchTimeoutSeconds"
        try {
            sys.props -= key
            if (sys.env.get("DATRIS_UNITY_CATALOG_SEARCH_TIMEOUT_SECONDS").isEmpty)
                assert(UnityCatalogDiscovery.searchTimeoutSeconds == 45L)
            sys.props += key -> "12"
            assert(UnityCatalogDiscovery.searchTimeoutSeconds == 12L)
            sys.props += key -> "0"
            assert(UnityCatalogDiscovery.searchTimeoutSeconds == 45L)
            sys.props += key -> "abc"
            assert(UnityCatalogDiscovery.searchTimeoutSeconds == 45L)
        } finally sys.props -= key
    }

    test("an Error from the task arrives wrapped in an Exception, never as a raw Error") {
        val e = intercept[Exception](UnityCatalogDiscovery.withDeadline(5)(throw new AssertionError("fatal-ish")))
        assert(e.getCause.isInstanceOf[AssertionError])
    }

    test("a full search pool rejects the next search with Left(search pool busy)") {
        val n = UnityCatalogDiscovery.SearchPoolSize
        val started = new CountDownLatch(n)
        val release = new CountDownLatch(1)
        val callers = (1 to n).map { _ =>
            val t = new Thread(() => {
                // Retry while a thread from an earlier (interrupted) test is
                // still winding down, so the pool always ends up exactly full.
                var r: Either[String, Int] = Left("search pool busy")
                while (r == Left("search pool busy")) {
                    r = UnityCatalogDiscovery.withDeadline(30)({ started.countDown(); release.await(); 0 })
                    if (r == Left("search pool busy")) Thread.sleep(10)
                }
            })
            t.setDaemon(true)
            t.start()
            t
        }
        try {
            assert(started.await(10, TimeUnit.SECONDS), "pool never filled")
            assert(UnityCatalogDiscovery.withDeadline(5)(1) == Left("search pool busy"))
        } finally {
            release.countDown()
            callers.foreach(_.join(10000))
        }
    }
}
