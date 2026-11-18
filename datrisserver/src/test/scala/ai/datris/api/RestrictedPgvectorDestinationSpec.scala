package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationProbe, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.concurrent.duration._

/** Review finding: a restricted user could DROP a kept Postgres table
  * through a pgvector pipeline (save a pgvector pipeline on the table, then
  * delete it with its data). For restricted sessions a fresh pgvector table
  * that already exists is refused with the `ExistsRefusal` unless a pgvector
  * pipeline visible to the caller names it; unchanged tables are never
  * probed; a failed probe is the 500; everyone else starts no probe. The
  * one-container rule still applies on its own. */
class RestrictedPgvectorDestinationSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("vec_bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pgv(name: String, table: String, catalog: String = "Sales", schema: String = "public"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null))
        )

    private def pg(name: String, table: String, catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private class Probe(existing: Set[String], fail: Boolean = false, sleepMs: Long = 0) extends (DestinationTargets.Target => Boolean) {
        @volatile var calls = 0
        val seen = scala.collection.mutable.ArrayBuffer.empty[DestinationTargets.Target]
        def apply(t: DestinationTargets.Target): Boolean = synchronized {
            calls += 1
            seen += t
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (fail) throw new RuntimeException("connection refused: pgvector.internal:5432")
            existing.contains(t.key.parts.last)
        }
    }

    /** The other-families probe: must never see a pgvector target. */
    private class NoOther extends (DestinationTargets.Target => Boolean) {
        var calls = 0
        def apply(t: DestinationTargets.Target): Boolean = { calls += 1; throw new AssertionError("other-family probe called for " + t) }
    }

    private def refuse(
        r: HttpServletRequest,
        c: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        vec: DestinationTargets.Target => Boolean,
        deadline: FiniteDuration = 5.seconds
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(r, c, stored, others, env, new NoOther, _ => false, deadline, vec)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    test("the finding: a restricted pgvector save onto a kept table no config names is refused with the ExistsRefusal") {
        val probe = new Probe(Set("orders"))
        val r = refuse(req(bob), pgv("bob_v", "orders"), null, Seq(pg("payroll_live", "payroll", catalog = "Finance")), probe)
        assert(r.map(_.getStatusCode.value).contains(400), r)
        assert(errorOf(r.get) == DestinationTargets.ExistsRefusal("pgvector table public.orders"))
        assert(probe.calls == 1)
        val t = probe.seen.head
        assert(t.key == SharedDatasets.Key("pgtable", "pgvector", Seq("datris", "public", "orders")))
        assert(t.exact == Seq("public", "orders"))
    }

    test("a brand-new pgvector table saves (probed, absent)") {
        val probe = new Probe(Set("orders"))
        assert(refuse(req(bob), pgv("bob_v", "bob_chunks"), null, Nil, probe).isEmpty)
        assert(probe.calls == 1)
    }

    test("re-saving one's own pgvector pipeline after its first load created the table is never probed") {
        val probe = new Probe(Set("bob_chunks"), fail = true)
        assert(refuse(req(bob), pgv("bob_v", "bob_chunks"), pgv("bob_v", "bob_chunks"), Nil, probe).isEmpty)
        assert(probe.calls == 0)
        // a null schema stored, "public" now: the same table, not probed
        assert(refuse(req(bob), pgv("bob_v", "bob_chunks"), pgv("bob_v", "bob_chunks", schema = null), Nil, probe).isEmpty)
        assert(probe.calls == 0)
    }

    test("a changed pgvector table, schema or letter case is probed") {
        val probe = new Probe(Set("orders", "Bob_chunks"))
        assert(refuse(req(bob), pgv("bob_v", "orders"), pgv("bob_v", "bob_chunks"), Nil, probe).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pgv("bob_v", "Bob_chunks"), pgv("bob_v", "bob_chunks"), Nil, probe).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pgv("bob_v", "bob_chunks", schema = "vec"), pgv("bob_v", "bob_chunks"), Nil, probe).isEmpty)
        assert(probe.calls == 3)
    }

    test("a stored config the caller cannot see grandfathers nothing") {
        val probe = new Probe(Set("orders"))
        val hiddenStored = pgv("bob_v", "orders", catalog = "Finance")
        assert(refuse(req(bob), pgv("bob_v", "orders"), hiddenStored, Nil, probe).map(_.getStatusCode.value).contains(400))
        assert(probe.calls == 1)
    }

    test("a visible pgvector pipeline on the same table exempts the probe; a hidden one or a visible postgres table does not") {
        val probe = new Probe(Set("orders"), fail = true)
        assert(refuse(req(bob), pgv("bob_v", "orders"), null, Seq(pgv("sales_v", "orders")), probe).isEmpty)
        assert(probe.calls == 0)
        val p2 = new Probe(Set("orders"))
        assert(refuse(req(bob), pgv("bob_v", "orders"), null, Seq(pgv("fin_v", "orders", catalog = "Finance")), p2).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pgv("bob_v", "orders"), null, Seq(pg("sales_p", "orders")), p2).map(_.getStatusCode.value).contains(400))
        assert(p2.calls == 2)
    }

    test("the one-container rule still refuses a table a visible pgvector pipeline names") {
        val r = PipelineAPIController.vectorContainerRefusal(req(bob), pgv("bob_v", "orders"), null, Seq(pgv("sales_v", "orders")))
        assert(r.map(_.getStatusCode.value).contains(400), r)
    }

    test("any probe failure is the 500 ProbeUnavailable; nothing is saved") {
        val r = refuse(req(bob), pgv("bob_v", "orders"), null, Nil, new Probe(Set.empty, fail = true))
        assert(r.map(_.getStatusCode.value).contains(500), r)
        assert(errorOf(r.get) == DestinationTargets.ProbeUnavailable)
    }

    test("the pgvector probe runs under the save's deadline") {
        val r = refuse(req(bob), pgv("bob_v", "orders"), null, Nil, new Probe(Set.empty, sleepMs = 3000), deadline = 200.millis)
        assert(r.map(_.getStatusCode.value).contains(500), r)
        assert(errorOf(r.get) == DestinationTargets.ProbeUnavailable)
    }

    test("the pgvector probe takes one of the caller's slots: over the limit it is the 429 and the probe is never called") {
        val owner = PipelineAPIController.probeOwner(req(bob))
        val release = new java.util.concurrent.CountDownLatch(1)
        val started = new java.util.concurrent.CountDownLatch(DestinationProbe.MaxPerOwner)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(DestinationProbe.MaxPerOwner)
        try {
            for (_ <- 1 to DestinationProbe.MaxPerOwner)
                pool.submit(new Runnable {
                    def run(): Unit =
                        DestinationProbe.withDeadline(60.seconds, "parked", owner) {
                            started.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS)
                        }
                })
            assert(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val probe = new Probe(Set.empty)
            val r = refuse(req(bob), pgv("bob_v", "orders"), null, Nil, probe)
            assert(r.map(_.getStatusCode.value).contains(429), r)
            assert(probe.calls == 0)
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    test("a changed pgvector table whose name is not a plain quoted identifier is refused before any probe") {
        val probe = new Probe(Set.empty, fail = true)
        for (c <- Seq(pgv("bob_v", "x\"; DROP TABLE \"orders"), pgv("bob_v", "orders", schema = "pub\"lic"), pgv("bob_v", "orders", schema = ""))) {
            val r = refuse(req(bob), c, null, Nil, probe)
            assert(r.map(_.getStatusCode.value).contains(400), r)
            assert(errorOf(r.get) == DestinationTargets.UnsafeVectorTableRefusal)
        }
        assert(probe.calls == 0)
    }

    test("unrestricted users, API keys and auth off start no pgvector probe") {
        val probe = new Probe(Set("orders"), fail = true)
        val unauth = mock(classOf[HttpServletRequest])
        for (r <- Seq(req(editor), req(fullKey), unauth))
            assert(refuse(r, pgv("x", "orders"), null, Nil, probe).isEmpty)
        assert(probe.calls == 0)
    }

    test("a version restore onto an existing pgvector table is refused the same way") {
        val probe = new Probe(Set("orders"))
        val snap = pgv("bob_v", "orders").copy(version = 2)
        val r = EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snap, pgv("bob_v", "bob_chunks"), Nil, env, new NoOther, probe)
        assert(r.map(_.getStatusCode.value).contains(400), r)
        assert(errorOf(r.get) == DestinationTargets.ExistsRefusal("pgvector table public.orders"))
        // restoring the live table back: unchanged, not probed
        val quiet = new Probe(Set("bob_chunks"), fail = true)
        assert(
            EntityVersionAPIController
                .pipelineRestoreRefusal(req(bob), pgv("bob_v", "bob_chunks").copy(version = 2), pgv("bob_v", "bob_chunks"), Nil, env, new NoOther, quiet)
                .isEmpty
        )
        assert(quiet.calls == 0)
    }
}
