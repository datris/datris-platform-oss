package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, MongoDBConfig, PipelineStatusRollup, TapConfig}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Enable a catalog's taps in dependency order
  * (plans/stories/tap-dependency-order-enable.md), Acceptance bullets 1-3.
  *
  * The worker is driven with fakes only: no Mongo, no runner, no pipeline
  * status table. Contract pinned here (names the story left open are chosen
  * here; keep them or update this spec):
  *
  * {{{
  * object CatalogEnableJob {
  *   /** Every side effect of the worker. Production wires TapConfigIO,
  *     * TapRunner, CatalogOps.withCatalogLock and PipelineStatusUtil. */
  *   trait Deps {
  *     /** The stored tap (TapConfigIO.read), re-read before the enable. */
  *     def read(name: String): TapConfig
  *     /** TapRunner.run(mode = "test", testLimit = TapAPIController.resolveTestLimit("test", null))
  *       * plus the extracted `stampTestResult` write. `error != null` = failed test. */
  *     def test(tap: TapConfig): TapScriptResult
  *     /** TapConfigIO.writeVersioned(tap, note, actor) under CatalogOps.withCatalogLock.
  *       * `tap` is the stored tap with `enabled = true` (and normalizeCron applied). */
  *     def enable(tap: TapConfig, note: String, actor: String): Unit
  *     /** TapRunner.run(mode = "run", trigger = "manual"). */
  *     def run(tap: TapConfig): TapScriptResult
  *     /** PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(token).rollup */
  *     def rollup(publisherToken: String): PipelineStatusRollup
  *     /** 5000 in production. */
  *     def pollIntervalMs: Long
  *     /** `datris.catalog.enableWaitMs`, default 30 minutes. */
  *     def waitTimeoutMs: Long
  *   }
  *
  *   /** phase: pending|testing|enabling|running|waiting|landed|failed|skipped|cancelled */
  *   case class TapOutcome(level: Int, phase: String, reason: String, recordCount: Int,
  *                         publisherToken: String, rollupStatus: String, updatedAt: Long)
  *
  *   case class Job(id: String, catalog: String, startedBy: String, startedAt: Long,
  *                  plan: TapDependencies.Plan, cancel: AtomicBoolean,
  *                  taps: ConcurrentHashMap[String, TapOutcome],
  *                  @volatile var finishedAt: Option[Long] = None)
  *
  *   /** Left = the 409 text when a job for this catalog (key tenant::catalog) is active. */
  *   def start(catalog: String, actor: String, plan: TapDependencies.Plan, deps: Deps): Either[String, Job]
  *   def status(catalog: String): Option[Job]
  *   /** false when there is no active job. */
  *   def cancel(catalog: String): Boolean
  *   def activeFor(catalog: String): Boolean
  *   /** Body of GET /catalog/{name}/enable-ordered: exactly {active:false} when there is no job. */
  *   def statusBody(catalog: String): com.google.gson.JsonObject
  * }
  * }}}
  *
  * Catalog names are unique per test because the registry is global. The job
  * key reads `DatrisEnvironment.values.multiTenant`, so a minimal environment
  * is installed for the suite.
  */
class CatalogEnableJobSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var savedEnv: DatrisEnvironment = _

    override def beforeAll(): Unit = {
        savedEnv = DatrisEnvironment.values
        DatrisEnvironment.values = CatalogEnableJobSpec.env
    }

    override def afterAll(): Unit = DatrisEnvironment.values = savedEnv

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def tap(
        name: String,
        catalog: String,
        dependsOn: java.util.List[String] = null,
        tested: Boolean = false,
        target: String = "p"
    ): TapConfig =
        TapConfig(
            name = name,
            description = "d",
            targetPipeline = target,
            catalog = catalog,
            enabled = false,
            scriptKind = "http",
            endpointUrl = "https://source.example/" + name,
            dependsOn = dependsOn,
            lastTestRunStatus = if (tested) "success" else null,
            lastTestRunScriptId = if (tested) "http:https://source.example/" + name else null
        )

    /** `a` (level 1) and `b` (level 2, depends on `a`). */
    private def pair(catalog: String, aTested: Boolean = false, bTested: Boolean = false): Seq[TapConfig] =
        Seq(tap("a", catalog, tested = aTested), tap("b", catalog, dependsOn = jl("a"), tested = bTested, target = null))

    private def awaitFinished(catalog: String, ms: Long = 30000L): CatalogEnableJob.Job = {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val j = CatalogEnableJob.status(catalog)
            if (j.exists(_.finishedAt.isDefined)) return j.get
            if (System.currentTimeMillis() > deadline) fail("job for " + catalog + " did not finish: " + j)
            Thread.sleep(10)
        }
        throw new IllegalStateException
    }

    private def await(what: String, ms: Long = 30000L)(cond: => Boolean): Unit = {
        val deadline = System.currentTimeMillis() + ms
        while (!cond) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what)
            Thread.sleep(5)
        }
    }

    private def outcome(j: CatalogEnableJob.Job, name: String): CatalogEnableJob.TapOutcome = {
        val o = j.taps.get(name)
        assert(o != null, "no outcome for " + name + " in " + j.taps)
        o
    }

    private def startOk(catalog: String, deps: CatalogEnableJobSpec.FakeDeps): CatalogEnableJob.Job =
        CatalogEnableJob.start(catalog, "spec-actor", TapDependencies.order(deps.stored.values().asScala.toSeq), deps) match {
            case Right(j) => j
            case Left(err) => fail("start refused: " + err)
        }

    // ── Bullet 1 ────────────────────────────────────────────────────────

    test("a lands, then b is tested, enabled, run and landed, in that order") {
        val cat = "cej-happy"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 5, token = "tok-a")
        // b has no target pipeline: no publisher token, landed without a rollup.
        deps.runResult("b") = CatalogEnableJobSpec.result(recordCount = 3, token = null)
        deps.rollupFor("tok-a") = () => PipelineStatusRollup(allDone = true, status = "success", jobs = new java.util.ArrayList())
        startOk(cat, deps)
        val j = awaitFinished(cat)
        assert(outcome(j, "a").phase == "landed", j.taps)
        assert(outcome(j, "b").phase == "landed", j.taps)
        assert(outcome(j, "a").level == 1 && outcome(j, "b").level == 2, j.taps)
        val ev = deps.events.toList
        val order = Seq("test:a", "enable:a", "run:a", "rollup:tok-a", "test:b", "enable:b", "run:b")
        order.foreach(e => assert(ev.contains(e), s"$e missing from $ev"))
        order.sliding(2).foreach { case Seq(x, y) => assert(ev.indexOf(x) < ev.indexOf(y), s"$x before $y in $ev") }
        assert(deps.enabled.forall(_._1.enabled), "enable writes the tap with enabled=true")
        assert(deps.enabled.map(_._3).forall(_ == "spec-actor"), deps.enabled)
        assert(!CatalogEnableJob.activeFor(cat))
    }

    test("a run that returns an error fails a and skips b with a reason naming a") {
        val cat = "cej-error"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 0, token = "tok-a", error = "source unreachable")
        startOk(cat, deps)
        val j = awaitFinished(cat)
        assert(outcome(j, "a").phase == "failed", j.taps)
        val b = outcome(j, "b")
        assert(b.phase == "skipped", j.taps)
        assert(b.reason != null && "\\ba\\b".r.findFirstIn(b.reason).isDefined, "reason names a: " + b.reason)
        assert(b.reason.contains("did not land"), b.reason)
        assert(!deps.events.exists(e => e.endsWith(":b")), "nothing happens to b: " + deps.events)
    }

    test("a that lands zero records fails (no_records) and b is skipped") {
        val cat = "cej-zero"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 0, token = "tok-a")
        deps.rollupFor("tok-a") = () => PipelineStatusRollup(allDone = true, status = "success", jobs = new java.util.ArrayList())
        startOk(cat, deps)
        val j = awaitFinished(cat)
        assert(outcome(j, "a").phase == "failed", j.taps)
        assert(Option(outcome(j, "a").reason).exists(_.contains("no_records")), outcome(j, "a").reason)
        assert(outcome(j, "b").phase == "skipped", j.taps)
        assert(Option(outcome(j, "b").reason).exists(r => "\\ba\\b".r.findFirstIn(r).isDefined), outcome(j, "b").reason)
        assert(!deps.events.exists(e => e.endsWith(":b")), deps.events)
    }

    // ── Bullet 2 ────────────────────────────────────────────────────────

    test("a tap with hasPassingTest=true is never tested") {
        val cat = "cej-tested"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat, aTested = true, bTested = true))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 2, token = null)
        deps.runResult("b") = CatalogEnableJobSpec.result(recordCount = 2, token = null)
        startOk(cat, deps)
        val j = awaitFinished(cat)
        assert(outcome(j, "a").phase == "landed" && outcome(j, "b").phase == "landed", j.taps)
        assert(!deps.events.exists(_.startsWith("test:")), "no test run: " + deps.events)
        assert(deps.events.contains("run:a") && deps.events.contains("run:b"), deps.events)
    }

    test("a failing test fails the tap: nothing enabled, no run") {
        val cat = "cej-badtest"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        deps.testError("a") = "ModuleNotFoundError: requests"
        startOk(cat, deps)
        val j = awaitFinished(cat)
        assert(outcome(j, "a").phase == "failed", j.taps)
        assert(deps.events.contains("test:a"), deps.events)
        assert(deps.enabled.isEmpty, "nothing enabled: " + deps.enabled)
        assert(!deps.events.exists(_.startsWith("run:")), "no run: " + deps.events)
        assert(!deps.stored.get("a").enabled)
        assert(outcome(j, "b").phase == "skipped", j.taps)
    }

    test("a TapCronGate refusal becomes failed with that text, and the tap is not enabled") {
        val cat = "cej-cron"
        // Scheduled, last test green, but the stamp names an older endpoint: the gate refuses.
        val a = tap("a", cat, tested = true).copy(cronExpression = "0 0 * * * ?", lastTestRunScriptId = "http:https://source.example/old")
        val refusal = TapCronGate.check(a, a.copy(enabled = true))
        assert(refusal.isDefined, "fixture must be refused by the gate")
        val deps = new CatalogEnableJobSpec.FakeDeps(Seq(a))
        startOk(cat, deps)
        val j = awaitFinished(cat)
        val o = outcome(j, "a")
        assert(o.phase == "failed", j.taps)
        assert(Option(o.reason).exists(_.contains(refusal.get)), s"reason ${o.reason} should carry ${refusal.get}")
        assert(deps.enabled.isEmpty, deps.enabled)
        assert(!deps.events.exists(_.startsWith("run:")), deps.events)
    }

    // ── Bullet 3 ────────────────────────────────────────────────────────

    test("cancel during a's wait: a finishes its phase, b is cancelled, finishedAt is set") {
        val cat = "cej-cancel"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        val release = new AtomicBoolean(false)
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 4, token = "tok-a")
        deps.rollupFor("tok-a") = () =>
            if (release.get()) PipelineStatusRollup(allDone = true, status = "success", jobs = new java.util.ArrayList())
            else PipelineStatusRollup(allDone = false, status = "processing", jobs = new java.util.ArrayList())
        startOk(cat, deps)
        await("a waiting")(CatalogEnableJob.status(cat).exists(j => Option(j.taps.get("a")).exists(_.phase == "waiting")))
        assert(CatalogEnableJob.activeFor(cat))
        assert(CatalogEnableJob.cancel(cat), "cancel of an active job returns true")
        release.set(true)
        val j = awaitFinished(cat)
        assert(j.finishedAt.isDefined)
        assert(outcome(j, "a").phase == "landed", "the in-flight wait completes: " + j.taps)
        assert(outcome(j, "b").phase == "cancelled", j.taps)
        assert(!deps.events.exists(e => e.endsWith(":b")), "b never touched: " + deps.events)
        assert(!CatalogEnableJob.activeFor(cat))
        assert(!CatalogEnableJob.cancel(cat), "nothing left to cancel")
    }

    test("a second start while active is Left; after finishedAt a new start succeeds") {
        val cat = "cej-twice"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat))
        val release = new AtomicBoolean(false)
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = "tok-a")
        deps.runResult("b") = CatalogEnableJobSpec.result(recordCount = 1, token = null)
        deps.rollupFor("tok-a") = () =>
            PipelineStatusRollup(allDone = release.get(), status = if (release.get()) "success" else "processing", jobs = new java.util.ArrayList())
        val first = startOk(cat, deps)
        await("a waiting")(Option(first.taps.get("a")).exists(_.phase == "waiting"))
        val again = CatalogEnableJob.start(cat, "spec-actor", TapDependencies.order(pair(cat)), new CatalogEnableJobSpec.FakeDeps(pair(cat)))
        assert(again.isLeft, "second start while active must be refused: " + again)
        release.set(true)
        awaitFinished(cat)
        val deps2 = new CatalogEnableJobSpec.FakeDeps(pair(cat, aTested = true, bTested = true))
        deps2.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = null)
        deps2.runResult("b") = CatalogEnableJobSpec.result(recordCount = 1, token = null)
        val second = CatalogEnableJob.start(cat, "spec-actor", TapDependencies.order(deps2.stored.values().asScala.toSeq), deps2)
        assert(second.isRight, "a finished job does not block a new one: " + second)
        assert(second.toOption.get.id != first.id)
        awaitFinished(cat)
    }

    test("a rollup that never reaches allDone fails with timed out after waitTimeoutMs") {
        val cat = "cej-timeout"
        val deps = new CatalogEnableJobSpec.FakeDeps(pair(cat), waitMs = 200L)
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 9, token = "tok-a")
        deps.rollupFor("tok-a") = () => PipelineStatusRollup(allDone = false, status = "processing", jobs = new java.util.ArrayList())
        val t0 = System.currentTimeMillis()
        startOk(cat, deps)
        val j = awaitFinished(cat, 10000L)
        val a = outcome(j, "a")
        assert(a.phase == "failed", j.taps)
        assert(Option(a.reason).exists(_.startsWith("timed out")), a.reason)
        assert(System.currentTimeMillis() - t0 >= 200L, "waited at least waitTimeoutMs")
        assert(deps.events.count(_ == "rollup:tok-a") >= 2, "the rollup is polled: " + deps.events)
        assert(outcome(j, "b").phase == "skipped", j.taps)
    }

    test("statusBody is exactly {active:false} when the catalog has no job") {
        val body = CatalogEnableJob.statusBody("cej-never-started")
        assert(body.toString == """{"active":false}""", body.toString)
        assert(CatalogEnableJob.status("cej-never-started").isEmpty)
        assert(!CatalogEnableJob.activeFor("cej-never-started"))
    }
}

