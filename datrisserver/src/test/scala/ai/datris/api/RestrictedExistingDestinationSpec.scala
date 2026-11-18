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

import scala.collection.JavaConverters._

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it)
  * (plans/stories/catalog-scoped-user-access-warehouse-preview-target.md),
  * Acceptance, RestrictedExistingDestinationSpec.
  *
  * The save and version-restore entry points read Mongo and the warehouse,
  * so this spec pins the companion seams with the existence probe injected
  * (a counting `exists`). Fixtures copied from RestrictedQueryStampsSpec.
  * Assumed:
  * {{{
  * object PipelineAPIController {
  *   // None unless CapabilityCheck.isRestrictedSession(request);
  *   // 400 {"error": DestinationTargets.ExistsRefusal(SharedDatasets.label(key))} on the first existing target;
  *   // 500 ApiErrors.errorBody(DestinationTargets.ProbeUnavailable) when `exists` throws.
  *   def existingDestinationRefusal(request: HttpServletRequest, candidate: PipelineConfig, stored: PipelineConfig,
  *                                  others: Seq[PipelineConfig], env: SharedDatasets.Env,
  *                                  exists: DestinationTargets.Target => Boolean): Option[ResponseEntity[String]]
  * }
  * object EntityVersionAPIController {
  *   // the 5-argument form stays (HiddenDatasetWriterRuleSpec); this one (or a
  *   // defaulted 6th parameter on it) takes the probe:
  *   // vectorContainerRefusal, then hiddenWriterRefusal, then existingDestinationRefusal(snap, live, ...)
  *   def pipelineRestoreRefusal(request: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig,
  *                              others: Seq[PipelineConfig], env: SharedDatasets.Env,
  *                              exists: DestinationTargets.Target => Boolean): Option[ResponseEntity[String]]
  * }
  * }}}
  */
class RestrictedExistingDestinationSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/pipeline"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
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
            Capability.parseList(Seq(
                "pipeline:read:catalog=Sales",
                "pipeline:create:catalog=Sales",
                "pipeline:update:catalog=Sales",
                "tap:read:catalog=Sales",
                "metadata:read"
            )),
            isLegacyFullAccess = false
        )

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    // ------------------------------------------------------------ helpers

    private def snow(
        name: String,
        table: String,
        catalog: String = "Sales",
        owner: String = "erin",
        secret: String = "sf-main",
        authoritative: java.lang.Boolean = null
    ): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            authoritative = authoritative,
            destination = Destination(database =
                Database(dbName = "analytics", schema = "public", table = table, useSnowflake = true, credentialsSecret = secret, warehouse = "WH1")
            )
        )

    private def os(name: String, prefix: String, catalog: String = "Sales", owner: String = "erin"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(objectStore = ObjectStore(prefixKey = prefix, fileFormat = "parquet"))
        )

    private def pg(name: String, catalog: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    /** A probe that answers from a fixed set of existing table names /
      * prefixes (the last key part) and counts its calls. */
    private class Probe(existing: Set[String], fail: Boolean = false) extends (DestinationTargets.Target => Boolean) {
        var calls = 0
        val seen = scala.collection.mutable.ArrayBuffer.empty[DestinationTargets.Target]
        def apply(t: DestinationTargets.Target): Boolean = {
            calls += 1
            seen += t
            if (fail) throw new RuntimeException("connection refused: warehouse.internal:443 user=svc_loader")
            existing.exists(e => t.key.parts.lastOption.exists(_.equalsIgnoreCase(e)))
        }
    }

    private def refuse(
        r: HttpServletRequest,
        c: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        probe: DestinationTargets.Target => Boolean
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(r, c, stored, others, env, probe)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertStatus(r: Option[ResponseEntity[String]], status: Int): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == status, r.get.getBody)
        errorOf(r.get)
    }

    private val ExistsText =
        "The destination snowflake table ANALYTICS.PUBLIC.ORPHAN_TBL already exists. Choose a table or prefix that does not exist yet, " +
            "or ask a user with install-wide access to save this destination."

    private val others = Seq(
        snow("sales_a", "sales_tbl", owner = "carol"),
        pg("mkt_p", "Marketing", "mkt_tbl"),
        snow("hr_p", "hr_tbl", catalog = "HR", owner = "dave")
    )

    // ------------------------------------------------------------ tests

    test("a restricted session is refused with 400 when a fresh target exists, and the body names only the candidate's own coordinates") {
        assert(
            DestinationTargets.ExistsRefusal("snowflake table ANALYTICS.PUBLIC.ORPHAN_TBL") == ExistsText
        )
        val bodies = Seq(java.lang.Boolean.TRUE, java.lang.Boolean.FALSE, null).map { auth =>
            val probe = new Probe(Set("orphan_tbl"))
            val res = refuse(req(bob), snow("bob_new", "orphan_tbl", authoritative = auth), null, others, probe)
            val err = assertStatus(res, 400)
            assert(err == ExistsText, err)
            assert(probe.calls >= 1)
            res.get.getBody
        }
        assert(bodies.distinct.size == 1, bodies)
        val body = bodies.head
        for (bad <- Seq("sales_a", "mkt_p", "hr_p", "Marketing", "HR", "Sales", "carol", "dave", "erin", "bob_new", "sf-main", "WH1", "authoritative"))
            assert(!body.contains(bad), bad + " in " + body)

        // first existing target wins; an object-store prefix is labelled the SharedDatasets way
        val multi = PipelineConfig(
            name = "bob_multi",
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination = Destination(
                database = Database(dbName = "analytics", schema = "public", table = "fresh_tbl", useSnowflake = true, credentialsSecret = "sf-main"),
                objectStore = ObjectStore(prefixKey = "pt_orphan", fileFormat = "parquet")
            )
        )
        val res = refuse(req(bob), multi, null, others, new Probe(Set("pt_orphan")))
        val err = assertStatus(res, 400)
        val osKey = SharedDatasets.keysOf(multi, env).find(_.family == "objectstore").get
        assert(err == DestinationTargets.ExistsRefusal(SharedDatasets.label(osKey)), err)
        assert(err.contains("dev-data.pt_orphan"), err)
    }

    test("a restricted session saves when the fresh target does not exist") {
        val probe = new Probe(Set("orphan_tbl"))
        assert(refuse(req(bob), snow("bob_new", "bob_own_tbl"), null, others, probe).isEmpty)
        assert(probe.calls == 1, probe.seen)
        val osProbe = new Probe(Set("pt_orphan"))
        assert(refuse(req(bob), os("pt_new", "pt_new"), null, others, osProbe).isEmpty)
        assert(osProbe.calls == 1)
    }

    test("the probe is never called when the destination is unchanged") {
        val stored = snow("bob_p", "orphan_tbl")
        val probe = new Probe(Set("orphan_tbl"))
        // re-save with a tag change, schema change and keyFields: not probed, not refused
        val retagged = stored.copy(tags = java.util.Arrays.asList("changed"))
        val keyed = stored.copy(destination =
            stored.destination.copy(database = stored.destination.database.copy(keyFields = java.util.Arrays.asList("id"), truncateBeforeWrite = true))
        )
        for (c <- Seq(stored, retagged, keyed))
            assert(refuse(req(bob), c, stored, others :+ stored, probe).isEmpty, c)
        val oStored = os("pt_new", "pt_new")
        assert(refuse(req(bob), oStored.copy(tags = java.util.Arrays.asList("t")), oStored, others, probe).isEmpty)
        // a table a visible pipeline uses with the same connection is not probed either
        assert(refuse(req(bob), snow("bob_p2", "sales_tbl"), null, others, probe).isEmpty)
        assert(probe.calls == 0, probe.seen)
    }

    test("a table a hidden pipeline names is still probed (only visible pipelines exempt a target)") {
        val probe = new Probe(Set("hr_tbl"))
        val err = assertStatus(refuse(req(bob), snow("bob_p", "hr_tbl"), null, others, probe), 400)
        assert(probe.calls == 1)
        assert(!err.contains("hr_p") && !err.contains("HR ") && !err.contains("dave"), err)
    }

    test("retargeting from a table the pipeline wrote to an existing table is refused, and so is moving back") {
        val wrote = snow("bob_p", "bob_first")
        val moved = snow("bob_p", "orphan_tbl")
        // both tables exist: bob_first because the pipeline wrote it, orphan_tbl because someone else did
        val probe = new Probe(Set("bob_first", "orphan_tbl"))
        assertStatus(refuse(req(bob), moved, wrote, others :+ wrote, probe), 400)
        // the pipeline now sits on bob_second (fresh when chosen); moving back to bob_first is refused too
        val second = snow("bob_p", "bob_second")
        val err = assertStatus(refuse(req(bob), wrote, second, others :+ second, probe), 400)
        assert(err.contains("BOB_FIRST"), err)
        assert(probe.calls == 2)
    }

    test("a probe failure answers 500 with ProbeUnavailable and nothing else") {
        assert(
            DestinationTargets.ProbeUnavailable ==
                "Could not check whether the destination already exists; nothing was saved. Try again."
        )
        val probe = new Probe(Set.empty, fail = true)
        val res = refuse(req(bob), snow("bob_new", "bob_own_tbl"), null, others, probe)
        assert(res.isDefined)
        assert(res.get.getStatusCode.value == 500, res.get.getBody)
        assert(res.get.getBody == ApiErrors.errorBody(DestinationTargets.ProbeUnavailable), res.get.getBody)
        val obj = JsonParser.parseString(res.get.getBody).getAsJsonObject
        assert(obj.keySet.asScala == Set("error"))
        for (leak <- Seq("connection refused", "warehouse.internal", "svc_loader", "RuntimeException"))
            assert(!res.get.getBody.contains(leak), leak)
        assert(probe.calls == 1)
    }

    test("an unrestricted editor, a full key and a catalog-scoped key get None and the probe is never called") {
        val probe = new Probe(Set("orphan_tbl", "pt_orphan"))
        val failing = new Probe(Set.empty, fail = true)
        for (rk <- Seq(editor, fullKey, salesKey, null); p <- Seq(probe, failing)) {
            assert(refuse(req(rk), snow("x_new", "orphan_tbl"), null, others, p).isEmpty, String.valueOf(rk))
            assert(refuse(req(rk), os("x_os", "pt_orphan"), null, others, p).isEmpty, String.valueOf(rk))
            assert(refuse(req(rk), snow("x_new", "orphan_tbl"), snow("x_new", "elsewhere"), others, p).isEmpty, String.valueOf(rk))
        }
        assert(probe.calls == 0, probe.seen)
        assert(failing.calls == 0, failing.seen)
    }

    test("version restore onto an existing table is refused; a restore that keeps the live destination is not probed") {
        val live = snow("bob_p", "bob_live")
        val all = others :+ live
        val probe = new Probe(Set("orphan_tbl", "bob_live"))
        val snapOnto = snow("bob_p", "orphan_tbl").copy(version = 2)
        val err = assertStatus(EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snapOnto, live, all, env, probe), 400)
        assert(err == ExistsText, err)
        assert(probe.calls == 1)

        val snapKeeps = live.copy(version = 1, tags = java.util.Arrays.asList("older"))
        assert(EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snapKeeps, live, all, env, probe).isEmpty)
        assert(probe.calls == 1, probe.seen)
        // the 5-argument form keeps working, and does not probe a kept destination
        assert(EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snapKeeps, live, all, env).isEmpty)

        // a restore of a deleted pipeline (no live config) onto an existing table is refused too
        assertStatus(EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snapOnto, null, others, env, probe), 400)

        // everyone else: no probe
        val quiet = new Probe(Set("orphan_tbl"))
        for (rk <- Seq(editor, fullKey, salesKey))
            assert(EntityVersionAPIController.pipelineRestoreRefusal(req(rk), snapOnto, live, all, env, quiet).isEmpty, String.valueOf(rk))
        assert(quiet.calls == 0)
    }

    test("a hidden-writer conflict is reported before any probe runs") {
        val hidden = Seq(snow("mkt_s", "mkt_snow_tbl", catalog = "Marketing"))
        val probe = new Probe(Set("mkt_snow_tbl"))
        val snap = snow("bob_p", "mkt_snow_tbl")
        val res = EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snap, snow("bob_p", "bob_live"), hidden, env, probe)
        val err = assertStatus(res, 400)
        assert(err.contains("is already in use by another pipeline"), err)
        assert(!err.contains("already exists. Choose a table"), err)
        assert(probe.calls == 0, probe.seen)
        // the hidden-writer seam itself still answers, unchanged
        assert(PipelineAPIController.hiddenWriterRefusal(req(bob), snap, null, hidden, env).isDefined)
    }
}
