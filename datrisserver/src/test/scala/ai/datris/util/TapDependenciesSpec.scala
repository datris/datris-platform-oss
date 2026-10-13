package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Tap dependencies: declare, infer, and show the enable order of a
  * catalog (plans/stories/tap-dependency-order.md), Acceptance bullets 1-3.
  *
  * API this spec pins (pure, no Mongo, no network). Only the members used
  * below are pinned; other `Readiness` fields named in the story are free.
  *
  * {{{
  * // TapConfig gains (Gson: absent -> null, like `tags`):
  * //   dependsOn: java.util.List[String] = null          // explicit; [] = "none, ignore inference"
  * //   inferredDependsOn: java.util.List[String] = null  // server-stamped
  *
  * object TapDependencies {
  *   case class Readiness(name: String, ..., hasPassingTest: Boolean,
  *                        lastRealRunLanded: Boolean, blockedBy: List[String], ready: Boolean)
  *   case class Plan(levels: List[List[Readiness]], cycles: List[List[String]])
  *
  *   /** Same-catalog producer taps whose pipelines' destination tables the
  *     * script reads through /query/ or /metadata/. `pipelines` and `taps`
  *     * may span catalogs; only `self.catalog` counts. Sorted, distinct, no self. */
  *   def infer(script: String, pipelines: Seq[PipelineConfig], taps: Seq[TapConfig], self: TapConfig): List[String]
  *   /** dependsOn when non-null (even empty), else inferredDependsOn, else Nil. */
  *   def effective(t: TapConfig): List[String]
  *   /** Left names the offending entry: not a tap of t's catalog, or t itself. */
  *   def validate(t: TapConfig, catalogTaps: Seq[TapConfig]): Either[String, Unit]
  *   def cycle(taps: Seq[TapConfig]): Option[List[String]]
  *   /** Kahn over `effective`: levels(0) = taps with no deps (producers). */
  *   def order(taps: Seq[TapConfig]): Plan
  * }
  * }}}
  */
class TapDependenciesSpec extends AnyFunSuite {

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def pg(table: String, schema: String = "public"): Destination =
        Destination(database = Database(dbName = "datris", schema = schema, table = table, usePostgres = true))

