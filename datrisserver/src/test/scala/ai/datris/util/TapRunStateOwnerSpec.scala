package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Change a tap's owner (plans/stories/tap-change-owner.md),
  * Acceptance bullet 5 (`TapRunStateOwnerSpec`) and rule 6 (O6): a run or a
  * test that started before an owner change finishes as it started, but its
  * status writes do not put the previous owner back. Each run and test status
  * write re-reads the stored tap and keeps the stored owner; every other
  * field is the run's. When the re-read fails, the status write still happens
  * exactly as it did before this story (the tap as the run holds it).
  *
  * Names the implementation must provide:
  * {{{
  * object TapConfigIO {
  *   /** `toWrite` with `live`'s owner when `live` is non-null and its owner
  *     * differs; otherwise `toWrite` itself. Pure. */
  *   def keepLiveOwner(toWrite: TapConfig, live: TapConfig): TapConfig
  *
  *   /** A run or test status write: re-read the tap by name (`read`), keep
  *     * its owner ([[keepLiveOwner]]), write (`write`). A re-read that throws
  *     * writes `tap` as given. */
  *   def writeRunStateWith(tap: TapConfig, read: String => TapConfig, write: TapConfig => Unit): Unit
  *
  *   /** [[writeRunStateWith]] over the live tap store; used by
  *     * TapRunner.ProductionEffects.writeTap and the two test-stamp writes. */
  *   def writeRunState(tap: TapConfig): Unit
  * }
  * }}}
  */
class TapRunStateOwnerSpec extends AnyFunSuite {

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    /** The tap as a run holds it from its start (owner before the change),
      * with the run's status stamped. */
    private val runWrite = TapConfig(
        name = "orders-feed",
        description = "d",
        scriptPath = "tap-scripts/orders-feed.py",
        targetPipeline = "sales-orders",
        cronExpression = "0 0 * * * ?",
        catalog = "Sales",
        createdByKeyLabel = "old-agent",
        updatedAt = "2026-11-20 12:00:00",
        version = 4,
        lastRunStatus = "success",
        lastRunTime = "2026-11-22 03:00:00",
        lastRunRecordCount = 12,
        lastRunColumns = jl("a"),
        lastRunTrigger = "cron",
        retryCount = 0,
        lastRunUsedCallback = true,
        lastTestRunStatus = "success",
        lastTestRunScriptId = "sha-1"
    )

    /** The stored tap after an administrator changed its owner mid-run. */
    private val stored = runWrite.copy(createdByKeyLabel = "new-agent", lastRunStatus = "running", lastRunTime = "2026-11-22 02:59:00")

    // ================================================================ keepLiveOwner

    test("keepLiveOwner keeps the stored owner when it differs, and leaves every other field of the write") {
        val kept = TapConfigIO.keepLiveOwner(runWrite, stored)
        assert(kept == runWrite.copy(createdByKeyLabel = "new-agent"), kept.toString)
    }

    test("keepLiveOwner returns the write unchanged when nothing is stored") {
        assert(TapConfigIO.keepLiveOwner(runWrite, null) eq runWrite)
    }

    test("keepLiveOwner returns the write unchanged when the stored owner is the same") {
        val same = TapConfigIO.keepLiveOwner(runWrite, stored.copy(createdByKeyLabel = "old-agent"))
        assert(same == runWrite, same.toString)
    }

    test("keepLiveOwner takes nothing but the owner from the stored tap") {
        val other = stored.copy(
            description = "changed",
            enabled = false,
            catalog = "Ops",
            cronExpression = null,
            updatedAt = "2026-11-22 03:30:00",
            version = 5,
            retryCount = 3,
            lastTestRunStatus = "failure"
        )
        val kept = TapConfigIO.keepLiveOwner(runWrite, other)
        assert(kept == runWrite.copy(createdByKeyLabel = "new-agent"), kept.toString)
    }

    // ================================================================ writeRunStateWith

    private class Tables(initial: TapConfig, failRead: Boolean = false) {
        var row: TapConfig = initial
        val reads = mutable.Buffer[String]()
        val writes = mutable.Buffer[TapConfig]()
        val read: String => TapConfig = n => {
            reads += n
            if (failRead) throw new IllegalStateException("store down") else if (row != null && row.name == n) row else null
        }
        val write: TapConfig => Unit = t => { writes += t; row = t }
    }

    test("a run that started before an owner change does not put the previous owner back; its status is written") {
        val t = new Tables(stored)
        TapConfigIO.writeRunStateWith(runWrite, t.read, t.write)
        assert(t.reads == Seq("orders-feed"), t.reads.toString)
        assert(t.writes == Seq(runWrite.copy(createdByKeyLabel = "new-agent")), t.writes.toString)
        assert(t.row.lastRunStatus == "success" && t.row.createdByKeyLabel == "new-agent")
    }

    test("a test-stamp write that started before an owner change keeps the new owner as well") {
        val testStamp = runWrite.copy(lastTestRunStatus = "failure", lastTestRunError = "boom", lastTestRunScriptId = null)
        val t = new Tables(stored)
        TapConfigIO.writeRunStateWith(testStamp, t.read, t.write)
        assert(t.writes == Seq(testStamp.copy(createdByKeyLabel = "new-agent")), t.writes.toString)
    }

    test("with no owner change the status write is exactly the run's tap") {
        val t = new Tables(stored.copy(createdByKeyLabel = "old-agent"))
        TapConfigIO.writeRunStateWith(runWrite, t.read, t.write)
        assert(t.writes == Seq(runWrite), t.writes.toString)
    }

    test("when the re-read fails, the status write still happens with the run's tap, as before") {
        val t = new Tables(stored, failRead = true)
        TapConfigIO.writeRunStateWith(runWrite, t.read, t.write)
        assert(t.writes == Seq(runWrite), t.writes.toString)
    }

    test("when the tap is no longer stored, the status write is the run's tap, as before") {
        val t = new Tables(null)
        TapConfigIO.writeRunStateWith(runWrite, t.read, t.write)
        assert(t.writes == Seq(runWrite), t.writes.toString)
    }
}
