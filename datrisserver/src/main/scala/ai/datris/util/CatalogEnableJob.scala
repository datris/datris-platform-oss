package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, PipelineStatusRollup, TapConfig, TenantContext}
import com.google.gson.{JsonArray, JsonObject}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

/** One server-side job per catalog that walks the catalog's enable order
  * (plans/stories/tap-dependency-order-enable.md): for each tap, test it if
  * it has no passing test, enable it, do a real run and wait until the
  * pipeline rollup says the data landed, then move on. A tap whose source
  * did not land is skipped with the reason.
  *
  * Mutual exclusion is the registry itself (one active job per
  * `tenant::catalog`, started atomically); the global catalog lock is only
  * taken around each enable write (by the production [[Deps]]), never for the
  * job's lifetime. Finished jobs stay readable for [[RetentionMs]].
  *
  * Levels run in order and taps within a level sequentially. Cancel is
  * checked between phases: the in-flight test, run or load wait finishes
  * (there is no script kill) and every tap not yet started is `cancelled`. */
object CatalogEnableJob {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Every side effect of the worker. Production wires TapConfigIO,
      * TapRunner, CatalogOps.withCatalogLock and PipelineStatusUtil. */
    trait Deps {

        /** The stored tap (TapConfigIO.read), re-read before the enable. */
        def read(name: String): TapConfig

        /** TapRunner.run(mode = "test") plus the test-result stamp write.
          * `error != null` = failed test. */
        def test(tap: TapConfig): TapScriptResult

        /** TapConfigIO.writeVersioned(tap, note, actor) under
          * CatalogOps.withCatalogLock. `tap` is the stored tap with
          * `enabled = true` (and normalizeCron applied). */
        def enable(tap: TapConfig, note: String, actor: String): Unit

        /** TapRunner.run(mode = "run", trigger = "manual"). */
        def run(tap: TapConfig): TapScriptResult

        /** PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(token).rollup */
        def rollup(publisherToken: String): PipelineStatusRollup

        /** 5000 in production. */
        def pollIntervalMs: Long

        /** `datris.catalog.enableWaitMs`, default 30 minutes. */
        def waitTimeoutMs: Long
    }

    /** phase: pending|testing|enabling|running|waiting|landed|failed|skipped|cancelled */
    case class TapOutcome(
        level: Int,
        phase: String,
        reason: String,
        recordCount: Int,
        publisherToken: String,
        rollupStatus: String,
        updatedAt: Long
    )

    case class Job(
        id: String,
        catalog: String,
        startedBy: String,
        startedAt: Long,
        plan: TapDependencies.Plan,
        cancel: AtomicBoolean,
        taps: ConcurrentHashMap[String, TapOutcome],
        @volatile var finishedAt: Option[Long] = None
    )

    /** How long a finished job stays readable. */
    val RetentionMs: Long = 60L * 60L * 1000L

    /** Default load wait per tap; StartupRunner sets it from
      * `datris.catalog.enableWaitMs`. */
    @volatile var defaultWaitTimeoutMs: Long = 30L * 60L * 1000L

    val EnableNote = "enabled by ordered catalog enable"

    private val Terminal = Set("landed", "failed", "skipped", "cancelled")
    private val DidNotLand = Set("failed", "skipped", "cancelled")

    private val jobs = new ConcurrentHashMap[String, Job]()

    /** Same tenant key as the tap-run debounce (TapAPIController.recentRunStarts). */
    private def key(catalog: String): String = {
        val tenant = if (DatrisEnvironment.values.multiTenant) DatrisEnvironment.current.environment else "global"
        tenant + "::" + catalog
    }

    private def purgeExpired(): Unit = {
        val cutoff = System.currentTimeMillis() - RetentionMs
        jobs.entrySet().removeIf(e => e.getValue.finishedAt.exists(_ < cutoff))
    }

    /** Left = the 409 text when a job for this catalog is active. */
    def start(catalog: String, actor: String, plan: TapDependencies.Plan, deps: Deps): Either[String, Job] = {
        purgeExpired()
        val now = System.currentTimeMillis()
        val outcomes = new ConcurrentHashMap[String, TapOutcome]()
        plan.levels.zipWithIndex.foreach { case (lvl, i) =>
            lvl.foreach(r => outcomes.put(r.name, TapOutcome(i + 1, "pending", null, 0, null, null, now)))
        }
        val candidate = Job(UUID.randomUUID().toString, catalog, actor, now, plan, new AtomicBoolean(false), outcomes)
        val created = new AtomicBoolean(false)
        val current = jobs.compute(
            key(catalog),
            (_, existing) =>
                if (existing != null && existing.finishedAt.isEmpty) existing
                else { created.set(true); candidate }
        )
        if (!created.get())
            return Left(
                "An enable job for catalog '" + catalog + "' is already running (job " + current.id + ", started by " +
                    current.startedBy + "). Wait for it to finish or cancel it."
            )
        val tenant = TenantContext.get()
        val worker = new Thread(
            new Runnable {
                override def run(): Unit = {
                    tenant.foreach(TenantContext.set)
                    try work(candidate, deps)
                    finally TenantContext.clear()
                }
            },
            "catalog-enable-" + catalog
        )
        worker.setDaemon(true)
        try worker.start()
        catch {
            case e: Throwable =>
                candidate.finishedAt = Some(System.currentTimeMillis())
                throw e
        }
        logger.info("Ordered enable of catalog '" + catalog + "' started by " + actor + " (job " + candidate.id + ")")
        Right(candidate)
    }

