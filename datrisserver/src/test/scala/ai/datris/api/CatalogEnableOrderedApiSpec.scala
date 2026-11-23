package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, DatrisEnvironment, PipelineStatusRollup, ResolvedKey, TapConfig, User}
import ai.datris.util.{CatalogEnableJob, CatalogEnableJobSpec, TapDependencies}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

/** Story: Enable a catalog's taps in dependency order
  * (plans/stories/tap-dependency-order-enable.md), Acceptance bullet 5
  * (controller): a key without `tap:run` on one member -> 403 and the status
  * stays `{active:false}`; a catalog with a cycle -> 409 with `cycles`;
  * `PUT /catalog/{name}` during a job -> 409.
  *
  * `POST /catalog/{name}/enable-ordered` loads the catalog's taps from Mongo,
  * so this spec pins the pure seam it runs before anything starts (same style
  * as `TapAPIController.dependencySaveProblem`):
  *
  * {{{
  * object CatalogAPIController {
  *   /** The refusal `POST /catalog/{name}/enable-ordered` returns before a job
  *     * starts, or None. `taps` = the catalog's member taps (no placeholders).
  *     *  - 409 {"error": "...", "cycles": [[...], ...]} when TapDependencies.order has cycles;
  *     *  - 403 when the caller's key fails CapabilityCheck.assertScope for
  *     *    `tap:update` or `tap:run` on any member (scope context = catalog + owner). */
  *   def enableOrderedRefusal(request: HttpServletRequest, taps: Seq[TapConfig]): Option[ResponseEntity[String]]
  * }
  * }}}
  *
  * The rename guard is driven through the real `renameCatalog` entry point:
  * the "enable job in progress" refusal must come before any Mongo read (as
  * the reserved-name and newName checks do), since it needs only the job
  * registry. Job contract: see CatalogEnableJobSpec.
  */
class CatalogEnableOrderedApiSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var savedEnv: DatrisEnvironment = _

    override def beforeAll(): Unit = {
        savedEnv = DatrisEnvironment.values
        DatrisEnvironment.values = CatalogEnableJobSpec.env
    }

    override def afterAll(): Unit = DatrisEnvironment.values = savedEnv

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def req(k: Option[ResolvedKey], method: String = "POST", path: String = "/api/v1/catalog/sales/enable-ordered"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => k.orNull)
        r
    }

    private def key(caps: String*): ResolvedKey =
        ResolvedKey(None, "k", Capability.parseList(caps), isLegacyFullAccess = false)

    private def tap(name: String, catalog: String, owner: String = "k", dependsOn: java.util.List[String] = null): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "p", catalog = catalog, enabled = false, createdByKeyLabel = owner, dependsOn = dependsOn)

    test("a key without tap:run on one member is refused with 403 and no job starts") {
        val cat = "ceo-denied"
        val taps = Seq(tap("a", cat, owner = "k"), tap("b", cat, owner = "someone-else", dependsOn = jl("a")))
        val scoped = key("tap:read", "tap:update", "tap:run:owner=self")
        val r = CatalogAPIController.enableOrderedRefusal(req(Some(scoped)), taps)
        assert(r.map(_.getStatusCode.value()).contains(403), r)
        assert(!CatalogEnableJob.activeFor(cat))
        assert(CatalogEnableJob.statusBody(cat).toString == """{"active":false}""")
    }

    test("a key without tap:update is refused with 403 even with tap:run") {
        val cat = "ceo-noupdate"
        val taps = Seq(tap("a", cat), tap("b", cat, dependsOn = jl("a")))
        val r = CatalogAPIController.enableOrderedRefusal(req(Some(key("tap:read", "tap:run"))), taps)
        assert(r.map(_.getStatusCode.value()).contains(403), r)
        assert(!CatalogEnableJob.activeFor(cat))
    }

    test("a key holding tap:update and tap:run on every member passes") {
        val cat = "ceo-ok"
        val taps = Seq(tap("a", cat), tap("b", cat, dependsOn = jl("a")))
        assert(
            CatalogAPIController.enableOrderedRefusal(
                req(Some(key("tap:update", "tap:run:owner=self", "pipeline:run:owner=self", "tap:read:owner=self", "pipeline:read:owner=self"))),
                taps,
                _ => ai.datris.model.PipelineConfig(name = "p", createdByKeyLabel = "k")
            ).isEmpty
        )
        assert(CatalogAPIController.enableOrderedRefusal(req(None), taps).isEmpty, "no resolved key: the interceptor decided")
    }

    test("a catalog with a dependency cycle is refused with 409 whose body has cycles") {
        val cat = "ceo-cycle"
        val taps = Seq(tap("a", cat, dependsOn = jl("b")), tap("b", cat, dependsOn = jl("a")), tap("c", cat))
        val r = CatalogAPIController.enableOrderedRefusal(req(None), taps)
        assert(r.map(_.getStatusCode.value()).contains(409), r)
        val body = JsonParser.parseString(r.get.getBody).getAsJsonObject
        assert(body.has("error"), r.get.getBody)
        assert(body.has("cycles"), r.get.getBody)
        val cycles = body.getAsJsonArray("cycles").asScala.map(_.getAsJsonArray.asScala.map(_.getAsString).toList).toList
        assert(cycles == List(List("a", "b")), cycles)
        assert(!CatalogEnableJob.activeFor(cat))
    }

    test("PUT /catalog/{name} while an enable job runs is refused with 409") {
        val cat = "ceo-rename"
        val release = new AtomicBoolean(false)
        val deps = new CatalogEnableJobSpec.FakeDeps(Seq(tap("a", cat)))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = "tok-ceo")
        deps.rollupFor("tok-ceo") = () =>
            PipelineStatusRollup(allDone = release.get(), status = if (release.get()) "success" else "processing", jobs = new java.util.ArrayList())
        val plan = TapDependencies.order(Seq(tap("a", cat).copy(lastTestRunStatus = "success")))
        CatalogEnableJob.start(cat, "spec-actor", plan, deps) match {
            case Left(err) => fail("start refused: " + err)
            case Right(_) =>
        }
        try {
            assert(CatalogEnableJob.activeFor(cat))
            val r = new CatalogAPIController().renameCatalog(null, cat, """{"newName":"ceo-renamed"}""", req(None, "PUT", "/api/v1/catalog/" + cat))
            assert(r.getStatusCode.value() == 409, r.getStatusCode + " " + r.getBody)
            assert(r.getBody.contains("enable job in progress"), r.getBody)
        } finally {
            CatalogEnableJob.cancel(cat)
            release.set(true)
        }
    }

    test("GET and DELETE enable-ordered apply the key's catalog scope (review finding 1)") {
        val cat = "ceo-scope"
        val release = new AtomicBoolean(false)
        val deps = new CatalogEnableJobSpec.FakeDeps(Seq(tap("a", cat)))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = "tok-scope")
        deps.rollupFor("tok-scope") = () =>
            PipelineStatusRollup(allDone = release.get(), status = if (release.get()) "success" else "processing", jobs = new java.util.ArrayList())
        val plan = TapDependencies.order(Seq(tap("a", cat).copy(lastTestRunStatus = "success")))
        CatalogEnableJob.start(cat, "spec-actor", plan, deps) match {
            case Left(err) => fail("start refused: " + err)
            case Right(_) =>
        }
        val path = "/api/v1/catalog/" + cat + "/enable-ordered"
        val controller = new CatalogAPIController()
        try {
            val otherRead = controller.enableOrderedStatus(null, cat, req(Some(key("tap:read:catalog=ceo-other")), "GET", path))
            assert(otherRead.getStatusCode.value() == 403, otherRead.getBody)
            assert(!otherRead.getBody.contains("tok-scope"), "no job detail leaks: " + otherRead.getBody)
            val otherCancel = controller.cancelEnableOrdered(null, cat, req(Some(key("tap:run:catalog=ceo-other")), "DELETE", path))
            assert(otherCancel.getStatusCode.value() == 403, otherCancel.getBody)
            assert(CatalogEnableJob.activeFor(cat), "a refused cancel leaves the job running")

            val ownRead = controller.enableOrderedStatus(null, cat, req(Some(key("tap:read:catalog=" + cat)), "GET", path))
            assert(ownRead.getStatusCode.value() == 200, ownRead.getBody)
            assert(JsonParser.parseString(ownRead.getBody).getAsJsonObject.get("active").getAsBoolean)
            // catalog-ops-scope-edges D3: a cancelling key must also read the catalog's taps
            val ownCancel = controller.cancelEnableOrdered(null, cat, req(Some(key("tap:run:catalog=" + cat, "tap:read:catalog=" + cat)), "DELETE", path))
            assert(ownCancel.getStatusCode.value() == 200, ownCancel.getBody)
            assert(CatalogEnableJob.status(cat).exists(_.cancel.get()))
        } finally {
            CatalogEnableJob.cancel(cat)
            release.set(true)
        }
    }

    test("GET enable-ordered with no job is {active:false} for any key") {
        val r = new CatalogAPIController()
            .enableOrderedStatus(null, "ceo-none", req(Some(key("tap:read:catalog=ceo-other")), "GET", "/api/v1/catalog/ceo-none/enable-ordered"))
        assert(r.getStatusCode.value() == 200)
        assert(r.getBody == """{"active":false}""", r.getBody)
    }

    // ------------------------------------------------------------ Story: catalog-ops-scope-edges
    // (plans/stories/catalog-ops-scope-edges.md), Acceptance bullet 4, owner decision D3:
    // cancelling a catalog's ordered enable needs tap read on the catalog's taps
    // for an API key; a key that may not read them gets the same 403 whether or
    // not a job is running. Driven through the real cancelEnableOrdered (no Mongo).

    private def withJob(cat: String, actor: String = "spec-actor")(body: => Unit): Unit = {
        val release = new AtomicBoolean(false)
        val deps = new CatalogEnableJobSpec.FakeDeps(Seq(tap("a", cat, owner = actor)))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = "tok-" + cat)
        deps.rollupFor("tok-" + cat) = () =>
            PipelineStatusRollup(allDone = release.get(), status = if (release.get()) "success" else "processing", jobs = new java.util.ArrayList())
        CatalogEnableJob.start(cat, actor, TapDependencies.order(Seq(tap("a", cat, owner = actor).copy(lastTestRunStatus = "success"))), deps) match {
            case Left(err) => fail("start refused: " + err)
            case Right(_) =>
        }
        try body
        finally {
            CatalogEnableJob.cancel(cat)
            release.set(true)
        }
    }

    private def cancel(rk: Option[ResolvedKey], cat: String): org.springframework.http.ResponseEntity[String] =
        new CatalogAPIController().cancelEnableOrdered(null, cat, req(rk, "DELETE", "/api/v1/catalog/" + cat + "/enable-ordered"))

    private def noJobBody(cat: String): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty("error", "No enable job is running for catalog '" + cat + "'")
        o.toString
    }

    private def assertCancelled(r: org.springframework.http.ResponseEntity[String], cat: String, who: String): Unit = {
        assert(r.getStatusCode.value() == 200, who + ": " + r.getBody)
        val o = JsonParser.parseString(r.getBody).getAsJsonObject
        assert(o.keySet.asScala.toSet == Set("cancelled", "jobId"), who + ": " + r.getBody)
        assert(o.get("cancelled").getAsBoolean)
        assert(CatalogEnableJob.status(cat).exists(j => j.id == o.get("jobId").getAsString && j.cancel.get()), who)
    }

    private def assertNoJob(r: org.springframework.http.ResponseEntity[String], cat: String, who: String): Unit = {
        assert(r.getStatusCode.value() == 404, who + ": " + r.getBody)
        assert(r.getBody == noJobBody(cat), who + ": " + r.getBody)
    }

    test("D3: a key holding tap:run whose tap read is limited to another catalog cannot cancel, and the answer is the same 403 with or without a job") {
        val cat = "ceo-d3-noread"
        val caps = Seq("tap:run", "tap:read:catalog=ceo-d3-elsewhere")
        val k = ResolvedKey(None, "canceller", Capability.parseList(caps), isLegacyFullAccess = false)
        val none = cancel(Some(k), cat)
        assert(none.getStatusCode.value() == 403, "no job: " + none.getStatusCode + " " + none.getBody)
        ai.datris.auth.ScopeDenialWording.assertBody(none.getBody, ai.datris.auth.ScopeDenialWording.message("canceller", "tap", "read", caps), Seq(cat))
        withJob(cat) {
            val running = cancel(Some(k), cat)
            assert(running.getStatusCode.value() == 403, "job running: " + running.getStatusCode + " " + running.getBody)
            assert(!CatalogEnableJob.status(cat).exists(_.cancel.get()), "a refused cancel leaves the job running")
            assert(running.getBody == none.getBody, "identical answer whether or not a job exists: " + running.getBody + " vs " + none.getBody)
            assert(!running.getBody.contains("jobId") && !running.getBody.contains("spec-actor"), running.getBody)
        }
    }

    test("D3: a key holding only tap:run scoped to the catalog (no tap read) cannot cancel; same 403 with or without a job") {
        val cat = "ceo-d3-runonly"
        val k = key("tap:run:catalog=" + cat)
        val none = cancel(Some(k), cat)
        assert(none.getStatusCode.value() == 403, none.getBody)
        withJob(cat) {
            val running = cancel(Some(k), cat)
            assert(running.getStatusCode.value() == 403, running.getBody)
            assert(running.getBody == none.getBody)
            assert(!CatalogEnableJob.status(cat).exists(_.cancel.get()))
        }
    }

    test("D3: a key that may read the catalog's taps cancels a running job (200) and gets today's 404 when none runs") {
        val cat = "ceo-d3-read"
        val k = key("tap:run", "tap:read:catalog=" + cat)
        assertNoJob(cancel(Some(k), cat), cat, "no job")
        withJob(cat)(assertCancelled(cancel(Some(k), cat), cat, "running"))
    }

    test("D3: the job's starter (owner=self run and read) still cancels with 200") {
        val cat = "ceo-d3-starter"
        val starter = ResolvedKey(
            None,
            "starter",
            Capability.parseList(Seq("tap:update:owner=self", "tap:run:owner=self", "tap:read:owner=self")),
            isLegacyFullAccess = false
        )
        withJob(cat, actor = "starter")(assertCancelled(cancel(Some(starter), cat), cat, "starter"))
    }

    test("D3 unchanged: keys off, legacy, *:*, unscoped and template keys, admin, editor and granted restricted sessions cancel as today") {
        import ai.datris.util.APIKeyValidator
        import ai.datris.util.InMemoryUserDb.grants
        def sess(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)
        def restricted(cat: String, level: String): ResolvedKey =
            sess(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(cat -> level)))
        def callers(cat: String): Seq[(String, Option[ResolvedKey])] = Seq(
            "keys off" -> None,
            "legacy key" -> Some(ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)),
            "*:* key" -> Some(key("*:*")),
            "unscoped key" -> Some(key("tap:run", "tap:read")),
            "ops template" -> Some(key(KeyTemplatesForSpecs.capabilities("ops"): _*)),
            "recovery key" -> Some(ResolvedKey(
                None,
                ai.datris.incident.RecoveryKey.Label,
                Capability.parseList(ai.datris.incident.RecoveryKey.Capabilities),
                isLegacyFullAccess = false
            )),
            "admin session" -> Some(sess(User("root", "h", User.RoleAdmin, null, null, null))),
            "editor session" -> Some(sess(User("ed", "h", User.RoleEditor, null, null, null))),
            "restricted own" -> Some(restricted(cat, "own")),
            "restricted edit" -> Some(restricted(cat, "edit"))
        )
        for (((who, _), i) <- callers("x").zipWithIndex) {
            val cat = "ceo-d3-same-" + i
            val rk = callers(cat)(i)._2
            assertNoJob(cancel(rk, cat), cat, who + " (no job)")
            withJob(cat)(assertCancelled(cancel(rk, cat), cat, who))
        }
    }

    test("D3 unchanged: a key that is not limited, holding unscoped tap:run and no other capability, cancels as today (200, 404 with no job)") {
        val cat = "ceo-d3-unscoped-run"
        val k = key("tap:run")
        assertNoJob(cancel(Some(k), cat), cat, "no job")
        withJob(cat)(assertCancelled(cancel(Some(k), cat), cat, "running"))
    }

    test(
        "released behaviour: the status route for a key that cannot read the catalog's taps keeps today's answers ({active:false} with no job, 403 with one)"
    ) {
        val path = (cat: String) => "/api/v1/catalog/" + cat + "/enable-ordered"
        for (
            (what, caps) <- Seq("run only, scoped" -> Seq("tap:run:catalog=ceo-st-run"), "read elsewhere" -> Seq("tap:run", "tap:read:catalog=ceo-st-other"))
        ) {
            val cat = if (what == "run only, scoped") "ceo-st-run" else "ceo-st-elsewhere"
            val k = key(caps: _*)
            val none = new CatalogAPIController().enableOrderedStatus(null, cat, req(Some(k), "GET", path(cat)))
            assert(none.getStatusCode.value() == 200 && none.getBody == """{"active":false}""", what + ": " + none.getBody)
            withJob(cat) {
                val st = new CatalogAPIController().enableOrderedStatus(null, cat, req(Some(k), "GET", path(cat)))
                assert(st.getStatusCode.value() == 403, what + ": " + st.getBody)
                assert(!st.getBody.contains("tok-" + cat), what + ": " + st.getBody)
            }
        }
    }

    test("D3 unchanged: a restricted session with a view grant gets today's 403, without a grant today's 404, job or not") {
        import ai.datris.util.APIKeyValidator
        import ai.datris.util.InMemoryUserDb.grants
        val cat = "ceo-d3-rs"
        def restricted(g: (String, String)*): ResolvedKey =
            APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))
        val grantText = PipelineAPIController
            .capabilityDenied(new ai.datris.model.DatrisException("capability denied: your grant on catalog " + cat + " does not allow this"))
            .getBody
        def check(): Unit = {
            val view = cancel(Some(restricted(cat -> "view")), cat)
            assert(view.getStatusCode.value() == 403 && view.getBody == grantText, view.getBody)
            val hidden = cancel(Some(restricted("Other" -> "own")), cat)
            assert(hidden.getStatusCode.value() == 404 && hidden.getBody == RestrictedCatalogRules.catalogNotFound(cat).getBody, hidden.getBody)
        }
        check()
        withJob(cat) {
            check()
            assert(!CatalogEnableJob.status(cat).exists(_.cancel.get()))
        }
    }

    test("D3 unchanged: start and status keep their answers (status of no job is {active:false} for a key that cannot read)") {
        val cat = "ceo-d3-status"
        val k = key("tap:run", "tap:read:catalog=ceo-d3-elsewhere")
        val path = "/api/v1/catalog/" + cat + "/enable-ordered"
        val none = new CatalogAPIController().enableOrderedStatus(null, cat, req(Some(k), "GET", path))
        assert(none.getStatusCode.value() == 200 && none.getBody == """{"active":false}""", none.getBody)
        withJob(cat) {
            val st = new CatalogAPIController().enableOrderedStatus(null, cat, req(Some(k), "GET", path))
            assert(st.getStatusCode.value() == 403, st.getBody)
        }
        // start: the per-member rule, unchanged
        val taps = Seq(tap("a", cat))
        assert(CatalogAPIController.enableOrderedRefusal(req(Some(k)), taps).exists(_.getStatusCode.value() == 403))
    }
}
