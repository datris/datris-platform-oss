package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.HiddenTapScrub
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Search hits whose stamp columns were planted through a pipeline's static
  * destination `metadata` (chunks written before the stamper dropped such
  * keys): a hit that names several taps is stripped when any of them is
  * hidden, and a tap or run read that throws fails closed instead of failing
  * the search. bob: restricted, grants `{Sales: edit}`; taps `t_sales`
  * (Sales), `t_hr_feed` (HR). */
class RestrictedSearchStampsPlantedSpec extends AnyFunSuite with BeforeAndAfterEach {

    private type Hit = java.util.Map[String, Any]
    private type Hits = java.util.List[java.util.Map[String, Any]]

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/search/pgvector")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))

    private def tap(n: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = "orders", catalog = catalog)
    private val taps = Map("t_sales" -> tap("t_sales", "Sales"), "t_hr_feed" -> tap("t_hr_feed", "HR"))
    private val readTap: String => TapConfig = n => taps.getOrElse(n, null)
    private val failRun: String => Option[RunLineage] = id => throw new AssertionError("run must not be called, got " + id)

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    private def hit(id: Int, pairs: (String, Any)*): Hit = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("text", "chunk " + id)
        m.put("source_pipeline", "orders")
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(hs: Hit*): Hits = {
        val l = new java.util.ArrayList[Hit]()
        hs.foreach(l.add)
        l
    }

    private val writers: Option[Set[String]] = Some(Set("orders"))