    def status(catalog: String): Option[Job] = {
        purgeExpired()
        Option(jobs.get(key(catalog)))
    }

    /** false when there is no active job. */
    def cancel(catalog: String): Boolean =
        Option(jobs.get(key(catalog))).filter(_.finishedAt.isEmpty) match {
            case Some(j) =>
                j.cancel.set(true)
                logger.info("Ordered enable of catalog '" + catalog + "' cancel requested (job " + j.id + ")")
                true
            case None => false
        }

    def activeFor(catalog: String): Boolean =
        Option(jobs.get(key(catalog))).exists(_.finishedAt.isEmpty)

    /** Body of GET /catalog/{name}/enable-ordered: exactly {active:false}
      * when there is no job; otherwise the job with one entry per tap in
      * plan order. A finished job reads `active: false` with `finishedAt`. */
    def statusBody(catalog: String): JsonObject = {
        val out = new JsonObject
        status(catalog) match {
            case None => out.addProperty("active", false)
            case Some(j) =>
                out.addProperty("active", j.finishedAt.isEmpty)
                out.addProperty("jobId", j.id)
                out.addProperty("catalog", j.catalog)
                out.addProperty("startedBy", j.startedBy)
                out.addProperty("startedAt", j.startedAt)
                j.finishedAt.foreach(f => out.addProperty("finishedAt", f))
                out.addProperty("cancelRequested", j.cancel.get())
                val taps = new JsonArray
                val counts = scala.collection.mutable.LinkedHashMap.empty[String, Int]
                j.plan.levels.flatten.foreach { r =>
                    Option(j.taps.get(r.name)).foreach { o =>
                        val t = new JsonObject
                        t.addProperty("name", r.name)
                        t.addProperty("level", o.level)
                        t.addProperty("phase", o.phase)
                        t.addProperty("reason", o.reason)
                        t.addProperty("recordCount", o.recordCount)
                        if (o.publisherToken != null) t.addProperty("publisherToken", o.publisherToken)
                        if (o.rollupStatus != null) t.addProperty("rollupStatus", o.rollupStatus)
                        t.addProperty("updatedAt", o.updatedAt)
                        taps.add(t)
                        counts(o.phase) = counts.getOrElse(o.phase, 0) + 1
                    }
                }
                out.add("taps", taps)
                val summary = new JsonObject
                counts.foreach { case (k, v) => summary.addProperty(k, v) }
                out.add("summary", summary)
        }
        out
    }

    // ── worker ──────────────────────────────────────────────────────────

    private def set(job: Job, name: String, phase: String, reason: String = null)(
        f: TapOutcome => TapOutcome = identity
    ): Unit = {
        val prev = job.taps.get(name)
        if (prev != null)
            job.taps.put(name, f(prev.copy(phase = phase, reason = reason, updatedAt = System.currentTimeMillis())))
    }