object CatalogEnableJobSpec {

    val env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false
    )

    def result(recordCount: Int, token: String, error: String = null): TapScriptResult =
        TapScriptResult(staged = null, recordCount = recordCount, error = error, publisherToken = token)

    /** In-memory store + recorded calls. Unconfigured runs return one record
      * with no publisher token (landed); unconfigured rollups never finish. */
    class FakeDeps(taps: Seq[TapConfig], waitMs: Long = 30000L) extends CatalogEnableJob.Deps {
        val stored = new ConcurrentHashMap[String, TapConfig]()
        taps.foreach(t => stored.put(t.name, t))
        val events: mutable.Buffer[String] = java.util.Collections.synchronizedList(new java.util.ArrayList[String]()).asScala
        val enabled: mutable.Buffer[(TapConfig, String, String)] =
            java.util.Collections.synchronizedList(new java.util.ArrayList[(TapConfig, String, String)]()).asScala
        val runResult = new ConcurrentHashMap[String, TapScriptResult]().asScala
        val testError = new ConcurrentHashMap[String, String]().asScala
        val rollupFor = new ConcurrentHashMap[String, () => PipelineStatusRollup]().asScala

        override def read(name: String): TapConfig = stored.get(name)

        override def test(tap: TapConfig): TapScriptResult = {
            events += ("test:" + tap.name)
            testError.get(tap.name) match {
                case Some(err) =>
                    stored.computeIfPresent(tap.name, (_, t) => t.copy(lastTestRunStatus = "failed", lastTestRunError = err))
                    result(0, null, err)
                case None =>
                    stored.computeIfPresent(
                        tap.name,
                        (_, t) => t.copy(lastTestRunStatus = "success", lastTestRunScriptId = TapScriptIdentity.of(t, _ => None).orNull)
                    )
                    result(1, null)
            }
        }

        override def enable(tap: TapConfig, note: String, actor: String): Unit = {
            events += ("enable:" + tap.name)
            enabled += ((tap, note, actor))
            stored.put(tap.name, tap)
        }

        override def run(tap: TapConfig): TapScriptResult = {
            events += ("run:" + tap.name)
            runResult.getOrElse(tap.name, result(1, null))
        }

        override def rollup(publisherToken: String): PipelineStatusRollup = {
            events += ("rollup:" + publisherToken)
            rollupFor.get(publisherToken).map(_.apply())
                .getOrElse(PipelineStatusRollup(allDone = false, status = "processing", jobs = new java.util.ArrayList()))
        }

        override def pollIntervalMs: Long = 10L
        override def waitTimeoutMs: Long = waitMs
    }
}
