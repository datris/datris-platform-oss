package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{Callable, Future, RejectedExecutionException}
import scala.concurrent.duration._

/** Catalog-scoped user access, capacity review note 7: a destination check
  * whose submit fails in any way (a rejection, or an Error such as
  * `OutOfMemoryError: unable to create native thread` from starting the
  * worker) frees the caller's slot at once, so repeated failures never leave
  * the caller at 429 until a restart. */
class DestinationProbeSubmitFailureSpec extends AnyFunSuite {

    private def failing[T](e: Throwable): Callable[T] => Future[T] = _ => throw e

    test("an Error thrown by the submit frees the slot and propagates") {
        val owner = "session:submit-oom-" + System.nanoTime()
        (1 to DestinationProbe.MaxPerOwner + 2).foreach { _ =>
            val thrown = intercept[OutOfMemoryError](
                DestinationProbe.withDeadlineSubmitting[Int](5.seconds, "probe", owner, failing(new OutOfMemoryError("unable to create native thread")))(1)
            )
            assert(thrown.getMessage.contains("native thread"))
            assert(DestinationProbe.inFlight(owner) == 0)
        }
        // the caller is not locked out afterwards
        assert(DestinationProbe.withDeadline(5.seconds, "probe", owner)(42) == 42)
        assert(DestinationProbe.inFlight(owner) == 0)
    }

    test("a rejection or any other exception from the submit frees the slot too") {
        val owner = "session:submit-reject-" + System.nanoTime()
        Seq(new RejectedExecutionException("full"), new IllegalStateException("shut down")).foreach { e =>
            intercept[RuntimeException](DestinationProbe.withDeadlineSubmitting[Int](5.seconds, "probe", owner, failing(e))(1))
            assert(DestinationProbe.inFlight(owner) == 0, e)
        }
    }

    test("the body never runs when the submit failed") {
        val owner = "session:submit-norun-" + System.nanoTime()
        var ran = false
        intercept[OutOfMemoryError](
            DestinationProbe.withDeadlineSubmitting[Unit](5.seconds, "probe", owner, failing(new OutOfMemoryError("x"))) { ran = true }
        )
        assert(!ran)
    }

    test("ProbeS3ASettings also turns off the S3A throttle retries") {
        val m = DestinationProbe.ProbeS3ASettings("b1").toMap
        assert(m.get("fs.s3a.bucket.b1.retry.throttle.limit").contains("0"), m)
    }
}
