package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Pipeline delete keep-data review, finding 2 (lead decision): the
  * restricted-session "new destinations only" rule covers the platform's
  * Postgres tables and MongoDB collections, so a kept table or collection
  * (or any other existing one) cannot be adopted by a restricted user's save
  * or version restore. Same rules as the warehouse families: fresh targets
  * only, a visible pipeline's target exempts, a failed probe is the 500,
  * unrestricted callers start no probe. */
class RestrictedPostgresMongoDestinationSpec extends AnyFunSuite {

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

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pg(name: String, table: String, catalog: String = "Sales", schema: String = "public"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = schema, table = table, usePostgres = true))
        )

    private def mongo(name: String, coll: String, catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", table = coll, useMongoDB = true))
        )

    private class Probe(existing: Set[String], fail: Boolean = false) extends (DestinationTargets.Target => Boolean) {
        var calls = 0
        val seen = scala.collection.mutable.ArrayBuffer.empty[DestinationTargets.Target]
        def apply(t: DestinationTargets.Target): Boolean = {
            calls += 1
            seen += t
            if (fail) throw new RuntimeException("connection refused: db.internal:5432")
            existing.contains(t.key.parts.last)
        }
    }

    private def refuse(r: HttpServletRequest, c: PipelineConfig, stored: PipelineConfig, others: Seq[PipelineConfig], probe: Probe) =
        PipelineAPIController.existingDestinationRefusal(r, c, stored, others, env, probe)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private val financePayroll = pg("payroll_live", "payroll_other", catalog = "Finance")

    test("a restricted save onto an existing postgres table (e.g. one kept by a delete) is refused with the ExistsRefusal naming only its own table") {
        val probe = new Probe(Set("payroll"))
        val r = refuse(req(bob), pg("bob_p", "payroll"), null, Seq(financePayroll), probe)
        assert(r.isDefined && r.get.getStatusCode.value == 400, r)
        assert(errorOf(r.get) == DestinationTargets.ExistsRefusal("postgres table datris.public.payroll"), errorOf(r.get))
        assert(!r.get.getBody.contains("Finance") && !r.get.getBody.contains("payroll_live"))
        assert(probe.calls == 1)
        assert(probe.seen.head.key == SharedDatasets.Key("pgtable", "postgres", Seq("datris", "public", "payroll")))
        assert(probe.seen.head.connection == Nil)
    }

    test("a restricted save onto an existing mongodb collection is refused the same way") {
        val probe = new Probe(Set("events"))
        val r = refuse(req(bob), mongo("bob_m", "events"), null, Nil, probe)
        assert(r.map(_.getStatusCode.value).contains(400), r)
        assert(errorOf(r.get) == DestinationTargets.ExistsRefusal("mongodb collection datris.events"))
    }

    test("a brand-new pipeline whose postgres table or mongodb collection does not exist yet saves") {
        val probe = new Probe(Set.empty)
        assert(refuse(req(bob), pg("bob_p", "fresh_tbl"), null, Nil, probe).isEmpty)
        assert(refuse(req(bob), mongo("bob_m", "fresh_coll"), null, Nil, probe).isEmpty)
        assert(probe.calls == 2)
    }

    test("re-saving the user's own postgres or mongodb pipeline with the same table is never probed or refused") {
        val probe = new Probe(Set("orders", "events"), fail = true)
        val storedPg = pg("bob_p", "orders")
        val storedMg = mongo("bob_m", "events")
        def edited(c: PipelineConfig): PipelineConfig =
            c.copy(destination = c.destination.copy(database = c.destination.database.copy(truncateBeforeWrite = true)))
        assert(refuse(req(bob), storedPg, storedPg, Nil, probe).isEmpty)
        assert(refuse(req(bob), edited(storedPg), storedPg, Nil, probe).isEmpty)
        assert(refuse(req(bob), edited(storedMg), storedMg, Nil, probe).isEmpty)
        assert(probe.calls == 0)
    }

    test("moving the user's own pipeline to another existing table, schema or collection is refused; to a new one saves") {
        val stored = pg("bob_p", "orders")
        assert(refuse(req(bob), pg("bob_p", "payroll"), stored, Nil, new Probe(Set("payroll"))).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pg("bob_p", "orders", schema = "hr"), stored, Nil, new Probe(Set("orders"))).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pg("bob_p", "orders_v2"), stored, Nil, new Probe(Set("payroll"))).isEmpty)
        val storedMg = mongo("bob_m", "events")
        assert(refuse(req(bob), mongo("bob_m", "audit"), storedMg, Nil, new Probe(Set("audit"))).map(_.getStatusCode.value).contains(400))
    }

    test("a table or collection a pipeline the user can see already writes is not probed") {
        val probe = new Probe(Set("orders", "events"), fail = true)
        assert(refuse(req(bob), pg("bob_p", "orders"), null, Seq(pg("sales_orders", "orders")), probe).isEmpty)
        assert(refuse(req(bob), mongo("bob_m", "events"), null, Seq(mongo("sales_events", "events")), probe).isEmpty)
        assert(probe.calls == 0)
        // A hidden pipeline's table exempts nothing (it is probed).
        val p2 = new Probe(Set("payroll_other"))
        assert(refuse(req(bob), pg("bob_p", "payroll_other"), null, Seq(financePayroll), p2).map(_.getStatusCode.value).contains(400))
        assert(p2.calls == 1)
        // A visible pgvector pipeline on the same table name does not exempt a postgres target.
        val pgv = PipelineConfig(
            name = "sales_vec",
            catalog = "Sales",
            destination = Destination(pgvector = PGVectorConfig("orders", "public", ChunkingConfig(), null, null, null))
        )
        val p3 = new Probe(Set("orders"))
        assert(refuse(req(bob), pg("bob_p", "orders"), null, Seq(pgv), p3).map(_.getStatusCode.value).contains(400))
    }

    test("a failed postgres or mongodb probe is the 500 and nothing is saved") {
        Seq(pg("bob_p", "t1"), mongo("bob_m", "c1")).foreach { c =>
            val r = refuse(req(bob), c, null, Nil, new Probe(Set.empty, fail = true))
            assert(r.map(_.getStatusCode.value).contains(500), r)
            assert(errorOf(r.get) == DestinationTargets.ProbeUnavailable)
        }
    }

    test("unrestricted users, API keys and auth off start no probe for postgres or mongodb") {
        val probe = new Probe(Set("payroll", "events"), fail = true)
        val unauth = mock(classOf[HttpServletRequest])
        for (r <- Seq(req(editor), req(fullKey), unauth)) {
            assert(refuse(r, pg("x", "payroll"), null, Nil, probe).isEmpty)
            assert(refuse(r, mongo("y", "events"), null, Nil, probe).isEmpty)
        }
        assert(probe.calls == 0)
    }

    test("a pgvector pipeline is not probed by this rule") {
        val pgv = PipelineConfig(
            name = "bob_v",
            catalog = "Sales",
            destination = Destination(pgvector = PGVectorConfig("chunks", "public", ChunkingConfig(), null, null, null))
        )
        val probe = new Probe(Set("chunks"), fail = true)
        assert(refuse(req(bob), pgv, null, Nil, probe).isEmpty)
        assert(probe.calls == 0)
    }
}