    private def assertStripped(h: Hit, ctx: String): Unit =
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!h.containsKey(c), s"$ctx: $c must be stripped: $h"))

    test("scenario A: a planted readable _datris_tap_run does not vouch for a tap: source naming a hidden tap") {
        val planted = hit(
            1,
            "_datris_run_id" -> "run-hr",
            "_datris_tap_run" -> "t_sales|x",
            "_datris_source" -> "tap:t_hr_feed",
            "_datris_script_sha" -> "sha-hr"
        )
        val out = SearchAPIController.hitsFor(req(bob), hits(planted), writers, readTap, failRun)
        assertStripped(out.get(0), "planted t_sales + tap:t_hr_feed")
        assert(out.get(0).get("_datris_run_id") == "run-hr")
        assert(!new Gson().toJson(out).contains("t_hr_feed"))

        // the pure core too, and a hidden feed filename beside a readable tap_run
        val byFile = hit(2, "_datris_tap_run" -> "t_sales|x", "_datris_script_sha" -> "sha-hr", "filename" -> "tap-t_hr_feed.csv")
        val pure = HiddenTapScrub.scrubHits(hits(byFile), n => HiddenTapScrub.tapHidden(req(bob), n, readTap), failRun)
        assertStripped(pure.get(0), "planted t_sales + hidden feed filename")
        assert(pure.get(0).get("filename") == "tap-[hidden].csv")
    }

    test("a hit naming only readable taps keeps its columns and run is not called") {
        val hs = hits(hit(1, "_datris_tap_run" -> "t_sales|x", "_datris_source" -> "tap:t_sales", "_datris_script_sha" -> "s", "filename" -> "tap-t_sales.csv"))
        assert(SearchAPIController.hitsFor(req(bob), hs, writers, readTap, failRun) eq hs)
    }

    test("scenario B on old chunks: a planted upload run id does not vouch for a tap: source naming a hidden tap") {
        val upload: String => Option[RunLineage] = id => Some(RunLineage(id, "orders", input = RunLineageInput("upload")))
        val h = hit(1, "_datris_run_id" -> "upload-run-7", "_datris_source" -> "tap:t_hr_feed", "_datris_script_sha" -> "sha-hr")
        val out = SearchAPIController.hitsFor(req(bob), hits(h), writers, readTap, upload)
        assertStripped(out.get(0), "upload run id + tap:t_hr_feed")
    }

    test("a tap read that throws counts as hidden instead of failing the search") {
        val throwing: String => TapConfig = _ => throw new RuntimeException("mongo down")
        val out = SearchAPIController.hitsFor(req(bob), hits(hit(1, "_datris_tap_run" -> "t_sales|x", "_datris_script_sha" -> "s")), writers, throwing, failRun)
        assertStripped(out.get(0), "throwing tap read")
    }

    test("a run read that throws counts as no record (fail closed)") {
        val throwingRun: String => Option[RunLineage] = _ => throw new RuntimeException("mongo down")
        val out = SearchAPIController.hitsFor(
            req(bob),
            hits(hit(1, "_datris_run_id" -> "r1", "_datris_source" -> "feeds.example.com")),
            writers,
            readTap,
            throwingRun
        )
        assertStripped(out.get(0), "throwing run read")
    }

    test("a scratch row naming a readable tap_run and a hidden tap: source loses the tap stamp columns") {
        val rows = com.google.gson.JsonParser
            .parseString(
                """[{"id":1,"_datris_tap_run":"t_sales|x","_datris_source":"tap:t_hr_feed","_datris_script_sha":"s"},{"id":2,"_datris_tap_run":"t_sales|x"}]"""
            )
            .getAsJsonArray
        val out = HiddenTapScrub.scrubStampedRows(rows, n => HiddenTapScrub.tapHidden(req(bob), n, readTap))
        val r0 = out.get(0).getAsJsonObject
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!r0.has(c), r0.toString))
        assert(out.get(1).getAsJsonObject.has("_datris_tap_run"), "a readable-only row is kept")
    }

    test("unrestricted callers are untouched by planted columns") {
        val hs = hits(hit(1, "_datris_tap_run" -> "t_sales|x", "_datris_source" -> "tap:t_hr_feed"))
        assert(SearchAPIController.hitsFor(req(editor), hs, None, readTap, failRun) eq hs)
    }

    // ------------------------------------------------------------ planted filename

    private def hrRun(id: String): Option[RunLineage] = Some(RunLineage(id, "orders", input = RunLineageInput("tap", tapName = "t_hr_feed")))

    /** A chunk of a hidden tap's run whose `filename` was replaced by a
      * static `metadata.filename` naming a readable tap (chunks written before
      * `filename` became a reserved metadata key). */
    private def plantedFile(id: Int): Hit =
        hit(
            id,
            "_datris_run_id" -> "run-hr",
            "_datris_source" -> "hr.internal",
            "_datris_script_sha" -> "sha-hr",
            "filename" -> "tap-t_sales.csv"
        )

    test("a planted readable feed filename does not vouch for a hidden tap's stamps: the run record decides") {
        val out = SearchAPIController.hitsFor(req(bob), hits(plantedFile(1)), writers, readTap, id => hrRun(id))
        assertStripped(out.get(0), "planted tap-t_sales.csv, run record t_hr_feed")
        assert(out.get(0).get("_datris_run_id") == "run-hr")
        assert(out.get(0).get("filename") == "tap-t_sales.csv", "a readable filename is not renamed")
        val body = new Gson().toJson(out)
        assert(!body.contains("hr.internal") && !body.contains("sha-hr"), body)

        // no run record, or one filed under another pipeline: fail closed
        val noRecord = SearchAPIController.hitsFor(req(bob), hits(plantedFile(2)), writers, readTap, _ => None)
        assertStripped(noRecord.get(0), "planted filename, no run record")
        val otherWriter = SearchAPIController.hitsFor(
            req(bob),
            hits(plantedFile(3)),
            writers,
            readTap,
            id => Some(RunLineage(id, "payroll", input = RunLineageInput("tap", tapName = "t_sales")))
        )
        assertStripped(otherWriter.get(0), "planted filename, record of another pipeline")

        // the run record of the readable tap keeps the columns
        val readableRun: String => Option[RunLineage] = id => Some(RunLineage(id, "orders", input = RunLineageInput("tap", tapName = "t_sales")))
        val keptHits = hits(plantedFile(4))
        assert(SearchAPIController.hitsFor(req(bob), keptHits, writers, readTap, readableRun) eq keptHits)
    }

    test("a readable feed filename with no run id keeps the stamp columns and run is not called") {
        // Pinned by RestrictedSearchStampsSpec (filename-only readable hits are kept
        // with no run lookup); the planting route for new chunks is closed by
        // filename being a reserved static metadata key.
        val hs = hits(hit(1, "_datris_source" -> "feeds.example.com", "_datris_script_sha" -> "s", "filename" -> "tap-t_sales.json"))
        assert(SearchAPIController.hitsFor(req(bob), hs, writers, readTap, failRun) eq hs)
    }

    test("a hidden feed filename strips the stamps even beside a run id, and run is not called") {
        val h = hit(1, "_datris_run_id" -> "run-s", "_datris_source" -> "feeds.example.com", "_datris_script_sha" -> "s", "filename" -> "tap-t_hr_feed.csv")
        val out = SearchAPIController.hitsFor(req(bob), hits(h), writers, readTap, failRun)
        assertStripped(out.get(0), "hidden feed filename")
        assert(out.get(0).get("filename") == "tap-[hidden].csv")
    }
}
