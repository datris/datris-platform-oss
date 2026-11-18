package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * E2E finding: a probe that hangs answers 500 `ProbeUnavailable` at the
  * deadline instead of holding the request. Fixtures copied from
  * RestrictedExistingDestinationSpec, not shared. */
class RestrictedDestinationDeadlineSpec extends AnyFunSuite {

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
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(None, "sales-key", Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales")), isLegacyFullAccess = false)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def os(name: String, prefix: String, format: String = "parquet"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination = Destination(objectStore = ObjectStore(prefixKey = prefix, fileFormat = format))
        )

    private def multi(name: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination = Destination(
                database = Database(dbName = "analytics", schema = "public", table = "fresh_tbl", useSnowflake = true, credentialsSecret = "sf-main"),
                objectStore = ObjectStore(prefixKey = "fresh_prefix", fileFormat = "parquet")
            )
        )

    private def hang(calls: AtomicInteger): DestinationTargets.Target => Boolean = _ => {
        calls.incrementAndGet()
        Thread.sleep(60000)
        false
    }

    private def timed[T](body: => T): (T, Long) = {
        val t0 = System.nanoTime()
        val out = body
        (out, (System.nanoTime() - t0) / 1000000)
    }

    test("a probe that blocks past the deadline answers 500 ProbeUnavailable promptly") {
        val calls = new AtomicInteger(0)
        val (res, ms) = timed(
            PipelineAPIController.existingDestinationRefusal(req(bob), os("bob_o", "fresh"), null, Nil, env, hang(calls), _ => false, 300.millis)
        )
        assert(ms < 5000, ms)
        assert(res.get.getStatusCode.value == 500)
        assert(res.get.getBody == ApiErrors.errorBody(DestinationTargets.ProbeUnavailable), res.get.getBody)
        assert(calls.get == 1)
    }

    test("the deadline covers the whole check: a slow first probe leaves no time for the next") {
        val calls = new AtomicInteger(0)
        val slow: DestinationTargets.Target => Boolean = _ => { calls.incrementAndGet(); Thread.sleep(400); false }
        val (res, ms) = timed(PipelineAPIController.existingDestinationRefusal(req(bob), multi("bob_m"), null, Nil, env, slow, _ => false, 600.millis))
        assert(ms < 5000, ms)
        assert(res.get.getStatusCode.value == 500)
        assert(calls.get == 2)
    }

    test("a Unity Catalog state read that blocks past the deadline answers 500 ProbeUnavailable promptly") {
        val calls = new AtomicInteger(0)
        val hangingRead: String => Boolean = _ => { Thread.sleep(60000); true }
        val (res, ms) = timed(
            PipelineAPIController.existingDestinationRefusal(req(bob), os("bob_o", "fresh", "iceberg"), null, Nil, env, hang(calls), hangingRead, 300.millis)
        )
        assert(ms < 5000, ms)
        assert(res.get.getBody == ApiErrors.errorBody(DestinationTargets.ProbeUnavailable))
        assert(calls.get == 0)
    }

    test("a probe that returns in time is unaffected") {
        val existing: DestinationTargets.Target => Boolean = t => t.key.parts.last == "taken"
        assert(PipelineAPIController.existingDestinationRefusal(req(bob), os("bob_o", "fresh"), null, Nil, env, existing, _ => false, 5.seconds).isEmpty)
        val res = PipelineAPIController.existingDestinationRefusal(req(bob), os("bob_o", "taken"), null, Nil, env, existing, _ => false, 5.seconds)
        assert(res.get.getStatusCode.value == 400)
        // and the default deadline is the documented 30 seconds
        assert(ai.datris.util.DestinationProbe.DefaultDeadline == 30.seconds)
    }

    test("unrestricted callers never start a probe or a state read") {
        val calls = new AtomicInteger(0)
        val reads = new AtomicInteger(0)
        for (rk <- Seq(editor, fullKey, salesKey, null)) {
            val res = PipelineAPIController.existingDestinationRefusal(
                req(rk),
                os("x_o", "fresh", "iceberg"),
                null,
                Nil,
                env,
                hang(calls),
                _ => { reads.incrementAndGet(); true },
                1.millis
            )
            assert(res.isEmpty, String.valueOf(rk))
        }
        assert(calls.get == 0 && reads.get == 0)
    }
}
