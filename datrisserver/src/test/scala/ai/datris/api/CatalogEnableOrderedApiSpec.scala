package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, DatrisEnvironment, PipelineStatusRollup, ResolvedKey, TapConfig}
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
        assert(CatalogAPIController.enableOrderedRefusal(req(Some(key("tap:update", "tap:run:owner=self"))), taps).isEmpty)
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
}
