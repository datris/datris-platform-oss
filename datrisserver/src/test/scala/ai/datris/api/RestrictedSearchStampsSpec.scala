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
import com.google.gson.{Gson, JsonArray, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Catalog-scoped user access: hide a hidden tap's provenance stamps in
  * search hits and stamp-field subsets
  * (plans/stories/catalog-scoped-user-access-stamp-columns.md), Acceptance,
  * RestrictedSearchStampsSpec.
  *
  * bob: restricted, grants `{Sales: edit}`; taps `t_sales` (Sales),
  * `t_hr_feed` (HR), `gone` (not stored). Assumed:
  * {{{
  * object HiddenTapScrub {
  *   // <name> of an exact tap-<name>.csv|json, else None (null -> None)
  *   def feedTap(filename: String): Option[String]
  *   def scrubHits(hits: java.util.List[java.util.Map[String, Any]], hidden: String => Boolean,
  *                 run: String => Option[RunLineage]): java.util.List[java.util.Map[String, Any]]
  * }
  * object SearchAPIController {
  *   def hitsFor(request: HttpServletRequest, hits: java.util.List[java.util.Map[String, Any]],
  *               writers: Option[Set[String]], readTap: String => TapConfig,
  *               run: String => Option[RunLineage]): java.util.List[java.util.Map[String, Any]]
  * }
  * object PipelineStatusAPIController {
  *   def scrubFor(hidden: Set[String], data: AnyRef, rowTapHidden: String => Boolean): AnyRef  // jobTap from each job's filename
  *   def scrubResultPage(request: HttpServletRequest, page: ScratchResultPage,
  *                       readTap: String => TapConfig, jobFilename: String): ScratchResultPage
  * }
  * }}}
  */
class RestrictedSearchStampsSpec extends AnyFunSuite with BeforeAndAfterEach {

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
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read")),
            isLegacyFullAccess = false
        )

    private def tap(n: String, target: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = target, catalog = catalog)
    private val taps = Map("t_sales" -> tap("t_sales", "orders", "Sales"), "t_hr_feed" -> tap("t_hr_feed", "orders", "HR"))
    private val readTap: String => TapConfig = n => taps.getOrElse(n, null)

    private val failReadTap: String => TapConfig = n => throw new AssertionError("readTap must not be called, got " + n)
    private val failRun: String => Option[RunLineage] = id => throw new AssertionError("run must not be called, got " + id)
    private val noRun: String => Option[RunLineage] = _ => None

    private def bobHidden: String => Boolean = n => HiddenTapScrub.tapHidden(req(bob), n, readTap)

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

    private def hit(id: Int, pairs: (String, Any)*): Hit = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("text", "chunk " + id)
        m.put("score", 0.9)
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(hs: Hit*): Hits = {
        val l = new java.util.ArrayList[Hit]()
        hs.foreach(l.add)
        l
    }

    private def ids(l: Hits): Seq[Any] = l.asScala.map(_.get("id")).toList

    private def keys(h: Hit): Seq[String] = h.keySet.asScala.toList

    private val otherStamps: Seq[(String, Any)] = Seq(
        "_datris_run_id" -> "run-1",
        "_datris_ingested_at" -> "2026-11-01T00:00:01Z",
        "_datris_config_version" -> 3
    )

    private def tapStamped(id: Int, writer: String, tapRun: String, extra: (String, Any)*): Hit =
        hit(
            id,
            (Seq[(String, Any)](
                "source_pipeline" -> writer,
                "_datris_tap_run" -> tapRun,
                "_datris_script_sha" -> "sha-abc",
                "_datris_source" -> "feeds.example.com"
            ) ++ otherStamps ++ extra): _*
        )

    private val writers: Option[Set[String]] = Some(Set("orders", "orders_old"))

    private def bobHits(storeHits: Hits, run: String => Option[RunLineage] = noRun): Hits =
        SearchAPIController.hitsFor(req(bob), storeHits, writers, readTap, run)

    private def assertStripped(h: Hit, ctx: String = ""): Unit =
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!h.containsKey(c), s"$ctx: $c must be stripped: $h"))

    private def assertKept(h: Hit, ctx: String = ""): Unit =
        HiddenTapScrub.StampTapColumns.foreach(c => assert(h.containsKey(c), s"$ctx: $c must be kept: $h"))

    private def record(pipeline: String, input: RunLineageInput): RunLineage = RunLineage("run-1", pipeline, input = input)

    private def runs(rs: (String, RunLineage)*): String => Option[RunLineage] = {
        val m = rs.toMap
        id => m.get(id)
    }

    // ------------------------------------------------------------ feedTap

    test("feedTap reads the tap name from an exact tap-<name>.csv|json filename only") {
        assert(HiddenTapScrub.feedTap("tap-hr.eu.csv") == Some("hr.eu"))
        assert(HiddenTapScrub.feedTap("tap-t_hr_feed.json") == Some("t_hr_feed"))
        assert(HiddenTapScrub.feedTap("orders.csv") == None)
        assert(HiddenTapScrub.feedTap("Loaded tap-x.csv") == None)
        assert(HiddenTapScrub.feedTap("tap-x.csv.gz") == None)
        assert(HiddenTapScrub.feedTap(null) == None)
    }

    // ------------------------------------------------------------ search hits

    test("search hits fed by a hidden tap lose the tap stamp columns and keep text, score, source_pipeline and the other stamps") {
        val original = tapStamped(1, "orders", "t_hr_feed|2026-11-01T00:00:00Z", "filename" -> "doc.pdf")
        val originalCopy = new java.util.LinkedHashMap[String, Any](original)
        val storeHits = hits(original)
        val out = bobHits(storeHits)
        assert(out.size == 1)
        val h = out.get(0)
        assertStripped(h, "tap_run names t_hr_feed")
        assert(h.get("id") == 1)
        assert(h.get("text") == "chunk 1")
        assert(h.get("score") == 0.9)
        assert(h.get("source_pipeline") == "orders")
        assert(h.get("_datris_run_id") == "run-1")
        assert(h.get("_datris_ingested_at") == "2026-11-01T00:00:01Z")
        assert(h.get("_datris_config_version") == 3)
        assert(h.get("filename") == "doc.pdf")
        assert(keys(h) == keys(original).filterNot(HiddenTapScrub.StampTapColumns.contains), "key order is kept")
        assert(!new Gson().toJson(out).contains("t_hr_feed"))
        assert(original == originalCopy, "the store's hit is not modified")

        // tap:<name> source alone, and a feed filename alone, identify the tap too
        val bySource = hit(2, "source_pipeline" -> "orders", "_datris_source" -> "tap:t_hr_feed", "_datris_script_sha" -> "s")
        val byFile = hit(3, "source_pipeline" -> "orders", "filename" -> "tap-t_hr_feed.csv", "_datris_script_sha" -> "s", "_datris_run_id" -> "r3")
        val out2 = bobHits(hits(bySource, byFile), failRun)
        assertStripped(out2.get(0), "tap: source")
        assertStripped(out2.get(1), "feed filename")
        assert(out2.get(1).get("filename") == "tap-[hidden].csv", out2.get(1))
        assert(!new Gson().toJson(out2).contains("t_hr_feed"), new Gson().toJson(out2))
    }

    test("a deleted tap is hidden in search hits") {
        val out = bobHits(hits(tapStamped(1, "orders", "gone|2026-11-01T00:00:00Z")))
        assertStripped(out.get(0), "gone")
        assert(out.get(0).get("_datris_run_id") == "run-1")
        assert(!new Gson().toJson(out).contains("gone"))
        // pure core too
        val pure = HiddenTapScrub.scrubHits(hits(tapStamped(1, "orders", "gone|2026-11-01T00:00:00Z")), bobHidden, noRun)
        assertStripped(pure.get(0), "gone (scrubHits)")
    }

    private def subsetHit(id: Int, writer: String): Hit =
        hit(id, "source_pipeline" -> writer, "_datris_run_id" -> "run-1", "_datris_source" -> "feeds.example.com", "_datris_script_sha" -> "sha-abc")

    test("search hits with a stamp-field subset are resolved through the run record filed under the hit's own writer") {
        val hidden = bobHits(hits(subsetHit(1, "orders")), runs("run-1" -> record("orders", RunLineageInput("tap", tapName = "t_hr_feed"))))
        assert(!hidden.get(0).containsKey("_datris_source") && !hidden.get(0).containsKey("_datris_script_sha"), hidden.get(0))
        assert(hidden.get(0).get("_datris_run_id") == "run-1")

        val visibleHits = hits(subsetHit(1, "orders"))
        val visible = bobHits(visibleHits, runs("run-1" -> record("orders", RunLineageInput("tap", tapName = "t_sales"))))
        assert(visible eq visibleHits, "a readable tap's hit is untouched")
        assert(visible.get(0).get("_datris_source") == "feeds.example.com")

        val uploadHits = hits(subsetHit(1, "orders"))
        assert(bobHits(uploadHits, runs("run-1" -> record("orders", RunLineageInput("upload")))) eq uploadHits, "an upload run keeps its stamps")

        val noTapHits = hits(subsetHit(1, "orders"))
        assert(bobHits(noTapHits, runs("run-1" -> record("orders", RunLineageInput("tap")))) eq noTapHits, "a run with no tapName keeps its stamps")
    }

    test("a run record filed under another pipeline name is not used") {
        val out = bobHits(hits(subsetHit(1, "orders")), runs("run-1" -> record("payroll", RunLineageInput("tap", tapName = "t_sales"))))
        assert(!out.get(0).containsKey("_datris_source") && !out.get(0).containsKey("_datris_script_sha"), out.get(0))
        assert(out.get(0).get("_datris_run_id") == "run-1")

        // a record with no input counts as not found too
        val noInput = bobHits(hits(subsetHit(1, "orders")), runs("run-1" -> record("orders", null)))
        assert(!noInput.get(0).containsKey("_datris_source") && !noInput.get(0).containsKey("_datris_script_sha"), noInput.get(0))
    }

    test("a renamed pipeline's older chunks still resolve") {
        // Only hits, a hidden predicate and a run lookup reach the scrub: no pipeline configs.
        val keptHits = hits(subsetHit(1, "orders_old"))
        val kept = HiddenTapScrub.scrubHits(keptHits, bobHidden, runs("run-1" -> record("orders_old", RunLineageInput("tap", tapName = "t_sales"))))
        assert(kept eq keptHits)
        val keptVia = hits(subsetHit(1, "orders_old"))
        assert(bobHits(keptVia, runs("run-1" -> record("orders_old", RunLineageInput("tap", tapName = "t_sales")))) eq keptVia)

        val stripped =
            HiddenTapScrub.scrubHits(
                hits(subsetHit(1, "orders_old")),
                bobHidden,
                runs("run-1" -> record("orders_old", RunLineageInput("tap", tapName = "t_hr_feed")))
            )
        assert(!stripped.get(0).containsKey("_datris_source") && !stripped.get(0).containsKey("_datris_script_sha"), stripped.get(0))
        val strippedVia = bobHits(hits(subsetHit(1, "orders_old")), runs("run-1" -> record("orders_old", RunLineageInput("tap", tapName = "t_hr_feed"))))
        assert(!strippedVia.get(0).containsKey("_datris_source") && !strippedVia.get(0).containsKey("_datris_script_sha"), strippedVia.get(0))
        assert(strippedVia.get(0).get("source_pipeline") == "orders_old")
    }

    test(
        "a search hit whose feeding tap cannot be determined loses non-empty tap stamp columns; an unstamped hit and a hit with null or empty tap columns are untouched"
    ) {
        // run id present, no record
        val noRecord = bobHits(hits(subsetHit(1, "orders")), noRun)
        assert(!noRecord.get(0).containsKey("_datris_source") && !noRecord.get(0).containsKey("_datris_script_sha"), noRecord.get(0))
        assert(noRecord.get(0).get("_datris_run_id") == "run-1")

        // no run id at all (also: a pipeline's static metadata reusing a stamp name)
        val noRunId = bobHits(hits(hit(2, "source_pipeline" -> "orders", "_datris_script_sha" -> "sha-abc")), noRun)
        assertStripped(noRunId.get(0), "no run id")
        val staticMeta = bobHits(hits(hit(3, "source_pipeline" -> "orders", "_datris_source" -> "crm")), noRun)
        assert(!staticMeta.get(0).containsKey("_datris_source"), staticMeta.get(0))

        // untouched: unstamped, null tap columns, empty tap columns
        val untouched = hits(
            hit(4, "source_pipeline" -> "orders"),
            hit(
                5,
                "source_pipeline" -> "orders",
                "_datris_run_id" -> "run-x",
                "_datris_tap_run" -> null,
                "_datris_script_sha" -> null,
                "_datris_source" -> null
            ),
            hit(6, "source_pipeline" -> "orders", "_datris_tap_run" -> "", "_datris_script_sha" -> "", "_datris_source" -> "")
        )
        val out = bobHits(untouched, noRun)
        assert(out eq untouched, out)
        assert(out.get(1).containsKey("_datris_tap_run") && out.get(2).get("_datris_source") == "")
    }

    test(
        "hitsFor returns the same list instance for writers None (unrestricted editor, catalog-scoped API key) without calling readTap or run, and for a restricted session when every surviving hit is from a readable tap"
    ) {
        def stamped: Hits = hits(
            tapStamped(1, "orders", "t_hr_feed|2026-11-01T00:00:00Z"),
            tapStamped(2, "mkt_docs", "gone|t"),
            subsetHit(3, "orders"),
            hit(4, "source_pipeline" -> "orders", "filename" -> "tap-t_hr_feed.csv")
        )
        for (rk <- Seq(editor, fullKey, salesKey)) {
            val storeHits = stamped
            assert(SearchAPIController.hitsFor(req(rk), storeHits, None, failReadTap, failRun) eq storeHits, rk.label)
            assert(storeHits.get(0).containsKey("_datris_tap_run") && storeHits.get(3).get("filename") == "tap-t_hr_feed.csv", rk.label)
        }
        // bob with writers None (never produced by searchWriters for him, but the seam honours it)
        val asIs = stamped
        assert(SearchAPIController.hitsFor(req(bob), asIs, None, failReadTap, failRun) eq asIs)

        val readable = hits(
            tapStamped(1, "orders", "t_sales|2026-11-01T00:00:00Z"),
            hit(2, "source_pipeline" -> "orders", "_datris_source" -> "tap:t_sales"),
            subsetHit(3, "orders"),
            hit(4, "source_pipeline" -> "orders"),
            hit(5, "source_pipeline" -> "orders", "filename" -> "tap-t_sales.json", "_datris_script_sha" -> "s")
        )
        val out = bobHits(readable, runs("run-1" -> record("orders", RunLineageInput("upload"))))
        assert(out eq readable)
        assertKept(out.get(0), "t_sales")
    }

    test("the stamp scrub runs after the writer check") {
        val tapCalls = mutable.ArrayBuffer[String]()
        val runCalls = mutable.ArrayBuffer[String]()
        val countingTap: String => TapConfig = n => { tapCalls += n; readTap(n) }
        val countingRun: String => Option[RunLineage] = id => { runCalls += id; None }
        val storeHits = hits(
            hit(
                1,
                "source_pipeline" -> "payroll",
                "_datris_run_id" -> "run-pay",
                "_datris_tap_run" -> "t_hr_feed|2026-11-01T00:00:00Z",
                "_datris_script_sha" -> "s",
                "_datris_source" -> "tap:t_hr_feed",
                "filename" -> "tap-t_hr_feed.csv"
            ),
            hit(2, "source_pipeline" -> "orders")
        )
        val out = SearchAPIController.hitsFor(req(bob), storeHits, writers, countingTap, countingRun)
        assert(ids(out) == Seq(2))
        assert(tapCalls.isEmpty, tapCalls)
        assert(runCalls.isEmpty, runCalls)
        assert(!new Gson().toJson(out).contains("payroll") && !new Gson().toJson(out).contains("t_hr_feed"))
    }

    test("mixed hits: only the hidden-tap hits change and hit order is kept") {
        val storeHits = hits(
            tapStamped(1, "orders", "t_sales|2026-11-01T00:00:00Z"),
            tapStamped(2, "orders", "t_hr_feed|2026-11-01T00:00:00Z"),
            hit(3, "source_pipeline" -> "orders"),
            hit(4, "source_pipeline" -> "orders_old", "_datris_source" -> "tap:gone", "_datris_script_sha" -> "s"),
            // a JDBC-style non-String value: compared via String.valueOf
            hit(5, "source_pipeline" -> "orders", "_datris_tap_run" -> new java.lang.StringBuilder("t_hr_feed|t"), "_datris_script_sha" -> "s"),
            hit(6, "source_pipeline" -> "orders", "filename" -> "tap-t_sales.csv", "_datris_script_sha" -> "s"),
            hit(7, "source_pipeline" -> "mkt_docs", "_datris_tap_run" -> "t_sales|t")
        )
        val before = storeHits.asScala.map(h => new java.util.LinkedHashMap[String, Any](h)).toList
        val out = bobHits(storeHits)
        assert(ids(out) == Seq(1, 2, 3, 4, 5, 6), "writer check drops 7, order is kept")
        assert(!(out eq storeHits))
        Seq(0, 2, 5).foreach(i => assert(out.get(i) eq storeHits.get(i), s"hit ${i + 1} is unchanged and not copied"))
        assertKept(out.get(0), "t_sales")
        assertStripped(out.get(1), "t_hr_feed")
        assertStripped(out.get(3), "tap:gone")
        assertStripped(out.get(4), "StringBuilder t_hr_feed")
        assert(out.get(5).get("_datris_script_sha") == "s" && out.get(5).get("filename") == "tap-t_sales.csv")
        Seq(1, 3, 4).foreach { i =>
            assert(keys(out.get(i)) == keys(storeHits.get(i)).filterNot(HiddenTapScrub.StampTapColumns.contains), s"hit ${i + 1} key order")
        }
        assert(storeHits.asScala.toList == before, "the store's hits are not modified")
        val body = new Gson().toJson(out)
        assert(!body.contains("t_hr_feed") && !body.contains("gone"), body)
    }

    // ------------------------------------------------------------ scratch rows

    private def subsetRows: JsonArray =
        JsonParser.parseString(
            """[{"id":1,"_datris_run_id":"tok","_datris_source":"feeds.example.com","_datris_script_sha":"abc"},
              |{"id":2,"_datris_run_id":"tok","_datris_source":"feeds.example.com","_datris_script_sha":"def"}]""".stripMargin
        ).getAsJsonArray

    private def assertSubsetScrubbed(rows: JsonArray, ctx: String): Unit = {
        val r = rows.asScala.map(_.getAsJsonObject).toList
        assert(r.size == 2, ctx)
        r.foreach { row =>
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!row.has(c), s"$ctx: keeps $c: $row"))
            assert(row.get("_datris_run_id").getAsString == "tok", ctx)
        }
        assert(r.map(_.get("id").getAsInt) == List(1, 2), ctx)
    }

    private def scratchResponse(filename: String) = PipelineStatusResponse(
        PipelineStatusRollup(
            allDone = true,
            status = "success",
            jobs = List(PipelineJobRollup("tok", "orders", filename, "success", "s", "l", "1s", null, resultPreview = subsetRows)).asJava
        ),
        List(
            PipelineStatus(
                1,
                "now",
                "orders",
                "p",
                "pub",
                "tok",
                filename,
                "end",
                "OK",
                "done",
                1L,
                scratchResult = ScratchResult("u", 2L, "e", subsetRows, false)
            )
        ).asJava
    )

    test("scratch rows of a job fed by a hidden tap lose the stamp columns when provenance.fields omits _datris_tap_run") {
        val shown = PipelineStatusAPIController.scrubFor(Set.empty, scratchResponse("tap-t_hr_feed.csv"), bobHidden).asInstanceOf[PipelineStatusResponse]
        assertSubsetScrubbed(shown.rollup.jobs.get(0).resultPreview, "rollup job")
        assertSubsetScrubbed(shown.events.get(0).scratchResult.resultPreview, "event")

        val json = PipelineStatusAPIController.scrubFor(Set.empty, scratchResponse("tap-t_hr_feed.json"), bobHidden).asInstanceOf[PipelineStatusResponse]
        assertSubsetScrubbed(json.rollup.jobs.get(0).resultPreview, "rollup job (.json)")

        val gone = PipelineStatusAPIController.scrubFor(Set.empty, scratchResponse("tap-gone.csv"), bobHidden).asInstanceOf[PipelineStatusResponse]
        assertSubsetScrubbed(gone.events.get(0).scratchResult.resultPreview, "event (deleted tap)")

        val readable = scratchResponse("tap-t_sales.csv")
        assert(PipelineStatusAPIController.scrubFor(Set.empty, readable, bobHidden) eq readable)
        val upload = scratchResponse("orders.csv")
        assert(PipelineStatusAPIController.scrubFor(Set.empty, upload, bobHidden) eq upload)

        // a list of events (status by token without rollup)
        val events = List(
            PipelineStatus(
                1,
                "now",
                "orders",
                "p",
                "pub",
                "tok",
                "tap-t_hr_feed.csv",
                "end",
                "OK",
                "done",
                1L,
                scratchResult = ScratchResult("u", 2L, "e", subsetRows, false)
            )
        ).asJava
        val evOut = PipelineStatusAPIController.scrubFor(Set.empty, events, bobHidden).asInstanceOf[java.util.List[PipelineStatus]]
        assertSubsetScrubbed(evOut.get(0).scratchResult.resultPreview, "event list")
    }

    test("GET /pipeline/result subset-stamped rows are scrubbed by job filename for a restricted session only") {
        def page = ScratchResultPage(subsetRows, 2L, 2, 0L, truncated = false, "u", "e")
        val r = req(bob, method = "GET", path = "/api/v1/pipeline/result")
        assertSubsetScrubbed(PipelineStatusAPIController.scrubResultPage(r, page, readTap, "tap-t_hr_feed.json").records, "hidden tap")
        assertSubsetScrubbed(PipelineStatusAPIController.scrubResultPage(r, page, readTap, "tap-gone.csv").records, "deleted tap")
        Seq("tap-t_sales.csv", "orders.csv", null).foreach { f =>
            val p = page
            assert(PipelineStatusAPIController.scrubResultPage(r, p, readTap, f) eq p, String.valueOf(f))
        }
        val p3 = page
        assert(PipelineStatusAPIController.scrubResultPage(r, p3, readTap) eq p3, "3-argument call")
        for (rk <- Seq(editor, salesKey); f <- Seq("tap-t_hr_feed.json", "tap-gone.csv", "tap-t_sales.csv", "orders.csv")) {
            val p = page
            assert(
                PipelineStatusAPIController.scrubResultPage(req(rk, method = "GET", path = "/api/v1/pipeline/result"), p, readTap, f) eq p,
                rk.label + " " + f
            )
        }
    }
}
