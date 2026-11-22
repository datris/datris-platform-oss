package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Tap version restore, what is written under the lock
  * (plans/stories/tap-version-restore-validates.md, lead ruling 5): the re-check
  * accepts a fresh tap whose only change is run, retry or test state, so the
  * write takes those fields from the tap read under the lock, never from the
  * earlier read, and a run that finished in between is not rolled back. */
class TapVersionRestoreWriteSpec extends AnyFunSuite {

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    /** The live tap the restore decided on. */
    private val decided = TapConfig(
        name = "t",
        description = "live",
        targetPipeline = "p",
        catalog = "C",
        createdByKeyLabel = "owner",
        createdAt = "2026-01-01 00:00:00",
        enabled = true,
        lastRunStatus = "failure",
        lastRunTime = "2026-11-19 01:00:00",
        lastRunRecordCount = 1,
        lastRunError = "boom",
        lastRunDataType = "csv",
        lastRunColumns = jl("a"),
        lastRunTrigger = "cron",
        lastRunRetrySafe = true,
        retryCount = 1,
        lastTestRunStatus = "success",
        lastTestRunTime = "2026-11-18 00:00:00",
        lastTestRunRecordCount = 3,
        lastTestRunError = null,
        lastTestRunDataType = "csv",
        lastTestRunColumns = jl("a"),
        lastTestRunScriptId = "id-1"
    )

    /** The config the save path checked: the snapshot's definition with the
      * decided tap's live fields, plus what the core sets itself. */
    private val checked = EntityVersionAPIController
        .tapRestoreCandidate("t", decided.copy(description = "restored", cronExpression = "0 0 * * * ?", dependsOn = jl("x")), decided)
        .copy(updatedAt = "2026-11-19 02:00:00", version = 4, inferredDependsOn = jl("y"))

    test("the write takes run, retry and test state from the tap read under the lock") {
        // A run (and a retry) finished between the decision and the lock.
        val fresh = decided.copy(
            lastRunStatus = "success",
            lastRunTime = "2026-11-19 01:30:00",
            lastRunRecordCount = 42,
            lastRunError = null,
            lastRunDataType = "json",
            lastRunColumns = jl("b"),
            lastRunTrigger = "manual",
            lastRunRetrySafe = false,
            retryCount = 0,
            lastTestRunStatus = "failure",
            lastTestRunTime = "2026-11-19 01:20:00",
            lastTestRunRecordCount = 0,
            lastTestRunError = "test failed",
            lastTestRunDataType = "xml",
            lastTestRunColumns = jl("c"),
            lastTestRunScriptId = "id-2"
        )
        // The re-check accepts it (run and test state are not guard state).
        assert(EntityVersionAPIController.sameTapGuardState(decided, fresh))
        val written = EntityVersionAPIController.tapRestoreWriteConfig(checked, fresh)
        assert(
            written == checked.copy(
                lastRunStatus = fresh.lastRunStatus,
                lastRunTime = fresh.lastRunTime,
                lastRunRecordCount = fresh.lastRunRecordCount,
                lastRunError = fresh.lastRunError,
                lastRunDataType = fresh.lastRunDataType,
                lastRunColumns = fresh.lastRunColumns,
                lastRunTrigger = fresh.lastRunTrigger,
                lastRunRetrySafe = fresh.lastRunRetrySafe,
                retryCount = fresh.retryCount,
                lastTestRunStatus = fresh.lastTestRunStatus,
                lastTestRunTime = fresh.lastTestRunTime,
                lastTestRunRecordCount = fresh.lastTestRunRecordCount,
                lastTestRunError = fresh.lastTestRunError,
                lastTestRunDataType = fresh.lastTestRunDataType,
                lastTestRunColumns = fresh.lastTestRunColumns,
                lastTestRunScriptId = fresh.lastTestRunScriptId
            ),
            s"$written"
        )
        // The definition and what the core set are the checked config's.
        assert(written.description == "restored" && written.cronExpression == "0 0 * * * ?" && written.dependsOn == jl("x"))
        assert(written.updatedAt == checked.updatedAt && written.version == checked.version && written.inferredDependsOn == jl("y"))
    }

    test("an unchanged fresh tap writes the checked config; a re-created tap (fresh null) writes it as checked") {
        assert(EntityVersionAPIController.tapRestoreWriteConfig(checked, decided) == checked)
        val created = checked.copy(createdByKeyLabel = "caller", lastTestRunStatus = null, lastTestRunScriptId = null)
        assert(EntityVersionAPIController.tapRestoreWriteConfig(created, null) eq created)
    }

    test("wiring: the restore writes tapRestoreWriteConfig(stamped, fresh) under the lock, after the re-check") {
        val rel = "src/main/scala/ai/datris/api/TapAPIController.scala"
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        val src = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
        val lock = src.indexOf("CatalogOps.withCatalogLock {", src.indexOf("def saveTapCore("))
        val fresh = src.indexOf("val fresh = io.readTap(", lock)
        val recheck = src.indexOf(".tapRestoreRecheck(", fresh)
        val write = src.indexOf("io.writeVersioned(EntityVersionAPIController.tapRestoreWriteConfig(stamped, fresh)", recheck)
        assert(lock > 0 && fresh > lock && recheck > fresh && write > recheck, s"$lock $fresh $recheck $write")
    }
}
