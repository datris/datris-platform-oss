package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.StrictAuditGate
import ai.datris.model.{DatrisEnvironment, TapConfig, TenantContext}
import org.scalatest.funsuite.AnyFunSuite

import java.util.Date
import scala.collection.mutable

/** Strict evidence mode (plans/stories/strict-evidence-mode.md): no scheduled
  * tap fires while the audit log is not accepting entries; due taps fire on
  * the first tick after it recovers. */
class TapSchedulerStrictSpec extends AnyFunSuite {

    private val dueTap = TapConfig(name = "due", description = "d", targetPipeline = "p", cronExpression = "0 * * * * ?")

    test("a due tap is skipped while the strict audit gate is closed and fires once it reopens") {
        val fired = mutable.ArrayBuffer[String]()
        TapScheduler.fireOverride = (t: TapConfig) => fired.synchronized { fired += t.name; () }
        val gate = new StrictAuditGate
        try {
            gate.close()
            try {
                TapScheduler.checkSchedules(Seq(dueTap), new Date())
                assert(fired.isEmpty, "nothing fires while audited actions are refused")
            } finally gate.reopen()

            val saved = DatrisEnvironment.values
            val e = gate.env(strict = true)
            DatrisEnvironment.values = e
            TenantContext.set(e)
            try TapScheduler.checkSchedules(Seq(dueTap), new Date())
            finally { TenantContext.clear(); DatrisEnvironment.values = saved }
            assert(fired.toList == List("due"), "the due tap fires on the first open tick")
        } finally TapScheduler.fireOverride = null
    }
}
