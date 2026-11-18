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
import ai.datris.util.ScratchReader.ScratchResultPage
import com.google.gson.{JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Follow-up to the stamp-named-tap-spoof story
  * (plans/stories/catalog-scoped-user-access-stamp-named-tap-spoof.md):
  * the `_datris_tap_run` prefix rule is bounded (a value with many pipes is
  * not split and fails closed, quickly, with no tap lookup), and the edge
  * values (leading / trailing / lone pipe, empty, whitespace) keep their
  * fail-closed outcomes for hits and scratch rows. bob: restricted, grants
  * `{Sales: edit}`; taps `t_sales` (Sales), `t_hr_feed` (HR). */
class RestrictedStampTapRunBoundsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private type Hit = java.util.Map[String, Any]
    private type Hits = java.util.List[java.util.Map[String, Any]]

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/search/pgvector"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))

    private def tap(n: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = "orders", catalog = catalog)
    private val taps = Map("t_sales" -> tap("t_sales", "Sales"), "t_hr_feed" -> tap("t_hr_feed", "HR"))
    private val readTap: String => TapConfig = n => taps.getOrElse(n, null)
    private val failReadTap: String => TapConfig = n => throw new AssertionError("readTap must not be called, got " + n.take(40))
    private val failRun: String => Option[RunLineage] = id => throw new AssertionError("run must not be called, got " + id)
    private val failDescriptions: () => Seq[String] = () => throw new AssertionError("job descriptions must not be read")
    private def record(pipeline: String, tapName: String): String => Option[RunLineage] =
        id => Some(RunLineage(id, pipeline, input = RunLineageInput("tap", tapName = tapName)))

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

    // ------------------------------------------------------------ helpers

    private def hit(tapRun: String, extra: (String, Any)*): Hit = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", 1)
        m.put("text", "chunk 1")
        m.put("source_pipeline", "orders")
        m.put("_datris_tap_run", tapRun)
        m.put("_datris_script_sha", "sha-x")
        m.put("_datris_source", "feeds.example.com")
        extra.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(h: Hit): Hits = {
        val l = new java.util.ArrayList[Hit]()
        l.add(h)
        l
    }

    private def bobHits(hs: Hits, run: String => Option[RunLineage], rt: String => TapConfig = readTap): Hits =
        SearchAPIController.hitsFor(req(bob), hs, Some(Set("orders")), rt, run)

    private def rows(tapRun: String): JsonArray = {
        val o = new JsonObject
        o.addProperty("id", 1)
        o.addProperty("_datris_run_id", "tok")
        o.addProperty("_datris_tap_run", tapRun)
        o.addProperty("_datris_script_sha", "sha-x")
        o.addProperty("_datris_source", "feeds.example.com")
        val a = new JsonArray
        a.add(o)
        a
    }

    private def page(r: JsonArray) = ScratchResultPage(r, 1L, 1, 0L, truncated = false, "u", "e")

    private def bobPage(p: ScratchResultPage, run: String => Option[RunLineage], rt: String => TapConfig = readTap, d: () => Seq[String] = () => Nil) =
        PipelineStatusAPIController.scrubResultPage(req(bob, "GET", "/api/v1/pipeline/result"), p, rt, "stream: orders", "orders", "tok", run, d)

    private def assertHitStripped(h: Hit, ctx: String): Unit =
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!h.containsKey(c), s"$ctx: $c must be stripped"))

    private def assertRowStripped(rs: JsonArray, ctx: String): Unit = {
        val r = rs.get(0).getAsJsonObject
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!r.has(c), s"$ctx: $c must be stripped: $r"))
        assert(r.get("_datris_run_id").getAsString == "tok", ctx)
    }

    // ------------------------------------------------------------ tests

    test("a tap_run value with 100k pipes strips quickly, without splitting it or looking up a tap") {
        val big = "a" + ("|" * 100000)
        val start = System.nanoTime()

        val out = bobHits(hits(hit(big)), failRun, failReadTap)
        assertHitStripped(out.get(0), "100k pipes, hit")
        assert(out.get(0).get("id") == 1)

        val rs = rows(big)
        val before = rs.deepCopy()
        val p = bobPage(page(rs), failRun, failReadTap, failDescriptions)
        assertRowStripped(p.records, "100k pipes, row")
        assert(rs == before, "the input rows are not modified")

        val direct = HiddenTapScrub.scrubStampedRows(rows(big), n => throw new AssertionError("hidden must not be called, got " + n.take(40)))
        assertRowStripped(direct, "100k pipes, scrubStampedRows")

        val ms = (System.nanoTime() - start) / 1000000
        assert(ms < 5000, s"took $ms ms")
    }

    test("a tap_run of a readable tap with up to the pipe cap is still split; one more pipe fails closed") {
        // t_sales + 8 pipes (at the cap): split into t_sales, t_sales|, ...; the unknown longer prefixes strip
        assertHitStripped(bobHits(hits(hit("t_sales" + ("|" * 8))), failRun).get(0), "8 pipes")
        val readableAll: String => TapConfig = n => if (n.startsWith("t_sales")) tap(n, "Sales") else null
        val eight = hits(hit("t_sales" + ("|x" * 8)))
        assert(bobHits(eight, failRun, readableAll) eq eight, "8 pipes, every prefix readable")
        assertHitStripped(bobHits(hits(hit("t_sales" + ("|x" * 9))), failRun, readableAll).get(0), "9 pipes, fail closed")
    }

    test("edge tap_run values keep their outcomes for hits and scratch rows") {
        sealed trait Outcome
        case object Strip extends Outcome
        case object Keep extends Outcome
        // (value, run, description, hit outcome, row outcome)
        val table: Seq[(String, String => Option[RunLineage], String, Outcome, Outcome)] = Seq(
            ("|x", _ => None, "leading pipe names nothing, no record", Strip, Strip),
            ("|x", record("orders", "t_sales"), "leading pipe names nothing, readable record", Keep, Keep),
            ("|x", record("orders", "t_hr_feed"), "leading pipe names nothing, hidden record", Strip, Strip),
            ("|", _ => None, "lone pipe names nothing, no record", Strip, Strip),
            ("|", record("orders", "t_sales"), "lone pipe names nothing, readable record", Keep, Keep),
            ("t_sales|", failRun, "trailing pipe names t_sales", Keep, Keep),
            ("t_hr_feed|", failRun, "trailing pipe names t_hr_feed", Strip, Strip),
            ("", _ => None, "empty names nothing, no record", Strip, Strip),
            ("", record("orders", "t_sales"), "empty names nothing, readable record", Keep, Keep),
            ("   ", failRun, "whitespace names an unknown tap", Strip, Strip),
            (" | ", failRun, "whitespace prefix names an unknown tap", Strip, Strip)
        )
        for ((value, run, ctx, hitOut, rowOut) <- table) {
            val withId = hits(hit(value, "_datris_run_id" -> "run-1"))
            val h = bobHits(withId, run)
            hitOut match {
                case Keep => assert(h eq withId, s"hit: $ctx")
                case Strip => assertHitStripped(h.get(0), s"hit: $ctx")
            }
            val p = page(rows(value))
            val out = bobPage(p, run)
            rowOut match {
                case Keep => assert(out eq p, s"row: $ctx")
                case Strip => assertRowStripped(out.records, s"row: $ctx")
            }
        }
        // without a run id a hit whose tap_run names nothing is unresolved and run is not called
        for (value <- Seq("|x", "|", "")) assertHitStripped(bobHits(hits(hit(value)), failRun).get(0), s"hit without run id: '$value'")
    }
}
