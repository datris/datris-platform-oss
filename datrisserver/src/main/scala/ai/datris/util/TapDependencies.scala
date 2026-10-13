package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, TapConfig}

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Dependencies between the taps of one catalog: a tap that reads another
  * tap's destination table (through the query or metadata endpoints) can only
  * test green once that producer has landed data. Pure: no Mongo, no network.
  *
  * Dependencies order and explain only; nothing enforces them (save, test,
  * run and cron behave exactly as without them).
  *
  *   - `dependsOn` (explicit, user-set) wins whenever it is non-null; `[]`
  *     means "none, ignore inference".
  *   - `inferredDependsOn` is stamped by the server from the stored script
  *     (see [[infer]]).
  */
object TapDependencies {

    /** Per-tap readiness in an enable order. `hasPassingTest` = the last
      * mode=test run succeeded; `lastRealRunLanded` = the last real run
      * succeeded with at least one record; `blockedBy` = dependencies that
      * have not landed data yet; `ready` = tested and not blocked. */
    case class Readiness(
        name: String,
        enabled: Boolean,
        cronExpression: String,
        dependsOn: List[String],
        inferredDependsOn: List[String],
        explicit: Boolean,
        hasPassingTest: Boolean,
        lastRealRunLanded: Boolean,
        blockedBy: List[String],
        ready: Boolean
    )

    /** `levels(0)` = taps with no dependencies (leaf producers). Taps on a
      * dependency cycle are listed under `cycles`, never in `levels`. */
    case class Plan(levels: List[List[Readiness]], cycles: List[List[String]])

    private val QueryHint = Seq("/query/", "/metadata/")
    private val TableRef = """(?i)\b(?:FROM|JOIN)\s+([A-Za-z_][\w.]*)""".r
    private val CollectionRef = """['"]collection['"]\s*:\s*['"](\w+)['"]""".r
    // Dataset kinds readable through the Postgres and Mongo query endpoints.
    private val QueryableKinds = Set("postgres", "pgvector", "mongodb")
    private val NameCoords = Set("table", "collection", "tableName")

    private def list(xs: java.util.List[String]): List[String] =
        if (xs == null) Nil else xs.asScala.toList.filter(n => n != null && n.trim.nonEmpty)

    /** Table and collection names a script reads through the query endpoints,
      * lower-cased with any schema prefix stripped. Empty unless the script
      * mentions `/query/` or `/metadata/`. */
    def referencedTables(script: String): Set[String] = {
        if (script == null || !QueryHint.exists(script.contains)) return Set.empty
        val tables = TableRef.findAllMatchIn(script).map { m =>
            val full = m.group(1)
            full.substring(full.lastIndexOf('.') + 1)
        }
        val collections = CollectionRef.findAllMatchIn(script).map(_.group(1))
        (tables ++ collections).map(_.toLowerCase).filter(_.nonEmpty).toSet
    }

    /** Same-catalog producer taps whose pipelines' destination tables the
      * script reads. `pipelines` and `taps` may span catalogs; only
      * `self.catalog` counts. Sorted, distinct, never `self`. */
    def infer(script: String, pipelines: Seq[PipelineConfig], taps: Seq[TapConfig], self: TapConfig): List[String] = {
        if (self == null) return Nil
        val refs = referencedTables(script)
        if (refs.isEmpty) return Nil
        val catalog = self.catalog
        val readPipelines = pipelines
            .filter(p => p != null && p.catalog == catalog)
            .filter { p =>
                LineageService.datasets(p).exists { d =>
                    QueryableKinds.contains(d.kind) &&
                    d.coords.exists { case (k, v) => NameCoords.contains(k) && v != null && refs.contains(v.toLowerCase) }
                }
            }
            .map(_.name)
            .toSet
        taps
            .filter(t => t != null && t.catalog == catalog && !CatalogOps.Placeholder.is(t.name))
            .filter(t => t.targetPipeline != null && readPipelines.contains(t.targetPipeline))
            .map(_.name)
            .filter(_ != self.name)
            .distinct
            .sorted
            .toList
    }

    /** `dependsOn` when non-null (even empty), else `inferredDependsOn`, else Nil. */
    def effective(t: TapConfig): List[String] =
        if (t == null) Nil
        else if (t.dependsOn != null) list(t.dependsOn).distinct
        else list(t.inferredDependsOn).distinct

