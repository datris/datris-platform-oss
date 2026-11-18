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

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * review findings 2, 4 and 5 at the controller seam. Fixtures copied from
  * RestrictedExistingDestinationSpec, not shared. */
class RestrictedDestinationHardeningSpec extends AnyFunSuite {

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
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales", "pipeline:update:catalog=Sales")),
            isLegacyFullAccess = false
        )

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def os(name: String, prefix: String, format: String = "parquet", catalog: String = "Sales", bucket: String = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "erin",
            destination = Destination(objectStore = ObjectStore(prefixKey = prefix, fileFormat = format, destinationBucketOverride = bucket))
        )

    private def sqls(name: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination =
                Destination(database = Database(dbName = "sales", schema = "dbo", table = table, useSqlServer = true, credentialsSecret = "mssql-main"))
        )

    private class Counter[A, B](answer: A => B) extends (A => B) {
        var calls = 0
        def apply(a: A): B = { calls += 1; answer(a) }
    }

    private def errorOf(r: Option[ResponseEntity[String]], status: Int): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == status, r.get.getBody)
        JsonParser.parseString(r.get.getBody).getAsJsonObject.get("error").getAsString
    }

    private def refuse(
        rk: ResolvedKey,
        c: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        exists: DestinationTargets.Target => Boolean,
        committed: String => Boolean = _ => false
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(req(rk), c, stored, others, env, exists, committed)

    test("a fresh object-store prefix that is not a plain path is refused before any probe (a/../b, a*, [a]b)") {
        for (prefix <- Seq("a/../b", "a*", "[a]b", "pt_orph*", "{pt_orphan}", "zz/../pt_orphan", "a//b")) {
            val probe = new Counter[DestinationTargets.Target, Boolean](_ => false)
            val err = errorOf(refuse(bob, os("bob_o", prefix), null, Nil, probe), 400)
            assert(err == DestinationTargets.UnsafePathRefusal, err)
            assert(probe.calls == 0, prefix)
        }
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => false)
        assert(errorOf(refuse(bob, os("bob_o", "a", bucket = "dev-*"), null, Nil, probe), 400) == DestinationTargets.UnsafePathRefusal)
        // a plain prefix is probed as before
        assert(refuse(bob, os("bob_o", "plain/prefix"), null, Nil, probe).isEmpty)
        assert(probe.calls == 1)
    }

    test("'..' under a visible pipeline's prefix is refused, though the cover would have skipped the probe") {
        val visible = os("bob_zz", "zz")
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => true)
        assert(errorOf(refuse(bob, os("bob_p2", "zz/../pt_orphan"), null, Seq(visible), probe), 400) == DestinationTargets.UnsafePathRefusal)
        assert(probe.calls == 0)
        // a real sub-prefix of the visible one is still covered
        assert(refuse(bob, os("bob_p2", "zz/sub"), null, Seq(visible), probe).isEmpty)
        assert(probe.calls == 0)
    }

    test("an unchanged stored prefix is not refused at save, and nobody else is refused for an unplain prefix") {
        val stored = os("bob_o", "legacy*")
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => true)
        assert(refuse(bob, stored.copy(tags = java.util.Arrays.asList("t")), stored, Nil, probe).isEmpty)
        for (rk <- Seq(editor, fullKey, salesKey, null))
            assert(refuse(rk, os("x_o", "a/../b"), null, Nil, probe).isEmpty, String.valueOf(rk))
        assert(probe.calls == 0)
    }

    test("the restricted object-store preview refuses a stored prefix that is not a plain path") {
        val err = errorOf(QueryAPIController.restrictedObjectStorePathRefusal(req(bob), os("bob_o", "legacy*")), 400)
        assert(err == DestinationTargets.UnsafePathRefusal)
        assert(QueryAPIController.restrictedObjectStorePathRefusal(req(bob), os("bob_o", "zz/../pt_orphan")).isDefined)
        assert(QueryAPIController.restrictedObjectStorePathRefusal(req(bob), os("bob_o", "plain")).isEmpty)
        assert(QueryAPIController.restrictedObjectStorePathRefusal(req(bob), null).isEmpty)
        for (rk <- Seq(editor, fullKey, salesKey, null))
            assert(QueryAPIController.restrictedObjectStorePathRefusal(req(rk), os("x", "legacy*")).isEmpty, String.valueOf(rk))
    }

    test("a SQL Server letter-case change is probed for a restricted session") {
        val stored = sqls("bob_q", "customers")
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => true)
        val err = errorOf(refuse(bob, sqls("bob_q", "Customers"), stored, Seq(stored), probe), 400)
        assert(err.startsWith("The destination sqlserver table sales.dbo.customers already exists."), err)
        assert(probe.calls == 1)
        // the same letter case: not probed
        assert(refuse(bob, stored, stored, Seq(stored), probe).isEmpty)
        assert(probe.calls == 1)
    }

    test("a new Iceberg pipeline whose name has a kept Unity Catalog catalog commit is refused; a failed state read is 500") {
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => false)
        val committed = new Counter[String, Boolean](_ == "dead_managed")
        val err = errorOf(refuse(bob, os("dead_managed", "fresh_prefix", format = "iceberg"), null, Nil, probe, committed), 400)
        assert(err == DestinationTargets.ExistsRefusal("object-store prefix dev-data.fresh_prefix"), err)
        assert(!err.contains("dead_managed"), err)
        assert(committed.calls == 1)
        assert(probe.calls == 0)

        // no kept commit: probed as usual and saved
        assert(refuse(bob, os("other_name", "fresh_prefix", format = "iceberg"), null, Nil, probe, committed).isEmpty)
        assert(probe.calls == 1)

        // the state read fails: 500, nothing else in the body
        val failing: String => Boolean = _ => throw new RuntimeException("mongo down at mongo.internal:27017")
        val res = refuse(bob, os("dead_managed", "fresh_prefix", format = "iceberg"), null, Nil, probe, failing)
        assert(res.get.getStatusCode.value == 500)
        assert(res.get.getBody == ApiErrors.errorBody(DestinationTargets.ProbeUnavailable), res.get.getBody)
    }

    test("the Unity Catalog state is not read for a stored pipeline, a non-Iceberg format or an unrestricted caller") {
        val probe = new Counter[DestinationTargets.Target, Boolean](_ => false)
        val committed = new Counter[String, Boolean](_ => true)
        val stored = os("p", "pfx", format = "iceberg")
        assert(refuse(
            bob,
            stored.copy(destination = stored.destination.copy(objectStore = stored.destination.objectStore.copy(prefixKey = "pfx2"))),
            stored,
            Nil,
            probe,
            committed
        ).isEmpty)
        assert(refuse(bob, stored, stored, Nil, probe, committed).isEmpty)
        assert(refuse(bob, os("p", "pfx", format = "parquet"), null, Nil, probe, committed).isEmpty)
        for (rk <- Seq(editor, fullKey, salesKey, null))
            assert(refuse(rk, os("p", "pfx", format = "iceberg"), null, Nil, probe, committed).isEmpty, String.valueOf(rk))
        assert(committed.calls == 0)
    }
}
