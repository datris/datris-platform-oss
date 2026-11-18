package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationProbe, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, Executors, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * review follow-up: the probe pool's rejection path. With every probe worker
  * busy, a further probe is refused at submit and the save answers 500
  * `ProbeUnavailable` (fail closed). The parked bodies are released at the
  * end, so the pool is left idle for other suites. */
class DestinationProbePoolSpec extends AnyFunSuite {

    private val PoolSize = 16

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    test("with every probe worker parked, the next probe is rejected and the save answers 500 ProbeUnavailable") {
        val release = new CountDownLatch(1)
        val started = new CountDownLatch(PoolSize)
        val callers = Executors.newFixedThreadPool(PoolSize)
        val finished = new AtomicInteger(0)
        try {
            for (_ <- 1 to PoolSize)
                callers.submit(new Runnable {
                    def run(): Unit = {
                        DestinationProbe.withDeadline(60.seconds, "parked") {
                            started.countDown()
                            release.await(60, TimeUnit.SECONDS)
                        }
                        finished.incrementAndGet()
                    }
                })
            assert(started.await(10, TimeUnit.SECONDS), "the probe workers did not all start")

            intercept[RejectedExecutionException](DestinationProbe.withDeadline(5.seconds, "seventeenth")(true))

            val calls = new AtomicInteger(0)
            val candidate = PipelineConfig(
                name = "bob_o",
                catalog = "Sales",
                createdByKeyLabel = "erin",
                destination = Destination(objectStore = ObjectStore(prefixKey = "fresh", fileFormat = "parquet"))
            )
            val res = PipelineAPIController.existingDestinationRefusal(
                req(bob),
                candidate,
                null,
                Nil,
                env,
                (_: DestinationTargets.Target) => { calls.incrementAndGet(); false },
                (_: String) => false,
                5.seconds
            )
            assert(res.isDefined)
            assert(res.get.getStatusCode.value == 500, res.get.getBody)
            assert(res.get.getBody == ApiErrors.errorBody(DestinationTargets.ProbeUnavailable), res.get.getBody)
            assert(calls.get == 0)
        } finally {
            release.countDown()
            callers.shutdown()
            assert(callers.awaitTermination(30, TimeUnit.SECONDS))
        }
        assert(finished.get == PoolSize)
        // the pool is usable again once the parked bodies are done
        assert(DestinationProbe.withDeadline(5.seconds)(7) == 7)
    }
}