    private def pipeline(name: String, catalog: String, table: String): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, destination = pg(table))

    private def tap(
        name: String,
        catalog: String = "Canslim",
        target: String = null,
        dependsOn: java.util.List[String] = null,
        inferred: java.util.List[String] = null,
        lastRunStatus: String = null,
        lastRunRecordCount: Int = 0,
        lastTestRunStatus: String = null
    ): TapConfig =
        TapConfig(
            name = name,
            description = "d",
            targetPipeline = target,
            catalog = catalog,
            dependsOn = dependsOn,
            inferredDependsOn = inferred,
            lastRunStatus = lastRunStatus,
            lastRunRecordCount = lastRunRecordCount,
            lastTestRunStatus = lastTestRunStatus
        )

    // ---------------------------------------------------------------- infer

    private val pipelines = Seq(
        pipeline("ohlcv_daily", "Canslim", "canslim_ohlcv_daily"),
        pipeline("current_universe", "Canslim", "canslim_current_universe"),
        pipeline("rs_rating", "Canslim", "canslim_rs_rating"),
        // Same table name, different catalog: never a dependency.
        pipeline("other_ohlcv", "Other", "canslim_ohlcv_daily")
    )
    private val self = tap("rs_rating_tap", target = "rs_rating")
    private val taps = Seq(
        tap("ohlcv_tap", target = "ohlcv_daily"),
        tap("universe_tap", target = "current_universe"),
        self,
        tap("other_ohlcv_tap", catalog = "Other", target = "other_ohlcv")
    )

    private val sql =
        """WITH ranked AS (
          |  SELECT o.symbol, o.close FROM canslim_ohlcv_daily o
          |  JOIN canslim_rs_rating r ON r.symbol = o.symbol
          |)
          |SELECT * FROM ranked
          |WHERE symbol IN (SELECT symbol FROM public.canslim_current_universe)""".stripMargin

    private val script =
        "import requests\n" +
            "def fetch(ctx):\n" +
            "    sql = \"\"\"" + sql + "\"\"\"\n" +
            "    r = requests.post(ctx.api_url + '/api/v1/query/postgres', json={'sql': sql})\n" +
            "    return r.json()\n"

    test("infer: FROM-aliased and schema-qualified tables map to the same-catalog producer taps") {
        val got = TapDependencies.infer(script, pipelines, taps, self)
        assert(got == List("ohlcv_tap", "universe_tap"), got)
    }

    test("infer: CTE names, other-catalog tables and the tap itself are ignored") {
        val got = TapDependencies.infer(script, pipelines, taps, self)
        assert(!got.contains("ranked"))
        assert(!got.contains("other_ohlcv_tap"), "same-named table in another catalog")
        assert(!got.contains("rs_rating_tap"), "a tap never depends on itself")
    }

    test("infer: a script that never calls /query/ or /metadata/ infers nothing") {
        val noQuery = script.replace("/api/v1/query/postgres", "/api/v1/somewhere/else")
        assert(!noQuery.contains("/query/") && !noQuery.contains("/metadata/"))
        assert(TapDependencies.infer(noQuery, pipelines, taps, self) == Nil)
    }

    // ------------------------------------------- effective, validate, cycle

    test("effective: dependsOn = [] overrides a non-empty inference") {
        val t = tap("c", dependsOn = jl(), inferred = jl("ohlcv_tap"))
        assert(TapDependencies.effective(t) == Nil)
        // And without an explicit list, the inference is used.
        assert(TapDependencies.effective(tap("c", inferred = jl("ohlcv_tap"))) == List("ohlcv_tap"))
        assert(TapDependencies.effective(tap("c")) == Nil)
    }

    test("validate: dependsOn naming a tap outside the catalog fails and names it") {
        val t = tap("c", dependsOn = jl("other_ohlcv_tap"))
        TapDependencies.validate(t, taps.filter(_.catalog == "Canslim")) match {
            case Left(msg) => assert(msg.contains("other_ohlcv_tap"), msg)
            case Right(_) => fail("a tap of another catalog must not validate")
        }
        assert(TapDependencies.validate(tap("c", dependsOn = jl("ohlcv_tap")), taps.filter(_.catalog == "Canslim")).isRight)
    }

    test("a -> b -> a is a cycle, and order puts both under cycles, not levels") {
        val a = tap("a", dependsOn = jl("b"))
        val b = tap("b", dependsOn = jl("a"))
        val c = tap("c")
        val cyc = TapDependencies.cycle(Seq(a, b, c))
        assert(cyc.isDefined, "cycle not reported")
        assert(cyc.get.toSet == Set("a", "b"), cyc)

        val plan = TapDependencies.order(Seq(a, b, c))
        assert(plan.cycles.exists(_.toSet == Set("a", "b")), plan.cycles)
        val leveled = plan.levels.flatten.map(_.name)
        assert(!leveled.contains("a") && !leveled.contains("b"), leveled)
        assert(leveled.contains("c"))
        assert(TapDependencies.cycle(Seq(c, tap("d", dependsOn = jl("c")))).isEmpty)
    }

    test("cycles lists every cycle, not only the first") {
        val all = Seq(
            tap("a", dependsOn = jl("b")),
            tap("b", dependsOn = jl("a")),
            tap("y", dependsOn = jl("z")),
            tap("z", dependsOn = jl("y")),
            tap("c")
        )
        assert(TapDependencies.cycles(all) == List(List("a", "b"), List("y", "z")))
        assert(TapDependencies.order(all).cycles.map(_.toSet).toSet == Set(Set("a", "b"), Set("y", "z")))
    }

    test("needsBackfill: only an unstamped script tap needs analysing") {
        val scripted = tap("s").copy(scriptPath = "s3://b/k.py")
        assert(TapDependencies.needsBackfill(scripted), "pre-dependency tap with a script")
        assert(TapDependencies.needsBackfill(tap("r").copy(scriptStorage = "github", scriptRepoPath = "taps/r.py")))
        assert(!TapDependencies.needsBackfill(scripted.copy(inferredDependsOn = jl())), "stamped [] = analysed, nothing found")
        assert(!TapDependencies.needsBackfill(scripted.copy(inferredDependsOn = jl("p"))))
        assert(!TapDependencies.needsBackfill(tap("n")), "no script: stays unstamped")
        assert(!TapDependencies.needsBackfill(tap("h").copy(scriptKind = "http", endpointUrl = "https://x.example/tap")))
        assert(!TapDependencies.needsBackfill(tap("__catalog__Canslim").copy(scriptPath = "s3://b/k.py")))
        assert(!TapDependencies.needsBackfill(null))
    }

    test("dedupeDependsOn trims and keeps the first occurrence of each name") {
        val t = tap("c", dependsOn = jl("b", "a", " b ", "a"))
        assert(TapDependencies.dedupeDependsOn(t).dependsOn == jl("b", "a"))
        assert(TapDependencies.dedupeDependsOn(tap("c")).dependsOn == null)
        assert(TapDependencies.dedupeDependsOn(tap("c", dependsOn = jl())).dependsOn == jl())
    }

    // ----------------------------------------------------- order, readiness

    private def names(plan: TapDependencies.Plan): List[List[String]] = plan.levels.map(_.map(_.name).sorted)

    private def readiness(plan: TapDependencies.Plan, name: String): TapDependencies.Readiness =
        plan.levels.flatten.find(_.name == name).getOrElse(fail("no readiness for " + name))

    test("levels are [[producers], [consumers]]; a landed producer does not block") {
        val p1 = tap("p1", lastRunStatus = "success", lastRunRecordCount = 10, lastTestRunStatus = "success")
        val p2 = tap("p2", lastRunStatus = "success", lastRunRecordCount = 3)
        val c = tap("c", dependsOn = jl("p1"), inferred = null, lastTestRunStatus = "success")
        val d = tap("d", inferred = jl("p2"))
        val plan = TapDependencies.order(Seq(c, d, p1, p2))
        assert(names(plan) == List(List("p1", "p2"), List("c", "d")), names(plan))
        assert(plan.cycles.isEmpty)
        val rc = readiness(plan, "c")
        assert(rc.blockedBy.isEmpty, rc)
        assert(rc.hasPassingTest)
        assert(rc.ready)
        assert(readiness(plan, "p1").lastRealRunLanded)
        assert(readiness(plan, "d").blockedBy.isEmpty, "inferred deps count too")
    }

    test("a producer that has not landed data (lastRunStatus = null) blocks its consumer") {
        val p = tap("p", lastRunStatus = null, lastTestRunStatus = "success")
        val c = tap("c", dependsOn = jl("p"), lastTestRunStatus = "success")
        val plan = TapDependencies.order(Seq(p, c))
        val rc = readiness(plan, "c")
        assert(rc.blockedBy == List("p"), rc)
        assert(!rc.ready)
        assert(!readiness(plan, "p").lastRealRunLanded)
        // success with zero records has not landed anything either.
        val p0 = tap("p", lastRunStatus = "success", lastRunRecordCount = 0)
        assert(readiness(TapDependencies.order(Seq(p0, c)), "c").blockedBy == List("p"))
    }

    test("hasPassingTest is false after a restore (test fields cleared by stripTapForExport)") {
        val tested = tap("p", lastRunStatus = "success", lastRunRecordCount = 5, lastTestRunStatus = "success")
        assert(readiness(TapDependencies.order(Seq(tested)), "p").hasPassingTest)
        val restored = CatalogArchive.stripTapForExport(tested)
        val r = readiness(TapDependencies.order(Seq(restored)), "p")
        assert(!r.hasPassingTest)
        assert(!r.lastRealRunLanded)
        assert(!r.ready)
    }

    test("hasPassingTest requires the test stamp to match the current script (same rule as TapCronGate)") {
        val gh = tap("p", lastTestRunStatus = "success")
            .copy(scriptStorage = "github", scriptRepoPath = "taps/p.py", scriptCommitSha = "da9b9576", cronExpression = "0 0 * * * ?")
        val current = gh.copy(lastTestRunScriptId = "gh:da9b9576")
        assert(readiness(TapDependencies.order(Seq(current)), "p").hasPassingTest)
        assert(TapCronGate.check(current, current.copy(enabled = true)).isEmpty)

        // Overwrite restore: the script was committed again, the stamp kept.
        val stale = gh.copy(lastTestRunScriptId = "gh:3cbf6fe9")
        val r = readiness(TapDependencies.order(Seq(stale)), "p")
        assert(!r.hasPassingTest, "a stamp for an older script is no passing test")
        assert(!r.ready)
        assert(TapCronGate.check(stale, stale.copy(enabled = true)).isDefined, "the gate refuses the same tap")

        // A failed last test never counts, stamp or not.
        assert(!readiness(TapDependencies.order(Seq(current.copy(lastTestRunStatus = "failed"))), "p").hasPassingTest)

        // MinIO taps: the injected identity is compared with the stamp.
        val minio = tap("m", lastTestRunStatus = "success").copy(scriptPath = "s3://b/m.py", lastTestRunScriptId = "abc")
        assert(readiness(TapDependencies.order(Seq(minio), _ => Some("abc")), "m").hasPassingTest)
        assert(!readiness(TapDependencies.order(Seq(minio), _ => Some("def")), "m").hasPassingTest)
        assert(!readiness(TapDependencies.order(Seq(minio), _ => None), "m").hasPassingTest, "unreadable script: no match")
        // Unstamped legacy taps keep the old meaning (green last test).
        assert(readiness(TapDependencies.order(Seq(minio.copy(lastTestRunScriptId = null)), _ => None), "m").hasPassingTest)
    }
}