    /** Left names the offending entry: not a tap of `t`'s catalog, or `t`
      * itself. `catalogTaps` = the taps of `t`'s catalog. */
    def validate(t: TapConfig, catalogTaps: Seq[TapConfig]): Either[String, Unit] = {
        val deps = list(t.dependsOn)
        if (deps.contains(t.name)) return Left("Tap '" + t.name + "' cannot depend on itself")
        val known = catalogTaps.filter(x => x != null && !CatalogOps.Placeholder.is(x.name)).map(_.name).toSet
        val unknown = deps.filterNot(known.contains).distinct
        if (unknown.isEmpty) Right(())
        else {
            val where = Option(t.catalog).filter(_.nonEmpty).map("catalog '" + _ + "'").getOrElse("this catalog")
            Left(
                "dependsOn names " + (if (unknown.size == 1) "a tap that is" else "taps that are") + " not in " + where + ": " +
                    unknown.mkString(", ")
            )
        }
    }

    /** Dependency graph over the given taps (edges to names outside the set
      * are dropped). */
    private def graph(taps: Seq[TapConfig]): Map[String, List[String]] = {
        val names = taps.filter(_ != null).map(_.name).toSet
        taps.filter(_ != null).map(t => t.name -> effective(t).filter(names.contains)).toMap
    }

    /** Strongly connected components that are real cycles (more than one
      * member, or a self-edge), each sorted, in name order. */
    private def cyclesOf(g: Map[String, List[String]]): List[List[String]] = {
        var index = 0
        val idx = mutable.Map.empty[String, Int]
        val low = mutable.Map.empty[String, Int]
        val onStack = mutable.Set.empty[String]
        val stack = mutable.Stack.empty[String]
        val out = mutable.ListBuffer.empty[List[String]]
        def strong(v: String): Unit = {
            idx(v) = index; low(v) = index; index += 1
            stack.push(v); onStack += v
            g.getOrElse(v, Nil).foreach { w =>
                if (!idx.contains(w)) { strong(w); low(v) = math.min(low(v), low(w)) }
                else if (onStack.contains(w)) low(v) = math.min(low(v), idx(w))
            }
            if (low(v) == idx(v)) {
                val comp = mutable.ListBuffer.empty[String]
                var w = ""
                while ({ w = stack.pop(); onStack -= w; comp += w; w != v }) {}
                if (comp.size > 1 || g.getOrElse(v, Nil).contains(v)) out += comp.toList.sorted
            }
        }
        g.keys.toList.sorted.foreach(v => if (!idx.contains(v)) strong(v))
        out.toList.sortBy(_.head)
    }

    /** The first dependency cycle among `taps` (its members, sorted), if any. */
    def cycle(taps: Seq[TapConfig]): Option[List[String]] = cyclesOf(graph(taps)).headOption

    /** Kahn over `effective`: level 1 (index 0) = taps with no dependencies.
      * Cycle members go under `cycles`; a tap that depends on a cycle is
      * levelled as if those edges were absent (it still lists them in
      * `blockedBy` until they land data). */
    def order(taps: Seq[TapConfig]): Plan = {
        val present = taps.filter(_ != null)
        val byName = present.map(t => t.name -> t).toMap
        val g = graph(present)
        val cycles = cyclesOf(g)
        val inCycle = cycles.flatten.toSet
        val nodes = present.map(_.name).filterNot(inCycle.contains).distinct
        val deps = nodes.map(n => n -> g.getOrElse(n, Nil).filterNot(inCycle.contains).toSet).toMap

        def landed(n: String): Boolean = byName.get(n).exists { t =>
            t.lastRunStatus == "success" && t.lastRunRecordCount > 0
        }
        def readiness(t: TapConfig): Readiness = {
            val eff = effective(t)
            val blocked = eff.filter(d => byName.contains(d) && !landed(d))
            val tested = t.lastTestRunStatus == "success"
            Readiness(
                name = t.name,
                enabled = t.enabled,
                cronExpression = t.cronExpression,
                dependsOn = if (t.dependsOn == null) null else list(t.dependsOn),
                inferredDependsOn = if (t.inferredDependsOn == null) null else list(t.inferredDependsOn),
                explicit = t.dependsOn != null,
                hasPassingTest = tested,
                lastRealRunLanded = landed(t.name),
                blockedBy = blocked,
                ready = tested && blocked.isEmpty
            )
        }

        val levels = mutable.ListBuffer.empty[List[Readiness]]
        val done = mutable.Set.empty[String]
        var remaining = nodes.toSet
        while (remaining.nonEmpty) {
            val next = remaining.filter(n => deps(n).forall(done.contains)).toList.sorted
            // Unreachable: every cycle member was removed above.
            if (next.isEmpty) remaining = Set.empty
            else {
                levels += next.map(n => readiness(byName(n)))
                done ++= next
                remaining --= next
            }
        }
        Plan(levels.toList, cycles)
    }
}
