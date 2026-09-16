package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

/** Per-pipeline write lock for the object store loader
  *  (plans/stories/iceberg-loader-reader-lineage.md, step 5).
  *
  *  Plan §12 Q1 was answered YES in story 1: runs of one pipeline can overlap
  *  (ScheduledBatchTasks.startJobs gates only on destination.database.table),
  *  so writes to one objectstore destination must be serialised — Iceberg
  *  commits would otherwise race on metadata, and parquet/ORC appends would
  *  interleave. The lock is keyed on the pipeline name and must not serialise
  *  unrelated pipelines.
  *
  *  Expected seam (companion object of the loader, no JobContext needed):
  *
  *  {{{
  *  object SparkObjectStoreLoader {
  *      private[util] def withPipelineWriteLock[T](pipelineName: String)(body: => T): T
  *  }
  *  }}}
  */
class SparkObjectStoreLoaderSpec extends AnyFunSuite {

    private def onThread[T](body: => T): java.util.concurrent.Future[T] = {
        val ex = Executors.newSingleThreadExecutor()
        val task: Callable[T] = () => body
        try ex.submit(task)
        finally ex.shutdown()
    }

    test("withPipelineWriteLock serialises concurrent writes of the same pipeline") {
        val inside = new AtomicInteger(0)
        val maxInside = new AtomicInteger(0)
        val runs = new AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        val tasks = (1 to 4).map { _ =>
            val task: Callable[Unit] = () => {
                (1 to 3).foreach { _ =>
                    SparkObjectStoreLoader.withPipelineWriteLock("orders") {
                        val now = inside.incrementAndGet()
                        maxInside.accumulateAndGet(now, Math.max)
                        Thread.sleep(20)
                        inside.decrementAndGet()
                        runs.incrementAndGet()
                    }
                }
            }
            pool.submit(task)
        }
        tasks.foreach(_.get(30, TimeUnit.SECONDS))
        pool.shutdown()
        assert(runs.get() == 12)
        assert(maxInside.get() == 1, s"two writers of the same pipeline overlapped (max concurrent = ${maxInside.get()})")
    }

    test("withPipelineWriteLock does not block a different pipeline") {
        val aHeld = new CountDownLatch(1)
        val releaseA = new CountDownLatch(1)

        val a = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("pipeline-a") {
                aHeld.countDown()
                assert(releaseA.await(10, TimeUnit.SECONDS), "test harness never released pipeline-a")
            }
        }
        assert(aHeld.await(10, TimeUnit.SECONDS), "pipeline-a never acquired its lock")

        // While A's lock is held, B must acquire promptly.
        val b = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("pipeline-b") { "b-done" }
        }
        try assert(b.get(2, TimeUnit.SECONDS) == "b-done")
        catch {
            case _: java.util.concurrent.TimeoutException =>
                releaseA.countDown()
                fail("pipeline-b was blocked behind pipeline-a's write lock — the lock is not per-pipeline")
        }
        releaseA.countDown()
        a.get(10, TimeUnit.SECONDS)
    }

    test("withPipelineWriteLock releases the lock when the body throws") {
        intercept[IllegalStateException] {
            SparkObjectStoreLoader.withPipelineWriteLock("flaky") { throw new IllegalStateException("boom") }
        }
        val next = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("flaky") { "recovered" }
        }
        assert(next.get(2, TimeUnit.SECONDS) == "recovered", "lock leaked after an exception in the body")
    }

    test("withPipelineWriteLock returns the body's value") {
        assert(SparkObjectStoreLoader.withPipelineWriteLock("p") { 42 } == 42)
    }
}