    private def work(job: Job, deps: Deps): Unit = {
        try {
            val readiness = job.plan.levels.flatten.map(r => r.name -> r).toMap
            job.plan.levels.flatten.foreach { r =>
                if (job.cancel.get()) set(job, r.name, "cancelled", "cancelled before it started")()
                else
                    try processTap(job, r, readiness, deps)
                    catch {
                        case e: Exception =>
                            logger.warn("Ordered enable of catalog '" + job.catalog + "': tap '" + r.name + "' failed: " + e.getMessage)
                            set(job, r.name, "failed", "error: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName))()
                    }
            }
        } catch {
            case e: Throwable =>
                logger.error("Ordered enable of catalog '" + job.catalog + "' stopped: " + e.getMessage, e)
                job.taps.asScala.foreach { case (n, o) =>
                    if (!Terminal.contains(o.phase)) set(job, n, "failed", "the enable job stopped: " + e.getMessage)()
                }
        } finally {
            job.finishedAt = Some(System.currentTimeMillis())
            val summary = job.taps.asScala.toSeq.map(_._2.phase).groupBy(identity).map { case (k, v) => k + "=" + v.size }
            logger.info("Ordered enable of catalog '" + job.catalog + "' finished (job " + job.id + "): " + summary.mkString(", "))
        }
    }

    /** True to stop: the remaining phases of this tap are cancelled. */
    private def cancelled(job: Job, name: String, before: String): Boolean =
        if (job.cancel.get()) { set(job, name, "cancelled", "cancelled before " + before)(); true }
        else false

    private def processTap(job: Job, r: TapDependencies.Readiness, readiness: Map[String, TapDependencies.Readiness], deps: Deps): Unit = {
        val name = r.name

        // Never re-run a tap that was already enabled and had landed data.
        if (r.enabled && r.lastRealRunLanded) {
            set(job, name, "landed", "already enabled with data; not re-run")()
            return
        }

        // (a) every effective dependency must have landed (before or in this job).
        val effective = if (r.dependsOn != null) r.dependsOn else Option(r.inferredDependsOn).getOrElse(Nil)
        val notLanded = effective.distinct.filter { d =>
            val o = job.taps.get(d)
            if (o != null) DidNotLand.contains(o.phase) || (o.phase != "landed" && !readiness.get(d).exists(_.lastRealRunLanded))
            else r.blockedBy.contains(d)
        }
        if (notLanded.nonEmpty) {
            set(job, name, "skipped", "source " + notLanded.mkString(", ") + " did not land data")()
            return
        }

        // (b) test when there is no passing test.
        if (!r.hasPassingTest) {
            set(job, name, "testing")()
            val stored = deps.read(name)
            if (stored == null) { set(job, name, "failed", "tap no longer exists")(); return }
            val res = deps.test(stored)
            if (res.error != null) {
                set(job, name, "failed", "test failed: " + clip(res.error))()
                return
            }
            if (cancelled(job, name, "enabling")) return
        }

        // (c) enable through the test-before-cron gate.
        set(job, name, "enabling")()
        val existing = deps.read(name)
        if (existing == null) { set(job, name, "failed", "tap no longer exists")(); return }
        if (existing.catalog != job.catalog) {
            set(job, name, "failed", "tap moved out of catalog '" + job.catalog + "'")()
            return
        }
        val normalized = TapCronGate.normalizeCron(existing)
        // Same checks as a tap save: an unparsable (legacy) cron would make
        // the scheduler fail on every tick once the tap is enabled.
        TapCronValidation.check(existing.name, normalized.cronExpression) match {
            case Some(invalid) =>
                set(job, name, "failed", invalid)()
                return
            case None =>
        }
        val toEnable = normalized.copy(enabled = true)
        TapCronGate.check(existing, toEnable) match {
            case Some(refusal) =>
                set(job, name, "failed", refusal)()
                return
            case None =>
        }
        if (!existing.enabled || (normalized ne existing)) deps.enable(toEnable, EnableNote, job.startedBy)
        if (job.cancel.get()) {
            // The tap stays enabled (the write is done); only its run is skipped.
            set(job, name, "cancelled", "enabled; run cancelled")()
            return
        }

        // (d) a real run, then wait for the pipeline load.
        set(job, name, "running")()
        val runnable = Option(deps.read(name)).getOrElse(toEnable)
        val res = deps.run(runnable)
        if (res.error != null) {
            set(job, name, "failed", "run failed: " + clip(res.error))(_.copy(recordCount = res.recordCount))
            return
        }
        if (res.recordCount == 0) {
            set(job, name, "failed", "no_records: the run returned no records, so dependent taps would find no data")(
                _.copy(recordCount = 0, publisherToken = res.publisherToken)
            )
            return
        }
        if (res.publisherToken == null) {
            set(job, name, "landed", "no target pipeline to wait for")(_.copy(recordCount = res.recordCount))
            return
        }
        set(job, name, "waiting")(_.copy(recordCount = res.recordCount, publisherToken = res.publisherToken))
        waitForLoad(job, name, res.publisherToken, deps)
    }

    private def waitForLoad(job: Job, name: String, token: String, deps: Deps): Unit = {
        val timeout = deps.waitTimeoutMs
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val rollup =
                try Option(deps.rollup(token))
                catch {
                    case e: Exception =>
                        logger.warn("Ordered enable: rollup read for tap '" + name + "' failed: " + e.getMessage)
                        None
                }
            rollup.foreach { r =>
                set(job, name, "waiting")(_.copy(rollupStatus = r.status))
                if (r.allDone) {
                    if (r.status == "success" || r.status == "warning") set(job, name, "landed")(_.copy(rollupStatus = r.status))
                    else set(job, name, "failed", "the pipeline load ended with status " + r.status)(_.copy(rollupStatus = r.status))
                    return
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                set(job, name, "failed", "timed out waiting for the load after " + describe(timeout))()
                return
            }
            Thread.sleep(math.max(1L, deps.pollIntervalMs))
        }
    }

    private def describe(ms: Long): String =
        if (ms >= 60000L && ms % 60000L == 0) (ms / 60000L) + " min" else ms + " ms"

    private def clip(s: String): String = if (s.length > 500) s.take(500) + "…" else s
}
