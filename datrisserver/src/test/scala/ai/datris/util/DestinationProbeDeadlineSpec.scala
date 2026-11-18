package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext, User, UserContext}
import org.mockito.Mockito.mock
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * E2E finding: the destination check is bounded by a deadline. */
class DestinationProbeDeadlineSpec extends AnyFunSuite {

    test("a body that blocks past the deadline is abandoned promptly, and its worker is interrupted") {
        val interrupted = new CountDownLatch(1)
        val started = System.nanoTime()
        val e = intercept[DestinationProbe.DeadlineExceeded] {
            DestinationProbe.withDeadline(200.millis, "test probe") {
                try { Thread.sleep(60000); true }
                catch { case _: InterruptedException => interrupted.countDown(); false }
            }
        }
        val tookMs = (System.nanoTime() - started) / 1000000
        assert(tookMs < 5000, tookMs)
        assert(e.getMessage.contains("test probe"))
        assert(interrupted.await(5, TimeUnit.SECONDS), "the abandoned worker was not interrupted")
    }

    test("a body that returns in time is unaffected; its failure is rethrown as is") {
        assert(DestinationProbe.withDeadline(5.seconds)(42) == 42)
        val boom = new IllegalStateException("probe failed")
        assert(intercept[IllegalStateException](DestinationProbe.withDeadline(5.seconds)(throw boom)) eq boom)
        // no time left: refused without starting anything
        val ran = new java.util.concurrent.atomic.AtomicBoolean(false)
        intercept[DestinationProbe.DeadlineExceeded](DestinationProbe.withDeadline(Duration.Zero) { ran.set(true); 1 })
        assert(!ran.get)
    }

    test("the worker gets the request's tenant but never its session user, and keeps neither afterwards") {
        val env = mock(classOf[DatrisEnvironment])
        val seenTenant = new AtomicReference[Option[DatrisEnvironment]]()
        val seenUser = new AtomicReference[Option[User]]()
        try {
            TenantContext.set(env)
            UserContext.set(User("bob", "h", User.RoleEditor, null, null, null, restricted = true))
            DestinationProbe.withDeadline(5.seconds) {
                seenTenant.set(TenantContext.get())
                seenUser.set(UserContext.get())
            }
        } finally {
            TenantContext.clear()
            UserContext.clear()
        }
        assert(seenTenant.get.exists(_ eq env))
        assert(seenUser.get.isEmpty)
        // later work on the (possibly same) pooled thread sees nothing left behind
        for (_ <- 1 to 5) {
            DestinationProbe.withDeadline(5.seconds) {
                seenTenant.set(TenantContext.get())
                seenUser.set(UserContext.get())
            }
            assert(seenTenant.get.isEmpty)
            assert(seenUser.get.isEmpty)
        }
    }
}
