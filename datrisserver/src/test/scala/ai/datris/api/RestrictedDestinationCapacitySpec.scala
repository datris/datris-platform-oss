package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationProbe, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** Story: Catalog-scoped user access: one restricted user cannot use up the
  * new-destination check for everyone else. A restricted user's third
  * concurrent check answers 429 `TooManyChecksRefusal` with `Retry-After`
  * for save, version restore and rename; other users and unrestricted
  * callers are unaffected. Fixtures copied from
  * RestrictedDestinationDeadlineSpec, not shared. Every parked body is
  * released at the end of its test, so the pool is left idle. */
class RestrictedDestinationCapacitySpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def restricted(name: String, level: String = "edit"): ResolvedKey =
        APIKeyValidator.resolveFromSession(User(name, "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> level)))
    private def bob: ResolvedKey = restricted("bob")
    private def carol: ResolvedKey = restricted("carol")
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(None, "sales-key", Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales")), isLegacyFullAccess = false)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private val TooManyText =
        "Another destination check of yours is still running; nothing was saved. Wait a moment and try again."

    private def os(name: String, prefix: String, format: String = "parquet"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination = Destination(objectStore = ObjectStore(prefixKey = prefix, fileFormat = format))
        )

    private def snow(name: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "erin",
            destination =
                Destination(database = Database(dbName = "analytics", schema = "public", table = table, useSnowflake = true, credentialsSecret = "sf-main"))
        )

    private def eventually(cond: => Boolean, ms: Long = 10000): Boolean = {
        val until = System.currentTimeMillis() + ms
        while (!cond && System.currentTimeMillis() < until) Thread.sleep(20)
        cond
    }

    /** `n` bodies held under `owner` on probe workers until `done()`. */
    private final class Parked(owner: String, n: Int = DestinationProbe.MaxPerOwner) {
        private val release = new CountDownLatch(1)
        private val started = new CountDownLatch(n)
        private val callers: ExecutorService = Executors.newFixedThreadPool(n)
        for (_ <- 1 to n)
            callers.submit(new Runnable {
                def run(): Unit =
                    DestinationProbe.withDeadline(60.seconds, "parked", owner) { started.countDown(); release.await(60, TimeUnit.SECONDS) }
            })
        assert(started.await(10, TimeUnit.SECONDS), "the parked bodies did not all start")
        def done(): Unit = {
            release.countDown()
            callers.shutdown()
            assert(callers.awaitTermination(30, TimeUnit.SECONDS))
            assert(eventually(DestinationProbe.inFlight(owner) == 0), owner)
        }
    }

    private def assertTooMany(r: Option[ResponseEntity[String]], what: String): Unit = {
        assert(r.isDefined, what + ": answered None (absent) at the limit")
        assert(r.get.getStatusCode.value == 429, what + ": " + r.get.getStatusCode + " " + r.get.getBody)
        assert(r.get.getBody == ApiErrors.errorBody(DestinationTargets.TooManyChecksRefusal), what + ": " + r.get.getBody)
        assert(r.get.getHeaders.getFirst("Retry-After") == DestinationTargets.RetryAfterSeconds, what + ": " + r.get.getHeaders)
    }

    private def save(
        rk: ResolvedKey,
        c: PipelineConfig,
        exists: DestinationTargets.Target => Boolean,
        committed: String => Boolean = _ => false
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(req(rk), c, null, Nil, env, exists, committed, 5.seconds)

    test("bob's third concurrent check answers 429 with TooManyChecksRefusal and Retry-After, and its probe is never called") {
        assert(DestinationTargets.TooManyChecksRefusal == TooManyText)
        assert(DestinationTargets.RetryAfterSeconds == "5")
        val parked = new Parked(bob.label)
        try {
            val probes = new AtomicInteger(0)
            val reads = new AtomicInteger(0)
            val probe: DestinationTargets.Target => Boolean = _ => { probes.incrementAndGet(); false }
            val read: String => Boolean = _ => { reads.incrementAndGet(); false }
            val t0 = System.nanoTime()
            // object store: the slot is refused at the Unity Catalog state read
            assertTooMany(save(bob, os("bob_o", "fresh"), probe, read), "object store")
            // snowflake: the slot is refused at the probe itself
            assertTooMany(save(bob, snow("bob_s", "fresh_tbl"), probe, read), "snowflake")
            val ms = (System.nanoTime() - t0) / 1000000
            assert(ms < 1000, ms)
            assert(probes.get == 0 && reads.get == 0, (probes.get, reads.get))
        } finally parked.done()
    }

    test("while bob is at his limit, carol's save is checked and saved") {
        val parked = new Parked(bob.label)
        try {
            val probes = new AtomicInteger(0)
            val probe: DestinationTargets.Target => Boolean = _ => { probes.incrementAndGet(); false }
            assert(save(carol, snow("carol_s", "carol_tbl"), probe).isEmpty)
            assert(save(carol, os("carol_o", "carol_fresh"), probe).isEmpty)
            assert(probes.get == 2)
            // and an existing target is still refused for her with the 400
            val taken = save(carol, os("carol_t", "taken"), t => t.key.parts.last == "taken")
            assert(taken.get.getStatusCode.value == 400, taken)
        } finally parked.done()
    }

    test("bob saves again once his checks end") {
        val parked = new Parked(bob.label)
        val probes = new AtomicInteger(0)
        val probe: DestinationTargets.Target => Boolean = _ => { probes.incrementAndGet(); false }
        try assertTooMany(save(bob, snow("bob_s", "fresh_tbl"), probe), "at the limit")
        finally parked.done()
        assert(save(bob, snow("bob_s", "fresh_tbl"), probe).isEmpty)
        assert(probes.get == 1)
        assert(eventually(DestinationProbe.inFlight(bob.label) == 0))
    }

    test("429 is never 'absent': nothing is returned as None when the limit is hit, for save, restore and rename") {
        val owner = restricted("bob", "own")
        assert(owner.label == bob.label)
        val parked = new Parked(bob.label)
        try {
            val probes = new AtomicInteger(0)
            val probe: DestinationTargets.Target => Boolean = _ => { probes.incrementAndGet(); false }
            // save (the eight-argument form and the seven-argument production form)
            assertTooMany(save(bob, snow("bob_s", "fresh_tbl"), probe), "save")
            assertTooMany(
                PipelineAPIController.existingDestinationRefusal(req(bob), snow("bob_s", "fresh_tbl"), null, Nil, env, probe, _ => false),
                "save (default deadline)"
            )
            // version restore of a deleted pipeline onto a fresh table
            assertTooMany(
                EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snow("bob_p", "fresh_tbl").copy(version = 2), null, Nil, env, probe),
                "restore"
            )
            assert(probes.get == 0)
            // rename: the state read as the rename route wires it (StateReadDeadline, the caller's owner slot)
            val reads = new AtomicInteger(0)
            val old = os("never_run", "fresh", "iceberg")
            val r = PipelineAPIController.renameRefusal(
                req(owner),
                old.name,
                "renamed_p",
                old,
                null,
                Nil,
                ucStateExists = false,
                catalogCommitted = n =>
                    DestinationProbe.withDeadline(DestinationProbe.StateReadDeadline, "Unity Catalog state read", owner.label) {
                        reads.incrementAndGet(); false
                    }
            )
            assertTooMany(r, "rename")
            assert(reads.get == 0)
        } finally parked.done()
    }

    test("a rename state read that blocks answers 500 within StateReadDeadline") {
        assert(DestinationProbe.StateReadDeadline == 5.seconds)
        val owner = restricted("bob", "own")
        val old = os("never_run", "fresh", "iceberg")
        val t0 = System.nanoTime()
        val r = PipelineAPIController.renameRefusal(
            req(owner),
            old.name,
            "renamed_p",
            old,
            null,
            Nil,
            ucStateExists = false,
            catalogCommitted = n =>
                DestinationProbe.withDeadline(DestinationProbe.StateReadDeadline, "Unity Catalog state read", owner.label) {
                    try { Thread.sleep(60000); true }
                    catch { case _: InterruptedException => true }
                }
        )
        val ms = (System.nanoTime() - t0) / 1000000
        assert(r.isDefined && r.get.getStatusCode.value == 500, r)
        assert(ms < DestinationProbe.StateReadDeadline.toMillis + 2000, ms)
        assert(eventually(DestinationProbe.inFlight(owner.label) == 0))
    }

    test("a rename over the limit answers 429") {
        val owner = restricted("bob", "own")
        val parked = new Parked(owner.label)
        try {
            val old = os("never_run", "fresh")
            val r = PipelineAPIController.renameRefusal(
                req(owner),
                old.name,
                "renamed_q",
                old,
                null,
                Nil,
                ucStateExists = false,
                catalogCommitted = n => DestinationProbe.withDeadline(DestinationProbe.StateReadDeadline, "Unity Catalog state read", owner.label)(false)
            )
            assertTooMany(r, "rename")
        } finally parked.done()
        // and once the checks end the same rename goes ahead
        val old = os("never_run", "fresh")
        assert(
            PipelineAPIController
                .renameRefusal(
                    req(owner),
                    old.name,
                    "renamed_q",
                    old,
                    null,
                    Nil,
                    ucStateExists = false,
                    catalogCommitted = n => DestinationProbe.withDeadline(DestinationProbe.StateReadDeadline, "Unity Catalog state read", owner.label)(false)
                )
                .isEmpty
        )
    }

    test("unrestricted editor, full key, scoped key and no identity take no slot and start no probe") {
        // even with their labels at the limit, unrestricted callers are never refused with 429
        val labels = Seq(editor.label, fullKey.label, salesKey.label)
        val parked = labels.map(l => new Parked(l))
        try {
            val probes = new AtomicInteger(0)
            val reads = new AtomicInteger(0)
            val probe: DestinationTargets.Target => Boolean = _ => { probes.incrementAndGet(); false }
            val read: String => Boolean = _ => { reads.incrementAndGet(); false }
            for (rk <- Seq(editor, fullKey, salesKey, null); c <- Seq(os("x_o", "fresh", "iceberg"), snow("x_s", "fresh_tbl"))) {
                assert(save(rk, c, probe, read).isEmpty, String.valueOf(rk))
                assert(EntityVersionAPIController.pipelineRestoreRefusal(req(rk), c.copy(version = 2), null, Nil, env, probe).isEmpty, String.valueOf(rk))
            }
            assert(probes.get == 0 && reads.get == 0, (probes.get, reads.get))
            labels.foreach(l => assert(DestinationProbe.inFlight(l) == DestinationProbe.MaxPerOwner, l))
        } finally parked.foreach(_.done())
        // and with nothing parked they leave no slot behind
        val probe: DestinationTargets.Target => Boolean = _ => false
        for (rk <- Seq(editor, fullKey, salesKey)) {
            assert(save(rk, snow("x_s", "fresh_tbl"), probe).isEmpty)
            assert(DestinationProbe.inFlight(rk.label) == 0, rk.label)
        }
    }
}
