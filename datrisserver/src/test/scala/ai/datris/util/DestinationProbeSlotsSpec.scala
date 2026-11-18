package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext, User, UserContext}
import org.mockito.Mockito.mock
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration._

/** Story: Catalog-scoped user access: one restricted user cannot use up the
  * new-destination check for everyone else. Per-owner slots on the probe
  * pool: `withDeadline(timeout, what, owner)` takes a slot before submit and
  * the worker frees it when the body really ends. Every parked body is
  * released at the end of its test, so the pool is left idle. */
class DestinationProbeSlotsSpec extends AnyFunSuite {

    private def eventually(cond: => Boolean, ms: Long = 10000): Boolean = {
        val until = System.currentTimeMillis() + ms
        while (!cond && System.currentTimeMillis() < until) Thread.sleep(20)
        cond
    }

    /** `n` bodies of `owner` (None: the ownerless form) parked on probe
      * workers until the returned latch is opened. */
    private final class Parked(owner: Option[String], n: Int) {
        val release = new CountDownLatch(1)
        private val started = new CountDownLatch(n)
        private val callers: ExecutorService = Executors.newFixedThreadPool(n)
        for (_ <- 1 to n)
            callers.submit(new Runnable {
                def run(): Unit = {
                    def body: Boolean = { started.countDown(); release.await(60, TimeUnit.SECONDS) }
                    owner match {
                        case Some(o) => DestinationProbe.withDeadline(60.seconds, "parked", o)(body)
                        case None => DestinationProbe.withDeadline(60.seconds, "parked")(body)
                    }
                }
            })
        assert(started.await(10, TimeUnit.SECONDS), "the parked bodies did not all start")

        def done(): Unit = {
            release.countDown()
            callers.shutdown()
            assert(callers.awaitTermination(30, TimeUnit.SECONDS))
            owner.foreach(o => assert(eventually(DestinationProbe.inFlight(o) == 0), o))
        }
    }

    test("an owner with MaxPerOwner bodies parked gets TooManyChecks at once, and the body is never started") {
        assert(DestinationProbe.MaxPerOwner == 2)
        val parked = new Parked(Some("session:slot_a"), DestinationProbe.MaxPerOwner)
        try {
            assert(DestinationProbe.inFlight("session:slot_a") == DestinationProbe.MaxPerOwner)
            val ran = new AtomicBoolean(false)
            val t0 = System.nanoTime()
            intercept[DestinationProbe.TooManyChecks] {
                DestinationProbe.withDeadline(30.seconds, "third", "session:slot_a") { ran.set(true); true }
            }
            val ms = (System.nanoTime() - t0) / 1000000
            assert(ms < 1000, ms)
            assert(!ran.get, "the refused body was started")
            assert(DestinationProbe.inFlight("session:slot_a") == DestinationProbe.MaxPerOwner)
        } finally parked.done()
    }

    test("another owner, and the ownerless form, still run while one owner is at its limit") {
        val parked = new Parked(Some("session:slot_b"), DestinationProbe.MaxPerOwner)
        try {
            assert(DestinationProbe.withDeadline(5.seconds, "other owner", "session:slot_other")(8) == 8)
            assert(DestinationProbe.withDeadline(5.seconds)(7) == 7)
            assert(DestinationProbe.withDeadline(5.seconds, "ownerless")(9) == 9)
        } finally parked.done()
    }

    test("a body abandoned at the deadline keeps its slot until the worker really ends, then frees it") {
        val open = new CountDownLatch(1)
        val ended = new CountDownLatch(1)
        try {
            intercept[DestinationProbe.DeadlineExceeded] {
                DestinationProbe.withDeadline(200.millis, "stubborn", "session:slot_c") {
                    // ignores the interrupt until the latch opens
                    var waiting = true
                    while (waiting)
                        try { open.await(); waiting = false }
                        catch { case _: InterruptedException => }
                    ended.countDown()
                    true
                }
            }
            Thread.sleep(300)
            assert(DestinationProbe.inFlight("session:slot_c") == 1, "the abandoned worker's slot was freed before it ended")
        } finally open.countDown()
        assert(ended.await(10, TimeUnit.SECONDS))
        assert(eventually(DestinationProbe.inFlight("session:slot_c") == 0))
    }

    test("a slot is freed when the body throws, and when the pool rejects the submit") {
        val boom = new IllegalStateException("probe failed")
        assert(intercept[IllegalStateException](DestinationProbe.withDeadline(5.seconds, "throws", "session:slot_d")(throw boom)) eq boom)
        assert(eventually(DestinationProbe.inFlight("session:slot_d") == 0))

        // every worker busy (ownerless): the owner's submit is rejected and its slot freed at once
        val full = new Parked(None, 16)
        try {
            intercept[RejectedExecutionException](DestinationProbe.withDeadline(5.seconds, "rejected", "session:slot_e")(true))
            assert(DestinationProbe.inFlight("session:slot_e") == 0)
        } finally full.done()
        // the pool is usable again, and the owner too
        assert(DestinationProbe.withDeadline(5.seconds, "after", "session:slot_e")(5) == 5)
        assert(eventually(DestinationProbe.inFlight("session:slot_e") == 0))
    }

    test("inFlight holds no entry for an idle owner") {
        assert(DestinationProbe.inFlight("session:never_seen") == 0)
        val owner = "session:slot_f"
        assert(DestinationProbe.withDeadline(5.seconds, "one", owner)(1) == 1)
        intercept[RuntimeException](DestinationProbe.withDeadline(5.seconds, "two", owner)(throw new RuntimeException("x")))
        assert(eventually(DestinationProbe.inFlight(owner) == 0))
        // the in-flight map itself (whatever its field name) keeps no key for the idle owner
        val maps = DestinationProbe.getClass.getDeclaredFields.toSeq
            .filter(f => classOf[java.util.Map[_, _]].isAssignableFrom(f.getType))
            .map { f => f.setAccessible(true); f.get(DestinationProbe).asInstanceOf[java.util.Map[_, _]] }
        assert(maps.nonEmpty, "no in-flight map found on DestinationProbe")
        assert(maps.forall(m => !m.containsKey(owner)), maps)
    }

    test("the owner form carries the tenant but never the session user to the worker") {
        val env = mock(classOf[DatrisEnvironment])
        val seenTenant = new AtomicReference[Option[DatrisEnvironment]]()
        val seenUser = new AtomicReference[Option[User]]()
        try {
            TenantContext.set(env)
            UserContext.set(User("bob", "h", User.RoleEditor, null, null, null, restricted = true))
            DestinationProbe.withDeadline(5.seconds, "tenant", "session:bob") {
                seenTenant.set(TenantContext.get())
                seenUser.set(UserContext.get())
            }
        } finally {
            TenantContext.clear()
            UserContext.clear()
        }
        assert(seenTenant.get.exists(_ eq env))
        assert(seenUser.get.isEmpty)
        for (_ <- 1 to 5) {
            DestinationProbe.withDeadline(5.seconds, "after", "session:bob") {
                seenTenant.set(TenantContext.get())
                seenUser.set(UserContext.get())
            }
            assert(seenTenant.get.isEmpty)
            assert(seenUser.get.isEmpty)
        }
        assert(eventually(DestinationProbe.inFlight("session:bob") == 0))
    }
}
